(ns seon.data.ingest-test
  "Tests for options data ingestion pipeline.

   Tests the current API:
   - thetadata->xtdb-doc (transformation)
   - plan-daily-work (work item generation)
   - execute-daily-work-item! (fetch one day - mocked)"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [seon.data.ingest :as ingest]
            [seon.data.validation :as validation])
  (:import [java.time LocalDate Instant ZoneId]
           [java.time.format DateTimeFormatter]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn quiet-fixture [f]
  (binding [validation/*quiet* true]
    (validation/reset-rejection-counts!)
    (f)))

(use-fixtures :each quiet-fixture)

;;; ---------------------------------------------------------------------------
;;; Mock Data
;;; ---------------------------------------------------------------------------

(def sample-thetadata-record
  "Sample record as returned by ThetaData API (post-transformation in thetadata.clj).
   Includes :xt/id and :xt/valid-from as set by thetadata.clj."
  {:xt/id "AAPL20250117C00230000-2024-11-27T15:56:58.017Z"
   :xt/valid-from (Instant/parse "2024-11-27T22:00:00Z")  ; 5pm ET on quote date
   :asset/ticker "AAPL"
   :option/id "AAPL20250117C00230000"
   :option/strike 230.0
   :option/type :call
   :option/expiry (Instant/parse "2025-01-17T22:00:00Z")
   :quote/date (LocalDate/of 2024 11 27)
   :quote/timestamp (Instant/parse "2024-11-27T15:56:58.017Z")
   :quote/iv 0.35
   :quote/bid 2.50
   :quote/ask 2.60
   :quote/close 2.55
   :greeks/delta 0.52
   :greeks/gamma 0.05
   :greeks/theta -0.15
   :greeks/vega 0.45
   :greeks/rho 0.25
   :underlying/price 234.50})

(def sample-deep-itm-record
  "Deep ITM record with IV=0.0 (valid edge case)"
  {:xt/id "AAPL20250117C00165000-2024-11-27T15:56:58.017Z"
   :xt/valid-from (Instant/parse "2024-11-27T22:00:00Z")
   :asset/ticker "AAPL"
   :option/id "AAPL20250117C00165000"
   :option/strike 165.0
   :option/type :call
   :option/expiry (Instant/parse "2025-01-17T22:00:00Z")
   :quote/date (LocalDate/of 2024 11 27)
   :quote/timestamp (Instant/parse "2024-11-27T15:56:58.017Z")
   :quote/iv 0.0  ; IV=0 allowed for deep ITM
   :quote/bid 69.30
   :quote/ask 70.60
   :quote/close 69.95
   :greeks/delta 1.0  ; Deep ITM
   :greeks/gamma 0.0
   :greeks/theta 0.0
   :greeks/vega 0.0
   :greeks/rho 0.0
   :underlying/price 234.50})

(def sample-invalid-record
  "Record with invalid data (bid > ask)"
  {:xt/id "AAPL20250117C00230000-2024-11-27T15:56:58.017Z"
   :xt/valid-from (Instant/parse "2024-11-27T22:00:00Z")
   :asset/ticker "AAPL"
   :option/id "AAPL20250117C00230000"
   :option/strike 230.0
   :option/type :call
   :option/expiry (Instant/parse "2025-01-17T22:00:00Z")
   :quote/date (LocalDate/of 2024 11 27)
   :quote/timestamp (Instant/parse "2024-11-27T15:56:58.017Z")
   :quote/iv 0.35
   :quote/bid 3.00  ; bid > ask - invalid!
   :quote/ask 2.60
   :greeks/delta 0.52
   :underlying/price 234.50})

;;; ---------------------------------------------------------------------------
;;; thetadata->xtdb-doc Tests
;;; ---------------------------------------------------------------------------

(deftest thetadata->xtdb-doc-basic-test
  (testing "Validates and returns valid record unchanged"
    (let [result (ingest/thetadata->xtdb-doc sample-thetadata-record)]
      (is (some? result) "Should return validated document")
      (is (string? (:xt/id result)) "Should have string ID")
      (is (instance? Instant (:xt/valid-from result)) "Should have valid-from instant")
      (is (= "AAPL" (:asset/ticker result)))
      (is (= "AAPL20250117C00230000" (:option/id result)))
      (is (= 0.52 (:greeks/delta result)))))

  (testing "Preserves ID and valid-from from input"
    (let [result (ingest/thetadata->xtdb-doc sample-thetadata-record)]
      (is (= "AAPL20250117C00230000-2024-11-27T15:56:58.017Z" (:xt/id result))
          "Should preserve input ID")
      (is (= (Instant/parse "2024-11-27T22:00:00Z") (:xt/valid-from result))
          "Should preserve input valid-from")))

  (testing "Same input produces same output (idempotent)"
    (let [result1 (ingest/thetadata->xtdb-doc sample-thetadata-record)
          result2 (ingest/thetadata->xtdb-doc sample-thetadata-record)]
      (is (= result1 result2)
          "Same input should produce identical output"))))

(deftest thetadata->xtdb-doc-deep-itm-test
  (testing "Deep ITM with IV=0.0 passes validation"
    (let [result (ingest/thetadata->xtdb-doc sample-deep-itm-record)]
      (is (some? result) "Deep ITM with IV=0 should be valid")
      (is (= 0.0 (:quote/iv result)))
      (is (= 1.0 (:greeks/delta result))))))

(deftest thetadata->xtdb-doc-invalid-test
  (testing "Invalid record (bid > ask) returns nil"
    (let [result (ingest/thetadata->xtdb-doc sample-invalid-record)]
      (is (nil? result) "Record with bid > ask should be rejected"))))

(deftest thetadata->xtdb-doc-nil-handling-test
  (testing "Handles nil optional fields gracefully"
    (let [record-with-nils (assoc sample-thetadata-record
                                  :greeks/gamma nil
                                  :greeks/vega nil
                                  :quote/close nil)
          result (ingest/thetadata->xtdb-doc record-with-nils)]
      (is (some? result) "Should accept nil optional fields")
      (is (nil? (:greeks/gamma result)))
      (is (nil? (:greeks/vega result))))))

;;; ---------------------------------------------------------------------------
;;; plan-daily-work Tests
;;; ---------------------------------------------------------------------------

(deftest plan-daily-work-basic-test
  (testing "Generates work items for date range"
    (let [start (LocalDate/of 2024 11 25)
          end (LocalDate/of 2024 11 27)
          items (ingest/plan-daily-work "AAPL" start end #{})]
      (is (= 3 (count items)) "Should have 3 days")
      (is (every? #(= :pending (:status %)) items) "All should be pending")
      (is (every? #(= "AAPL" (:symbol %)) items) "All should have symbol")))

  (testing "Respects completed dates set"
    (let [start (LocalDate/of 2024 11 25)
          end (LocalDate/of 2024 11 27)
          completed #{(LocalDate/of 2024 11 26)} ; Middle day done
          items (ingest/plan-daily-work "AAPL" start end completed)]
      (is (= 2 (count items)) "Should skip completed day")
      (is (not-any? #(= (LocalDate/of 2024 11 26) (:date %)) items)
          "Should not include Nov 26")))

  (testing "Returns empty when all dates completed"
    (let [start (LocalDate/of 2024 11 25)
          end (LocalDate/of 2024 11 27)
          completed #{(LocalDate/of 2024 11 25)
                      (LocalDate/of 2024 11 26)
                      (LocalDate/of 2024 11 27)}
          items (ingest/plan-daily-work "AAPL" start end completed)]
      (is (empty? items) "Should return empty for fully completed range")))

  (testing "Single day range works"
    (let [date (LocalDate/of 2024 11 27)
          items (ingest/plan-daily-work "AAPL" date date #{})]
      (is (= 1 (count items)) "Single day should produce 1 item")
      (is (= date (:date (first items)))))))

(deftest plan-daily-work-order-test
  (testing "Work items are in chronological order"
    (let [start (LocalDate/of 2024 11 20)
          end (LocalDate/of 2024 11 30)
          items (ingest/plan-daily-work "SPY" start end #{})
          dates (map :date items)]
      (is (= dates (sort dates)) "Dates should be sorted chronologically"))))

;;; ---------------------------------------------------------------------------
;;; Integration-style Tests
;;; ---------------------------------------------------------------------------

(deftest transformation-pipeline-test
  (testing "Full validation pipeline produces valid XTDB document"
    (let [doc (ingest/thetadata->xtdb-doc sample-thetadata-record)]
      ;; Check required XTDB fields preserved
      (is (contains? doc :xt/id))
      (is (contains? doc :xt/valid-from))

      ;; Check ID is preserved from input
      (is (= "AAPL20250117C00230000-2024-11-27T15:56:58.017Z" (:xt/id doc)))

      ;; Check all data fields preserved
      (is (= "AAPL" (:asset/ticker doc)))
      (is (= 230.0 (:option/strike doc)))
      (is (= :call (:option/type doc)))
      (is (= 0.35 (:quote/iv doc)))
      (is (= 0.52 (:greeks/delta doc))))))

(deftest batch-transformation-test
  (testing "Multiple records transform correctly"
    (let [records [sample-thetadata-record
                   sample-deep-itm-record
                   sample-invalid-record] ; One invalid
          results (keep ingest/thetadata->xtdb-doc records)]
      (is (= 2 (count results)) "Should have 2 valid results (1 rejected)")
      (is (every? :xt/id results) "All results should have IDs")
      (is (every? :xt/valid-from results) "All should have valid-from"))))

(comment
  ;; Run tests
  (clojure.test/run-tests 'seon.data.ingest-test)

  ;; Test transformation
  (ingest/thetadata->xtdb-doc sample-thetadata-record)

  ;; Test work planning
  (ingest/plan-daily-work "AAPL"
                          (LocalDate/of 2024 11 25)
                          (LocalDate/of 2024 11 27)
                          #{}))
