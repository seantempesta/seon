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
   ;; The deliberate "I am replying ABOUT the failure" escape (#51
   ;; narrowed). The reply is NEVER refused on a same-turn failure — it
   ;; always transacts + delivers; the protection is the loop's
   ;; make-good-turn veto (run-agentic-loop! + same-turn-overclaim?).
   ;; `force` declares the over-claim is intentional. Request-only;
   ;; never stored.
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
;; Same-turn envelope-failure derivation (#51 narrowed,
;; reliability-49-53-deepdive-2026-06-22 §"#51"). The original B3 gate
;; REFUSED a send composed in a batch where an earlier form failed; the
;; refusal is now GONE — the reply ALWAYS transacts and is delivered
;; (blocking the write was the post-answer churn the loop economy
;; fights). What survives is the LOOP-TERMINATION VETO: when a
;; user-facing reply lands in the SAME turn as a sibling form that
;; returned a `{*/ok? false}` ENVELOPE VALUE — an `:seon.eval/ok? true`
;; row that "succeeded" but returned a failure value, the
;; structurally-INVISIBLE case the real #26/B3 incident hinged on — the
;; loop forces ONE more live turn so the advisory render lands in a turn
;; the agent actually sees (a dead next-turn advisory was the flaw the
;; dissent + Gemini proved). The eval-ERROR half was DROPPED: a genuine
;; `:seon.eval/ok? false` error is advisory after #50.
;;
;; All derived, nothing stored — `envelope-failure-lines` reads the
;; current turn's recorded :seon.eval rows:
;;   - eval-batch! runs forms sequentially and AWAITS record-eval!
;;     before the next form, so every EARLIER form of the current batch
;;     is a durable :seon.eval row under the current turn;
;;   - message!/reply! execute inside the batch's tx-context scope
;;     (run-turn! layers :seon.db/turn-id; eval-batch!'s per-form scope
;;     merges) — (db/current-tx-context) names the turn;
;;   - ok-eval values live in the globalThis stash (seval/lookup-result).
;; Outside a batch (HTTP adapter, tests, boot) there is no turn-id in
;; scope and the derivation is empty.
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

