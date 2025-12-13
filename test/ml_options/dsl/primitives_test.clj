(ns ml-options.dsl.primitives-test
  "Tests for DSL financial calculation primitives.

  Coverage:
  - Helper functions (calculate-percentile, calculate-percentile-rank)
  - Property tests for invariants
  - Integration tests with XTDB"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [ml-options.dsl.primitives :as p]
            [ml-options.test-utils :as tu]
            [xtdb.node :as xtn]
            [xtdb.api :as xt]))

;;; ---------------------------------------------------------------------------
;;; Helper Function Tests (Private Functions via #')
;;; ---------------------------------------------------------------------------

(deftest calculate-percentile-test
  (testing "boundary percentiles"
    (is (= 1 (#'p/calculate-percentile [1 2 3 4 5] 0))
        "0th percentile should return minimum")
    (is (= 5 (#'p/calculate-percentile [1 2 3 4 5] 100))
        "100th percentile should return maximum"))

  (testing "middle percentiles"
    (is (= 3 (#'p/calculate-percentile [1 2 3 4 5] 50))
        "50th percentile of odd-length sequence")
    ;; Note: current impl uses floor, so 50th percentile of [1 2 3 4] = 2
    (is (= 2 (#'p/calculate-percentile [1 2 3 4] 50))
        "50th percentile of even-length sequence (floor behavior)"))

  (testing "empty sequence returns nil"
    (is (nil? (#'p/calculate-percentile [] 50))
        "Empty sequence should return nil"))

  (testing "single value"
    (is (= 42 (#'p/calculate-percentile [42] 0))
        "Single value at 0th percentile")
    (is (= 42 (#'p/calculate-percentile [42] 50))
        "Single value at 50th percentile")
    (is (= 42 (#'p/calculate-percentile [42] 100))
        "Single value at 100th percentile"))

  (testing "unsorted input gets sorted"
    (is (= 1 (#'p/calculate-percentile [5 3 1 4 2] 0))
        "Should sort before calculating")
    (is (= 5 (#'p/calculate-percentile [5 3 1 4 2] 100))
        "Should find max regardless of input order"))

  (testing "duplicate values"
    (is (= 3 (#'p/calculate-percentile [3 3 3 3 3] 50))
        "All same values should return that value")
    (is (= 2 (#'p/calculate-percentile [1 2 2 2 3] 50))
        "Duplicates handled correctly")))

(deftest calculate-percentile-rank-test
  (testing "known distribution"
    ;; [1 2 3 4 5] with value 3: 3 values <= 3 (1,2,3), so 3/5 = 0.6
    (is (= 0.6 (#'p/calculate-percentile-rank [1 2 3 4 5] 3))
        "3 of 5 values are <= 3"))

  (testing "boundary values"
    ;; Value 1: 1 value <= 1, so 1/5 = 0.2
    (is (= 0.2 (#'p/calculate-percentile-rank [1 2 3 4 5] 1))
        "Minimum value should have low rank")
    ;; Value 5: 5 values <= 5, so 5/5 = 1.0
    (is (= 1.0 (#'p/calculate-percentile-rank [1 2 3 4 5] 5))
        "Maximum value should have rank 1.0"))

  (testing "value below all"
    ;; Value 0: 0 values <= 0, so 0/5 = 0.0
    (is (= 0.0 (#'p/calculate-percentile-rank [1 2 3 4 5] 0))
        "Value below all should have rank 0.0"))

  (testing "value above all"
    ;; Value 10: 5 values <= 10, so 5/5 = 1.0
    (is (= 1.0 (#'p/calculate-percentile-rank [1 2 3 4 5] 10))
        "Value above all should have rank 1.0"))

  (testing "empty sequence returns nil"
    (is (nil? (#'p/calculate-percentile-rank [] 5))
        "Empty sequence should return nil"))

  (testing "nil current-value returns nil"
    (is (nil? (#'p/calculate-percentile-rank [1 2 3] nil))
        "Nil value should return nil"))

  (testing "single value"
    ;; Value in sequence: 1 value <= 42, so 1/1 = 1.0
    (is (= 1.0 (#'p/calculate-percentile-rank [42] 42))
        "Single value equals input should be 1.0")
    ;; Value below: 0 values <= 41, so 0/1 = 0.0
    (is (= 0.0 (#'p/calculate-percentile-rank [42] 41))
        "Value below single should be 0.0"))

  (testing "duplicates in values"
    ;; [1 2 2 2 3] with value 2: 4 values <= 2 (1,2,2,2), so 4/5 = 0.8
    (is (= 0.8 (#'p/calculate-percentile-rank [1 2 2 2 3] 2))
        "Duplicates count as separate values")))

;;; ---------------------------------------------------------------------------
;;; Property-Based Tests for Invariants
;;; ---------------------------------------------------------------------------

(defspec percentile-rank-always-in-range 100
  (prop/for-all [values (gen/vector (gen/double* {:min 0.0 :max 100.0 :NaN? false :infinite? false}) 1 50)]
                (let [current (first values)
                      rank (#'p/calculate-percentile-rank values current)]
                  (and (number? rank)
                       (>= rank 0.0)
                       (<= rank 1.0)))))

(defspec percentile-rank-max-is-one 50
  (prop/for-all [values (gen/vector (gen/double* {:min 0.0 :max 100.0 :NaN? false :infinite? false}) 1 50)]
                (let [max-val (apply max values)
                      rank (#'p/calculate-percentile-rank values max-val)]
                  (= 1.0 rank))))

(defspec percentile-result-in-value-range 100
  (prop/for-all [values (gen/vector (gen/double* {:min 0.0 :max 100.0 :NaN? false :infinite? false}) 1 50)
                 p (gen/choose 0 100)]
                (let [result (#'p/calculate-percentile values p)
                      min-val (apply min values)
                      max-val (apply max values)]
                  (and (>= result min-val)
                       (<= result max-val)))))

(defspec percentile-monotonic 50
  (prop/for-all [values (gen/vector (gen/double* {:min 0.0 :max 100.0 :NaN? false :infinite? false}) 2 20)]
    ;; For any two percentiles p1 < p2, result at p1 <= result at p2
                (let [p1 25
                      p2 75
                      result1 (#'p/calculate-percentile values p1)
                      result2 (#'p/calculate-percentile values p2)]
                  (<= result1 result2))))

;;; ---------------------------------------------------------------------------
;;; Integration Tests with XTDB
;;; ---------------------------------------------------------------------------

(deftest iv-rank-with-no-data-test
  (testing "iv-rank returns 0.5 for unknown ticker (no data)"
    (with-open [node (xtn/start-node)]
      (is (= 0.5 (p/iv-rank node "NONEXISTENT"))
          "No data should return neutral 0.5"))))

(deftest iv-percentile-with-no-data-test
  (testing "iv-percentile returns 0.20 for unknown ticker (no data)"
    (with-open [node (xtn/start-node)]
      (is (= 0.20 (p/iv-percentile node "NONEXISTENT" 50))
          "No data should return default 0.20"))))

;; NOTE: Integration test with data deferred - XTDB v2 tx format investigation needed.
;; Helper function tests provide core calculation logic coverage.

;;; ---------------------------------------------------------------------------
;;; Edge Case Tests
;;; ---------------------------------------------------------------------------

(deftest primitives-handle-keyword-ticker-test
  (testing "primitives accept keyword ticker"
    (with-open [node (xtn/start-node)]
      ;; Should not throw, should return default
      (is (= 0.5 (p/iv-rank node :SPY))
          "Keyword ticker should work"))))

(deftest primitives-handle-as-of-option-test
  (testing "primitives accept :as-of option"
    (with-open [node (xtn/start-node)]
      (let [as-of (java.time.Instant/parse "2024-06-15T12:00:00Z")]
        ;; Should not throw
        (is (= 0.5 (p/iv-rank node "SPY" 252 {:as-of as-of}))
            ":as-of option should be accepted")))))

;;; ---------------------------------------------------------------------------
;;; Bug Fix Tests (TDD - Written Before Fixes)
;;; ---------------------------------------------------------------------------

(deftest vanna-uses-actual-expiry-test
  (testing "vanna calculation uses actual days to expiry, not hardcoded 30"
    (with-open [node (xtn/start-node)]
      ;; Insert two options with different expiry dates
      ;; Use dates far in the future to ensure they're valid
      (let [near-expiry (java.time.LocalDate/parse "2026-01-15")
            far-expiry (java.time.LocalDate/parse "2026-06-20")
            ;; Mock option data - use :option/id as the query key
            near-opt {:xt/id "AAPL260115C00150000"
                      :option/id "AAPL260115C00150000"
                      :asset/ticker "AAPL"
                      :option/strike 150.0
                      :option/type :call
                      :option/expiry near-expiry
                      :greeks/delta 0.5
                      :greeks/gamma 0.01
                      :greeks/theta -0.05
                      :greeks/vega 0.15
                      :quote/iv 0.30
                      :quote/bid 5.0
                      :quote/ask 5.1}
            far-opt {:xt/id "AAPL260620C00150000"
                     :option/id "AAPL260620C00150000"
                     :asset/ticker "AAPL"
                     :option/strike 150.0
                     :option/type :call
                     :option/expiry far-expiry
                     :greeks/delta 0.5
                     :greeks/gamma 0.01
                     :greeks/theta -0.05
                     :greeks/vega 0.15
                     :quote/iv 0.30
                     :quote/bid 8.0
                     :quote/ask 8.1}]

        ;; Insert test data using XTDB v2 format (execute-tx waits for completion)
        (xt/execute-tx node [[:put-docs :option-greeks near-opt]])
        (xt/execute-tx node [[:put-docs :option-greeks far-opt]])

        ;; Calculate vanna for both options
        (let [vanna-near (p/vanna node "AAPL260115C00150000")
              vanna-far (p/vanna node "AAPL260620C00150000")]

          ;; Vanna should be different because time-to-expiry is different
          ;; (even though delta and IV are the same)
          ;; Vanna formula includes sqrt(T), so far expiry should have higher vanna
          (is (not= vanna-near vanna-far)
              "Vanna should differ for options with different expiries")

          (is (> vanna-far vanna-near)
              "Far expiry option should have higher vanna (more time sensitivity)"))))))

(deftest term-structure-slope-normalized-by-time-test
  (testing "term-structure-slope divides by time span, not count"
    (with-open [node (xtn/start-node)]
      ;; Create options at different expirations with known IV spread
      ;; If we have near IV = 0.20, far IV = 0.30, 30 days apart
      ;; Slope should be (0.30 - 0.20) / 30 = 0.0033... per day
      ;; NOT (0.30 - 0.20) / 2 = 0.05 (which is what the bug does)
      (let [near-date (java.time.LocalDate/parse "2026-01-15")
            far-date (java.time.LocalDate/parse "2026-02-14")  ; 30 days later
            days-between (.between java.time.temporal.ChronoUnit/DAYS near-date far-date)
            _ (is (= 30 days-between) "Sanity check: dates are 30 days apart")

            ;; Create ATM options at both dates
            near-opt {:xt/id "SPY260115C00500000"
                      :option/id "SPY260115C00500000"
                      :asset/ticker "SPY"
                      :option/strike 500.0
                      :option/type :call
                      :option/expiry near-date
                      :greeks/delta 0.5
                      :quote/iv 0.20}  ; 20% IV
            far-opt {:xt/id "SPY260214C00500000"
                     :option/id "SPY260214C00500000"
                     :asset/ticker "SPY"
                     :option/strike 500.0
                     :option/type :call
                     :option/expiry far-date
                     :greeks/delta 0.5
                     :quote/iv 0.30}]  ; 30% IV

        (xt/execute-tx node [[:put-docs :option-greeks near-opt]])
        (xt/execute-tx node [[:put-docs :option-greeks far-opt]])

        (let [slope (p/term-structure-slope node "SPY")]
          ;; Correct slope: (0.30 - 0.20) / 30 days = 0.0033...
          ;; Buggy slope: (0.30 - 0.20) / 2 expirations = 0.05
          (is (< slope 0.01)
              "Slope should be small when normalized by days, not large from dividing by count")
          (is (> slope 0.003)
              "Slope should be approximately 0.0033 = (0.30 - 0.20) / 30")
          (is (< slope 0.004)
              "Slope should be approximately 0.0033 = (0.30 - 0.20) / 30"))))))

;; NOTE: iv-rank and iv-percentile lookback parameter test skipped
;; The lookback parameter is currently ignored (queries all history).
;; Implementing proper temporal filtering requires complex XTDB v2 queries
;; with system-time ranges. This is documented as a known limitation.
;; See iv-rank/iv-percentile docstrings for details.

(deftest implied-correlation-unimplemented-test
  (testing "implied-correlation returns nil (marked as unimplemented)"
    (with-open [node (xtn/start-node)]
      ;; implied-correlation currently has hardcoded volatilities
      ;; Rather than return incorrect values, it should return nil
      (let [result (p/implied-correlation node "SPX" ["AAPL" "MSFT" "GOOGL"] [0.33 0.33 0.34])]
        (is (nil? result)
            "implied-correlation should return nil until properly implemented")))))
