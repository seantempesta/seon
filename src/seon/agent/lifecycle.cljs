(ns seon.agent.lifecycle
  "Control agent runs and lifecycle state through database facts.

   This agent-facing namespace exposes scoped operations for waiting,
   completing, pausing, resuming, and terminating work. It validates authority
   and returns errors as data; state derivation and loop execution live in
   their respective runtime owners."
  (:require
   [clojure.string :as str]
   [seon.agent.internal :as internal]
   [seon.agent.message :as message]
   [seon.agent.run :as run]
   [seon.db :as db]
   [seon.db.id :as db.id]
   [seon.db.protocol :as protocol]
   [seon.runtime.admission :as admission]
   [seon.schema :as schema]))

(schema/register! ::note :string)
(schema/register! ::result :string)
(schema/register! ::target-request
  [:map [:seon.agent/id {:optional true} :seon.agent/id]])
(schema/register! ::direct-error
  [:map [:seon.error/message :string]])
(schema/register! ::lifecycle-result
  [:or :seon.derive/state ::direct-error])

(defn- error-value?
  [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- error-value
  ([message]
   {:seon.error/message message})
  ([message data]
   {:seon.error/message message
    :seon.error/data data}))

(defn- no-open-run-error
  [function-name agent-id]
  (error-value
   (str function-name ": agent " (pr-str agent-id)
        " has no open run to act on (it is not currently running).")))

(defn ^:async ^:private acquire-target
  [database function-name caller-id target-id]
  (let [target
        (await
         (db/pull
          {::db/db database
           ::db/pull-pattern internal/managed-agent-selector
           ::db/ref [:seon.agent/id target-id]}))]
    (cond
      (error-value? target) target
      (not (internal/manages? caller-id target))
      (internal/unauthorized-target-error function-name caller-id target-id)
      :else target)))

(defn- stale-database-error?
  [value]
  (= protocol/stale-database-value-error
     (get-in value [:seon.error/data ::protocol/error-kind])))

(def ^:private maximum-lifecycle-attempts 32)

(defn ^:async ^:private retry-stale!
  [operation]
  (loop [attempt 1]
    (let [result (await (operation))]
      (if (and (stale-database-error? result)
               (< attempt maximum-lifecycle-attempts))
        (recur (inc attempt))
        result))))

(defn- completed-test-error
  [test-runs]
  (when-let [{:seon.agent.testrun/keys [passed failed errors]}
             (when (seq test-runs)
               (apply max-key :db/id test-runs))]
    (when (or (pos? (or failed 0)) (pos? (or errors 0)))
      (error-value
       (str "complete refused — your latest test run is RED ("
            (or failed 0) " failed, " (or passed 0) " passed"
            (when (pos? (or errors 0))
              (str ", " errors " error" (when (not= errors 1) "s")))
            "). Run the tests and SEE a green result render before completing; "
            "to stop without claiming success, pause or report the honest status "
            "with message/user.")))))

(defn- close-transaction-data
  [agent-id run-id reason closed-at]
  (run/close-tx-data true agent-id run-id reason closed-at))

(defn ^{:async true :seon.fn/agent-facing? true} wait
  "Park the calling agent by closing its current run as `:waited`."
  {:malli/schema [:=> [:catn [::note :string]] ::lifecycle-result]}
  [_note]
  (if-let [agent-id (db/current-agent-id)]
    (let [database (await (db/db))]
      (if (error-value? database)
        database
        (let [current (await
                       (run/current-run
                        {:seon.agent/id agent-id :seon.db/db database}))]
          (cond
            (error-value? current) current
            (nil? current) (no-open-run-error "wait" agent-id)
            :else
            (let [report
                  (await
                   (db/transact!
                    {::db/db database
                     ::db/tx-data
                     (close-transaction-data
                      agent-id (:seon.agent.run/id current) :waited
                      (js/Date.))}))]
              (if (error-value? report) report :idle))))))
    (internal/no-agent-error "wait")))

(def ^:private completion-agent-selector
  '[:db/id :seon.agent/id
    {:seon.agent/parent [:db/id :seon.agent/id]}
    {:seon.agent.testrun/_agent
     [:db/id :seon.agent.testrun/passed :seon.agent.testrun/failed
      :seon.agent.testrun/errors]}])

