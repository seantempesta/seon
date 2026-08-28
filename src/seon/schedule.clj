(ns seon.schedule
  "Durable schedule facts and the per-agent Flow schedule proc.

  Cron expressions are parsed by cron-utils. Seon owns only the database
  identities and the `java.time` conversion to nominal instants. Every due
  instant claims one durable maintenance receipt. The existing per-agent
  schedule proc calls the declared Var directly; only an error settlement
  creates a message, through `seon.error/commit-tx`."
  (:require [malli.core :as m]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.maintenance :as maintenance]
            [seon.operator.runtime :as operator.runtime]
            [seon.schema.edn :as schema.edn])
  (:import [com.cronutils.model Cron CronType]
           [com.cronutils.model.definition CronDefinitionBuilder]
           [com.cronutils.model.time ExecutionTime]
           [com.cronutils.parser CronParser]
           [java.time LocalDateTime ZoneId ZonedDateTime]
           [java.time.temporal ChronoUnit]
           [java.util Date Optional]))

(schema.edn/load! {})

(def ^:private unix-definition
  (CronDefinitionBuilder/instanceDefinitionFor CronType/UNIX))

(def ^:private cron-parser (CronParser. unix-definition))

(def ^:private root-maintenance-portfolio
  [{:seon.schedule/id "root/maintenance/footprint-schedule"
    :seon.schedule/expression "0 2 * * *"
    :seon.schedule/zone-id "UTC"
    :seon.schedule.task/id "root/maintenance/footprint"
    :seon.fn/sym "seon.operator/observe-footprint!"}
   {:seon.schedule/id "root/maintenance/reap-dead-roots-schedule"
    :seon.schedule/expression "15 2 * * *"
    :seon.schedule/zone-id "UTC"
    :seon.schedule.task/id "root/maintenance/reap-dead-roots"
    :seon.fn/sym "seon.operator/reap-dead-roots!"}
   {:seon.schedule/id "root/maintenance/rotate-logs-schedule"
    :seon.schedule/expression "30 2 * * *"
    :seon.schedule/zone-id "UTC"
    :seon.schedule.task/id "root/maintenance/rotate-logs"
    :seon.fn/sym "seon.operator/rotate-logs!"}
   {:seon.schedule/id "root/maintenance/process-census-schedule"
    :seon.schedule/expression "5 * * * *"
    :seon.schedule/zone-id "UTC"
    :seon.schedule.task/id "root/maintenance/process-census"
    :seon.fn/sym "seon.operator/census-processes!"}
   {:seon.schedule/id "root/maintenance/compact-schedule"
    :seon.schedule/expression "0 3 * * 0"
    :seon.schedule/zone-id "UTC"
    :seon.schedule.task/id "root/maintenance/compact"
    :seon.fn/sym "seon.operator/collect!"}])

(defn root-maintenance-seed-call
  "Return initialization data for root's five absent maintenance tasks.

  Existing task identities are sovereign. In particular, reopening a cluster
  never restores the recommended cron or timezone over an ordinary cadence
  transaction. This function runs through `:db.fn/call`, so absence is decided
  by the serial writer rather than by a caller pre-read."
  {:malli/schema
   [:=> [:cat :seon.db/database-value] :seon.store/transaction-data]}
  [database]
  (into []
        (mapcat
         (fn [{schedule-id :seon.schedule/id
               expression :seon.schedule/expression
               zone-id :seon.schedule/zone-id
               task-id :seon.schedule.task/id
               function :seon.fn/sym}]
           (when-not
            (db/q '[:find ?task .
                    :in $ ?task-id
                    :where [?task :seon.schedule.task/id ?task-id]]
                  database task-id)
             (cond-> []
               (not (db/q '[:find ?schedule .
                            :in $ ?schedule-id
                            :where [?schedule :seon.schedule/id ?schedule-id]]
                          database schedule-id))
               (conj {:seon.schedule/id schedule-id
                      :seon.schedule/expression expression
                      :seon.schedule/zone-id zone-id})
               true
               (conj {:seon.schedule.task/id task-id
                      :seon.schedule.task/owner
                      [:seon.cluster.agent/id "root"]
                      :seon.schedule.task/function [:seon.fn/sym function]
                      :seon.schedule.task/schedule
                      [:seon.schedule/id schedule-id]}))))
         root-maintenance-portfolio)))

