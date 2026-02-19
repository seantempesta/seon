(ns seon.dev.context-test
  "Tests for the context namespace - edit/review event tracking.

   Dev context events are now stored in-memory (no XTDB dependency).
   Tests clear events between runs to ensure isolation."
  (:require [clojure.test :refer :all]
            [seon.dev.context :as ctx]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(defn clear-events [f]
  (ctx/clear-all-events! {::ctx/xtdb-node nil})
  (f)
  (ctx/clear-all-events! {::ctx/xtdb-node nil}))

(use-fixtures :each clear-events)

;;; ---------------------------------------------------------------------------
;;; record-edit! Tests
;;; ---------------------------------------------------------------------------

(deftest record-edit!-test
  (testing "Records edit event with file and namespace"
    (let [result (ctx/record-edit! {::ctx/xtdb-node nil
                                    ::ctx/file-path "/path/to/file.clj"
                                    ::ctx/namespace 'seon.foo})]
      (is (true? (::ctx/success result)) "Should return success")
      (is (::ctx/tx-id result) "Should have transaction ID")))

  (testing "Records edit event with nil namespace"
    (let [result (ctx/record-edit! {::ctx/xtdb-node nil
                                    ::ctx/file-path "/path/to/other.clj"})]
      (is (true? (::ctx/success result)) "Should allow nil namespace")
      (is (::ctx/tx-id result))))

  (testing "Multiple edits are recorded separately"
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/a.clj"
                      ::ctx/namespace 'seon.a})
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/b.clj"
                      ::ctx/namespace 'seon.b})
    (let [edits (::ctx/edits (ctx/edits-since-last-review {::ctx/xtdb-node nil}))]
      ;; 4 total: 2 from earlier tests + 2 from this test
      (is (>= (count edits) 4) "Should have recorded all edits"))))

;;; ---------------------------------------------------------------------------
;;; record-review! Tests
;;; ---------------------------------------------------------------------------

(deftest record-review!-test
  (testing "Records review event with files"
    ;; First record some edits
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/file.clj"
                      ::ctx/namespace 'seon.foo})

    ;; Then record review
    (let [result (ctx/record-review! {::ctx/xtdb-node nil
                                      ::ctx/files #{"/path/to/file.clj"}})]
      (is (true? (::ctx/success result)) "Should return success")
      (is (::ctx/tx-id result) "Should have transaction ID")))

  (testing "Review records edit count"
    ;; After review, edits since last review should be empty
    (let [edits (::ctx/edits (ctx/edits-since-last-review {::ctx/xtdb-node nil}))]
      (is (empty? edits) "Should have no edits since review"))))

;;; ---------------------------------------------------------------------------
;;; get-last-edit-time Tests
;;; ---------------------------------------------------------------------------

(deftest get-last-edit-time-test
  (testing "Returns nil when no edits"
    (is (nil? (::ctx/timestamp (ctx/get-last-edit-time {::ctx/xtdb-node nil})))))

  (testing "Returns timestamp after edit"
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/file.clj"
                      ::ctx/namespace 'seon.foo})
    (let [t (::ctx/timestamp (ctx/get-last-edit-time {::ctx/xtdb-node nil}))]
      (is (some? t) "Should have timestamp")
      (is (instance? java.time.Instant t) "Should be an Instant")))

  (testing "Returns most recent edit time"
    (Thread/sleep 10) ; Ensure different timestamps
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/other.clj"
                      ::ctx/namespace 'seon.bar})
    (let [t1 (::ctx/timestamp (ctx/get-last-edit-time {::ctx/xtdb-node nil}))]
      (Thread/sleep 10)
      (ctx/record-edit! {::ctx/xtdb-node nil
                        ::ctx/file-path "/path/to/third.clj"
                        ::ctx/namespace 'seon.baz})
      (let [t2 (::ctx/timestamp (ctx/get-last-edit-time {::ctx/xtdb-node nil}))]
        (is (.isAfter t2 t1) "Later edit should have later timestamp")))))

;;; ---------------------------------------------------------------------------
;;; get-last-review-time Tests
;;; ---------------------------------------------------------------------------

(deftest get-last-review-time-test
  (testing "Returns nil when no reviews"
    (is (nil? (::ctx/timestamp (ctx/get-last-review-time {::ctx/xtdb-node nil})))))

  (testing "Returns timestamp after review"
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/file.clj"
                      ::ctx/namespace 'seon.foo})
    (ctx/record-review! {::ctx/xtdb-node nil
                         ::ctx/files #{"/path/to/file.clj"}})
    (let [t (::ctx/timestamp (ctx/get-last-review-time {::ctx/xtdb-node nil}))]
      (is (some? t) "Should have timestamp")
      (is (instance? java.time.Instant t) "Should be an Instant"))))

;;; ---------------------------------------------------------------------------
;;; edits-since-last-review Tests
;;; ---------------------------------------------------------------------------

(deftest edits-since-last-review-test
  (testing "Returns all edits when no review"
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/a.clj"
                      ::ctx/namespace 'seon.a})
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/b.clj"
                      ::ctx/namespace 'seon.b})
    (let [edits (::ctx/edits (ctx/edits-since-last-review {::ctx/xtdb-node nil}))]
      (is (= 2 (count edits)))
      (is (= #{"/path/to/a.clj" "/path/to/b.clj"}
             (set (map ::ctx/file edits))))))

  (testing "Returns only edits after review"
    (ctx/record-review! {::ctx/xtdb-node nil
                         ::ctx/files #{"/path/to/a.clj" "/path/to/b.clj"}})
    (let [edits-after-review (::ctx/edits (ctx/edits-since-last-review {::ctx/xtdb-node nil}))]
      (is (empty? edits-after-review) "Should be empty after review"))

    ;; Add new edit
    (Thread/sleep 10) ; Ensure timestamp is after review
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/c.clj"
                      ::ctx/namespace 'seon.c})
    (let [new-edits (::ctx/edits (ctx/edits-since-last-review {::ctx/xtdb-node nil}))]
      (is (= 1 (count new-edits)))
      (is (= "/path/to/c.clj" (::ctx/file (first new-edits)))))))

;;; ---------------------------------------------------------------------------
;;; edits-summary Tests
;;; ---------------------------------------------------------------------------

(deftest edits-summary-test
  (testing "Returns empty summary when no edits"
    (let [summary (ctx/edits-summary {::ctx/xtdb-node nil})]
      (is (= #{} (::ctx/files summary)))
      (is (= #{} (::ctx/namespaces summary)))
      (is (= 0 (::ctx/edit-count summary)))))

  (testing "Returns summary of edits"
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/a.clj"
                      ::ctx/namespace 'seon.a})
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/b.clj"
                      ::ctx/namespace 'seon.b})
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/a.clj"
                      ::ctx/namespace 'seon.a}) ; same file again
    (let [summary (ctx/edits-summary {::ctx/xtdb-node nil})]
      (is (= #{"/path/to/a.clj" "/path/to/b.clj"} (::ctx/files summary)))
      (is (= #{:seon.a :seon.b} (::ctx/namespaces summary)))
      (is (= 3 (::ctx/edit-count summary)) "Should count all edits, not unique files"))))

;;; ---------------------------------------------------------------------------
;;; should-review? Tests
;;; ---------------------------------------------------------------------------

(deftest should-review?-test
  (testing "Returns false when no edits"
    (is (false? (::ctx/should-review (ctx/should-review? {::ctx/xtdb-node nil})))))

  (testing "Returns true after first edit (never reviewed)"
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/file.clj"
                      ::ctx/namespace 'seon.foo})
    (is (true? (::ctx/should-review (ctx/should-review? {::ctx/xtdb-node nil})))
        "Should review immediately when never reviewed"))

  (testing "Returns false immediately after review"
    (ctx/record-review! {::ctx/xtdb-node nil
                         ::ctx/files #{"/path/to/file.clj"}})
    (is (false? (::ctx/should-review (ctx/should-review? {::ctx/xtdb-node nil})))
        "Should not review - no new edits"))

  (testing "Returns false when interval not passed"
    (Thread/sleep 10)
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/new.clj"
                      ::ctx/namespace 'seon.new})
    ;; Default interval is 60 seconds, which hasn't passed
    (is (false? (::ctx/should-review (ctx/should-review? {::ctx/xtdb-node nil})))
        "Should not review - interval not passed"))

  (testing "Returns true with short interval"
    ;; With 0 second interval, should immediately return true
    (is (true? (::ctx/should-review (ctx/should-review? {::ctx/xtdb-node nil
                                                          ::ctx/interval-seconds 0})))
        "Should review with 0 second interval")))

(deftest should-review?-timing-test
  (testing "Rate limiting works correctly"
    ;; Record edit
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/file.clj"
                      ::ctx/namespace 'seon.foo})

    ;; Review immediately (interval = 0)
    (is (true? (::ctx/should-review (ctx/should-review? {::ctx/xtdb-node nil
                                                          ::ctx/interval-seconds 0}))))
    (ctx/record-review! {::ctx/xtdb-node nil
                         ::ctx/files #{"/path/to/file.clj"}})

    ;; Record new edit
    (Thread/sleep 10)
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/new.clj"
                      ::ctx/namespace 'seon.new})

    ;; With 60 second interval, should be false (hasn't passed)
    (is (false? (::ctx/should-review (ctx/should-review? {::ctx/xtdb-node nil
                                                           ::ctx/interval-seconds 60})))
        "Should not review - 60s interval not passed")

    ;; With 0 second interval, should be true
    (is (true? (::ctx/should-review (ctx/should-review? {::ctx/xtdb-node nil
                                                          ::ctx/interval-seconds 0})))
        "Should review with 0s interval")))

;;; ---------------------------------------------------------------------------
;;; clear-all-events! Tests
;;; ---------------------------------------------------------------------------

(deftest clear-all-events!-test
  (testing "Clears all events"
    ;; Record some events
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/a.clj"
                      ::ctx/namespace 'seon.a})
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/b.clj"
                      ::ctx/namespace 'seon.b})
    (ctx/record-review! {::ctx/xtdb-node nil
                         ::ctx/files #{"/path/to/a.clj"}})

    ;; Verify they exist
    (is (some? (::ctx/timestamp (ctx/get-last-edit-time {::ctx/xtdb-node nil}))))
    (is (some? (::ctx/timestamp (ctx/get-last-review-time {::ctx/xtdb-node nil}))))

    ;; Clear all
    (ctx/clear-all-events! {::ctx/xtdb-node nil})

    ;; Verify they're gone
    (is (nil? (::ctx/timestamp (ctx/get-last-edit-time {::ctx/xtdb-node nil}))))
    (is (nil? (::ctx/timestamp (ctx/get-last-review-time {::ctx/xtdb-node nil}))))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases
;;; ---------------------------------------------------------------------------

(deftest edge-cases-test
  (testing "Empty file set for review"
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/file.clj"
                      ::ctx/namespace 'seon.foo})
    (let [result (ctx/record-review! {::ctx/xtdb-node nil
                                      ::ctx/files #{}})]
      (is (true? (::ctx/success result)) "Should allow empty file set")))

  (testing "Very long file path"
    (let [long-path (str "/very/long/path/" (apply str (repeat 500 "x")) ".clj")]
      (let [result (ctx/record-edit! {::ctx/xtdb-node nil
                                      ::ctx/file-path long-path})]
        (is (true? (::ctx/success result)) "Should handle long paths"))))

  (testing "Special characters in namespace"
    (ctx/clear-all-events! {::ctx/xtdb-node nil})
    (ctx/record-edit! {::ctx/xtdb-node nil
                      ::ctx/file-path "/path/to/file.clj"
                      ::ctx/namespace 'foo-bar.baz_qux})
    (let [summary (ctx/edits-summary {::ctx/xtdb-node nil})]
      (is (contains? (::ctx/namespaces summary) :foo-bar.baz_qux)))))
