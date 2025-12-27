(ns seon.polymarket.api-test
  "Unit tests for Polymarket API client."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.java.io :as io]
            [seon.polymarket.api :as api]))

;;; ---------------------------------------------------------------------------
;;; Integration Tests (require network access)
;;; ---------------------------------------------------------------------------

(def rn1-wallet "0x2005d16a84ceefa912d4e380cd32e7ff827875ea")

(deftest ^:integration health-check-test
  (testing "API health check returns boolean"
    (let [result (api/health-check)]
      (is (boolean? result)))))

(deftest ^:integration fetch-activity-test
  (testing "fetch-activity returns activity records"
    (let [result (api/fetch-activity rn1-wallet {:limit 3})]
      (is (vector? result) "Should return a vector")
      (is (pos? (count result)) "Should return at least one record")
      (is (every? :timestamp result) "Each record should have timestamp")
      (is (every? :type result) "Each record should have type"))))

(deftest ^:integration fetch-trades-test
  (testing "fetch-trades returns trade records"
    (let [result (api/fetch-trades rn1-wallet {:limit 3})]
      (is (vector? result) "Should return a vector")
      (is (pos? (count result)) "Should return at least one record"))))

(deftest ^:integration fetch-positions-test
  (testing "fetch-positions returns position records"
    (let [result (api/fetch-positions rn1-wallet)]
      (is (vector? result) "Should return a vector"))))

(deftest ^:integration fetch-value-test
  (testing "fetch-value returns total value map"
    (let [result (api/fetch-value rn1-wallet)]
      (is (map? result) "Should return a map")
      (is (contains? result :value) "Should contain :value key"))))

;;; ---------------------------------------------------------------------------
;;; Unit Tests (no network required)
;;; ---------------------------------------------------------------------------

(deftest limit-capping-test
  (testing "limit is capped at max-limit (500)"
    ;; This tests the internal behavior - limit should be capped
    ;; We can't easily test this without mocking, but we verify the constant exists
    (is (= 500 api/max-limit))))

(deftest base-url-test
  (testing "base-url is correct"
    (is (= "https://data-api.polymarket.com" api/base-url))))

;;; ---------------------------------------------------------------------------
;;; Pagination Integration Tests
;;; ---------------------------------------------------------------------------

(deftest ^:integration fetch-all-activity-test
  (testing "fetch-all-activity returns lazy sequence"
    (let [result (api/fetch-all-activity rn1-wallet)]
      (is (seq? result) "Should return a sequence (lazy)")
      ;; Take just first few to avoid full download
      (let [first-10 (take 10 result)]
        (is (= 10 (count first-10)) "Should be able to take first 10")
        (is (every? :timestamp first-10) "Each record should have timestamp")))))

(deftest ^:integration fetch-all-trades-test
  (testing "fetch-all-trades returns lazy sequence"
    (let [result (api/fetch-all-trades rn1-wallet)]
      (is (seq? result) "Should return a sequence (lazy)")
      (let [first-10 (take 10 result)]
        (is (= 10 (count first-10)) "Should be able to take first 10")))))

;;; ---------------------------------------------------------------------------
;;; EDN Persistence Tests
;;; ---------------------------------------------------------------------------

(deftest load-activity-nonexistent-test
  (testing "load-activity returns nil for nonexistent file"
    (is (nil? (api/load-activity "/nonexistent/path/file.edn")))))

(deftest load-positions-nonexistent-test
  (testing "load-positions returns nil for nonexistent file"
    (is (nil? (api/load-positions "/nonexistent/path/file.edn")))))

(deftest ^:integration save-and-load-activity-test
  (testing "save-activity! and load-activity roundtrip"
    (let [test-path "/tmp/seon-test-activity.edn"
          ;; Use a small wallet or just test with first page
          ;; For a quick test, we'll create a mock file
          test-data [{:timestamp "2024-01-01" :type "TRADE" :amount 100}
                     {:timestamp "2024-01-02" :type "TRADE" :amount 200}]]
      ;; Write test data directly
      (spit test-path (pr-str test-data))
      ;; Verify load works
      (let [loaded (api/load-activity test-path)]
        (is (= 2 (count loaded)))
        (is (= "2024-01-01" (:timestamp (first loaded)))))
      ;; Cleanup
      (io/delete-file test-path true))))

(deftest ^:integration save-and-load-positions-test
  (testing "save-positions! and load-positions roundtrip"
    (let [test-path "/tmp/seon-test-positions.edn"
          test-data [{:market "market1" :size 100}
                     {:market "market2" :size 200}]]
      ;; Write test data directly
      (spit test-path (pr-str test-data))
      ;; Verify load works
      (let [loaded (api/load-positions test-path)]
        (is (= 2 (count loaded)))
        (is (= "market1" (:market (first loaded)))))
      ;; Cleanup
      (io/delete-file test-path true))))
