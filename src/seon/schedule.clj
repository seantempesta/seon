(ns seon.schedule
  "Durable schedule facts and the per-agent Flow schedule proc.

  Cron expressions are parsed by cron-utils. Seon owns only the database
  identities and the `java.time` conversion to nominal instants. Every due
  instant is committed atomically with an ordinary outside-origin message;
  the message's existing `to` datom is the owning graph's wake."
  (:require [clojure.core.async :as async]
            [clojure.core.async.flow :as flow]
            [datahike.api :as d]
            [seon.db :as db]
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
    [:or :inst [:= nil]]]}
  [{expression :seon.schedule/cron
    timezone :seon.schedule/timezone
    reference-at :seon.schedule/reference-at}]
  (let [zone (ZoneId/of timezone)
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
    [:or :inst [:= nil]]]}
  [{expression :seon.schedule/cron
    timezone :seon.schedule/timezone
    reference-at :seon.schedule/reference-at}]
  (let [zone (ZoneId/of timezone)
        schedule (execution-time expression)
        reference (.plusNanos (date->zoned reference-at zone) 1)
        nominal (optional-value (.lastExecution schedule reference))]
    (some-> nominal .toInstant Date/from)))

(defn- task-rows
  [database agent-id]
  (->> (db/q '[:find ?task ?task-id ?function ?cron ?timezone
               :in $ ?agent-id
               :where
               [?owner :seon.cluster.agent/id ?agent-id]
               [?task :seon.schedule.task/owner ?owner]
               [?task :seon.schedule.task/id ?task-id]
               [?task :seon.schedule.task/function ?function-row]
               [?function-row :seon.fn/sym ?function]
               [?task :seon.schedule.task/schedule ?schedule]
               [?schedule :seon.schedule/cron ?cron]
               [?schedule :seon.schedule/timezone ?timezone]]
             database agent-id)
       (map (fn [[task task-id function cron timezone]]
              {:db/id task
               :seon.schedule.task/id task-id
               :seon.fn/sym function
               :seon.schedule/cron cron
               :seon.schedule/timezone timezone}))
       (sort-by :seon.schedule.task/id)))

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

(defn fire-call
  "Transaction function for one nominal fire and its ordinary message.

  An existing fire identity returns no transaction data, making retries and
  restart derivation idempotent at the serial writer. The declaration is also
  checked here: the task still belongs to the requested agent and its function
  still lives in that agent's assigned namespace."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.schedule.fire/request]
    :seon.store/transaction-data]}
  [database
   {task-id :seon.schedule.task/id
    agent-id :seon.cluster.agent/id
    function :seon.fn/sym
    nominal-at :seon.schedule.fire/nominal-at
    observed-at :seon.schedule.fire/observed-at}]
  (let [fire-id (pr-str [task-id nominal-at])
        existing (db/q '[:find ?fire .
                         :in $ ?id
                         :where [?fire :seon.schedule.fire/id ?id]]
                       database fire-id)
        declaration
        (first
         (db/q '[:find ?owner-id ?function-sym ?namespace-name
                 :in $ ?task-id
                 :where
                 [?task :seon.schedule.task/id ?task-id]
                 [?task :seon.schedule.task/owner ?owner]
                 [?owner :seon.cluster.agent/id ?owner-id]
                 [?owner :seon.cluster.agent/namespace ?namespace]
                 [?namespace :seon.ns/name ?namespace-name]
                 [?task :seon.schedule.task/function ?function]
                 [?function :seon.fn/sym ?function-sym]]
               database task-id))]
    (cond
      existing []

      (nil? declaration)
      (throw (ex-info "The scheduled task declaration is incomplete."
                      {:seon.error/kind ::incomplete-task
                       :seon.schedule.task/id task-id}))

      :else
      (let [[declared-owner declared-function namespace-name] declaration
            function-namespace (some-> function symbol namespace symbol)]
        (when-not (and (= agent-id declared-owner)
                       (= function declared-function)
                       (= namespace-name function-namespace))
          (throw
           (ex-info "The scheduled task owner, namespace, or function changed."
                    {:seon.error/kind ::invalid-task-owner
                     :seon.schedule.task/id task-id
                     :seon.cluster.agent/id agent-id
                     :seon.fn/sym function})))
        (let [message-id (str "schedule-fire/" fire-id)
              fire-tempid (str message-id "/fire")]
          [{:db/id fire-tempid
            :seon.schedule.fire/id fire-id
            :seon.schedule.fire/task [:seon.schedule.task/id task-id]
            :seon.schedule.fire/nominal-at nominal-at
            :seon.schedule.fire/observed-at observed-at}
           {:seon.cluster.message/id message-id
            :seon.cluster.message/to [:seon.cluster.agent/id agent-id]
            :seon.cluster.message/content
            (str "Scheduled task " task-id " fired. Call (" function " "
                 (pr-str {:seon.schedule.fire/id fire-id}) ").")
            :seon.cluster.message/at observed-at
            :seon.cluster.message/ordinal 0}])))))

(defn fire-due!
  "Commit at most the latest due nominal instant for each task of `agent-id`.

  This is the sealed `:latest` recovery contract: process downtime cannot
  manufacture an unbounded replay storm. Returns the number of newly committed
  fires."
  {:malli/schema
   [:=> [:cat :seon.store/branch-connection :seon.cluster.agent/id :inst]
    :seon.schedule/fire-count]}
  [connection agent-id observed-at]
  (reduce
   (fn [fire-count task]
     (let [database @connection
           last-fire (latest-fire-at database (:db/id task))
           nominal
           (latest-nominal-at-or-before
            {:seon.schedule/cron (:seon.schedule/cron task)
             :seon.schedule/timezone (:seon.schedule/timezone task)
             :seon.schedule/reference-at observed-at})]
       (if (and nominal
                (or (nil? last-fire) (.after ^Date nominal ^Date last-fire)))
         (let [result
               (db/transact!
                connection
                {:tx-data
                 [[:db.fn/call #'fire-call
                   {:seon.schedule.task/id (:seon.schedule.task/id task)
                    :seon.cluster.agent/id agent-id
                    :seon.fn/sym (:seon.fn/sym task)
                    :seon.schedule.fire/nominal-at nominal
                    :seon.schedule.fire/observed-at observed-at}]]})]
           (when (:seon.error/kind result)
             (throw (ex-info "The scheduled fire transaction was refused."
                             {:seon.error/kind ::fire-refused
                              :seon.schedule.task/id
                              (:seon.schedule.task/id task)
                              :seon.schedule/result result})))
           (if (some #(= :seon.schedule.fire/id (nth % 1))
                     (:tx-data result))
             (inc fire-count)
             fire-count))
         fire-count)))
   0
   (task-rows @connection agent-id)))

(defn- earliest-next-at
  [database agent-id reference-at]
  (->> (task-rows database agent-id)
       (keep (fn [task]
               (next-nominal-after
                {:seon.schedule/cron (:seon.schedule/cron task)
                 :seon.schedule/timezone (:seon.schedule/timezone task)
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
    :seon.schedule/cron
    :seon.schedule/timezone})

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
                                   :seon.store/branch-connection])
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
         (cancel-timer state))

       state)))
  ([state _input _message]
   (let [connection (get-in state [:seon.cluster.loop/cluster
                                   :seon.store/branch-connection])
         agent-id (:seon.cluster.agent/id state)
         observed-at (Date.)
         fires (fire-due! connection agent-id observed-at)
         next-at (earliest-next-at @connection agent-id observed-at)]
     [(-> state
          (update ::passes inc)
          (update ::fires + fires)
          (arm-timer next-at))
      nil])))
