(ns seon.agent.driver
  "The one claim-native portable run driver.

   This namespace owns claim arbitration, held-epoch threading, eligibility,
   clean handoff, and the step loop. Platform leaves execute only the external
   phase they advertise; cursor and receipt transaction builders remain in the
   portable cores."
  #?(:clj (:refer-clojure :exclude [await]))
  (:require [seon.agent.loop.core :as loop.core]
            [seon.agent.run.core :as run.core]
            [seon.agent.turn.core :as turn.core]
            [seon.config.resolve :as config.resolve]
            [seon.db :as db]
            [seon.error :as error]
            [seon.program.plan :as program.plan]
            [seon.schema :as schema]))

#?(:clj (defmacro await [value] value))

(schema/register!
 :seon.agent.driver/capability
 [:enum :seon.agent.driver.capability/render
  :seon.agent.driver.capability/llm
  :seon.agent.driver.capability/eval
  :seon.agent.driver.capability/publish])
(schema/register!
 :seon.agent.driver/capabilities
 [:set :seon.agent.driver/capability])
(schema/register! :seon.agent.driver/claimant [:string {:min 1}])

(defonce ^:private process-start
  #?(:clj (java.time.Instant/now)
     :cljs (.toISOString (js/Date.))))

(defonce ^{:doc "This process instance's stable, self-derived custody identity."}
  claimant
  (str #?(:clj (.pid (java.lang.ProcessHandle/current))
          :cljs (.-pid js/process))
       "@"
       process-start))

(def ^:dynamic *leaf* nil)

(defn- leaf-fn [key]
  (or (get *leaf* key)
      (throw
       (ex-info "The claim driver platform leaf is incomplete."
                {:seon.error/kind :core-bug
                 :seon.agent.driver/missing-leaf key}))))

(def ^:private open-runs-query
  '[:find ?agent-id ?run-id
    :where
    [?agent :seon.agent/id ?agent-id]
    [?agent :seon.agent/run ?run]
    [?run :seon.agent.run/id ?run-id]
    [?run :seon.agent.run/status :open]
    (not [?run :seon.agent.run/paused-at _])])

(def ^:private pending-input-query
  {:find '[?message-id ?message-tx]
   :in '[$ ?agent-id ?run-id ?hop-cap]
   :where
   '[[?agent :seon.agent/id ?agent-id]
     [?run :seon.agent.run/id ?run-id]
     [?message :seon.agent.message/id ?message-id]
     [?message :seon.agent.message/to ?agent ?message-tx]
     [?message :seon.agent.message/from ?sender]
     [(not= ?sender ?agent)]
     [(get-else $ ?message :seon.agent.message/origin :agent) ?origin]
     [(not= ?origin :core)]
     [(get-else $ ?message :seon.agent.message/hops 0) ?hops]
     [(< ?hops ?hop-cap)]
     (not-join [?run ?message]
       [?run :seon.agent.run/consumed-input ?message])]
   :order-by '[?message-tx :asc ?message-id :asc]
   :limit 1})

(def ^:private run-selector
  [:db/id
   :seon.agent.run/id
   :seon.agent.run/status
   :seon.agent.run/started-at
   :seon.agent.run/turn-limit
   :seon.agent.run/deadline
   :seon.agent.run/last-beat-at
   :seon.agent.run/claimant
   :seon.agent.run/claim-epoch
   :seon.agent.run/paused-at
   {:seon.agent.run/cause [:db/id :seon.agent.message/id]}
   {:seon.agent.run/consumed-input [:db/id]}
   {:seon.agent.turn/_run
    [:db/id
     :seon.agent.turn/id
     :seon.agent.turn/at
     :seon.agent.turn/status
     :seon.agent.turn/scheduled?
     :seon.agent.turn/phase
     :seon.agent.turn/rendered-tx
     {:seon.agent.turn/prompt-blob [:my.blob/hash]}
     {:seon.agent.turn/reply-blob [:my.blob/hash]}
     {:seon.agent.turn/llm-attempts
      [:db/id :seon.ai.attempt/id :seon.ai.attempt/ordinal
       :seon.ai.attempt/outcome :seon.ai.attempt/deadline-at
       :seon.ai.attempt/reply-evaluation]}
     {:seon.agent.turn/evals
      [:seon.eval/id :seon.eval/source :seon.eval/status :seon.eval/ok?
       :seon.eval/progress? :seon.eval/result-edn :seon.eval/output
       :seon.eval/error :seon.eval/error-data :seon.eval/ns]}]}])

