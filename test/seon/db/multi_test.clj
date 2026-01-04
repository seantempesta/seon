(ns seon.db.multi-test
  "Tests for XTDB multi-database management.

  Tests the namespace database isolation, attach/detach lifecycle,
  and cross-database queries."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [seon.db.multi :as multi]
            [seon.test-utils :refer [with-test-node *test-node*]]
            [next.jdbc :as jdbc]
            [xtdb.api :as xt]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(use-fixtures :each with-test-node)

;;; ---------------------------------------------------------------------------
;;; Naming Convention Tests
;;; ---------------------------------------------------------------------------

(deftest namespace->db-name-test
  (testing "converts namespace symbols to SQL-compatible database names"
    (is (= "seon_primer" (multi/namespace->db-name 'seon.primer)))
    (is (= "seon_dev" (multi/namespace->db-name 'seon.dev)))
    (is (= "seon_trading_signals" (multi/namespace->db-name 'seon.trading.signals))))

  (testing "handles string input"
    (is (= "seon_foo" (multi/namespace->db-name "seon.foo")))))

(deftest db-name->namespace-test
  (testing "converts database names back to namespace symbols"
    (is (= 'seon.primer (multi/db-name->namespace "seon_primer")))
    (is (= 'seon.dev (multi/db-name->namespace "seon_dev")))
    (is (= 'seon.trading.signals (multi/db-name->namespace "seon_trading_signals")))))

(deftest namespace->storage-path-test
  (testing "generates correct storage paths preserving dotted namespace"
    (is (= "data/namespaces/seon.primer" (multi/namespace->storage-path 'seon.primer)))
    (is (= "data/namespaces/seon.dev" (multi/namespace->storage-path 'seon.dev)))))

;;; ---------------------------------------------------------------------------
;;; Database Query Tests
;;; ---------------------------------------------------------------------------

(deftest list-attached-databases-test
  (testing "lists at least the primary xtdb database on fresh node"
    (let [dbs (multi/list-attached-databases *test-node*)]
      (is (set? dbs) "Should return a set")
      (is (contains? dbs "xtdb") "Should always include primary database"))))

(deftest db-attached?-test
  (testing "primary database is always attached"
    (is (true? (multi/db-attached? *test-node* "xtdb"))))

  (testing "non-existent database is not attached"
    (is (false? (multi/db-attached? *test-node* "nonexistent_db")))))

(deftest namespace-db-attached?-test
  (testing "checks namespace attachment using namespace symbol"
    ;; On fresh node, namespace DBs shouldn't exist
    (is (false? (multi/namespace-db-attached? *test-node* 'seon.test)))))

;;; ---------------------------------------------------------------------------
;;; Database Lifecycle Tests
;;; ---------------------------------------------------------------------------

(deftest attach-namespace-db!-test
  (testing "attaches a new namespace database"
    (let [result (multi/attach-namespace-db! *test-node* 'seon.test)]
      (is (some? result) "Should return result for new attach")
      (is (multi/namespace-db-attached? *test-node* 'seon.test)
          "Database should be attached after attach!")))

  (testing "attaching already-attached database is idempotent"
    ;; Attach again
    (let [result (multi/attach-namespace-db! *test-node* 'seon.test)]
      (is (nil? result) "Should return nil for already-attached database")
      (is (multi/namespace-db-attached? *test-node* 'seon.test)
          "Database should still be attached"))))

(deftest ensure-namespace-db!-test
  (testing "creates database if not exists"
    (let [result (multi/ensure-namespace-db! *test-node* 'seon.ensure.test)]
      (is (= :attached result) "Should return :attached for new database")
      (is (multi/namespace-db-attached? *test-node* 'seon.ensure.test))))

  (testing "returns :exists for already-attached database"
    (let [result (multi/ensure-namespace-db! *test-node* 'seon.ensure.test)]
      (is (= :exists result) "Should return :exists for existing database"))))

;;; ---------------------------------------------------------------------------
;;; Connection Tests
;;; ---------------------------------------------------------------------------

(deftest create-namespace-connection-test
  (testing "creates connection to attached namespace database"
    ;; First attach the database
    (multi/attach-namespace-db! *test-node* 'seon.conn.test)

    (with-open [conn (multi/create-namespace-connection *test-node* 'seon.conn.test)]
      (is (some? conn) "Should create connection")
      ;; Verify it's a working connection by running a query
      (let [result (xt/q conn ["SELECT 1 as x"])]
        (is (= [{:x 1}] result) "Connection should be able to query")))))

(deftest with-namespace-db-test
  (testing "executes function with connection and cleans up"
    (let [result (multi/with-namespace-db *test-node* 'seon.with.test
                   (fn [conn]
                     ;; Verify we got a valid connection
                     (xt/q conn ["SELECT 42 as answer"])))]
      (is (= [{:answer 42}] result) "Should return function result")))

  (testing "ensures database exists before connecting"
    ;; Use a fresh namespace that doesn't exist yet
    (is (false? (multi/namespace-db-attached? *test-node* 'seon.auto.attach)))
    (multi/with-namespace-db *test-node* 'seon.auto.attach
      (fn [_conn]
        (is (multi/namespace-db-attached? *test-node* 'seon.auto.attach)
            "Database should be auto-attached")))))

;;; ---------------------------------------------------------------------------
;;; Query and Transaction Tests
;;; ---------------------------------------------------------------------------

(deftest q-test
  (testing "queries namespace database"
    ;; Ensure database exists
    (multi/ensure-namespace-db! *test-node* 'seon.query.test)

    ;; Insert some data first
    (multi/execute-tx! *test-node* 'seon.query.test
                       [["INSERT INTO test_data (_id, name, value) VALUES (?, ?, ?)"
                         "id-1" "Alice" 100]])

    ;; Query it back
    (let [results (multi/q *test-node* 'seon.query.test
                           "SELECT name, value FROM test_data WHERE _id = ?"
                           ["id-1"])]
      (is (= 1 (count results)))
      (is (= "Alice" (:name (first results))))
      (is (= 100 (:value (first results)))))))

(deftest execute-tx!-test
  (testing "executes transactions on namespace database"
    (multi/ensure-namespace-db! *test-node* 'seon.tx.test)

    ;; Execute a transaction
    (let [result (multi/execute-tx! *test-node* 'seon.tx.test
                                    [["INSERT INTO items (_id, count) VALUES (?, ?)"
                                      "item-1" 5]])]
      (is (some? result) "Transaction should return result")
      (is (:tx-id result) "Should have transaction ID"))

    ;; Verify the data was inserted
    (let [items (multi/q *test-node* 'seon.tx.test
                         "SELECT * FROM items")]
      (is (= 1 (count items)))
      (is (= 5 (:count (first items)))))))

;;; ---------------------------------------------------------------------------
;;; Database Isolation Tests
;;; ---------------------------------------------------------------------------

(deftest database-isolation-test
  (testing "data in one namespace is isolated from another"
    ;; Set up two separate namespace databases
    (multi/ensure-namespace-db! *test-node* 'seon.ns1)
    (multi/ensure-namespace-db! *test-node* 'seon.ns2)

    ;; Insert different data into each
    (multi/execute-tx! *test-node* 'seon.ns1
                       [["INSERT INTO shared_table (_id, value) VALUES (?, ?)"
                         "id-1" "ns1-value"]])
    (multi/execute-tx! *test-node* 'seon.ns2
                       [["INSERT INTO shared_table (_id, value) VALUES (?, ?)"
                         "id-1" "ns2-value"]])

    ;; Query each and verify isolation
    (let [ns1-data (multi/q *test-node* 'seon.ns1 "SELECT value FROM shared_table")
          ns2-data (multi/q *test-node* 'seon.ns2 "SELECT value FROM shared_table")]
      (is (= "ns1-value" (:value (first ns1-data)))
          "ns1 should have its own data")
      (is (= "ns2-value" (:value (first ns2-data)))
          "ns2 should have its own data"))))

;;; ---------------------------------------------------------------------------
;;; Cross-Database Query Tests
;;; ---------------------------------------------------------------------------

(deftest cross-database-query-test
  (testing "can query across databases using db.table syntax"
    ;; Set up a namespace database with data
    (multi/ensure-namespace-db! *test-node* 'seon.cross)
    (multi/execute-tx! *test-node* 'seon.cross
                       [["INSERT INTO items (_id, name) VALUES (?, ?)"
                         "cross-1" "Cross DB Item"]])

    ;; Insert data into primary xtdb database
    (xt/execute-tx *test-node*
                   [["INSERT INTO items (_id, name) VALUES (?, ?)"
                     "xtdb-1" "Primary DB Item"]])

    ;; Query both from primary connection using qualified table names
    (let [results (xt/q *test-node*
                        ["SELECT _id, name FROM seon_cross.items
                          UNION ALL
                          SELECT _id, name FROM xtdb.items"])]
      (is (= 2 (count results)) "Should get items from both databases")
      (is (some #(= "Cross DB Item" (:name %)) results))
      (is (some #(= "Primary DB Item" (:name %)) results)))))

;;; ---------------------------------------------------------------------------
;;; Batch Operations Tests
;;; ---------------------------------------------------------------------------

(deftest attach-all-namespace-dbs!-test
  (testing "attaches multiple namespace databases"
    (let [namespaces ['seon.batch.one 'seon.batch.two 'seon.batch.three]
          results (multi/attach-all-namespace-dbs! *test-node* namespaces)]
      (is (map? results) "Should return a map")
      (is (= (count namespaces) (count results)) "Should have result for each namespace")
      (doseq [ns-sym namespaces]
        (is (contains? #{:attached :exists} (get results ns-sym))
            (str "Should have valid result for " ns-sym))
        (is (multi/namespace-db-attached? *test-node* ns-sym)
            (str "Database should be attached for " ns-sym))))))

(deftest list-namespace-databases-test
  (testing "lists attached namespace databases with metadata"
    ;; Attach some namespace databases
    (multi/attach-namespace-db! *test-node* 'seon.list.one)
    (multi/attach-namespace-db! *test-node* 'seon.list.two)

    (let [ns-dbs (multi/list-namespace-databases *test-node*)]
      (is (seq ns-dbs) "Should have namespace databases")
      (is (every? #(contains? % :namespace) ns-dbs) "Each should have :namespace")
      (is (every? #(contains? % :db-name) ns-dbs) "Each should have :db-name")
      ;; Should NOT include primary xtdb
      (is (not-any? #(= "xtdb" (:db-name %)) ns-dbs)
          "Should not include primary xtdb database"))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.db.multi-test)

  ;; Run specific test
  (clojure.test/test-var #'namespace->db-name-test)
  (clojure.test/test-var #'attach-namespace-db!-test)
  (clojure.test/test-var #'database-isolation-test))
