(ns seon.maintenance-test
  (:require [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.maintenance :as maintenance]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.schema.form :as schema.form]
            [seon.test-support :as test-support])
  (:import [java.time Instant]
           [java.util Date UUID]))

(defn- instant
  [text]
  (Date/from (Instant/parse text)))

(def ^:private at-1 (instant "2026-08-05T12:34:00Z"))
(def ^:private at-2 (instant "2026-08-05T12:35:00Z"))

(defn- process-identity
  [pid generation]
  {:seon.dev.process/generation generation
   :seon.dev.process/pid pid
   :seon.dev.process/start-instant "2026-08-05T12:00:00Z"
   :seon.dev.process/root "/repo/operator"})

(defn- census-result
  []
  (let [generation (UUID/fromString "11111111-1111-1111-1111-111111111111")]
    {:seon.operator.process-census/observed-at at-1
     :seon.operator.process-census/roots
     [{:seon.operator.claim/id
       (UUID/fromString "22222222-2222-2222-2222-222222222222")
       :seon.operator.claim/root "/repo/operator"
       :seon.operator.claim/creator
       {:seon.dev.process/pid 40
        :seon.dev.process/start-instant "2026-08-05T11:59:00Z"}
       :seon.operator.claim/reap-on-owner-exit? true}]
     :seon.operator.process-census/processes
     [(assoc (process-identity 41 generation)
             :seon.operator.process-census/alive? true
             :seon.operator.process-census/responsive? false
             :seon.operator.process-census/advertisements ["default"])]
     :seon.operator.process-census/dead []
     :seon.operator.process-census/unresponsive
     [(process-identity 41 generation)]
     :seon.operator.process-census/unclaimed
     [(process-identity 42 generation)]
     :seon.operator.process-census/claim-errors
     [{:seon.error/kind :seon.operator/unreadable-claim
       :seon.error/message "Unreadable claim."
       :seon.error/data {:seon.operator.claim/path "claim.edn"}}]
     :seon.operator.process-census/complete? false}))

(deftest result-projection-is-declared-and-keeps-census-evidence-queryable
  (let [public-result (census-result)
        projected (maintenance/result-entity public-result)]
    (testing "the public vector-of-map contract keeps its meaning"
      (is (true? (schema/valid-candidate-value?
                  :seon.operator.process-census/result public-result)))
      (is (= 'seon.maintenance/project-process-census-result
             (:seon.maintenance/result-projection
              (schema.form/schema-properties
               (schema/schema-definition
                :seon.operator.process-census/result))))))
    (testing "the declared producer yields component-ref attributes"
      (is (= :db.type/ref
             (:db/valueType
              (schema.datahike/malli->datahike-attr
               :seon.maintenance.result/process-census-processes))))
      (is (true?
           (:db/isComponent
            (schema.datahike/malli->datahike-attr
             :seon.maintenance.result/process-census-processes)))))
    (test-support/with-database
      (fn [connection]
        (db/transact!
         connection
         [(assoc projected
                 :seon.maintenance.result/id "census-result/1")])
        (is (= #{[41 false "default"]}
               (db/q
                '[:find ?pid ?responsive ?advertisement
                  :where
                  [?result :seon.maintenance.result/id "census-result/1"]
                  [?result
                   :seon.maintenance.result/process-census-processes
                   ?process]
                  [?process :seon.dev.process/pid ?pid]
                  [?process :seon.operator.process-census/responsive?
                   ?responsive]
                  [?process :seon.operator.process-census/advertisements
                   ?advertisement]]
                @connection)))
        (is (= #{[42 "/repo/operator"]}
               (db/q
                '[:find ?pid ?root
                  :where
                  [?result :seon.maintenance.result/id "census-result/1"]
                  [?result
                   :seon.maintenance.result/process-census-unclaimed
                   ?process]
                  [?process :seon.dev.process/pid ?pid]
                  [?process :seon.dev.process/root ?root]]
                @connection)))
        (is (= #{["claim.edn" "Unreadable claim."]}
               (db/q
                '[:find ?path ?message
                  :where
                  [?result :seon.maintenance.result/id "census-result/1"]
                  [?result
                   :seon.maintenance.result/process-census-claim-errors
                   ?error]
                  [?error :seon.operator.claim/path ?path]
                  [?error :seon.error/message ?message]]
                @connection)))))))