(defn valid-cron?
  "True when `expression` is a valid five-field Unix cron expression."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [expression]
  (try
    (when (string? expression)
      (let [cron (.parse cron-parser expression)]
        (.validate ^Cron cron)
        true))
    (catch Throwable _ false)))

(defn valid-timezone?
  "True when `timezone` names an installed IANA time zone."
  {:malli/schema [:=> [:cat :seon.schema/value] :boolean]}
  [timezone]
  (try
    (when (string? timezone)
      (ZoneId/of timezone)
      true)
    (catch Throwable _ false)))

(defn- execution-time
  ^ExecutionTime [expression]
  (let [cron (.parse cron-parser expression)]
    (.validate ^Cron cron)
    (ExecutionTime/forCron cron)))

(defn- optional-value
  [^Optional value]
  (when (.isPresent value) (.get value)))

(defn- date->zoned
  ^ZonedDateTime [^Date value ^ZoneId zone]
  (ZonedDateTime/ofInstant (.toInstant value) zone))

(defn- overlap-twin
  "The later matching instant for reference's repeated local minute, or nil.

  cron-utils 9.2.1 intentionally suppresses the second instant for some
  less-than-hourly expressions when `nextExecution` starts at the first.
  ZoneRules is the authority for the two actual offsets; cron-utils remains
  the authority for whether that local minute matches the expression."
  ^ZonedDateTime [^ExecutionTime schedule ^ZonedDateTime reference]
  (let [zone (.getZone reference)
        local (.toLocalDateTime (.truncatedTo reference ChronoUnit/MINUTES))
        offsets (.getValidOffsets (.getRules zone) ^LocalDateTime local)]
    (when (= 2 (.size offsets))
      (let [candidates (mapv #(ZonedDateTime/ofLocal local zone %) offsets)]
        ;; The cron fields describe the local minute. cron-utils may suppress
        ;; `isMatch` for the later offset by the same duplicate-avoidance rule
        ;; that suppresses `nextExecution`, so establish local matching from
        ;; either valid offset and let ZoneRules provide both instants.
        (when (some #(.isMatch schedule ^ZonedDateTime %) candidates)
          (->> candidates
               (filter #(.isAfter (.toInstant ^ZonedDateTime %)
                                  (.toInstant reference)))
               (sort-by #(.toInstant ^ZonedDateTime %))
               first))))))

(defn next-nominal-after
  "The first distinct scheduled instant after `reference-at`, or nil."
  {:malli/schema
   [:=> [:cat :seon.schedule/nominal-request]
    [:or :inst :nil]]}
  [{expression :seon.schedule/expression
    zone-id :seon.schedule/zone-id
    reference-at :seon.schedule/reference-at}]
  (let [zone (ZoneId/of zone-id)
        schedule (execution-time expression)
        reference (date->zoned reference-at zone)
        library-next (optional-value (.nextExecution schedule reference))
        twin (overlap-twin schedule reference)
        next-at (first (sort-by #(.toInstant ^ZonedDateTime %)
                                (remove nil? [library-next twin])))]
    (some-> next-at .toInstant Date/from)))

(defn latest-nominal-at-or-before
  "The newest scheduled instant no later than `reference-at`, or nil.

  Adding one nanosecond makes an exactly aligned minute inclusive while the
  cron-utils API remains exclusive. Nonexistent local minutes stay absent;
  overlapping local minutes retain their distinct instants."
  {:malli/schema
   [:=> [:cat :seon.schedule/nominal-request]
    [:or :inst :nil]]}
  [{expression :seon.schedule/expression
    zone-id :seon.schedule/zone-id
    reference-at :seon.schedule/reference-at}]
  (let [zone (ZoneId/of zone-id)
        schedule (execution-time expression)
        reference (.plusNanos (date->zoned reference-at zone) 1)
        nominal (optional-value (.lastExecution schedule reference))]
    (some-> nominal .toInstant Date/from)))

(defn- task-rows
  [database agent-id]
  (->> (db/q '[:find ?task ?task-id ?function ?expression ?zone-id
               :in $ ?agent-id
               :where
               [?owner :seon.cluster.agent/id ?agent-id]
               [?task :seon.schedule.task/owner ?owner]
               [?task :seon.schedule.task/id ?task-id]
               [?task :seon.schedule.task/function ?function-row]
               [?function-row :seon.fn/sym ?function]
               [?task :seon.schedule.task/schedule ?schedule]
               [?schedule :seon.schedule/expression ?expression]
               [?schedule :seon.schedule/zone-id ?zone-id]]
             database agent-id)
       (map (fn [[task task-id function expression zone-id]]
              {:db/id task
               :seon.schedule.task/id task-id
               :seon.fn/sym function
               :seon.schedule/expression expression
               :seon.schedule/zone-id zone-id}))
       (sort-by :seon.schedule.task/id)))

(defn- task-created-at
  "The transaction instant that asserted the task's identity.

  A task with no fire history measures dueness from its own creation:
  a nominal instant that predates the task never fires. Without this
  floor a fresh cluster's first boot fired every seeded portfolio task
  at once — including the weekly store collection, whose reachability
  sweep then refused any concurrent cluster start on the same store."
  ^Date [database task-eid]
  (or (db/q '[:find ?instant .
              :in $ ?task
              :where
              [?task :seon.schedule.task/id _ ?tx]
              [?tx :db/txInstant ?instant]]
            database task-eid)
      (throw (ex-info "The task has no creation transaction instant."
                      {:seon.error/kind ::task-without-creation-instant
                       :db/id task-eid
                       :seon.schedule/task-without-creation-instant true}))))

(defn- latest-fire-at
  [database task-eid]
  (->> (db/q '[:find [?nominal ...]
               :in $ ?task
               :where
               [?fire :seon.schedule.fire/task ?task]
               [?fire :seon.schedule.fire/nominal-at ?nominal]]
             database task-eid)
       (sort)
       last))

