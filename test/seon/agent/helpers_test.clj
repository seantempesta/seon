(ns seon.agent.helpers-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.agent.helpers :as h]
            [seon.db.multi :as multi]
            [seon.orchestrator.nrepl :refer [*ctx*]]
            [seon.test-utils :refer [with-test-node *test-node*]]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(use-fixtures :each with-test-node)

;;; ---------------------------------------------------------------------------
;;; Helper Tests
;;; ---------------------------------------------------------------------------

(deftest sql-query-test
  (multi/ensure-namespace-db! *test-node* 'test.helpers)
  (let [conn (multi/create-namespace-connection *test-node* 'test.helpers)
        ctx (atom {:seon.agent/db conn
                   :seon.agent/namespace 'test.helpers})]
    (try
      (testing "Query without params"
        (binding [*ctx* ctx]
          (h/sql! "INSERT INTO query_test (_id, name) VALUES (?, ?)" "q1" "Alice")
          (let [result (h/sql "SELECT * FROM query_test")]
            (is (= 1 (count result)))
            (is (= "Alice" (:name (first result)))))))

      (testing "Query with single param"
        (binding [*ctx* ctx]
          (h/sql! "INSERT INTO query_test2 (_id, name, score) VALUES (?, ?, ?)" "q1" "Bob" 100)
          (h/sql! "INSERT INTO query_test2 (_id, name, score) VALUES (?, ?, ?)" "q2" "Carol" 90)
          (let [result (h/sql "SELECT * FROM query_test2 WHERE name = ?" "Bob")]
            (is (= 1 (count result)))
            (is (= "Bob" (:name (first result)))))))

      (testing "Query with multiple params"
        (binding [*ctx* ctx]
          (h/sql! "INSERT INTO query_test3 (_id, name, score) VALUES (?, ?, ?)" "q1" "Dave" 100)
          (h/sql! "INSERT INTO query_test3 (_id, name, score) VALUES (?, ?, ?)" "q2" "Eve" 80)
          (let [result (h/sql "SELECT * FROM query_test3 WHERE name = ? AND score > ?" "Dave" 90)]
            (is (= 1 (count result)))
            (is (= "Dave" (:name (first result)))))))
      (finally
        (.close conn)))))

(deftest sql!-write-test
  (multi/ensure-namespace-db! *test-node* 'test.helpers2)
  (let [conn (multi/create-namespace-connection *test-node* 'test.helpers2)
        ctx (atom {:seon.agent/db conn
                   :seon.agent/namespace 'test.helpers2})]
    (try
      (testing "INSERT creates table implicitly"
        (binding [*ctx* ctx]
          (let [tx-result (h/sql! "INSERT INTO implicit_table (_id, value) VALUES (?, ?)"
                                  "row1" 42)]
            (is (some? (:tx-id tx-result))))
          (let [result (h/sql "SELECT * FROM implicit_table")]
            (is (= 1 (count result)))
            (is (= 42 (:value (first result)))))))

      (testing "UPDATE modifies existing row"
        (binding [*ctx* ctx]
          (h/sql! "INSERT INTO update_test (_id, status) VALUES (?, ?)" "u1" "pending")
          (h/sql! "UPDATE update_test SET status = ? WHERE _id = ?" "done" "u1")
          (let [result (h/sql "SELECT * FROM update_test WHERE _id = ?" "u1")]
            (is (= "done" (:status (first result)))))))

      (testing "DELETE removes row"
        (binding [*ctx* ctx]
          (h/sql! "INSERT INTO delete_test (_id, name) VALUES (?, ?)" "d1" "ToDelete")
          (is (= 1 (count (h/sql "SELECT * FROM delete_test"))))
          (h/sql! "DELETE FROM delete_test WHERE _id = ?" "d1")
          (is (= 0 (count (h/sql "SELECT * FROM delete_test"))))))
      (finally
        (.close conn)))))

(deftest sql-batch!-test
  (multi/ensure-namespace-db! *test-node* 'test.helpers3)
  (let [conn (multi/create-namespace-connection *test-node* 'test.helpers3)
        ctx (atom {:seon.agent/db conn
                   :seon.agent/namespace 'test.helpers3})]
    (try
      (testing "Batch insert multiple rows"
        (binding [*ctx* ctx]
          (h/sql-batch! "INSERT INTO batch_test (_id, name, score) VALUES (?, ?, ?)"
                        ["b1" "Alice" 100]
                        ["b2" "Bob" 90]
                        ["b3" "Carol" 80])
          (let [result (h/sql "SELECT * FROM batch_test ORDER BY score DESC")]
            (is (= 3 (count result)))
            (is (= ["Alice" "Bob" "Carol"] (mapv :name result))))))
      (finally
        (.close conn)))))

(deftest no-db-error-test
  (testing "Helpful error when no db in ctx"
    (let [ctx (atom {})]
      (binding [*ctx* ctx]
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"No database connection"
             (h/sql "SELECT 1")))
        (is (thrown-with-msg?
             clojure.lang.ExceptionInfo
             #"No database connection"
             (h/sql! "INSERT INTO foo (_id) VALUES (?)" "x")))))))
