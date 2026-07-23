(ns seon.db.writer-crash-fixture
  "Killable file-backed writer process for recovery regressions."
  (:require [seon.db.executor :as executor]
            [seon.db.writer :as writer]
            [seon.db.writer-test-support :as writer-test]))

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _k _eids] [])})

(defn- capacity []
  (-> (executor/capacity 8)
      (assoc-in [::executor/classes :mutation ::executor/maximum-active] 32768)
      (assoc-in [::executor/classes :mutation ::executor/maximum-queued] 32768)
      (assoc-in [::executor/classes :mutation
                 ::executor/maximum-queued-by-database]
                32768)))

(defn -main
  "Start one killable writer and retain it until the process exits."
  [database-name database-path request-socket-path]
  (writer-test/start!
   {::writer/dependencies (dependencies)
    ::writer/database-name database-name
    ::writer/backend :file
    ::writer/database-path database-path
    ::executor/capacity (capacity)
    ::writer/request-socket-path request-socket-path})
  (.. (Thread/currentThread) join))