(defn- nominal-fire-id
  [task-id nominal-at]
  (pr-str [task-id nominal-at]))

(defn- receipt-identity
  [claimed-fire-id]
  (str "maintenance-receipt/" claimed-fire-id))

(defn- request-identity
  [claimed-fire-id]
  (str "maintenance-request/" claimed-fire-id))

(defn- result-identity
  [claimed-receipt-id]
  (str "maintenance-result/" claimed-receipt-id))

(defn- error-identity
  [claimed-receipt-id]
  (str "maintenance-error/" claimed-receipt-id))

(defn- request-entity
  [request task-eid function-eid fire-tempid request-tempid]
  (cond-> {:db/id request-tempid
           :seon.maintenance.request/id
           (request-identity (:seon.schedule.fire/id request))
           :seon.maintenance.request/task task-eid
           :seon.maintenance.request/fire fire-tempid
           :seon.maintenance.request/handler function-eid
           :seon.maintenance.request/agent
           [:seon.cluster.agent/id (:seon.cluster.agent/id request)]
           :seon.maintenance.request/cluster-name
           (:seon.boot/cluster-name request)
           :seon.maintenance.request/repository-root
           (:seon.operator/repository-root request)
           :seon.maintenance.request/managed-root
           (:seon.operator/managed-root request)
           :seon.maintenance.request/log-dir
           (:seon.boot/log-dir request)
           :seon.maintenance.request/nominal-at
           (:seon.schedule.fire/nominal-at request)
           :seon.maintenance.request/observed-at
           (:seon.schedule.fire/observed-at request)}
    (:seon.config.maintenance/min-usable-bytes request)
    (assoc :seon.config.maintenance/min-usable-bytes
           (:seon.config.maintenance/min-usable-bytes request))
    (:seon.config.maintenance/min-usable-ratio request)
    (assoc :seon.config.maintenance/min-usable-ratio
           (:seon.config.maintenance/min-usable-ratio request))
    (:seon.config.maintenance/log-max-bytes request)
    (assoc :seon.config.maintenance/log-max-bytes
           (:seon.config.maintenance/log-max-bytes request))
    (contains? request :seon.config.maintenance/log-retained-files)
    (assoc :seon.config.maintenance/log-retained-files
           (:seon.config.maintenance/log-retained-files request))))

