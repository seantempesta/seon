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
    [seon.eval :as seval]
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
;; Provenance (#43): WHO authored this message, deterministic for the
;; wake/halt gates. :human = the one human (HTTP/user adapter); :agent =
;; an agent's own send/consult/reply; :core = a substrate-originated
;; nudge (e.g. tile-recovery) that must NEVER wake an idle agent NOR move
;; the halt baseline. Anchoring the wake gate (inbound-msg-datom?) and
;; the halt side (replied-since-inbound?) to origin ∈ {:human :agent} ∧
;; from ≠ me means a :core message can't masquerade as human and re-arm
;; a loop. Derived by default in message! from `from` (user ⇒ :human,
;; else :agent); :core is set explicitly by the substrate caller.
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
    [:or :seon.db/ref [:vector :seon.db/ref]]]
   ;; Override for the batch-failure guard (fix-everything B3): a send
   ;; composed in the same batch as a FAILED earlier form is refused
   ;; unless the caller passes force — a deliberate "I have seen the
   ;; failures and am replying about them anyway". Request-only; never
   ;; stored.
   [:seon.agent.message/force {:optional true} :boolean]
   ;; Provenance override (#43). Absent ⇒ DERIVED from `from` (user ⇒
   ;; :human, else :agent). A substrate-originated nudge (tile recovery)
   ;; passes :core explicitly so it can't wake an idle agent or move the
   ;; halt baseline. The HTTP/user adapter relies on the derived :human;
   ;; agent sends/consults/replies on the derived :agent.
   [:seon.agent.message/origin {:optional true} :seon.agent.message/origin]])

