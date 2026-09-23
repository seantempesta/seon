(ns seon.maintenance-test
  (:require [clojure.java.io :as io]
            [malli.registry :as mr]
            [seon.schema.internal] [malli.core] [clojure.test :refer [deftest is testing]]
            [seon.db :as db]
            [seon.maintenance :as maintenance]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
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



(deftest collection-results-project-branch-maps-to-components
  (let [store-id (UUID/fromString "00000000-0000-0000-0000-000000000201")
        commit-id (UUID/fromString "00000000-0000-0000-0000-000000000202")
        projected
        (maintenance/result-entity (schema/handed-projection)
         {:seon.operator.collect/store-id store-id
          :seon.operator.collect/managed-root "/repo/operator"
          :seon.operator.collect/branches
          [{:seon.store/branch :cluster-default
            :seon.source/commit-id commit-id}]
          :seon.operator.collect/objects-before 8
          :seon.operator.collect/objects-after 7
          :seon.operator.collect/swept-objects 1
          :seon.operator.collect/bytes-before 2048
          :seon.operator.collect/bytes-after 1024
          :seon.operator.collect/reclaimed-bytes 1024
          :seon.operator.collect/verification-pass-swept 0
          :seon.operator.collect/roots-verified? true
          :seon.operator.collect/complete? true})]
    (is (= [{:seon.store/branch :cluster-default
             :seon.source/commit-id commit-id}]
           (:seon.maintenance.result/collect-branches projected)))
    (is (= 1024 (:seon.operator.collect/reclaimed-bytes projected)))
    (is (not (contains? projected :seon.operator.collect/branches)))))


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
         {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.operator/collection
         :seon.error/operation 'seon.maintenance/collect!
          :seon.error/message "Public collection evidence lands in Unit 6."
          :seon.error/data {:seon.cluster.registry/swept :opaque}}
         :seon.operator.cluster-cleanup/remaining []
         :seon.operator.cluster-cleanup/reclaimed-bytes 8192
         :seon.operator.cluster-cleanup/complete? true}
        projected (maintenance/result-entity (schema/handed-projection) public-result)]
    (is (true? ((schema/projection-validator (schema/handed-projection) :seon.operator.cluster-cleanup/result) public-result)))
    (test-support/with-database
      (fn [connection]
        (test-support/transacted!
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
        (is (= #{['seon.maintenance/collect!
                  "Public collection evidence lands in Unit 6."]}
               (db/q
                '[:find ?operation ?message
                  :where
                  [?result :seon.maintenance.result/id "cleanup-result/1"]
                  [?result
                   :seon.maintenance.result/cluster-cleanup-collection
                   ?collection]
                  [?collection :seon.error/operation ?operation]
                  [?collection :seon.error/message ?message]]
                @connection)))))))

(defn- task-transaction
  [task-id handler]
  [(test-support/program-fn-row handler)
   {:seon.schedule/id (str task-id "/schedule")
    :seon.schedule/expression "0 2 * * *"
    :seon.schedule/zone-id "UTC"}
   {:seon.schedule.task/id task-id
    :seon.schedule.task/owner [:seon.agent/id "root"]
    :seon.schedule.task/function [:seon.fn/sym handler]
    :seon.schedule.task/schedule
    [:seon.schedule/id (str task-id "/schedule")]}])

