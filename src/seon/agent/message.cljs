(ns seon.agent.message
  "Message model — THE single write path for `:seon.agent.message` rows.
   The keyword namespace matches the code namespace. Owns:
     - the `:seon.agent.message/*` attr + entity-kind schemas
     - the `:seon.user` entity schema + `user-ref` (the default `to`
       target — seeded at boot by seon.client)
     - `message!` — fully-formed storage, boundary defaulting, the
       blank-content refusal, hop derivation (`outbound-hops`), the
       concise success value
     - the two agent-facing functions, thin wrappers over `message!` — the
       agent reaches them through the `message/` alias on its home ns
       (`(message/user …)` / `(message/agent …)`):
         `user`  — from = me (the ALS agent), to = the one human user
         `agent` — from = me, to = [agent-id]. The one `message!` boundary
                   refuses self-addressing for every caller.

   The wake trigger itself lives in [[seon.agent.loop]], while this
   message-data namespace owns the pure waking-inbound rule. `seon.agent`
   re-exports `message!`/`user-ref` on the face."
  (:require
    [clojure.string :as str]
    [seon.agent.message.internal :as internal]
    [seon.db :as db]
    [seon.db.id :as db.id]
    [seon.db.protocol :as protocol]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]
    [seon.warn :as warn]))

;; Every stored message is FULLY FORMED — from + to + content + at + id
;; + hops. Identity is the ref (`:seon.agent.message/from` points at the
;; sender entity — a `:seon.user/id` or `:seon.agent/id` entity).
;; "My conversation" is DERIVED: from = me OR to ∋ me.
(schema/register!
  :seon.agent.message/id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! :seon.agent.message/content :string)
(schema/register! :seon.agent.message/from    :seon.db/ref)
(schema/register! :seon.agent.message/to      [:vector :seon.db/ref])
(schema/register! :seon.agent.message/at      :inst)
;; Ping-pong guard: 0 from the user; an agent send carries the SAME
;; {me,peer}-pair's prior depth + 1 (per-peer, reset at each human
;; message — see `internal/outbound-hops`); the wake trigger refuses
;; past `seon.warn/hop-cap`.
(schema/register! :seon.agent.message/hops    :int)
;; Provenance for the wake gate: :human / :agent / :core (a substrate
;; nudge that must never wake an idle agent). Derived in message!.
(schema/register! :seon.agent.message/origin  [:enum :human :agent :core])

;; The user is a REAL entity — ONE `:seon.user/id` row seeded at boot
;; (identity upsert, idempotent — same pattern as agent entities). All
;; message refs are uniform; later home for user prefs/memory.
(schema/register! :seon.user/id         [:string {:seon.db/identity true}])

(schema/register! :seon.user
  [:map {:seon.db/entity true}
   [:seon.user/id :seon.user/id]])

(def user-ref
  "Lookup ref of THE user entity (one human for now). Seeded at boot by
   seon.client; the default `:seon.agent.message/to` target."
  [:seon.user/id "user"])

;; Entity-kind :map schema — required attrs reflect what message!
;; (the single writer) populates unconditionally. See the entity-kind
;; block comment in seon.agent for the discovery/render mechanism.
(schema/register! :seon.agent.message
  [:map {:seon.db/entity   true
         :seon.render/ai   'seon.handlers.message/render-ai
         :seon.render/html 'seon.handlers.message/render-html}
   [:seon.agent.message/id      :seon.agent.message/id]
   [:seon.agent.message/from    :seon.agent.message/from]
   [:seon.agent.message/to      :seon.agent.message/to]
   [:seon.agent.message/content :seon.agent.message/content]
   [:seon.agent.message/at      :seon.agent.message/at]
   [:seon.agent.message/hops    :seon.agent.message/hops]
   [:seon.agent.message/origin  :seon.agent.message/origin]])

(schema/register! ::recent-limit [:int {:min 1 :max 200}])
(schema/register! ::recent-request
  [:map {:closed true}
   [:seon.agent/id :string]
   [::recent-limit ::recent-limit]
   [::db/db {:optional true} :seon.db/db]])
(schema/register! ::read-failure
  [:map {:closed true}
   [:seon.error/message :string]
   [:seon.error/data :map]])
(schema/register! ::recent-response [:or [:vector :map] ::read-failure])
(schema/register! ::recent-all-request
  [:map {:closed true}
   [::recent-limit ::recent-limit]
   [::db/db {:optional true} :seon.db/db]])

(def ^:private recent-pull-pattern
  '[* {:seon.agent.message/from [:db/id :seon.user/id :seon.agent/id]
       :seon.agent.message/to   [:db/id :seon.user/id :seon.agent/id]}])

