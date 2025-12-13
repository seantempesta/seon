(ns ml-options.data.ingestion-state-test
  "Tests for ingestion state tracking and resumable data loads.

  Tests cover:
  - ID generation (state and progress IDs)
  - Pure functions (get-resume-work, make-state-id, make-progress-id)
  - Database operations (integration tests with fixtures)
  - Edge cases and error handling"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [ml-options.data.ingestion-state :as state]
            [ml-options.test-utils :refer [with-test-node *test-node*]]
            [ml-options.db.node :as node]
            [xtdb.api :as xt])
  (:import [java.time Instant LocalDate]
           [java.time.temporal ChronoUnit]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(use-fixtures :each with-test-node)

;;; ---------------------------------------------------------------------------
;;; Helpers
;;; ---------------------------------------------------------------------------

(defn ->instant
  "Convert temporal types to Instant for comparison.
  XTDB sometimes returns ZonedDateTime instead of Instant."
  [x]
  (cond
    (instance? Instant x) x
    (instance? java.time.ZonedDateTime x) (.toInstant x)
    :else x))

;;; ---------------------------------------------------------------------------
;;; ID Generation Tests
;;; ---------------------------------------------------------------------------

(deftest make-state-id-test
  (testing "Generates deterministic state ID"
    (is (= "ingestion-state-AAPL" (state/make-state-id "AAPL")))
    (is (= "ingestion-state-SPY" (state/make-state-id "SPY")))
    (is (= "ingestion-state-GOOGL" (state/make-state-id "GOOGL"))))

  (testing "ID format matches expected pattern"
    (let [id (state/make-state-id "NVDA")]
      (is (string? id))
      (is (clojure.string/starts-with? id "ingestion-state-"))
      (is (clojure.string/ends-with? id "NVDA"))))

  (testing "Same symbol produces same ID (idempotent)"
    (is (= (state/make-state-id "AAPL")
           (state/make-state-id "AAPL")))
    (is (= (state/make-state-id "SPY")
           (state/make-state-id "SPY"))))

  (testing "Different symbols produce different IDs"
    (is (not= (state/make-state-id "AAPL")
              (state/make-state-id "SPY")))))

(deftest make-progress-id-test
  (testing "Generates deterministic progress ID with string date"
    (is (= "progress-SPY-2024-11-27" (state/make-progress-id "SPY" "2024-11-27")))
    (is (= "progress-AAPL-2024-01-15" (state/make-progress-id "AAPL" "2024-01-15"))))

  (testing "Generates deterministic progress ID with LocalDate"
    (let [date (LocalDate/of 2024 11 27)]
      (is (= "progress-SPY-2024-11-27" (state/make-progress-id "SPY" date)))))

  (testing "ID format matches expected pattern"
    (let [id (state/make-progress-id "NVDA" "2024-11-27")]
      (is (string? id))
      (is (clojure.string/starts-with? id "progress-"))
      (is (clojure.string/includes? id "NVDA"))
      (is (clojure.string/ends-with? id "2024-11-27"))))

  (testing "Same symbol + date produces same ID (idempotent)"
    (is (= (state/make-progress-id "AAPL" "2024-11-27")
           (state/make-progress-id "AAPL" "2024-11-27"))))

  (testing "Different dates produce different IDs"
    (is (not= (state/make-progress-id "AAPL" "2024-11-27")
              (state/make-progress-id "AAPL" "2024-11-28"))))

  (testing "Different symbols produce different IDs"
    (is (not= (state/make-progress-id "AAPL" "2024-11-27")
              (state/make-progress-id "SPY" "2024-11-27")))))

;;; ---------------------------------------------------------------------------
;;; Pure Function Tests - get-resume-work
;;; ---------------------------------------------------------------------------

(deftest get-resume-work-filters-completed-dates-test
  (testing "Filters out completed dates from all dates"
    ;; This test can be done without database by mocking get-completed-dates
    ;; But we'll test the logic with database integration below
    (let [all-dates [(LocalDate/of 2024 11 1)
                     (LocalDate/of 2024 11 2)
                     (LocalDate/of 2024 11 3)
                     (LocalDate/of 2024 11 4)
                     (LocalDate/of 2024 11 5)]
          ;; Mark dates 2, 4 as complete
          _ (state/mark-date-done! *test-node* "AAPL" (LocalDate/of 2024 11 2) 100)
          _ (state/mark-date-done! *test-node* "AAPL" (LocalDate/of 2024 11 4) 150)
          remaining (state/get-resume-work *test-node* "AAPL" all-dates)]

      ;; Should only return dates 1, 3, 5
      (is (= 3 (count remaining)))
      (is (contains? (set remaining) (LocalDate/of 2024 11 1)))
      (is (contains? (set remaining) (LocalDate/of 2024 11 3)))
      (is (contains? (set remaining) (LocalDate/of 2024 11 5)))
      (is (not (contains? (set remaining) (LocalDate/of 2024 11 2))))
      (is (not (contains? (set remaining) (LocalDate/of 2024 11 4)))))))

(deftest get-resume-work-preserves-order-test
  (testing "Returns dates in original order"
    (let [all-dates [(LocalDate/of 2024 11 5)
                     (LocalDate/of 2024 11 3)
                     (LocalDate/of 2024 11 1)
                     (LocalDate/of 2024 11 4)
                     (LocalDate/of 2024 11 2)]
          ;; Mark date 3 as complete
          _ (state/mark-date-done! *test-node* "SPY" (LocalDate/of 2024 11 3) 100)
          remaining (state/get-resume-work *test-node* "SPY" all-dates)]

      ;; Should preserve original order: 5, 1, 4, 2
      (is (= [(LocalDate/of 2024 11 5)
              (LocalDate/of 2024 11 1)
              (LocalDate/of 2024 11 4)
              (LocalDate/of 2024 11 2)]
             remaining)))))

(deftest get-resume-work-all-complete-test
  (testing "Returns empty vector when all dates complete"
    (let [all-dates [(LocalDate/of 2024 11 1)
                     (LocalDate/of 2024 11 2)]
          _ (state/mark-date-done! *test-node* "NVDA" (LocalDate/of 2024 11 1) 100)
          _ (state/mark-date-done! *test-node* "NVDA" (LocalDate/of 2024 11 2) 150)
          remaining (state/get-resume-work *test-node* "NVDA" all-dates)]
      (is (empty? remaining))
      (is (vector? remaining)))))

(deftest get-resume-work-none-complete-test
  (testing "Returns all dates when none complete"
    (let [all-dates [(LocalDate/of 2024 11 1)
                     (LocalDate/of 2024 11 2)
                     (LocalDate/of 2024 11 3)]
          remaining (state/get-resume-work *test-node* "MSFT" all-dates)]
      (is (= all-dates remaining)))))

(deftest get-resume-work-different-symbols-isolated-test
  (testing "Completed dates are isolated per symbol"
    (let [all-dates [(LocalDate/of 2024 11 1)
                     (LocalDate/of 2024 11 2)]
          ;; Mark dates complete for AAPL
          _ (state/mark-date-done! *test-node* "AAPL" (LocalDate/of 2024 11 1) 100)
          _ (state/mark-date-done! *test-node* "AAPL" (LocalDate/of 2024 11 2) 150)
          ;; Check remaining for SPY (different symbol)
          remaining-spy (state/get-resume-work *test-node* "SPY" all-dates)]

      ;; SPY should have no completed dates
      (is (= all-dates remaining-spy)))))

;;; ---------------------------------------------------------------------------
;;; Database Integration Tests - State Management
;;; ---------------------------------------------------------------------------

(deftest init-state-creates-new-state-test
  (testing "Creates new ingestion state"
    (let [start-date (Instant/now)
          _ (state/init-state! *test-node* "AAPL" start-date)
          result (state/get-state *test-node* "AAPL")]

      (is (some? result))
      (is (= "AAPL" (:ingestion/symbol result)))
      (is (= start-date (->instant (:ingestion/start-date result))))
      (is (= :in-progress (:ingestion/status result)))
      (is (= 0 (:ingestion/records-count result)))
      (is (some? (:ingestion/updated-at result)))
      (is (not (contains? result :ingestion/last-date))))))

(deftest get-state-returns-nil-when-no-state-test
  (testing "Returns nil when no state exists for symbol"
    (let [result (state/get-state *test-node* "NONEXISTENT")]
      (is (nil? result)))))

(deftest update-progress-updates-state-test
  (testing "Updates progress with last-date and records-count"
    (let [start-date (Instant/now)
          last-date (.minus (Instant/now) 1 ChronoUnit/DAYS)
          _ (state/init-state! *test-node* "SPY" start-date)
          _ (state/update-progress! *test-node* "SPY" last-date 5000)
          result (state/get-state *test-node* "SPY")]

      (is (= "SPY" (:ingestion/symbol result)))
      (is (= last-date (->instant (:ingestion/last-date result))))
      (is (= 5000 (:ingestion/records-count result)))
      (is (= :in-progress (:ingestion/status result))))))

(deftest update-progress-incremental-test
  (testing "Multiple progress updates are cumulative"
    (let [start-date (Instant/now)
          date1 (.minus (Instant/now) 3 ChronoUnit/DAYS)
          date2 (.minus (Instant/now) 2 ChronoUnit/DAYS)
          date3 (.minus (Instant/now) 1 ChronoUnit/DAYS)
          _ (state/init-state! *test-node* "NVDA" start-date)
          _ (state/update-progress! *test-node* "NVDA" date1 1000)
          _ (state/update-progress! *test-node* "NVDA" date2 2500)
          _ (state/update-progress! *test-node* "NVDA" date3 4200)
          result (state/get-state *test-node* "NVDA")]

      ;; Last update should be reflected
      (is (= date3 (->instant (:ingestion/last-date result))))
      (is (= 4200 (:ingestion/records-count result)))
      (is (= :in-progress (:ingestion/status result))))))

(deftest mark-complete-sets-complete-status-test
  (testing "Marks ingestion as successfully completed"
    (let [start-date (Instant/now)
          last-date (.minus (Instant/now) 1 ChronoUnit/DAYS)
          _ (state/init-state! *test-node* "GOOGL" start-date)
          _ (state/update-progress! *test-node* "GOOGL" last-date 3000)
          _ (state/mark-complete! *test-node* "GOOGL" last-date 3500)
          result (state/get-state *test-node* "GOOGL")]

      (is (= :complete (:ingestion/status result)))
      (is (= last-date (->instant (:ingestion/last-date result))))
      (is (= 3500 (:ingestion/records-count result)))
      (is (some? (:ingestion/updated-at result))))))

(deftest mark-failed-sets-failed-status-test
  (testing "Marks ingestion as failed with error message"
    (let [start-date (Instant/now)
          error-msg "API rate limit exceeded"
          _ (state/init-state! *test-node* "MSFT" start-date)
          _ (state/mark-failed! *test-node* "MSFT" error-msg)
          result (state/get-state *test-node* "MSFT")]

      (is (= :failed (:ingestion/status result)))
      (is (= error-msg (:ingestion/error result)))
      (is (some? (:ingestion/updated-at result))))))

(deftest get-resume-date-returns-next-day-for-in-progress-test
  (testing "Returns day after last-date for in-progress ingestion"
    (let [start-date (Instant/now)
          last-date (.minus (Instant/now) 5 ChronoUnit/DAYS)
          expected-resume (.plus last-date 1 ChronoUnit/DAYS)
          _ (state/init-state! *test-node* "AAPL" start-date)
          _ (state/update-progress! *test-node* "AAPL" last-date 1000)
          resume-date (state/get-resume-date *test-node* "AAPL")]

      (is (= expected-resume (->instant resume-date))))))

(deftest get-resume-date-returns-nil-when-no-state-test
  (testing "Returns nil when no state exists"
    (let [resume-date (state/get-resume-date *test-node* "NONEXISTENT")]
      (is (nil? resume-date)))))

(deftest get-resume-date-returns-nil-for-complete-test
  (testing "Returns nil when status is :complete"
    (let [start-date (Instant/now)
          last-date (.minus (Instant/now) 1 ChronoUnit/DAYS)
          _ (state/init-state! *test-node* "SPY" start-date)
          _ (state/mark-complete! *test-node* "SPY" last-date 5000)
          resume-date (state/get-resume-date *test-node* "SPY")]

      (is (nil? resume-date)))))

(deftest get-resume-date-returns-nil-for-failed-test
  (testing "Returns nil when status is :failed"
    (let [start-date (Instant/now)
          _ (state/init-state! *test-node* "NVDA" start-date)
          _ (state/mark-failed! *test-node* "NVDA" "Test error")
          resume-date (state/get-resume-date *test-node* "NVDA")]

      (is (nil? resume-date)))))

(deftest get-resume-date-returns-nil-when-no-last-date-test
  (testing "Returns nil when last-date is not set"
    (let [start-date (Instant/now)
          _ (state/init-state! *test-node* "GOOGL" start-date)
          resume-date (state/get-resume-date *test-node* "GOOGL")]

      (is (nil? resume-date)))))

;;; ---------------------------------------------------------------------------
;;; Database Integration Tests - Progress Tracking
;;; ---------------------------------------------------------------------------

(deftest mark-date-done-creates-progress-record-test
  (testing "Creates progress record for a date"
    (let [date (LocalDate/of 2024 11 27)
          _ (state/mark-date-done! *test-node* "AAPL" date 250)
          completed (state/get-completed-dates *test-node* "AAPL")]

      (is (contains? completed date))
      (is (= 1 (count completed))))))

(deftest mark-date-done-multiple-dates-test
  (testing "Tracks multiple completed dates"
    (let [date1 (LocalDate/of 2024 11 25)
          date2 (LocalDate/of 2024 11 26)
          date3 (LocalDate/of 2024 11 27)
          _ (state/mark-date-done! *test-node* "SPY" date1 100)
          _ (state/mark-date-done! *test-node* "SPY" date2 150)
          _ (state/mark-date-done! *test-node* "SPY" date3 200)
          completed (state/get-completed-dates *test-node* "SPY")]

      (is (= 3 (count completed)))
      (is (contains? completed date1))
      (is (contains? completed date2))
      (is (contains? completed date3)))))

(deftest get-completed-dates-returns-empty-set-when-none-test
  (testing "Returns empty set when no dates completed"
    (let [completed (state/get-completed-dates *test-node* "NONEXISTENT")]
      (is (empty? completed))
      (is (set? completed)))))

(deftest mark-date-done-idempotent-test
  (testing "Marking same date twice is idempotent"
    (let [date (LocalDate/of 2024 11 27)
          _ (state/mark-date-done! *test-node* "NVDA" date 100)
          _ (state/mark-date-done! *test-node* "NVDA" date 200)
          completed (state/get-completed-dates *test-node* "NVDA")]

      ;; Should still only have one date
      (is (= 1 (count completed)))
      (is (contains? completed date)))))

(deftest mark-date-done-isolated-by-symbol-test
  (testing "Progress is isolated per symbol"
    (let [date (LocalDate/of 2024 11 27)
          _ (state/mark-date-done! *test-node* "AAPL" date 100)
          _ (state/mark-date-done! *test-node* "SPY" date 200)
          aapl-completed (state/get-completed-dates *test-node* "AAPL")
          spy-completed (state/get-completed-dates *test-node* "SPY")]

      (is (contains? aapl-completed date))
      (is (contains? spy-completed date))
      (is (= 1 (count aapl-completed)))
      (is (= 1 (count spy-completed))))))

;;; ---------------------------------------------------------------------------
;;; Database Integration Tests - Convenience Functions
;;; ---------------------------------------------------------------------------

(deftest list-all-states-test
  (testing "Lists all ingestion states"
    (let [start-date (Instant/now)
          _ (state/init-state! *test-node* "AAPL" start-date)
          _ (state/init-state! *test-node* "SPY" start-date)
          _ (state/init-state! *test-node* "NVDA" start-date)
          all-states (state/list-all-states *test-node*)
          symbols (set (map :ingestion/symbol all-states))]

      (is (>= (count all-states) 3))
      (is (contains? symbols "AAPL"))
      (is (contains? symbols "SPY"))
      (is (contains? symbols "NVDA")))))

(deftest list-in-progress-test
  (testing "Lists only in-progress states"
    (let [start-date (Instant/now)
          last-date (.minus (Instant/now) 1 ChronoUnit/DAYS)
          _ (state/init-state! *test-node* "AAPL" start-date)
          _ (state/init-state! *test-node* "SPY" start-date)
          _ (state/mark-complete! *test-node* "SPY" last-date 1000)
          _ (state/init-state! *test-node* "NVDA" start-date)
          _ (state/mark-failed! *test-node* "NVDA" "Test error")
          in-progress (state/list-in-progress *test-node*)
          symbols (set (map :ingestion/symbol in-progress))]

      ;; Only AAPL should be in-progress
      (is (= 1 (count in-progress)))
      (is (contains? symbols "AAPL"))
      (is (not (contains? symbols "SPY")))
      (is (not (contains? symbols "NVDA"))))))

(deftest list-failed-test
  (testing "Lists only failed states"
    (let [start-date (Instant/now)
          last-date (.minus (Instant/now) 1 ChronoUnit/DAYS)
          _ (state/init-state! *test-node* "AAPL" start-date)
          _ (state/init-state! *test-node* "SPY" start-date)
          _ (state/mark-complete! *test-node* "SPY" last-date 1000)
          _ (state/init-state! *test-node* "NVDA" start-date)
          _ (state/mark-failed! *test-node* "NVDA" "API error")
          _ (state/init-state! *test-node* "GOOGL" start-date)
          _ (state/mark-failed! *test-node* "GOOGL" "Network timeout")
          failed (state/list-failed *test-node*)
          symbols (set (map :ingestion/symbol failed))]

      ;; NVDA and GOOGL should be failed
      (is (= 2 (count failed)))
      (is (contains? symbols "NVDA"))
      (is (contains? symbols "GOOGL"))
      (is (not (contains? symbols "AAPL")))
      (is (not (contains? symbols "SPY")))

      ;; Check error messages are present
      (let [nvda-state (first (filter #(= "NVDA" (:ingestion/symbol %)) failed))
            googl-state (first (filter #(= "GOOGL" (:ingestion/symbol %)) failed))]
        (is (= "API error" (:ingestion/error nvda-state)))
        (is (= "Network timeout" (:ingestion/error googl-state)))))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases and Error Handling
;;; ---------------------------------------------------------------------------

(deftest state-id-with-special-characters-test
  (testing "Handles symbols with special characters"
    ;; Note: Real tickers don't have special chars, but test edge case
    (let [id (state/make-state-id "BRK.B")]
      (is (= "ingestion-state-BRK.B" id)))))

(deftest progress-id-with-various-date-formats-test
  (testing "Handles LocalDate correctly"
    (let [date (LocalDate/of 2024 1 5)
          id (state/make-progress-id "AAPL" date)]
      (is (clojure.string/ends-with? id "2024-01-05"))))

  (testing "Handles string date correctly"
    (let [id (state/make-progress-id "AAPL" "2024-01-05")]
      (is (clojure.string/ends-with? id "2024-01-05")))))

(deftest update-progress-without-init-test
  (testing "Can update progress without explicit init"
    ;; update-progress! should work even without init-state!
    ;; because it does a put-docs which creates or updates
    (let [last-date (Instant/now)
          _ (state/update-progress! *test-node* "TSLA" last-date 100)
          result (state/get-state *test-node* "TSLA")]

      (is (some? result))
      (is (= "TSLA" (:ingestion/symbol result)))
      (is (= 100 (:ingestion/records-count result))))))

(deftest empty-dates-list-test
  (testing "get-resume-work handles empty dates list"
    (let [remaining (state/get-resume-work *test-node* "AAPL" [])]
      (is (empty? remaining))
      (is (vector? remaining)))))

;;; ---------------------------------------------------------------------------
;;; NOTE: Gap Detection Tests
;;; ---------------------------------------------------------------------------

;; gap detection (find-gaps) requires actual option-greeks data in XTDB.
;; This would require:
;; 1. Creating test option-greeks documents
;; 2. Inserting them with proper dates
;; 3. Then testing gap detection logic
;;
;; This is more of an integration test that should be in a separate
;; test suite that loads actual data. For now, we focus on the pure
;; functions and state management logic that can be tested with fixtures.

(comment
  ;; Run all tests
  (clojure.test/run-tests 'ml-options.data.ingestion-state-test)

  ;; Run specific test
  (clojure.test/test-var #'make-state-id-test)
  (clojure.test/test-var #'make-progress-id-test)
  (clojure.test/test-var #'get-resume-work-filters-completed-dates-test)
  (clojure.test/test-var #'init-state-creates-new-state-test)
  (clojure.test/test-var #'mark-date-done-creates-progress-record-test)
  (clojure.test/test-var #'list-in-progress-test)

  ;; Test with temp node
  (ml-options.test-utils/with-temp-node [node]
    (state/init-state! node "TEST" (java.time.Instant/now))
    (state/get-state node "TEST")))
