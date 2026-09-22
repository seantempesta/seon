(ns seon.schedule
  "Durable schedule facts and the per-agent Flow schedule proc.

  Cron expressions are parsed by cron-utils. Seon owns only the database
  identities and the `java.time` conversion to nominal instants. Every due
  instant records one durable maintenance firing. The existing per-agent
  schedule proc calls the declared Var directly; only an error settlement
  creates a message, through `seon.error/commit-tx`.

  EACH FIRING IS ITS OWN WAKE. `:seon.schedule.fire/agent` carries the
  fired task's owner and is a LISTENED attribute, so one firing routes
  one payload-free wake to that agent — asserted once on a new immutable
  entity, never re-asserted. It is declared `:seon.wake/opens-turn?
  false`: a firing surfaces in the agent's next context and never causes
  a model call by itself, because the maintenance portfolio's ticks are
  not turns."
  (:require [malli.core :as m]
            [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [seon.config :as config]
            [seon.db :as db]
            [seon.error :as error]
            [seon.error.refusal :as refusal]
            [seon.id :as id]
            [seon.maintenance :as maintenance]
            [seon.operator.runtime :as operator.runtime]
            [seon.schema :as schema]
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
    :seon.fn/sym 'seon.maintenance/observe-footprint!}
   {:seon.schedule/id "root/maintenance/rotate-logs-schedule"
    :seon.schedule/expression "30 2 * * *"
    :seon.schedule/zone-id "UTC"
    :seon.schedule.task/id "root/maintenance/rotate-logs"
    :seon.fn/sym 'seon.maintenance/rotate-logs!}
   {:seon.schedule/id "root/maintenance/compact-schedule"
    :seon.schedule/expression "0 3 * * 0"
    :seon.schedule/zone-id "UTC"
    :seon.schedule.task/id "root/maintenance/compact"
    :seon.fn/sym 'seon.maintenance/collect!}])

(defn root-maintenance-seed-call
  "Return initialization data for root's absent maintenance tasks.

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
                      [:seon.agent/id "root"]
                      :seon.schedule.task/function [:seon.fn/sym function]
                      :seon.schedule.task/schedule
                      [:seon.schedule/id schedule-id]}))))
         root-maintenance-portfolio)))

(defn valid-cron?
  "True when the supplied expression is valid five-field Unix cron."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
  [expression]
  (try
    (when (string? expression)
      (let [cron (.parse cron-parser expression)]
        (.validate ^Cron cron)
        true))
    (catch Throwable _ false)))

(defn valid-timezone?
  "True when `timezone` names an installed IANA time zone."
  {:malli/schema [:=> [:cat [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "A total predicate accepts arbitrary objects, including nil, and returns false when they do not satisfy its declared shape.", :gen/elements [nil false 0 "" :k [] {}]}]] :boolean]}
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
               [?owner :seon.agent/id ?agent-id]
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
                      {:seon.error/at (Date.)
                        :seon.error/layer :seon.schedule/execution
                        :seon.error/operation 'seon.schedule/task-created-at
                        :seon.error/message "Scheduled execution requires a task creation transaction instant."
                        :seon.error/offending {
                       :db/id task-eid
                       }
                        :seon.error/data {
                       :db/id task-eid
                       }
                        :seon.schedule/undated-task-entity task-eid
                        :seon.error/expected "a task creation transaction instant"}))))

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
  (id/digest 12 [:seon.schedule.fire/id task-id nominal-at]))

(defn- receipt-identity
  [claimed-fire-id]
  (id/digest 12 [:seon.maintenance.receipt/id claimed-fire-id]))

(defn- request-identity
  [claimed-fire-id]
  (id/digest 12 [:seon.maintenance.request/id claimed-fire-id]))

(defn- result-identity
  [claimed-receipt-id]
  (id/digest 12 [:seon.maintenance.result/id claimed-receipt-id]))

(defn- error-identity
  [claimed-receipt-id]
  (id/digest 12 [::maintenance-error claimed-receipt-id]))

