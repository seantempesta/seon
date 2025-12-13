(ns seon.web.stats-test
  "Tests for database statistics queries."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [seon.web.stats :as stats]
            [seon.test-utils :refer [with-test-node *test-node*]]
            [xtdb.api :as xt])
  (:import [java.time LocalDate Instant]))

(use-fixtures :each with-test-node)

(defn insert-test-data!
  "Insert test option-greeks records for statistics testing."
  [node records]
  (xt/execute-tx node
                 [(into [:put-docs :option-greeks] records)]))

(deftest get-total-records-test
  (testing "returns 0 for empty database"
    (is (= 0 (stats/get-total-records *test-node*))))

  (testing "counts all records"
    (insert-test-data! *test-node*
                       [{:xt/id "AAPL-1"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-01")
                         :quote/timestamp (Instant/parse "2024-01-01T15:00:00Z")}
                        {:xt/id "SPY-1"
                         :asset/ticker "SPY"
                         :quote/date (LocalDate/parse "2024-01-01")
                         :quote/timestamp (Instant/parse "2024-01-01T15:00:00Z")}
                        {:xt/id "AAPL-2"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-02")
                         :quote/timestamp (Instant/parse "2024-01-02T15:00:00Z")}])
    (is (= 3 (stats/get-total-records *test-node*)))))

(deftest get-date-range-test
  (testing "returns nil for empty database"
    (is (nil? (stats/get-date-range *test-node*))))

  (testing "returns earliest and latest dates"
    (insert-test-data! *test-node*
                       [{:xt/id "AAPL-1"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-15")
                         :quote/timestamp (Instant/parse "2024-01-15T15:00:00Z")}
                        {:xt/id "AAPL-2"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-01")
                         :quote/timestamp (Instant/parse "2024-01-01T15:00:00Z")}
                        {:xt/id "AAPL-3"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-30")
                         :quote/timestamp (Instant/parse "2024-01-30T15:00:00Z")}])
    (let [result (stats/get-date-range *test-node*)]
      (is (= (LocalDate/parse "2024-01-01") (:earliest result)))
      (is (= (LocalDate/parse "2024-01-30") (:latest result))))))

(deftest get-distinct-symbols-test
  (testing "returns empty vector for empty database"
    (is (= [] (stats/get-distinct-symbols *test-node*))))

  (testing "returns sorted list of unique symbols"
    (insert-test-data! *test-node*
                       [{:xt/id "AAPL-1"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-01")
                         :quote/timestamp (Instant/parse "2024-01-01T15:00:00Z")}
                        {:xt/id "AAPL-2"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-02")
                         :quote/timestamp (Instant/parse "2024-01-02T15:00:00Z")}
                        {:xt/id "SPY-1"
                         :asset/ticker "SPY"
                         :quote/date (LocalDate/parse "2024-01-01")
                         :quote/timestamp (Instant/parse "2024-01-01T15:00:00Z")}
                        {:xt/id "NVDA-1"
                         :asset/ticker "NVDA"
                         :quote/date (LocalDate/parse "2024-01-01")
                         :quote/timestamp (Instant/parse "2024-01-01T15:00:00Z")}])
    (let [result (stats/get-distinct-symbols *test-node*)]
      (is (= ["AAPL" "NVDA" "SPY"] result)))))

(deftest get-latest-timestamp-test
  (testing "returns nil for empty database"
    (is (nil? (stats/get-latest-timestamp *test-node*))))

  (testing "returns most recent timestamp"
    (insert-test-data! *test-node*
                       [{:xt/id "AAPL-1"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-01")
                         :quote/timestamp (Instant/parse "2024-01-01T15:00:00Z")}
                        {:xt/id "AAPL-2"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-02")
                         :quote/timestamp (Instant/parse "2024-01-02T15:00:00Z")}
                        {:xt/id "AAPL-3"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-03")
                         :quote/timestamp (Instant/parse "2024-01-03T15:00:00Z")}])
    (is (= (Instant/parse "2024-01-03T15:00:00Z")
           (stats/get-latest-timestamp *test-node*)))))

(deftest get-database-stats-test
  (testing "returns empty stats for empty database"
    (let [result (stats/get-database-stats *test-node*)]
      (is (= 0 (:total-records result)))
      (is (= [] (:by-symbol result)))
      (is (nil? (:date-range result)))
      (is (= [] (:distinct-symbols result)))
      (is (= 0 (:symbols-count result)))
      (is (nil? (:latest-timestamp result)))
      (is (true? (:empty? result)))))

  (testing "returns comprehensive stats for populated database"
    (insert-test-data! *test-node*
                       [{:xt/id "AAPL-1"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-01")
                         :quote/timestamp (Instant/parse "2024-01-01T15:00:00Z")}
                        {:xt/id "AAPL-2"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-02")
                         :quote/timestamp (Instant/parse "2024-01-02T15:00:00Z")}
                        {:xt/id "SPY-1"
                         :asset/ticker "SPY"
                         :quote/date (LocalDate/parse "2024-01-01")
                         :quote/timestamp (Instant/parse "2024-01-01T15:00:00Z")}])
    (let [result (stats/get-database-stats *test-node*)
          by-symbol-map (into {} (map (fn [m] [(:asset/ticker m) (:count m)]) (:by-symbol result)))]
      (is (= 3 (:total-records result)))
      (is (= {"AAPL" 2 "SPY" 1} by-symbol-map))
      (is (= (LocalDate/parse "2024-01-01") (get-in result [:date-range :min-date])))
      (is (= (LocalDate/parse "2024-01-02") (get-in result [:date-range :max-date])))
      (is (= ["AAPL" "SPY"] (:distinct-symbols result)))
      (is (= 2 (:symbols-count result)))
      (is (= (Instant/parse "2024-01-02T15:00:00Z") (:latest-timestamp result)))
      (is (false? (:empty? result))))))

(deftest stats-caching-test
  (testing "caches stats for performance"
    (insert-test-data! *test-node*
                       [{:xt/id "AAPL-1"
                         :asset/ticker "AAPL"
                         :quote/date (LocalDate/parse "2024-01-01")
                         :quote/timestamp (Instant/parse "2024-01-01T15:00:00Z")}])

    ;; First call should populate cache
    (let [stats1 (stats/get-cached-stats *test-node*)]
      (is (= 1 (:total-records stats1)))

      ;; Insert more data
      (insert-test-data! *test-node*
                         [{:xt/id "SPY-1"
                           :asset/ticker "SPY"
                           :quote/date (LocalDate/parse "2024-01-01")
                           :quote/timestamp (Instant/parse "2024-01-01T15:00:00Z")}])

      ;; Second call should still return cached value (before TTL)
      (let [stats2 (stats/get-cached-stats *test-node*)]
        (is (= 1 (:total-records stats2)) "Should return cached value"))

      ;; Invalidate cache
      (stats/invalidate-cache!)

      ;; Third call should return fresh data
      (let [stats3 (stats/get-cached-stats *test-node*)]
        (is (= 2 (:total-records stats3)) "Should return fresh data after invalidation")))))
