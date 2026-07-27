(ns seon.db.database-value-test
  "Writer-backed transaction-to-Proximum-branch-head resolution tests."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
            [seon.db.branch :as branch]
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
    {::protocol/request-id (str "database-value/ensure/" database-name)
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

(defn- current-branch-head
  [database-name]
  (branch/head (d/db (connection database-name))))

(defn- resolve-branch-head
  [runtime database-name containing-branch-head transaction]
  (writer/handle-request
   runtime
   (protocol/resolve-transaction-branch-head-request
    {::protocol/request-id (str "database-value/resolve/" database-name "/" transaction)
     ::protocol/database-name database-name
     ::protocol/containing-branch-head containing-branch-head
     ::protocol/transaction-id transaction})))

(deftest later-head-resolution-skips-a-force-commit-with-the-same-t
  (let [runtime (runtime)
        database-name (str "branch-" (random-uuid))
        admitted (ensure-database! runtime database-name)
        completion-response
        (transact! runtime (:seon.db/db admitted) "completion" "completion")
        completion-branch-head (current-branch-head database-name)
        conn (connection database-name)
        completion-db (d/commit-as-db
                       conn (::branch/commit-id completion-branch-head))
        _ (d/force-branch!
           completion-db :db #{(::branch/commit-id completion-branch-head)}
           {:expected-current-commit
            (::branch/commit-id completion-branch-head)})
        _ (registry/release-database!
           {::registry/database-name (keyword database-name)})
        reopened (ensure-database! runtime database-name)
        later-response (transact! runtime (:seon.db/db reopened) "later" "later")
        later-branch-head (current-branch-head database-name)
        resolved
        (resolve-branch-head runtime database-name later-branch-head
                             (::branch/basis-t completion-branch-head))]
    (is (true? (::protocol/success? resolved)))
    (is (= completion-branch-head (::protocol/branch-head resolved))
        "the force commit repeats t but did not originate its transaction")))

(deftest wrong-connection-and-non-ancestor-heads-fail-explicitly
  (let [runtime (runtime)
        database-name (str "branch-failures-" (random-uuid))
        admitted (ensure-database! runtime database-name)
        first-response (transact! runtime (:seon.db/db admitted) "first" "first")
        first-branch-head (current-branch-head database-name)
        abandoned-response
        (transact! runtime (:db-after first-response) "abandoned" "abandoned")
        abandoned-branch-head (current-branch-head database-name)
        conn (connection database-name)
        first-db (d/commit-as-db conn (::branch/commit-id first-branch-head))
        _ (d/force-branch!
           first-db :db #{(::branch/commit-id first-branch-head)}
           {:expected-current-commit
            (::branch/commit-id abandoned-branch-head)})
        _ (registry/release-database!
           {::registry/database-name (keyword database-name)})
        reopened (ensure-database! runtime database-name)
        current-response (transact! runtime (:seon.db/db reopened) "current" "current")
        current-branch-head (current-branch-head database-name)
        wrong-connection
        (resolve-branch-head
         runtime database-name
         (assoc current-branch-head ::branch/store-id (random-uuid))
         (::branch/basis-t current-branch-head))
        non-ancestor
        (resolve-branch-head runtime database-name abandoned-branch-head
                             (::branch/basis-t abandoned-branch-head))]
    (is (false? (::protocol/success? wrong-connection)))
    (is (= protocol/connection-id-mismatch-error
           (::protocol/error-kind wrong-connection)))
    (is (false? (::protocol/success? non-ancestor)))
    (is (= protocol/non-ancestor-error
           (::protocol/error-kind non-ancestor)))))

(deftest missing-transaction-does-not-substitute-the-frozen-head
  (let [runtime (runtime)
        database-name (str "branch-missing-" (random-uuid))
        admitted (ensure-database! runtime database-name)
        response (transact! runtime (:seon.db/db admitted) "only" "only")
        head (current-branch-head database-name)
        missing (resolve-branch-head runtime database-name head
                                     (inc (::branch/basis-t head)))]
    (is (false? (::protocol/success? missing)))
    (is (= protocol/not-found-error (::protocol/error-kind missing)))))

(deftest branch-head-alias-is-not-accepted-as-a-main-commit
  (let [runtime (runtime)
        database-name (str "branch-alias-" (random-uuid))
        _ (ensure-database! runtime database-name)
        main-conn (connection database-name)
        main-branch-head (branch/head (d/db main-conn))
        branch :branch/alias
        _ (d/branch! main-conn :db branch)
        branch-name (str database-name "-alias")
        branch-open
        (writer/handle-request
         runtime
         (protocol/ensure-database-request
          {::protocol/request-id "database-value/ensure-branch"
           ::protocol/database-name branch-name
           ::protocol/backend :memory
           ::branch/connection-id
           (assoc (branch/connection-id main-branch-head) 1 branch)}))
        branch-head (current-branch-head branch-name)
        request
        {::protocol/operation
         protocol/resolve-transaction-branch-head-operation
         ::protocol/database-name branch-name
         ::protocol/containing-branch-head branch-head
         ::protocol/transaction-id (::branch/basis-t branch-head)}
        response (writer/handle-request runtime request)]
    (is (true? (::protocol/success? branch-open)))
    (is (= branch (::branch/name branch-head)))
    (is (false? (protocol/valid-request? request))
        "the portable request schema names only the live :db lineage")
    (is (= protocol/protocol-error (::protocol/error-kind response)))))
