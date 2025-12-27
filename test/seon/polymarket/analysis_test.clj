(ns seon.polymarket.analysis-test
  "Tests for Polymarket analysis functions.

  Uses small mock data sets rather than the full 171k record file."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.polymarket.analysis :as analysis]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def sample-trades
  "Sample trading activity for testing."
  [{:type "TRADE" :side "BUY" :usdcSize 100.0 :size 200 :price 0.5
    :timestamp 1735000000 :conditionId "0xabc" :slug "test-market-1"
    :title "Test Market 1" :outcome "Yes"}
   {:type "TRADE" :side "SELL" :usdcSize 50.0 :size 100 :price 0.5
    :timestamp 1735100000 :conditionId "0xabc" :slug "test-market-1"
    :title "Test Market 1" :outcome "Yes"}
   {:type "TRADE" :side "BUY" :usdcSize 75.0 :size 150 :price 0.5
    :timestamp 1735200000 :conditionId "0xdef" :slug "test-market-2"
    :title "Test Market 2" :outcome "No"}
   {:type "REDEEM" :usdcSize 120.0 :size 120
    :timestamp 1735300000 :conditionId "0xabc" :slug "test-market-1"
    :title "Test Market 1" :outcome "Yes"}])

(def multi-day-trades
  "Trades spanning multiple days for time-based tests."
  [{:type "TRADE" :side "BUY" :usdcSize 100.0 :size 100
    :timestamp 1735084800 :conditionId "0xabc" :slug "market-1"
    :title "Market 1" :outcome "Yes"}  ;; 2024-12-25
   {:type "TRADE" :side "BUY" :usdcSize 200.0 :size 200
    :timestamp 1735171200 :conditionId "0xabc" :slug "market-1"
    :title "Market 1" :outcome "Yes"}  ;; 2024-12-26
   {:type "TRADE" :side "BUY" :usdcSize 150.0 :size 150
    :timestamp 1735171300 :conditionId "0xabc" :slug "market-1"
    :title "Market 1" :outcome "No"}   ;; 2024-12-26
   {:type "TRADE" :side "SELL" :usdcSize 50.0 :size 50
    :timestamp 1735257600 :conditionId "0xdef" :slug "market-2"
    :title "Market 2" :outcome "Yes"}]) ;; 2024-12-27

;;; ---------------------------------------------------------------------------
;;; summarize-activity tests
;;; ---------------------------------------------------------------------------

(deftest summarize-activity-test
  (testing "returns nil for empty data"
    (is (nil? (analysis/summarize-activity [])))
    (is (nil? (analysis/summarize-activity nil))))

  (testing "counts total records"
    (is (= 4 (:total (analysis/summarize-activity sample-trades)))))

  (testing "groups by type"
    (let [summary (analysis/summarize-activity sample-trades)]
      (is (= {"TRADE" 3 "REDEEM" 1} (:by-type summary)))))

  (testing "groups by side"
    (let [summary (analysis/summarize-activity sample-trades)]
      (is (= {"BUY" 2 "SELL" 1} (:by-side summary)))))

  (testing "calculates date range"
    (let [summary (analysis/summarize-activity sample-trades)]
      (is (= 1735000000 (get-in summary [:date-range :earliest-timestamp])))
      (is (= 1735300000 (get-in summary [:date-range :latest-timestamp])))))

  (testing "counts unique markets"
    (is (= 2 (:unique-markets (analysis/summarize-activity sample-trades)))))

  (testing "counts unique outcomes"
    (is (= 2 (:unique-outcomes (analysis/summarize-activity sample-trades))))))

;;; ---------------------------------------------------------------------------
;;; group-by-* tests
;;; ---------------------------------------------------------------------------

(deftest group-by-market-test
  (testing "groups records by conditionId"
    (let [grouped (analysis/group-by-market sample-trades)]
      (is (= 2 (count grouped)))
      (is (= 3 (count (get grouped "0xabc"))))
      (is (= 1 (count (get grouped "0xdef")))))))

