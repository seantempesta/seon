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
   STILL names this run. A superseded run's beat returns `ok? false` (lost
   authority → terminate); its turn-open is rejected at commit. Between turns
   the next iteration re-reads the latest db and `next-event` sees the moved
   pointer → :superseded (§8c).

   Wake = read-derived-then-open. An inbound datom fires the per-tx listener;
   the handler derives the agent's state from the local db snapshot. If :idle
   → `open-run!` ({trigger :message, cause the message}) then `run-loop!` on
   that run. If already :running → `renew!` (the new message extends the lease
   — the sliding window is now lease renewal). The idle→running open is ATOMIC
   (a CAS on `:seon.agent/run` being absent — [[seon.agent.run/open-run!]]):
   two simultaneous idle wakes can't both open, the loser's open returns the
   db error envelope and it RENEWS the winner's run instead (no orphaned run).

   Requires `seon.agent` (the wake gate `inbound-msg-datom?`), `seon.derive`
   (the one derived-state/turn-count leaf), `seon.agent.run` (the run
   lifecycle), `seon.agent.turn` (`run-turn!`). The boot path (`seon.client`)
   requires THIS ns to `install-wake-trigger!`."
  (:require
    [clojure.string :as str]
    [my.plan.internal :as plan-internal]
    [seon.agent :as agent]
    [seon.agent.ctx :as ctx]
    [seon.agent.home :as home]
    [seon.agent.run :as run]
    [seon.agent.schedule :as schedule]
    [seon.agent.turn :as turn]
    [seon.config :as config]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.eval :as seval]
    [seon.log :as seon-log]
    [seon.repl :as repl]
    [seon.repl.internal :as repl-internal]
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
         :superseded :error :no-forms :pause :terminate :resume])

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
                :terminate  :terminated}
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
;; (`:seon.agent/id` / `:seon.agent/llm-fn` / `:seon.agent/compile-state`) the
;; wake trigger was (re)armed with. `install-wake-trigger!` (re)stamps it on
;; EVERY arm, so it stays exactly as fresh as the live wake-handler closure (a
;; hot reload re-arms with a freshly-resolved llm-fn). A genuinely stateful
;; runtime artifact — the llm-fn is a closure and compile-state is the live
;; bootstrap, neither DB-derivable — so a registry is the right home (same
;; class as `seon.agent.run/!runs-this-process`, not a derivable-state store).
;; `drive-run!` reads it to RE-ENTER the loop on RESUME (the loop exits on
;; :pause; resume must re-drive the still-open run). `defonce` survives a hot
;; reload; the re-arm repopulates it regardless.
;; ============================================================

(defonce ^:private !loop-input (atom {}))

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