(deftest reap-projection-keeps-stop-and-refusal-evidence-queryable
  (let [claim-id (UUID/fromString "33333333-3333-3333-3333-333333333333")
        generation
        (UUID/fromString "44444444-4444-4444-4444-444444444444")
        census (assoc (census-result)
                      :seon.operator.process-census/complete? true
                      :seon.operator.process-census/unresponsive []
                      :seon.operator.process-census/unclaimed []
                      :seon.operator.process-census/claim-errors [])
        public-result
        {:seon.operator.reap/observed-at at-1
         :seon.operator.reap/census census
         :seon.operator.reap/eligible-root-claims [claim-id]
         :seon.operator.reap/stopped-processes
         [{:seon.dev.process/generation generation
           :seon.dev.process/pid 51
           :seon.dev.process/start-instant "2026-08-05T12:00:00Z"
           :seon.operator.reap/stop-path :prepl}]
         :seon.operator.reap/roots []
         :seon.operator.reap/refused
         [{:seon.operator.claim/id claim-id
           :seon.operator.reap/reason :seon.operator.reap/missing-evidence
           :seon.error/message "The exact process claim is absent."}]
         :seon.operator.reap/reclaimed-bytes 0
         :seon.operator.reap/complete? false}
        projected (maintenance/result-entity public-result)]
    (is (true? (schema/valid-candidate-value?
                :seon.operator.reap/result public-result)))
    (test-support/with-database
      (fn [connection]
        (db/transact!
         connection
         [(assoc projected :seon.maintenance.result/id "reap-result/1")])
        (is (= #{[51 :prepl]}
               (db/q
                '[:find ?pid ?stop-path
                  :where
                  [?result :seon.maintenance.result/id "reap-result/1"]
                  [?result
                   :seon.maintenance.result/reap-stopped-processes
                   ?process]
                  [?process :seon.dev.process/pid ?pid]
                  [?process :seon.operator.reap/stop-path ?stop-path]]
                @connection)))
        (is (= #{[claim-id :seon.operator.reap/missing-evidence]}
               (db/q
                '[:find ?claim ?reason
                  :where
                  [?result :seon.maintenance.result/id "reap-result/1"]
                  [?result :seon.maintenance.result/reap-refused ?refusal]
                  [?refusal :seon.operator.claim/id ?claim]
                  [?refusal :seon.operator.reap/reason ?reason]]
                @connection)))))))

(deftest cluster-cleanup-projection-keeps-result-evidence-queryable
  (let [public-result
        {:seon.operator.cluster-cleanup/managed-root "/repo/operator"
         :seon.boot/cluster-name "retired"
         :seon.store/branch :cluster/retired
         :seon.operator.cluster-cleanup/live-instance-stopped? true
         :seon.operator.cluster-cleanup/branch-retired? true
         :seon.operator.cluster-cleanup/removed
         ["/repo/operator/data/clusters/retired"]
         :seon.operator.cluster-cleanup/collection
         {:seon.error/kind :seon.error/not-yet
          :seon.error/message "Public collection evidence lands in Unit 6."
          :seon.error/data {:seon.cluster.registry/swept :opaque}}
         :seon.operator.cluster-cleanup/remaining []
         :seon.operator.cluster-cleanup/reclaimed-bytes 8192
         :seon.operator.cluster-cleanup/complete? true}
        projected (maintenance/result-entity public-result)]
    (is (true? (schema/valid-candidate-value?
                :seon.operator.cluster-cleanup/result public-result)))
    (test-support/with-database
      (fn [connection]
        (db/transact!
         connection
         [(assoc projected :seon.maintenance.result/id "cleanup-result/1")])
        (is (= #{["retired" :cluster/retired 8192 true]}
               (db/q
                '[:find ?cluster ?branch ?bytes ?complete
                  :where
                  [?result :seon.maintenance.result/id "cleanup-result/1"]
                  [?result :seon.boot/cluster-name ?cluster]
                  [?result :seon.store/branch ?branch]
                  [?result
                   :seon.operator.cluster-cleanup/reclaimed-bytes ?bytes]
                  [?result :seon.operator.cluster-cleanup/complete? ?complete]]
                @connection)))
        (is (= #{[:seon.error/not-yet
                  "Public collection evidence lands in Unit 6."]}
               (db/q
                '[:find ?kind ?message
                  :where
                  [?result :seon.maintenance.result/id "cleanup-result/1"]
                  [?result
                   :seon.maintenance.result/cluster-cleanup-collection
                   ?collection]
                  [?collection :seon.error/kind ?kind]
                  [?collection :seon.error/message ?message]]
                @connection)))))))

(defn- task-transaction
  [task-id handler]
  [{:seon.fn/sym handler}
   {:seon.schedule/id (str task-id "/schedule")
    :seon.schedule/expression "0 2 * * *"
    :seon.schedule/zone-id "UTC"}
   {:seon.schedule.task/id task-id
    :seon.schedule.task/owner [:seon.cluster.agent/id "root"]
    :seon.schedule.task/function [:seon.fn/sym handler]
    :seon.schedule.task/schedule
    [:seon.schedule/id (str task-id "/schedule")]}])

