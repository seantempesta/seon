(ns ml-options.db.queries-test
  "Tests for XTDB query functions, especially temporal queries."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ml-options.db.queries :as queries]
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

(defn insert-option-greeks-at-time
  "Insert option greeks data at a specific valid-time.

  Args:
    node - XTDB node
    ticker - Stock ticker
    iv - Implied volatility
    delta - Delta value
    valid-from - Valid-time instant when this data becomes valid

  Returns:
    Transaction result"
  [node ticker iv delta valid-from]
  (let [id (str ticker "-" (.toEpochMilli valid-from))]
    (xt/execute-tx node
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
                      :greeks/delta delta
                      :greeks/gamma 0.05
                      :greeks/theta -0.1
                      :greeks/vega 10.0
                      :xt/valid-from valid-from}]])))

;;; ---------------------------------------------------------------------------
;;; Historical IVs Tests
;;; ---------------------------------------------------------------------------

(deftest historical-ivs-returns-current-data-test
  (testing "Returns IV values from current valid-time"
    (let [now (Instant/now)
          ;; Insert two different options at current time (different strikes to avoid ID collision)
          _ (xt/execute-tx *test-node*
                           [[:put-docs :option-greeks
                             {:xt/id "AAPL-OPT1"
                              :asset/ticker "AAPL"
                              :option/id "AAPL-OPT1"
                              :option/strike 150.0
                              :option/type :call
                              :option/expiry (.plus now 30 ChronoUnit/DAYS)
                              :quote/iv 0.25
                              :quote/bid 1.0
                              :quote/ask 1.5
                              :greeks/delta 0.5
                              :greeks/gamma 0.05
                              :greeks/theta -0.1
                              :greeks/vega 10.0
                              :xt/valid-from now}]
                            [:put-docs :option-greeks
                             {:xt/id "AAPL-OPT2"
                              :asset/ticker "AAPL"
                              :option/id "AAPL-OPT2"
                              :option/strike 155.0
                              :option/type :call
                              :option/expiry (.plus now 30 ChronoUnit/DAYS)
                              :quote/iv 0.28
                              :quote/bid 1.2
                              :quote/ask 1.7
                              :greeks/delta 0.48
                              :greeks/gamma 0.05
                              :greeks/theta -0.1
                              :greeks/vega 10.0
                              :xt/valid-from now}]])
          results (queries/historical-ivs *test-node* "AAPL" 1)]

      (is (seq results) "Should return results")
      (is (every? number? results) "All results should be numbers")
      (is (>= (count results) 2) "Should return at least 2 IV values"))))

(deftest historical-ivs-temporal-query-test
  (testing "Returns IV values across multiple valid-times within lookback period"
    (let [now (Instant/now)
          five-days-ago (.minus now 5 ChronoUnit/DAYS)
          three-days-ago (.minus now 3 ChronoUnit/DAYS)
          one-day-ago (.minus now 1 ChronoUnit/DAYS)

          ;; Insert data at different valid-times
          _ (insert-option-greeks-at-time *test-node* "SPY" 0.20 0.5 five-days-ago)
          _ (insert-option-greeks-at-time *test-node* "SPY" 0.22 0.5 three-days-ago)
          _ (insert-option-greeks-at-time *test-node* "SPY" 0.25 0.5 one-day-ago)

          ;; Query with 7-day lookback (should get all 3)
          results-7d (queries/historical-ivs *test-node* "SPY" 7)

          ;; Query with 2-day lookback (should get only the most recent)
          results-2d (queries/historical-ivs *test-node* "SPY" 2)]

      (is (>= (count results-7d) 3) "7-day lookback should return at least 3 values")
      (is (>= (count results-2d) 1) "2-day lookback should return at least 1 value")
      (is (< (count results-2d) (count results-7d)) "Shorter lookback should return fewer values"))))

