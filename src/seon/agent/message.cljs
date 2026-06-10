(ns seon.agent.message
  "Message model — THE single write path for `:seon.agent.message` rows
   (unit 1.5, split out of seon.agent in the P6 reorg so the keyword
   namespace matches the code namespace). Owns:
     - the `:seon.agent.message/*` attr + entity-kind schemas
     - the `:seon.user` entity schema + `user-ref` (the default `to`
       target — seeded at boot by seon.client)
     - `message!` — fully-formed storage, boundary defaulting, the
       blank-content refusal, hop derivation (`waking-hops`), the
       concise success envelope
     - `reply!`   — woken-by targeting (derived from the current turn)

   The WAKE side (the inbound-message trigger + the hop-cap refusal at
   wake) stays in `seon.agent` — it drives `run-agentic-loop!` and
   would cycle here. `seon.agent` re-exports `message!`/`reply!`/
   `user-ref` on the face; the agent-taught call surface is unchanged
   (`seon.agent/reply!` …)."
  (:require
    [clojure.string :as str]
    [seon.ctx :as ctx]
    [seon.db :as db]
    [seon.schema :as schema]))

;; Messaging codified (unit 1.5, 2026-06-09): every stored message is
;; FULLY FORMED — from + to + content + at + id + hops. Identity is the
;; ref (`:seon.agent.message/from` points at the sender entity — a
;; `:seon.user/id` or `:seon.agent/id` entity); `role` and `agent` are
;; RETIRED. "My conversation" is DERIVED: from = me OR to ∋ me.
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
   [:seon.agent.message/hops    :seon.agent.message/hops]])

;; ============================================================
;; message! / reply! — the SINGLE write entry point for messages.
;; Presence of attributes IS the intent; the DB holds only FULLY-
;; FORMED messages (from + to + content + at + id + hops). All
;; defaulting is a message!-boundary liberty, never a storage shape.
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
    [:or :seon.db/ref [:vector :seon.db/ref]]]])

;; Concise success / standard error envelope (#26, A3 applied): the raw
;; transact tx-report is OFF the agent surface — ~1.5k transcript chars
;; per reply taught nothing and carried a misdirected "narrow your
;; query" hint. Success answers the three things a sender can act on:
;; did it store, which message, at what hop depth. Failure stays the
;; substrate-standard error envelope (errors are values).
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
   none. This — not the turn's woken-by — is the hops base for
   agent-originated sends: a long-running loop keeps replying while new
   inbound messages arrive, and deriving from the loop's ORIGINAL
   waking message would pin hops constant forever (observed live
   2026-06-09: two stub agents ping-ponged at hops 2↔3 indefinitely,
   the cap never reached). The latest inbound climbs with the chain, so
   replies carry climbing hops and the guard actually bites."
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

   Blank content is REJECTED with an error envelope — empty assistant
   messages were a recurring live defect (runs 3 + 6); since every
   message write routes through here, the guard kills the class."
  {:malli/schema [:=> [:cat ::message-request] ::message-response]}
  [{:seon.agent.message/keys [content from to]}]
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
      (let [hops   (if (user-entity? from)
                     0
                     (inc (waking-hops agent-id)))
            msg-id (db/new-id!)
            env    (await
                     (db/transact!
                       {:seon.db/tx-data
                        [{:seon.agent.message/id      msg-id
                          :seon.agent.message/from    from
                          :seon.agent.message/to      to
                          :seon.agent.message/content content
                          :seon.agent.message/at      (js/Date.)
                          :seon.agent.message/hops    hops}]}))]
        (if (:seon.db/ok? env)
          ;; concise success — the tx-report stays off the agent surface;
          ;; the id is the durable handle ([:seon.agent.message/id msg-id]).
          {:seon.agent.message/ok?  true
           :seon.agent.message/id   msg-id
           :seon.agent.message/hops hops}
          env)))))

(defn ^:async reply!
  "Reply to whoever woke the current turn: `message!` with `to` := the
   `:seon.agent.message/from` of the current turn's `:seon.agent.turn/woken-by`
   message (derived — the substrate knows who's talking to you; no
   target atom). Falls back to the user when the turn wasn't woken by a
   message. Returns `message!`'s concise envelope. The one-liner for
   both user- and agent-conversations:

     (seon.agent/reply! {:seon.agent.message/content \"done — stored 2 rows\"})"
  {:malli/schema [:=> [:cat ::message-request] ::message-response]}
  [{:seon.agent.message/keys [content]}]
  (let [agent-id (db/current-agent-id)
        woke-from (get-in (ctx/current-turn {:seon.agent/id agent-id})
                          [:seon.agent.turn/woken-by :seon.agent.message/from :db/id])]
    (await (message! {:seon.agent.message/content content
                      :seon.agent.message/to      (if woke-from [woke-from] [user-ref])}))))
