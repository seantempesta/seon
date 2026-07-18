(ns seon.agent.loop
  "The agent LOOP — the wake trigger + the run-driven fold.

   The loop is a FOLD of [[transition]] (the FSM transition table, which lives
   HERE with the loop that folds it) over events derived from the RUN's data.
   A trigger (an inbound message) opens a RUN ([[seon.agent.run/open-run!]]);
   `run-loop!` then drives turns until a bound fires or a function closes the run:

     each iteration re-reads ONE frozen db value (§8a) and `next-event` derives
     the event from it:
       - run already :closed (a function ran inside a turn) → :wait / :complete /
         :terminate (from the run's closed-reason) — the function owns the close
       - the agent's `:seon.agent/run` points at a DIFFERENT run → :superseded
       - the run carries :paused-at                       → :pause
       - work ≥ turn-limit (the WORK bound: turns in repl-mode
         :batch, FORMS in :stream)                        → :turn-limit
       - now > deadline (the WALL-CLOCK bound)            → :deadline
       - else                                             → :turn-ok
     `(transition state event)` gives the next state; the EFFECT of
       :turn-ok is `beat!` + `run-turn!`; the LOOP closes the run on
       :turn-limit/:deadline/:error (the bounds it owns), while function closes
       and supersede are already handled (no re-close). The loop ends when the
       state leaves :running.

   The RUN-ID is the fencing token, enforced IN-TX (§8b): `beat!`/`run-turn!`
   each LEAD their work tx with a CAS asserting the agent's `:seon.agent/run`
   STILL names this run. A superseded run's beat returns a direct CAS error
   (lost authority → terminate); its turn-open is rejected at commit. Between turns
   the next iteration re-reads the latest db and `next-event` sees the moved
   pointer → :superseded (§8c).

   Wake = read-derived-then-open. An inbound datom fires the per-tx listener;
   the handler derives the agent's state from the local db snapshot. If :idle
   → `open-run!` ({trigger :message, cause the message}) then `run-loop!` on
   that run. If already :running → `renew!` (the new message extends the lease
   — the sliding window is now lease renewal). The idle→running open is ATOMIC
   (a CAS on `:seon.agent/run` being absent — [[seon.agent.run/open-run!]]):
   two simultaneous idle wakes can't both open, the loser's open returns a
   direct CAS error and it RENEWS the winner's run instead (no orphaned run).

   Requires `seon.agent.message` (the wake gate `inbound-msg-datom?`),
   `seon.derive` (the one derived-state leaf), `seon.agent.run` (the run
   lifecycle), `seon.agent.turn` (`run-turn!`). The boot path (`seon.client`)
   requires THIS ns to `install-wake-trigger!`."
  (:require
    [clojure.string :as str]
    [my.plan.internal :as plan-internal]
    [seon.agent.home :as home]
    [seon.agent.message :as message]
    [seon.agent.run :as run]
    [seon.agent.schedule :as schedule]
    [seon.agent.turn :as turn]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.protocol :as db.protocol]
    [seon.derive :as derive]
    [seon.eval :as seval]
    [seon.log :as seon-log]
    [seon.repl.internal :as repl-internal]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]
    [seon.warn :as warn]))

(defn- log [agent-id stage & info]
  (seon-log/info-console!
    (str "seon.agent.loop/" agent-id)
    stage
    (if (= 1 (count info)) (first info) (vec info))))

;; ============================================================
;; The FSM transition table — the whole machine as one value. It lives WITH
;; the loop that folds it: [[run-loop!]] reads the RUN's data, derives an
;; event, and folds [[transition]] over the run state. The derived STATE enum
;; (:idle/:running/:paused/:terminated) is `:seon.derive/state` — a pure
;; projection of the run/terminated-at primitives ([[seon.derive/derive-state]]),
;; never stored.
;; ============================================================

(schema/register! :seon.agent.loop/event
  [:enum :trigger :turn-ok :wait :complete :turn-limit :deadline
         :superseded :error :no-forms :pause :terminate :resume :quiesce])

(def transitions
  "The whole FSM as data — `{state {event → next-state}}`. A wake (`:trigger`)
   opens a run (`:idle`→`:running`); functions/bounds/fences close it back to
   `:idle`; `:pause`/`:resume` hold without killing; `:terminate` is terminal.
   An event absent from a state's row leaves the state unchanged (see
   [[transition]])."
  {:idle       {:trigger :running}
   :running    {:turn-ok    :running :wait     :idle :complete   :idle
                :turn-limit :idle    :deadline :idle :superseded :idle
                :error      :idle    :no-forms :idle :pause      :paused
                :terminate  :terminated :quiesce :idle}
   :paused     {:resume :running :terminate :terminated}
   :terminated {}})

(defn transition
  "The single transition function: `(state, event) → next-state`.

   An event that is not in `state`'s row leaves the state unchanged (e.g. `:resume`
   while `:running`, or anything while `:terminated`)."
  {:malli/schema [:=> [:catn [:seon.derive/state :seon.derive/state]
                             [:seon.agent.loop/event :seon.agent.loop/event]]
                  :seon.derive/state]}
  [state event]
  (get-in transitions [state event] state))

;; ============================================================
;; Process-local loop-input registry — agent-id → the `input` map
;; (`:seon.agent/id` / `:seon.agent/llm-fn`) the
;; wake trigger was (re)armed with. `install-wake-trigger!` (re)stamps it on
;; EVERY arm, so it stays exactly as fresh as the live wake-handler closure (a
;; hot reload re-arms with a freshly-resolved llm-fn). A genuinely stateful
;; runtime artifact — the llm-fn is a closure and is not DB-derivable. This
;; registry remains a separate runtime-
;; service consolidation target; it is not evidence about result liveness.
;; `drive-run!` reads it to RE-ENTER the loop on RESUME (the loop exits on
;; :pause; resume must re-drive the still-open run). `defonce` survives a hot
;; reload; the re-arm repopulates it regardless.
;; ============================================================

(defonce ^:private !loop-input (atom {}))

