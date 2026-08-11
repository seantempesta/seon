(ns seon.schedule-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.env :as env]
            [seon.schedule :as schedule]
            [seon.test-support :as test-support])
  (:import [java.time Instant]
           [java.util Date]))

(defn- instant
  [text]
  (Date/from (Instant/parse text)))

(def ^:private handler-calls (atom []))

(defn successful-handler
  [request]
  (swap! handler-calls conj request)
  {:seon.operator.footprint/root (:seon.operator/managed-root request)
   :seon.operator.footprint/file-bytes 4096
   :seon.operator.footprint/usable-bytes 8192
   :seon.operator.footprint/total-bytes 12288
   :seon.operator.footprint/usable-ratio 0.5
   :seon.operator.footprint/observed-at
   (:seon.schedule.fire/observed-at request)
   :seon.operator/low-space? false})

(defn flat-error-handler
  [request]
  (swap! handler-calls conj request)
  {:seon.error/kind :seon.schedule-test/returned-error
   :seon.error/message "The scheduled test handler returned an error."})

(defn throwing-handler
  [request]
  (swap! handler-calls conj request)
  (throw (ex-info "The scheduled test handler threw."
                  {:seon.error/kind :seon.schedule-test/thrown-failure})))

(def ^:private result-caps
  {:seon.config.eval.result/max-depth 16
   :seon.config.eval.result/max-collection 100
   :seon.config.eval.result/max-string 4096
   :seon.config.eval.result/max-nodes 1000})

(defn- execution-context
  []
  {:seon.boot/cluster-name "default"
   :seon.operator/repository-root "/repo"
   :seon.operator/managed-root "/repo/operator"
   :seon.boot/log-dir "/repo/operator/data/clusters/default/logs"
   :seon.config.maintenance/min-usable-bytes 1
   :seon.config.maintenance/min-usable-ratio 0.01
   :seon.config.maintenance/log-max-bytes 1024
   :seon.config.maintenance/log-retained-files 2
   :seon.cluster.loop/cluster
   {:seon.cluster/name "default"
    :seon.cluster.run/process "schedule-test-process"
    :seon.sci.admit/caps result-caps
    :seon.config.error/recurrence-limit 3
    :seon.config.error/escalate-to "root"}})

(defn- seed-task!
  [connection task-id handler]
  (db/transact!
   connection
   [{:seon.cluster.agent/id "root"}
    {:seon.fn/sym handler}
    {:seon.schedule/id (str task-id "/schedule")
     :seon.schedule/expression "* * * * *"
     :seon.schedule/zone-id "UTC"}
    {:seon.schedule.task/id task-id
     :seon.schedule.task/owner [:seon.cluster.agent/id "root"]
     :seon.schedule.task/function [:seon.fn/sym handler]
     :seon.schedule.task/schedule
     [:seon.schedule/id (str task-id "/schedule")]}]))

(defn- count-with
  [database attribute]
  (or (db/q '[:find (count ?entity) .
              :in $ ?attribute
              :where [?entity ?attribute _]]
            database attribute)
      0))