(deftest group-by-type-test
  (testing "groups records by type keyword"
    (let [grouped (analysis/group-by-type sample-trades)]
      (is (= #{:TRADE :REDEEM} (set (keys grouped))))
      (is (= 3 (count (:TRADE grouped))))
      (is (= 1 (count (:REDEEM grouped)))))))

(deftest group-by-slug-test
  (testing "groups records by slug"
    (let [grouped (analysis/group-by-slug sample-trades)]
      (is (= 2 (count grouped)))
      (is (= 3 (count (get grouped "test-market-1"))))
      (is (= 1 (count (get grouped "test-market-2")))))))

;;; ---------------------------------------------------------------------------
;;; calculate-totals tests
;;; ---------------------------------------------------------------------------

(deftest calculate-totals-test
  (testing "sums volume correctly"
    (let [totals (analysis/calculate-totals sample-trades)]
      (is (= 345.0 (:total-volume-usdc totals)))))

  (testing "sums shares correctly"
    (let [totals (analysis/calculate-totals sample-trades)]
      (is (= 570 (:total-shares totals)))))

  (testing "counts trades and redeems"
    (let [totals (analysis/calculate-totals sample-trades)]
      (is (= 3 (:total-trades totals)))
      (is (= 1 (:total-redeems totals)))))

  (testing "separates buy/sell volume"
    (let [totals (analysis/calculate-totals sample-trades)]
      (is (= 175.0 (:buy-volume-usdc totals)))
      (is (= 50.0 (:sell-volume-usdc totals)))))

  (testing "counts buy/sell trades"
    (let [totals (analysis/calculate-totals sample-trades)]
      (is (= 2 (:buy-count totals)))
      (is (= 1 (:sell-count totals))))))

;;; ---------------------------------------------------------------------------
;;; market-summary tests
;;; ---------------------------------------------------------------------------

(deftest market-summary-test
  (let [market-records (filter #(= "0xabc" (:conditionId %)) sample-trades)
        summary (analysis/market-summary market-records)]

    (testing "includes market metadata"
      (is (= "Test Market 1" (:title summary)))
      (is (= "test-market-1" (:slug summary)))
      (is (= "0xabc" (:condition-id summary))))

    (testing "counts records correctly"
      (is (= 3 (:total-records summary)))
      (is (= 2 (:total-trades summary)))
      (is (= 1 (:total-redeems summary))))

    (testing "sums volume"
      (is (= 270.0 (:volume-usdc summary))))

    (testing "lists outcomes"
      (is (= ["Yes"] (vec (:outcomes summary)))))))

;;; ---------------------------------------------------------------------------
;;; top-markets tests
;;; ---------------------------------------------------------------------------

(deftest top-markets-by-volume-test
  (testing "returns markets sorted by volume descending"
    (let [top (analysis/top-markets-by-volume sample-trades 2)]
      (is (= 2 (count top)))
      (is (= "test-market-1" (:slug (first top))))
      (is (= "test-market-2" (:slug (second top))))
      (is (> (:volume-usdc (first top))
             (:volume-usdc (second top)))))))

(deftest top-markets-by-trades-test
  (testing "returns markets sorted by trade count descending"
    (let [top (analysis/top-markets-by-trades sample-trades 2)]
      (is (= 2 (count top)))
      (is (= "test-market-1" (:slug (first top))))
      (is (> (:total-trades (first top))
             (:total-trades (second top)))))))

;;; ---------------------------------------------------------------------------
;;; Time-based analysis tests
;;; ---------------------------------------------------------------------------

(deftest group-by-date-test
  (testing "groups records by date string"
    (let [grouped (analysis/group-by-date multi-day-trades)]
      (is (= 3 (count grouped)))
      (is (= 1 (count (get grouped "2024-12-25"))))
      (is (= 2 (count (get grouped "2024-12-26"))))
      (is (= 1 (count (get grouped "2024-12-27")))))))

(deftest daily-volume-test
  (testing "calculates volume per day"
    (let [volume (analysis/daily-volume multi-day-trades)]
      (is (= 100.0 (get volume "2024-12-25")))
      (is (= 350.0 (get volume "2024-12-26")))
      (is (= 50.0 (get volume "2024-12-27")))))

  (testing "returns sorted map"
    (let [volume (analysis/daily-volume multi-day-trades)]
      (is (= ["2024-12-25" "2024-12-26" "2024-12-27"] (keys volume))))))

(deftest daily-trade-count-test
  (testing "counts trades per day"
    (let [counts (analysis/daily-trade-count multi-day-trades)]
      (is (= 1 (get counts "2024-12-25")))
      (is (= 2 (get counts "2024-12-26")))
      (is (= 1 (get counts "2024-12-27"))))))

;;; ---------------------------------------------------------------------------
;;; outcome-summary tests
;;; ---------------------------------------------------------------------------

(deftest outcome-summary-test
  (let [market-records (filter #(= "0xabc" (:conditionId %)) sample-trades)
        summary (analysis/outcome-summary market-records)]

    (testing "summarizes by outcome"
      (is (contains? summary "Yes")))

    (testing "counts buys and sells"
      (is (= 1 (get-in summary ["Yes" :buys])))
      (is (= 1 (get-in summary ["Yes" :sells]))))

    (testing "sums volume by side"
      (is (= 100.0 (get-in summary ["Yes" :buy-volume])))
      (is (= 50.0 (get-in summary ["Yes" :sell-volume]))))))
