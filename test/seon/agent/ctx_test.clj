(ns seon.agent.ctx-test
  "Tests for persisted context atom.

  Tests validation, persistence, time-travel, and restore functionality."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [seon.agent.ctx :as ctx]
            [seon.schema :as schema]
            [seon.test-utils :refer [with-test-node *test-node*]]
            [xtdb.api :as xt]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(use-fixtures :each with-test-node)

;;; ---------------------------------------------------------------------------
;;; Test Schema Registration (for agent-side validation)
;;; ---------------------------------------------------------------------------

;; Register test schemas that agents would use
(schema/register! :test.ctx/value
                  [:int {:min 0 :description "A test integer value"}])

(schema/register! :test.ctx/name
                  [:string {:min 1 :description "A test string name"}])

(schema/register! :test.ctx/signals
                  [:vector [:map
                            [:symbol :string]
                            [:direction [:enum :long :short]]]])

;;; ---------------------------------------------------------------------------
;;; Validation Tests
;;; ---------------------------------------------------------------------------

(deftest validation-non-namespaced-key-test
  (testing "rejects non-namespaced keys with clear error"
    (let [{::ctx/keys [atom close!]} (ctx/make-persisted-ctx
                                       {::ctx/db *test-node*
                                        ::ctx/namespace 'test.validation})]
      (try
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Invalid ctx key :invalid-key"
              (swap! atom assoc :invalid-key "value")))
        (finally
          (close!))))))

(deftest validation-missing-spec-test
  (testing "rejects keys without registered spec"
    (let [{::ctx/keys [atom close!]} (ctx/make-persisted-ctx
                                       {::ctx/db *test-node*
                                        ::ctx/namespace 'test.missing-spec})]
      (try
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"No spec registered for key"
              (swap! atom assoc :unregistered.ns/key "value")))
        (finally
          (close!))))))

(deftest validation-spec-failure-test
  (testing "rejects values that fail spec validation"
    (let [{::ctx/keys [atom close!]} (ctx/make-persisted-ctx
                                       {::ctx/db *test-node*
                                        ::ctx/namespace 'test.spec-failure})]
      (try
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Value for :test.ctx/value failed validation"
              (swap! atom assoc :test.ctx/value "not-an-int")))
        (finally
          (close!))))))

(deftest validation-reserved-key-modification-test
  (testing "rejects modification of reserved :seon.agent/* keys"
    (let [{::ctx/keys [atom close!]} (ctx/make-persisted-ctx
                                       {::ctx/db *test-node*
                                        ::ctx/namespace 'test.reserved})]
      (try
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Cannot modify reserved key"
              (swap! atom assoc :seon.agent/namespace 'hacked)))
        (finally
          (close!))))))

(deftest validation-reserved-key-removal-test
  (testing "rejects removal of reserved :seon.agent/* keys"
    (let [{::ctx/keys [atom close!]} (ctx/make-persisted-ctx
                                       {::ctx/db *test-node*
                                        ::ctx/namespace 'test.reserved-removal})]
      (try
        (is (thrown-with-msg?
              clojure.lang.ExceptionInfo
              #"Cannot remove reserved key"
              (swap! atom dissoc :seon.agent/namespace)))
        (finally
          (close!))))))

(deftest validation-valid-update-test
  (testing "accepts valid updates with registered schemas"
    (let [{::ctx/keys [atom close!]} (ctx/make-persisted-ctx
                                       {::ctx/db *test-node*
                                        ::ctx/namespace 'test.valid})]
      (try
        ;; This should succeed
        (swap! atom assoc :test.ctx/value 42)
        (is (= 42 (:test.ctx/value @atom)))

        (swap! atom assoc :test.ctx/name "test-name")
        (is (= "test-name" (:test.ctx/name @atom)))

        (swap! atom assoc :test.ctx/signals [{:symbol "AAPL" :direction :long}])
        (is (= [{:symbol "AAPL" :direction :long}] (:test.ctx/signals @atom)))
        (finally
          (close!))))))

;;; ---------------------------------------------------------------------------
;;; Reserved Keys Tests
;;; ---------------------------------------------------------------------------

(deftest reserved-keys-present-test
  (testing "ctx contains reserved keys on creation"
    (let [{::ctx/keys [atom close!]} (ctx/make-persisted-ctx
                                       {::ctx/db *test-node*
                                        ::ctx/namespace 'test.reserved-present})]
      (try
        (is (= 'test.reserved-present (:seon.agent/namespace @atom)))
        (is (some? (:seon.agent/db @atom)))
        (finally
          (close!))))))

;;; ---------------------------------------------------------------------------
;;; Persistence Tests
;;; ---------------------------------------------------------------------------

(deftest persistence-after-debounce-test
  (testing "valid swap! persists after debounce"
    (let [{::ctx/keys [atom flush! close!]} (ctx/make-persisted-ctx
                                               {::ctx/db *test-node*
                                                ::ctx/namespace 'test.persist
                                                ::ctx/debounce-ms 100})]
      (try
        ;; Make an update
        (swap! atom assoc :test.ctx/value 123)

        ;; Force flush (don't wait for debounce)
        (flush!)

        ;; Give the async persist time to complete
        (Thread/sleep 200)

        ;; Check it was persisted
        (let [result (ctx/load-latest {::ctx/db *test-node*
                                       ::ctx/namespace 'test.persist})]
          (is (some? (::ctx/state result)))
          (is (= 123 (:test.ctx/value (::ctx/state result)))))
        (finally
          (close!))))))

(deftest persistence-flush-test
  (testing "flush! immediately persists pending state"
    (let [{::ctx/keys [atom flush! close!]} (ctx/make-persisted-ctx
                                               {::ctx/db *test-node*
                                                ::ctx/namespace 'test.flush
                                                ::ctx/debounce-ms 5000})]  ; Long debounce
      (try
        (swap! atom assoc :test.ctx/value 456)

        ;; Flush immediately (don't wait 5 seconds)
        (flush!)
        (Thread/sleep 100)

        ;; Should be persisted
        (let [result (ctx/load-latest {::ctx/db *test-node*
                                       ::ctx/namespace 'test.flush})]
          (is (= 456 (:test.ctx/value (::ctx/state result)))))
        (finally
          (close!))))))

;;; ---------------------------------------------------------------------------
;;; Time Travel Tests
;;; ---------------------------------------------------------------------------

(deftest at-returns-historical-state-test
  (testing "at returns correct historical state"
    (let [{::ctx/keys [atom flush! close!]} (ctx/make-persisted-ctx
                                               {::ctx/db *test-node*
                                                ::ctx/namespace 'test.at
                                                ::ctx/debounce-ms 100})]
      (try
        ;; Create first snapshot
        (swap! atom assoc :test.ctx/value 1)
        (flush!)
        (Thread/sleep 150)

        ;; Record time after first snapshot
        (let [time1 (java.util.Date.)]
          (Thread/sleep 50)

          ;; Create second snapshot
          (swap! atom assoc :test.ctx/value 2)
          (flush!)
          (Thread/sleep 150)

          ;; Query at time1 - should get value 1
          (let [result (ctx/at {::ctx/db *test-node*
                                ::ctx/namespace 'test.at
                                ::ctx/instant time1})]
            (is (= 1 (:test.ctx/value (::ctx/state result))))))
        (finally
          (close!))))))

(deftest history-returns-all-snapshots-test
  (testing "history returns all historical snapshots"
    (let [{::ctx/keys [atom flush! close!]} (ctx/make-persisted-ctx
                                               {::ctx/db *test-node*
                                                ::ctx/namespace 'test.history
                                                ::ctx/debounce-ms 100})]
      (try
        ;; Create multiple snapshots
        (swap! atom assoc :test.ctx/value 10)
        (flush!)
        (Thread/sleep 150)

        (swap! atom assoc :test.ctx/value 20)
        (flush!)
        (Thread/sleep 150)

        (swap! atom assoc :test.ctx/value 30)
        (flush!)
        (Thread/sleep 150)

        ;; Get history
        (let [result (ctx/history {::ctx/db *test-node*
                                   ::ctx/namespace 'test.history})
              snapshots (::ctx/snapshots result)]
          (is (>= (count snapshots) 3) "Should have at least 3 snapshots")
          ;; Values should be in chronological order
          (let [values (mapv #(:test.ctx/value (::ctx/state %)) snapshots)]
            (is (= [10 20 30] (take-last 3 values)))))
        (finally
          (close!))))))

;;; ---------------------------------------------------------------------------
;;; Restore Tests
;;; ---------------------------------------------------------------------------

(deftest restore-does-not-create-snapshot-test
  (testing "restore! does NOT create a new snapshot"
    (let [{::ctx/keys [atom flush! close!]} (ctx/make-persisted-ctx
                                               {::ctx/db *test-node*
                                                ::ctx/namespace 'test.restore-no-persist
                                                ::ctx/debounce-ms 100})]
      (try
        ;; Create initial snapshot
        (swap! atom assoc :test.ctx/value 100)
        (flush!)
        (Thread/sleep 150)

        ;; Record time and count
        (let [time-before (java.util.Date.)
              history-before (ctx/history {::ctx/db *test-node*
                                           ::ctx/namespace 'test.restore-no-persist})
              count-before (count (::ctx/snapshots history-before))]

          (Thread/sleep 50)

          ;; Create second snapshot
          (swap! atom assoc :test.ctx/value 200)
          (flush!)
          (Thread/sleep 150)

          ;; Restore to earlier time
          (ctx/restore! {::ctx/atom atom
                         ::ctx/db *test-node*
                         ::ctx/namespace 'test.restore-no-persist
                         ::ctx/instant time-before})

          ;; Give time for any errant persist
          (Thread/sleep 200)

          ;; Count should be same as after second snapshot (not increased)
          (let [history-after (ctx/history {::ctx/db *test-node*
                                            ::ctx/namespace 'test.restore-no-persist})
                count-after (count (::ctx/snapshots history-after))]
            ;; Should have exactly 2 snapshots (initial + second), not 3
            (is (= 2 count-after) "restore! should not create new snapshot")))
        (finally
          (close!))))))

(deftest restore-sets-atom-state-test
  (testing "restore! sets atom to historical state"
    (let [{::ctx/keys [atom flush! close!]} (ctx/make-persisted-ctx
                                               {::ctx/db *test-node*
                                                ::ctx/namespace 'test.restore-state
                                                ::ctx/debounce-ms 100})]
      (try
        ;; Create first snapshot with value 111
        (swap! atom assoc :test.ctx/value 111)
        (flush!)
        (Thread/sleep 150)

        ;; Record time after first snapshot
        (let [time1 (java.util.Date.)]
          (Thread/sleep 50)

          ;; Update to value 222
          (swap! atom assoc :test.ctx/value 222)
          (flush!)
          (Thread/sleep 150)

          ;; Verify current value is 222
          (is (= 222 (:test.ctx/value @atom)))

          ;; Restore to time1
          (ctx/restore! {::ctx/atom atom
                         ::ctx/db *test-node*
                         ::ctx/namespace 'test.restore-state
                         ::ctx/instant time1})

          ;; Atom should now have value 111
          (is (= 111 (:test.ctx/value @atom))))
        (finally
          (close!))))))

(deftest swap-after-restore-persists-test
  (testing "swap! after restore DOES persist"
    (let [{::ctx/keys [atom flush! close!]} (ctx/make-persisted-ctx
                                               {::ctx/db *test-node*
                                                ::ctx/namespace 'test.swap-after-restore
                                                ::ctx/debounce-ms 100})]
      (try
        ;; Create initial snapshot
        (swap! atom assoc :test.ctx/value 500)
        (flush!)
        (Thread/sleep 150)

        (let [time1 (java.util.Date.)]
          (Thread/sleep 50)

          ;; Update
          (swap! atom assoc :test.ctx/value 600)
          (flush!)
          (Thread/sleep 150)

          ;; Restore
          (ctx/restore! {::ctx/atom atom
                         ::ctx/db *test-node*
                         ::ctx/namespace 'test.swap-after-restore
                         ::ctx/instant time1})

          ;; Now swap again - this should persist
          (swap! atom assoc :test.ctx/value 700)
          (flush!)
          (Thread/sleep 150)

          ;; Verify it persisted
          (let [result (ctx/load-latest {::ctx/db *test-node*
                                         ::ctx/namespace 'test.swap-after-restore})]
            (is (= 700 (:test.ctx/value (::ctx/state result))))))
        (finally
          (close!))))))

;;; ---------------------------------------------------------------------------
;;; Load Latest Tests
;;; ---------------------------------------------------------------------------

(deftest load-latest-returns-nil-when-empty-test
  (testing "load-latest returns nil state when no snapshots exist"
    (let [result (ctx/load-latest {::ctx/db *test-node*
                                   ::ctx/namespace 'test.nonexistent})]
      (is (nil? (::ctx/state result)))
      (is (nil? (::ctx/system-time result))))))

(deftest load-latest-returns-most-recent-test
  (testing "load-latest returns the most recent state"
    (let [{::ctx/keys [atom flush! close!]} (ctx/make-persisted-ctx
                                               {::ctx/db *test-node*
                                                ::ctx/namespace 'test.load-latest
                                                ::ctx/debounce-ms 100})]
      (try
        ;; Create multiple snapshots
        (swap! atom assoc :test.ctx/value 1)
        (flush!)
        (Thread/sleep 150)

        (swap! atom assoc :test.ctx/value 2)
        (flush!)
        (Thread/sleep 150)

        (swap! atom assoc :test.ctx/value 3)
        (flush!)
        (Thread/sleep 150)

        ;; Load latest should get value 3
        (let [result (ctx/load-latest {::ctx/db *test-node*
                                       ::ctx/namespace 'test.load-latest})]
          (is (= 3 (:test.ctx/value (::ctx/state result)))))
        (finally
          (close!))))))

;;; ---------------------------------------------------------------------------
;;; Startup Recovery Tests
;;; ---------------------------------------------------------------------------

(deftest startup-loads-latest-state-test
  (testing "new ctx loads latest persisted state on startup"
    ;; Create first ctx and persist some state
    (let [{::ctx/keys [atom flush! close!]} (ctx/make-persisted-ctx
                                               {::ctx/db *test-node*
                                                ::ctx/namespace 'test.recovery
                                                ::ctx/debounce-ms 100})]
      (try
        (swap! atom assoc :test.ctx/value 999)
        (swap! atom assoc :test.ctx/name "recovered")
        (flush!)
        (Thread/sleep 200)
        (finally
          (close!))))

    ;; Create second ctx for same namespace - should recover state
    (let [{::ctx/keys [atom close!]} (ctx/make-persisted-ctx
                                        {::ctx/db *test-node*
                                         ::ctx/namespace 'test.recovery
                                         ::ctx/debounce-ms 100})]
      (try
        (is (= 999 (:test.ctx/value @atom)))
        (is (= "recovered" (:test.ctx/name @atom)))
        (finally
          (close!))))))

(comment
  ;; Run all tests
  (clojure.test/run-tests 'seon.agent.ctx-test)

  ;; Run specific test
  (clojure.test/test-var #'validation-non-namespaced-key-test)
  (clojure.test/test-var #'restore-does-not-create-snapshot-test))