(defn- receipt
  [task-id handler receipt-id started-at terminal]
  [{:seon.schedule.fire/id (str receipt-id "/fire")
    :seon.schedule.fire/task [:seon.schedule.task/id task-id]
    ;; A firing carries its owner: the wake router needs no query.
    :seon.schedule.fire/agent [:seon.agent/id "root"]
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
      :seon.maintenance.request/agent [:seon.agent/id "root"]
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
        footprint-handler 'seon.maintenance/observe-footprint!
        census-task "root/maintenance/process-census"
        census-handler 'seon.operator/census-processes!]
    (test-support/transacted!
                 connection
                 (into [{:seon.agent/id "root"}]
                       cat
                       [(task-transaction footprint-task footprint-handler)
                        (task-transaction census-task census-handler)]))
    {:footprint-task footprint-task
     :footprint-handler footprint-handler
     :census-task census-task
     :census-handler census-handler}))


(def ^:private collected-root "/repo/operator")

(defn- collect-result
  [reclaimed]
  {:seon.operator.collect/store-id
   (UUID/fromString "00000000-0000-0000-0000-000000000101")
   :seon.operator.collect/managed-root collected-root
   :seon.operator.collect/branches
   [{:seon.store/branch :cluster-retired
     :seon.source/commit-id
     (UUID/fromString "00000000-0000-0000-0000-000000000102")}]
   :seon.operator.collect/objects-before 12
   :seon.operator.collect/objects-after 10
   :seon.operator.collect/swept-objects 2
   :seon.operator.collect/bytes-before 8192
   :seon.operator.collect/bytes-after (- 8192 reclaimed)
   :seon.operator.collect/reclaimed-bytes reclaimed
   :seon.operator.collect/verification-pass-swept 0
   :seon.operator.collect/roots-verified? true
   :seon.operator.collect/complete? true})

(defn- cleanup-result
  [collection]
  {:seon.operator.cluster-cleanup/managed-root collected-root
   :seon.boot/cluster-name "retired"
   :seon.store/branch :cluster-retired
   :seon.operator.cluster-cleanup/live-instance-stopped? true
   :seon.operator.cluster-cleanup/branch-retired? true
   :seon.operator.cluster-cleanup/removed
   ["/repo/operator/data/clusters/retired"]
   :seon.operator.cluster-cleanup/collection collection
   :seon.operator.cluster-cleanup/remaining []
   :seon.operator.cluster-cleanup/reclaimed-bytes 8192
   :seon.operator.cluster-cleanup/complete? true})

(deftest a-successful-cleanup-persists-its-verified-collection-result
  (let [public-result (cleanup-result (collect-result 4096))
        projected (maintenance/result-entity (schema/handed-projection) public-result)]
    (is (true? ((schema/projection-validator (schema/handed-projection) :seon.operator.cluster-cleanup/result) public-result))
        "collect-store! returns its verified result, so the slot carries one")
    (is (= 4096
           (get-in projected
                   [:seon.maintenance.result/cluster-cleanup-collection
                    :seon.operator.collect/reclaimed-bytes]))
        "the projected collection is the collect result, not an empty map")
    (test-support/with-database
      (fn [connection]
        (test-support/transacted!
         connection
         [(assoc projected
                 :seon.maintenance.result/id "cleanup-result/collected")])
        (testing "a SUCCESSFUL collection is a queryable maintenance fact"
          (is (= #{[4096 2 true]}
                 (db/q
                  '[:find ?reclaimed ?swept ?complete
                    :where
                    [?result :seon.maintenance.result/id
                     "cleanup-result/collected"]
                    [?result
                     :seon.maintenance.result/cluster-cleanup-collection
                     ?collection]
                    [?collection :seon.operator.collect/reclaimed-bytes
                     ?reclaimed]
                    [?collection :seon.operator.collect/swept-objects ?swept]
                    [?collection :seon.operator.collect/complete? ?complete]]
                  @connection))))
        (testing "the collection's retained roster rides the same component"
          (is (= #{[:cluster-retired
                    (UUID/fromString
                     "00000000-0000-0000-0000-000000000102")]}
                 (db/q
                  '[:find ?branch ?commit
                    :where
                    [?result :seon.maintenance.result/id
                     "cleanup-result/collected"]
                    [?result
                     :seon.maintenance.result/cluster-cleanup-collection
                     ?collection]
                    [?collection :seon.maintenance.result/collect-branches
                     ?head]
                    [?head :seon.store/branch ?branch]
                    [?head :seon.source/commit-id ?commit]]
                  @connection))))))))

