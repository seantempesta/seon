(ns seon.db.transaction-coordinate-test
  "Writer-backed original transaction coordinate resolution tests."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [datahike.api :as d]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
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
   ::writer/knn (fn [_db-value _vector _k _eids] [])
   ::writer/publisher
   {::uds/channel (Object.)
    ::uds/subscribers (atom #{})
    ::uds/closed? (atom false)}})

(defn- ensure-database!
  [runtime database-name]
  (writer/handle-request
   runtime
   (protocol/ensure-database-request
    {::protocol/database-name database-name
     ::protocol/backend :memory})))

(defn- transact!
  [runtime database-name request-id value]
  (writer/handle-request
   runtime
   (protocol/transaction-request
    {::protocol/database-name database-name
     ::protocol/request-id request-id
     ::protocol/transaction-data
     [{:db/id request-id :db/doc value}]})))

(defn- connection
  [database-name]
  (::registry/conn
   (registry/lookup-connection
    {::registry/database-name (keyword database-name)})))

(defn- resolve-coordinate
  [runtime database-name head-coordinate transaction]
  (writer/handle-request
   runtime
   (protocol/resolve-transaction-coordinate-request
    {::protocol/database-name database-name
     ::protocol/head-coordinate head-coordinate
     ::protocol/transaction-id transaction})))

(deftest later-head-resolution-skips-a-force-commit-with-the-same-t
  (let [runtime (runtime)
        database-name (str "transaction-coordinate-" (random-uuid))
        _ (ensure-database! runtime database-name)
        completion-response
        (transact! runtime database-name "completion" "completion")
        completion-coordinate (::protocol/coordinate completion-response)
        conn (connection database-name)
        completion-db (d/commit-as-db
                       conn (::coordinate/commit-id completion-coordinate))
        _ (d/force-branch!
           completion-db :db #{(::coordinate/commit-id completion-coordinate)}
           {:expected-current-commit
            (::coordinate/commit-id completion-coordinate)})
        _ (registry/release-database!
           {::registry/database-name (keyword database-name)})
        _ (ensure-database! runtime database-name)
        later-response (transact! runtime database-name "later" "later")
        later-coordinate (::protocol/coordinate later-response)
        resolved
        (resolve-coordinate runtime database-name later-coordinate
                            (::coordinate/t completion-coordinate))]
    (is (true? (::protocol/success? resolved)))
    (is (= completion-coordinate (::protocol/coordinate resolved))
        "the force commit repeats t but did not originate its transaction")))

(deftest wrong-attachment-and-non-ancestor-heads-fail-explicitly
  (let [runtime (runtime)
        database-name (str "transaction-coordinate-failures-" (random-uuid))
        _ (ensure-database! runtime database-name)
        first-response (transact! runtime database-name "first" "first")
        first-coordinate (::protocol/coordinate first-response)
        abandoned-response
        (transact! runtime database-name "abandoned" "abandoned")
        abandoned-coordinate (::protocol/coordinate abandoned-response)
        conn (connection database-name)
        first-db (d/commit-as-db conn (::coordinate/commit-id first-coordinate))
        _ (d/force-branch!
           first-db :db #{(::coordinate/commit-id first-coordinate)}
           {:expected-current-commit
            (::coordinate/commit-id abandoned-coordinate)})
        _ (registry/release-database!
           {::registry/database-name (keyword database-name)})
        _ (ensure-database! runtime database-name)
        current-response (transact! runtime database-name "current" "current")
        current-coordinate (::protocol/coordinate current-response)
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
        _ (ensure-database! runtime database-name)
        response (transact! runtime database-name "only" "only")
        head (::protocol/coordinate response)
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
          {::protocol/database-name branch-name
           ::protocol/backend :memory
           ::coordinate/attachment
           (assoc (coordinate/attachment main-coordinate)
                  ::coordinate/branch branch)}))
        branch-coordinate (::coordinate/coordinate branch-open)
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
