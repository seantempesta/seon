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

   Wake = read-derived-then-open (no atom, no CAS). An inbound datom fires the
   per-tx listener; the handler derives the agent's state from the local db
   snapshot. If :idle → `open-run!` ({trigger :message, cause the message})
   then `run-loop!` on that run. If already :running → `renew!` (the new
   message extends the lease — the sliding window is now lease renewal). Two
   simultaneous idle wakes both open a run; the loser's run is orphaned-open
   (closed by a later ticker/crash-recovery pass — DEFERRED).

   Requires `seon.agent` (the wake gate `inbound-msg-datom?` + derived-state),
   `seon.agent.run` (the run lifecycle), `seon.agent.turn` (`run-turn!`). The
   boot path (`seon.client`) requires THIS ns to `install-wake-trigger!`."
  (:require
    [seon.agent :as agent]
    [seon.agent.fsm :as fsm]
    [seon.agent.run :as run]
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
                  errored? (= :error (:seon.agent.turn/status r))]
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

;; ============================================================
;; Wake — the per-tx listener fires on every transact; we filter for new
;; INBOUND messages (to ∋ me, from ≠ me, waking origin — agent/inbound-msg-datom?)
;; and, if the agent's derived state is :idle, OPEN A RUN and start the loop.
;; An already-:running agent RENEWS its open run's lease (the new message
;; extends both bounds — the sliding window).
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
   run's lease. No atom — the run-id fence settles any race."
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
                      (db/with-agent id
                        (fn ^:async renew! []
                          (when-let [cur (run/current-run {:seon.agent/id id})]
                            (await (run/renew!
                                     {:seon.agent/id     id
                                      :seon.agent.run/id (:seon.agent.run/id cur)}))))))
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
                            (if (false? (:seon.db/ok? opened))
                              (js/console.error
                                (str "seon.agent.loop: open-run! FAILED for " id
                                     ": " (:seon.error/message (:seon.db/error opened))))
                              (await (run-loop! input (:seon.agent.run/id opened))))))))
                    (.catch (fn [e]
                              (js/console.error
                                (str "seon.agent.loop: wake loop threw for "
                                     id ": " (or (.-message e) e)))))))
              0)))))))

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
  ;; One stable listener key per agent so re-arming REPLACES the prior
  ;; listener (a hot reload must not leave two listeners firing for one
  ;; agent).
  (let [k [:seon.agent/user-message-trigger id]]
    (try (db/unlisten! {:seon.db/key k}) (catch :default _ nil))
    (db/listen!
      {:seon.db/key     k
       :seon.db/handler (wake-handler input)})))

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
