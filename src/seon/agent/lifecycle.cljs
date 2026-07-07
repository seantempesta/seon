(ns seon.agent.lifecycle
  "The agent's run-lifecycle verbs — `wait` / `complete` / `pause` / `resume`
   / `terminate`.

   State is DERIVED from the run, so each verb is a RUN mutation, not a stored
   state flip:
     - `wait`      closes the open run `:waited`      → derived `:idle`
     - `complete`  closes the open run `:completed`   → derived `:idle`
     - `pause`     stamps the open run `:paused-at`   → derived `:paused`
     - `resume`    clears `:paused-at` + re-extends   → derived `:running`
     - `terminate` sets `:seon.agent/terminated-at` + closes any open run
                   `:terminated`                      → derived `:terminated`

   Each returns the new DERIVED state keyword (the value the transcript shows:
   `:idle` for wait/complete, `:paused`/`:running` for pause/resume,
   `:terminated` for terminate), or a loud error envelope on a failed transact
   / no agent in scope / no open run (errors are values — never a throw). The
   REASON a run closed lives on the run (`:seon.agent.run/closed-reason`), not
   a separate note. No verb ever writes a self→self message.

   `wait` / `complete` / `pause` / `resume` default to the calling agent, read
   from the ALS scope via `(seon.db/current-agent-id)` — call them inside
   `(seon.db/with-agent id …)`. `terminate` takes an explicit id (it is
   orchestrator-only; an agent does not terminate itself).

   The run mutations live in [[seon.agent.run]]; the shared
   no-agent-in-scope envelope in [[seon.agent.internal]].

   `^:async` fns aren't runtime-instrumented — the `:malli/schema` is the
   contract."
  (:require
    [clojure.string :as str]
    [seon.agent.internal :as internal]
    [seon.agent.loop :as loop]
    [seon.agent.message :as msg]
    [seon.agent.run :as run]
    [seon.agent.testrun :as testrun]
    [seon.db :as db]
    [seon.schema :as schema]))

(schema/register! ::note   :string)
(schema/register! ::result :string)

(defn- no-open-run-error
  "The error envelope a verb returns when the agent has no OPEN run to act on
   (e.g. `wait` called while already idle)."
  [verb id]
  {:seon.db/ok? false
   :seon.db/error
   {:seon.error/message
    (str verb ": agent " (pr-str id) " has no open run to act on "
         "(it is not currently running).")}})

(defn ^:async wait
  "Park the calling agent: close its open run `:waited` → derived `:idle`.

   `:idle` is the single wakeable parked state (a new message opens a fresh
   run). The `note` is informational only — WHY it parked is the run's `:waited`
   closed-reason. Returns `:idle` on success, a loud error envelope on a
   failed transact, no agent in scope, or no open run."
  {:malli/schema [:=> [:catn [::note :string]]
                  [:or :seon.derive/state :seon.db/transact-response]]}
  [note]
  (if-let [id (db/current-agent-id)]
    (if-let [r (run/current-run {:seon.agent/id id})]
      (let [env (await (run/close-run!
                         {:seon.agent.run/id            (:seon.agent.run/id r)
                          :seon.agent.run/closed-reason :waited}))]
        (if (:seon.db/ok? env) :idle env))
      (no-open-run-error "wait" id))
    (internal/no-agent-error "wait")))

(defn- messaged-recipient-since?
  "Whether the agent messaged `recipient-eid` at/after `started-at`.

   DERIVED from the message log at call time (from = the agent's eid,
   to ∋ the recipient, at ≥ the run's started-at) — no stored flag. The
   [[complete]] delivery gate: a message already sent this run IS the
   answer, so complete closes without sending a second one."
  [agent-eid recipient-eid started-at]
  (->> (db/query {:seon.db/query '[:find ?m ?at
                                   :in $ ?from ?to
                                   :where
                                   [?m :seon.agent.message/from ?from]
                                   [?m :seon.agent.message/to ?to]
                                   [?m :seon.agent.message/at ?at]]
                  :seon.db/args [agent-eid recipient-eid]})
       (some (fn [[_ at]]
               (>= (.getTime ^js at) (.getTime ^js started-at))))
       boolean))