(defn fire-call
  "Claim one nominal fire and its maintenance receipt atomically.

  An existing fire identity returns no transaction data, making retries and
  restart derivation idempotent at the serial writer. The transaction snapshots
  the task's exact declared function without restricting its namespace."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.schedule.fire/request]
    :seon.store/transaction-data]}
  [database
   {task-id :seon.schedule.task/id
    requested-fire-id :seon.schedule.fire/id
    agent-id :seon.cluster.agent/id
    function :seon.fn/sym
    nominal-at :seon.schedule.fire/nominal-at
    observed-at :seon.schedule.fire/observed-at
    :as request}]
  (let [derived-fire-id (nominal-fire-id task-id nominal-at)
        claimed-receipt-id (receipt-identity derived-fire-id)
        existing (db/q '[:find ?fire .
                         :in $ ?id
                         :where [?fire :seon.schedule.fire/id ?id]]
                       database derived-fire-id)
        existing-receipt
        (db/q '[:find ?receipt .
                :in $ ?id
                :where [?receipt :seon.maintenance.receipt/id ?id]]
              database claimed-receipt-id)
        declaration
        (first
         (db/q '[:find ?task ?owner-id ?function ?function-sym
                 :in $ ?task-id
                 :where
                 [?task :seon.schedule.task/id ?task-id]
                 [?task :seon.schedule.task/owner ?owner]
                 [?owner :seon.cluster.agent/id ?owner-id]
                 [?task :seon.schedule.task/function ?function]
                 [?function :seon.fn/sym ?function-sym]]
               database task-id))]
    (cond
      (or existing existing-receipt) []

      (not= requested-fire-id derived-fire-id)
      (throw (ex-info "The scheduled fire identity is not nominal-derived."
                      {:seon.error/kind ::invalid-fire-id
                       :seon.schedule.fire/id requested-fire-id
                       :seon.schedule.fire/derived-id derived-fire-id :seon.schedule/invalid-fire-id true}))

      (nil? declaration)
      (throw (ex-info "The scheduled task declaration is incomplete."
                      {:seon.error/kind ::incomplete-task
                       :seon.schedule.task/id task-id :seon.schedule/incomplete-task true}))

      :else
      (let [[task-eid declared-owner function-eid declared-function]
            declaration]
        (when-not (and (= agent-id declared-owner)
                       (= function declared-function))
          (throw
           (ex-info "The scheduled task owner or function changed."
                    {:seon.error/kind ::invalid-task-owner
                     :seon.schedule.task/id task-id
                     :seon.cluster.agent/id agent-id
                     :seon.fn/sym function :seon.schedule/invalid-task-owner true})))
        (let [fire-tempid (str "schedule-fire/" derived-fire-id)
              request-tempid (str fire-tempid "/request")]
          [{:db/id fire-tempid
            :seon.schedule.fire/id derived-fire-id
            :seon.schedule.fire/task [:seon.schedule.task/id task-id]
            :seon.schedule.fire/nominal-at nominal-at
            :seon.schedule.fire/observed-at observed-at}
           (request-entity request task-eid function-eid fire-tempid
                           request-tempid)
           {:seon.maintenance.receipt/id claimed-receipt-id
            :seon.maintenance.receipt/fire fire-tempid
            :seon.maintenance.receipt/task task-eid
            :seon.maintenance.receipt/handler function-eid
            :seon.maintenance.receipt/request request-tempid
            :seon.maintenance.receipt/started-at observed-at}])))))

(defn- terminal-receipt
  [database claimed-receipt-id]
  (when-let [receipt-eid
             (db/q '[:find ?receipt .
                     :in $ ?id
                     :where [?receipt :seon.maintenance.receipt/id ?id]]
                   database claimed-receipt-id)]
    {:db/id receipt-eid
     :seon.maintenance.receipt/terminal-attributes
     (set
      (db/q '[:find [?attribute ...]
              :in $ ?receipt [?attribute ...]
              :where [?receipt ?attribute _]]
            database receipt-eid
            [:seon.maintenance.receipt/completed-at
             :seon.maintenance.receipt/interrupted-at
             :seon.maintenance.receipt/result
             :seon.maintenance.receipt/error]))}))

