(ns seon.maintenance-test
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [clojure.string :as str]
            [malli.registry :as mr]
            [seon.schema.internal] [malli.core] [clojure.test :refer [deftest is testing]]
            [seon.config :as config]
            [seon.db :as db]
            [konserve.core :as k]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.maintenance :as maintenance]
            [seon.operator.runtime :as operator.runtime]
            [seon.schema :as schema]
            [seon.schema.datahike :as schema.datahike]
            [seon.test-support :as test-support])
  (:import [java.time Instant]
           [java.util Date UUID]))

(defn- instant
  [text]
  (Date/from (Instant/parse text)))

(def ^:private at-1 (instant "2026-08-05T12:34:00Z"))

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
  [(test-support/program-fn-entity handler)
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
        (testing "the collection's retained branches ride the same component"
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
            collect-handler 'seon.maintenance/collect!]
        (test-support/transacted!
         connection
         (into (test-support/agent-tx @connection "root")
               (task-transaction collect-task collect-handler)))
        (testing "an uncollected root is the typed unknown, never absence"
          ;; A flat refusal: the observed root and the refused member are top-level.
          (let [answer (maintenance/last-collection @connection collected-root)]
            (is (= collected-root (:seon.maintenance/uncollected-root answer)))
            (is (= collected-root
                   (get-in answer [:seon.error/offending])))
            (is (= :seon.operator/managed-root
                   (:seon.error/member answer)))))
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
            (is (true? ((schema/projection-validator (schema/handed-projection) :seon.maintenance/collection-record) answer)))))))))

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