(defn- receipt
  [task-id handler receipt-id started-at terminal]
  [{:seon.schedule.fire/id (str receipt-id "/fire")
    :seon.schedule.fire/task [:seon.schedule.task/id task-id]
    :seon.schedule.fire/nominal-at started-at
    :seon.schedule.fire/observed-at started-at}
   (merge
    {:seon.maintenance.receipt/id receipt-id
     :seon.maintenance.receipt/fire
     [:seon.schedule.fire/id (str receipt-id "/fire")]
     :seon.maintenance.receipt/task [:seon.schedule.task/id task-id]
     :seon.maintenance.receipt/handler [:seon.fn/sym handler]
     :seon.maintenance.receipt/request
     {:seon.maintenance.request/id (str receipt-id "/request")
      :seon.maintenance.request/task [:seon.schedule.task/id task-id]
      :seon.maintenance.request/fire
      [:seon.schedule.fire/id (str receipt-id "/fire")]
      :seon.maintenance.request/handler [:seon.fn/sym handler]
      :seon.maintenance.request/agent [:seon.cluster.agent/id "root"]
      :seon.maintenance.request/cluster-name "default"
      :seon.maintenance.request/repository-root "/repo"
      :seon.maintenance.request/managed-root "/repo/operator"
      :seon.maintenance.request/log-dir "/repo/operator/logs"
      :seon.maintenance.request/nominal-at started-at
      :seon.maintenance.request/observed-at started-at}
     :seon.maintenance.receipt/started-at started-at}
    terminal)])

(defn- seed-report!
  [connection]
  (let [footprint-task "root/maintenance/footprint"
        footprint-handler "seon.operator/observe-footprint!"
        census-task "root/maintenance/process-census"
        census-handler "seon.operator/census-processes!"]
    (db/transact!
     connection
     (into [{:seon.cluster.agent/id "root"}]
           cat
           [(task-transaction footprint-task footprint-handler)
            (task-transaction census-task census-handler)]))
    {:footprint-task footprint-task
     :footprint-handler footprint-handler
     :census-task census-task
     :census-handler census-handler}))

(deftest report-renders-no-run-green-and-latest-red-facts
  (test-support/with-database
    (fn [connection]
      (let [{:keys [footprint-task footprint-handler
                    census-task census-handler]}
            (seed-report! connection)]
        (testing "declared tasks with no receipts have the sealed empty face"
          (is (= "Maintenance: no task has run yet."
                 (maintenance/render-report-ai
                  (maintenance/report @connection)))))
        (db/transact!
         connection
         (into [] cat
          [(receipt
           footprint-task footprint-handler "footprint/1" at-1
           {:seon.maintenance.receipt/completed-at at-1
            :seon.maintenance.receipt/result
            {:seon.maintenance.result/id "footprint-result/1"
             :seon.operator.footprint/root "/repo/operator"
             :seon.operator.footprint/file-bytes 4096
             :seon.operator.footprint/usable-bytes 10737418240
             :seon.operator.footprint/total-bytes 21474836480
             :seon.operator.footprint/usable-ratio 0.5
             :seon.operator.footprint/observed-at at-1
             :seon.operator/low-space? false}})
          (receipt
           census-task census-handler "census/1" at-1
           {:seon.maintenance.receipt/completed-at at-1
            :seon.maintenance.receipt/result
            (assoc (maintenance/result-entity
                    (assoc (census-result)
                           :seon.operator.process-census/complete? true
                           :seon.operator.process-census/unresponsive []
                           :seon.operator.process-census/unclaimed []
                           :seon.operator.process-census/claim-errors []))
                   :seon.maintenance.result/id "census-result/2")})]))
        (testing "all latest receipts render one green line"
          (let [report-value (maintenance/report @connection)]
            (is (= "Maintenance: 2 tasks succeeded; latest 2026-08-05T12:34:00Z; 0 errors."
                   (maintenance/render-report-ai report-value)))
            (is (= :article (first (maintenance/render-report-html
                                    report-value))))))
        (db/transact!
         connection
         (receipt census-task census-handler "census/2" at-2
                  {:seon.maintenance.receipt/interrupted-at at-2}))
        (testing "only the latest receipt per task determines the red face"
          (let [report-value (maintenance/report @connection)
                rendered (maintenance/render-report-ai report-value)]
            (is (= (str "Maintenance: 1 succeeded; 1 need attention.\n"
                        "census-processes!: receipt census/2 was interrupted.")
                   rendered))
            (is (= ["footprint/1" "census/2"]
                   (mapv #(get-in % [:seon.maintenance/receipt-facts
                                     :seon.maintenance.receipt/id])
                         (:seon.maintenance/entries report-value))))))))))