(defn- settle-call
  "Attach exactly one terminal result or error to a claimed receipt."
  [database
   {claimed-receipt-id :seon.maintenance.receipt/id
    completed-at :seon.maintenance.receipt/completed-at
    arm :seon.maintenance.settlement/arm
    result :seon.maintenance.settlement/result
    error-request :seon.maintenance.settlement/error-request}]
  (let [receipt (terminal-receipt database claimed-receipt-id)]
    (cond
      (nil? receipt)
      (throw (ex-info "The maintenance receipt does not exist."
                      {:seon.error/kind ::missing-receipt
                       :seon.maintenance.receipt/id claimed-receipt-id :seon.schedule/missing-receipt true}))

      (seq (:seon.maintenance.receipt/terminal-attributes receipt))
      []

      (= :result arm)
      (let [result-tempid (str "maintenance-result/" claimed-receipt-id)]
        [(assoc (dissoc result :db/id)
                :db/id result-tempid
                :seon.maintenance.result/id
                (result-identity claimed-receipt-id))
         {:db/id [:seon.maintenance.receipt/id claimed-receipt-id]
          :seon.maintenance.receipt/completed-at completed-at
          :seon.maintenance.receipt/result result-tempid}])

      (= :error arm)
      (let [error-tx (error/commit-tx database error-request)
            error-tempid (:db/id (first error-tx))]
        (conj error-tx
              {:db/id [:seon.maintenance.receipt/id claimed-receipt-id]
               :seon.maintenance.receipt/completed-at completed-at
               :seon.maintenance.receipt/error error-tempid}))

      :else
      (throw (ex-info "The maintenance terminal arm is invalid."
                      {:seon.error/kind ::invalid-terminal-arm
                       :seon.maintenance.settlement/arm arm :seon.schedule/invalid-terminal-arm true})))))

(defn- interrupt-call
  "Mark every unterminated receipt for one agent interrupted."
  [database agent-id interrupted-at]
  (->> (db/q '[:find [?receipt ...]
               :in $ ?agent-id
               :where
               [?agent :seon.cluster.agent/id ?agent-id]
               [?task :seon.schedule.task/owner ?agent]
               [?receipt :seon.maintenance.receipt/task ?task]
               (not [?receipt :seon.maintenance.receipt/completed-at _])
               (not [?receipt :seon.maintenance.receipt/result _])
               (not [?receipt :seon.maintenance.receipt/error _])
               (not [?receipt :seon.maintenance.receipt/interrupted-at _])]
             database agent-id)
       sort
       (mapv (fn [receipt-eid]
               {:db/id receipt-eid
                :seon.maintenance.receipt/interrupted-at interrupted-at}))))

(defn- transact-result!
  [connection tx-data refusal-kind refusal-data]
  (let [result (db/transact! connection {:tx-data tx-data})]
    (when (:seon.error/kind result)
      (throw (ex-info "The maintenance receipt transaction was refused."
                      (assoc refusal-data
                             :seon.error/kind refusal-kind
                             :seon.schedule/result result))))
    result))