(defn ^:async ^:private completion-data
  [database agent-id current]
  (let [agent
        (await
         (db/pull
          {::db/db database
           ::db/pull-pattern completion-agent-selector
           ::db/ref [:seon.agent/id agent-id]}))]
    (if (error-value? agent)
      agent
      (let [parent (:seon.agent/parent agent)
            recipient-ref
            (if parent
              [:seon.agent/id (:seon.agent/id parent)]
              message/user-ref)
            recipient
            (if parent
              parent
              (await
               (db/pull
                {::db/db database
                 ::db/pull-pattern [:db/id :seon.user/id]
                 ::db/ref message/user-ref})))]
        (if (error-value? recipient)
          recipient
          (let [messages
                (await
                 (db/query
                  {::db/db database
                   ::db/query
                   '[:find ?message ?at
                     :in $ ?from ?to
                     :where
                     [?message :seon.agent.message/from ?from]
                     [?message :seon.agent.message/to ?to]
                     [?message :seon.agent.message/at ?at]]
                   ::db/args [(:db/id agent) (:db/id recipient)]
                   ::db/max-results 10000
                   ::db/max-result-weight 524288}))]
            (if (error-value? messages)
              messages
              {:seon.agent.lifecycle/agent agent
               :seon.agent.lifecycle/recipient recipient-ref
               :seon.agent.lifecycle/already-messaged?
               (boolean
                (some
                 (fn [[_ at]]
                   (>= (.getTime ^js at)
                       (.getTime ^js (:seon.agent.run/started-at current))))
                 messages))})))))))

(defn- completion-transaction-data
  [agent-id run-id result result-ref message-data ids now]
  (let [[fence close-row retract-pointer]
        (close-transaction-data agent-id run-id :completed now)
        result-row
        (cond-> {:seon.agent.run/id run-id}
          (not (str/blank? result)) (assoc :seon.agent.run/result result)
          (some? result-ref) (assoc :seon.agent.run/result-ref result-ref))
        message-rows
        (if message-data
          (:seon.db/tx-data
           ((:seon.agent.message/transaction-builder message-data) ids))
          [])]
    (into [fence]
          (concat
           (when (> (count result-row) 1) [result-row])
           message-rows
           [close-row retract-pointer]))))

(defn ^:async ^:private complete-once
  [result result-ref]
  (if-let [agent-id (db/current-agent-id)]
    (let [database (await (db/db))]
      (if (error-value? database)
        database
        (let [current
              (await
               (run/current-run
                {:seon.agent/id agent-id :seon.db/db database}))]
          (cond
            (error-value? current) current
            (nil? current) (no-open-run-error "complete" agent-id)
            :else
            (let [acquired (await (completion-data database agent-id current))]
              (if (error-value? acquired)
                acquired
                (or
                 (completed-test-error
                  (:seon.agent.testrun/_agent
                   (:seon.agent.lifecycle/agent acquired)))
                 (let [send?
                       (and (not (str/blank? result))
                            (not (:seon.agent.lifecycle/already-messaged?
                                  acquired)))
                       message-data
                       (when send?
                         (await
                          (message/message-transaction-for
                           database
                           {:seon.agent.message/content result
                            :seon.agent.message/from
                            [:seon.agent/id agent-id]
                            :seon.agent.message/to
                            [(:seon.agent.lifecycle/recipient acquired)]})))
                       run-id (:seon.agent.run/id current)]
                   (if (error-value? message-data)
                     message-data
                     (if message-data
                       (await
                        (db.id/allocate!
                         {::db/db database
                          ::db.id/allocations
                          (:seon.agent.message/allocations message-data)
                          ::db.id/transaction-builder
                          (fn [ids]
                            {::db/expected-db database
                             ::db/tx-data
                             (completion-transaction-data
                              agent-id run-id result result-ref message-data ids
                              (js/Date.))})}))
                       (await
                        (db/transact!
                         {::db/db database
                          ::db/expected-db database
                          ::db/tx-data
                          (completion-transaction-data
                           agent-id run-id result result-ref nil nil
                           (js/Date.))}))))))))))))
    (internal/no-agent-error "complete")))