(deftest a-refused-collection-keeps-its-typed-error-on-the-same-slot
  (let [refusal {:seon.error/at (java.util.Date.)
         :seon.error/layer :seon.operator/collection
         :seon.error/operation 'seon.maintenance/collect!
                 :seon.error/message "Collection did not verify every root."
                 :seon.error/data {:seon.cluster.registry/swept :opaque}}
        projected (maintenance/result-entity (schema/handed-projection) (cleanup-result refusal))]
    (test-support/with-database
      (fn [connection]
        (test-support/transacted!
         connection
         [(assoc projected
                 :seon.maintenance.result/id "cleanup-result/refused")])
        (is (= #{['seon.maintenance/collect!
                  "Collection did not verify every root."]}
               (db/q
                '[:find ?operation ?message
                  :where
                  [?result :seon.maintenance.result/id
                   "cleanup-result/refused"]
                  [?result
                   :seon.maintenance.result/cluster-cleanup-collection
                   ?collection]
                  [?collection :seon.error/operation ?operation]
                  [?collection :seon.error/message ?message]]
                @connection))
            "one projection writes both arms; the error arm is unchanged")
        (is (empty?
             (db/q '[:find ?reclaimed
                     :where
                     [?result :seon.maintenance.result/id
                      "cleanup-result/refused"]
                     [?result
                      :seon.maintenance.result/cluster-cleanup-collection
                      ?collection]
                     [?collection :seon.operator.collect/reclaimed-bytes
                      ?reclaimed]]
                   @connection))
            "a refused collection reclaims nothing and claims nothing")))))

(deftest re-recording-one-maintenance-run-replaces-its-attributes
  (test-support/with-database
    (fn [connection]
      (let [result-id "cleanup-result/rerun"
            record!
            (fn [reclaimed]
              (test-support/transacted!
               connection
               [(assoc (maintenance/result-entity (schema/handed-projection)
                        (cleanup-result (collect-result reclaimed)))
                       :seon.maintenance.result/id result-id)]))]
        (record! 4096)
        (record! 8192)
        (is (= 1 (count (db/q '[:find ?result
                                :where
                                [?result :seon.maintenance.result/id
                                 "cleanup-result/rerun"]]
                              @connection)))
            "one maintenance entity per run identity, never one per event")
        (is (= #{[8192]}
               (db/q '[:find ?reclaimed
                       :where
                       [?result :seon.maintenance.result/id
                        "cleanup-result/rerun"]
                       [?result
                        :seon.maintenance.result/cluster-cleanup-collection
                        ?collection]
                       [?collection :seon.operator.collect/reclaimed-bytes
                        ?reclaimed]]
                     @connection))
            "the re-run replaces the attributes it re-observes")))))