(defn- flat-error?
  [value]
  (and (map? value)
       (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))

(defn- declared-maintenance-request-values
  [effective]
  (select-keys
   effective
   (m/explicit-keys
    (m/deref (m/schema :seon.maintenance.request/value)))))

(defn- canonical-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn- execution-context
  [database cluster]
  (let [cluster-name (:seon.cluster/name cluster)
        effective (config/effective database cluster-name)
        instance (get @operator.runtime/running-instances cluster-name)
        repository-root
        (canonical-path (or (System/getProperty "seon.repository.root")
                            (System/getProperty "user.dir")))
        managed-root
        (canonical-path (or (System/getProperty "seon.operator.root")
                            repository-root))
        log-dir
        (canonical-path
         (or (get-in instance [:seon.boot/config :seon.boot/log-dir])
             (io/file managed-root "data" "clusters" cluster-name "logs")))]
    (merge (declared-maintenance-request-values effective)
           {:seon.boot/cluster-name cluster-name
            :seon.operator/repository-root repository-root
            :seon.operator/managed-root managed-root
            :seon.boot/log-dir log-dir})))

(defn- error-request
  [cluster claimed-receipt-id agent-id source completed-at]
  (cond-> {:seon.error/source source
           :seon.error/id (error-identity claimed-receipt-id)
           :seon.error/at completed-at
           :seon.error/process (:seon.cluster.run/process cluster)
           :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
           :seon.config.error/recurrence-limit
           (:seon.config.error/recurrence-limit cluster)
           :seon.cluster.agent/id agent-id}
    (:seon.config.error/escalate-to cluster)
    (assoc :seon.config.error/escalate-to
           (:seon.config.error/escalate-to cluster))))

(defn- settle!
  [connection cluster claimed-receipt-id agent-id result-or-failure]
  (let [completed-at (Date.)
        handler-result (:seon.maintenance.settlement/result result-or-failure)
        failure (:seon.maintenance.settlement/failure result-or-failure)
        result (if failure
                 handler-result
                 (maintenance/result-entity handler-result))
        returned-error? (flat-error? result)
        source (cond
                 failure failure
                 returned-error?
                 (ex-info (:seon.error/message result) result)
                 :else nil)
        request
        (cond-> {:seon.maintenance.receipt/id claimed-receipt-id
                 :seon.maintenance.receipt/completed-at completed-at}
          source
          (assoc :seon.maintenance.settlement/arm :error
                 :seon.maintenance.settlement/error-request
                 (error-request cluster claimed-receipt-id agent-id source
                                completed-at))
          (nil? source)
          (assoc :seon.maintenance.settlement/arm :result
                 :seon.maintenance.settlement/result result))]
    (transact-result!
     connection
     [[:db.fn/call #'settle-call request]]
     ::settlement-refused
     {:seon.maintenance.receipt/id claimed-receipt-id})))

(defn- invoke-handler
  [function request]
  (try
    (let [handler (requiring-resolve (symbol function))]
      (when-not handler
        (throw (ex-info "The scheduled handler Var does not resolve."
                        {:seon.error/kind ::unresolved-handler
                         :seon.fn/sym function :seon.schedule/unresolved-handler true})))
      {:seon.maintenance.settlement/result (handler request)})
    (catch Throwable failure
      {:seon.maintenance.settlement/failure failure})))

(defn fire-due!
  "Commit at most the latest due nominal instant for each task of `agent-id`.

  This is the sealed `:latest` recovery contract: process downtime cannot
  manufacture an unbounded replay storm. Returns the number of newly committed
  fires."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/connection :seon.cluster.agent/id :inst]
     :seon.schedule/fire-count]
    [:=> [:cat :seon.db/connection :seon.cluster.agent/id :inst
          :seon.schedule/execution-context]
     :seon.schedule/fire-count]]}
  ([connection agent-id observed-at]
   (let [cluster-name
         (or (db/q '[:find ?cluster-name .
                     :where [_ :seon.cluster/name ?cluster-name]]
                   @connection)
             "default")
         instance (get @operator.runtime/running-instances cluster-name)
         cluster (:seon.cluster.loop/cluster instance)]
     (when-not cluster
       (throw (ex-info "The cluster execution handle is unavailable."
                       {:seon.error/kind ::missing-execution-handle
                        :seon.cluster/name cluster-name :seon.schedule/missing-execution-handle true})))
     (fire-due! connection agent-id observed-at
                (assoc (execution-context @connection cluster)
                       :seon.cluster.loop/cluster cluster))))
  ([connection agent-id observed-at context]
   (let [cluster (:seon.cluster.loop/cluster context)
         common-request (dissoc context :seon.cluster.loop/cluster)]
    (reduce
   (fn [fire-count task]
     (let [database @connection
           last-fire (latest-fire-at database (:db/id task))
           nominal
           (latest-nominal-at-or-before
            {:seon.schedule/expression (:seon.schedule/expression task)
             :seon.schedule/zone-id (:seon.schedule/zone-id task)
             :seon.schedule/reference-at observed-at})]
       (if (and nominal
                (if last-fire
                  (.after ^Date nominal ^Date last-fire)
                  (.after ^Date nominal
                          (task-created-at database (:db/id task)))))
         (let [task-id (:seon.schedule.task/id task)
               claimed-fire-id (nominal-fire-id task-id nominal)
               request
               (merge common-request
                      {:seon.schedule.task/id task-id
                       :seon.schedule.fire/id claimed-fire-id
                       :seon.cluster.agent/id agent-id
                       :seon.fn/sym (:seon.fn/sym task)
                       :seon.schedule.fire/nominal-at nominal
                       :seon.schedule.fire/observed-at observed-at})
               result
               (transact-result!
                connection
                [[:db.fn/call #'fire-call request]]
                ::fire-refused
                {:seon.schedule.task/id task-id})
               claimed?
               (some #(= :seon.maintenance.receipt/id (nth % 1))
                     (:tx-data result))]
           (if claimed?
             (do
               (settle! connection cluster (receipt-identity claimed-fire-id)
                        agent-id
                        (invoke-handler (:seon.fn/sym task) request))
               (inc fire-count))
             fire-count))
         fire-count)))
   0
   (task-rows @connection agent-id)))))

(defn recover-interrupted!
  "Mark claim-only receipts interrupted before deriving scheduled work."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.cluster.agent/id :inst] :map]}
  [connection agent-id interrupted-at]
  (transact-result!
   connection
   [[:db.fn/call #'interrupt-call agent-id interrupted-at]]
   ::recovery-refused
   {:seon.cluster.agent/id agent-id}))

(defn- earliest-next-at
  [database agent-id reference-at]
  (->> (task-rows database agent-id)
       (keep (fn [task]
               (next-nominal-after
                {:seon.schedule/expression (:seon.schedule/expression task)
                 :seon.schedule/zone-id (:seon.schedule/zone-id task)
                 :seon.schedule/reference-at reference-at})))
       sort
       first))

(defn- cancel-timer
  [state]
  (when-let [^Thread timer (::timer state)]
    (.interrupt timer))
  (dissoc state ::timer ::timer-at))

(defn- arm-timer
  [state ^Date nominal-at]
  (let [state (cancel-timer state)]
    (if-not nominal-at
      state
      (let [delay-ms (max 0 (- (.getTime nominal-at)
                               (System/currentTimeMillis)))
            kick (:seon.schedule/channel state)
            timer
            (-> (Thread/ofVirtual)
                (.name (str "seon-schedule-"
                            (:seon.cluster.agent/id state)))
                (.start
                 (fn []
                   (try
                     (Thread/sleep delay-ms)
                     (async/offer! kick ::kick)
                     (catch InterruptedException _ nil)))))]
        (assoc state ::timer timer ::timer-at nominal-at)))))

(def ^:private relevant-attributes
  #{:seon.schedule.task/id
    :seon.schedule.task/owner
    :seon.schedule.task/function
    :seon.schedule.task/schedule
    :seon.schedule/id
    :seon.schedule/expression
    :seon.schedule/zone-id})

