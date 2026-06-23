(ns seon.agent.message
  "Message model — THE single write path for `:seon.agent.message` rows.
   The keyword namespace matches the code namespace. Owns:
     - the `:seon.agent.message/*` attr + entity-kind schemas
     - the `:seon.user` entity schema + `user-ref` (the default `to`
       target — seeded at boot by seon.client)
     - `message!` — fully-formed storage, boundary defaulting, the
       blank-content refusal, hop derivation (`waking-hops`), the
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
    [seon.db :as db]
    [seon.schema :as schema]))

;; Every stored message is FULLY FORMED — from + to + content + at + id
;; + hops. Identity is the ref (`:seon.agent.message/from` points at the
;; sender entity — a `:seon.user/id` or `:seon.agent/id` entity).
;; "My conversation" is DERIVED: from = me OR to ∋ me.
(schema/register! :seon.agent.message/id      [:and {:seon.db/identity true} :seon.db/id])
(schema/register! :seon.agent.message/content :string)
(schema/register! :seon.agent.message/from    :seon.db/ref)
(schema/register! :seon.agent.message/to      [:vector :seon.db/ref])
(schema/register! :seon.agent.message/at      :inst)
;; Ping-pong guard: 0 when from = the user; agent-originated sends
;; carry waking-message-hops + 1. The wake trigger REFUSES messages
;; whose hops reached `seon.warn/hop-cap` so two agents can't auto-bill
;; an infinite reply chain.
(schema/register! :seon.agent.message/hops    :int)
;; Provenance: WHO authored this message, deterministic for the wake
;; gate. :human = the one human (HTTP/user adapter); :agent = an agent's
;; own send; :core = a substrate-originated nudge (e.g. tile-recovery)
;; that must NEVER wake an idle agent. Anchoring the wake gate
;; (inbound-msg-datom?) to origin ∈ {:human :agent} ∧ from ≠ me means a
;; :core message can't masquerade as human and re-arm a loop. Derived by
;; default in message! from `from` (user ⇒ :human, else :agent); :core is
;; set explicitly by the substrate caller.
(schema/register! :seon.agent.message/origin  [:enum :human :agent :core])
;; Consumed marker: a tx-hook (e.g. a downstream deterministic
;; chat-control like `/persona`) sets this `true` IN THE SAME TX that
;; processes the command, so the message does NOT wake the agent. STORED
;; only when true (absent = a live, unconsumed message); never stored as
;; false. Reversible — retract the attr to un-consume.
(schema/register! :seon.agent.message/handled? :boolean)

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
   [:seon.agent.message/origin  :seon.agent.message/origin]
   ;; Present only on a tx-hook-consumed message (handled? = true).
   ;; Optional/absent on every live message.
   [:seon.agent.message/handled? {:optional true} :seon.agent.message/handled?]])

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

(defn- user-entity?
  "Does `ref` resolve to THE user entity?"
  [ref]
  (boolean (:seon.user/id (db/entity {:seon.db/ref ref}))))

(defn- waking-hops
  "Hops of the NEWEST inbound message (to ∋ me, from ≠ me), or 0 when
   none. This — not the loop's original waking message — is the hops
   base for agent-originated sends: a long-running loop keeps replying
   while new inbound messages arrive, and deriving from the ORIGINAL
   waking message would pin hops constant forever (two agents would
   ping-pong at a fixed hop count and never reach the cap). The latest
   inbound climbs with the chain, so replies carry climbing hops and the
   guard actually bites."
  [agent-id]
  (let [my-eid (:db/id (db/entity {:seon.db/ref [:seon.agent/id agent-id]}))]
    (or (when my-eid
          (->> (db/query
                 {:seon.db/query
                  '[:find ?at ?h
                    :in $ ?me
                    :where
                    [?m :seon.agent.message/to ?me]
                    [?m :seon.agent.message/from ?f]
                    [(not= ?f ?me)]
                    [?m :seon.agent.message/at ?at]
                    [(get-else $ ?m :seon.agent.message/hops 0) ?h]]
                  :seon.db/args [my-eid]})
               (sort-by #(.getTime ^js (first %)))
               last
               second))
        0)))

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
     hops               — 0 when from = the user; otherwise the waking
                          message's hops + 1 (ping-pong guard — the wake
                          trigger refuses past `seon.warn/hop-cap`).

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
      (let [from-user? (user-entity? from)
            hops   (if from-user?
                     0
                     (inc (waking-hops agent-id)))
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
            env    (await (db/transact! {:seon.db/tx-data [row]}))]
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
   `message!`'s concise envelope:

     (message/agent \"Kpx-2605232138\" \"heads up: foo depends on qux\")

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
