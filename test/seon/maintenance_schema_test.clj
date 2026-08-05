(ns seon.maintenance-schema-test
  "Schema and initialization proofs for turn-free maintenance receipts."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support])
  (:import [java.time Instant]
           [java.util Date]))

(schema.edn/load! {})

(defn- instant
  [text]
  (Date/from (Instant/parse text)))

(def ^:private portfolio
  [{:seon.schedule.task/id "root/maintenance/footprint"
    :seon.schedule/id "root/maintenance/footprint-schedule"
    :seon.schedule/expression "0 2 * * *"
    :seon.fn/sym "seon.operator/observe-footprint!"}
   {:seon.schedule.task/id "root/maintenance/reap-dead-roots"
    :seon.schedule/id "root/maintenance/reap-dead-roots-schedule"
    :seon.schedule/expression "15 2 * * *"
    :seon.fn/sym "seon.operator/reap-dead-roots!"}
   {:seon.schedule.task/id "root/maintenance/rotate-logs"
    :seon.schedule/id "root/maintenance/rotate-logs-schedule"
    :seon.schedule/expression "30 2 * * *"
    :seon.fn/sym "seon.operator/rotate-logs!"}
   {:seon.schedule.task/id "root/maintenance/process-census"
    :seon.schedule/id "root/maintenance/process-census-schedule"
    :seon.schedule/expression "5 * * * *"
    :seon.fn/sym "seon.operator/census-processes!"}
   {:seon.schedule.task/id "root/maintenance/compact"
    :seon.schedule/id "root/maintenance/compact-schedule"
    :seon.schedule/expression "0 3 * * 0"
    :seon.fn/sym "seon.operator/collect!"}])

(deftest maintenance-maps-are-open-and-components-are-owned
  (let [nominal-at (instant "2026-08-05T02:00:00Z")
        observed-at (instant "2026-08-05T02:00:01Z")
        request
        {:seon.schedule.task/id "root/maintenance/footprint"
         :seon.schedule.fire/id "root/maintenance/footprint@1785895200000"
         :seon.schedule.fire/nominal-at nominal-at
         :seon.schedule.fire/observed-at observed-at
         :seon.cluster.agent/id "root"
         :seon.boot/cluster-name "default"
         :seon.operator/repository-root "/repo"
         :seon.operator/managed-root "/repo"
         :seon.boot/log-dir "/repo/data/clusters/default/logs"
         :seon.config.maintenance/min-usable-bytes 1
         :seon.maintenance-schema-test/extra-request-value true}
        result
        {:seon.operator.footprint/file-bytes 4096
         :seon.operator/low-space? false
         :seon.maintenance-schema-test/extra-result-value true}
        receipt
        {:seon.maintenance.receipt/id
         "root/maintenance/footprint@1785895200000"
         :seon.maintenance.receipt/fire
         [:seon.schedule.fire/id
          "root/maintenance/footprint@1785895200000"]
         :seon.maintenance.receipt/task
         [:seon.schedule.task/id "root/maintenance/footprint"]
         :seon.maintenance.receipt/handler
         [:seon.fn/sym "seon.operator/observe-footprint!"]
         :seon.maintenance.receipt/request
         [:seon.maintenance.request/id
          "root/maintenance/footprint@1785895200000"]
         :seon.maintenance.receipt/started-at observed-at
         :seon.maintenance-schema-test/extra-receipt-value true}]
    (testing "required entries validate while unrelated entries accrete"
      (is (true? (schema/valid-candidate-value?
                  :seon.maintenance.request/value request)))
      (is (true? (schema/valid-candidate-value?
                  :seon.maintenance.result/value result)))
      (is (true? (schema/valid-candidate-value?
                  :seon.maintenance.receipt/receipt receipt))))
    (testing "the request and result are component refs, not serialized maps"
      (doseq [attribute [:seon.maintenance.receipt/request
                         :seon.maintenance.receipt/result]]
        (is (= {:db/ident attribute
                :db/valueType :db.type/ref
                :db/cardinality :db.cardinality/one
                :db/isComponent true}
               (schema.datahike/malli->datahike-attr attribute)))))))