(defn- complete-refusal
  "Refuse `complete` (honest value) when the agent's latest test run is RED.

   Purely DERIVED from the agent's own `:seon.agent.testrun` datoms — no
   stored gate flag. nil (⇒ complete proceeds) when the agent ran no
   recognized test suite (a non-test task — root/research agent — completes
   normally) OR the latest run was green (0 failed, 0 errors). A red latest
   run means not-done: the agent must SEE a real green render before claiming
   success, so we return the loud, actionable error envelope it reads next
   turn. `complete` = a success claim; honest give-up is `pause` or a
   `(message/user …)`, neither of which is gated."
  [id]
  (let [{:seon.agent.testrun/keys [passed failed errors]}
        (testrun/latest-run @db/*conn* id)]
    (when (and (some? failed) (or (pos? failed) (pos? errors)))
      {:seon.db/ok? false
       :seon.db/error
       {:seon.error/message
        (str "complete refused — your latest test run is RED ("
             failed " failed, " (or passed 0) " passed"
             (when (pos? errors) (str ", " errors " error" (when (not= errors 1) "s")))
             "). Run the tests and SEE a green result render before completing; "
             "a result you did not see the runtime render does not count. To "
             "STOP without claiming success, `pause` or report your honest "
             "status with (message/user \"…\") — those are not gated.")}})))

(defn ^:async complete
  "Finish the calling agent's work; close the open run `:completed`.

   Derived `:idle` (a new message opens a fresh run). The `result` string is
   delivered to WHOEVER ASKED — with a `:seon.agent/parent` it is messaged to
   the parent (waking it via the normal inbound gate); with no parent it is
   messaged to the HUMAN, the same `message!` path as `(message/user …)` —
   UNLESS the agent already messaged that recipient THIS RUN: the earlier
   message IS the answer, so complete just closes without sending a second,
   answer-clobbering message ([[messaged-recipient-since?]] — derived from
   the message log, no stored flag). Delivery, when it happens, precedes the
   close (a caller that polls for idle then reads the last user message
   always sees the result). A blank `result` delivers nothing (there is
   nothing to say) and just closes. A failed delivery is returned as the
   error envelope WITHOUT closing the run — retry with a fixed result.

   DURABLE VALUE (multi-agent-context Piece 1): the `result` (and optional
   `result-ref`, an entity id pointing at the stored work product) are written
   onto the RUN — `:seon.agent.run/result` / `:seon.agent.run/result-ref` —
   UNCONDITIONALLY, even when the message is skipped by the answered-this-run
   guard. Message = wake signal; datom = the value a parent (or the human)
   reads back at any later time via the run, surviving turns and restarts. So
   REPORT=DATA, MESSAGE=POINTER: `result` is the ANSWER itself when it is
   short, else a SHORT pointer (the stored entity id + a one-line summary),
   with `result-ref` the durable handle — never a long report inline (a
   multi-line result truncates mid-string and never sends). Store big findings
   as data first; the reader QUERIES the stored data. Returns `:idle` on
   success, the error envelope on a failed transact, no agent in scope, or no
   open run."
  {:malli/schema [:function
                  [:=> [:catn [::result :string]]
                   [:or :seon.derive/state :seon.db/transact-response]]
                  [:=> [:catn [::result :string] [::result-ref :seon.db/ref]]
                   [:or :seon.derive/state :seon.db/transact-response]]]}
  ([result] (complete result nil))
  ([result result-ref]
   (if-let [id (db/current-agent-id)]
     (if-let [r (run/current-run {:seon.agent/id id})]
       ;; Complete-gate: refuse a success claim while the latest real test
       ;; run is RED (derived from this agent's own testrun datoms). nil =
       ;; no test run or green ⇒ proceed. See [[complete-refusal]].
       (or
         (complete-refusal id)
         (let [ent       (db/entity {:seon.db/ref [:seon.agent/id id]})
               run-id    (:seon.agent.run/id r)
               parent    (:db/id (:seon.agent/parent ent))
               recipient (or parent
                             (:db/id (db/entity {:seon.db/ref msg/user-ref})))
               started   (:seon.agent.run/started-at r)
               said?     (boolean
                           (and recipient started
                                (messaged-recipient-since?
                                  (:db/id ent) recipient started)))
               ;; DURABLE VALUE first — written UNCONDITIONALLY (the said? guard
               ;; gates only the wake MESSAGE, never the datom). A blank result
               ;; with no ref writes nothing (optional = absent, never store nil).
               result-row (cond-> {:seon.agent.run/id run-id}
                            (not (str/blank? result)) (assoc :seon.agent.run/result result)
                            (some? result-ref)        (assoc :seon.agent.run/result-ref result-ref))
               wrote      (when (> (count result-row) 1)
                            (await (db/transact! {:seon.db/tx-data [result-row]})))
               sent       (when-not (or (str/blank? result) said?)
                            (await (msg/message!
                                     {:seon.agent.message/content result
                                      :seon.agent.message/to
                                      (if parent [parent] [msg/user-ref])})))]
           (cond
             (false? (:seon.db/ok? wrote)) wrote
             (false? (:seon.db/ok? sent))  sent
             :else
             (let [env (await (run/close-run!
                                {:seon.agent.run/id            run-id
                                 :seon.agent.run/closed-reason :completed}))]
               (if (:seon.db/ok? env) :idle env)))))
       (no-open-run-error "complete" id))
     (internal/no-agent-error "complete"))))

(defn ^:async pause
  "Hold the calling agent WITHOUT killing it — stamp its run `paused-at`.

   Derived `:paused`; banks the remaining wall-clock budget. `resume`
   re-extends the deadline by it. Returns `:paused` on success, the error
   envelope on a failed transact, no agent in scope, or no open run."
  {:malli/schema [:=> [:catn] [:or :seon.derive/state :seon.db/transact-response]]}
  []
  (if-let [id (db/current-agent-id)]
    (if-let [r (run/current-run {:seon.agent/id id})]
      (let [env (await (run/pause! {:seon.agent/id     id
                                    :seon.agent.run/id (:seon.agent.run/id r)}))]
        (if (:seon.db/ok? env) :paused env))
      (no-open-run-error "pause" id))
    (internal/no-agent-error "pause")))

(defn ^:async resume
  "Wake a paused run: clear `paused-at` and re-enter the drive loop.

   Derived `:running`; re-extend the
   deadline by the banked remaining-ms (a long pause never instantly blows the
   clock bound), and RE-ENTER the drive loop on the still-open run — the loop
   EXITED on :pause, so resume must re-drive or the run is derived `:running`
   with nothing folding turns. Returns `:running` on success, the error
   envelope on a failed transact (incl. a not-actually-paused run), no agent in
   scope, or no open run."
  {:malli/schema [:=> [:catn] [:or :seon.derive/state :seon.db/transact-response]]}
  []
  (if-let [id (db/current-agent-id)]
    (if-let [r (run/current-run {:seon.agent/id id})]
      (let [env (await (run/resume! {:seon.agent/id     id
                                     :seon.agent.run/id (:seon.agent.run/id r)}))]
        (if (:seon.db/ok? env)
          (do (loop/drive-run! {:seon.agent/id id})
              :running)
          env))
      (no-open-run-error "resume" id))
    (internal/no-agent-error "resume")))

(defn ^:async terminate
  "Kill an agent: set `:seon.agent/terminated-at`, close any open run.

   Presence ⇒ derived `:terminated`, the one UNWAKEABLE state; the open run
   closes `:terminated`. Orchestrator-only; an agent does not terminate itself.
   Returns `:terminated` on success, the error envelope on a failed transact."
  {:malli/schema [:=> [:catn [::id :seon.agent/id]]
                  [:or :seon.derive/state :seon.db/transact-response]]}
  [id]
  (let [r   (run/current-run {:seon.agent/id id})
        env (await (db/transact!
                     {:seon.db/tx-data [{:seon.agent/id             id
                                         :seon.agent/terminated-at (js/Date.)}]}))]
    (if (:seon.db/ok? env)
      (do (when r
            (await (run/close-run!
                     {:seon.agent.run/id            (:seon.agent.run/id r)
                      :seon.agent.run/closed-reason :terminated})))
          :terminated)
      env)))