(defn- next-event
  "Derive the loop event from the run's data over the FROZEN db value `db`
   (§8a — one basis-t per turn) + the consecutive empty-turn `streak` (no side
   effects). One of :wait/:complete/:terminate (a function closed the run inside a
   turn), :superseded (a newer run owns the agent OR the pointer was
   retracted), :pause, :turn-limit, :deadline, :no-forms (the LLM produced zero
   actionable forms for [[no-forms-streak-limit]] turns running), or :turn-ok
   (run another turn)."
  [db id run-id streak]
  (let [r      (db/entity {:seon.db/db db :seon.db/ref [:seon.agent.run/id run-id]})
        status (:seon.agent.run/status r)
        reason (:seon.agent.run/closed-reason r)
        cur    (derive/current-run db id)]
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
      (not= run-id (:seon.agent.run/id cur))
      :superseded

      (:seon.agent.run/paused-at r)
      :pause

      ;; The WORK bound is mode-denominated (repl-milestone rung-0 verdict, 2026-07-10):
      ;; `:batch` counts turns (one turn = many forms of work); `:stream`
      ;; counts FORMS (one form per turn — a prose/orientation turn burns
      ;; nothing, so a green agent is never parked at :turn-limit by its
      ;; own narration).
      (run/turn-limit-reached? (if (= :stream (ctx/repl-mode db))
                                 (derive/run-form-count db run-id)
                                 (derive/run-turn-count db run-id))
                               (:seon.agent.run/turn-limit r))
      :turn-limit

      (run/deadline-passed? (:seon.agent.run/deadline r) (js/Date.))
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

(defn ^:async run-loop!
  "Drive agentic turns for `run-id` until the FSM leaves :running.

   `input` carries `:seon.agent/id` / `:seon.agent/llm-fn` / `:seon.agent/compile-state`.
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
                  :seon.derive/state]}
  [{:seon.agent/keys [id] :as input} run-id]
  (await
    (loop [state :running streak 0]
      (let [db    @db/*conn*               ; §8a — ONE frozen basis-t per turn
            event (next-event db id run-id streak)]
        (cond
          (= :turn-ok event)
          (let [beat (await (await-bounded
                              "run/beat!"
                              (run/beat! {:seon.agent/id id :seon.agent.run/id run-id})))]
            (cond
              ;; The beat WRITE hung past the per-turn bound (a wedged write
              ;; path) — fail the run :error (best-effort close, also bounded)
              ;; rather than parking the loop.
              (:seon.error/message beat)
              (do (log id "halt" "beat hung past per-turn bound → close run :error")
                  (await (await-bounded
                           "run/close-run!"
                           (run/close-run!
                             {:seon.agent.run/id            run-id
                              :seon.agent.run/closed-reason :error})))
                  (transition state :error))

              ;; §8c — the beat's leading CAS aborted: a watchdog/newer run
              ;; moved (or retracted) the pointer between next-event and now.
              ;; Authority lost; terminate cleanly (no re-close — the new owner
              ;; / watchdog owns the run).
              (false? (:seon.db/ok? beat))
              (do (log id "halt" "beat fence lost — superseded; loop terminates")
                  (transition state :superseded))

              :else
              (let [r      (await (await-bounded
                                    "turn/run-turn!"
                                    (turn/run-turn!
                                      (assoc input :seon.agent.run/id run-id :seon.db/db db))))
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
                                 (false? (:seon.db/ok? r))
                                 (nil? (:seon.agent.turn/id r)))]
                (if errored?
                  ;; §8c — distinguish LOST AUTHORITY (the turn's leading CAS
                  ;; aborted: open-turn! rejected because the run was
                  ;; superseded/watchdog-closed mid-LLM) from a genuine turn
                  ;; error. Re-derive over the LATEST db (streak 0 — purely to
                  ;; classify): a stop event means the run is no longer ours to
                  ;; close → route there; otherwise the loop owns the :error
                  ;; close.
                  (let [ev (next-event @db/*conn* id run-id 0)]
                    (if (contains? #{:superseded :wait :complete :terminate} ev)
                      (do (log id "halt" (str "turn rejected/closed — " (name ev)))
                          (transition state ev))
                      (do (log id "halt" "turn :error → close run :error")
                          (await (await-bounded
                                   "run/close-run!"
                                   (run/close-run!
                                     {:seon.agent.run/id            run-id
                                      :seon.agent.run/closed-reason :error})))
                          (transition state :error))))
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
                             0)))))))

          (= :no-forms event)
          (do (log id "halt"
                   (str "no actionable forms for " no-forms-streak-limit
                        " turns → close run :no-forms"))
              (await (await-bounded
                       "run/close-run!"
                       (run/close-run!
                         {:seon.agent.run/id            run-id
                          :seon.agent.run/closed-reason :no-forms})))
              (transition state :no-forms))

          (= :turn-limit event)
          (do (log id "halt" "turn-limit reached → close run")
              (await (await-bounded
                       "run/close-run!"
                       (run/close-run!
                         {:seon.agent.run/id            run-id
                          :seon.agent.run/closed-reason :turn-limit})))
              (transition state :turn-limit))

          (= :deadline event)
          (do (log id "halt" "deadline passed → close run")
              (await (await-bounded
                       "run/close-run!"
                       (run/close-run!
                         {:seon.agent.run/id            run-id
                          :seon.agent.run/closed-reason :deadline-exceeded})))
              (transition state :deadline))

          (= :superseded event)
          (do (log id "halt" "superseded — a newer run owns the agent")
              (transition state :superseded))

          ;; :wait / :complete / :terminate / :pause — the run is already
          ;; closed/paused by the function; the loop just stops, recording the
          ;; FSM state the function moved to.
          :else
          (do (log id "halt" (str "function — " (name event)))
              (transition state event)))))))

(defn ^:async ^:private renew-current-run!
  "Renew the agent's CURRENT open run's lease — the new message extends both
   bounds (the sliding window). Shared by the :running wake branch and the
   :idle CAS-loss path (a wake that lost the atomic open race). A no-op when
   the agent has no open run. Caller establishes the `with-agent` scope."
  [id]
  (when-let [cur (run/current-run {:seon.agent/id id})]
    (await (run/renew! {:seon.agent/id     id
                        :seon.agent.run/id (:seon.agent.run/id cur)}))))

;; ============================================================
;; Wake — the per-tx listener fires on every transact; we filter for new
;; INBOUND messages (to ∋ me, from ≠ me, waking origin — agent/inbound-msg-datom?)
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

(defn wake-handler
  "Return the tx-listener handler for `input`'s agent.

   On an inbound datom
   that passes [[seon.agent/inbound-msg-datom?]], derive the agent's state
   from the local db snapshot; if :idle → open a `:message` run (cause = the
   waking message) and start `run-loop!`; if :running → `renew!` the open
   run's lease. The idle open is CAS-guarded; a wake that loses the race
   renews the winner's run instead of opening a second."
  {:malli/schema [:=> [:catn [:input [:map [:seon.agent/id :seon.agent/id]]]] :any]}
  [{:seon.agent/keys [id] :as input}]
  (fn [{:seon.db/keys [db attr-index]}]
    (let [my-eid  (:db/id (db/entity {:seon.db/db db :seon.db/ref [:seon.agent/id id]}))
          inbound (when my-eid
                    (->> (:seon.agent.message/to attr-index)
                         (filter :seon.db/added?)
                         (filter #(agent/inbound-msg-datom? db % my-eid))))
          {waking false exhausted true}
          (group-by (fn [{eid :seon.db/e}]
                      (>= (or (:seon.agent.message/hops
                                (db/entity {:seon.db/db db :seon.db/ref eid}))
                              0)
                          warn/hop-cap))
                    inbound)]
      ;; Hop guard AT wake — refuse, loudly. The message stays in the DB
      ;; (check-hop-exhausted renders it); the loop does NOT start for it.
      (doseq [{eid :seon.db/e} exhausted]
        (let [msg (db/entity {:seon.db/db db :seon.db/ref eid})]
          (js/console.error
            (str "seon.agent.loop: WAKE REFUSED for agent " id
                 " — message " (:seon.agent.message/id msg)
                 " hops=" (:seon.agent.message/hops msg)
                 " reached hop-cap " warn/hop-cap
                 " (agent↔agent ping-pong guard). A human message"
                 " resets the chain (hops 0)."))))
      (when (seq waking)
        (let [state (derive/derive-state db id)
              ;; The message that caused this wake (the run's cause ref on an
              ;; :idle open). The first waking datom's eid — the others, if
              ;; any, are absorbed by the same run's lease.
              cause-eid (:seon.db/e (first waking))]
          (case state
            :terminated
            (log id "wake skipped" "terminated — change state first")

            :paused
            (log id "wake skipped" "paused — resume first")

            :running
            ;; A run is already driving turns; renew its lease so the new
            ;; message extends both bounds (the sliding window).
            (js/setTimeout
              (fn []
                (-> (js/Promise.resolve
                      (db/with-agent id (fn ^:async renew! [] (await (renew-current-run! id)))))
                    (.catch (fn [e]
                              (js/console.error
                                (str "seon.agent.loop: wake renew threw for "
                                     id ": " (or (.-message e) e)))))))
              0)

            ;; :idle — open a fresh run and drive it. setTimeout breaks the
            ;; ALS scope, so re-enter `with-agent` for the loop's downstream
            ;; calls (db/current-agent-id).
            (js/setTimeout
              (fn []
                (-> (js/Promise.resolve
                      (db/with-agent id
                        (fn ^:async wake! []
                          (let [opened (await (run/open-run!
                                                {:seon.agent/id           id
                                                 :seon.agent.run/trigger  :message
                                                 :seon.agent.run/cause    cause-eid}))]
                            (cond
                              ;; Opened a fresh run (the snapshot has no
                              ;; :seon.db/ok? key) — drive it.
                              (not (false? (:seon.db/ok? opened)))
                              (await (run-loop! input (:seon.agent.run/id opened)))

                              ;; Open FAILED but a run now exists ⇒ we LOST the
                              ;; atomic open race (a concurrent message/schedule
                              ;; won the CAS). The agent IS running; absorb this
                              ;; message by renewing the winner's lease.
                              (run/current-run {:seon.agent/id id})
                              (await (renew-current-run! id))

                              :else
                              (js/console.error
                                (str "seon.agent.loop: open-run! FAILED for " id
                                     ": " (:seon.error/message (:seon.db/error opened)))))))))
                    (.catch (fn [e]
                              (js/console.error
                                (str "seon.agent.loop: wake loop threw for "
                                     id ": " (or (.-message e) e)))))))
              0)))))))

;; ============================================================
;; Re-drive — RESUME re-enters the loop. The loop EXITS on :pause (the run
;; stays open + paused); `seon.agent.run/resume!` clears `paused-at` and
;; re-extends the deadline, flipping derived state back to :running — but
;; nothing would drive turns again without this. `drive-run!` re-enters
;; `run-loop!` on the agent's STILL-OPEN run, using the loop input the wake
;; trigger was armed with (the process-local registry), via the SAME
;; setTimeout(0) + `with-agent` re-scope the wake `:idle` branch uses
;; (setTimeout breaks the ALS scope, so the loop's downstream
;; db/current-agent-id needs the scope re-entered).
;; ============================================================

(defn drive-run!
  "Re-enter [[run-loop!]] for `id`'s CURRENTLY-OPEN run.

   The re-drive entry
   shared by RESUME (paused-at cleared) and any future external kick. Looks up
   the loop `input` this process armed the wake trigger with (refreshed on
   every [[install-wake-trigger!]], so as fresh as the live wake handler),
   then kicks `run-loop!` on the open run. Fire-and-forget: schedules the
   drive on the macrotask queue and returns. A loud no-op when the agent was
   never armed in THIS process (no input — e.g. resume before a hot-reload
   re-arm) or has no open run."
  {:malli/schema [:=> [:catn [:input [:map [:seon.agent/id :seon.agent/id]]]] :any]}
  [{:seon.agent/keys [id]}]
  (if-let [input (get @!loop-input id)]
    (js/setTimeout
      (fn []
        (-> (js/Promise.resolve
              (db/with-agent id
                (fn ^:async redrive! []
                  (when-let [cur (run/current-run {:seon.agent/id id})]
                    (await (run-loop! input (:seon.agent.run/id cur)))))))
            (.catch (fn [e]
                      (js/console.error
                        (str "seon.agent.loop: drive-run! threw for "
                             id ": " (or (.-message e) e)))))))
      0)
    (js/console.warn
      (str "seon.agent.loop: drive-run! — no live loop input for agent " id
           " (the wake trigger was never armed in this process); cannot "
           "re-drive the resumed run. A hot-reload re-arm or boot installs "
           "it."))))

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
;; agent must SEE the result) but [[seon.derive/run-turn-count]] EXCLUDES it, so
;; a fire never burns a turn from the run's work budget (turn-limit). Without
;; that marker every cron tick stole an LLM turn — at turn-limit fires the run
;; would close `:turn-limit` having done zero LLM work (#66).
;;
;; A broken scheduled fn records a failed eval (errors are values), never
;; crashing the ticker. compile-state comes from the one pod-global bootstrap
;; ([[seon.repl/ensure-bootstrap!]]), so this needs no per-agent loop-input.
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
   ([[seon.derive/run-turn-count]] excludes it — #66). A no-op when the agent
   has no open run (a supersede/close raced the fire). Errors are values
   (eval-batch! records a failed eval per form). Injected into
   [[seon.agent.schedule/fire-due-schedules!]] as `:seon.agent.schedule/exec-fn!`.
   `^:async`."
  {:malli/schema [:=> [:cat ::exec-request] :any]}
  [{:seon.agent/keys [id] fns :seon.agent.schedule/fns}]
  (let [compile-state (await (repl/ensure-bootstrap!))]
    (await
      (db/with-agent id
        (fn ^:async run-scheduled! []
          (when-let [cur (run/current-run {:seon.agent/id id})]
            (let [run-id  (:seon.agent.run/id cur)
                  source  (str/join "\n"
                            (map (fn [s]
                                   (str ";; schedule fired — running " s "\n(" s ")"))
                                 fns))]
              (await
                (db/with-tx-context
                  {:seon.db/agent-id id
                   :seon.db/origin   :system}
                  (fn ^:async open-scheduled-turn! []
                    (await
                      (turn/open-turn!
                        {:seon.agent/id               id
                         :seon.agent.run/id-of-run    run-id
                         :seon.agent.turn/scheduled?  true
                         :seon.agent.turn/prompt-text ""}
                        (fn ^:async eval-scheduled! [turn-id]
                          (await (seval/eval-batch!
                                   compile-state
                                   (repl-internal/parse-forms source)
                                   (home/home-ns id)
                                   id turn-id run-id)))))))))))))))

(defn install-wake-trigger!
  "Register the inbound-message wake trigger for this agent.

   Wakes a run via
   [[wake-handler]] when a message lands with to ∋ me AND from ≠ me AND a
   waking origin. Idempotent: unlistens any prior handler for the same
   agent-id first, so hot-reload doesn't leave stale closures wired to the
   tx bus.

   Input map:
     :seon.agent/id              the agent's id string
     :seon.agent/llm-fn          ctx-string -> Promise<{:text \"…\"}>
     :seon.agent/compile-state   bootstrap compile-state"
  {:malli/schema [:=> [:catn [:input [:map [:seon.agent/id :seon.agent/id]]]] :any]}
  [{:seon.agent/keys [id] :as input}]
  ;; Stamp the loop input so RESUME can re-drive the open run with this
  ;; agent's live llm-fn / compile-state (refreshed on every re-arm — see the
  ;; !loop-input block comment). Same staleness profile as the wake handler.
  (swap! !loop-input assoc id input)
  ;; One stable listener key per agent so re-arming REPLACES the prior
  ;; listener (a hot reload must not leave two listeners firing for one
  ;; agent).
  (let [k [:seon.agent/user-message-trigger id]]
    (try (db/unlisten! {:seon.db/key k}) (catch :default _ nil))
    (db/listen!
      {:seon.db/key     k
       :seon.db/handler (wake-handler input)})))

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
   setInterval; the scan core (`run/stale-run-ids`) is a pure fn of (db, now)."
  [now]
  (-> (js/Promise.resolve
        (run/close-overdue-runs! {:seon.agent/now now}))
      (.then (fn [_]
               (run/close-stale-runs!
                 {:seon.agent/now          now
                  :seon.agent.run/stale-ms (config/watchdog-stale-ms)})))
      (.then (fn [_]
               (schedule/fire-due-schedules!
                 {:seon.agent/now               now
                  :seon.agent.schedule/exec-fn! exec-scheduled-fns!
                  :seon.agent.schedule/drive!   drive-run!})))
      (.catch (fn [e]
                (seon-log/error-console!
                  "seon.agent.loop/run-tick!"
                  (str "tick failed (timer continues): " (or (.-message e) e)))))))

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

(defn activity-log
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
                       :seon.agent.loop/activity-response]}
  [{:seon.agent/keys [id]}]
  (let [agent-eid (:db/id (db/entity {:seon.db/ref [:seon.agent/id id]}))]
    (if-not agent-eid
      {:seon.agent.loop/entries []}
      (let [rows (->> (db/query {:seon.db/query
                                 '[:find ?r ?started
                                   :in $ ?aid
                                   :where
                                   [?a :seon.agent/id ?aid]
                                   [?r :seon.agent.run/agent ?a]
                                   [?r :seon.agent.run/started-at ?started]]
                                 :seon.db/args [id]})
                      (sort-by #(.getTime ^js (second %))))]
        {:seon.agent.loop/entries
         (mapv (fn [[run-eid started]]
                 (let [r        (db/entity run-eid)
                       reason   (:seon.agent.run/closed-reason r)
                       open?    (= :open (:seon.agent.run/status r))
                       cause    (some-> (:db/id (:seon.agent.run/cause r))
                                        db/entity
                                        :seon.agent.message/content)
                       ;; DERIVE the per-run state via the ONE rule so :paused
                       ;; (an open run carrying paused-at) is included — map a
                       ;; closed-:terminated run to a terminated-at primitive.
                       state    (derive/state-from-primitives
                                  (cond-> {:seon.agent.run/open? open?}
                                    (= reason :terminated)
                                    (assoc :seon.agent/terminated-at started)
                                    (:seon.agent.run/paused-at r)
                                    (assoc :seon.agent.run/paused-at
                                           (:seon.agent.run/paused-at r))))]
                   (cond-> {:seon.agent.loop/at    started
                            :seon.agent/state      state}
                     reason (assoc :seon.agent.loop/stop-reason reason)
                     cause  (assoc :seon.agent.loop/cause cause))))
               rows)}))))