(defn- index-member
  [database attribute value limit]
  {::protocol/operation protocol/index-page-operation
   ::db/db database
   ::protocol/index :avet
   ::protocol/prefix [attribute value]
   ::protocol/direction :reverse
   ::protocol/limit limit
   :datahike.resource/max-result-weight 131072})

(defn- read-failure
  [message data]
  {:seon.error/message message
   :seon.error/data data})

(defn- failed-read?
  [value]
  (and (map? value)
       (string? (:seon.error/message value))))

(defn- ordered-messages
  [messages]
  (->> messages
       (remove nil?)
       (sort-by #(.getTime ^js (:seon.agent.message/at %)))
       vec))

(defn ^:async recent
  "Return one agent's newest messages, oldest first, from bounded ref indexes.

   Conversation membership remains derived (`from = agent OR to contains
   agent`). The two ref attributes are indexed by Datahike, so this reads at
   most `2 × :seon.agent.message/recent-limit` datoms plus that many entity
   pulls; it never scans or sorts the complete message log. Message entities
   are append-only, so descending entity ids are their creation order."
  {:malli/schema [:=> [:cat ::recent-request] ::recent-response]}
  [{agent-id :seon.agent/id limit ::recent-limit :as request}]
  (let [database (or (::db/db request) (await (db/db)))]
    (if (failed-read? database)
      (read-failure "Recent-message database acquisition failed." database)
      (let [indexed
            (await
             (db/execute-many
              {::db/members
               [(index-member database :seon.agent.message/from
                              [:seon.agent/id agent-id] limit)
                (index-member database :seon.agent.message/to
                              [:seon.agent/id agent-id] limit)]
               ::db/max-result-weight 262144}))]
        (if-not (and (= 2 (count (::db/results indexed)))
                     (every? #(true? (::protocol/success? %))
                             (::db/results indexed)))
          (read-failure "Recent-message index read failed." indexed)
          (let [entity-ids
                (->> (::db/results indexed)
                     (mapcat :datahike.index-page/datoms)
                     (map first)
                     distinct
                     (sort >)
                     (take limit)
                     vec)
                messages
                (if (seq entity-ids)
                  (await
                   (db/pull-many
                    {::db/db database
                     ::db/selector recent-pull-pattern
                     ::db/eids entity-ids
                     ::db/max-results limit
                     ::db/max-result-weight 524288}))
                  [])]
            (if (failed-read? messages)
              (read-failure "Recent-message pull failed." messages)
              (ordered-messages messages))))))))

(defn ^:async recent-all
  "Return newest messages across the database, bounded and oldest first."
  {:malli/schema [:=> [:cat ::recent-all-request] ::recent-response]}
  [{limit ::recent-limit :as request}]
  (let [database (or (::db/db request) (await (db/db)))]
    (if (failed-read? database)
      (read-failure "Recent-message database acquisition failed." database)
      (let [indexed
            (await
             (db/index-page
              database
              {::db/index :aevt
               ::db/components [:seon.agent.message/at]
               ::db/direction :reverse
               ::db/limit limit
               ::db/max-result-weight 131072}))]
        (if (failed-read? indexed)
          (read-failure "Recent-message index read failed." indexed)
          (let [entity-ids
                (mapv first (:datahike.index-page/datoms indexed))
                messages
                (if (seq entity-ids)
                  (await
                   (db/pull-many
                    {::db/db database
                     ::db/selector recent-pull-pattern
                     ::db/eids entity-ids
                     ::db/max-results limit
                     ::db/max-result-weight 524288}))
                  [])]
            (if (failed-read? messages)
              (read-failure "Recent-message pull failed." messages)
              (ordered-messages messages))))))))

;; ============================================================
;; message! — the SINGLE write entry point for messages (the functions
;; `user`/`agent` below are thin wrappers over it). Presence of
;; attributes IS the intent; the DB holds only FULLY-FORMED messages
;; (from + to + content + at + id + hops). All defaulting is a
;; message!-boundary liberty, never a storage shape.
;; ============================================================

(schema/register! ::message-request
  [:map
   [::db/db {:optional true} :seon.db/db]
   [:seon.agent.message/content :seon.agent.message/content]
   ;; from defaults to the calling agent's ref via the ALS scope
   ;; ((seon.db/current-agent-id)); the HTTP adapter passes the user
   ;; ref explicitly.
   [:seon.agent.message/from {:optional true} :seon.agent.message/from]
   ;; to accepts ONE ref or a vector of refs (fan-out); defaults to
   ;; THE user. Storage is always the normalized vector.
   [:seon.agent.message/to {:optional true}
    [:or :seon.db/ref [:vector :seon.db/ref]]]
   ;; Provenance override. Absent ⇒ DERIVED from `from` (user ⇒ :human,
   ;; else :agent). A substrate-originated nudge (canvas recovery) passes
   ;; :core explicitly so it can't wake an idle agent. The HTTP/user
   ;; adapter relies on the derived :human; agent sends on the derived
   ;; :agent.
   [:seon.agent.message/origin {:optional true} :seon.agent.message/origin]])

;; Concise success / direct error value: the raw transact tx-report
;; is OFF the agent surface — it taught nothing and carried a misdirected
;; "narrow your query" hint. A committed identity already says it stored;
;; success answers which message and at what hop depth. Failures are direct
;; :seon.error/message values.
(schema/register! ::message-response
  [:or
   [:map
    [:seon.agent.message/id   :seon.agent.message/id]
    [:seon.agent.message/hops :seon.agent.message/hops]]
   [:map [:seon.error/message :string]]])

;; The waking-inbound RULE — ONE source of truth for "this message wakes
;; (and renders as an inbound) for the agent whose eid is `my-eid`". Both
;; the wake gate and the transcript head-render
;; ([[seon.agent.ctx.transcript]]) call these so a message wakes
;; under exactly the rule it renders under — no drift. PUBLIC (cross-ns
;; callers): lives here, the message-data owner, not in `.internal`.

(defn waking-inbound?
  "True iff message map `m` is a WAKING inbound for agent `my-eid`.

   `m` is a pull with its `from` ref carrying `:db/id`. Waking means:
     from ≠ me        — an agent's own writes never re-wake it
     origin ∉ {:core} — a substrate nudge never wakes an idle agent
                        (absent origin = legacy human/agent ⇒ waking).
   The to-check (to ∋ me) is the CALLER's job. The HOP-CAP guard is NOT
   folded in here on purpose: the wake side needs hop-exhausted messages
   to still pass so it can refuse LOUDLY; the transcript adds the hop-cap
   clause itself. See [[hop-live?]]."
  {:malli/schema [:=> [:catn [::m :map] [::my-eid :int]] :boolean]}
  [m my-eid]
  (and (not= my-eid (:db/id (:seon.agent.message/from m)))
       (not= :core (:seon.agent.message/origin m))))

(defn hop-live?
  "True iff message `m`'s hop count is under `seon.warn/hop-cap`.

   Absent hops = 0 ⇒ live. The ping-pong guard, factored out of
   [[waking-inbound?]] so the wake side can keep the loud hop-exhausted
   refusal while the transcript composes `(and (waking-inbound? …)
   (hop-live? …))` to drop a dead-chain message from the rendered log."
  {:malli/schema [:=> [:catn [::m :map]] :boolean]}
  [m]
  (< (or (:seon.agent.message/hops m) 0) warn/hop-cap))

(defn- normalize-recipients
  [to]
  (->> (cond
         (nil? to) [user-ref]
         (and (vector? to) (vector? (first to))) to
         (and (vector? to) (keyword? (first to))) [to]
         (vector? to) to
         :else [to])
       distinct
       vec))

(defn- message-transaction
  "Build the one generated-id message transaction from acquired message data."
  [{:seon.agent.message/keys [content from to origin at send-data]}]
  (let [from-user? (:seon.agent.message/from-user? send-data)
        hops (if from-user?
               0
               (inc (:seon.agent.message/hops send-data)))
        origin (or origin (if from-user? :human :agent))
        agent-tos
        (if (= origin :human)
          (:seon.agent.message/agent-tos send-data)
          [])
        step-allocations
        (mapv
         (fn [idx agent-ref]
           {:seon.agent.message/allocation-key
            (keyword "seon.agent.message" (str "plan-id-" idx))
            :seon.agent.message/agent-ref agent-ref})
         (range)
         agent-tos)
        allocations
        (into
         [{::db.id/key :seon.agent.message/id
           ::db.id/identity-attr :seon.agent.message/id}]
         (map
          (fn [{allocation-key :seon.agent.message/allocation-key}]
            {::db.id/key allocation-key
             ::db.id/identity-attr :my.plan/id}))
         step-allocations)]
    {:seon.agent.message/allocations allocations
     :seon.agent.message/hops hops
     :seon.agent.message/transaction-builder
     (fn [ids]
       (let [message-id (get ids :seon.agent.message/id)
             message-row
             {:seon.agent.message/id message-id
              :seon.agent.message/from from
              :seon.agent.message/to to
              :seon.agent.message/content content
              :seon.agent.message/at at
              :seon.agent.message/hops hops
              :seon.agent.message/origin origin}
             plan-rows
             (mapv
              (fn [{allocation-key :seon.agent.message/allocation-key
                    agent-ref :seon.agent.message/agent-ref}]
                {:my.plan/id (get ids allocation-key)
                 :my.plan/title (internal/clip-title content)
                 :my.plan/status :open
                 :my.plan/created-at at
                 :my.plan/agent agent-ref
                 :my.plan/from from
                 :my.plan/message [:seon.agent.message/id message-id]})
              step-allocations)]
         {:seon.db/tx-data (into [message-row] plan-rows)}))}))

(defn ^:async ^:no-doc message-transaction-for
  "Acquire and build one message transaction at an immutable database value."
  [database {:seon.agent.message/keys [content from to origin]}]
  (let [to (normalize-recipients to)
        send-data (await (internal/acquire-send-data database from to))]
    (if (failed-read? send-data)
      send-data
      (message-transaction
       {:seon.agent.message/content content
        :seon.agent.message/from from
        :seon.agent.message/to to
        :seon.agent.message/origin origin
        :seon.agent.message/at (js/Date.)
        :seon.agent.message/send-data send-data}))))

(defn ^:async message!
  "Send a message; the single entry point for message writes.

   Map-in / map-out; returns a CONCISE domain result, never the raw
   tx-report:
     {:seon.agent.message/id _ :seon.agent.message/hops _}
     {:seon.error/message _}   ; failure (errors are values)

   Defaulting (boundary liberties — the STORED row is always full):
     :seon.agent.message/from — defaults to [:seon.agent/id (current-agent-id)]
                          from the ALS turn scope. No scope + no explicit
                          from → direct error value.
     :seon.agent.message/to   — single ref or vector; defaults to the user.
     hops               — 0 when from = the user; otherwise this
                          {me,recipient}-pair's prior depth + 1 (per-peer
                          ping-pong guard, reset at each human message —
                          `internal/outbound-hops`; distinct delegation
                          rounds do NOT accumulate). The wake trigger
                          refuses past `seon.warn/hop-cap`.

   Blank content is rejected with a direct error — an empty message
   carries nothing; since every message write routes through here, the
   guard kills the class.

   Closed runtime admission returns the existing direct error before
   allocation or transaction. Otherwise a message ALWAYS transacts and is
   delivered — there is no same-turn refusal. Errors are values: if a sibling
   form in the same turn returned an error value the agent may over-claim, but that
   is visible in the transcript and a human follow-up re-wakes the agent.
   Nothing else here blocks a send."
  {:malli/schema [:=> [:cat ::message-request] ::message-response]}
  [{database ::db/db
    :seon.agent.message/keys [content from to origin]}]
  (if-not (admission/available?)
    (:seon/error (admission/unavailable))
    (let [agent-id (db/current-agent-id)
          from     (or from (when agent-id [:seon.agent/id agent-id]))
          to       (normalize-recipients to)]
    (cond
      (or (nil? content) (str/blank? content))
      {:seon.error/message
       (str "message!: blank :seon.agent.message/content refused — a message "
            "with nothing to say must not be stored. Compose the text "
            "first, then send.")}

      (nil? from)
      {:seon.error/message
       (str "message!: no :seon.agent.message/from and no agent-id in scope — "
            "pass from explicitly or call inside (seon.db/with-agent …).")}

      (empty? to)
      {:seon.error/message
       "message!: empty :seon.agent.message/to refused — address at least one recipient."}

      (and origin (not= :core origin))
      {:seon.error/message
       "message!: only :core may override origin; :human/:agent derive from the sender."}

      :else
      (let [database (or database (await (db/db)))]
        (if (failed-read? database)
          database
          (let [transaction
                (await
                 (message-transaction-for
                  database
                  {:seon.agent.message/content content
                   :seon.agent.message/from from
                   :seon.agent.message/to to
                   :seon.agent.message/origin origin}))]
            (if (failed-read? transaction)
              transaction
              (let [env
                    (await
                     (db.id/allocate!
                      {::db/db database
                       ::db.id/allocations
                       (:seon.agent.message/allocations transaction)
                       ::db.id/transaction-builder
                       (:seon.agent.message/transaction-builder transaction)}))
                    msg-id (get-in env [::db.id/ids
                                        :seon.agent.message/id])]
                (if (failed-read? env)
                  env
                  {:seon.agent.message/id msg-id
                   :seon.agent.message/hops
                   (:seon.agent.message/hops transaction)}))))))))))