(defn ^:async ^:private complete*
  [result result-ref]
  (let [final-result
        (await (retry-stale! #(complete-once result result-ref)))]
    (if (error-value? final-result) final-result :idle)))

(defn ^{:async true :seon.fn/agent-facing? true} complete
  "Complete the current run atomically with its result and optional message."
  {:malli/schema
   [:function
    [:=> [:catn [::result :string]] ::lifecycle-result]
    [:=> [:catn [::result :string] [::result-ref :seon.db/ref]]
     ::lifecycle-result]]}
  ([result] (await (complete* result nil)))
  ([result result-ref] (await (complete* result result-ref))))

(defn ^{:async true :seon.fn/agent-facing? true} pause
  "Pause the current run of a managed agent."
  {:malli/schema
   [:function
    [:=> [:catn] ::lifecycle-result]
    [:=> [:cat ::target-request] ::lifecycle-result]]}
  ([] (await (pause {})))
  ([{target-id :seon.agent/id}]
   (let [caller-id (db/current-agent-id)
         target-id (or target-id caller-id)]
     (if-not caller-id
       (internal/no-agent-error "pause")
       (let [database (await (db/db))]
         (if (error-value? database)
           database
           (let [target
                 (await
                  (acquire-target database "pause" caller-id target-id))]
             (if (error-value? target)
               target
               (let [current
                     (await
                      (run/current-run
                       {:seon.agent/id target-id :seon.db/db database}))]
                 (cond
                   (error-value? current) current
                   (nil? current) (no-open-run-error "pause" target-id)
                   :else
                   (let [report
                         (await
                          (run/pause!
                           {:seon.agent/id target-id
                            :seon.agent.run/id (:seon.agent.run/id current)
                            :seon.db/db database}))]
                     (if (error-value? report) report :paused))))))))))))

(defn ^{:async true :seon.fn/agent-facing? true} resume
  "Resume the paused current run of a managed agent."
  {:malli/schema
   [:function
    [:=> [:catn] ::lifecycle-result]
    [:=> [:cat ::target-request] ::lifecycle-result]]}
  ([] (await (resume {})))
  ([{target-id :seon.agent/id}]
   (if-not (admission/available?)
     (:seon/error (admission/unavailable))
     (let [caller-id (db/current-agent-id)
           target-id (or target-id caller-id)]
       (if-not caller-id
         (internal/no-agent-error "resume")
         (let [database (await (db/db))]
           (if (error-value? database)
             database
             (let [target
                   (await
                    (acquire-target database "resume" caller-id target-id))]
               (if (error-value? target)
                 target
                 (let [current
                       (await
                        (run/current-run
                         {:seon.agent/id target-id :seon.db/db database}))]
                   (cond
                     (error-value? current) current
                     (nil? current) (no-open-run-error "resume" target-id)
                     :else
                     (let [report
                           (await
                            (run/resume!
                             {:seon.agent/id target-id
                              :seon.agent.run/id (:seon.agent.run/id current)
                              :seon.db/db database}))]
                       (cond
                         (error-value? report) report
                         (not (admission/available?))
                         (:seon/error (admission/unavailable))
                         :else :running)))))))))))))

(defn ^:async ^:private terminate-once
  [caller-id target-id]
  (let [database (await (db/db))]
    (if (error-value? database)
      database
      (let [target
            (await
             (acquire-target database "terminate" caller-id target-id))]
        (cond
          (error-value? target) target
          (:seon.agent/terminated-at target) :terminated
          :else
          (let [current
                (await
                 (run/current-run
                  {:seon.agent/id target-id :seon.db/db database}))]
            (if (error-value? current)
              current
                (let [termination
                    [:db.fn/cas [:seon.agent/id target-id]
                     :seon.agent/terminated-at nil (js/Date.)]
                    release-namespace
                    [:db.fn/retractAttribute [:seon.agent/id target-id]
                     :seon.agent/namespace]
                    close-data
                    (when current
                      (close-transaction-data
                       target-id (:seon.agent.run/id current) :terminated
                       (js/Date.)))
                    report
                    (await
                     (db/transact!
                      {::db/db database
                       ::db/expected-db database
                       ::db/tx-data
                       (into [termination release-namespace] close-data)}))]
                (if (error-value? report) report :terminated)))))))))

(defn ^{:async true :seon.fn/agent-facing? true} terminate
  "Terminate a managed non-root agent and atomically close its current run."
  {:malli/schema [:=> [:catn [::id :seon.agent/id]] ::lifecycle-result]}
  [target-id]
  (if-let [caller-id (db/current-agent-id)]
    (if (= "root" target-id)
      (error-value "terminate: the cluster root cannot be terminated.")
      (await (retry-stale! #(terminate-once caller-id target-id))))
    (internal/no-agent-error "terminate")))
