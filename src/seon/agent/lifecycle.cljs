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
    [seon.agent.internal :as internal]
    [seon.agent.loop :as loop]
    [seon.agent.message :as msg]
    [seon.agent.run :as run]
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

(defn ^:async complete
  "Finish the calling agent's work: close its open run `:completed`.

   Derived `:idle` (a new message opens a fresh run). If `:seon.agent/parent` is set,
   send the result to the parent (which wakes it via the normal inbound gate);
   no parent → the result is for the human (already said via message/user).
   The result is a MESSAGE, not a state — so it is REPORT=DATA, MESSAGE=POINTER:
   `result` is a SHORT pointer (the stored entity id + a one-line summary), not
   a long report inline (a multi-line result truncates mid-string and never
   sends). Store the findings as data first, then complete with the pointer;
   the parent QUERIES the stored data. Returns `:idle` on success, the error
   envelope on a failed transact, no agent in scope, or no open run."
  {:malli/schema [:=> [:catn [::result :string]]
                  [:or :seon.derive/state :seon.db/transact-response]]}
  [result]
  (if-let [id (db/current-agent-id)]
    (if-let [r (run/current-run {:seon.agent/id id})]
      (let [ent    (db/entity {:seon.db/ref [:seon.agent/id id]})
            parent (:db/id (:seon.agent/parent ent))
            env    (await (run/close-run!
                            {:seon.agent.run/id            (:seon.agent.run/id r)
                             :seon.agent.run/closed-reason :completed}))]
        (if (:seon.db/ok? env)
          (do (when parent
                (await (msg/message! {:seon.agent.message/content result
                                      :seon.agent.message/to      [parent]})))
              :idle)
          env))
      (no-open-run-error "complete" id))
    (internal/no-agent-error "complete")))

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
