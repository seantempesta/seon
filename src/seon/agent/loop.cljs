(ns seon.agent.loop
  "Drive agent runs through the event-derived runtime loop.

   This namespace owns wake handling, the one ticker, run driving, and the
   finite-state fold that selects the next effect from persisted facts. Each
   turn uses one pinned database value; run fencing, turn execution, and
   schedule matching remain delegated to their owning namespaces."
  (:require
    [seon.agent.driver :as driver]
    [seon.agent.driver.pod :as driver.pod]
    [seon.agent.message :as message]
    [seon.agent.run :as run]
    [seon.agent.schedule :as schedule]
    [seon.config :as config]
    [seon.db :as db]
    [seon.db.protocol :as db.protocol]
    [seon.derive :as derive]
    [seon.error :as error]
    [seon.log :as seon-log]
    [seon.runtime.admission :as admission]
    [seon.schema :as schema]
    [seon.warn :as warn]))

(defn- log [agent-id stage & info]
  (seon-log/info-console!
    (str "seon.agent.loop/" agent-id)
    stage
    (if (= 1 (count info)) (first info) (vec info))))

;; ============================================================
;; The derived state vocabulary remains a pure projection helper for wake and
;; lifecycle surfaces. The claim-native driver, not this table, owns progress.
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

(defn ^:async ^:private drive-claimed-run!
  ([agent-id run-id]
   (await (drive-claimed-run! agent-id run-id nil)))
  ([agent-id run-id input-ref]
   (await
    (driver.pod/dispatch-run!
     {:seon.agent/id agent-id
      :seon.agent.run/id run-id
      :seon.agent.run/input-ref input-ref}))))

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
                   :seon.agent.run/paused-at
                   :seon.agent.run/claim-epoch]}]
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
     (not-join [?message]
       [?run :seon.agent.run/consumed-input ?message])]
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
                    :seon.agent.run/paused-at
                    :seon.agent.run/claim-epoch]}]
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

(defn- schedule-renew! [id run-id input-ref]
  (js/setTimeout
    (fn []
      (-> (js/Promise.resolve
            (with-agent-repl
              id
              (fn ^:async renew! []
                (await (drive-claimed-run! id run-id input-ref)))))
          (.catch
            (fn [exception]
              (seon-log/error!
                {:seon.log/source ::wake-renew
                 :seon.log/agent id
                 :seon.log/message
                 (str "wake renew threw: "
                      (or (.-message exception) exception))})))))
    0))

(defn ^:async ^:private open-or-renew-message-run!
  "Open and claim one message run, or claim the concurrent CAS winner."
  [input cause-eid]
  (let [id (:seon.agent/id input)
        opened
        (await
         (run/open-run!
          {:seon.agent/id id
           :seon.agent.run/trigger :message
           :seon.agent.run/cause cause-eid}))]
    (if-not (database-error? opened)
      (await (drive-claimed-run! id (:seon.agent.run/id opened)))
      (let [latest (await (acquire-agent-state id))]
        (if (database-error? latest)
          (seon-log/error!
           {:seon.log/source ::open-run
            :seon.log/agent id
            :seon.log/message "open-run! FAILED; refresh failed"
            :seon.log/data {::opened opened ::refresh latest}})
          (if-let [winner (::current-run-id latest)]
            (await (drive-claimed-run! id winner))
            (seon-log/error!
             {:seon.log/source ::open-run
              :seon.log/agent id
              :seon.log/message "open-run! FAILED"
              :seon.log/data {::opened opened}})))))))

