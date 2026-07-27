(ns seon.db.writer-mutation-concurrency-test
  "Cross-database mutation concurrency at the complete writer boundary."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.executor :as executor]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.writer-test-support :as writer-test]
            [seon.db.writer :as writer])
  (:import [java.io File]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _k _eids] [])})

(defn- request-path [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory (str "writer-mutation-concurrency-" label "-"
                           (random-uuid) ".sock")))))

(defn- ensure-database! [runtime database-name request-id]
  (writer/handle-request
   runtime
   (protocol/ensure-database-request
    {::protocol/request-id request-id
     ::protocol/database-name database-name
     ::protocol/backend :memory})))

(defn- transact! [runtime database request-id transaction-data]
  (writer/handle-request
   runtime
   (protocol/transaction-request
    {::protocol/request-id request-id
     :seon.db/db database
     ::protocol/transaction-data transaction-data})))

(defn- mutation-capacity
  [processors maximum-active maximum-queued]
  (-> (executor/capacity processors)
      (assoc-in [::executor/classes :mutation ::executor/maximum-active]
                maximum-active)
      (assoc-in [::executor/classes :mutation ::executor/maximum-queued]
                maximum-queued)
      (assoc-in [::executor/classes :mutation
                 ::executor/maximum-queued-by-database]
                maximum-queued)))

(deftest ordinary-mutations-pipeline-while-allocation-remains-exclusive
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-a (str "mutation-a-" (random-uuid))
        database-b (str "mutation-b-" (random-uuid))
        path (request-path "request")
        server (writer-test/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-a
                               ::writer/backend :memory
                               ::writer/selected-processors 4
                               ::executor/capacity (mutation-capacity 4 4 16)
                               ::writer/request-socket-path path})
        runtime (::writer/runtime server)
        original (var-get #'writer/transact-once-async!)
        ordinary-entered (CountDownLatch. 3)
        allocation-entered (CountDownLatch. 1)
        release-ordinary (CountDownLatch. 1)]
    (try
      (let [head-a (:seon.db/db
                    (ensure-database! runtime database-a "ensure/a"))
            head-b (:seon.db/db
                    (ensure-database! runtime database-b "ensure/b"))
            declaration [{:db/ident :writer.concurrent/id
                          :db/valueType :db.type/string
                          :db/cardinality :db.cardinality/one
                          :db/unique :db.unique/identity}]
            schema-a (transact! runtime head-a "schema/a" declaration)
            schema-b (transact! runtime head-b "schema/b" declaration)
            db-a (:db-after schema-a)
            db-b (:db-after schema-b)
            wrapper
            (fn [runtime connection database-name connection-id request]
              (if (= "mutation/a-allocation" (::protocol/request-id request))
                (.countDown allocation-entered)
                (do
                  (.countDown ordinary-entered)
                  (when-not (.await release-ordinary 5 TimeUnit/SECONDS)
                    (throw (ex-info "concurrent mutations were not released"
                                    {::protocol/database-name database-name})))))
              (original runtime connection database-name connection-id request))]
        (is (::protocol/success? schema-a) (pr-str schema-a))
        (is (::protocol/success? schema-b) (pr-str schema-b))
        (with-redefs-fn
          {#'writer/transact-once-async! wrapper}
          (fn []
            (let [a-first
                  (future
                    (transact! runtime db-a "mutation/a-first"
                               [{:writer.concurrent/id "a-first"}]))
                  a-second
                  (future
                    (transact! runtime db-a "mutation/a-second"
                               [{:writer.concurrent/id "a-second"}]))
                  b-first
                  (future
                    (transact! runtime db-b "mutation/b-first"
                               [{:writer.concurrent/id "b-first"}]))]
              (is (.await ordinary-entered 5 TimeUnit/SECONDS)
                  "ordinary mutations for one and multiple databases pipeline")
              (let [allocation
                    (future
                      (transact! runtime db-a "mutation/a-allocation"
                                 [{:db/id "a-allocation"
                                   :writer.concurrent/id "a-allocation"}]))]
                (is (false? (.await allocation-entered
                                    100 TimeUnit/MILLISECONDS))
                    "a tempid allocation waits for every running mutation")
                (.countDown release-ordinary)
                (is (.await allocation-entered 5 TimeUnit/SECONDS))
                (let [responses (mapv #(deref % 5000 ::timeout)
                                      [a-first a-second b-first allocation])]
                  (is (every? ::protocol/success? responses) (pr-str responses))
                  (is (= #{"a-first" "a-second" "a-allocation"}
                         (set (d/q '[:find [?id ...]
                                     :where [_ :writer.concurrent/id ?id]]
                                   (d/db
                                    (::registry/conn
                                     (registry/resolve-connection
                                      {::registry/database-name
                                       (keyword database-a)})))))))
                  (is (= #{"b-first"}
                         (set (d/q '[:find [?id ...]
                                     :where [_ :writer.concurrent/id ?id]]
                                   (d/db
                                    (::registry/conn
                                     (registry/resolve-connection
                                      {::registry/database-name
                                       (keyword database-b)})))))))))))))
      (finally
        (.countDown release-ordinary)
        (writer/stop! server)
        (.delete (File. path))
        (registry/restore-registry! {::registry/snapshot snapshot})))))

(deftest duplicate-in-flight-mutation-waits-for-the-original-result
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-name (str "mutation-retry-" (random-uuid))
        path (request-path "retry")
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/selected-processors 4
          ::executor/capacity (mutation-capacity 4 4 16)
          ::writer/request-socket-path path})
        runtime (::writer/runtime server)
        original (var-get #'writer/transact-once-async!)
        entered (CountDownLatch. 1)
        release (CountDownLatch. 1)]
    (try
      (let [head (:seon.db/db
                  (ensure-database! runtime database-name "retry/ensure"))
            schema
            (transact! runtime head "retry/schema"
                       [{:db/ident :writer.concurrent/id
                         :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one
                         :db/unique :db.unique/identity}])
            database (:db-after schema)
            reports (atom [])
            connection
            (::registry/conn
             (registry/resolve-connection
              {::registry/database-name (keyword database-name)}))
            _ (d/listen connection ::capture #(swap! reports conj %))
            wrapper
            (fn [runtime connection database-name connection-id request]
              (.countDown entered)
              (when-not (.await release 5 TimeUnit/SECONDS)
                (throw (ex-info "the original mutation was not released" {})))
              (original runtime connection database-name connection-id request))]
        (with-redefs-fn
          {#'writer/transact-once-async! wrapper}
          (fn []
            (let [request-id "mutation/retry"
                  transaction-data [{:writer.concurrent/id "once"}]
                  first-attempt
                  (future
                    (transact! runtime database request-id transaction-data))]
              (is (.await entered 5 TimeUnit/SECONDS))
              (let [retry
                    (future
                      (transact! runtime database request-id transaction-data))]
                (is (= ::waiting (deref retry 100 ::waiting))
                    "a same-intent retry waits instead of receiving steering")
                (.countDown release)
                (let [first-response (deref first-attempt 5000 ::timeout)
                      retry-response (deref retry 5000 ::timeout)]
                  (is (::protocol/success? first-response)
                      (pr-str first-response))
                  (is (= first-response retry-response)
                      "the waiter receives the original transaction response")
                  (is (= 1 (count @reports)))
                  (is (= 1
                         (d/q '[:find (count ?entity) .
                                :where [?entity :writer.concurrent/id "once"]]
                              (d/db connection))))))))))
      (finally
        (.countDown release)
        (writer/stop! server)
        (.delete (File. path))
        (registry/restore-registry! {::registry/snapshot snapshot})))))