;; PrintStream writes hold the stream's own monitor (JDK PrintStream.java,
;; `synchronized (this)`); these writers take the same monitors so the test
;; exercises System/out and System/err exclusion without replacing either.
(deftest rotation-keeps-every-line-written-during-it
  (let [root (owned-root)
        log-dir (io/file root "logs")
        log-file (io/file log-dir "seon.log")
        stop? (atom false)
        written (atom 0)]
    (try
      (.mkdirs log-dir)
      (spit log-file "")
      (with-open [out (java.io.FileOutputStream. log-file true)]
        (let [writer (fn [stream]
                       (doto (Thread.
                              #(while (not @stop?)
                                 (locking stream
                                   (.write out (.getBytes
                                                (str (swap! written inc) "\n"))))))
                         .start))
              threads (mapv writer [System/out System/err])
              results (try
                        (doall (for [_ (range 20)]
                                 (do (Thread/sleep 5)
                                     (maintenance/rotate-logs!
                                      {:seon.boot/log-dir (.getCanonicalPath log-dir)
                                       :seon.config.maintenance/log-max-bytes 1
                                       :seon.config.maintenance/log-retained-files 30}))))
                        (finally
                          (reset! stop? true)
                          (run! #(.join ^Thread %) threads)))
              files (filter #(.isFile ^java.io.File %)
                            (cons log-file
                                  (map #(io/file (str (.getCanonicalPath log-file) "." %))
                                       (range 1 31))))
              lines (mapcat #(str/split-lines (slurp %)) files)]
          (is (= 20 (count (filter :seon.operator.log/rotated? results))))
          (is (< 1000 @written))
          (is (= (range 1 (inc @written)) (sort (map parse-long lines))))))
      (finally
        (test-support/delete-recursively! root)))))


(defn- declare-cluster-config!
  "Give the root's store the one cluster configuration fact `collect!` reads:
  `retention-cutoff` refuses a store whose clusters declare none, and an empty
  root holds no cluster. One argument carries the SHIPPED default snapshot
  window; two carry the window given."
  {:malli/schema [:function [:=> [:cat :string] :any] [:=> [:cat :string :int] :any]]}
  ([managed-root]
   (let [window (get-in (config/compile-manifest {:seon.boot/cluster-name "collection-probe"})
                        [:seon.config/effective :seon.config.db/snapshot-window-ms])]
     (is (pos-int? window) "the shipped default declares a snapshot window")
     (declare-cluster-config! managed-root window)))
  ([managed-root window]
   (#'maintenance/with-root-store!
    managed-root
    (fn [held]
      (d/transact (:seon.store/connection-object held)
                  {:tx-data [{:db/ident :seon.config/cluster
                              :db/valueType :db.type/string
                              :db/cardinality :db.cardinality/one}
                             {:db/ident :seon.config.db/snapshot-window-ms
                              :db/valueType :db.type/long
                              :db/cardinality :db.cardinality/one}
                             {:seon.config/cluster "collection-probe"
                              :seon.config.db/snapshot-window-ms window}]})))))

(defn- collect-with-retention
  "Collect a fresh root store whose snapshot window is zero, after `:db`
  committed an old commit and then newer ones and a branch committed and was
  retired, with the request's retention dial as given. Returns whether each
  commit is still in the store."
  {:malli/schema [:=> [:cat [:maybe :boolean]] [:map-of :keyword :boolean]]}
  [retention?]
  (let [repository-root (owned-root)
        managed-root (.getCanonicalPath (io/file repository-root "managed"))]
    (try
      (declare-cluster-config! managed-root 0)
      (let [[old-commit doomed-commit]
            (#'maintenance/with-root-store!
             managed-root
             (fn [held]
               (let [connection (:seon.store/connection-object held)
                     marker! #(d/transact connection {:tx-data [{:db/ident %}]})
                     _ (d/transact connection {:tx-data [{:db/ident ::marker
                                                          :db/valueType :db.type/string
                                                          :db/cardinality :db.cardinality/one}]})
                     old (d/commit-id @connection)
                     _ (Thread/sleep 50)
                     _ (marker! ::newer-1)
                     _ (Thread/sleep 50)
                     _ (marker! ::newer-2)
                     _ (registry/branch! {:seon.store/store held
                                          :seon.cluster.registry/from :db
                                          :seon.store/branch :doomed})
                     doomed-connection (store/open-branch! held :doomed)
                     _ (d/transact doomed-connection {:tx-data [{:db/ident ::doomed-marker}]})
                     doomed (d/commit-id @doomed-connection)]
                 (d/release doomed-connection)
                 (registry/retire-branch! {:seon.store/store held :seon.store/branch :doomed})
                 [old doomed])))]
        (maintenance/collect!
         (cond-> {:seon.operator/repository-root repository-root
                  :seon.operator/managed-root managed-root
                  :seon.config.maintenance/collect? true}
           (some? retention?) (assoc :seon.config.maintenance/retention? retention?)))
        (#'maintenance/with-root-store!
         managed-root
         (fn [held]
           (let [konserve (:store @(:seon.store/connection-object held))
                 present? #(boolean (k/exists? konserve % {:sync? true}))]
             {:old-commit (present? old-commit)
              :deleted-branch-commit (present? doomed-commit)}))))
      (finally
        (test-support/delete-recursively! repository-root)))))

(deftest collection-keeps-history-unless-retention-is-on
  (is (= {:old-commit true :deleted-branch-commit false}
         (collect-with-retention false))
      "retention off keeps a live branch's old commit and sweeps a deleted branch")
  (is (= {:old-commit true :deleted-branch-commit false}
         (collect-with-retention nil))
      "an absent dial is off")
  (is (= {:old-commit false :deleted-branch-commit false}
         (collect-with-retention true))
      "retention on prunes the old commit by the snapshot window"))

(deftest collection-reports-and-verifies-the-exact-store
  (let [repository-root (owned-root)
        managed-root (.getCanonicalPath
                      (io/file repository-root "managed"))]
    (try
      (declare-cluster-config! managed-root)
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

(deftest a-branch-that-commits-after-its-root-was-recorded-still-verifies
  ;; default's 2026-09-27 collection (receipt aa5e1e02c6dc) swept, then refused
  ;; its own result: `:cluster-default` committed between recording its root
  ;; and verifying it, and the check compared the moving head.
  (let [repository-root (owned-root)
        managed-root (.getCanonicalPath (io/file repository-root "managed"))]
    (try
      (#'maintenance/with-root-store!
       managed-root
       (fn [held]
         (let [connection (:seon.store/connection-object held)
               evidence (#'maintenance/collection-evidence held)
               held-before (#'maintenance/konserve-key-set held)
               recorded (:seon.source/commit-id
                         (first (:seon.operator.collect/branches evidence)))]
           (d/transact connection {:tx-data [{:db/ident ::marker
                                              :db/valueType :db.type/string
                                              :db/cardinality :db.cardinality/one}]})
           (is (not= recorded (d/commit-id @connection)) "the head moved on")
           (is (= {:seon.operator.collect/roots-verified? true
                   :seon.operator.collect/unstored-digests 0}
                  (#'maintenance/root-verification held evidence held-before))))))
      (finally
        (test-support/delete-recursively! repository-root)))))

(deftest maintenance-holds-the-cluster-store-through-its-owner-count
  ;; A cluster in this JVM holds the operator root's store; maintenance
  ;; takes one more hold on that same value and gives only that one back.
  (let [root (store/declared-operator-root)
        path (.getCanonicalPath (io/file root "data" "store"))
        holders #(get-in @operator.runtime/root-store-holder
                         [path :seon.cluster/holders])
        shared (get-in @operator.runtime/root-store-holder
                       [path :seon.store/store])
        before (holders)
        [inside same?] (#'maintenance/with-root-store!
                        root (fn [held] [(holders) (identical? held shared)]))]
    (is (pos-int? before) "a running cluster holds the operator root's store")
    (is (= (inc before) inside))
    (is (true? same?))
    (is (= before (holders)))
    (is (.isValid ^java.nio.channels.FileLock (:seon.store/lock shared)))))
