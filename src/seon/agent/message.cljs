(ns seon.agent.message
  "Message model — THE single write path for `:seon.agent.message` rows.
   The keyword namespace matches the code namespace. Owns:
     - the `:seon.agent.message/*` attr + entity-kind schemas
     - the `:seon.user` entity schema + `user-ref` (the default `to`
       target — seeded at boot by seon.client)
     - `message!` — fully-formed storage, boundary defaulting, the
       blank-content refusal, hop derivation (`outbound-hops`), the
       concise success envelope
     - the two agent-facing verbs, thin wrappers over `message!` — the
       agent reaches them through the `message/` alias on its home ns
       (`(message/user …)` / `(message/agent …)`):
         `user`  — from = me (the ALS agent), to = the one human user
         `agent` — from = me, to = [agent-id]; REFUSES `to = me` (loud
                   error, no row). No self→self messaging, ever.

   The WAKE side (the inbound-message trigger + the hop-cap refusal at
   wake) stays in `seon.agent` — it drives the loop and would cycle
   here. `seon.agent` re-exports `message!`/`user-ref` on the face."
  (:require
    [clojure.string :as str]
    [seon.agent.message.internal :as internal]
    [seon.db :as db]
    [seon.schema :as schema]
    [seon.warn :as warn]))

;; Every stored message is FULLY FORMED — from + to + content + at + id
;; + hops. Identity is the ref (`:seon.agent.message/from` points at the
;; sender entity — a `:seon.user/id` or `:seon.agent/id` entity).
;; "My conversation" is DERIVED: from = me OR to ∋ me.
(schema/register! :seon.agent.message/id      [:and {:seon.db/identity true} :seon.db/id])
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

;; ============================================================
;; message! — the SINGLE write entry point for messages (the verbs
;; `user`/`agent` below are thin wrappers over it). Presence of
;; attributes IS the intent; the DB holds only FULLY-FORMED messages
;; (from + to + content + at + id + hops). All defaulting is a
;; message!-boundary liberty, never a storage shape.
;; ============================================================

(schema/register! ::message-request
  [:map
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
   ;; else :agent). A substrate-originated nudge (tile recovery) passes
   ;; :core explicitly so it can't wake an idle agent. The HTTP/user
   ;; adapter relies on the derived :human; agent sends on the derived
   ;; :agent.
   [:seon.agent.message/origin {:optional true} :seon.agent.message/origin]])

;; Concise success / standard error envelope: the raw transact tx-report
;; is OFF the agent surface — it taught nothing and carried a misdirected
;; "narrow your query" hint. Success answers the three things a sender
;; can act on: did it store, which message, at what hop depth. Failure
;; stays the core-standard error envelope (errors are values).
(schema/register! ::message-response
  [:or
   [:map
    [:seon.agent.message/ok?  [:= true]]
    [:seon.agent.message/id   :seon.agent.message/id]
    [:seon.agent.message/hops :seon.agent.message/hops]]
   [:map
    [:seon.db/ok?   [:= false]]
    [:seon.db/error :seon.db/error]]])

;; The waking-inbound RULE — ONE source of truth for "this message wakes
;; (and renders as an inbound) for the agent whose eid is `my-eid`". Both
;; the wake gate ([[seon.agent/inbound-msg-datom?]]) and the transcript
;; head-render ([[seon.agent.ctx.transcript]]) call these so a message wakes
;; under exactly the rule it renders under — no drift. PUBLIC (cross-ns
;; callers): lives here, the message-data owner, not in `.internal`.

(defn waking-inbound?
  "True iff message map `m` (a pull with its `from` ref carrying `:db/id`)
   is a WAKING inbound for the agent whose eid is `my-eid`:
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
  "True iff message map `m`'s hop count is still under `seon.warn/hop-cap`
   (absent hops = 0 ⇒ live). The ping-pong guard, factored out of
   [[waking-inbound?]] so the wake side can keep the loud hop-exhausted
   refusal while the transcript composes `(and (waking-inbound? …)
   (hop-live? …))` to drop a dead-chain message from the rendered log."
  {:malli/schema [:=> [:catn [::m :map]] :boolean]}
  [m]
  (< (or (:seon.agent.message/hops m) 0) warn/hop-cap))