;; Wake delivery, committed-work replay, and hot-reload reconciliation may all
;; discover the same open run. They share its one local driver; Datahike's CAS
;; prevents duplicate runs, while this prevents duplicate turns within the
;; winning run.
(defonce ^:private !run-loop-promises (atom {}))

;; ============================================================
;; The loop — a fold of [[transition]] over run-derived events. Each
;; iteration re-reads the run; :turn-ok runs a turn, the bounds close the
;; run, function closes / supersede are already settled.
;; ============================================================

(def no-forms-streak-limit
  "Consecutive empty turns (an LLM completion that produced ZERO actionable
   forms — pure prose/thinking) that close a run `:no-forms`. A STREAK guard,
   not a single-turn trip: a turn or two of planning-before-acting is normal,
   so the run only halts once the LLM has gone this many turns RUNNING without
   emitting a single form. The run-model successor to the deleted wake-token
   loop's empty-streak guard (which tolerated two 'thinking' turns and halted
   on the third) — without it an unresponsive/looping LLM spins to the
   turn-limit cap."
  3)

(def ^:private current-run-query
  '[:find ?run-id .
    :in $ ?agent-id
    :where
    [?agent :seon.agent/id ?agent-id]
    [?agent :seon.agent/run ?run]
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/status :open]])

(def ^:private run-turn-count-query
  '[:find (count ?turn) .
    :in $ ?run-id
    :where
    [?run :seon.agent.run/id ?run-id]
    [?turn :seon.agent.turn/run ?run]
    (not [?turn :seon.agent.turn/scheduled? true])])

(def ^:private run-form-count-query
  '[:find (count ?eval) .
    :in $ ?run-id
    :where
    [?run :seon.agent.run/id ?run-id]
    [?turn :seon.agent.turn/run ?run]
    (not [?turn :seon.agent.turn/scheduled? true])
    [?turn :seon.agent.turn/evals ?eval]])