(def ^:private claim-policy-selector
  [:seon.config.watchdog/stale-ms])

(def ^:private run-authority-selector
  [:seon.agent.run/status
   :seon.agent.run/claimant
   :seon.agent.run/claim-epoch
   :seon.agent.run/closed-reason])

(defn- turn-reply-evaluation [turn]
  (->> (:seon.agent.turn/llm-attempts turn)
       (filter #(= :success (:seon.ai.attempt/outcome %)))
       (sort-by :seon.ai.attempt/ordinal)
       last
       :seon.ai.attempt/reply-evaluation))

(defn- work-count [run]
  (let [turns (->> (:seon.agent.turn/_run run)
                   (remove :seon.agent.turn/scheduled?)
                   (filter #(= :done (:seon.agent.turn/status %))))]
    (reduce
     (fn [work-total turn]
       (+ work-total
          (if (= :first-form (turn-reply-evaluation turn))
            (count (:seon.agent.turn/evals turn))
            1)))
     0
     turns)))

(defn- close-reason [run _policy now]
  (cond
    (and (int? (:seon.agent.run/turn-limit run))
         (>= (work-count run)
             (:seon.agent.run/turn-limit run)))
    :turn-limit

    (and (:seon.agent.run/deadline run)
         (> (run.core/instant-ms now)
            (run.core/instant-ms (:seon.agent.run/deadline run))))
    :deadline-exceeded

    (>= (loop.core/no-progress-streak run)
        loop.core/no-progress-streak-limit)
    :no-forms

    :else nil))

(defn- latest-turn [run]
  (->> (:seon.agent.turn/_run run)
       (filter #(= :running (:seon.agent.turn/status %)))
       (sort-by :db/id)
       last))

(defn- cause-ref [run]
  (when-let [input-id
             (get-in run [:seon.agent.run/cause
                          :seon.agent.message/id])]
    [:seon.agent.message/id input-id]))

(defn- consumed-cause? [run]
  (let [cause-eid (get-in run [:seon.agent.run/cause :db/id])]
    (and cause-eid
         (some #(= cause-eid (:db/id %))
               (:seon.agent.run/consumed-input run)))))

(defn- acquired-run
  [run agent-id]
  (cond-> (assoc run :seon.agent/id agent-id)
    (latest-turn run)
    (assoc :seon.agent.run/current-turn (latest-turn run))))

(defn ^:async acquire-run-state!
  "Read one run and the one claim policy at an explicit database value."
  [database agent-id run-id]
  (let [run
        (await
         (db/pull
          {::db/db database
           ::db/pull-pattern run-selector
           ::db/ref [:seon.agent.run/id run-id]}))
        policy
        (await
         (db/pull
          {::db/db database
           ::db/pull-pattern claim-policy-selector
           ::db/ref config.resolve/cluster-config-lookup-ref}))
        pending-input
        (await
         (db/query
          {::db/db database
           ::db/query pending-input-query
           ::db/args [agent-id run-id loop.core/hop-cap]}))]
    (cond
      (run.core/error-value? run) run
      (run.core/error-value? policy) policy
      (run.core/error-value? pending-input) pending-input
      (nil? run)
      {:seon.error/message (str "No run " (pr-str run-id) " exists.")
       :seon.error/kind :core-bug}
      (not (int? (:seon.config.watchdog/stale-ms policy)))
      {:seon.error/message "The claim lease policy is missing."
       :seon.error/kind :configuration}
      :else
      {:seon.db/db database
       :seon.config.watchdog/stale-ms
       (:seon.config.watchdog/stale-ms policy)
       :seon.agent.driver/policy policy
       :seon.agent.driver/pending-input
       (when-let [[message-id] (first pending-input)]
         [:seon.agent.message/id message-id])
       :seon.agent.driver/run (acquired-run run agent-id)})))

(defn ^:async claim!
  "Attempt exactly one claim transition at one immutable database value."
  [{database :seon.db/db :as request}]
  (let [agent-id (:seon.agent/id request)
        run-id (:seon.agent.run/id request)
        database (or database (await (db/db)))]
    (if (run.core/error-value? database)
      database
      (let [state (await (acquire-run-state! database agent-id run-id))]
        (if (run.core/error-value? state)
          state
          (let [run (:seon.agent.driver/run state)
                input-ref
                (or (:seon.agent.run/input-ref request)
                    (:seon.agent.driver/pending-input state)
                    (when-not (consumed-cause? run) (cause-ref run)))
                plan
                (when
                 (loop.core/eligible?
                  (:seon.agent.driver/capabilities *leaf*) run)
                  (run.core/claim-plan
                   run claimant ((leaf-fn :seon.agent.driver/now))
                   (:seon.config.watchdog/stale-ms state) input-ref))]
            (cond
              (nil? plan) nil
              :else
              (let [report
                    (await
                     (db/transact!
                      {::db/db database
                       ::db/tx-data (:seon.db/tx-data plan)}))]
                (if (run.core/error-value? report)
                  report
                  (let [claimed
                        (await
                         (acquire-run-state!
                          (:db-after report) agent-id run-id))]
                    (assoc claimed
                           :seon.agent.driver/claimant claimant
                           :seon.agent.run/claim-epoch
                           (:seon.agent.run/claim-epoch plan))))))))))))

(defn ^:async release!
  "Release custody at the held epoch without changing the cursor."
  [{:seon.agent.driver/keys [run]
    claim-epoch :seon.agent.run/claim-epoch
    database :seon.db/db}]
  (await
   (db/transact!
    {::db/db database
     ::db/tx-data
     (run.core/release-tx-data
      (:seon.agent/id run)
      (:seon.agent.run/id run)
      claim-epoch)})))

(defn execution-plan-disposition
  "Classify one exact pre-dispatch plan without opening the eval phase."
  {:malli/schema
   [:=> [:cat
         [:map {:closed true}
          [:seon.execution/plan :seon.execution/plan]
          [:seon.execution/planning-projection
           :seon.execution/planning-projection]
          [:seon.execution/tier-inventories
           :seon.execution/tier-inventories]
          [:seon.execution/invoking-tier :seon.execution/tier]
          [:seon.execution/roots :seon.execution/roots]
          [:seon.execution/db-value :seon.db/db]]]
    :seon.db.protocol/ordinary-wire-value]}
  [{:seon.execution/keys
    [plan planning-projection tier-inventories invoking-tier roots db-value]}]
  (let [placement (:seon.execution/placement plan)
        selected-tier (:seon.execution/selected-tier plan)
        eligible-tiers (:seon.execution/eligible-tiers plan)
        inspected-tiers (set (keys tier-inventories))
        schema-manifest (:seon.execution/schema-manifest plan)
        capability-manifest (:seon.execution/capability-manifest plan)
        inventory (get tier-inventories selected-tier)
        installed (or (:seon.execution.inventory/bindings inventory) #{})
        required (:seon.execution/required-bindings capability-manifest)
        missing-leaves (into #{} (remove installed) required)
        artifacts (:seon.execution/artifact-inventories planning-projection)
        exports (get-in artifacts
                        [:seon.execution.inventory/exports-by-tier
                         selected-tier] #{})
        missing-exports
        (into #{} (remove exports)
              (:seon.execution/artifact-exports capability-manifest))
        schema-projection (:seon.execution/schema-projection planning-projection)
        schema-covered?
        (program.plan/manifest-covered-by-projection?
         schema-manifest schema-projection
         (:seon.execution/schema-fingerprint planning-projection))
        missing-schema-keys
        (into #{}
              (remove #(contains? (:seon.schema.projection/forms
                                   schema-projection) %))
              (:seon.execution/schema-keys schema-manifest))
        evidence
        {:seon.execution/roots roots
         :seon.execution/callsites
         (mapv #(select-keys % [:seon.execution/from
                               :seon.execution/target])
               (:seon.execution/unresolved plan))
         :seon.execution/missing-capability-leaves missing-leaves
         :seon.execution/missing-artifact-exports missing-exports
         :seon.execution/missing-schema-keys missing-schema-keys
         :seon.execution/unresolved (:seon.execution/unresolved plan)
         :seon.execution/planned-basis
         {:t (:seon.execution/basis-t planning-projection)
          :datahike/commit-id (:seon.execution/commit-id planning-projection)}
         :seon.execution/observed-basis
         {:t (:t db-value)
          :datahike/commit-id (:datahike/commit-id db-value)}
         :seon.execution/planned-generation
         (:seon.execution/graph-digest planning-projection)
         :seon.execution/observed-generation
         (:seon.execution/graph-digest planning-projection)
         :seon.execution/eligible-tiers eligible-tiers
         :seon.execution/inspected-tiers inspected-tiers}]
    (cond
      (and (= :unplannable placement)
           (= #{:no-roots}
              (into #{}
                    (map :seon.execution/reason)
                    (:seon.execution/unresolved plan))))
      {:seon.agent.driver/disposition :no-dispatch}

      (= :unplannable placement)
      {:seon.agent.driver/disposition :steering
       :seon.agent.driver/error
       {:seon.error/message
        "The parsed reply has no exact execution plan on an inspected tier."
        :seon.error/kind :agent
        :seon.error/data evidence}}

      (and selected-tier (not= selected-tier invoking-tier))
      {:seon.agent.driver/disposition :release
       :seon.execution/selected-tier selected-tier}

      (nil? selected-tier)
      {:seon.agent.driver/disposition
       (if (seq eligible-tiers) :release :steering)
       :seon.agent.driver/error
       (when-not (seq eligible-tiers)
         {:seon.error/message
          "The parsed reply has no exact execution plan on an inspected tier."
          :seon.error/kind :agent
          :seon.error/data evidence})}

      (or (seq missing-leaves) (seq missing-exports) (not schema-covered?))
      {:seon.agent.driver/disposition :core-fault
       :seon.agent.driver/error
       {:seon.error/message
        "The selected execution tier is missing a requirement from an exact plan."
        :seon.error/kind :core-bug
        :seon.error/data evidence}}

      :else
      {:seon.agent.driver/disposition :execute
       :seon.execution/selected-tier selected-tier})))

(defn- phase-error-fault
  [phase-error]
  (or (when (#{:agent :core} (:seon.error/fault phase-error))
        (:seon.error/fault phase-error))
      (if (or (= :agent (:seon.error/kind phase-error))
              (contains? error/agent-fault-kinds
                         (:seon.error/kind phase-error)))
        :agent
        :core)))

(defn- bounded-phase-error-message
  [phase-error]
  (let [message (:seon.error/message phase-error)
        maximum-characters 4096]
    (subs message 0 (min maximum-characters (count message)))))

(defn- ^:async terminal-or-displaced-result
  "Return terminal/lost-custody data when a failed phase no longer owns work."
  [held]
  (let [held-run (:seon.agent.driver/run held)
        agent-id (:seon.agent/id held-run)
        run-id (:seon.agent.run/id held-run)
        held-epoch (:seon.agent.run/claim-epoch held)
        held-claimant (or (:seon.agent.driver/claimant held)
                          (:seon.agent.run/claimant held-run))
        database (await (db/db))]
    (if (run.core/error-value? database)
      database
      (let [run
            (await
             (db/pull
              {::db/db database
               ::db/pull-pattern run-authority-selector
               ::db/ref [:seon.agent.run/id run-id]}))]
        (cond
          (run.core/error-value? run) run

          (= :closed (:seon.agent.run/status run))
          {:seon.db/db database
           :seon.agent.driver/closed? true
           :seon.agent.run/closed-reason
           (:seon.agent.run/closed-reason run)}

          (or (nil? run)
              (not= held-epoch (:seon.agent.run/claim-epoch run))
              (not= held-claimant (:seon.agent.run/claimant run)))
          {:seon.db/db database
           :seon.agent.driver/released? true}

          :else nil)))))

(defn- ^:async settle-phase-error!
  "Persist one direct phase error and atomically fault/release the held run."
  [held phase-error now]
  (let [run (:seon.agent.driver/run held)
        turn (:seon.agent.run/current-turn run)
        agent-id (:seon.agent/id run)
        run-id (:seon.agent.run/id run)
        claim-epoch (:seon.agent.run/claim-epoch held)
        run-fence (run.core/run-fence agent-id run-id claim-epoch)
        tx-data
        (if turn
          (turn.core/phase-error-close-tx-data
           run-fence agent-id run-id
           (:seon.agent.turn/id turn)
           (:seon.agent.turn/phase turn)
           (into []
                 (comp
                  (filter #(= :open (:seon.ai.attempt/outcome %)))
                  (map :seon.ai.attempt/id))
                 (:seon.agent.turn/llm-attempts turn))
           now
           (bounded-phase-error-message phase-error))
          (run.core/close-tx-data
           agent-id run-id claim-epoch :error now))
        report
        (await
         (db/transact!
          {::db/db (:seon.db/db held)
           ::db/tx-data tx-data}))
        recorded
        (error/record!
         {::error/raw
          (ex-info (:seon.error/message phase-error) phase-error)
          ::error/fault (phase-error-fault phase-error)})]
    (if (run.core/error-value? report)
      report
      {:seon.db/db (:db-after report)
       :seon.agent.driver/closed? true
       :seon.agent.run/closed-reason :error
       :seon.agent.driver/error recorded})))

(defn- ^:async execute-step-result
  "Execute one claimed phase and turn an escaped throw into flat error data."
  [held step]
  (try
    (await
     ((leaf-fn :seon.agent.driver/execute-step!)
      (assoc held :seon.agent.driver/step step)))
    (catch #?(:clj Throwable :cljs :default) throwable
      {:seon.error/message
       (str "The claimed " (name step) " phase threw: "
            (error/->message throwable))
       :seon.error/kind :core-bug
       :seon.error/data
       {:seon.agent.driver/step step}})))

(defn ^:async drive-claim!
  "Advance one held run until close, loss, or a clean tier handoff."
  [claim]
  (loop [held claim]
    (let [run (:seon.agent.driver/run held)
          capabilities (:seon.agent.driver/capabilities *leaf*)
          now ((leaf-fn :seon.agent.driver/now))]
      (if-let [reason (close-reason run
                                    (:seon.agent.driver/policy held)
                                    now)]
        (let [report
              (await
               (db/transact!
                {::db/db (:seon.db/db held)
                 ::db/tx-data
                 (run.core/close-tx-data
                  (:seon.agent/id run)
                  (:seon.agent.run/id run)
                  (:seon.agent.run/claim-epoch held)
                  reason now)}))]
          (if (run.core/error-value? report)
            report
            {:seon.db/db (:db-after report)
             :seon.agent.driver/closed? true
             :seon.agent.run/closed-reason reason}))
        (if-not (loop.core/eligible? capabilities run)
          (await (release! held))
          (let [step (loop.core/next-step run)
                result
                (await (execute-step-result held step))]
            (cond
              (run.core/error-value? result)
              (let [terminal-or-displaced
                    (await (terminal-or-displaced-result held))]
                (if terminal-or-displaced
                  terminal-or-displaced
                  (await (settle-phase-error! held result now))))
              (:seon.agent.driver/closed? result) result
              (:seon.agent.driver/released? result) result
              (:seon.db/db result)
              (let [next-state
                    (await
                     (acquire-run-state!
                      (:seon.db/db result)
                      (:seon.agent/id run)
                      (:seon.agent.run/id run)))]
                (if (run.core/error-value? next-state)
                  next-state
                  (recur
                   (merge held next-state
                          {:seon.agent.run/claim-epoch
                           (:seon.agent.run/claim-epoch held)}))))
              :else
              (await
               (settle-phase-error!
                held
                {:seon.error/message
                 (str "The " (name step) " leaf returned no database value.")
                 :seon.error/kind :core-bug}
                now)))))))))

(defn ^:async drive-run!
  "Claim and drive one open run; a CAS loser returns its direct error value."
  [request]
  (let [claim (await (claim! request))]
    (cond
      (nil? claim) nil
      (run.core/error-value? claim) claim
      :else (await (drive-claim! claim)))))

(defn ^:async scan!
  "Drive every currently open run that this tier can claim."
  []
  (let [database (await (db/db))]
    (if (run.core/error-value? database)
      database
      (let [rows
            (await
             (db/query
              {::db/db database
               ::db/query open-runs-query}))]
        (if (run.core/error-value? rows)
          rows
          (loop [remaining (vec (sort rows))
                 results []]
            (if-let [[agent-id run-id] (first remaining)]
              (recur
               (subvec remaining 1)
               (conj results
                     (if-let [dispatch
                              (:seon.agent.driver/dispatch-run! *leaf*)]
                       (dispatch
                        {:seon.agent/id agent-id
                         :seon.agent.run/id run-id})
                       (await
                        (drive-run!
                         {:seon.agent/id agent-id
                          :seon.agent.run/id run-id})))))
              results)))))))

(defn ^:async call-with-leaf
  "Run a driver operation with one platform leaf and database leaf."
  [platform-leaf database-leaf operation]
  (binding [*leaf* platform-leaf
            db/*leaf* database-leaf]
    (await (operation))))