(defn- relevant-report?
  [report]
  (boolean
   (some #(contains? relevant-attributes (nth % 1))
         (:tx-data report))))

(defn schedule-step
  "The per-agent schedule proc in Flow's four arities.

  It owns one virtual timer and one Datahike listener, both disposable. Timer
  and relevant-fact callbacks offer the same payload-free kick. Every transform
  derives due work and the next instant again from the current database value."
  {:malli/schema
   [:function
    [:=> [:cat] [:map]]
    [:=> [:cat :seon.schedule/proc-request] :map]
    [:=> [:cat :map :keyword] :map]
    [:=> [:cat :map :keyword :any]
     [:tuple :map [:maybe [:map-of :keyword [:vector :some]]]]]]}
  ([]
   {:ins {}
    :outs {}
    :workload :io
    :ping-map-fn #(select-keys % [::passes ::fires ::timer-at])})
  ([args]
   (assoc args
          ::flow/in-ports {::kick (:seon.schedule/channel args)}
          ::passes 0
          ::fires 0
          ::listener-key (random-uuid)))
  ([state transition]
   (let [connection (get-in state [:seon.cluster.loop/cluster
                                   :seon.db/connection])
         listener-key (::listener-key state)]
     (case transition
       ::flow/resume
       (do
         (recover-interrupted! connection
                               (:seon.cluster.agent/id state)
                               (Date.))
         (d/listen connection listener-key
                   (fn [report]
                     (when (relevant-report? report)
                       (async/offer! (:seon.schedule/channel state) ::kick))))
         (async/offer! (:seon.schedule/channel state) ::kick)
         state)

       ::flow/pause
       (do
         (d/unlisten connection listener-key)
         (cancel-timer state))

       ::flow/stop
       (do
         (d/unlisten connection listener-key)
         (async/close! (:seon.schedule/channel state))
         (cancel-timer state))

       state)))
  ([state _input _message]
   (let [connection (get-in state [:seon.cluster.loop/cluster
                                   :seon.db/connection])
         agent-id (:seon.cluster.agent/id state)
         observed-at (Date.)
         cluster (get-in state [:seon.cluster.loop/cluster])
         fires (fire-due!
                connection agent-id observed-at
                (assoc (execution-context @connection cluster)
                       :seon.cluster.loop/cluster cluster))
         next-at (earliest-next-at @connection agent-id observed-at)]
     [(-> state
          (update ::passes inc)
          (update ::fires + fires)
          (arm-timer next-at))
      nil])))
