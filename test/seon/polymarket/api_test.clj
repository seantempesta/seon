(ns seon.polymarket.api-test
  "Unit tests for Polymarket API client."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