(deftest last-collection-answers-when-a-root-was-collected-and-what-it-reclaimed
  (test-support/with-database
    (fn [connection]
      (let [collect-task "root/maintenance/compact"
            collect-handler 'seon.maintenance/collect!
            cleanup-task "root/maintenance/cleanup"
            cleanup-handler 'seon.operator/cleanup-cluster!]
        (test-support/transacted!
         connection
         (into [{:seon.agent/id "root"}]
               cat
               [(task-transaction collect-task collect-handler)
                (task-transaction cleanup-task cleanup-handler)]))
        (testing "an uncollected root is the typed unknown, never absence"
          ;; The refusal retains the producer observation in
          ;; `:seon.error/data`; the top level carries the observed root and base evidence.
          (let [answer (maintenance/last-collection @connection collected-root)]
            (is (= collected-root (:seon.maintenance/uncollected-root answer)))
            (is (= collected-root
                   (get-in answer [:seon.error/offending])))
            (is (= :seon.operator/managed-root
                   (get-in answer [:seon.error/data
                                   :seon.error/member])))))
        (test-support/transacted!
         connection
         (receipt collect-task collect-handler "collect/1" at-1
                  {:seon.maintenance.receipt/completed-at at-1
                   :seon.maintenance.receipt/result
                   (assoc (maintenance/result-entity (schema/handed-projection) (collect-result 4096))
                          :seon.maintenance.result/id "collect-result/1")}))
        (testing "a collect! receipt answers from its own result entity"
          (let [answer (maintenance/last-collection @connection collected-root)]
            (is (= {:seon.operator/managed-root collected-root
                    :seon.maintenance.receipt/id "collect/1"
                    :seon.maintenance.receipt/completed-at at-1
                    :seon.operator.collect/reclaimed-bytes 4096}
                   (select-keys answer
                                [:seon.operator/managed-root
                                 :seon.maintenance.receipt/id
                                 :seon.maintenance.receipt/completed-at
                                 :seon.operator.collect/reclaimed-bytes])))
            (is (true? ((schema/projection-validator (schema/handed-projection) :seon.maintenance/collection-record) answer)))))
        (test-support/transacted!
         connection
         (receipt cleanup-task cleanup-handler "cleanup/1" at-2
                  {:seon.maintenance.receipt/completed-at at-2
                   :seon.maintenance.receipt/result
                   (assoc (maintenance/result-entity (schema/handed-projection)
                           (cleanup-result (collect-result 8192)))
                          :seon.maintenance.result/id "cleanup-result/2")}))
        (testing "a later cleanup's collection is the root's latest answer"
          (let [answer (maintenance/last-collection @connection collected-root)]
            (is (= "cleanup/1" (:seon.maintenance.receipt/id answer)))
            (is (= at-2 (:seon.maintenance.receipt/completed-at answer)))
            (is (= 8192
                   (:seon.operator.collect/reclaimed-bytes answer)))))))))

(defn- owned-root
  []
  (let [root (str "tmp/operator-test/" (random-uuid))]
    (.mkdirs (io/file root))
    (.getCanonicalPath (io/file root))))


(deftest live-log-inode-is-bounded-and-archived
  (let [root (owned-root)
        log-dir (io/file root "logs")
        log-file (io/file log-dir "seon.log")]
    (try
      (.mkdirs log-dir)
      (spit log-file (apply str (repeat 32 "x")))
      (let [result (maintenance/rotate-logs!
                    {:seon.boot/log-dir (.getCanonicalPath log-dir)
                     :seon.config.maintenance/log-max-bytes 16
                     :seon.config.maintenance/log-retained-files 1})]
        (is (true? (:seon.operator.log/rotated? result)))
        (is (zero? (.length log-file)))
        (is (= 32 (.length (io/file (str (.getCanonicalPath log-file) ".1"))))))
      (finally
        (test-support/delete-recursively! root)))))


(deftest collection-reports-and-verifies-the-exact-store
  (let [repository-root (owned-root)
        managed-root (.getCanonicalPath
                      (io/file repository-root "managed"))]
    (try
      (let [result
            (maintenance/collect!
             {:seon.operator/repository-root repository-root
              :seon.operator/managed-root managed-root
              :seon.config.maintenance/collect? true})]
        (is (uuid? (:seon.operator.collect/store-id result)))
        (is (= managed-root
               (:seon.operator.collect/managed-root result)))
        (is (= [:db]
               (mapv :seon.store/branch
                     (:seon.operator.collect/branches result))))
        (is (every? uuid?
                    (map :seon.source/commit-id
                         (:seon.operator.collect/branches result))))
        (is (<= (:seon.operator.collect/objects-after result)
                (:seon.operator.collect/objects-before result)))
        (is (<= (:seon.operator.collect/bytes-after result)
                (:seon.operator.collect/bytes-before result)))
        (is (zero?
             (:seon.operator.collect/verification-pass-swept result)))
        (is (true? (:seon.operator.collect/complete? result))))
      (finally
        (test-support/delete-recursively! repository-root)))))
