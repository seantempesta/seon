(ns seon.dev.context-test
  "Tests for the context namespace - edit/review event tracking."
  (:require [clojure.test :refer :all]
            [seon.dev.context :as ctx]
            [seon.test-utils :refer [with-test-node *test-node*]]))

;;; ---------------------------------------------------------------------------
;;; record-edit! Tests
;;; ---------------------------------------------------------------------------

(deftest record-edit!-test
  (with-test-node
    (fn []
      (testing "Records edit event with file and namespace"
        (let [result (ctx/record-edit! *test-node* "/path/to/file.clj" 'seon.foo)]
          (is (some? result) "Should return transaction result")
          (is (:tx-id result) "Should have transaction ID")))

      (testing "Records edit event with nil namespace"
        (let [result (ctx/record-edit! *test-node* "/path/to/other.clj" nil)]
          (is (some? result) "Should allow nil namespace")
          (is (:tx-id result))))

      (testing "Multiple edits are recorded separately"
        (ctx/record-edit! *test-node* "/path/to/a.clj" 'seon.a)
        (ctx/record-edit! *test-node* "/path/to/b.clj" 'seon.b)
        (let [edits (ctx/edits-since-last-review *test-node*)]
          ;; 4 total: 2 from earlier tests + 2 from this test
          (is (>= (count edits) 4) "Should have recorded all edits"))))))

;;; ---------------------------------------------------------------------------
;;; record-review! Tests
;;; ---------------------------------------------------------------------------

(deftest record-review!-test
  (with-test-node
    (fn []
      (testing "Records review event with files"
        ;; First record some edits
        (ctx/record-edit! *test-node* "/path/to/file.clj" 'seon.foo)

        ;; Then record review
        (let [result (ctx/record-review! *test-node* #{"/path/to/file.clj"})]
          (is (some? result) "Should return transaction result")
          (is (:tx-id result) "Should have transaction ID")))

      (testing "Review records edit count"
        ;; After review, edits since last review should be empty
        (let [edits (ctx/edits-since-last-review *test-node*)]
          (is (empty? edits) "Should have no edits since review"))))))

;;; ---------------------------------------------------------------------------
;;; get-last-edit-time Tests
;;; ---------------------------------------------------------------------------

(deftest get-last-edit-time-test
  (with-test-node
    (fn []
      (testing "Returns nil when no edits"
        (is (nil? (ctx/get-last-edit-time *test-node*))))

      (testing "Returns timestamp after edit"
        (ctx/record-edit! *test-node* "/path/to/file.clj" 'seon.foo)
        (let [t (ctx/get-last-edit-time *test-node*)]
          (is (some? t) "Should have timestamp")
          ;; XTDB returns ZonedDateTime, not Instant
          (is (instance? java.time.temporal.Temporal t) "Should be a temporal type")))

      (testing "Returns most recent edit time"
        (Thread/sleep 10) ; Ensure different timestamps
        (ctx/record-edit! *test-node* "/path/to/other.clj" 'seon.bar)
        (let [t1 (ctx/get-last-edit-time *test-node*)]
          (Thread/sleep 10)
          (ctx/record-edit! *test-node* "/path/to/third.clj" 'seon.baz)
          (let [t2 (ctx/get-last-edit-time *test-node*)]
            (is (.isAfter t2 t1) "Later edit should have later timestamp")))))))

;;; ---------------------------------------------------------------------------
;;; get-last-review-time Tests
;;; ---------------------------------------------------------------------------

(deftest get-last-review-time-test
  (with-test-node
    (fn []
      (testing "Returns nil when no reviews"
        (is (nil? (ctx/get-last-review-time *test-node*))))

      (testing "Returns timestamp after review"
        (ctx/record-edit! *test-node* "/path/to/file.clj" 'seon.foo)
        (ctx/record-review! *test-node* #{"/path/to/file.clj"})
        (let [t (ctx/get-last-review-time *test-node*)]
          (is (some? t) "Should have timestamp")
          ;; XTDB returns ZonedDateTime, not Instant
          (is (instance? java.time.temporal.Temporal t) "Should be a temporal type"))))))

;;; ---------------------------------------------------------------------------
;;; edits-since-last-review Tests
;;; ---------------------------------------------------------------------------

(deftest edits-since-last-review-test
  (with-test-node
    (fn []
      (testing "Returns all edits when no review"
        (ctx/record-edit! *test-node* "/path/to/a.clj" 'seon.a)
        (ctx/record-edit! *test-node* "/path/to/b.clj" 'seon.b)
        (let [edits (ctx/edits-since-last-review *test-node*)]
          (is (= 2 (count edits)))
          (is (= #{"/path/to/a.clj" "/path/to/b.clj"}
                 (set (map :edit/file edits))))))

      (testing "Returns only edits after review"
        (ctx/record-review! *test-node* #{"/path/to/a.clj" "/path/to/b.clj"})
        (let [edits-after-review (ctx/edits-since-last-review *test-node*)]
          (is (empty? edits-after-review) "Should be empty after review"))

        ;; Add new edit
        (Thread/sleep 10) ; Ensure timestamp is after review
        (ctx/record-edit! *test-node* "/path/to/c.clj" 'seon.c)
        (let [new-edits (ctx/edits-since-last-review *test-node*)]
          (is (= 1 (count new-edits)))
          (is (= "/path/to/c.clj" (:edit/file (first new-edits)))))))))

;;; ---------------------------------------------------------------------------
;;; edits-summary Tests
;;; ---------------------------------------------------------------------------

(deftest edits-summary-test
  (with-test-node
    (fn []
      (testing "Returns empty summary when no edits"
        (let [summary (ctx/edits-summary *test-node*)]
          (is (= #{} (::ctx/files summary)))
          (is (= #{} (::ctx/namespaces summary)))
          (is (= 0 (::ctx/edit-count summary)))))

      (testing "Returns summary of edits"
        (ctx/record-edit! *test-node* "/path/to/a.clj" 'seon.a)
        (ctx/record-edit! *test-node* "/path/to/b.clj" 'seon.b)
        (ctx/record-edit! *test-node* "/path/to/a.clj" 'seon.a) ; same file again
        (let [summary (ctx/edits-summary *test-node*)]
          (is (= #{"/path/to/a.clj" "/path/to/b.clj"} (::ctx/files summary)))
          (is (= #{:seon.a :seon.b} (::ctx/namespaces summary)))
          (is (= 3 (::ctx/edit-count summary)) "Should count all edits, not unique files"))))))

;;; ---------------------------------------------------------------------------
;;; should-review? Tests
;;; ---------------------------------------------------------------------------

(deftest should-review?-test
  (with-test-node
    (fn []
      (testing "Returns false when no edits"
        (is (false? (ctx/should-review? *test-node*))))

      (testing "Returns true after first edit (never reviewed)"
        (ctx/record-edit! *test-node* "/path/to/file.clj" 'seon.foo)
        (is (true? (ctx/should-review? *test-node*))
            "Should review immediately when never reviewed"))

      (testing "Returns false immediately after review"
        (ctx/record-review! *test-node* #{"/path/to/file.clj"})
        (is (false? (ctx/should-review? *test-node*))
            "Should not review - no new edits"))

      (testing "Returns false when interval not passed"
        (Thread/sleep 10)
        (ctx/record-edit! *test-node* "/path/to/new.clj" 'seon.new)
        ;; Default interval is 60 seconds, which hasn't passed
        (is (false? (ctx/should-review? *test-node*))
            "Should not review - interval not passed"))

      (testing "Returns true with short interval"
        ;; With 0 second interval, should immediately return true
        (is (true? (ctx/should-review? *test-node* {::ctx/interval-seconds 0}))
            "Should review with 0 second interval")))))

(deftest should-review?-timing-test
  (with-test-node
    (fn []
      (testing "Rate limiting works correctly"
        ;; Record edit
        (ctx/record-edit! *test-node* "/path/to/file.clj" 'seon.foo)

        ;; Review immediately (interval = 0)
        (is (true? (ctx/should-review? *test-node* {::ctx/interval-seconds 0})))
        (ctx/record-review! *test-node* #{"/path/to/file.clj"})

        ;; Record new edit
        (Thread/sleep 10)
        (ctx/record-edit! *test-node* "/path/to/new.clj" 'seon.new)

        ;; With 1 second interval, should be false (hasn't passed)
        ;; Actually, might be true since we slept 10ms. Use larger interval.
        (is (false? (ctx/should-review? *test-node* {::ctx/interval-seconds 60}))
            "Should not review - 60s interval not passed")

        ;; With 0 second interval, should be true
        (is (true? (ctx/should-review? *test-node* {::ctx/interval-seconds 0}))
            "Should review with 0s interval")))))

;;; ---------------------------------------------------------------------------
;;; clear-all-events! Tests
;;; ---------------------------------------------------------------------------

(deftest clear-all-events!-test
  (with-test-node
    (fn []
      (testing "Clears all events"
        ;; Record some events
        (ctx/record-edit! *test-node* "/path/to/a.clj" 'seon.a)
        (ctx/record-edit! *test-node* "/path/to/b.clj" 'seon.b)
        (ctx/record-review! *test-node* #{"/path/to/a.clj"})

        ;; Verify they exist
        (is (some? (ctx/get-last-edit-time *test-node*)))
        (is (some? (ctx/get-last-review-time *test-node*)))

        ;; Clear all
        (ctx/clear-all-events! *test-node*)

        ;; Verify they're gone
        (is (nil? (ctx/get-last-edit-time *test-node*)))
        (is (nil? (ctx/get-last-review-time *test-node*)))))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases
;;; ---------------------------------------------------------------------------

(deftest edge-cases-test
  (with-test-node
    (fn []
      (testing "Empty file set for review"
        (ctx/record-edit! *test-node* "/path/to/file.clj" 'seon.foo)
        (let [result (ctx/record-review! *test-node* #{})]
          (is (some? result) "Should allow empty file set")))

      (testing "Very long file path"
        (let [long-path (str "/very/long/path/" (apply str (repeat 500 "x")) ".clj")]
          (let [result (ctx/record-edit! *test-node* long-path nil)]
            (is (some? result) "Should handle long paths"))))

      (testing "Special characters in namespace"
        (ctx/record-edit! *test-node* "/path/to/file.clj" 'foo-bar.baz_qux)
        (let [summary (ctx/edits-summary *test-node*)]
          (is (contains? (::ctx/namespaces summary) :foo-bar.baz_qux)))))))