(defn- request-entity
  [request task-eid function-eid fire-tempid request-tempid]
  (cond-> {:db/id request-tempid
           :seon.maintenance.request/id
           (request-identity (:seon.schedule.fire/id request))
           :seon.maintenance.request/task task-eid
           :seon.maintenance.request/fire fire-tempid
           :seon.maintenance.request/handler function-eid
           :seon.maintenance.request/agent
           [:seon.agent/id (:seon.agent/id request)]
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
    agent-id :seon.agent/id
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
         (db/q '[:find ?task ?owner-id ?function ?function-sym ?owner
                 :in $ ?task-id
                 :where
                 [?task :seon.schedule.task/id ?task-id]
                 [?task :seon.schedule.task/owner ?owner]
                 [?owner :seon.agent/id ?owner-id]
                 [?task :seon.schedule.task/function ?function]
                 [?function :seon.fn/sym ?function-sym]]
               database task-id))]
    (cond
      (or existing existing-receipt) []

      (not= requested-fire-id derived-fire-id)
      (throw (ex-info "The scheduled fire identity is not nominal-derived."
                      {:seon.error/at (Date.)
                        :seon.error/layer :seon.schedule/execution
                        :seon.error/operation 'seon.schedule/fire-call
                        :seon.error/message "Scheduled execution requires the nominal-derived fire identity."
                        :seon.error/offending {
                       :seon.schedule.fire/id requested-fire-id
                       :seon.schedule.fire/derived-id derived-fire-id }
                        :seon.error/data {
                       :seon.schedule.fire/id requested-fire-id
                       :seon.schedule.fire/derived-id derived-fire-id }
                        :seon.schedule/unexpected-fire-id requested-fire-id
                        :seon.error/expected "the nominal-derived fire identity"}))

      (nil? declaration)
      (throw (ex-info "The scheduled task declaration is incomplete."
                      {:seon.error/at (Date.)
                        :seon.error/layer :seon.schedule/execution
                        :seon.error/operation 'seon.schedule/fire-call
                        :seon.error/message "Scheduled execution requires a complete task declaration."
                        :seon.error/offending {
                       :seon.schedule.task/id task-id }
                        :seon.error/data {
                       :seon.schedule.task/id task-id }
                        :seon.schedule/incomplete-task-id task-id
                        :seon.error/expected "a complete task declaration"}))

      :else
      (let [[task-eid declared-owner function-eid declared-function owner-eid]
            declaration]
        (when-not (and (= agent-id declared-owner)
                       (= function declared-function))
          (throw
           (ex-info "The scheduled task owner or function changed."
                    {:seon.error/at (Date.)
                        :seon.error/layer :seon.schedule/execution
                        :seon.error/operation 'seon.schedule/fire-call
                        :seon.error/message "Scheduled execution requires the declared task owner and function."
                        :seon.error/offending {
                     :seon.schedule.task/id task-id
                     :seon.agent/id agent-id
                     :seon.fn/sym function }
                        :seon.error/data {
                     :seon.schedule.task/id task-id
                     :seon.agent/id agent-id
                     :seon.fn/sym function }
                        :seon.schedule/changed-task-id task-id
                        :seon.error/expected "the declared task owner and function"})))
        (let [fire-tempid (str "schedule-fire/" derived-fire-id)
              request-tempid (str fire-tempid "/request")]
          [{:db/id fire-tempid
            :seon.schedule.fire/id derived-fire-id
            :seon.schedule.fire/task [:seon.schedule.task/id task-id]
            ;; ONE FIRING, ONE AGENT, ONE DATOM WITH ITS OWN `:t`. The
            ;; recurrence definition asserts nothing; each firing is a new
            ;; immutable entity, so the wake fires exactly once per firing
            ;; and can never be re-asserted. The owner is resolved from the
            ;; task inside this same transaction, and carried on the firing
            ;; because the wake router does one map lookup and no query.
            :seon.schedule.fire/agent owner-eid
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
                      {:seon.error/at (Date.)
                        :seon.error/layer :seon.schedule/execution
                        :seon.error/operation 'seon.schedule/settle-call
                        :seon.error/message "Scheduled execution requires an existing maintenance receipt."
                        :seon.error/offending {
                       :seon.maintenance.receipt/id claimed-receipt-id }
                        :seon.error/data {
                       :seon.maintenance.receipt/id claimed-receipt-id }
                        :seon.schedule/missing-receipt-id claimed-receipt-id
                        :seon.error/expected "an existing maintenance receipt"}))

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
      (let [recording (error/recording database error-request)]
        (conj (:seon.db/tx-data recording)
              {:db/id [:seon.maintenance.receipt/id claimed-receipt-id]
               :seon.maintenance.receipt/completed-at completed-at
               :seon.maintenance.receipt/error (:seon.error/ref recording)}))

      :else
      (throw (ex-info "The maintenance terminal arm is invalid."
                      {:seon.error/at (Date.)
                        :seon.error/layer :seon.schedule/execution
                        :seon.error/operation 'seon.schedule/settle-call
                        :seon.error/message "Scheduled execution requires a result or error settlement arm."
                        :seon.error/offending {
                       :seon.maintenance.settlement/arm arm }
                        :seon.error/data {
                       :seon.maintenance.settlement/arm arm }
                        :seon.schedule/invalid-terminal-member :seon.maintenance.settlement/arm
                        :seon.error/expected "a result or error settlement arm"})))))