(deftest every-public-schedule-contract-compiles
  (doseq [[function-name function-var] (ns-publics 'seon.schedule)
          :let [contract (:malli/schema (meta function-var))]
          :when contract]
    (is (some? (m/function-schema contract))
        (str "invalid contract on seon.schedule/" function-name))))

(deftest nominal-instants-obey-gap-and-overlap-rules
  (testing "a nonexistent spring-forward minute is skipped"
    (is (= (instant "2025-03-10T06:30:00Z")
           (schedule/next-nominal-after
            {:seon.schedule/expression "30 2 * * *"
             :seon.schedule/zone-id "America/New_York"
             :seon.schedule/reference-at (instant "2025-03-09T05:00:00Z")}))))
  (testing "a repeated fall-back minute has two distinct nominal instants"
    (let [first-at (instant "2025-11-02T05:30:00Z")]
      (is (= (instant "2025-11-02T06:30:00Z")
             (schedule/next-nominal-after
              {:seon.schedule/expression "30 1 * * *"
               :seon.schedule/zone-id "America/New_York"
               :seon.schedule/reference-at first-at}))))))

(deftest one-nominal-fire-calls-the-handler-once-without-a-turn
  (test-support/with-database
    (fn [connection]
      (reset! handler-calls [])
      (seed-task! connection "schedule-test/success"
                  "seon.schedule-test/successful-handler")
      (let [observed-at (instant "2025-04-05T12:34:45Z")]
        (is (= 1 (schedule/fire-due! connection "root" observed-at
                                     (execution-context))))
        (is (= 0 (schedule/fire-due! connection "root" observed-at
                                     (execution-context))))
        (is (= 1 (count @handler-calls)))
        (is (= 1 (count-with @connection :seon.schedule.fire/id)))
        (is (= 1 (count-with @connection :seon.maintenance.receipt/id)))
        (is (= 1 (count-with @connection
                             :seon.maintenance.receipt/completed-at)))
        (is (= 1 (count-with @connection :seon.maintenance.receipt/result)))
        (is (= 0 (count-with @connection :seon.cluster.run/id)))
        (is (= 0 (count-with @connection :seon.cluster.message/id)))
        (is (= (dissoc (first @handler-calls)
                       :seon.schedule.task/id
                       :seon.schedule.fire/id
                       :seon.cluster.agent/id
                       :seon.fn/sym
                       :seon.schedule.fire/nominal-at
                       :seon.schedule.fire/observed-at)
               (dissoc (execution-context) :seon.cluster.loop/cluster)))))))

(deftest restart-marks-a-claimed-receipt-interrupted-without-reexecution
  (test-support/with-database
    (fn [connection]
      (reset! handler-calls [])
      (let [task-id "schedule-test/interrupted"
            observed-at (instant "2025-04-05T12:34:45Z")
            nominal-at (instant "2025-04-05T12:34:00Z")
            fire-id (pr-str [task-id nominal-at])
            request
            (merge (dissoc (execution-context) :seon.cluster.loop/cluster)
                   {:seon.schedule.task/id task-id
                    :seon.schedule.fire/id fire-id
                    :seon.cluster.agent/id "root"
                    :seon.fn/sym "seon.schedule-test/successful-handler"
                    :seon.schedule.fire/nominal-at nominal-at
                    :seon.schedule.fire/observed-at observed-at})]
        (seed-task! connection task-id
                    "seon.schedule-test/successful-handler")
        (db/transact! connection
                      {:tx-data [[:db.fn/call #'schedule/fire-call request]]})
        (schedule/recover-interrupted! connection "root"
                                       (instant "2025-04-05T12:35:00Z"))
        (is (= 0 (schedule/fire-due! connection "root" observed-at
                                     (execution-context))))
        (is (empty? @handler-calls))
        (is (= 1 (count-with @connection
                             :seon.maintenance.receipt/interrupted-at)))
        (is (= 0 (count-with @connection
                             :seon.maintenance.receipt/completed-at)))))))

(deftest returned-and-thrown-handler-errors-use-the-existing-root-wake
  (doseq [[task-id handler expected-kind]
          [["schedule-test/returned" "seon.schedule-test/flat-error-handler"
            :seon.schedule-test/returned-error]
           ["schedule-test/thrown" "seon.schedule-test/throwing-handler"
            :seon.schedule-test/thrown-failure]]]
    (testing handler
      (test-support/with-database
        (fn [connection]
          (reset! handler-calls [])
          (seed-task! connection task-id handler)
          (is (= 1 (schedule/fire-due!
                    connection "root" (instant "2025-04-05T12:34:45Z")
                    (execution-context))))
          (is (= 1 (count @handler-calls)))
          (is (= 1 (count-with @connection :seon.maintenance.receipt/id)))
          (is (= 1 (count-with @connection :seon.maintenance.receipt/error)))
          (is (= 1 (count-with @connection :seon.error/id)))
          (is (= 1 (count-with @connection :seon.cluster.message/id)))
          (is (= expected-kind
                 (db/q '[:find ?kind .
                         :where [_ :seon.error/kind ?kind]]
                       @connection))))))))

(deftest schedule-remains-the-third-proc-in-the-agent-graph
  (let [environment (test-support/environment "seon.schedule-test")
        handle (env/carry {:seon.schedule/channel ::channel} environment)
        definition
        (agent/graph-definition
         {:seon.cluster.loop/cluster handle
          :seon.cluster.agent/id "root"})]
    (is (= #{::agent/mailbox ::agent/turn ::agent/schedule}
           (set (keys (:procs definition)))))))