(deftest historical-ivs-filters-by-delta-test
  (testing "Only returns ATM options (delta near 0.5)"
    (let [now (Instant/now)

          ;; Insert ATM option (delta = 0.5)
          _ (insert-option-greeks-at-time *test-node* "NVDA" 0.30 0.5 now)

          ;; Insert deep ITM option (delta = 0.9, should be filtered out)
          _ (xt/execute-tx *test-node*
                           [[:put-docs :option-greeks
                             {:xt/id "NVDA-ITM"
                              :asset/ticker "NVDA"
                              :option/id "NVDA-ITM"
                              :option/strike 100.0
                              :option/type :call
                              :option/expiry (.plus now 30 ChronoUnit/DAYS)
                              :quote/iv 0.35
                              :quote/bid 1.0
                              :quote/ask 1.5
                              :greeks/delta 0.9  ;; Deep ITM, should be filtered
                              :greeks/gamma 0.05
                              :greeks/theta -0.1
                              :greeks/vega 10.0
                              :xt/valid-from now}]])

          results (queries/historical-ivs *test-node* "NVDA" 1)]

      (is (seq results) "Should return ATM results")
      ;; Should only have the ATM option (0.30), not the ITM (0.35)
      (is (some #(= 0.30 %) results) "Should include ATM IV")
      (is (not (some #(= 0.35 %) results)) "Should not include deep ITM IV"))))

(deftest historical-ivs-isolates-by-ticker-test
  (testing "Returns IV values only for specified ticker"
    (let [now (Instant/now)

          ;; Insert data for AAPL
          _ (insert-option-greeks-at-time *test-node* "AAPL" 0.25 0.5 now)

          ;; Insert data for SPY (different ticker)
          _ (insert-option-greeks-at-time *test-node* "SPY" 0.15 0.5 now)

          aapl-results (queries/historical-ivs *test-node* "AAPL" 1)
          spy-results (queries/historical-ivs *test-node* "SPY" 1)]

      (is (seq aapl-results) "Should return AAPL results")
      (is (seq spy-results) "Should return SPY results")
      (is (some #(= 0.25 %) aapl-results) "AAPL should have IV 0.25")
      (is (not (some #(= 0.15 %) aapl-results)) "AAPL should not have SPY's IV"))))

(deftest historical-ivs-empty-when-no-data-test
  (testing "Returns empty sequence when no data exists"
    (let [results (queries/historical-ivs *test-node* "NONEXISTENT" 30)]
      (is (empty? results) "Should return empty for non-existent ticker"))))

(deftest historical-ivs-lookback-boundary-test
  (testing "Lookback period boundary is respected"
    (let [now (Instant/now)
          ten-days-ago (.minus now 10 ChronoUnit/DAYS)
          five-days-ago (.minus now 5 ChronoUnit/DAYS)

          ;; Insert old data (outside 7-day lookback)
          _ (insert-option-greeks-at-time *test-node* "MSFT" 0.18 0.5 ten-days-ago)

          ;; Insert recent data (inside 7-day lookback)
          _ (insert-option-greeks-at-time *test-node* "MSFT" 0.22 0.5 five-days-ago)

          results (queries/historical-ivs *test-node* "MSFT" 7)]

      ;; Should only get the recent data
      (is (seq results) "Should return results")
      (is (some #(= 0.22 %) results) "Should include recent IV")
      ;; The old data should be filtered out by the lookback period
      (is (not (some #(= 0.18 %) results)) "Should not include data older than lookback"))))

;;; ---------------------------------------------------------------------------
;;; Options Chain Tests
;;; ---------------------------------------------------------------------------

(deftest options-chain-basic-test
  (testing "Returns options chain for a ticker"
    (let [now (Instant/now)
          _ (insert-option-greeks-at-time *test-node* "AAPL" 0.25 0.5 now)
          results (queries/options-chain *test-node* "AAPL")]

      (is (seq results) "Should return results")
      (is (= 0.25 (:quote/iv (first results))) "Should have correct IV"))))

(deftest options-chain-filters-by-expiry-test
  (testing "Filters options by expiration date when provided"
    (let [now (Instant/now)
          expiry1 (.plus now 30 ChronoUnit/DAYS)
          expiry2 (.plus now 60 ChronoUnit/DAYS)

          ;; Insert options with different expiries
          _ (xt/execute-tx *test-node*
                           [[:put-docs :option-greeks
                             {:xt/id "AAPL-30D"
                              :asset/ticker "AAPL"
                              :option/id "AAPL-30D"
                              :option/strike 150.0
                              :option/type :call
                              :option/expiry expiry1
                              :quote/iv 0.25
                              :quote/bid 1.0
                              :quote/ask 1.5
                              :greeks/delta 0.5
                              :greeks/gamma 0.05
                              :greeks/theta -0.1
                              :greeks/vega 10.0
                              :xt/valid-from now}]
                            [:put-docs :option-greeks
                             {:xt/id "AAPL-60D"
                              :asset/ticker "AAPL"
                              :option/id "AAPL-60D"
                              :option/strike 150.0
                              :option/type :call
                              :option/expiry expiry2
                              :quote/iv 0.28
                              :quote/bid 1.2
                              :quote/ask 1.7
                              :greeks/delta 0.5
                              :greeks/gamma 0.04
                              :greeks/theta -0.1
                              :greeks/vega 12.0
                              :xt/valid-from now}]])

          ;; Query with expiry filter
          results (queries/options-chain *test-node* "AAPL" {:expiry expiry1})]

      (is (seq results) "Should return results")
      ;; Should only get one result (the 30D option), not both
      (is (= 1 (count results)) "Should only return options with the specified expiry")
      (is (= 0.25 (:quote/iv (first results))) "Should return the correct option"))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'ml-options.db.queries-test)

  ;; Run specific test
  (clojure.test/test-var #'historical-ivs-temporal-query-test)
  (clojure.test/test-var #'historical-ivs-lookback-boundary-test))
