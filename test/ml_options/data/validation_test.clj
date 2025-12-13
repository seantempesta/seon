(ns ml-options.data.validation-test
  "Comprehensive tests for option data validation.

  Test Coverage:
  - Property-based tests for Greeks range validation
  - Unit tests for edge cases (deep ITM IV=0, positive theta, etc.)
  - Bid/ask sanity checks
  - Delta sign validation
  - Required fields validation
  - Composite record validation
  - Batch filtering"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [ml-options.data.validation :as v]
            [ml-options.generators :as custom-gen]))

;;; ---------------------------------------------------------------------------
;;; Fixtures
;;; ---------------------------------------------------------------------------

(use-fixtures :each
  (fn [f]
    ;; Reset rejection counts before each test
    (v/reset-rejection-counts!)
    ;; Run in quiet mode to avoid log spam during tests
    (binding [v/*quiet* true]
      (f))))

;;; ---------------------------------------------------------------------------
;;; Property-Based Tests - Greeks Range Validation
;;; ---------------------------------------------------------------------------

(defspec delta-within-range-accepted 100
  (prop/for-all [delta (gen/double* {:min -1.0 :max 1.0 :NaN? false :infinite? false})]
                (let [record {:xt/id "test" :option/id "TEST" :greeks/delta delta}
                      result (v/validate-greeks record)]
                  (:valid? result))))

(defspec delta-out-of-range-rejected 100
  (prop/for-all [delta (gen/one-of [(gen/double* {:min -10.0 :max -1.001 :NaN? false :infinite? false})
                                    (gen/double* {:min 1.001 :max 10.0 :NaN? false :infinite? false})])]
                (let [record {:xt/id "test" :option/id "TEST" :greeks/delta delta}
                      result (v/validate-greeks record)]
                  (not (:valid? result)))))

(defspec gamma-non-negative-accepted 100
  (prop/for-all [gamma (gen/double* {:min 0.0 :max 50.0 :NaN? false :infinite? false})]
                (let [record {:xt/id "test" :option/id "TEST" :greeks/gamma gamma}
                      result (v/validate-greeks record)]
                  (:valid? result))))

(defspec gamma-negative-rejected 100
  (prop/for-all [gamma (gen/double* {:min -10.0 :max -0.001 :NaN? false :infinite? false})]
                (let [record {:xt/id "test" :option/id "TEST" :greeks/gamma gamma}
                      result (v/validate-greeks record)]
                  (not (:valid? result)))))

(defspec vega-within-range-accepted 100
  (prop/for-all [vega (gen/double* {:min 0.0 :max 200.0 :NaN? false :infinite? false})]
                (let [record {:xt/id "test" :option/id "TEST" :greeks/vega vega}
                      result (v/validate-greeks record)]
                  (:valid? result))))

(defspec theta-within-range-accepted 100
  (prop/for-all [theta (gen/double* {:min -100.0 :max 10.0 :NaN? false :infinite? false})]
                (let [record {:xt/id "test" :option/id "TEST" :greeks/theta theta}
                      result (v/validate-greeks record)]
                  (:valid? result))))

(defspec rho-within-range-accepted 100
  (prop/for-all [rho (gen/double* {:min -200.0 :max 200.0 :NaN? false :infinite? false})]
                (let [record {:xt/id "test" :option/id "TEST" :greeks/rho rho}
                      result (v/validate-greeks record)]
                  (:valid? result))))

;;; ---------------------------------------------------------------------------
;;; Property-Based Tests - IV Validation
;;; ---------------------------------------------------------------------------

(defspec iv-positive-accepted 100
  (prop/for-all [iv (gen/double* {:min 0.0001 :max 10.0 :NaN? false :infinite? false})]
                (let [record {:xt/id "test" :option/id "TEST" :quote/iv iv :greeks/delta 0.5}
                      result (v/validate-greeks record)]
                  (:valid? result))))

(defspec iv-out-of-range-rejected 100
  (prop/for-all [iv (gen/double* {:min 10.001 :max 100.0 :NaN? false :infinite? false})]
                (let [record {:xt/id "test" :option/id "TEST" :quote/iv iv :greeks/delta 0.5}
                      result (v/validate-greeks record)]
                  (not (:valid? result)))))

;;; ---------------------------------------------------------------------------
;;; Unit Tests - Edge Cases
;;; ---------------------------------------------------------------------------

(deftest deep-itm-zero-iv-test
  (testing "Deep ITM call (delta=1.0) with IV=0.0 should pass"
    (let [record {:xt/id "TEST-ITM-CALL"
                  :option/id "AAPL20250117C00165000"
                  :option/type :call
                  :greeks/delta 1.0
                  :quote/iv 0.0}
          result (v/validate-greeks record)]
      (is (:valid? result) "Deep ITM call with IV=0 should be valid")
      (is (empty? (:errors result)))))

  (testing "Deep ITM put (delta=-1.0) with IV=0.0 should pass"
    (let [record {:xt/id "TEST-ITM-PUT"
                  :option/id "AAPL20250117P00300000"
                  :option/type :put
                  :greeks/delta -1.0
                  :quote/iv 0.0}
          result (v/validate-greeks record)]
      (is (:valid? result) "Deep ITM put with IV=0 should be valid")
      (is (empty? (:errors result)))))

  (testing "Near deep ITM (delta=0.99) with IV=0.0 should pass"
    (let [record {:xt/id "TEST-NEAR-ITM"
                  :option/id "AAPL20250117C00170000"
                  :option/type :call
                  :greeks/delta 0.99
                  :quote/iv 0.0}
          result (v/validate-greeks record)]
      (is (:valid? result) "Near deep ITM with IV=0 should be valid"))))

(deftest atm-zero-iv-test
  (testing "ATM option (delta=0.5) with IV=0.0 should fail"
    (let [record {:xt/id "TEST-ATM-ZERO-IV"
                  :option/id "AAPL20250117C00230000"
                  :option/type :call
                  :greeks/delta 0.5
                  :quote/iv 0.0}
          result (v/validate-greeks record)]
      (is (not (:valid? result)) "ATM with IV=0 should be invalid")
      (is (seq (:errors result)))
      (is (= 1 (:iv-zero-not-deep-itm @v/rejection-counts)))))

  (testing "OTM option (delta=0.1) with IV=0.0 should fail"
    (v/reset-rejection-counts!)
    (let [record {:xt/id "TEST-OTM-ZERO-IV"
                  :option/id "AAPL20250117C00260000"
                  :option/type :call
                  :greeks/delta 0.1
                  :quote/iv 0.0}
          result (v/validate-greeks record)]
      (is (not (:valid? result)) "OTM with IV=0 should be invalid")
      (is (= 1 (:iv-zero-not-deep-itm @v/rejection-counts))))))

(deftest positive-theta-edge-case-test
  (testing "Small positive theta near expiry should pass"
    (let [record {:xt/id "TEST-POS-THETA"
                  :option/id "AAPL20250117P00200000"
                  :option/type :put
                  :greeks/theta 0.5}
          result (v/validate-greeks record)]
      (is (:valid? result) "Small positive theta should be valid")
      (is (empty? (:errors result)))))

  (testing "Large positive theta should pass within max range"
    (let [record {:xt/id "TEST-LARGE-POS-THETA"
                  :option/id "AAPL20250117P00200000"
                  :option/type :put
                  :greeks/theta 9.99}
          result (v/validate-greeks record)]
      (is (:valid? result))))

  (testing "Theta beyond max should fail"
    (let [record {:xt/id "TEST-HUGE-THETA"
                  :option/id "AAPL20250117P00200000"
                  :option/type :put
                  :greeks/theta 11.0}
          result (v/validate-greeks record)
          summary (v/get-rejection-summary)]
      (is (not (:valid? result)))
      (is (pos? (get summary :theta-out-of-range 0))
          (str "Expected rejection tracking, got summary: " summary)))))

(deftest nil-greeks-accepted-test
  (testing "Nil Greeks should pass (optional fields)"
    (let [record {:xt/id "TEST-NIL-GREEKS"
                  :option/id "AAPL20250117C00230000"
                  :greeks/delta nil
                  :greeks/gamma nil
                  :greeks/vega nil
                  :greeks/theta nil
                  :greeks/rho nil
                  :quote/iv nil}
          result (v/validate-greeks record)]
      (is (:valid? result) "Nil Greeks should be valid")
      (is (empty? (:errors result))))))

;;; ---------------------------------------------------------------------------
;;; Unit Tests - Bid/Ask Validation
;;; ---------------------------------------------------------------------------

(deftest bid-ask-validation-test
  (testing "Valid bid < ask passes"
    (let [record {:xt/id "TEST-VALID-QUOTE"
                  :option/id "TEST"
                  :quote/bid 2.5
                  :quote/ask 2.6}
          result (v/validate-bid-ask record)]
      (is (:valid? result))
      (is (empty? (:warnings result)))))

  (testing "bid = ask passes"
    (let [record {:xt/id "TEST-EQUAL-QUOTE"
                  :option/id "TEST"
                  :quote/bid 2.5
                  :quote/ask 2.5}
          result (v/validate-bid-ask record)]
      (is (:valid? result))))

  (testing "bid > ask fails"
    (v/reset-rejection-counts!)
    (let [record {:xt/id "TEST-BAD-QUOTE"
                  :option/id "TEST"
                  :quote/bid 2.8
                  :quote/ask 2.6}
          result (v/validate-bid-ask record)]
      (is (not (:valid? result)))
      (is (= 1 (:bid-greater-than-ask @v/rejection-counts)))))

  (testing "Both nil passes (illiquid option)"
    (let [record {:xt/id "TEST-NO-QUOTE"
                  :option/id "TEST"
                  :quote/bid nil
                  :quote/ask nil}
          result (v/validate-bid-ask record)]
      (is (:valid? result))
      (is (empty? (:warnings result)))))

  (testing "Partial quote (one nil) passes with warning"
    (let [record {:xt/id "TEST-PARTIAL-QUOTE"
                  :option/id "TEST"
                  :quote/bid 2.5
                  :quote/ask nil}
          result (v/validate-bid-ask record)]
      (is (:valid? result))
      (is (seq (:warnings result))))))

(defspec valid-bid-ask-spread-accepted 100
  (prop/for-all [bid (gen/double* {:min 0.01 :max 100.0 :NaN? false :infinite? false})
                 spread (gen/double* {:min 0.01 :max 10.0 :NaN? false :infinite? false})]
                (let [ask (+ bid spread)
                      record {:xt/id "test" :option/id "TEST" :quote/bid bid :quote/ask ask}
                      result (v/validate-bid-ask record)]
                  (:valid? result))))

;;; ---------------------------------------------------------------------------
;;; Unit Tests - Delta Sign Validation
;;; ---------------------------------------------------------------------------

(deftest delta-sign-validation-test
  (testing "Call with positive delta passes"
    (let [record {:xt/id "TEST-CALL-POS"
                  :option/id "TEST"
                  :option/type :call
                  :greeks/delta 0.52}
          result (v/validate-delta-sign record)]
      (is (:valid? result))))

  (testing "Call with zero delta passes"
    (let [record {:xt/id "TEST-CALL-ZERO"
                  :option/id "TEST"
                  :option/type :call
                  :greeks/delta 0.0}
          result (v/validate-delta-sign record)]
      (is (:valid? result))))

  (testing "Call with small negative delta (rounding) passes"
    (let [record {:xt/id "TEST-CALL-TINY-NEG"
                  :option/id "TEST"
                  :option/type :call
                  :greeks/delta -0.0001}
          result (v/validate-delta-sign record)]
      (is (:valid? result))))

  (testing "Call with significantly negative delta fails"
    (v/reset-rejection-counts!)
    (let [record {:xt/id "TEST-CALL-NEG"
                  :option/id "TEST"
                  :option/type :call
                  :greeks/delta -0.3}
          result (v/validate-delta-sign record)]
      (is (not (:valid? result)))
      (is (seq (:errors result)))
      (is (= 1 (:call-negative-delta @v/rejection-counts)))))

  (testing "Put with negative delta passes"
    (let [record {:xt/id "TEST-PUT-NEG"
                  :option/id "TEST"
                  :option/type :put
                  :greeks/delta -0.48}
          result (v/validate-delta-sign record)]
      (is (:valid? result))))

  (testing "Put with zero delta passes"
    (let [record {:xt/id "TEST-PUT-ZERO"
                  :option/id "TEST"
                  :option/type :put
                  :greeks/delta 0.0}
          result (v/validate-delta-sign record)]
      (is (:valid? result))))

  (testing "Put with small positive delta (rounding) passes"
    (let [record {:xt/id "TEST-PUT-TINY-POS"
                  :option/id "TEST"
                  :option/type :put
                  :greeks/delta 0.0001}
          result (v/validate-delta-sign record)]
      (is (:valid? result))))

  (testing "Put with significantly positive delta fails"
    (v/reset-rejection-counts!)
    (let [record {:xt/id "TEST-PUT-POS"
                  :option/id "TEST"
                  :option/type :put
                  :greeks/delta 0.3}
          result (v/validate-delta-sign record)]
      (is (not (:valid? result)))
      (is (seq (:errors result)))
      (is (= 1 (:put-positive-delta @v/rejection-counts)))))

  (testing "Nil delta passes (no validation possible)"
    (let [record {:xt/id "TEST-NIL-DELTA"
                  :option/id "TEST"
                  :option/type :call
                  :greeks/delta nil}
          result (v/validate-delta-sign record)]
      (is (:valid? result)))))

(defspec call-non-negative-delta-accepted 100
  (prop/for-all [delta (gen/double* {:min 0.0 :max 1.0 :NaN? false :infinite? false})]
                (let [record {:xt/id "test" :option/id "TEST" :option/type :call :greeks/delta delta}
                      result (v/validate-delta-sign record)]
                  (:valid? result))))

(defspec put-non-positive-delta-accepted 100
  (prop/for-all [delta (gen/double* {:min -1.0 :max 0.0 :NaN? false :infinite? false})]
                (let [record {:xt/id "test" :option/id "TEST" :option/type :put :greeks/delta delta}
                      result (v/validate-delta-sign record)]
                  (:valid? result))))

;;; ---------------------------------------------------------------------------
;;; Unit Tests - Required Fields Validation
;;; ---------------------------------------------------------------------------

(deftest required-fields-validation-test
  (testing "Record with required fields passes"
    (let [record {:xt/id "TEST-001"
                  :option/id "AAPL20250117C00230000"}
          result (v/validate-required-fields record)]
      (is (:valid? result))
      (is (empty? (:errors result)))))

  (testing "Missing :xt/id fails"
    (v/reset-rejection-counts!)
    (let [record {:option/id "AAPL20250117C00230000"}
          result (v/validate-required-fields record)]
      (is (not (:valid? result)))
      (is (some #(clojure.string/includes? % ":xt/id") (:errors result)))
      (is (= 1 (:missing-xt-id @v/rejection-counts)))))

  (testing "Missing :option/id fails"
    (v/reset-rejection-counts!)
    (let [record {:xt/id "TEST-001"}
          result (v/validate-required-fields record)]
      (is (not (:valid? result)))
      (is (some #(clojure.string/includes? % ":option/id") (:errors result)))
      (is (= 1 (:missing-option-id @v/rejection-counts)))))

  (testing "Missing both required fields fails"
    (v/reset-rejection-counts!)
    (let [record {:asset/ticker "AAPL"}
          result (v/validate-required-fields record)]
      (is (not (:valid? result)))
      (is (= 2 (count (:errors result))))
      (is (= 1 (:missing-xt-id @v/rejection-counts)))
      (is (= 1 (:missing-option-id @v/rejection-counts))))))

;;; ---------------------------------------------------------------------------
;;; Unit Tests - Composite Record Validation
;;; ---------------------------------------------------------------------------

(deftest validate-record-test
  (testing "Fully valid record passes"
    (let [record {:xt/id "TEST-001"
                  :option/id "AAPL20250117C00230000"
                  :option/type :call
                  :quote/bid 2.5
                  :quote/ask 2.6
                  :greeks/delta 0.52
                  :greeks/gamma 0.05
                  :greeks/vega 0.45
                  :greeks/theta -0.15
                  :greeks/rho 0.25
                  :quote/iv 0.35}
          result (v/validate-record record)]
      (is (:valid? result))
      (is (empty? (:errors result)))
      (is (empty? (:warnings result)))))

  (testing "Record with multiple errors fails"
    (v/reset-rejection-counts!)
    (let [record {:xt/id "TEST-002"
                  :option/id "AAPL20250117C00230000"
                  :option/type :call
                  :quote/bid 2.8
                  :quote/ask 2.6  ; bid > ask
                  :greeks/delta -0.5  ; call with negative delta
                  :greeks/gamma -0.1  ; negative gamma
                  :quote/iv 15.0}  ; IV > 10.0
          result (v/validate-record record)]
      (is (not (:valid? result)))
      (is (>= (count (:errors result)) 3))
      (is (pos? (:bid-greater-than-ask @v/rejection-counts)))
      (is (pos? (:call-negative-delta @v/rejection-counts)))))

  (testing "Illiquid option with no quotes passes"
    (let [record {:xt/id "TEST-003"
                  :option/id "AAPL20250117C00500000"
                  :option/type :call
                  :quote/bid nil
                  :quote/ask nil
                  :greeks/delta 0.02}
          result (v/validate-record record)]
      (is (:valid? result))))

  (testing "Deep ITM with IV=0 passes"
    (let [record {:xt/id "TEST-004"
                  :option/id "AAPL20250117C00165000"
                  :option/type :call
                  :quote/bid 69.3
                  :quote/ask 70.6
                  :greeks/delta 1.0
                  :quote/iv 0.0}
          result (v/validate-record record)]
      (is (:valid? result)))))

;;; ---------------------------------------------------------------------------
;;; Unit Tests - Batch Filtering
;;; ---------------------------------------------------------------------------

(deftest filter-valid-records-test
  (testing "Empty list returns empty"
    (let [result (v/filter-valid-records [])]
      (is (empty? result))))

  (testing "All valid records are kept"
    (let [records [{:xt/id "T1" :option/id "AAPL20250117C00230000" :greeks/delta 0.5}
                   {:xt/id "T2" :option/id "SPY20250117P00400000" :greeks/gamma 0.1}
                   {:xt/id "T3" :option/id "NVDA20250117C00500000" :quote/iv 0.3}]
          result (v/filter-valid-records records)]
      (is (= 3 (count result)))))

  (testing "Invalid records are filtered out"
    (let [valid {:xt/id "VALID"
                 :option/id "AAPL20250117C00230000"
                 :option/type :call
                 :greeks/delta 0.5}
          invalid-1 {:xt/id "INVALID-1"
                     :option/id "TEST"
                     :option/type :call
                     :greeks/delta -0.5}  ; bad delta sign
          invalid-2 {:xt/id "INVALID-2"
                     :option/id "TEST"
                     :quote/bid 3.0
                     :quote/ask 2.5}  ; bid > ask
          records [valid invalid-1 invalid-2]
          result (v/filter-valid-records records)]
      (is (= 1 (count result)))
      (is (= "VALID" (:xt/id (first result))))))

  (testing "Mixed valid/invalid filters correctly"
    (let [records [{:xt/id "V1" :option/id "T1" :greeks/delta 0.5}
                   {:xt/id "I1" :option/id "T2" :greeks/delta 2.0}  ; out of range
                   {:xt/id "V2" :option/id "T3" :greeks/gamma 0.1}
                   {:xt/id "I2" :option/id "T4" :greeks/gamma -0.1}  ; negative
                   {:xt/id "V3" :option/id "T5" :quote/iv 0.5}]
          result (v/filter-valid-records records)]
      (is (= 3 (count result)))
      (is (every? #(clojure.string/starts-with? (:xt/id %) "V") result)))))

;;; ---------------------------------------------------------------------------
;;; Unit Tests - Rejection Tracking
;;; ---------------------------------------------------------------------------

(deftest rejection-tracking-test
  (testing "Rejection counts are tracked"
    (let [records [{:xt/id "T1" :option/id "X" :greeks/delta 2.0}  ; delta out of range
                   {:xt/id "T2" :option/id "X" :greeks/delta -2.0}  ; delta out of range
                   {:xt/id "T3" :option/id "X" :quote/bid 3.0 :quote/ask 2.0}  ; bid > ask
                   {:xt/id "T4" :option/id "X" :option/type :call :greeks/delta -0.5}]  ; call neg delta
          _ (v/filter-valid-records records)
          summary (v/get-rejection-summary)]
      (is (>= (get summary :delta-out-of-range 0) 2)
          (str "Expected 2+ delta rejections, got summary: " summary))
      (is (>= (get summary :bid-greater-than-ask 0) 1)
          (str "Expected 1+ bid>ask rejections, got summary: " summary))
      (is (>= (get summary :call-negative-delta 0) 1)
          (str "Expected 1+ call neg delta rejections, got summary: " summary))))

  (testing "Reset clears counts"
    (v/reset-rejection-counts!)
    (is (empty? (v/get-rejection-summary)))))

;;; ---------------------------------------------------------------------------
;;; Integration Tests - Real-World Scenarios
;;; ---------------------------------------------------------------------------

(deftest real-world-scenario-test
  (testing "Typical ATM call option"
    (let [record {:xt/id "AAPL20250117C00230000-2024-11-27T15:56:58Z"
                  :option/id "AAPL20250117C00230000"
                  :option/type :call
                  :option/strike 230.0
                  :quote/bid 5.2
                  :quote/ask 5.4
                  :quote/iv 0.32
                  :greeks/delta 0.52
                  :greeks/gamma 0.05
                  :greeks/vega 0.45
                  :greeks/theta -0.15
                  :greeks/rho 0.25}
          result (v/validate-record record)]
      (is (:valid? result))))

  (testing "Deep OTM put with minimal Greeks"
    (let [record {:xt/id "SPY20250117P00400000-2024-11-27T16:00:00Z"
                  :option/id "SPY20250117P00400000"
                  :option/type :put
                  :option/strike 400.0
                  :quote/bid 0.01
                  :quote/ask 0.02
                  :quote/iv 0.15
                  :greeks/delta -0.02
                  :greeks/gamma 0.001
                  :greeks/vega 0.01
                  :greeks/theta -0.001}
          result (v/validate-record record)]
      (is (:valid? result))))

  (testing "Illiquid far OTM option with no quotes"
    (let [record {:xt/id "NVDA20250117C00900000-2024-11-27T16:00:00Z"
                  :option/id "NVDA20250117C00900000"
                  :option/type :call
                  :option/strike 900.0
                  :quote/bid nil
                  :quote/ask nil
                  :greeks/delta 0.001}
          result (v/validate-record record)]
      (is (:valid? result))))

  (testing "Long-dated LEAPS with high vega"
    (let [record {:xt/id "AAPL20261218C00250000-2024-11-27T16:00:00Z"
                  :option/id "AAPL20261218C00250000"
                  :option/type :call
                  :option/strike 250.0
                  :quote/bid 45.0
                  :quote/ask 47.0
                  :quote/iv 0.45
                  :greeks/delta 0.48
                  :greeks/gamma 0.01
                  :greeks/vega 150.0  ; High vega for long-dated
                  :greeks/theta -0.05
                  :greeks/rho 180.0}  ; High rho for long-dated
          result (v/validate-record record)]
      (is (:valid? result)))))

;;; ---------------------------------------------------------------------------
;;; Edge Case Regression Tests
;;; ---------------------------------------------------------------------------

(deftest edge-case-regression-test
  (testing "Gamma exactly at max boundary"
    (let [record {:xt/id "TEST" :option/id "X" :greeks/gamma 50.0}
          result (v/validate-greeks record)]
      (is (:valid? result))))

  (testing "Theta exactly at min boundary"
    (let [record {:xt/id "TEST" :option/id "X" :greeks/theta -100.0}
          result (v/validate-greeks record)]
      (is (:valid? result))))

  (testing "IV exactly at max boundary"
    (let [record {:xt/id "TEST" :option/id "X" :quote/iv 10.0 :greeks/delta 0.5}
          result (v/validate-greeks record)]
      (is (:valid? result))))

  (testing "Delta exactly at -1.0 boundary"
    (let [record {:xt/id "TEST" :option/id "X" :greeks/delta -1.0}
          result (v/validate-greeks record)]
      (is (:valid? result))))

  (testing "Delta exactly at 1.0 boundary"
    (let [record {:xt/id "TEST" :option/id "X" :greeks/delta 1.0}
          result (v/validate-greeks record)]
      (is (:valid? result))))

  (testing "Vega exactly at 0.0"
    (let [record {:xt/id "TEST" :option/id "X" :greeks/vega 0.0}
          result (v/validate-greeks record)]
      (is (:valid? result))))

  (testing "Theta exactly at 0.0"
    (let [record {:xt/id "TEST" :option/id "X" :greeks/theta 0.0}
          result (v/validate-greeks record)]
      (is (:valid? result)))))