(def ^:private repl-mode-query
  '[:find ?mode . :where [_ :seon.config/repl-mode ?mode]])

(defn- query-member [database query arguments]
  {::db.protocol/operation db.protocol/query-operation
   ::db/db database
   ::db.protocol/query-form query
   ::db.protocol/arguments arguments
   :datahike.resource/max-work 500000
   :datahike.resource/max-results 4096
   :datahike.resource/max-result-weight 65536})

(defn- pull-member [database selector ref]
  {::db.protocol/operation db.protocol/pull-operation
   ::db/db database
   ::db.protocol/selector selector
   ::db.protocol/entity-id ref
   :datahike.resource/max-work 100000
   :datahike.resource/max-results 64
   :datahike.resource/max-result-weight 65536})

(defn- successful-member? [member]
  (true? (::db.protocol/success? member)))

(defn- member-result [member]
  (or (::db.protocol/result member)
      (:datahike.query/result member)))

(defn- read-failure [message data]
  {:seon.error/message message
   :seon.error/kind :core-bug
   :seon.error/data data})

(defn- database-error? [value]
  (string? (:seon.error/message value)))

(defn ^:async ^:private acquire-loop-state
  "Read every fact used by one loop recurrence from one immutable database value."
  [id run-id]
  (let [database (await (db/db))]
    (if (database-error? database)
      database
      (let [acquired
            (await
             (db/execute-many
              {::db/members
               [(pull-member
                database
                [:seon.agent.run/id :seon.agent.run/status
                 :seon.agent.run/closed-reason :seon.agent.run/paused-at
                 :seon.agent.run/turn-limit :seon.agent.run/deadline]
                [:seon.agent.run/id run-id])
                (query-member database current-run-query [id])
                (query-member database repl-mode-query [])
                (query-member database run-turn-count-query [run-id])
                (query-member database run-form-count-query [run-id])]
               ::db/max-result-weight 262144}))
            members (::db/results acquired)]
        (cond
          (database-error? acquired)
          acquired

          (not (and (= 5 (count members))
                    (every? successful-member? members)))
          (read-failure "Agent loop database acquisition failed." acquired)

          :else
          (let [[run-member current-member mode-member turns-member forms-member]
                members]
            {::db/db database
             ::run (member-result run-member)
             ::current-run-id (member-result current-member)
             ::repl-mode (or (member-result mode-member) :batch)
             ::turn-count (or (member-result turns-member) 0)
             ::form-count (or (member-result forms-member) 0)}))))))

(defn- next-event
  "Derive one loop event from a database projection and the empty-turn streak."
  [{::keys [run current-run-id repl-mode turn-count form-count]} streak]
  (let [status (:seon.agent.run/status run)
        reason (:seon.agent.run/closed-reason run)]
    (cond
      (= :closed status)
      (case reason
        :waited     :wait
        :completed  :complete
        :terminated :terminate
        ;; a bound the loop already closed on (turn-limit/deadline/error/
        ;; no-forms) or a supersede/crash cleanup — nothing left to do.
        :superseded)

      ;; The agent's CURRENT open run is no longer THIS run — a newer run owns
      ;; it (supersede) or the pointer was retracted (watchdog). The in-tx CAS
      ;; on `beat!`/`run-turn!` would reject our work anyway, so bail first.
      (not= (:seon.agent.run/id run) current-run-id)
      :superseded

      (:seon.agent.run/paused-at run)
      :pause

      ;; The WORK bound is mode-denominated (repl-milestone rung-0 verdict, 2026-07-10):
      ;; `:batch` counts turns (one turn = many forms of work); `:stream`
      ;; counts FORMS (one form per turn — a prose/orientation turn burns
      ;; nothing, so a green agent is never parked at :turn-limit by its
      ;; own narration).
      (run/turn-limit-reached? (if (= :stream repl-mode) form-count turn-count)
                               (:seon.agent.run/turn-limit run))
      :turn-limit

      (run/deadline-passed? (:seon.agent.run/deadline run) (js/Date.))
      :deadline

      ;; The empty-streak guard: the LLM has produced no actionable forms for a
      ;; full streak of turns — halt the spin (the run's still within both
      ;; bounds, so nothing else would stop it short of the turn-limit cap).
      (>= streak no-forms-streak-limit)
      :no-forms

      :else :turn-ok)))

(defn ^:async ^:private await-bounded
  "Await Promise `p` under the loop's per-step wall-clock bound.

   Races `p` against [[seon.config/turn-timeout-ms]] (`SEON_TURN_TIMEOUT_MS`,
   default 15 min — the INNER bound; the run's deadline stays the outer one)
   via the ONE racer ([[seon.eval/race-timeout]]). When the bound fires the
   caller gets a `:seon/error` VALUE (`:seon.error/message`, never a throw)
   after a loud console.error naming `label` — so a hung step fails into the
   run's :error path instead of parking the loop until the deadline reaper
   fences it. The bound frees the AWAITER only: the underlying work keeps
   running (one event loop, no preemption), and a late settler's run-scoped
   writes are aborted by the run's in-tx CAS work-fence."
  [label p]
  (let [ms (config/turn-timeout-ms)
        v  (await (seval/race-timeout p ms))]
    (if (seval/timed-out? v)
      (let [msg (str label " exceeded the per-turn bound (" ms
                     "ms, SEON_TURN_TIMEOUT_MS) — awaiter freed; late "
                     "writes are CAS-fenced")]
        (js/console.error (str "seon.agent.loop: " msg))
        {:seon.error/message msg})
      v)))

(defn- admission-event
  "Admission event at a turn recurrence boundary."
  []
  (case (::admission/status (admission/state))
    :quiescing :quiesce
    :available :available
    :unavailable))

(defn ^:async ^:private close-loop-run!
  "Close one loop-owned run and return the resulting state or direct error."
  [database run-id reason state event]
  (let [report
        (await
         (await-bounded
          "run/close-run!"
          (run/close-run!
           (cond-> {:seon.agent.run/id run-id
                    :seon.agent.run/closed-reason reason}
             database (assoc ::db/db database)))))]
    (if (database-error? report)
      report
      (transition state event))))

(defn ^:async ^:private close-quiescing-run!
  "Close this loop's still-owned run at a planned drain boundary."
  [agent-id run-id state]
  (log agent-id "halt" "planned runtime quiesce → close run :quiesced")
  ;; Admission changes before the recurrence acquires a database value, so
  ;; this exceptional drain path intentionally lets close-run! acquire head.
  (await (close-loop-run! nil run-id :quiesced state :quiesce)))

(defn ^:async run-loop!
  "Drive agentic turns for `run-id` until the FSM leaves :running.

   `input` carries `:seon.agent/id` and `:seon.agent/llm-fn`.
   A fold of [[seon.agent.transition]] over [[next-event]]: :turn-ok beats
   + runs a turn; the loop closes the run on the bounds it owns (:turn-limit /
   :deadline / :error / :no-forms); function closes (:wait/:complete/:terminate)
   and :superseded are already settled (no re-close). The consecutive empty-turn
   STREAK is folded alongside the state: a turn with zero actionable forms
   increments it, a productive turn resets it, and [[no-forms-streak-limit]]
   empty turns close the run :no-forms (an unresponsive/looping LLM never spins
   to the turn-limit). Every await in the body rides [[await-bounded]] (the
   per-turn INNER bound; the run deadline stays the outer one), so a hung
   turn/write fails the run :error instead of parking the loop. Returns the
   final FSM state. Errors are values — never throws into the trigger."
  {:malli/schema [:=> [:catn [:input [:map [:seon.agent/id :seon.agent/id]]]
                             [:run-id :seon.agent.run/id]]
                  [:or
                   :seon.derive/state
                   [:map [:seon.error/message :string]]
                   [:map
                    [::admission/admitted? [:= false]]
                    [:seon/error :map]]]]}
  [{:seon.agent/keys [id] :as input} run-id]
  (await
    (loop [state :running streak 0]
      (case (admission-event)
        :quiesce (await (close-quiescing-run! id run-id state))
        :unavailable (admission/unavailable)
        :available
        (let [projection (await (acquire-loop-state id run-id))
              event (if (database-error? projection)
                      :error
                      (next-event projection streak))]
        (cond
          (= :turn-ok event)
          (let [beat (await (await-bounded
                              "run/beat!"
                              (db/with-tx-context
                               {::db/db (::db/db projection)}
                               (fn []
                                 (run/beat!
                                  {:seon.agent/id id
                                   :seon.agent.run/id run-id})))))]
            (cond
              ;; The beat WRITE hung past the per-turn bound (a wedged write
              ;; path) — fail the run :error (best-effort close, also bounded)
              ;; rather than parking the loop.
              (database-error? beat)
              (let [latest (await (acquire-loop-state id run-id))
                    latest-event (if (database-error? latest)
                                   :error
                                   (next-event latest 0))]
                (if (contains? #{:superseded :wait :complete :terminate}
                               latest-event)
                  (do (log id "halt"
                           (str "beat rejected/closed — " (name latest-event)))
                      (transition state latest-event))
                  (do (log id "halt" "beat failed → close run :error")
                      (await
                       (close-loop-run! (::db/db latest) run-id
                                        :error state :error)))))

              :else
              (case (admission-event)
                :quiesce (await (close-quiescing-run! id run-id state))
                :unavailable (admission/unavailable)
                :available
                (let [r (await (await-bounded
                                    "turn/run-turn!"
                                    (turn/run-turn!
                                      {:seon.agent/id id
                                       :seon.agent/llm-fn
                                       (:seon.agent/llm-fn input)
                                       :seon.agent.run/id run-id
                                       :seon.db/db (::db/db projection)})))
                    ;; A turn that errored (LLM error / catastrophic), a result
                    ;; that created NO turn (no `:seon.agent.turn/id` — e.g. a
                    ;; fenced/failed open-tx that left no entity), OR a turn that
                    ;; hung past the per-turn bound (await-bounded's
                    ;; `:seon.error/message` value also has no turn-id) is NOT a
                    ;; no-op success. The id-absence clause is the structural
                    ;; guarantee: a failed/fenced/hung open can NEVER masquerade
                    ;; as a successful no-op turn (which would recur `:turn-ok`
                    ;; forever — a retry storm).
                    errored? (or (= :error (:seon.agent.turn/status r))
                                 (database-error? r)
                                 (nil? (:seon.agent.turn/id r)))]
                (case (admission-event)
                  :quiesce (await (close-quiescing-run! id run-id state))
                  :unavailable (admission/unavailable)
                  :available
                  (if errored?
                  ;; §8c — distinguish LOST AUTHORITY (the turn's leading CAS
                  ;; aborted: open-turn! rejected because the run was
                  ;; superseded/watchdog-closed mid-LLM) from a genuine turn
                  ;; error. Re-derive over the LATEST db (streak 0 — purely to
                  ;; classify): a stop event means the run is no longer ours to
                  ;; close → route there; otherwise the loop owns the :error
                  ;; close.
                  (let [latest (await (acquire-loop-state id run-id))
                        ev (if (database-error? latest)
                             :error
                             (next-event latest 0))]
                    (if (contains? #{:superseded :wait :complete :terminate} ev)
                      (do (log id "halt" (str "turn rejected/closed — " (name ev)))
                          (transition state ev))
                      (do (log id "halt" "turn :error → close run :error")
                          (await
                           (close-loop-run! (::db/db latest) run-id
                                            :error state :error)))))
                  ;; A productive turn (any actionable form ⇒ eval-count > 0)
                  ;; resets the streak; a turn with zero forms extends it
                  ;; (next-event halts at the cap). eval-count counts ATTEMPTED
                  ;; forms (ok + failed), so a turn whose forms all ERRORED is
                  ;; NOT empty — it yields a next turn that shows the error.
                  (do
                    ;; stuck×N → frontier re-plan escalation: the turn's evals
                    ;; just landed, so the derived flag can only TRANSITION
                    ;; here. maybe-consult! recomputes the wedge query and
                    ;; fires the once-per-episode planner message (both sides
                    ;; derived — see my.plan.internal's escalation section);
                    ;; errors are values, a failed consult never stops the
                    ;; loop.
                    (await (await-bounded
                             "plan/maybe-consult!"
                             (plan-internal/maybe-consult! {:seon.agent/id id})))
                    (recur (transition state :turn-ok)
                           (if (zero? (or (:seon.agent/eval-count r) 0))
                             (inc streak)
                             0)))))))))

          (= :error event)
          (do (log id "halt" "database read failed → close run :error")
              ;; There is no usable database value when acquisition itself
              ;; failed; close-run! is allowed to acquire current only here.
              (await (close-loop-run! nil run-id :error state :error)))

          (= :no-forms event)
          (do (log id "halt"
                   (str "no actionable forms for " no-forms-streak-limit
                        " turns → close run :no-forms"))
              (await
               (close-loop-run! (::db/db projection) run-id
                                :no-forms state :no-forms)))

          (= :turn-limit event)
          (do (log id "halt" "turn-limit reached → close run")
              (await
               (close-loop-run! (::db/db projection) run-id
                                :turn-limit state :turn-limit)))

          (= :deadline event)
          (do (log id "halt" "deadline passed → close run")
              (await
               (close-loop-run! (::db/db projection) run-id
                                :deadline-exceeded state :deadline)))

          (= :superseded event)
          (do (log id "halt" "superseded — a newer run owns the agent")
              (transition state :superseded))

          ;; :wait / :complete / :terminate / :pause — the run is already
          ;; closed/paused by the function; the loop just stops, recording the
          ;; FSM state the function moved to.
          :else
          (do (log id "halt" (str "function — " (name event)))
              (transition state event))))))))

(defn- drive-run-loop!
  "Share one active run loop per agent, including replay and hot reload."
  [input run-id]
  (let [id (:seon.agent/id input)]
    (if-let [{active-run-id ::run-id active-promise ::promise}
             (get @!run-loop-promises id)]
      (if (= run-id active-run-id)
        active-promise
        (.then active-promise (fn [] (drive-run-loop! input run-id))))
      (let [promise (js/Promise.resolve (run-loop! input run-id))]
        (swap! !run-loop-promises assoc id
               {::run-id run-id ::promise promise})
        (do
          (.finally
           promise
           (fn []
             (swap! !run-loop-promises
                    (fn [active]
                      (if (identical? promise
                                      (get-in active [id ::promise]))
                        (dissoc active id)
                        active)))))
          promise)))))

(defn ^:async ^:private renew-current-run!
  "Renew the agent's CURRENT open run's lease — the new message extends both
   bounds (the sliding window). Shared by the :running wake branch and the
   :idle CAS-loss path (a wake that lost the atomic open race). A no-op when
   the agent has no open run. Caller establishes the `with-agent` scope."
  [id run-id]
  (when run-id
    (await (run/renew! {:seon.agent/id     id
                        :seon.agent.run/id run-id}))))

(defn ^:async ^:private acquire-agent-state
  "Read one agent and its current run from one database value."
  ([id] (await (acquire-agent-state id nil)))
  ([id database]
   (let [database (or database (await (db/db)))]
     (if (database-error? database)
       database
       (let [agent
             (await
              (db/pull
               {::db/db database
                ::db/pull-pattern
                [:db/id :seon.agent/terminated-at
                 {:seon.agent/run
                  [:seon.agent.run/id :seon.agent.run/status
                   :seon.agent.run/paused-at]}]
                ::db/ref [:seon.agent/id id]}))]
         (if (database-error? agent)
           agent
           (let [current-run (:seon.agent/run agent)
                 open? (= :open (:seon.agent.run/status current-run))]
             {::db/db database
              ::agent agent
              ::current-run-id (when open? (:seon.agent.run/id current-run))
              ::state
              (derive/state-from-primitives
               (cond-> {:seon.agent.run/open? open?}
                 (:seon.agent/terminated-at agent)
                 (assoc :seon.agent/terminated-at
                        (:seon.agent/terminated-at agent))
                 (and open? (:seon.agent.run/paused-at current-run))
                        (assoc :seon.agent.run/paused-at
                               (:seon.agent.run/paused-at current-run))))})))))))

(def ^:private pending-inbound-query
  {:find '[?message ?message-tx]
   :in '[$ ?agent-id ?hop-cap]
   :where
   '[[?agent :seon.agent/id ?agent-id]
     [?message :seon.agent.message/to ?agent ?message-tx]
     [?message :seon.agent.message/from ?sender]
     [(not= ?sender ?agent)]
     [(get-else $ ?message :seon.agent.message/origin :agent) ?origin]
     [(not= ?origin :core)]
     [(get-else $ ?message :seon.agent.message/hops 0) ?hops]
     [(< ?hops ?hop-cap)]
     (not-join [?agent ?message-tx]
       [?run :seon.agent.run/agent ?agent]
       [?run :seon.agent.run/status :closed ?close-tx]
       [?run :seon.agent.run/closed-reason ?close-reason]
       [(not= ?close-reason :quiesced)]
       [(>= ?close-tx ?message-tx)])]
   :order-by '[?message-tx :asc ?message :asc]
   :limit 1})

(defn ^:async ^:private acquire-committed-work
  "Read current run ownership and uncovered inbound work at one database value."
  [id]
  (let [database (await (db/db))]
    (if (database-error? database)
      database
      (let [acquired
            (await
             (db/execute-many
              {::db/db database
               ::db/members
               [(pull-member
                 database
                 [:db/id :seon.agent/terminated-at
                  {:seon.agent/run
                   [:seon.agent.run/id :seon.agent.run/status
                    :seon.agent.run/paused-at]}]
                 [:seon.agent/id id])
                (assoc (query-member database pending-inbound-query
                                     [id warn/hop-cap])
                       :datahike.resource/max-results 65536
                       :datahike.resource/max-result-weight 4096)]
               ::db/max-result-weight 524288}))
            members (::db/results acquired)]
        (cond
          (database-error? acquired)
          acquired

          (not (and (= 2 (count members))
                    (every? successful-member? members)))
          (read-failure "Committed agent work acquisition failed." acquired)

          :else
          (let [[agent-member messages-member] members
                agent (member-result agent-member)
                current-run (:seon.agent/run agent)
                open? (= :open (:seon.agent.run/status current-run))]
            {::db/db database
             ::agent agent
             ::current-run-id
             (when open? (:seon.agent.run/id current-run))
             ::pending-inbound-eid
             (when-not open?
               (ffirst (member-result messages-member)))
             ::state
             (derive/state-from-primitives
              (cond-> {:seon.agent.run/open? open?}
                (:seon.agent/terminated-at agent)
                (assoc :seon.agent/terminated-at
                       (:seon.agent/terminated-at agent))
                (and open? (:seon.agent.run/paused-at current-run))
                (assoc :seon.agent.run/paused-at
                       (:seon.agent.run/paused-at current-run))))}))))))

;; ============================================================
;; Wake — the per-tx listener fires on every transact; we filter for new
;; INBOUND messages (to ∋ me, from ≠ me, waking origin — message/inbound-msg-datom?)
;; and, if the agent's derived state is :idle, OPEN A RUN and start the loop.
;; An already-:running agent RENEWS its open run's lease (the new message
;; extends both bounds — the sliding window). The idle→running open is
;; ATOMIC (a CAS in [[seon.agent.run/open-run!]]): if a concurrent wake won
;; the race, this wake's open returns the db error envelope and we RENEW the
;; winner's run instead (same effect as the :running branch).
;;
;; Hop guard lives HERE (at wake): a message whose hops reached warn/hop-cap
;; wakes nothing — loud console.error + the clustered check-hop-exhausted
;; warning surface the refusal.
;; ============================================================

(defn- with-agent-repl
  "Run one asynchronous agent-owned callback with final provenance.

   Wake listeners execute inside the transaction fiber that notified them;
   without this boundary, an inbound caller's explicit transaction user can
   survive `setTimeout` and override `with-agent`. Every wake, renew, and
   re-drive therefore enters both the agent identity and its REPL process."
  [id f]
  (db/with-agent id
    (fn []
      (db/with-tx-context
        {:seon.db/user [:seon.agent/id id]
         :seon.db/process [:seon.db.process/id :seon.db.process/repl]}
        f))))

(defn- schedule-renew! [id run-id]
  (js/setTimeout
    (fn []
      (-> (js/Promise.resolve
            (with-agent-repl
              id
              (fn ^:async renew! []
                (await (renew-current-run! id run-id)))))
          (.catch
            (fn [exception]
              (js/console.error
                (str "seon.agent.loop: wake renew threw for " id ": "
                     (or (.-message exception) exception)))))))
    0))

(defn ^:async ^:private open-or-renew-message-run!
  "Open and drive one message run, or renew the concurrent CAS winner."
  [input cause-eid]
  (let [id (:seon.agent/id input)
        opened
        (await
         (run/open-run!
          {:seon.agent/id id
           :seon.agent.run/trigger :message
           :seon.agent.run/cause cause-eid}))]
    (if-not (database-error? opened)
      (await (drive-run-loop! input (:seon.agent.run/id opened)))
      (let [latest (await (acquire-agent-state id))]
        (if (database-error? latest)
          (js/console.error
           (str "seon.agent.loop: open-run! FAILED for " id ": "
                (pr-str opened) "; refresh failed: " (pr-str latest)))
          (if-let [winner (::current-run-id latest)]
            (await (renew-current-run! id winner))
            (js/console.error
             (str "seon.agent.loop: open-run! FAILED for " id ": "
                  (pr-str opened)))))))))

(defn- schedule-message-run! [input cause-eid]
  (let [id (:seon.agent/id input)]
    (js/setTimeout
      (fn []
        (-> (js/Promise.resolve
              (with-agent-repl
                id
                (fn ^:async wake! []
                  (await (open-or-renew-message-run! input cause-eid)))))
            (.catch
              (fn [exception]
                (js/console.error
                  (str "seon.agent.loop: wake loop threw for " id ": "
                       (or (.-message exception) exception))))))
      0))))

(defn wake-handler
  "Return the tx-listener handler for `input`'s agent.

   The database interest supplies added datoms and the exact database value
   that contains them. The handler reads the agent and candidate messages at
   that same value, then opens or renews the run."
  {:malli/schema [:=> [:catn [:input [:map [:seon.agent/id :seon.agent/id]]]] :any]}
  [{:seon.agent/keys [id] :as input}]
  (fn ^:async handle-datoms! [{database :db-after tx-data :tx-data}]
    (if-not (admission/available?)
      (admission/unavailable)
      (let [candidates
            (into []
                  (filter (fn [[_ attribute _ _ added?]]
                            (and added?
                                 (= :seon.agent.message/to attribute))))
                  tx-data)]
        (when (seq candidates)
          (let [message-eids (into [] (comp (map first) (distinct)) candidates)
                values
                (await
                 (db/pull-many
                  database
                  [:db/id :seon.agent/terminated-at
                   {:seon.agent/run
                    [:seon.agent.run/id :seon.agent.run/status
                     :seon.agent.run/paused-at]}
                   :seon.agent.message/id :seon.agent.message/hops
                   :seon.agent.message/origin
                   {:seon.agent.message/from [:db/id]}]
                  (into [[:seon.agent/id id]] message-eids)))
                agent (first values)
                messages (rest values)]
            (if (database-error? values)
              values
              (if (or (nil? agent)
                    (some nil? messages))
              (read-failure "Wake message database acquisition failed." values)
              (let [my-eid (:db/id agent)
                    message-by-eid (into {} (map (juxt :db/id identity)) messages)
                    inbound
                    (into []
                          (filter
                           (fn [[eid _attribute target _transaction _added?]]
                             (let [message (get message-by-eid eid)]
                               (and (= target my-eid)
                                    (message/waking-inbound? message my-eid)))))
                          candidates)
                    {waking true exhausted false}
                    (group-by
                     (fn [[eid]]
                       (message/hop-live? (get message-by-eid eid)))
                     inbound)
                    current-run (:seon.agent/run agent)
                    open? (= :open (:seon.agent.run/status current-run))
                    state
                    (derive/state-from-primitives
                     (cond-> {:seon.agent.run/open? open?}
                       (:seon.agent/terminated-at agent)
                       (assoc :seon.agent/terminated-at
                              (:seon.agent/terminated-at agent))
                       (and open? (:seon.agent.run/paused-at current-run))
                       (assoc :seon.agent.run/paused-at
                              (:seon.agent.run/paused-at current-run))))]
                (doseq [[eid] exhausted]
                  (let [msg (get message-by-eid eid)]
                    (js/console.error
                     (str "seon.agent.loop: WAKE REFUSED for agent " id
                          " — message " (:seon.agent.message/id msg)
                          " hops=" (:seon.agent.message/hops msg)
                          " reached hop-cap " warn/hop-cap
                          " (agent↔agent ping-pong guard). A human message"
                          " resets the chain (hops 0)."))))
                (when (seq waking)
                  (case state
                    :terminated
                    (log id "wake skipped" "terminated — change state first")

                    :paused
                    (log id "wake skipped" "paused — resume first")

                    :running
                    (schedule-renew! id (:seon.agent.run/id current-run))

                    :idle
                    (schedule-message-run! input (ffirst waking)))))))))))))

;; ============================================================
;; Drive committed work. A host may start after its first message committed,
;; or restart while a run is open. The one driver re-enters that open run or
;; opens the oldest waking inbound message not covered by a later run close.
;; The listener is installed before this runs, so a concurrent message and
;; this reconciliation race only on open-run!'s existing absent-pointer CAS.
;; ============================================================

(defn drive-run!
  "Drive committed work for one hosted agent.

   Re-enters an existing open run. When the agent is idle, opens a message run
   for the oldest waking inbound message not covered by a later run close,
   then drives it. Messages already folded into a closed run never replay.
   Fire-and-forget: schedules the work on the macrotask queue. A loud no-op
   when the agent was never armed in this process."
  {:malli/schema [:=> [:catn [:input [:map [:seon.agent/id :seon.agent/id]]]] :any]}
  [{:seon.agent/keys [id]}]
  (if-not (admission/available?)
    (admission/unavailable)
    (if-let [input (get @!loop-input id)]
      (js/setTimeout
       (fn []
         (->
          (js/Promise.resolve
           (with-agent-repl
             id
             (fn ^:async drive-committed! []
               (let [work (await (acquire-committed-work id))]
                 (cond
                   (database-error? work)
                   (js/console.error
                    (str "seon.agent.loop: committed work acquisition FAILED for "
                         id ": " (pr-str work)))

                   (= :running (::state work))
                   (await (drive-run-loop! input (::current-run-id work)))

                   (and (= :idle (::state work))
                        (::pending-inbound-eid work))
                   (await
                    (open-or-renew-message-run!
                     input (::pending-inbound-eid work))))))))
          (.catch
           (fn [e]
             (js/console.error
              (str "seon.agent.loop: drive-run! threw for "
                   id ": " (or (.-message e) e)))))))
       0)
      (js/console.warn
       (str "seon.agent.loop: drive-run! — no live loop input for agent " id
            " (the wake trigger was never armed in this process); cannot drive "
            "committed work.")))))

;; ============================================================
;; Scheduled-fn execution — the action half of cron. Injected into
;; [[seon.agent.schedule/fire-due-schedules!]] (which can't require this ns —
;; it's a leaf the ticker calls). When a schedule fires, the DUE schedules' fns
;; run HERE as a SCHEDULE-FIRE turn on the just-opened :schedule run: each
;; `(the-fn)` is eval-batched in the agent's scope (the SAME path
;; bootstrap-turn-0 and every LLM turn use), recorded as a `:seon.eval` the
;; agent re-reads next turn — so the fired agent sees "the schedule fired and
;; ran THIS", not a blind wake.
;;
;; The turn is stamped `:seon.agent.turn/scheduled? true`: it KEEPS the run
;; stamp (so the transcript's agent→runs→turns walk renders its evals — the
;; agent must SEE the result) but the loop's work-count query excludes it, so
;; a fire never burns a turn from the run's work budget (turn-limit). Without
;; that marker every cron tick stole an LLM turn — at turn-limit fires the run
;; would close `:turn-limit` having done zero LLM work (#66).
;;
;; A broken scheduled fn records a failed eval (errors are values), never
;; crashing the ticker. The existing per-agent execution child owns eval and
;; its compiler, so this needs no compiler in the pod or per-agent loop input.
;; ============================================================

(schema/register! ::exec-request
  [:map
   [:seon.agent/id           :seon.agent/id]
   [:seon.agent.schedule/fns :seon.agent.schedule/fns]])

(defn ^:async exec-scheduled-fns!
  "Run the due schedule `fns` for `id` as ONE schedule-fire turn.

   On the agent's currently-open run — each fn invocation eval-batched in the agent's scope and
   recorded as a `:seon.eval` (with a `;` narration noting the schedule fired).
   The turn is stamped `:seon.agent.turn/scheduled? true` so it RENDERS in the
   transcript (run-stamped) yet does NOT count toward turn-limit
   (the loop's work-count query excludes it — #66). A no-op when the agent
   has no open run (a supersede/close raced the fire). Errors are values
   (eval-batch! records a failed eval per form). Injected into
   [[seon.agent.schedule/fire-due-schedules!]] as `:seon.agent.schedule/exec-fn!`.
   `^:async`."
  {:malli/schema [:=> [:cat ::exec-request] :any]}
  [{:seon.agent/keys [id] fns :seon.agent.schedule/fns}]
  (let [database (await (acquire-agent-state id))]
    (if (database-error? database)
      database
      (await
       (db/with-agent id
         (fn ^:async run-scheduled! []
           (when-let [run-id (::current-run-id database)]
             (let [database-value (::db/db database)
                   source  (str/join "\n"
                           (map (fn [s]
                                  (str ";; schedule fired — running " s "\n(" s ")"))
                                fns))]
             (await
              (db/with-tx-context
               {:seon.db/user [:seon.agent/id id]
                :seon.db/process
                [:seon.db.process/id :seon.db.process/repl]}
               (fn ^:async open-scheduled-turn! []
                 (await
                  (turn/open-turn!
                   {:seon.agent/id               id
                    :seon.agent.run/id-of-run    run-id
                    :seon.agent.turn/scheduled?  true
                    :seon.agent.turn/prompt-text ""
                    :seon.db/db database-value}
                   (fn ^:async eval-scheduled! [turn-id]
                     (await
                      (turn/eval-parsed!
                       id database-value (repl-internal/parse-forms source)
                       (home/home-ns id) turn-id run-id))))))))))))))))

(defn install-wake-trigger!
  "Register the inbound-message wake trigger for this agent.

   Wakes a run via
   [[wake-handler]] when a message lands with to ∋ me AND from ≠ me AND a
   waking origin. Registering the stable key atomically replaces any prior
   handler, so hot reload cannot create a delivery gap or duplicate callback.

   Input map:
     :seon.agent/id              the agent's id string
     :seon.agent/llm-fn          ctx-string -> Promise<{:text \"…\"}>"
  {:malli/schema [:=> [:catn [:input [:map [:seon.agent/id :seon.agent/id]]]] :any]}
  [{:seon.agent/keys [id] :as input}]
  ;; Stamp the loop input so RESUME can re-drive the open run with this
  ;; agent's live llm-fn (refreshed on every re-arm — see the !loop-input block
  ;; comment). Same staleness profile as the wake handler.
  (swap! !loop-input assoc id input)
  ;; One stable listener key per agent so re-arming REPLACES the prior
  ;; listener (a hot reload must not leave two listeners firing for one
  ;; agent).
  (let [k [:seon.agent/user-message-trigger id]]
    (db/listen!
      {:seon.db/key     k
       :seon.db/datom-patterns
       [{:seon.db/a :seon.agent.message/to :seon.db/added? true}]
       :seon.db/handler (wake-handler input)})))

(defn uninstall-wake-trigger!
  "Remove one agent's wake listener and process-local loop input.

   Idempotent. This is the inverse of [[install-wake-trigger!]] and is the
   required process cleanup for termination or an explicit unhost. Removing
   both cells is load-bearing: retaining only `!loop-input` would let a later
   resume drive a terminated agent with a stale provider handle."
  {:malli/schema
   [:=> [:catn [:input [:map [:seon.agent/id :seon.agent/id]]]]
    [:map
     [:seon.agent/id :seon.agent/id]
     [:seon.agent.loop/uninstalled? [:= true]]]]}
  [{:seon.agent/keys [id]}]
  (swap! !loop-input dissoc id)
  (try
    (db/unlisten! {:seon.db/key [:seon.agent/user-message-trigger id]})
    (catch :default _ nil))
  {:seon.agent/id id
   :seon.agent.loop/uninstalled? true})

(schema/register! ::uninstalled-ids [:vector :seon.agent/id])
(schema/register! ::uninstall-all-wake-triggers-response
  [:map {:closed true}
   [::uninstalled-ids ::uninstalled-ids]])

(defn uninstall-all-wake-triggers!
  "Remove every process-local agent wake listener and loop input."
  {:malli/schema
   [:=> [:cat] ::uninstall-all-wake-triggers-response]}
  []
  (let [ids (-> @!loop-input keys sort vec)]
    (run! (fn [id]
            (uninstall-wake-trigger! {:seon.agent/id id}))
          ids)
    {::uninstalled-ids ids}))

;; ============================================================
;; The ONE ticker — the only active machinery. The DB is passive about
;; wall-clock: a `deadline` is past or a cron is due in the cluster, but nothing
;; fires until something CHECKS. This single `setInterval` is that check —
;; each tick (1) closes overdue runs ([[seon.agent.run/close-overdue-runs!]],
;; the deadline watchdog) then (2) fires due schedules
;; ([[seon.agent.schedule/fire-due-schedules!]]), RUNNING each due schedule's fn
;; via [[exec-scheduled-fns!]] and DRIVING each opened run via [[drive-run!]]
;; (both injected so seon.agent.schedule need not require this ns).
;; Idempotent + single-instance (the `!ticker` atom holds the interval id;
;; re-install clears the prior). A throw in one tick is logged, never fatal —
;; the timer keeps running. Wired at client boot beside `install-wake-trigger!`
;; and re-armed idempotently on hot reload.
;; ============================================================

(defonce ^:private !ticker (atom nil))

(def default-tick-ms
  "Ticker cadence (ms) when SEON_TICK_MS is unset. Coarse on purpose — the
   watchdog need only catch an overrun within ~one cadence, and every tick is
   cheap DB reads plus at most a few transacts."
  30000)

(defn- run-tick!
  "ONE ticker pass at `now`: close overdue runs, close STALE (wedged) runs
   (the heartbeat watchdog — Piece 2c), then fire due schedules (driving each
   opened run). Returns a Promise; a throw anywhere is caught + logged so the
   interval survives. The watchdog rides THIS one ticker — no parallel
   setInterval; the scan core (`run/stale-run-ids`) is pure over acquired rows."
  [now]
  (if-not (admission/available?)
    (js/Promise.resolve (admission/unavailable))
    (-> (js/Promise.resolve
          (run/close-overdue-runs! {:seon.agent/now now}))
      (.then (fn [_]
               (run/close-stale-runs!
                 {:seon.agent/now now})))
      (.then (fn [_]
               (schedule/fire-due-schedules!
                 {:seon.agent/now               now
                  :seon.agent.schedule/exec-fn! exec-scheduled-fns!
                  :seon.agent.schedule/drive!   drive-run!})))
      (.catch (fn [e]
                (seon-log/error-console!
                  "seon.agent.loop/run-tick!"
                  (str "tick failed (timer continues): " (or (.-message e) e))))))))

(defn install-ticker!
  "Install the ONE periodic ticker (idempotent, single instance).

   Clear any
   prior interval, then `setInterval` [[run-tick!]] every SEON_TICK_MS ms
   (default [[default-tick-ms]]). Returns the interval id. Wired at client
   boot beside [[install-wake-trigger!]]; safe to re-run on hot reload — it
   clears the prior interval first, so reloads never stack timers."
  {:malli/schema [:=> [:catn] :any]}
  []
  (when-let [id @!ticker] (js/clearInterval id))
  (let [ms (or (config/tick-ms) default-tick-ms)
        id (js/setInterval (fn [] (run-tick! (js/Date.))) ms)]
    (reset! !ticker id)
    (seon-log/info-console! "seon.agent.loop/install-ticker!"
                            "ticker installed" {:seon.agent.loop/tick-ms ms})
    id))

(defn uninstall-ticker!
  "Stop the periodic ticker (clearInterval + drop the stored id).

   For tests + clean reload."
  {:malli/schema [:=> [:catn] :any]}
  []
  (when-let [id @!ticker]
    (js/clearInterval id)
    (reset! !ticker nil)))

;; ============================================================
;; Activity log — a DERIVED timeline over the agent's RUNS. One row per run,
;; ordered by `:seon.agent.run/started-at`: the wake (trigger + cause-message
;; content) and, when the run is closed, its `closed-reason` as the
;; stop-reason. A function of the DB; nothing stored that isn't already a run
;; datom (the web UI's lifecycle split reads the latest row's stop-reason).
;; ============================================================

(schema/register! :seon.agent.loop/activity-request
  [:map [:seon.agent/id :string]])

(schema/register! :seon.agent.loop/activity-entry
  [:map
   [:seon.agent.loop/at          :inst]
   [:seon.agent/state            :seon.derive/state]
   [:seon.agent.loop/stop-reason {:optional true} :seon.agent.run/closed-reason]
   [:seon.agent.loop/cause       {:optional true} :string]])

(schema/register! :seon.agent.loop/activity-response
  [:map [:seon.agent.loop/entries [:vector :seon.agent.loop/activity-entry]]])

(schema/register! :seon.agent.loop/activity-result
  [:or :seon.agent.loop/activity-response
   [:map [:seon.error/message :string]]])

(defn ^:async activity-log
  "Return the agent's activity log — a DERIVED timeline of its RUNS.

   Ordered by `:seon.agent.run/started-at`. Map-in, map-out.

   Each entry: `:seon.agent.loop/at` (the run's started-at), `:seon.agent/state`
   (the run's projected state via [[seon.derive/state-from-primitives]] —
   :terminated when closed :terminated, :paused when the open run is paused,
   :running while open, else :idle), the run's `:seon.agent.loop/stop-reason`
   (= `:seon.agent.run/closed-reason` when closed), and — when the run was a
   `:message` trigger — `:seon.agent.loop/cause` (the waking message's content).
   A function of the DB; no stored log to clear."
  {:malli/schema [:=> [:cat :seon.agent.loop/activity-request]
                       :seon.agent.loop/activity-result]}
  [{:seon.agent/keys [id]}]
  (let [rows
        (await
          (db/query
            {::db/query
             '[:find (pull ?run
                           [:seon.agent.run/status
                            :seon.agent.run/closed-reason
                            :seon.agent.run/paused-at
                            {:seon.agent.run/cause
                             [:seon.agent.message/content]}])
                    ?started
               :in $ ?agent-id
               :where
               [?agent :seon.agent/id ?agent-id]
               [?run :seon.agent.run/agent ?agent]
               [?run :seon.agent.run/started-at ?started]]
             ::db/args [id]}))]
    (if (:seon.error/message rows)
      rows
      {:seon.agent.loop/entries
       (->> rows
            (sort-by #(.getTime ^js (second %)))
            (mapv (fn [[run started]]
                    (let [reason (:seon.agent.run/closed-reason run)
                          open? (= :open (:seon.agent.run/status run))
                          cause (get-in run [:seon.agent.run/cause
                                             :seon.agent.message/content])
                          state
                          (derive/state-from-primitives
                            (cond-> {:seon.agent.run/open? open?}
                              (= reason :terminated)
                              (assoc :seon.agent/terminated-at started)
                              (:seon.agent.run/paused-at run)
                              (assoc :seon.agent.run/paused-at
                                     (:seon.agent.run/paused-at run))))]
                      (cond-> {:seon.agent.loop/at started
                               :seon.agent/state state}
                        reason (assoc :seon.agent.loop/stop-reason reason)
                        cause (assoc :seon.agent.loop/cause cause))))))})))
