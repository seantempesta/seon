(ns seon.agent.loop
  "The agent LOOP — the wake trigger + the run-driven fold.

   The loop is a FOLD of [[seon.agent.fsm/transition]] over events derived
   from the RUN's data. A trigger (an inbound message) opens a RUN
   ([[seon.agent.run/open-run!]]); `run-loop!` then drives turns until a
   bound fires or a verb closes the run:

     each iteration `next-event` derives the event from the run:
       - run already :closed (a verb ran inside a turn) → :wait / :complete /
         :terminate (from the run's closed-reason) — the verb owns the close
       - the agent's `:seon.agent/run` points at a DIFFERENT run → :superseded
       - the run carries :paused-at                       → :pause
       - turn-count ≥ turn-limit (the WORK bound)         → :turn-limit
       - now > deadline (the WALL-CLOCK bound)            → :deadline
       - else                                             → :turn-ok
     `(fsm/transition state event)` gives the next state; the EFFECT of
       :turn-ok is `beat!` + `run-turn!`; the LOOP closes the run on
       :turn-limit/:deadline/:error (the bounds it owns), while verb closes
       and supersede are already handled (no re-close). The loop ends when the
       state leaves :running.

   The RUN-ID is the fencing token: a turn from a superseded run answers
   `owns-run?` false and bails :superseded (the new run owns the agent).

   Wake = read-derived-then-open. An inbound datom fires the per-tx listener;
   the handler derives the agent's state from the local db snapshot. If :idle
   → `open-run!` ({trigger :message, cause the message}) then `run-loop!` on
   that run. If already :running → `renew!` (the new message extends the lease
   — the sliding window is now lease renewal). The idle→running open is ATOMIC
   (a CAS on `:seon.agent/run` being absent — [[seon.agent.run/open-run!]]):
   two simultaneous idle wakes can't both open, the loser's open returns the
   db error envelope and it RENEWS the winner's run instead (no orphaned run).

   Requires `seon.agent` (the wake gate `inbound-msg-datom?` + derived-state),
   `seon.agent.run` (the run lifecycle), `seon.agent.turn` (`run-turn!`). The
   boot path (`seon.client`) requires THIS ns to `install-wake-trigger!`."
  (:require
    [seon.agent :as agent]
    [seon.agent.fsm :as fsm]
    [seon.agent.run :as run]
    [seon.agent.schedule :as schedule]
    [seon.agent.turn :as turn]
    [seon.db :as db]
    [seon.log :as seon-log]
    [seon.schema :as schema]
    [seon.warn :as warn]))

(defn- log [agent-id stage & info]
  (seon-log/info-console!
    (str "seon.agent.loop/" agent-id)
    stage
    (if (= 1 (count info)) (first info) (vec info))))

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
;; The loop — a fold of fsm/transition over run-derived events. Each
;; iteration re-reads the run; :turn-ok runs a turn, the bounds close the
;; run, verb closes / supersede are already settled.
;; ============================================================

(defn- run-turn-count
  "How many turns are stamped with this run (the run's derived current-turn)."
  [run-eid]
  (or (db/query {:seon.db/query
                 '[:find (count ?t) . :in $ ?run
                   :where [?t :seon.agent.turn/run ?run]]
                 :seon.db/args [run-eid]})
      0))

(defn- next-event
  "Derive the loop event from the run's current data (no side effects).
   One of :wait/:complete/:terminate (verb closed the run inside a turn),
   :superseded (a newer run owns the agent), :pause, :turn-limit, :deadline,
   or :turn-ok (run another turn)."
  [id run-id]
  (let [snap   (run/snapshot {:seon.agent.run/id run-id})
        status (:seon.agent.run/status snap)
        reason (:seon.agent.run/closed-reason snap)]
    (cond
      (= :closed status)
      (case reason
        :waited     :wait
        :completed  :complete
        :terminated :terminate
        :superseded :superseded
        ;; a bound the loop already closed on (turn-limit/deadline/error) —
        ;; nothing left to do.
        :superseded)

      (not (run/owns-run? {:seon.agent/id id :seon.agent.run/id run-id}))
      :superseded

      (:seon.agent.run/paused-at snap)
      :pause

      (run/turn-limit-reached? (run-turn-count
                                 (:db/id (db/entity
                                           {:seon.db/ref [:seon.agent.run/id run-id]})))
                               (:seon.agent.run/turn-limit snap))
      :turn-limit

      (run/deadline-passed? (:seon.agent.run/deadline snap) (js/Date.))
      :deadline

      :else :turn-ok)))

