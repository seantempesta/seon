(ns seon.db.transaction-coordinate-test
  "Writer-backed original transaction coordinate resolution tests."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.writer :as writer]))

(defn- isolate-registry
  [test-fn]
  (let [{::registry/keys [snapshot]} (registry/snapshot-registry {})]
    (try
      (test-fn)
      (finally
        (registry/restore-registry! {::registry/snapshot snapshot})))))

(use-fixtures :each isolate-registry)

(defn- runtime
  []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _k _eids] [])})

(defn- ensure-database!
  [runtime database-name]
  (writer/handle-request
   runtime
   (protocol/ensure-database-request
    {::protocol/request-id (str "coordinate/ensure/" database-name)
     ::protocol/database-name database-name
     ::protocol/backend :memory})))

(defn- transact!
  [runtime database request-id value]
  (writer/handle-request
   runtime
   (protocol/transaction-request
    {:seon.db/db database
     ::protocol/request-id request-id
     ::protocol/transaction-data
     [{:db/id request-id :db/doc value}]})))

(defn- connection
  [database-name]
  (::registry/conn
   (registry/lookup-connection
    {::registry/database-name (keyword database-name)})))

(defn- current-coordinate
  [database-name]
  (coordinate/resolved (d/db (connection database-name))))

(defn- resolve-coordinate
  [runtime database-name head-coordinate transaction]
  (writer/handle-request
   runtime
   (protocol/resolve-transaction-coordinate-request
    {::protocol/request-id (str "coordinate/resolve/" database-name "/" transaction)
     ::protocol/database-name database-name
     ::protocol/head-coordinate head-coordinate
     ::protocol/transaction-id transaction})))

(deftest later-head-resolution-skips-a-force-commit-with-the-same-t
  (let [runtime (runtime)
        database-name (str "transaction-coordinate-" (random-uuid))
        admitted (ensure-database! runtime database-name)
        completion-response
        (transact! runtime (:seon.db/db admitted) "completion" "completion")
        completion-coordinate (current-coordinate database-name)
        conn (connection database-name)
        completion-db (d/commit-as-db
                       conn (::coordinate/commit-id completion-coordinate))
        _ (d/force-branch!
           completion-db :db #{(::coordinate/commit-id completion-coordinate)}
           {:expected-current-commit
            (::coordinate/commit-id completion-coordinate)})
        _ (registry/release-database!
           {::registry/database-name (keyword database-name)})
        reopened (ensure-database! runtime database-name)
        later-response (transact! runtime (:seon.db/db reopened) "later" "later")
        later-coordinate (current-coordinate database-name)
        resolved
        (resolve-coordinate runtime database-name later-coordinate
                            (::coordinate/t completion-coordinate))]
    (is (true? (::protocol/success? resolved)))
    (is (= completion-coordinate (::protocol/coordinate resolved))
        "the force commit repeats t but did not originate its transaction")))

(deftest wrong-attachment-and-non-ancestor-heads-fail-explicitly
  (let [runtime (runtime)
        database-name (str "transaction-coordinate-failures-" (random-uuid))
        admitted (ensure-database! runtime database-name)
        first-response (transact! runtime (:seon.db/db admitted) "first" "first")
        first-coordinate (current-coordinate database-name)
        abandoned-response
        (transact! runtime (:db-after first-response) "abandoned" "abandoned")
        abandoned-coordinate (current-coordinate database-name)
        conn (connection database-name)
        first-db (d/commit-as-db conn (::coordinate/commit-id first-coordinate))
        _ (d/force-branch!
           first-db :db #{(::coordinate/commit-id first-coordinate)}
           {:expected-current-commit
            (::coordinate/commit-id abandoned-coordinate)})
        _ (registry/release-database!
           {::registry/database-name (keyword database-name)})
        reopened (ensure-database! runtime database-name)
        current-response (transact! runtime (:seon.db/db reopened) "current" "current")
        current-coordinate (current-coordinate database-name)
        wrong-attachment
        (resolve-coordinate
         runtime database-name
         (assoc current-coordinate ::coordinate/database-id (random-uuid))
         (::coordinate/t current-coordinate))
        non-ancestor
        (resolve-coordinate runtime database-name abandoned-coordinate
                            (::coordinate/t abandoned-coordinate))]
    (is (false? (::protocol/success? wrong-attachment)))
    (is (= protocol/attachment-mismatch-error
           (::protocol/error-kind wrong-attachment)))
    (is (false? (::protocol/success? non-ancestor)))
    (is (= protocol/non-ancestor-error
           (::protocol/error-kind non-ancestor)))))

(deftest missing-transaction-does-not-substitute-the-frozen-head
  (let [runtime (runtime)
        database-name (str "transaction-coordinate-missing-" (random-uuid))
        admitted (ensure-database! runtime database-name)
        response (transact! runtime (:seon.db/db admitted) "only" "only")
        head (current-coordinate database-name)
        missing (resolve-coordinate runtime database-name head
                                    (inc (::coordinate/t head)))]
    (is (false? (::protocol/success? missing)))
    (is (= protocol/not-found-error (::protocol/error-kind missing)))))

(deftest branch-head-alias-is-not-accepted-as-a-main-commit-coordinate
  (let [runtime (runtime)
        database-name (str "transaction-coordinate-alias-" (random-uuid))
        _ (ensure-database! runtime database-name)
        main-conn (connection database-name)
        main-coordinate (coordinate/resolved (d/db main-conn))
        branch :transaction-coordinate/alias
        _ (d/branch! main-conn :db branch)
        branch-name (str database-name "-alias")
        branch-open
        (writer/handle-request
         runtime
         (protocol/ensure-database-request
          {::protocol/request-id "coordinate/ensure-branch"
           ::protocol/database-name branch-name
           ::protocol/backend :memory
           ::coordinate/attachment
           (assoc (coordinate/attachment main-coordinate)
                  ::coordinate/branch branch)}))
        branch-coordinate (current-coordinate branch-name)
        request
        {::protocol/operation
         protocol/resolve-transaction-coordinate-operation
         ::protocol/database-name branch-name
         ::protocol/head-coordinate branch-coordinate
         ::protocol/transaction-id (::coordinate/t branch-coordinate)}
        response (writer/handle-request runtime request)]
    (is (true? (::protocol/success? branch-open)))
    (is (= branch (::coordinate/branch branch-coordinate)))
    (is (false? (protocol/valid-request? request))
        "the portable request schema names only the live :db lineage")
    (is (= protocol/protocol-error (::protocol/error-kind response)))))
