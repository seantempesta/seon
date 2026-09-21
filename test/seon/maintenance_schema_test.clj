(ns seon.maintenance-schema-test
  "Schema and initialization proofs for turn-free maintenance receipts."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.maintenance :as maintenance]
            [seon.schedule :as schedule]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.edn :as schema.edn]
            [seon.test-support :as test-support])
  (:import [java.time Instant]
           [java.util Date UUID]))

(schema.edn/load! {})

(defn- instant
  [text]
  (Date/from (Instant/parse text)))

(def ^:private portfolio
  [{:seon.schedule.task/id "root/maintenance/footprint"
    :seon.schedule/id "root/maintenance/footprint-schedule"
    :seon.schedule/expression "0 2 * * *"
    :seon.schedule/zone-id "UTC"
    :seon.fn/sym 'seon.maintenance/observe-footprint!}
   {:seon.schedule.task/id "root/maintenance/reap-dead-roots"
    :seon.schedule/id "root/maintenance/reap-dead-roots-schedule"
    :seon.schedule/expression "15 2 * * *"
    :seon.schedule/zone-id "UTC"
    :seon.fn/sym 'seon.operator/reap-dead-roots!}
   {:seon.schedule.task/id "root/maintenance/rotate-logs"
    :seon.schedule/id "root/maintenance/rotate-logs-schedule"
    :seon.schedule/expression "30 2 * * *"
    :seon.schedule/zone-id "UTC"
    :seon.fn/sym 'seon.maintenance/rotate-logs!}
   {:seon.schedule.task/id "root/maintenance/process-census"
    :seon.schedule/id "root/maintenance/process-census-schedule"
    :seon.schedule/expression "5 * * * *"
    :seon.schedule/zone-id "UTC"
    :seon.fn/sym 'seon.operator/census-processes!}
   {:seon.schedule.task/id "root/maintenance/compact"
    :seon.schedule/id "root/maintenance/compact-schedule"
    :seon.schedule/expression "0 3 * * 0"
    :seon.schedule/zone-id "UTC"
    :seon.fn/sym 'seon.maintenance/collect!}])

(deftest maintenance-maps-are-open-and-components-are-owned
  (let [nominal-at (instant "2026-08-05T02:00:00Z")
        observed-at (instant "2026-08-05T02:00:01Z")
        request
        {:seon.schedule.task/id "root/maintenance/footprint"
         :seon.schedule.fire/id "root/maintenance/footprint@1785895200000"
         :seon.schedule.fire/agent [:seon.agent/id "root"]
         :seon.schedule.fire/nominal-at nominal-at
         :seon.schedule.fire/observed-at observed-at
         :seon.agent/id "root"
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
         [:seon.fn/sym 'seon.maintenance/observe-footprint!]
         :seon.maintenance.receipt/request
         [:seon.maintenance.request/id
          "root/maintenance/footprint@1785895200000"]
         :seon.maintenance.receipt/started-at observed-at
         :seon.maintenance-schema-test/extra-receipt-value true}]
    (testing "required entries validate while unrelated entries accrete"
      (is (true? ((schema/projection-validator (schema/handed-projection) :seon.maintenance.request/value) request)))
      (is (true? ((schema/projection-validator (schema/handed-projection) :seon.maintenance.result/value) result)))
      (is (true? ((schema/projection-validator (schema/handed-projection) :seon.maintenance.receipt/receipt) receipt))))
    (testing "the request and result are component refs, not serialized maps"
      (doseq [attribute [:seon.maintenance.receipt/request
                         :seon.maintenance.receipt/result]]
        (is (= {:db/ident attribute
                :db/valueType :db.type/ref
                :db/cardinality :db.cardinality/one
                :db/isComponent true}
               (schema.datahike/malli->datahike-attr-in (seon.schema/handed-projection) attribute)))))))

(deftest receipt-request-and-operation-result-attributes-are-queryable
  (test-support/with-database
    (fn [connection]
      (let [task-id "maintenance-schema-test/task"
            schedule-id "maintenance-schema-test/schedule"
            fire-id "maintenance-schema-test/fire"
            ;; The fn row refs its namespace row, and only namespaces the
            ;; canonical population already holds can be referenced.
            handler 'seon.maintenance/observe-footprint!
            receipt-id "maintenance-schema-test/receipt"
            nominal-at (instant "2026-08-05T02:00:00Z")
            observed-at (instant "2026-08-05T02:00:01Z")]
        (test-support/transacted!
                     connection
                     [{:seon.agent/id "maintenance-schema-test/root"}
                      (test-support/program-fn-row handler)
                      {:seon.schedule/id schedule-id
                       :seon.schedule/expression "0 2 * * *"
                       :seon.schedule/zone-id "UTC"}
                      {:seon.schedule.task/id task-id
                       :seon.schedule.task/owner
                       [:seon.agent/id "maintenance-schema-test/root"]
                       :seon.schedule.task/function [:seon.fn/sym handler]
                       :seon.schedule.task/schedule [:seon.schedule/id schedule-id]}
                      {:seon.schedule.fire/id fire-id
                       :seon.schedule.fire/task [:seon.schedule.task/id task-id]
                       :seon.schedule.fire/agent
                       [:seon.agent/id "maintenance-schema-test/root"]
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
                        [:seon.agent/id "maintenance-schema-test/root"]
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

(deftest declared-operation-results-install-through-the-production-population
  (test-support/with-database
    (fn [connection]
      (let [store-id (UUID/fromString "00000000-0000-0000-0000-000000000101")
            commit-id (UUID/fromString "00000000-0000-0000-0000-000000000102")
            log-result
            {:seon.operator.log/path "/repo/operator/logs/seon.log"
             :seon.operator.log/bytes-before 4096
             :seon.operator.log/bytes-after 1024
             :seon.operator.log/rotated? true
             :seon.operator.log/retained-files 4}
            collect-result
            {:seon.operator.collect/store-id store-id
             :seon.operator.collect/managed-root "/repo/operator"
             :seon.operator.collect/branches
             [{:seon.store/branch :cluster-default
               :seon.source/commit-id commit-id}]
             :seon.operator.collect/objects-before 12
             :seon.operator.collect/objects-after 10
             :seon.operator.collect/swept-objects 2
             :seon.operator.collect/bytes-before 8192
             :seon.operator.collect/bytes-after 4096
             :seon.operator.collect/reclaimed-bytes 4096
             :seon.operator.collect/verification-pass-swept 0
             :seon.operator.collect/roots-verified? true
             :seon.operator.collect/complete? true}]
        (test-support/transacted!
                     connection
                     [(assoc (maintenance/result-entity (schema/handed-projection) log-result)
                             :seon.maintenance.result/id "maintenance-result/log")
                      (assoc (maintenance/result-entity (schema/handed-projection) collect-result)
                             :seon.maintenance.result/id "maintenance-result/collect")])
        (is (= "/repo/operator/logs/seon.log"
               (db/q '[:find ?path .
                       :where
                       [?result :seon.maintenance.result/id
                        "maintenance-result/log"]
                       [?result :seon.operator.log/path ?path]]
                     @connection)))
        (is (= #{[4096 :cluster-default commit-id]}
               (db/q
                '[:find ?reclaimed ?branch ?commit
                  :where
                  [?result :seon.maintenance.result/id
                   "maintenance-result/collect"]
                  [?result :seon.operator.collect/reclaimed-bytes ?reclaimed]
                  [?result :seon.maintenance.result/collect-branches ?head]
                  [?head :seon.store/branch ?branch]
                  [?head :seon.source/commit-id ?commit]]
                @connection)))
        (is (not (contains? (:schema @connection)
                            :seon.operator.collect/branches))
            "the public vector-of-maps stays a contract, not a stored codec")))))

(deftest root-owned-portfolio-initializes-as-queryable-schedule-facts
  (test-support/with-database
    (fn [connection]
      (is (empty? (db/q '[:find ?task-id
                          :where [?task :seon.schedule.task/id ?task-id]]
                        @connection))
          "the config population does not own agent schedule rows")
      (test-support/transacted!
                   connection
                   (into [{:seon.agent/id "root"}]
                         (map (fn [row] (test-support/program-fn-row (:seon.fn/sym row))))
                         portfolio))
      (let [first-result
            (db/transact!
             connection [[:db.fn/call #'schedule/root-maintenance-seed-call]])]
        (is (seq (:tx-data first-result)))
        (is (= (set (map (fn [row]
                           [(:seon.schedule.task/id row)
                            (:seon.schedule/id row)
                            (:seon.schedule/expression row)
                            "UTC"
                            (:seon.fn/sym row)])
                         portfolio))
               (db/q
                '[:find ?task-id ?schedule-id ?expression ?zone-id ?handler
                  :where
                  [?task :seon.schedule.task/id ?task-id]
                  [?task :seon.schedule.task/owner ?owner]
                  [?owner :seon.agent/id "root"]
                  [?task :seon.schedule.task/function ?function]
                  [?function :seon.fn/sym ?handler]
                  [?task :seon.schedule.task/schedule ?schedule]
                  [?schedule :seon.schedule/id ?schedule-id]
                  [?schedule :seon.schedule/expression ?expression]
                  [?schedule :seon.schedule/zone-id ?zone-id]]
                @connection))))
      (test-support/transacted!
                   connection
                   [{:seon.schedule/id "root/maintenance/footprint-schedule"
                     :seon.schedule/expression "7 4 * * *"
                     :seon.schedule/zone-id "UTC"}])
      (let [second-result
            (db/transact!
             connection [[:db.fn/call #'schedule/root-maintenance-seed-call]])]
        (is (= "7 4 * * *"
               (db/q '[:find ?expression .
                       :where
                       [?schedule :seon.schedule/id
                        "root/maintenance/footprint-schedule"]
                       [?schedule :seon.schedule/expression ?expression]]
                     @connection))
            "a later ordinary cadence transaction remains authoritative")
        (is (empty? (filter #(#{:seon.schedule.task/id :seon.schedule/id}
                               (:a %))
                            (:tx-data second-result))))))))

(deftest the-cleanup-collection-slot-declares-both-arms-it-can-carry
  (let [collect-component
        (maintenance/project-collect-result
         {:seon.operator.collect/store-id
          (UUID/fromString "00000000-0000-0000-0000-000000000101")
          :seon.operator.collect/managed-root "/repo/operator"
          :seon.operator.collect/branches
          [{:seon.store/branch :cluster-default
            :seon.source/commit-id
            (UUID/fromString "00000000-0000-0000-0000-000000000102")}]
          :seon.operator.collect/objects-before 12
          :seon.operator.collect/objects-after 10
          :seon.operator.collect/swept-objects 2
          :seon.operator.collect/bytes-before 8192
          :seon.operator.collect/bytes-after 4096
          :seon.operator.collect/reclaimed-bytes 4096
          :seon.operator.collect/verification-pass-swept 0
          :seon.operator.collect/roots-verified? true
          :seon.operator.collect/complete? true})
        error-component
        {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.operator/collection
         :seon.error/operation 'seon.maintenance/collect!
         :seon.error/message "Collection did not verify every root."}]
    (testing "the maintenance mirror admits what the public slot admits"
      (doseq [[public-key component-key value]
              [[:seon.operator.collect/result
                :seon.maintenance.result/cluster-cleanup-collection-component
                collect-component]
               [:seon.error/value
                :seon.maintenance.result/cluster-cleanup-collection-component
                error-component]]]
        (is (true? ((schema/projection-validator (schema/handed-projection) component-key) value))
            (str component-key " must admit the " public-key " arm"))))
    (testing "the success arm is the one collect component, not a copy"
      (is (true? ((schema/projection-validator (schema/handed-projection) :seon.maintenance.result/collect-component) collect-component))))
    (is (false? ((schema/projection-validator (schema/handed-projection) :seon.maintenance.result/cluster-cleanup-collection-component) {:seon.maintenance-schema-test/only-unrelated true}))
        "neither arm admits a value carrying no collection evidence")))

(deftest empty-maintenance-memberships-retain-their-positive-observation
  (test-support/with-database
   (fn [connection]
     (let [observed (Date. 0)
           census {:seon.operator.process-census/observed-at observed
                   :seon.operator.process-census/roots []
                   :seon.operator.process-census/processes []
                   :seon.operator.process-census/dead []
                   :seon.operator.process-census/unresponsive []
                   :seon.operator.process-census/unclaimed []
                   :seon.operator.process-census/claim-errors []
                   :seon.operator.process-census/complete? true}
           reap {:seon.operator.reap/observed-at observed
                 :seon.operator.reap/census census
                 :seon.operator.reap/eligible-root-claims []
                 :seon.operator.reap/stopped-processes []
                 :seon.operator.reap/roots [] :seon.operator.reap/refused []
                 :seon.operator.reap/reclaimed-bytes 0 :seon.operator.reap/complete? true}
           collect {:seon.operator.collect/store-id (UUID/randomUUID)
                    :seon.operator.collect/managed-root "/unused"
                    :seon.operator.collect/branches []
                    :seon.operator.collect/objects-before 0 :seon.operator.collect/objects-after 0
                    :seon.operator.collect/swept-objects 0
                    :seon.operator.collect/bytes-before 0 :seon.operator.collect/bytes-after 0
                    :seon.operator.collect/reclaimed-bytes 0
                    :seon.operator.collect/verification-pass-swept 0
                    :seon.operator.collect/roots-verified? true
                    :seon.operator.collect/complete? true}
           cleanup {:seon.operator.cluster-cleanup/managed-root "/unused"
                    :seon.boot/cluster-name "empty" :seon.store/branch :cluster-empty
                    :seon.operator.cluster-cleanup/live-instance-stopped? false
                    :seon.operator.cluster-cleanup/branch-retired? true
                    :seon.operator.cluster-cleanup/removed []
                    :seon.operator.cluster-cleanup/remaining []
                    :seon.operator.cluster-cleanup/collection collect
                    :seon.operator.cluster-cleanup/reclaimed-bytes 0
                    :seon.operator.cluster-cleanup/complete? true}]
       (doseq [[operation-name producer value positive membership]
               [["census" maintenance/project-process-census-result census
                 :seon.operator.process-census/observed-at :seon.maintenance.result/process-census-roots]
                ["reap" maintenance/project-reap-result reap
                 :seon.operator.reap/observed-at :seon.maintenance.result/reap-roots]
                ["collect" maintenance/project-collect-result collect
                 :seon.operator.collect/store-id :seon.maintenance.result/collect-branches]
                ["cleanup" maintenance/project-cluster-cleanup-result cleanup
                 :seon.operator.cluster-cleanup/managed-root :seon.operator.cluster-cleanup/removed]]]
         (let [result-id (str "empty-maintenance/" operation-name)
               row (assoc (producer value) :seon.maintenance.result/id result-id)]
           (test-support/transacted! connection [row])
           (let [stored (db/pull @connection '[*] [:seon.maintenance.result/id result-id])]
             (is (= (get value positive) (get stored positive)))
             (is (not (contains? stored membership)))
             (is (nil? (db/pull @connection [:db/id]
                                [:seon.maintenance.result/id (str result-id "/not-observed")]))))))
       (let [partial-result (assoc cleanup
                            :seon.operator.cluster-cleanup/complete? false
                            :seon.operator.cluster-cleanup/collection
                            {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.operator/collection
         :seon.error/operation 'seon.maintenance/collect!
                             :seon.error/message "Collection did not finish."})
             row (assoc (maintenance/project-cluster-cleanup-result partial-result)
                        :seon.maintenance.result/id "empty-maintenance/partial")]
         (test-support/transacted! connection [row])
         (let [stored (db/pull @connection '[*]
                              [:seon.maintenance.result/id "empty-maintenance/partial"])]
           (is (false? (:seon.operator.cluster-cleanup/complete? stored)))
           (is (= 'seon.maintenance/collect!
                  (get-in stored [:seon.maintenance.result/cluster-cleanup-collection :seon.error/operation])))
           (is (nil? (get-in stored [:seon.maintenance.result/cluster-cleanup-collection
                                    :seon.operator.collect/store-id])))))))))