(defn ^:async run-loop!
  "Drive agentic turns for `run-id` until the FSM leaves :running. `input`
   carries `:seon.agent/id` / `:seon.agent/llm-fn` / `:seon.agent/compile-state`.
   A fold of [[seon.agent.fsm/transition]] over [[next-event]]: :turn-ok beats
   + runs a turn; the loop closes the run on the bounds it owns (:turn-limit /
   :deadline / :error); verb closes (:wait/:complete/:terminate) and
   :superseded are already settled (no re-close). Returns the final FSM state.
   Errors are values — never throws into the trigger."
  [{:seon.agent/keys [id] :as input} run-id]
  (await
    (loop [state :running]
      (let [event (next-event id run-id)]
        (cond
          (= :turn-ok event)
          (do
            (await (run/beat! {:seon.agent/id id :seon.agent.run/id run-id}))
            (let [r      (await (turn/run-turn! (assoc input :seon.agent.run/id run-id)))
                  ;; A turn that errored (LLM error / failed open / catastrophic)
                  ;; OR a result that created NO turn (no `:seon.agent.turn/id` —
                  ;; e.g. a failed open-tx that left no entity) closes the run
                  ;; `:error`. The id-absence clause is the structural guarantee:
                  ;; a failed open can NEVER masquerade as a successful no-op turn
                  ;; (which would recur `:turn-ok` forever — a retry storm).
                  errored? (or (= :error (:seon.agent.turn/status r))
                               (false? (:seon.db/ok? r))
                               (nil? (:seon.agent.turn/id r)))]
              (if errored?
                (do (log id "halt" "turn :error → close run :error")
                    (await (run/close-run!
                             {:seon.agent.run/id            run-id
                              :seon.agent.run/closed-reason :error}))
                    (fsm/transition state :error))
                (recur (fsm/transition state :turn-ok)))))

          (= :turn-limit event)
          (do (log id "halt" "turn-limit reached → close run")
              (await (run/close-run!
                       {:seon.agent.run/id            run-id
                        :seon.agent.run/closed-reason :turn-limit}))
              (fsm/transition state :turn-limit))

          (= :deadline event)
          (do (log id "halt" "deadline passed → close run")
              (await (run/close-run!
                       {:seon.agent.run/id            run-id
                        :seon.agent.run/closed-reason :deadline-exceeded}))
              (fsm/transition state :deadline))

          (= :superseded event)
          (do (log id "halt" "superseded — a newer run owns the agent")
              (fsm/transition state :superseded))

          ;; :wait / :complete / :terminate / :pause — the run is already
          ;; closed/paused by the verb; the loop just stops, recording the
          ;; FSM state the verb moved to.
          :else
          (do (log id "halt" (str "verb — " (name event)))
              (fsm/transition state event)))))))

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
  "Return the tx-listener handler for `input`'s agent. On an inbound datom
   that passes [[seon.agent/inbound-msg-datom?]], derive the agent's state
   from the local db snapshot; if :idle → open a `:message` run (cause = the
   waking message) and start `run-loop!`; if :running → `renew!` the open
   run's lease. The idle open is CAS-guarded; a wake that loses the race
   renews the winner's run instead of opening a second."
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
        (let [state (agent/derive-agent-state {:seon.agent/id id :seon.db/db db})
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
  "Re-enter [[run-loop!]] for `id`'s CURRENTLY-OPEN run — the re-drive entry
   shared by RESUME (paused-at cleared) and any future external kick. Looks up
   the loop `input` this process armed the wake trigger with (refreshed on
   every [[install-wake-trigger!]], so as fresh as the live wake handler),
   then kicks `run-loop!` on the open run. Fire-and-forget: schedules the
   drive on the macrotask queue and returns. A loud no-op when the agent was
   never armed in THIS process (no input — e.g. resume before a hot-reload
   re-arm) or has no open run."
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

(defn install-wake-trigger!
  "Register the inbound-message trigger for this agent — wakes a run via
   [[wake-handler]] when a message lands with to ∋ me AND from ≠ me AND a
   waking origin. Idempotent: unlistens any prior handler for the same
   agent-id first, so hot-reload doesn't leave stale closures wired to the
   tx bus.

   Input map:
     :seon.agent/id              the agent's id string
     :seon.agent/llm-fn          ctx-string -> Promise<{:text \"…\"}>
     :seon.agent/compile-state   bootstrap compile-state"
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
;; wall-clock: a `deadline` is past or a cron is due in the world, but nothing
;; fires until something CHECKS. This single `setInterval` is that check —
;; each tick (1) closes overdue runs ([[seon.agent.run/close-overdue-runs!]],
;; the deadline watchdog) then (2) fires due schedules
;; ([[seon.agent.schedule/fire-due-schedules!]]), DRIVING each opened run via
;; [[drive-run!]] (injected so seon.agent.schedule need not require this ns).
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

(defn- env-tick-ms
  "The `SEON_TICK_MS` env cadence override (parsed positive int), or nil when
   unset / unparseable / non-positive."
  []
  (some-> (.. js/process -env -SEON_TICK_MS)
          js/parseInt
          (#(when (and (not (js/isNaN %)) (pos? %)) %))))

(defn- run-tick!
  "ONE ticker pass at `now`: close overdue runs, then fire due schedules
   (driving each opened run). Returns a Promise; a throw anywhere is caught +
   logged so the interval survives."
  [now]
  (-> (js/Promise.resolve
        (run/close-overdue-runs! {:seon.agent/now now}))
      (.then (fn [_]
               (schedule/fire-due-schedules!
                 {:seon.agent/now             now
                  :seon.agent.schedule/drive! drive-run!})))
      (.catch (fn [e]
                (seon-log/error-console!
                  "seon.agent.loop/run-tick!"
                  (str "tick failed (timer continues): " (or (.-message e) e)))))))

(defn install-ticker!
  "Install the ONE periodic ticker (idempotent, single instance): clear any
   prior interval, then `setInterval` [[run-tick!]] every SEON_TICK_MS ms
   (default [[default-tick-ms]]). Returns the interval id. Wired at client
   boot beside [[install-wake-trigger!]]; safe to re-run on hot reload — it
   clears the prior interval first, so reloads never stack timers."
  []
  (when-let [id @!ticker] (js/clearInterval id))
  (let [ms (or (env-tick-ms) default-tick-ms)
        id (js/setInterval (fn [] (run-tick! (js/Date.))) ms)]
    (reset! !ticker id)
    (seon-log/info-console! "seon.agent.loop/install-ticker!"
                            "ticker installed" {:seon.agent.loop/tick-ms ms})
    id))

(defn uninstall-ticker!
  "Stop the periodic ticker (clearInterval + drop the stored id). For tests +
   clean reload."
  []
  (when-let [id @!ticker]
    (js/clearInterval id)
    (reset! !ticker nil)))

;; ============================================================
;; Activity log — a DERIVED timeline over the agent's RUNS. One row per run,
;; ordered by `:seon.agent.run/started-at`: the wake (trigger + cause-message
;; content) and, when the run is closed, its `closed-reason` as the
;; stop-reason. A function of the DB; nothing stored that isn't already a run
;; datom (the inspector's lifecycle split reads the latest row's stop-reason).
;; ============================================================

(schema/register! :seon.agent.loop/activity-request
  [:map [:seon.agent/id :string]])

(schema/register! :seon.agent.loop/activity-entry
  [:map
   [:seon.agent.loop/at          :inst]
   [:seon.agent/state            :seon.agent.fsm/state]
   [:seon.agent.loop/stop-reason {:optional true} :seon.agent.run/closed-reason]
   [:seon.agent.loop/cause       {:optional true} :string]])

(schema/register! :seon.agent.loop/activity-response
  [:map [:seon.agent.loop/entries [:vector :seon.agent.loop/activity-entry]]])

(defn activity-log
  "Return the agent's activity log — the DERIVED timeline of its RUNS, ordered
   by `:seon.agent.run/started-at`. Map-in, map-out.

   Each entry: `:seon.agent.loop/at` (the run's started-at), `:seon.agent/state`
   (the run's projected state — :terminated when closed :terminated, :running
   while open, else :idle), the run's `:seon.agent.loop/stop-reason`
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
                       state    (cond
                                  (= reason :terminated) :terminated
                                  open?                  :running
                                  :else                  :idle)]
                   (cond-> {:seon.agent.loop/at    started
                            :seon.agent/state      state}
                     reason (assoc :seon.agent.loop/stop-reason reason)
                     cause  (assoc :seon.agent.loop/cause cause))))
               rows)}))))