;; Concise success / standard error envelope (#26, A3 applied): the raw
;; transact tx-report is OFF the agent surface — ~1.5k transcript chars
;; per reply taught nothing and carried a misdirected "narrow your
;; query" hint. Success answers the three things a sender can act on:
;; did it store, which message, at what hop depth. Failure stays the
;; core-standard error envelope (errors are values).
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

;; ------------------------------------------------------------
;; Batch-failure guard (fix-everything-prd-2026-06-11 §1 ROOT-3 / B3).
;; The blind same-batch reply: research+register+transact+verify+reply
;; composed as ONE batch, every form failed, and the reply still told
;; the user "logged it" — the reply text was composed BEFORE any result
;; existed, and envelope failures (errors-as-values, eval-ok? TRUE)
;; correctly don't abort batches. The guard closes the gap at SEND
;; time, deriving batch state instead of storing any:
;;   - eval-batch! runs forms sequentially and AWAITS record-eval!
;;     before the next form, so every EARLIER form of the current batch
;;     is a durable :seon.eval row under the current turn;
;;   - message!/reply! execute inside the batch's tx-context scope
;;     (run-turn! layers :seon.db/turn-id; eval-batch!'s per-form scope
;;     merges) — (db/current-tx-context) names the turn;
;;   - ok-eval values live in the globalThis stash (seval/lookup-result).
;; Outside a batch (HTTP adapter, tests, boot) there is no turn-id in
;; scope and the guard is inert.
;; ------------------------------------------------------------

(defn- envelope-failure?
  "Is `v` an error-envelope-shaped VALUE — a map carrying any `*/ok?`
   key whose value is false? Structural (key NAME = \"ok?\"), not a
   list of blessed envelope types: every core envelope
   (`:seon.db/ok?`, `:seon.eval/ok?`, `:seon.agent.message/ok?`, …)
   and any future one matches."
  [v]
  (boolean
    (and (map? v)
         (some (fn [[k val]]
                 (and (keyword? k) (= "ok?" (name k)) (false? val)))
               v))))

(defn- envelope-error-message
  "Best-effort human line from an error envelope: a top-level
   `:seon.error/message`, or one nested under any `*/error` map key."
  [v]
  (when (map? v)
    (or (:seon.error/message v)
        (some (fn [[k val]]
                (when (and (keyword? k) (= "error" (name k)) (map? val))
                  (:seon.error/message val)))
              v))))

(defn- clip
  [s n]
  (let [s (str/replace (str s) #"\s+" " ")]
    (if (> (count s) n) (str (subs s 0 n) "…") s)))

(defn- batch-failure-lines
  "One legible line per FAILED earlier form of the current batch, [] when
   none (or outside a batch scope). Derived at send time — the current
   turn's recorded :seon.eval rows, oldest-first; the in-flight form
   (the one calling us) is not yet recorded, so every row is an EARLIER
   form. A failure is an eval error (:seon.eval/ok? false — includes
   read failures) OR an ok eval whose live value is an error envelope
   (the errors-as-values case the s21 blobs proved decisive). An ok
   eval whose live value can't be found is also flagged — unverifiable
   is not verified."
  []
  (if-let [turn-id (:seon.db/turn-id (db/current-tx-context))]
    (let [turn  (try (db/entity {:seon.db/ref [:seon.agent.turn/id turn-id]})
                     (catch :default _ nil))
          evals (sort-by #(.getTime ^js (:seon.eval/at %))
                         (:seon.agent.turn/evals turn))]
      (vec
        (keep
          (fn [e]
            (let [id  (:seon.eval/id e)
                  src (clip (:seon.eval/source e) 60)]
              (cond
                (false? (:seon.eval/ok? e))
                (str "eval " id " «" src "» — eval ERROR: "
                     (clip (or (:seon.eval/error e) "(see its transcript line)") 160))

                (true? (:seon.eval/ok? e))
                (let [live (seval/lookup-result id)]
                  (when (envelope-failure? live)
                    (str "eval " id " «" src "» — returned an error envelope"
                         (when-let [m (envelope-error-message live)]
                           (str ": " (clip m 160)))))))))
          evals)))
    []))

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
   message write routes through here, the guard kills the class.

   Same-batch failure guard (B3): when an EARLIER form of the current
   batch failed (eval error OR an error-envelope value — see
   [[batch-failure-lines]]), the send is REFUSED with a legible
   envelope naming the failed forms; the refusal lands in the eval log
   so the agent sees it next turn and replies honestly. Pass
   `:seon.agent.message/force true` to send anyway (a deliberate reply
   ABOUT the failures). The guard lives here — not only in `reply!` —
   because message! is THE single write path: a fan-out to another
   agent composed before its batch's results existed is the same false
   claim as a user-facing reply."
  {:malli/schema [:=> [:cat ::message-request] ::message-response]}
  [{:seon.agent.message/keys [content from to force origin]}]
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
      (let [failures (when-not force (batch-failure-lines))]
        (if (seq failures)
          ;; Same-batch failure guard (B3) — refuse legibly; the refusal
          ;; is itself a value the agent sees on its next wake.
          {:seon.db/ok? false
           :seon.db/error
           {:seon.error/message
            (str "message! REFUSED: " (count failures)
                 " earlier form(s) in this batch FAILED — this message was "
                 "composed before those results existed, so sending it "
                 "would claim success the transcript contradicts. Failed:\n  "
                 (str/join "\n  " failures)
                 "\nVerify each failure, then reply about what ACTUALLY "
                 "happened — or pass :seon.agent.message/force true to "
                 "send this text anyway.")}}
          (let [from-user? (user-entity? from)
                hops   (if from-user?
                         0
                         (inc (waking-hops agent-id)))
                ;; Provenance (#43): explicit :origin wins (a :core nudge);
                ;; otherwise derived — a user-ref send is :human, every
                ;; other send is :agent. Never stored as nil.
                origin (or origin (if from-user? :human :agent))
                msg-id (db/new-id!)
                env    (await
                         (db/transact!
                           {:seon.db/tx-data
                            [{:seon.agent.message/id      msg-id
                              :seon.agent.message/from    from
                              :seon.agent.message/to      to
                              :seon.agent.message/content content
                              :seon.agent.message/at      (js/Date.)
                              :seon.agent.message/hops    hops
                              :seon.agent.message/origin  origin}]}))]
            (if (:seon.db/ok? env)
              ;; concise success — the tx-report stays off the agent
              ;; surface; the id is the durable handle
              ;; ([:seon.agent.message/id msg-id]).
              {:seon.agent.message/ok?  true
               :seon.agent.message/id   msg-id
               :seon.agent.message/hops hops}
              env)))))))

(defn ^:async reply!
  "Reply to whoever woke the current turn: `message!` with `to` := the
   `:seon.agent.message/from` of the current turn's `:seon.agent.turn/woken-by`
   message (derived — the core knows who's talking to you; no
   target atom). Falls back to the user when the turn wasn't woken by a
   message. Returns `message!`'s concise envelope — including its
   same-batch failure refusal (B3): when an earlier form of this batch
   failed, the reply is refused; pass `:seon.agent.message/force true`
   to deliberately reply about the failures.

   Accepts EITHER a plain string (the common case) OR the request map.
   The string form just builds the canonical map and recurses, so there
   is exactly one path to `message!`:

     (seon.agent/reply! \"done — stored 2 rows\")
     (seon.agent/reply! {:seon.agent.message/content \"done — stored 2 rows\"})
     ;; map form when you need to override the batch-failure guard:
     (seon.agent/reply! {:seon.agent.message/content \"…\"
                         :seon.agent.message/force   true})"
  {:malli/schema [:=> [:cat [:or :string ::message-request]] ::message-response]}
  [arg]
  (if (string? arg)
    (await (reply! {:seon.agent.message/content arg}))
    (let [{:seon.agent.message/keys [content force]} arg
          agent-id  (db/current-agent-id)
          woke-from (get-in (ctx/current-turn {:seon.agent/id agent-id})
                            [:seon.agent.turn/woken-by :seon.agent.message/from :db/id])]
      (await (message! (cond-> {:seon.agent.message/content content
                                :seon.agent.message/to      (if woke-from [woke-from] [user-ref])}
                         (some? force) (assoc :seon.agent.message/force force)))))))