(defn recover-tx
  "Interrupt unfinished maintenance executions during cluster boot.

  Boot owns this database before any agent graph is armed. Recovery belongs
  to that lifecycle, never to a schedule proc's pause/resume transitions."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :inst] [:vector :map]]}
  [database interrupted-at]
  (->> (db/q '[:find [?receipt ...]
               :in $
               :where
               [?receipt :seon.maintenance.receipt/id _]
               (not [?receipt :seon.maintenance.receipt/completed-at _])
               (not [?receipt :seon.maintenance.receipt/result _])
               (not [?receipt :seon.maintenance.receipt/error _])
               (not [?receipt :seon.maintenance.receipt/interrupted-at _])]
             database)
       sort
       (mapv (fn [receipt-eid]
               {:db/id receipt-eid
                :seon.maintenance.receipt/interrupted-at interrupted-at}))))

(defn- transact-result!
  {:malli/schema [:=> [:cat :seon.db/connection :seon.store/transaction-data]
                  :seon.db/transaction-report]}
  [connection tx-data]
  (let [result (db/transact! connection {:tx-data tx-data})]
    ;; Debt: seon.db/transact! still returns the generic seon.db/error-result.
    (when (and (map? result) (:seon.error/at result)
               (:seon.error/layer result) (:seon.error/operation result))
      (throw (ex-info (:seon.error/message result) result)))
    result))



(defn- declared-maintenance-request-values
  [projection effective]
  (select-keys
   effective
   (m/explicit-keys
    (m/deref
     (m/schema :seon.maintenance.request/value
               {:registry (:seon.schema.projection/registry projection)})))))

(defn- canonical-path
  [path]
  (.getCanonicalPath (io/file path)))