(defn ^:async message!
  "Send a message — THE single entry point for `:seon.agent.message` writes.
   Map-in / map-out; returns a CONCISE envelope, never the raw
   tx-report:
     {:seon.agent.message/ok? true :seon.agent.message/id _ :seon.agent.message/hops _}
     {:seon.db/ok? false :seon.db/error …}   ; failure (errors are values)

   Defaulting (boundary liberties — the STORED row is always full):
     :seon.agent.message/from — defaults to [:seon.agent/id (current-agent-id)]
                          from the ALS turn scope. No scope + no explicit
                          from → error envelope.
     :seon.agent.message/to   — single ref or vector; defaults to the user.
     hops               — 0 when from = the user; otherwise this
                          {me,recipient}-pair's prior depth + 1 (per-peer
                          ping-pong guard, reset at each human message —
                          `internal/outbound-hops`; distinct delegation
                          rounds do NOT accumulate). The wake trigger
                          refuses past `seon.warn/hop-cap`.

   Blank content is REJECTED with an error envelope — an empty message
   carries nothing; since every message write routes through here, the
   guard kills the class.

   A message ALWAYS transacts and is delivered — there is no same-turn
   refusal. Errors are values: if a sibling form in the same turn
   returned a `{*/ok? false}` envelope the agent may over-claim, but that
   is visible in the transcript and a human follow-up re-wakes the agent.
   Nothing here blocks a send."
  {:malli/schema [:=> [:cat ::message-request] ::message-response]}
  [{:seon.agent.message/keys [content from to origin]}]
  (let [agent-id (db/current-agent-id)
        from     (or from (when agent-id [:seon.agent/id agent-id]))
        to       (cond
                   (nil? to)             [user-ref]
                   (and (vector? to)
                        (vector? (first to))) to        ; vector of lookup refs
                   (and (vector? to)
                        (keyword? (first to))) [to]     ; single lookup ref
                   (vector? to)          to             ; vector of eids
                   :else                 [to])]         ; single eid
    (cond
      (or (nil? content) (str/blank? content))
      {:seon.db/ok? false
       :seon.db/error
       {:seon.error/message
        (str "message!: blank :seon.agent.message/content refused — a message "
             "with nothing to say must not be stored. Compose the text "
             "first, then send.")}}

      (nil? from)
      {:seon.db/ok? false
       :seon.db/error
       {:seon.error/message
        (str "message!: no :seon.agent.message/from and no agent-id in scope — "
             "pass from explicitly or call inside (seon.db/with-agent …).")}}

      :else
      (let [from-user? (internal/user-entity? from)
            hops   (if from-user?
                     0
                     (inc (internal/outbound-hops agent-id to)))
            ;; Provenance: explicit :origin wins (a :core nudge);
            ;; otherwise derived — a user-ref send is :human, every
            ;; other send is :agent. Never stored as nil.
            origin (or origin (if from-user? :human :agent))
            msg-id (db/new-id!)
            row    {:seon.agent.message/id      msg-id
                    :seon.agent.message/from    from
                    :seon.agent.message/to      to
                    :seon.agent.message/content content
                    :seon.agent.message/at      (js/Date.)
                    :seon.agent.message/hops    hops
                    :seon.agent.message/origin  origin}
            ;; The message ↔ todo safety net (WRITE half): a :human inbound
            ;; auto-mints ONE address-todo per AGENT recipient, ATOMIC in
            ;; this same tx (no listener, no cascade). The todo carries a
            ;; clipped preview + a back-ref to this message's identity —
            ;; same-tx lookup-refs resolve. "Addressed" is DERIVED from the
            ;; todo's completion; there is no stored handled? flag.
            agent-tos (when (= origin :human)
                        (filter #(and (vector? %) (= :seon.agent/id (first %))) to))
            todos  (mapv (fn [agent-ref]
                           {:seon.agent.todo/id         (db/new-id!)
                            :seon.agent.todo/title      (internal/clip-title content)
                            :seon.agent.todo/status     :open
                            :seon.agent.todo/created-at (js/Date.)
                            :seon.agent.todo/owner      agent-ref
                            :seon.agent.todo/from       from
                            :seon.agent.todo/message    [:seon.agent.message/id msg-id]})
                         agent-tos)
            env    (await (db/transact! {:seon.db/tx-data (into [row] todos)}))]
        (if (:seon.db/ok? env)
          ;; concise success — the tx-report stays off the agent
          ;; surface; the id is the durable handle
          ;; ([:seon.agent.message/id msg-id]).
          {:seon.agent.message/ok?  true
           :seon.agent.message/id   msg-id
           :seon.agent.message/hops hops}
          env)))))

;; ============================================================
;; The two agent-facing verbs. Thin wrappers over `message!` — `from`
;; defaults to the ALS agent inside `message!`, so these only fix `to`.
;; The agent reaches them through the `message/` alias on its home ns:
;; `(message/user "…")` / `(message/agent id "…")`. No self→self
;; messaging, ever: `agent` refuses `to = me` (the wake gate already
;; ignores `from = me`; this makes it a hard prohibition at the verb).
;; `^:async` fns are not runtime-instrumented, so the `:malli/schema` is
;; the only contract — both reuse `::message-response`.
;; ============================================================

(defn ^:async user
  "Send a message to your human — `message!` with `to` := THE one user.
   `from` is you (the ALS agent). Returns `message!`'s concise envelope.
   This is how you say something to the human watching your REPL:

     (message/user \"done — stored 2 rows\")"
  {:malli/schema [:=> [:catn [::content :string]] ::message-response]}
  [content]
  (await (message! {:seon.agent.message/content content
                    :seon.agent.message/to      [user-ref]})))

(defn ^:async agent
  "Send a message to a PEER agent by id — `message!` with `to` :=
   `[[:seon.agent/id agent-id]]`. `from` is you (the ALS agent). Returns
   `message!`'s concise envelope. The peer must already exist — find live
   ids with `(db/query '[:find [?id ...] :where [?e :seon.agent/id ?id]])`:

     (message/agent <peer-agent-id> \"heads up: foo depends on qux\")

   REFUSES sending to YOURSELF (the ALS agent in scope): a self-message
   returns a loud error envelope and transacts NO row — an agent's
   notes-to-itself are `;;` comments in its turn, never a stored
   self→self message. Errors are values — branch on `:seon.db/ok?`."
  {:malli/schema [:=> [:catn [::to-id :string] [::content :string]]
                  ::message-response]}
  [to-id content]
  (let [me (db/current-agent-id)]
    (if (and me (= to-id me))
      {:seon.db/ok? false
       :seon.db/error
       {:seon.error/message
        (str "message/agent: refused — you cannot message YOURSELF ("
             (pr-str to-id) "). No self→self messages exist anywhere; a "
             "note to yourself is a ;; comment in your turn. To message a "
             "PEER, pass that agent's id; to say something to your human, "
             "use (message/user \"…\").")}}
      (await (message! {:seon.agent.message/content content
                        :seon.agent.message/to      [[:seon.agent/id to-id]]})))))
