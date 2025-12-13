(ns ml-options.db.node-test
  "Integration tests for XTDB node wrapper functions.

  Tests the core database operations: query routing, entity retrieval,
  entity history, and transaction execution."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ml-options.db.node :as node]
            [ml-options.test-utils :refer [with-test-node *test-node*]]
            [xtdb.api :as xt])
  (:import [java.time Instant]
           [java.time.temporal ChronoUnit]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(use-fixtures :each with-test-node)

;;; ---------------------------------------------------------------------------
;;; Test Data Helpers
;;; ---------------------------------------------------------------------------

(defn insert-test-option
  "Insert a test option-greeks document.

  Args:
    node - XTDB node
    id - Document ID
    ticker - Asset ticker
    iv - Implied volatility

  Returns:
    Transaction result"
  [node id ticker iv]
  (node/execute-tx! node
                    [[:put-docs :option-greeks
                      {:xt/id id
                       :asset/ticker ticker
                       :option/id id
                       :option/strike 150.0
                       :option/type :call
                       :option/expiry (.plus (Instant/now) 30 ChronoUnit/DAYS)
                       :quote/iv iv
                       :quote/bid 1.0
                       :quote/ask 1.5
                       :greeks/delta 0.5
                       :greeks/gamma 0.05
                       :greeks/theta -0.1
                       :greeks/vega 10.0}]]))

(defn insert-test-option-at-time
  "Insert a test option-greeks document at a specific valid-time.

  Args:
    node - XTDB node
    id - Document ID
    ticker - Asset ticker
    iv - Implied volatility
    valid-from - Valid-time instant

  Returns:
    Transaction result"
  [node id ticker iv valid-from]
  (node/execute-tx! node
                    [[:put-docs :option-greeks
                      {:xt/id id
                       :asset/ticker ticker
                       :option/id id
                       :option/strike 150.0
                       :option/type :call
                       :option/expiry (.plus valid-from 30 ChronoUnit/DAYS)
                       :quote/iv iv
                       :quote/bid 1.0
                       :quote/ask 1.5
                       :greeks/delta 0.5
                       :greeks/gamma 0.05
                       :greeks/theta -0.1
                       :greeks/vega 10.0
                       :xt/valid-from valid-from}]]))

;;; ---------------------------------------------------------------------------
;;; Query Function Tests
;;; ---------------------------------------------------------------------------

(deftest query-xtql-basic-test
  (testing "query routes XTQL expressions correctly"
    (let [_ (insert-test-option *test-node* "AAPL-1" "AAPL" 0.25)
          _ (insert-test-option *test-node* "AAPL-2" "AAPL" 0.28)
          results (node/query *test-node*
                              '(from :option-greeks [asset/ticker quote/iv]))]

      (is (seq results) "Should return results")
      (is (every? map? results) "Results should be maps")
      (is (every? :asset/ticker results) "Results should have ticker field")
      (is (every? :quote/iv results) "Results should have iv field")
      (is (= 2 (count results)) "Should return both options"))))

(deftest query-xtql-with-filter-test
  (testing "query with XTQL filter clause"
    (let [_ (insert-test-option *test-node* "AAPL-1" "AAPL" 0.25)
          _ (insert-test-option *test-node* "SPY-1" "SPY" 0.15)
          results (node/query *test-node*
                              '(-> (from :option-greeks [asset/ticker quote/iv])
                                   (where (= asset/ticker "AAPL"))))]

      (is (= 1 (count results)) "Should filter to AAPL only")
      (is (= "AAPL" (:asset/ticker (first results))) "Should return AAPL")
      (is (= 0.25 (:quote/iv (first results))) "Should have correct IV"))))

(deftest query-xtql-template-test
  (testing "query with xt/template for dynamic values"
    (let [_ (insert-test-option *test-node* "AAPL-1" "AAPL" 0.25)
          _ (insert-test-option *test-node* "SPY-1" "SPY" 0.15)
          ticker "SPY"
          results (node/query *test-node*
                              (xt/template
                               (-> (from :option-greeks [asset/ticker quote/iv])
                                   (where (= asset/ticker ~ticker)))))]

      (is (= 1 (count results)) "Should filter to SPY only")
      (is (= "SPY" (:asset/ticker (first results))) "Should return SPY")
      (is (= 0.15 (:quote/iv (first results))) "Should have correct IV"))))

(deftest query-sql-string-test
  (testing "query routes SQL strings correctly"
    (let [_ (insert-test-option *test-node* "AAPL-1" "AAPL" 0.25)
          results (node/query *test-node*
                              "SELECT asset$ticker, quote$iv FROM option_greeks WHERE asset$ticker = 'AAPL'")]

      (is (seq results) "Should return results")
      (is (every? map? results) "Results should be maps")
      ;; SQL returns snake_case by default, but our wrapper uses kebab-case-keyword
      (is (or (:asset/ticker (first results))
              (:asset-ticker (first results)))
          "Should have ticker field"))))

(deftest query-sql-with-args-test
  (testing "query with SQL parameterized query"
    ;; NOTE: XTDB v2 doesn't support ? placeholders in SQL
    ;; Use literal values or XTQL with xt/template instead
    (let [_ (insert-test-option *test-node* "AAPL-1" "AAPL" 0.25)
          _ (insert-test-option *test-node* "SPY-1" "SPY" 0.15)
          ;; Use literal value in SQL
          results (node/query *test-node*
                              "SELECT asset$ticker, quote$iv FROM option_greeks WHERE asset$ticker = 'SPY'")]

      (is (seq results) "Should return results")
      (is (= 1 (count results)) "Should filter to SPY only"))))

(deftest query-invalid-type-test
  (testing "query throws on invalid query type"
    (is (thrown-with-msg? Exception #"Unknown query type"
                          (node/query *test-node* 123))
        "Should throw on numeric query")
    (is (thrown-with-msg? Exception #"Unknown query type"
                          (node/query *test-node* {:invalid "map"}))
        "Should throw on map query")))

(deftest query-empty-results-test
  (testing "query returns empty vector when no results"
    (let [results (node/query *test-node*
                              '(from :option-greeks [asset/ticker]))]
      (is (vector? results) "Should return vector")
      (is (empty? results) "Should be empty when no data"))))

;;; ---------------------------------------------------------------------------
;;; Entity Function Tests
;;; ---------------------------------------------------------------------------

(deftest entity-basic-test
  (testing "entity retrieves document by ID"
    (let [_ (insert-test-option *test-node* "AAPL-1" "AAPL" 0.25)
          result (node/entity *test-node* :option-greeks "AAPL-1")]

      (is (some? result) "Should return result")
      (is (map? result) "Result should be a map")
      (is (= "AAPL-1" (:xt/id result)) "Should have correct ID")
      (is (= "AAPL" (:asset/ticker result)) "Should have correct ticker")
      (is (= 0.25 (:quote/iv result)) "Should have correct IV")
      (is (= 150.0 (:option/strike result)) "Should have all fields"))))

(deftest entity-not-found-test
  (testing "entity returns nil when ID not found"
    (let [result (node/entity *test-node* :option-greeks "NONEXISTENT")]
      (is (nil? result) "Should return nil for non-existent ID"))))

(deftest entity-wrong-table-test
  (testing "entity returns nil when ID exists in different table"
    (let [_ (insert-test-option *test-node* "AAPL-1" "AAPL" 0.25)
          result (node/entity *test-node* :wrong-table "AAPL-1")]
      (is (nil? result) "Should return nil when querying wrong table"))))

(deftest entity-with-temporal-opts-test
  (testing "entity retrieves document at specific valid-time"
    (let [now (Instant/now)
          past (.minus now 5 ChronoUnit/DAYS)

          ;; Insert document at past valid-time
          _ (insert-test-option-at-time *test-node* "AAPL-1" "AAPL" 0.25 past)

          ;; Query at current time (should return the document)
          result-now (node/entity *test-node* :option-greeks "AAPL-1" {:current-time now})

          ;; Query before the document existed (should return nil)
          result-before (node/entity *test-node* :option-greeks "AAPL-1"
                                     {:current-time (.minus past 1 ChronoUnit/DAYS)})]

      (is (some? result-now) "Should find document at current time")
      (is (= 0.25 (:quote/iv result-now)) "Should have correct IV")
      (is (nil? result-before) "Should not find document before its valid-time"))))

;;; ---------------------------------------------------------------------------
;;; Entity History Function Tests
;;; ---------------------------------------------------------------------------

(deftest entity-history-basic-test
  (testing "entity-history retrieves all versions of a document"
    (let [now (Instant/now)
          past (.minus now 5 ChronoUnit/DAYS)

          ;; Insert initial version
          _ (insert-test-option-at-time *test-node* "AAPL-1" "AAPL" 0.25 past)

          ;; Update with new version
          _ (insert-test-option-at-time *test-node* "AAPL-1" "AAPL" 0.30 now)

          history (node/entity-history *test-node* :option-greeks "AAPL-1")]

      (is (seq history) "Should return history")
      (is (>= (count history) 2) "Should have at least 2 versions")
      (let [ivs (set (map :quote/iv history))]
        (is (contains? ivs 0.25) "Should include old version")
        (is (contains? ivs 0.30) "Should include new version")))))

(deftest entity-history-single-version-test
  (testing "entity-history works with single version"
    (let [_ (insert-test-option *test-node* "AAPL-1" "AAPL" 0.25)
          history (node/entity-history *test-node* :option-greeks "AAPL-1")]

      (is (seq history) "Should return history")
      (is (= 1 (count history)) "Should have exactly 1 version")
      (is (= 0.25 (:quote/iv (first history))) "Should have correct IV"))))

(deftest entity-history-not-found-test
  (testing "entity-history returns empty sequence for non-existent ID"
    (let [history (node/entity-history *test-node* :option-greeks "NONEXISTENT")]
      (is (empty? history) "Should return empty sequence"))))

(deftest entity-history-temporal-opts-test
  (testing "entity-history respects temporal options"
    (let [now (Instant/now)
          five-days-ago (.minus now 5 ChronoUnit/DAYS)
          three-days-ago (.minus now 3 ChronoUnit/DAYS)
          one-day-ago (.minus now 1 ChronoUnit/DAYS)

          ;; Insert 3 versions over time
          _ (insert-test-option-at-time *test-node* "AAPL-1" "AAPL" 0.20 five-days-ago)
          _ (insert-test-option-at-time *test-node* "AAPL-1" "AAPL" 0.25 three-days-ago)
          _ (insert-test-option-at-time *test-node* "AAPL-1" "AAPL" 0.30 one-day-ago)

          ;; Get all history using :all-time
          full-history (node/entity-history *test-node* :option-greeks "AAPL-1"
                                            {:for-valid-time :all-time
                                             :for-system-time :all-time})]

      (is (>= (count full-history) 3) "Full history should have all versions")
      ;; Verify we got different IV values
      (let [ivs (set (map :quote/iv full-history))]
        (is (>= (count ivs) 3) "Should have at least 3 different IV values")))))

;;; ---------------------------------------------------------------------------
;;; Transaction Execution Tests
;;; ---------------------------------------------------------------------------

(deftest execute-tx-put-docs-test
  (testing "execute-tx! inserts documents synchronously"
    (let [tx-result (node/execute-tx! *test-node*
                                      [[:put-docs :option-greeks
                                        {:xt/id "TEST-1"
                                         :asset/ticker "TEST"
                                         :option/id "TEST-1"
                                         :option/strike 100.0
                                         :option/type :put
                                         :option/expiry (Instant/now)
                                         :quote/iv 0.35
                                         :quote/bid 2.0
                                         :quote/ask 2.5
                                         :greeks/delta -0.5
                                         :greeks/gamma 0.05
                                         :greeks/theta -0.1
                                         :greeks/vega 10.0}]])

          ;; Verify transaction completed
          entity (node/entity *test-node* :option-greeks "TEST-1")]

      (is (some? tx-result) "Transaction should return result")
      (is (:tx-id tx-result) "Should have transaction ID")
      (is (:system-time tx-result) "Should have system time")
      (is (some? entity) "Document should be retrievable immediately")
      (is (= "TEST" (:asset/ticker entity)) "Document should have correct data"))))

(deftest execute-tx-multiple-docs-test
  (testing "execute-tx! handles multiple documents in one transaction"
    (let [_ (node/execute-tx! *test-node*
                              [[:put-docs :option-greeks
                                {:xt/id "BATCH-1"
                                 :asset/ticker "BATCH"
                                 :option/id "BATCH-1"
                                 :option/strike 100.0
                                 :option/type :call
                                 :option/expiry (Instant/now)
                                 :quote/iv 0.20
                                 :quote/bid 1.0
                                 :quote/ask 1.5
                                 :greeks/delta 0.5
                                 :greeks/gamma 0.05
                                 :greeks/theta -0.1
                                 :greeks/vega 10.0}]
                               [:put-docs :option-greeks
                                {:xt/id "BATCH-2"
                                 :asset/ticker "BATCH"
                                 :option/id "BATCH-2"
                                 :option/strike 105.0
                                 :option/type :call
                                 :option/expiry (Instant/now)
                                 :quote/iv 0.22
                                 :quote/bid 1.2
                                 :quote/ask 1.7
                                 :greeks/delta 0.48
                                 :greeks/gamma 0.05
                                 :greeks/theta -0.1
                                 :greeks/vega 10.0}]])

          results (node/query *test-node*
                              '(-> (from :option-greeks [xt/id asset/ticker])
                                   (where (= asset/ticker "BATCH"))))]

      (is (= 2 (count results)) "Both documents should be inserted")
      (is (= #{"BATCH-1" "BATCH-2"} (set (map :xt/id results)))
          "Should have both IDs"))))

(deftest execute-tx-delete-docs-test
  (testing "execute-tx! deletes documents"
    (let [;; Insert document
          _ (insert-test-option *test-node* "DELETE-ME" "DEL" 0.25)

          ;; Verify it exists
          before (node/entity *test-node* :option-greeks "DELETE-ME")

          ;; Delete it
          _ (node/execute-tx! *test-node*
                              [[:delete-docs :option-greeks "DELETE-ME"]])

          ;; Verify it's gone
          after (node/entity *test-node* :option-greeks "DELETE-ME")]

      (is (some? before) "Document should exist before delete")
      (is (nil? after) "Document should not exist after delete"))))

(deftest execute-tx-update-test
  (testing "execute-tx! updates existing documents"
    (let [;; Insert initial version
          _ (insert-test-option *test-node* "UPDATE-1" "UPD" 0.25)

          ;; Verify initial value
          before (node/entity *test-node* :option-greeks "UPDATE-1")

          ;; Update with new IV
          _ (insert-test-option *test-node* "UPDATE-1" "UPD" 0.35)

          ;; Verify updated value
          after (node/entity *test-node* :option-greeks "UPDATE-1")]

      (is (= 0.25 (:quote/iv before)) "Should have old IV before update")
      (is (= 0.35 (:quote/iv after)) "Should have new IV after update")
      (is (= (:xt/id before) (:xt/id after)) "Should be same document ID"))))

(deftest execute-tx-temporal-put-test
  (testing "execute-tx! respects xt/valid-from for temporal inserts"
    (let [now (Instant/now)
          past (.minus now 5 ChronoUnit/DAYS)

          ;; Insert at past valid-time
          _ (node/execute-tx! *test-node*
                              [[:put-docs :option-greeks
                                {:xt/id "TEMPORAL-1"
                                 :asset/ticker "TEMP"
                                 :option/id "TEMPORAL-1"
                                 :option/strike 100.0
                                 :option/type :call
                                 :option/expiry (.plus past 30 ChronoUnit/DAYS)
                                 :quote/iv 0.25
                                 :quote/bid 1.0
                                 :quote/ask 1.5
                                 :greeks/delta 0.5
                                 :greeks/gamma 0.05
                                 :greeks/theta -0.1
                                 :greeks/vega 10.0
                                 :xt/valid-from past}]])

          ;; Query at current time (should find it)
          result-now (node/entity *test-node* :option-greeks "TEMPORAL-1" {:current-time now})

          ;; Query before valid-from (should not find it)
          result-before (node/entity *test-node* :option-greeks "TEMPORAL-1"
                                     {:current-time (.minus past 1 ChronoUnit/DAYS)})]

      (is (some? result-now) "Should find document at current time")
      (is (nil? result-before) "Should not find document before valid-from"))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'ml-options.db.node-test)

  ;; Run specific test
  (clojure.test/test-var #'query-xtql-basic-test)
  (clojure.test/test-var #'entity-history-basic-test)
  (clojure.test/test-var #'execute-tx-put-docs-test))