(deftest receipt-request-and-operation-result-attributes-are-queryable
  (test-support/with-database
    (fn [connection]
      (let [task-id "maintenance-schema-test/task"
            schedule-id "maintenance-schema-test/schedule"
            fire-id "maintenance-schema-test/fire"
            handler "maintenance-schema-test/handler"
            receipt-id "maintenance-schema-test/receipt"
            nominal-at (instant "2026-08-05T02:00:00Z")
            observed-at (instant "2026-08-05T02:00:01Z")]
        (db/transact!
         connection
         [{:seon.cluster.agent/id "maintenance-schema-test/root"}
          {:seon.fn/sym handler}
          {:seon.schedule/id schedule-id
           :seon.schedule/expression "0 2 * * *"
           :seon.schedule/zone-id "UTC"}
          {:seon.schedule.task/id task-id
           :seon.schedule.task/owner
           [:seon.cluster.agent/id "maintenance-schema-test/root"]
           :seon.schedule.task/function [:seon.fn/sym handler]
           :seon.schedule.task/schedule [:seon.schedule/id schedule-id]}
          {:seon.schedule.fire/id fire-id
           :seon.schedule.fire/task [:seon.schedule.task/id task-id]
           :seon.schedule.fire/nominal-at nominal-at
           :seon.schedule.fire/observed-at observed-at}
          {:seon.maintenance.receipt/id receipt-id
           :seon.maintenance.receipt/fire [:seon.schedule.fire/id fire-id]
           :seon.maintenance.receipt/task [:seon.schedule.task/id task-id]
           :seon.maintenance.receipt/handler [:seon.fn/sym handler]
           :seon.maintenance.receipt/request
           {:seon.maintenance.request/id receipt-id
            :seon.maintenance.request/task
            [:seon.schedule.task/id task-id]
            :seon.maintenance.request/fire
            [:seon.schedule.fire/id fire-id]
            :seon.maintenance.request/handler [:seon.fn/sym handler]
            :seon.maintenance.request/agent
            [:seon.cluster.agent/id "maintenance-schema-test/root"]
            :seon.maintenance.request/cluster-name "default"
            :seon.maintenance.request/repository-root "/repo"
            :seon.maintenance.request/managed-root "/repo"
            :seon.maintenance.request/log-dir
            "/repo/data/clusters/default/logs"
            :seon.maintenance.request/nominal-at nominal-at
            :seon.maintenance.request/observed-at observed-at
            :seon.config.maintenance/min-usable-bytes 1}
           :seon.maintenance.receipt/started-at observed-at
           :seon.maintenance.receipt/completed-at observed-at
           :seon.maintenance.receipt/result
           {:seon.maintenance.result/id receipt-id
            :seon.operator.footprint/file-bytes 4096
            :seon.operator/low-space? false}}])
        (is (= #{[receipt-id task-id 4096 false]}
               (db/q
                '[:find ?receipt-id ?task-id ?bytes ?low-space
                  :where
                  [?receipt :seon.maintenance.receipt/id ?receipt-id]
                  [?receipt :seon.maintenance.receipt/request ?request]
                  [?request :seon.maintenance.request/task ?task]
                  [?task :seon.schedule.task/id ?task-id]
                  [?receipt :seon.maintenance.receipt/result ?result]
                  [?result :seon.operator.footprint/file-bytes ?bytes]
                  [?result :seon.operator/low-space? ?low-space]]
                @connection)))))))

(deftest shipped-portfolio-applies-as-queryable-schedule-facts
  (test-support/with-database
    (fn [connection]
      (db/transact!
       connection
       (into [{:seon.cluster.agent/id "root"}]
             (map (fn [row] {:seon.fn/sym (:seon.fn/sym row)}))
             portfolio))
      (let [result (config/apply! {:seon.db/connection connection})
            schedules
            (db/q
             '[:find ?task-id ?schedule-id ?expression ?zone-id ?handler
               :where
               [?task :seon.schedule.task/id ?task-id]
               [?task :seon.schedule.task/owner ?owner]
               [?owner :seon.cluster.agent/id "root"]
               [?task :seon.schedule.task/function ?function]
               [?function :seon.fn/sym ?handler]
               [?task :seon.schedule.task/schedule ?schedule]
               [?schedule :seon.schedule/id ?schedule-id]
               [?schedule :seon.schedule/expression ?expression]
               [?schedule :seon.schedule/zone-id ?zone-id]]
             @connection)]
        (is (false? (:seon.reconcile/converged? result)))
        (is (= (set (map (fn [row]
                           [(:seon.schedule.task/id row)
                            (:seon.schedule/id row)
                            (:seon.schedule/expression row)
                            "UTC"
                            (:seon.fn/sym row)])
                         portfolio))
               schedules))))))
