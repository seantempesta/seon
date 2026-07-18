(ns seon.db.writer-mutation-concurrency-test
  "Cross-database mutation concurrency at the complete writer boundary."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
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

(deftest independent-database-mutations-run-together-and-each-database-serializes
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})
        database-a (str "mutation-a-" (random-uuid))
        database-b (str "mutation-b-" (random-uuid))
        path (request-path "request")
        server (writer/start! {::writer/dependencies (dependencies)
                               ::writer/database-name database-a
                               ::writer/backend :memory
                               ::writer/selected-processors 4
                               ::writer/request-socket-path path})
        runtime (::writer/runtime server)
        original (var-get #'writer/transact-once-async!)
        first-mutations-entered (CountDownLatch. 2)
        second-a-entered (CountDownLatch. 1)
        release-first-mutations (CountDownLatch. 1)
        calls (atom {})]
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
              (let [ordinal (get (swap! calls update database-name (fnil inc 0))
                                 database-name)]
                (if (and (= database-a database-name) (= 2 ordinal))
                  (.countDown second-a-entered)
                  (do
                    (.countDown first-mutations-entered)
                    (when-not (.await release-first-mutations
                                      5 TimeUnit/SECONDS)
                      (throw (ex-info "concurrent mutations were not released"
                                      {::protocol/database-name database-name})))))
                (original runtime connection database-name connection-id request)))]
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
              (is (.await first-mutations-entered 5 TimeUnit/SECONDS)
                  "the first mutation for both databases starts concurrently")
              (is (false? (.await second-a-entered 100 TimeUnit/MILLISECONDS))
                  "the second mutation for one database remains serialized")
              (.countDown release-first-mutations)
              (let [responses (mapv #(deref % 5000 ::timeout)
                                    [a-first a-second b-first])]
                (is (every? ::protocol/success? responses) (pr-str responses))
                (is (.await second-a-entered 5 TimeUnit/SECONDS))
                (is (= #{"a-first" "a-second"}
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
                                     (keyword database-b)}))))))))))))
      (finally
        (.countDown release-first-mutations)
        (writer/stop! server)
        (.delete (File. path))
        (registry/restore-registry! {::registry/snapshot snapshot})))))