(defn turn-envelope-failure-lines
  "One legible line per eval row of `turn` whose live value is an
   ENVELOPE-VALUE FAILURE — an `:seon.eval/ok? true` row (the eval
   succeeded) whose stashed value carries a `*/ok? false` key
   (errors-as-values: a transact that returned `{:seon.db/ok? false …}`).
   [] when none. Oldest-first.

   NARROW by design (#51, reliability-49-53-deepdive-2026-06-22): the
   eval-ERROR half was DROPPED — after #50 a genuine `:seon.eval/ok?
   false` error is ADVISORY (it counts toward the loop's eval-count,
   the loop grants a next turn, and the error renders crystal-clear in
   the transcript), so blocking a legit reply on an exploratory throw
   is the post-answer churn the loop economy fights. Only the
   envelope-value case survives — the structurally-INVISIBLE failure
   (eval succeeded, returned a failure VALUE) the real #26/B3 incident
   hinged on. A lookup MISS returns nil and is DROPPED (not flagged):
   `keep` over `(when (envelope-failure? live) …)`."
  [turn]
  (let [evals (sort-by #(.getTime ^js (:seon.eval/at %))
                       (:seon.agent.turn/evals turn))]
    (vec
      (keep
        (fn [e]
          (when (true? (:seon.eval/ok? e))
            (let [id   (:seon.eval/id e)
                  src  (clip (:seon.eval/source e) 60)
                  live (seval/lookup-result id)]
              (when (envelope-failure? live)
                (str "eval " id " «" src "» — returned an error envelope"
                     (when-let [m (envelope-error-message live)]
                       (str ": " (clip m 160))))))))
        evals))))

(defn envelope-failure-lines
  "[[turn-envelope-failure-lines]] for the CURRENT batch's turn — resolved
   from `(db/current-tx-context)`'s `:seon.db/turn-id`. [] outside a batch
   scope (HTTP adapter, tests, boot — no turn-id). The in-flight form
   (the one composing the reply) is not yet recorded, so every row is an
   EARLIER form of the batch."
  []
  (if-let [turn-id (:seon.db/turn-id (db/current-tx-context))]
    (let [turn (try (db/entity {:seon.db/ref [:seon.agent.turn/id turn-id]})
                    (catch :default _ nil))]
      (turn-envelope-failure-lines turn))
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

   Same-turn envelope-failure (B3, #51 narrowed): the send is NO LONGER
   refused on a same-turn failure — the reply ALWAYS transacts and is
   delivered (the human gets the answer). The protection moved to a
   LOOP-TERMINATION VETO: when a user-facing reply lands in the same
   turn as a sibling form that returned a `{*/ok? false}` envelope
   VALUE, `run-agentic-loop!` forces ONE more live turn so the advisory
   render lands in a turn the agent sees (see [[envelope-failure-lines]]
   + seon.agent/same-turn-overclaim?). `:seon.agent.message/force` is the
   agent's deliberate \"I am replying ABOUT the failure\" escape —
   request-only, never stored, accepted for backward-compat and as the
   loop-veto opt-out documented in the prompt."
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
          env)))))

(defn ^:async reply!
  "Reply to whoever woke the current turn: `message!` with `to` := the
   `:seon.agent.message/from` of the current turn's `:seon.agent.turn/woken-by`
   message (derived — the core knows who's talking to you; no
   target atom). Falls back to the user when the turn wasn't woken by a
   message. Returns `message!`'s concise envelope — the reply ALWAYS
   transacts and is delivered (#51 narrowed). When the reply lands in
   the same turn as a sibling form that returned a `{*/ok? false}`
   envelope VALUE, the loop forces ONE more live turn for the advisory
   (see [[envelope-failure-lines]]); pass `:seon.agent.message/force
   true` to declare a deliberate reply ABOUT the failure.

   Accepts EITHER a plain string (the common case) OR the request map.
   The string form just builds the canonical map and recurses, so there
   is exactly one path to `message!`:

     (seon.agent/reply! \"done — stored 2 rows\")
     (seon.agent/reply! {:seon.agent.message/content \"done — stored 2 rows\"})
     ;; map form when replying deliberately ABOUT a same-turn failure:
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

;; ============================================================
;; Same-turn overclaim derivation (#51 loop-termination veto).
;;
;; A user-facing reply that landed in the SAME turn as a sibling form
;; whose live value was a `{*/ok? false}` envelope failure is the
;; structurally-INVISIBLE over-claim the gate exists to catch (the eval
;; "succeeded", so the loop's eval-count grants it nothing; reply! halts
;; the loop first). Pure derivation over the turn's :seon.eval rows + the
;; message log — nothing stored, nothing to clear
;; (docs/seon/concepts/reactive-context). Shared by the loop veto
;; ([[seon.agent/run-agentic-loop!]] forces one make-good turn) and the
;; advisory render ([[overclaim-advisory-section]]).
;; ============================================================

(defn- user-facing-reply-in-window?
  "True iff `agent-id` sent an outbound USER-FACING reply (from = me,
   to ∋ THE user entity) whose `:seon.agent.message/at` falls in
   `[lo, hi)` — `lo` inclusive, `hi` exclusive (nil hi = open-ended,
   the just-run turn's window when no later turn exists yet). The
   per-turn assistant self-message (from = to = me) is NOT user-facing
   and never matches. Derived from the message log."
  [agent-id lo hi]
  (let [my-eid   (:db/id (db/entity {:seon.db/ref [:seon.agent/id agent-id]}))
        user-eid (:db/id (db/entity {:seon.db/ref user-ref}))]
    (boolean
      (when (and my-eid user-eid)
        (let [lo-ms (.getTime ^js lo)
              hi-ms (when hi (.getTime ^js hi))
              ats   (db/query
                      {:seon.db/query
                       '[:find ?at
                         :in $ ?me ?user
                         :where
                         [?m :seon.agent.message/from ?me]
                         [?m :seon.agent.message/to ?user]
                         [?m :seon.agent.message/at ?at]]
                       :seon.db/args [my-eid user-eid]})]
          (some (fn [[at]]
                  (let [t (.getTime ^js at)]
                    (and (>= t lo-ms) (or (nil? hi-ms) (< t hi-ms)))))
                ats))))))

(defn turn-overclaim-lines
  "The envelope-failure lines for `turn` IF — and only if — `turn` is an
   OVER-CLAIM turn: a sibling form returned a `{*/ok? false}` envelope
   VALUE AND a user-facing reply landed in `turn`'s window. [] otherwise.
   `hi` is the next turn's `:seon.agent.turn/at` (exclusive upper bound on
   the reply window), or nil for the just-run/most-recent turn (open
   window). Pure derivation — the loop veto and the advisory both call
   this so they see the SAME truth."
  [turn agent-id hi]
  (let [lines (turn-envelope-failure-lines turn)]
    (if (and (seq lines)
             (user-facing-reply-in-window?
               agent-id (:seon.agent.turn/at turn) hi))
      lines
      [])))

(defn same-turn-overclaim?
  "Loop-termination veto (#51): does the JUST-RUN turn carry a
   user-facing over-claim? `turn` is the closed turn entity the loop
   holds (run-turn!'s result, pulled with its evals inlined). True when a
   sibling form returned a `{*/ok? false}` envelope VALUE AND a
   user-facing reply landed in this turn. The just-run turn is the
   most-recent turn at loop-check time, so its reply window is OPEN
   (hi = nil). When true the loop forces ONE more live turn so
   [[overclaim-advisory-section]] lands where the agent sees it. Fully
   derived — by construction the make-good turn (a fresh turn) is NOT an
   over-claim turn, so this fires at most once per offending turn; no
   stored flag, nothing to clear."
  [turn agent-id]
  (boolean (seq (turn-overclaim-lines turn agent-id nil))))

(defn overclaim-advisory-section
  "Pure section fn (#51 advisory render, TIGHTEST scope per DECISION
   §5.2): fires ONLY when an outbound user-facing reply landed in the
   IMMEDIATELY-PRIOR turn as a sibling form that returned a
   `{*/ok? false}` envelope VALUE — the over-claim case. Returns \"\"
   (the section is then dropped) for every other turn, including
   exploratory eval-errors (those are advisory by construction after
   #50 — surfacing them here is the weak-model anxiety noise Lens B
   warned against).

   It renders on the MAKE-GOOD turn the loop forced: the offending turn
   is the prior turn (now :done), this turn is the current running one.
   Reading the prior turn (hi = the running turn's `at`) keeps the
   advisory correlated with the actual over-claim risk. Reactive — a
   pure fn of the turn + message log; nothing stored, vanishes once the
   agent moves on."
  {:malli/schema [:=> [:cat :map] :string]}
  [{:seon.agent/keys [id] db :seon.db/db}]
  (let [turns   (sort-by #(.getTime ^js (:seon.agent.turn/at %))
                         (:seon.agent.session/turns (ctx/current-session id db)))
        running (last turns)
        prior   (last (butlast turns))]
    (if (and prior running)
      (let [lines (turn-overclaim-lines prior id (:seon.agent.turn/at running))]
        (if (seq lines)
          (str "<reply-over-claim-warning>\n"
               "You replied to your human THIS just-passed turn, but a form "
               "in that same turn returned a FAILURE envelope ({*/ok? false}) "
               "— the eval \"succeeded\" yet its VALUE says the write did not "
               "land. Your human may have a false confirmation. Re-read each "
               "failure, then send a correcting reply with what ACTUALLY "
               "happened:\n  "
               (str/join "\n  " lines)
               "\n</reply-over-claim-warning>")
          ""))
      "")))
