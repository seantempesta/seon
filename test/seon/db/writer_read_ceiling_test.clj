(ns seon.db.writer-read-ceiling-test
  "Writer-enforced read ceiling and deadline tests."
  (:require [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer])
  (:import [java.io File]
           [java.nio.channels SocketChannel]))

(def ^:private entity-count 1500)

(defn- socket-path
  [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory
            (str "wrc-" label "-" (subs (str (random-uuid)) 0 8) ".sock")))))

(defn- seed-database!
  [connection _database-name]
  (d/transact
   connection
   [{:db/ident :writer-read/id
     :db/valueType :db.type/string
     :db/cardinality :db.cardinality/one
     :db/unique :db.unique/identity}
    {:db/ident :writer-read/rank
     :db/valueType :db.type/long
     :db/cardinality :db.cardinality/one}])
  (doseq [batch (partition-all 500 (range entity-count))]
    (d/transact
     connection
     (mapv (fn [position]
             {:writer-read/id (str "entity-" position)
              :writer-read/rank (long position)})
           batch))))

(defn- dependencies
  []
  {::writer/database-initializer seed-database!
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_database] [])
   ::writer/embedding-inputs-for-eids (fn [_database _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions
   (fn [_database _assertions] [])
   ::writer/query-vec (fn [_query] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_database _vector _limit _entity-ids] [])})

(defn- call!
  [channel request]
  (uds/call! {::uds/channel channel ::uds/message request}))

(defn- database-value
  [channel database-name]
  (:seon.db/db
   (call! channel
          (protocol/resolve-head-request
           {::protocol/request-id (str (random-uuid))
            ::protocol/database-name database-name}))))

(defn- query-request
  [database request-id query arguments]
  (protocol/query-request
   {::protocol/request-id request-id
    :seon.db/db database
    ::protocol/query-form query
    ::protocol/arguments arguments}))

(defn- unbounded-query
  [database request-id]
  (query-request database request-id
                 '[:find ?entity ?rank
                   :where [?entity :writer-read/rank ?rank]]
                 []))

(defn- slow-query
  [database request-id cache-key]
  (query-request
   database request-id
   '[:find (count ?left) .
     :in $ ?cache-key
     :where
     [?left :writer-read/rank ?left-rank]
     [?right :writer-read/rank ?right-rank]
     [(< ?left-rank ?right-rank)]
     [(not= ?right-rank ?cache-key)]]
   [cache-key]))

(defn- quick-query
  [database request-id]
  (query-request
   database request-id
   '[:find ?entity .
     :in $ ?id
     :where [?entity :writer-read/id ?id]]
   ["entity-7"]))

(defn- test-defaults
  [{:keys [max-results deadline-ms]
    :or {max-results 100 deadline-ms 5000}}]
  {:datahike.resource/max-work 2000000000
   :datahike.resource/max-results max-results
   :datahike.resource/max-result-weight 1000000
   ::writer/read-deadline-ms deadline-ms})

(defn- budget-error?
  [response budget-name allowed]
  (and (false? (::protocol/success? response))
       (= protocol/database-error (::protocol/error-kind response))
       (true? (:datahike/budget-exceeded response))
       (= budget-name (:datahike.budget/name response))
       (= allowed (:datahike.budget/allowed response))))

(defn- with-writer
  [label defaults selected-processors channel-count body]
  (with-redefs [writer/read-defaults defaults]
    (let [database-name (str "writer-read-ceiling-" label "-" (random-uuid))
          path (socket-path label)
          server (writer/start! {::writer/dependencies (dependencies)
                                 ::writer/database-name database-name
                                 ::writer/backend :memory
                                 ::writer/selected-processors selected-processors
                                 ::writer/request-socket-path path})
          channels (mapv (fn [_] (uds/connect! path)) (range channel-count))]
      (try
        (body channels (database-value (first channels) database-name))
        (finally
          (doseq [^SocketChannel channel channels]
            (try (.close channel) (catch Throwable _)))
          (writer/stop! server)
          (.delete (File. path)))))))

(deftest capless-read-is-limited-and-connection-remains-healthy
  (let [defaults (test-defaults {:max-results 100})]
    (with-writer
      "capless" defaults 3 1
      (fn [[channel] database]
        (let [limited (call! channel
                             (unbounded-query database "capless/hostile"))
              healthy (call! channel (quick-query database "capless/healthy"))]
          (is (budget-error? limited :query-results 100))
          (is (::protocol/success? healthy))
          (is (integer? (:datahike.query/result healthy))))))))

(deftest capless-read-settles-at-the-writer-deadline
  (let [deadline-ms 100
        defaults (test-defaults {:max-results 1000000
                                 :deadline-ms deadline-ms})]
    (with-writer
      "deadline" defaults 3 1
      (fn [[channel] database]
        (let [started (System/nanoTime)
              response (call! channel
                              (slow-query database "deadline/hostile" -1))
              elapsed-ms (/ (double (- (System/nanoTime) started)) 1000000.0)
              healthy (call! channel (quick-query database "deadline/healthy"))]
          (is (budget-error? response :deadline deadline-ms))
          (is (<= 50.0 elapsed-ms 2500.0))
          (is (::protocol/success? healthy)))))))

(deftest client-read-ceiling-within-server-maximum-is-preserved
  (let [defaults (test-defaults {:max-results 100})]
    (with-writer
      "client-lower" defaults 3 1
      (fn [[channel] database]
        (let [response
              (call! channel
                     (assoc (unbounded-query database "client/lower")
                            :datahike.resource/max-results 7))]
          (is (budget-error? response :query-results 7))
          (is (= 8 (:datahike.budget/observed response))))))))

(deftest client-read-ceiling-above-server-maximum-is-clamped
  (let [defaults (test-defaults {:max-results 50})]
    (with-writer
      "client-higher" defaults 3 1
      (fn [[channel] database]
        (let [response
              (call! channel
                     (assoc (unbounded-query database "client/higher")
                            :datahike.resource/max-results 1000))]
          (is (budget-error? response :query-results 50))
          (is (not= 1000 (:datahike.budget/allowed response))))))))

(deftest hostile-capless-read-does-not-block-a-parallel-quick-read
  (let [deadline-ms 100
        defaults (test-defaults {:max-results 1000000
                                 :deadline-ms deadline-ms})]
    (with-writer
      "parallel" defaults 3 2
      (fn [[hostile-channel quick-channel] database]
        (let [hostile (future
                        (call! hostile-channel
                               (slow-query database "parallel/hostile" -2)))]
          (Thread/sleep 20)
          (let [quick (future
                        (call! quick-channel
                               (quick-query database "parallel/quick")))
                quick-response (deref quick 2000 ::timeout)
                hostile-response (deref hostile 2500 ::timeout)]
            (testing "the other retained connection completes independently"
              (is (not= ::timeout quick-response))
              (is (::protocol/success? quick-response)))
            (is (not= ::timeout hostile-response))
            (is (budget-error? hostile-response :deadline deadline-ms))))))))