(defn- schedule-human-message-run! [input run cause-eid]
  (let [id (:seon.agent/id input)]
    (js/setTimeout
     (fn []
       (-> (js/Promise.resolve
            (with-agent-repl
             id
             (fn ^:async supersede! []
               (await
                (run/close-run!
                 {:seon.agent.run/id (:seon.agent.run/id run)
                  :seon.agent.run/claim-epoch
                  (:seon.agent.run/claim-epoch run)
                  :seon.agent.run/closed-reason :superseded}))
               (await (open-or-renew-message-run! input cause-eid)))))
           (.catch
            (fn [exception]
              (seon-log/error!
               {:seon.log/source ::human-message-supersede
                :seon.log/agent id
                :seon.log/message
                (str "human-message supersede threw: "
                     (or (.-message exception) exception))})))))
     0)))

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
                (seon-log/error!
                  {:seon.log/source ::wake-loop
                   :seon.log/agent id
                   :seon.log/message
                   (str "wake loop threw: "
                        (or (.-message exception) exception))}))))
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
                     :seon.agent.run/paused-at
                     :seon.agent.run/claim-epoch]}
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
                    (seon-log/error!
                     {:seon.log/source ::wake-refused
                      :seon.log/agent id
                      :seon.log/message
                      (str "WAKE REFUSED — message "
                           (:seon.agent.message/id msg)
                           " hops=" (:seon.agent.message/hops msg)
                           " reached hop-cap " warn/hop-cap
                           " (agent↔agent ping-pong guard). A human message"
                           " resets the chain (hops 0).")})))
                (when (seq waking)
                  (case state
                    :terminated
                    (log id "wake skipped" "terminated — change state first")

                    :paused
                    (log id "wake skipped" "paused — resume first")

                    :running
                    (let [cause-eid (ffirst waking)
                          cause (get message-by-eid cause-eid)]
                      (if (= :human (:seon.agent.message/origin cause))
                        (schedule-human-message-run!
                         input current-run cause-eid)
                        (schedule-renew!
                         id (:seon.agent.run/id current-run)
                         [:seon.agent.message/id
                          (:seon.agent.message/id cause)])))

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
   Fire-and-forget: schedules the work on the macrotask queue."
  {:malli/schema [:=> [:catn [:input [:map [:seon.agent/id :seon.agent/id]]]] :any]}
  [{:seon.agent/keys [id]}]
  (if-not (admission/available?)
    (admission/unavailable)
    (js/setTimeout
     (fn []
       (.catch
        (js/Promise.resolve
         (with-agent-repl
          id
          (fn ^:async drive-committed! []
            (let [work (await (acquire-committed-work id))]
              (cond
                (database-error? work)
                (seon-log/error!
                 {:seon.log/source ::drive-run
                  :seon.log/agent id
                  :seon.log/message "committed work acquisition FAILED"
                  :seon.log/data {::work work}})

                (= :running (::state work))
                (await (drive-claimed-run! id (::current-run-id work)))

                (and (= :idle (::state work))
                     (::pending-inbound-eid work))
                (await
                 (open-or-renew-message-run!
                  {:seon.agent/id id}
                  (::pending-inbound-eid work))))))))
        (fn [e]
          (seon-log/error!
           {:seon.log/source ::drive-run
            :seon.log/agent id
            :seon.log/message
            (str "drive-run! threw: " (or (.-message e) e))}))))
     0)))

(defn ^:async install-wake-trigger!
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
  ;; One stable listener key per agent so re-arming REPLACES the prior
  ;; listener (a hot reload must not leave two listeners firing for one
  ;; agent).
  (let [k [:seon.agent/user-message-trigger id]
        handle-datoms! (wake-handler input)
        agent-eid
        (await
         (db/query
          {::db/query '[:find ?agent . :in $ ?id :where
                        [?agent :seon.agent/id ?id]]
           ::db/args [id]}))]
    (if (:seon.error/message agent-eid)
      agent-eid
      (await
       (db/listen!
        {:seon.db/key k
         :seon.db/datom-patterns
         [{:seon.db/a :seon.agent.message/to
           :seon.db/v agent-eid
           :seon.db/added? true}]
         :seon.db/handler
         (fn [event]
           (if (= db.protocol/resynchronization-event
                  (::db.protocol/event event))
             (drive-run! input)
             (handle-datoms! event)))})))))

(defn uninstall-wake-trigger!
  "Remove one agent's wake listener.

   Idempotent. This is the inverse of [[install-wake-trigger!]]."
  {:malli/schema
   [:=> [:catn [:input [:map [:seon.agent/id :seon.agent/id]]]]
    [:map
     [:seon.agent/id :seon.agent/id]
     [:seon.agent.loop/uninstalled? [:= true]]]]}
  [{:seon.agent/keys [id]}]
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
  "Remove process-local wake listeners during pod shutdown."
  {:malli/schema
   [:=> [:cat] ::uninstall-all-wake-triggers-response]}
  []
  ;; The database session owns listener teardown. There is deliberately no
  ;; second process-local agent registry.
  {::uninstalled-ids []})

;; ============================================================
;; The ONE ticker — the only active machinery. The DB is passive about
;; wall-clock: a `deadline` is past or a cron is due in the cluster, but nothing
;; fires until something CHECKS. This single `setInterval` is that check —
;; each tick (1) closes overdue runs ([[seon.agent.run/close-overdue-runs!]],
;; the deadline watchdog) then (2) fires due schedules
;; ([[seon.agent.schedule/fire-due-schedules!]]), RUNNING each due schedule's fn
;; by opening claimable runs. Database interests wake the claimant driver.
;; Idempotent + single-instance (the `!ticker` atom holds the interval id;
;; re-install clears the prior). An unexpected rejection is a core fault and
;; follows the already-acquired database configuration's one fault policy.
;; Wired at client boot beside `install-wake-trigger!` and re-armed
;; idempotently on hot reload.
;; ============================================================

(defonce ^:private !ticker (atom nil))

(def default-tick-ms
  "Ticker cadence (ms) when SEON_TICK_MS is unset. Coarse on purpose — the
   watchdog need only catch an overrun within ~one cadence, and every tick is
   cheap DB reads plus at most a few transacts."
  30000)

(defn- run-tick!
  "ONE ticker pass at `now`: offer claimable work to the portable pod claimant,
   then fire due schedules. Lease/deadline recovery happens only after an
   exclusive acquire/steal; the ticker never closes a run out from under a
   claimant. Returns a Promise; an unexpected rejection is recorded once."
  [configuration now]
  (if-not (admission/available?)
    (js/Promise.resolve (admission/unavailable))
    (-> (js/Promise.resolve
          (driver/call-with-leaf
           driver.pod/leaf db/*leaf* driver/scan!))
      (.then (fn [_]
               (schedule/fire-due-schedules!
                 {:seon.agent/now now})))
      (.catch (fn [e]
                (error/with-configuration
                  configuration
                  #(error/record! {::error/raw e ::error/fault :core})))))))

(defn install-ticker!
  "Install the ONE periodic ticker (idempotent, single instance).

   Clear any
   prior interval, then `setInterval` [[run-tick!]] every SEON_TICK_MS ms
   (default [[default-tick-ms]]). Returns the interval id. Wired at client
   boot beside [[install-wake-trigger!]]; safe to re-run on hot reload — it
   clears the prior interval first, so reloads never stack timers."
  {:malli/schema [:=> [:catn [:configuration :seon.config/singleton]] :any]}
  [configuration]
  (when-let [id @!ticker] (js/clearInterval id))
  (let [ms (or (config/tick-ms) default-tick-ms)
        id (js/setInterval (fn [] (run-tick! configuration (js/Date.))) ms)]
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