;; ============================================================
;; The two agent-facing functions. Thin wrappers over `message!` — `from`
;; defaults to the ALS agent inside `message!`, so these only fix `to`.
;; The agent reaches them through the `message/` alias on its home ns:
;; `(message/user "…")` / `(message/agent id "…")`. The one `message!`
;; boundary refuses self-addressing for every caller.
;; Both ride the one injecting wrapper: semantic failures stay ordinary
;; error values; only a shape-invalid call trips the validator, surfaced by
;; the eval boundary as a structured `:seon/error` value — data, never a
;; crash. Both reuse `::message-response`.
;; ============================================================

(defn ^{:async true :seon.fn/agent-facing? true} user
  "Send a message to your human user.

   [[message!]] with `to` := THE one user. `from` is you (the ALS
   agent). Returns `message!`'s concise result.
   This is how you say something to the human watching your REPL:

     (message/user \"done — stored 2 rows\")"
  {:malli/schema [:=> [:catn [::content :string]] ::message-response]}
  [content]
  (await (message! {:seon.agent.message/content content
                    :seon.agent.message/to      [user-ref]})))

(defn ^{:async true :seon.fn/agent-facing? true} agent
  "Send a message to a PEER agent by id.

   `message!` with `to` := `[[:seon.agent/id agent-id]]`. `from` is you
   (the ALS agent). Returns
   `message!`'s concise result. The peer must already exist — find live
   ids with `(db/query '[:find [?id ...] :where [?e :seon.agent/id ?id]])`:

     (message/agent <peer-agent-id> \"heads up: foo depends on qux\")

   The one message boundary refuses sending to yourself and transacts no row;
   an agent's notes-to-itself are `;;` comments in its turn, never a stored
   self→self message. Errors are values — branch on `:seon.error/message`."
  {:malli/schema [:=> [:catn [::to-id :string] [::content :string]]
                  ::message-response]}
  [to-id content]
  (await (message! {:seon.agent.message/content content
                    :seon.agent.message/to      [[:seon.agent/id to-id]]})))