(defn- execution-context
  [database cluster]
  (let [projection (schema/projection-from-database database)
        cluster-name (:seon.cluster/name cluster)
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
    (merge (declared-maintenance-request-values projection effective)
           {:seon.boot/cluster-name cluster-name
            :seon.operator/repository-root repository-root
            :seon.operator/managed-root managed-root
            :seon.boot/log-dir log-dir})))

(defn- error-request
  [cluster claimed-receipt-id agent-id declared-schema source completed-at]
  (cond-> {:seon.error/source source
           :seon.error/declared-schema declared-schema
           :seon.error/id (error-identity claimed-receipt-id)
           :seon.error/at completed-at
           :seon.error/process (:seon.db.process/id cluster)
           :seon.sci.admit/caps (:seon.sci.admit/caps cluster)
           :seon.config.error/recurrence-limit
           (:seon.config.error/recurrence-limit cluster)
           :seon.config.error/max-evidence-bytes
           (:seon.config.error/max-evidence-bytes cluster)
           :seon.agent/id agent-id}
    (:seon.config.error/escalate-to cluster)
    (assoc :seon.config.error/escalate-to
           (:seon.config.error/escalate-to cluster))))

(defn- settle!
  [connection cluster claimed-receipt-id agent-id result-or-failure]
  (let [completed-at (Date.)
        handler-result (:seon.maintenance.settlement/result result-or-failure)
        projection (db/carried-projection (db/db connection))
        declared-schema (:seon.error/declared-schema result-or-failure)
        result (if declared-schema handler-result
                   (maintenance/result-entity projection handler-result))
        declared-schema
        (or declared-schema
            (some (fn [[name valid?]] (when (valid? result) name))
                  (error/declared-output-validators
                   projection (:malli/schema (meta #'maintenance/result-entity)) 2)))
        source (when declared-schema result)
        request
        (cond-> {:seon.maintenance.receipt/id claimed-receipt-id
                 :seon.maintenance.receipt/completed-at completed-at}
          source
          (assoc :seon.maintenance.settlement/arm :error
                 :seon.maintenance.settlement/error-request
                 (error-request cluster claimed-receipt-id agent-id declared-schema source
                                completed-at))
          (nil? source)
          (assoc :seon.maintenance.settlement/arm :result
                 :seon.maintenance.settlement/result result))]
    (transact-result!
     connection
     [[:db.fn/call #'settle-call request]])))

(defn- invoke-handler
  "Capture a handler's declared error alternative before recording custody."
  {:seon.fn/invokes #{:seon.schedule.task/function}
   :malli/schema [:=> [:cat :seon.schema/projection :qualified-symbol :map]
                  [:map [:seon.maintenance.settlement/result :seon.schema/value]
                   [:seon.error/declared-schema {:optional true} :seon.error/declared-schema]]]}
  [projection function request]
  (try
    (if-let [handler (requiring-resolve function)]
      (let [result (handler request)
            declaration (some (fn [[name valid?]] (when (valid? result) name))
                              (error/declared-output-validators
                               projection (:malli/schema (meta handler)) 1))]
        (cond-> {:seon.maintenance.settlement/result result}
          declaration (assoc :seon.error/declared-schema declaration)))
      {:seon.error/declared-schema :seon.schedule/unresolved-handler-error
       :seon.maintenance.settlement/result {:seon.error/at (Date.)
                        :seon.error/layer :seon.schedule/execution
                        :seon.error/operation 'seon.schedule/invoke-handler
                        :seon.error/message "Scheduled execution requires a resolving handler Var."
                        :seon.error/offending {
                         :seon.fn/sym function }
                        :seon.error/data {
                         :seon.fn/sym function }
                        :seon.schedule/unresolved-handler-symbol function
                        :seon.error/expected "a resolving handler Var"}})
    (catch Throwable failure
      {:seon.error/declared-schema :seon.schedule/handler-failed-error
       :seon.maintenance.settlement/result
       (cond-> (refusal/diagnostic
        {:seon.error/at (Date.)
         :seon.error/layer :seon.schedule/execution
         :seon.error/operation 'seon.schedule/invoke-handler
         :seon.error/message (or (ex-message failure) "The scheduled handler failed.")
         :seon.schedule/failed-handler-symbol function
         :seon.error/throwable failure})
         (ex-data failure) (assoc :seon.schedule/handler-exception-data (ex-data failure)))})))

(defn fire-due!
  "Commit at most the latest due nominal instant for each task of `agent-id`.

  This is the sealed `:latest` recovery contract: process downtime cannot
  manufacture an unbounded replay storm. Returns the number of newly committed
  fires."
  {:malli/schema
   [:=> [:cat :seon.db/connection :seon.agent/id :inst
         :seon.schedule/execution-context]
    :seon.schedule/fire-count]}
  [connection agent-id observed-at context]
  (let [cluster (:seon.turn.loop/cluster context)
        common-request (dissoc context :seon.turn.loop/cluster)]
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
                           :seon.agent/id agent-id
                           :seon.fn/sym (:seon.fn/sym task)
                           :seon.schedule.fire/nominal-at nominal
                           :seon.schedule.fire/observed-at observed-at})
                   result
                   (transact-result!
                    connection
                    [[:db.fn/call #'fire-call request]])
                   claimed?
                   (some #(= :seon.maintenance.receipt/id (nth % 1))
                         (:tx-data result))]
               (if claimed?
                 (do
                   (settle! connection cluster (receipt-identity claimed-fire-id)
                            agent-id
                            (invoke-handler (db/carried-projection database) (:seon.fn/sym task)
                                            (assoc request :seon.db/connection connection)))
                   (inc fire-count))
                 fire-count))
             fire-count)))
       0
       (task-rows @connection agent-id))))

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
                            (:seon.agent/id state)))
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
   [:function [:=> [:cat] [:map]] [:=> [:cat :seon.schedule/proc-request] :map] [:=> [:cat :map :keyword] :map] [:=> [:cat :map :keyword [:any {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "core.async.flow supplies per-port messages of different declared shapes and accepts heterogeneous non-nil output messages; the port determines each message contract.", :gen/elements [nil false 0 "" :k [] {}]}]] [:tuple :map [:maybe [:map-of :keyword [:vector [:some {:seon.schema.admission/exemption :seon.schema.admission/polymorphic-boundary, :seon.schema.admission/reason "core.async.flow supplies per-port messages of different declared shapes and accepts heterogeneous non-nil output messages; the port determines each message contract.", :gen/elements [false 0 "" :k [] {}]}]]]]]]]}
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
   (let [connection (get-in state [:seon.turn.loop/cluster
                                   :seon.db/connection])
         listener-key (::listener-key state)]
     (case transition
       ::flow/resume
       (do
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
   (let [connection (get-in state [:seon.turn.loop/cluster
                                   :seon.db/connection])
         agent-id (:seon.agent/id state)
         observed-at (Date.)
         cluster (get-in state [:seon.turn.loop/cluster])
         fires (fire-due!
                connection agent-id observed-at
                (assoc (execution-context @connection cluster)
                       :seon.turn.loop/cluster cluster))
         next-at (earliest-next-at @connection agent-id observed-at)]
     [(-> state
          (update ::passes inc)
          (update ::fires + fires)
          (arm-timer next-at))
      nil])))
