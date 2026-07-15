(ns seon.db.registry-test
  "Database registry lifecycle, concurrency, and fork tests."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
            [seon.db.backend :as backend]
            [seon.db.id :as id]
            [seon.db.registry :as registry])
  (:import [java.io File]))

(defn- isolate-registry
  [test-fn]
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})]
    (try
      (test-fn)
      (finally
        (registry/restore-registry! {::registry/snapshot snapshot})))))

(use-fixtures :each isolate-registry)

(defn- ensure-database!
  [request]
  (registry/ensure-database!
   (assoc request ::registry/initialize-connection!
          (fn [_connection _database-name] nil))))

(defn- delete-tree!
  [path]
  (let [root (File. ^String path)]
    (when (.exists root)
      (run! (fn [^File file] (.delete file))
            (reverse (file-seq root))))))

(deftest ensure-is-idempotent-and-concurrent
  (let [database-name :registry/concurrent
        start (promise)
        attempts
        (mapv (fn [_]
                (future
                  @start
                  (ensure-database!
                   {::registry/database-name database-name
                    ::registry/backend :memory})))
              (range 8))]
    (deliver start true)
    (let [entries (mapv deref attempts)
          connections (mapv ::registry/conn entries)
          connection (first connections)]
      (is (every? #(identical? connection %) connections))
      (is (nil? (id/assert-allocation-writer! connection)))
      (is (= [database-name]
             (mapv ::registry/database-name
                   (::registry/databases (registry/list-databases {})))))
      (is (true? (::registry/released?
                  (registry/release-database!
                   {::registry/database-name database-name}))))
      (is (false? (::registry/released?
                   (registry/release-database!
                    {::registry/database-name database-name})))))))

(deftest release-failure-remains-visible-and-retains-registry-identity
  (let [database-name :registry/release-failure
        entry
        (ensure-database!
         {::registry/database-name database-name
          ::registry/backend :memory})
        failure
        (with-redefs [d/release
                      (fn [_]
                        (throw (ex-info "injected release failure"
                                        {:registry-test/failure true})))]
          (registry/release-database!
           {::registry/database-name database-name}))
        retry
        (registry/release-database!
         {::registry/database-name database-name})
        listed
        (first
         (filter #(= database-name (::registry/database-name %))
                 (::registry/databases (registry/list-databases {}))))]
    (is (false? (::registry/released? failure)))
    (is (re-find #"injected release failure"
                 (::registry/release-error failure)))
    (is (= failure retry)
        "the same process cannot reclassify an unproved release as success")
    (is (identical? (::registry/conn entry)
                    (::registry/conn
                     (registry/lookup-connection
                      {::registry/database-name database-name})))
        "the registry retains the exact failed identity for diagnosis")
    (is (= (::registry/release-error failure)
           (::registry/release-error listed)))))

(deftest delete-refuses-to-run-after-release-failure
  (let [database-name :registry/delete-release-failure
        deleted? (atom false)]
    (ensure-database!
     {::registry/database-name database-name
      ::registry/backend :memory})
    (let [result
          (with-redefs [d/release
                        (fn [_]
                          (throw (ex-info "cannot prove release" {})))
                        d/delete-database
                        (fn [_]
                          (reset! deleted? true))]
            (registry/delete-database!
             {::registry/database-name database-name}))]
      (is (false? (::registry/released? result)))
      (is (false? (::registry/deleted? result)))
      (is (false? @deleted?)
          "destructive deletion cannot follow an unproved release")
      (is (re-find #"cannot prove release" (::registry/error result))))))

(deftest initial-schema-is-installed-before-connection-publication
  (let [database-name :registry/initial-schema
        declaration {:db/ident :registry.initial/id
                     :db/valueType :db.type/string
                     :db/cardinality :db.cardinality/one}
        observed (atom nil)
        entry
        (registry/ensure-database!
         {::registry/database-name database-name
          ::registry/backend :memory
          ::registry/initial-tx [declaration]
          ::registry/initialize-connection!
          (fn [connection _]
            (reset! observed
                    (get (:schema (d/db connection)) :registry.initial/id)))})]
    (is (= (select-keys declaration [:db/valueType :db/cardinality])
           (select-keys @observed [:db/valueType :db/cardinality])))
    (is (identical? (::registry/conn entry)
                    (::registry/conn
                     (registry/ensure-database!
                      {::registry/database-name database-name
                       ::registry/backend :memory
                       ::registry/initial-tx []
                       ::registry/initialize-connection!
                       (fn [_ _]
                         (throw (ex-info "initializer reran" {})))}))))))

(deftest file-fork-has-independent-identity-and-exact-fork-state
  (let [root (str (System/getProperty "java.io.tmpdir")
                  "/seon-registry-fork-" (random-uuid))
        source-name :registry/fork-source
        fork-name :registry/fork-target
        source-path (str root "/source/db")
        fork-path (str root "/fork/db")]
    (try
      (let [source-entry
            (ensure-database!
             {::registry/database-name source-name
              ::registry/backend :file
              ::registry/path source-path})
            source-connection (::registry/conn source-entry)]
        (d/transact source-connection
                    [{:db/ident :fork/value
                      :db/valueType :db.type/string
                      :db/cardinality :db.cardinality/one}
                     {:fork/value "at-fork"}])
        (let [basis-t (:max-tx (d/db source-connection))
              forked
              (registry/fork-database!
               {::registry/database-name source-name
                ::registry/fork-database-name fork-name
                ::registry/at basis-t
                ::registry/path fork-path})
              fork-entry
              (ensure-database!
               {::registry/database-name fork-name
                ::registry/backend :file
                ::registry/path fork-path})
              fork-connection (::registry/conn fork-entry)]
          (is (true? (::registry/forked? forked)))
          (is (= basis-t (::registry/basis-t forked)))
          (is (= basis-t (:max-tx (d/db fork-connection))))
          (is (= "at-fork"
                 (d/q '[:find ?value . :where [_ :fork/value ?value]]
                      (d/db fork-connection))))
          (is (not= (backend/database-id source-name)
                    (backend/database-id fork-name))
              "the fork is a new database, not a second name for the source")
          (d/transact fork-connection [{:fork/value "fork-only"}])
          (is (nil? (d/q '[:find ?entity .
                            :where [?entity :fork/value "fork-only"]]
                          (d/db source-connection))))))
      (finally
        (registry/delete-database!
         {::registry/database-name fork-name})
        (registry/delete-database!
         {::registry/database-name source-name})
        (delete-tree! root)))))
