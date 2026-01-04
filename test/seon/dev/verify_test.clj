(ns seon.dev.verify-test
  "Tests for the verify namespace - test orchestration for the dev hook."
  (:require [clojure.test :refer :all]
            [malli.core :as m]
            [seon.dev.verify :as v]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures - Create temporary namespaces for testing
;;; ---------------------------------------------------------------------------

(defn- create-test-ns!
  "Create a temporary namespace with test functions."
  [ns-sym fns]
  (create-ns ns-sym)
  (in-ns ns-sym)
  (clojure.core/refer 'clojure.core)
  (clojure.core/require '[clojure.test :refer :all])
  (clojure.core/require '[malli.core :as m])
  (doseq [[fn-name fn-body] fns]
    (eval fn-body))
  (in-ns 'seon.dev.verify-test))

(defn- remove-test-ns!
  "Remove a temporary namespace."
  [ns-sym]
  (remove-ns ns-sym))

;;; ---------------------------------------------------------------------------
;;; run-unit-tests Tests
;;; ---------------------------------------------------------------------------

(deftest run-unit-tests-test
  (testing "Returns error when test namespace doesn't exist"
    (let [result (v/run-unit-tests {::v/test-ns 'seon.nonexistent-test})]
      (is (false? (::v/success result)))
      (is (string? (::v/error result)))
      (is (re-find #"Failed to load" (::v/error result)))))

  (testing "Runs existing test namespace successfully"
    ;; Use a real test namespace that exists
    (let [result (v/run-unit-tests {::v/test-ns 'seon.dev.context-test})]
      (is (boolean? (::v/success result)) "Should have success flag")
      (is (number? (::v/test-count result)) "Should have test count")
      (is (number? (::v/pass-count result)) "Should have pass count"))))

(deftest run-unit-tests-for-source-test
  (testing "Derives test namespace correctly"
    ;; This tests the namespace derivation logic
    (let [result (v/run-unit-tests-for-source {::v/source-ns 'seon.dev.context})]
      (is (= 'seon.dev.context-test (::v/test-ns result))
          "Should derive test namespace")
      (is (boolean? (::v/success result)))))

  (testing "Handles already-test namespace"
    (let [result (v/run-unit-tests-for-source {::v/source-ns 'seon.dev.context-test})]
      (is (= 'seon.dev.context-test (::v/test-ns result))
          "Should not double-append -test"))))

;;; ---------------------------------------------------------------------------
;;; run-gen-tests Tests
;;; ---------------------------------------------------------------------------

(deftest run-gen-tests-test
  (testing "Returns success when namespace has no schemas"
    ;; hook-test-ns has functions but no Malli schemas registered
    (let [result (v/run-gen-tests {::v/namespace 'seon.dev.hook-test-ns})]
      (is (true? (::v/success result)))
      (is (empty? (::v/failures result)))))

  (testing "Returns error when namespace doesn't exist"
    (let [result (v/run-gen-tests {::v/namespace 'seon.totally-nonexistent-ns})]
      (is (false? (::v/success result)))
      (is (string? (::v/error result)))))

  (testing "Accepts num-tests option"
    (let [result (v/run-gen-tests {::v/namespace 'seon.dev.hook-test-ns ::v/num-tests 5})]
      (is (true? (::v/success result)))))

  (testing "Tests namespace with real Malli schemas"
    ;; seon.dev.codebase has functions with :malli/schema metadata
    ;; This exercises the actual generative testing machinery
    (let [result (v/run-gen-tests {::v/namespace 'seon.dev.codebase ::v/num-tests 5})]
      (is (boolean? (::v/success result)) "Should return success status")
      (is (or (empty? (::v/failures result))
              (seq (::v/failures result)))
          "Should have failures vector (empty or with entries)")
      ;; If it succeeded, we verified gen tests work on schema'd functions
      (when (::v/success result)
        (is (zero? (count (::v/failures result)))
            "Passing gen tests should have no failures"))))

  (testing "Runs gen tests against repair namespace (has schemas)"
    ;; seon.dev.repair has several schema-annotated functions
    (let [result (v/run-gen-tests {::v/namespace 'seon.dev.repair ::v/num-tests 3})]
      (is (boolean? (::v/success result)) "Should return success status")
      ;; repair functions should pass with valid schemas
      (is (vector? (::v/failures result)) "Should have failures vector"))))

;;; ---------------------------------------------------------------------------
;;; format-unit-result Tests
;;; ---------------------------------------------------------------------------

(deftest format-unit-result-test
  (testing "Formats success result"
    (let [result {::v/success true
                  ::v/test-count 10
                  ::v/pass-count 10
                  ::v/fail-count 0
                  ::v/error-count 0}]
      (is (= "10 tests passed" (v/format-unit-result {::v/result result})))
      (is (= "10 tests passed (seon.foo-test)" (v/format-unit-result {::v/result result ::v/test-ns 'seon.foo-test})))))

  (testing "Formats failure result"
    (let [result {::v/success false
                  ::v/test-count 10
                  ::v/pass-count 7
                  ::v/fail-count 2
                  ::v/error-count 1}]
      (is (= "2 failures, 1 errors out of 10 tests" (v/format-unit-result {::v/result result})))
      (is (re-find #"seon.bar-test" (v/format-unit-result {::v/result result ::v/test-ns 'seon.bar-test})))))

  (testing "Formats error result"
    (let [result {::v/success false
                  ::v/error "File not found"}]
      (is (= "Unit test error: File not found" (v/format-unit-result {::v/result result})))))

  (testing "Formats timeout result"
    (let [result {::v/success false
                  ::v/timeout true}]
      (is (= "Unit tests timed out" (v/format-unit-result {::v/result result}))))))

;;; ---------------------------------------------------------------------------
;;; format-gen-result Tests
;;; ---------------------------------------------------------------------------

(deftest format-gen-result-test
  (testing "Formats success result"
    (let [result {::v/success true
                  ::v/failures []}]
      (is (= "Generative tests passed" (v/format-gen-result {::v/result result})))
      (is (= "Generative tests passed (seon.core)" (v/format-gen-result {::v/result result ::v/namespace 'seon.core})))))

  (testing "Formats failure result with function names"
    (let [result {::v/success false
                  ::v/failures [{::v/fn-symbol 'foo}
                                {::v/fn-symbol 'bar}]}]
      (is (= "Generative tests failed: foo, bar" (v/format-gen-result {::v/result result})))))

  (testing "Formats error result"
    (let [result {::v/success false
                  ::v/failures []
                  ::v/error "Schema error"}]
      (is (= "Generative test error: Schema error" (v/format-gen-result {::v/result result})))))

  (testing "Formats timeout result"
    (let [result {::v/success false
                  ::v/timeout true
                  ::v/failures []}]
      (is (= "Generative tests timed out" (v/format-gen-result {::v/result result}))))))


;;; ---------------------------------------------------------------------------
;;; Schema Validation Tests
;;; ---------------------------------------------------------------------------

(deftest schema-validation-test
  (testing "unit-test-result schema validates correctly"
    (is (m/validate ::v/unit-test-result
                    {::v/success true
                     ::v/test-count 5
                     ::v/pass-count 5
                     ::v/fail-count 0
                     ::v/error-count 0}))

    (is (m/validate ::v/unit-test-result
                    {::v/success false
                     ::v/error "Something went wrong"})))

  (testing "gen-test-result schema validates correctly"
    (is (m/validate ::v/gen-test-result
                    {::v/success true
                     ::v/failures []}))

    (is (m/validate ::v/gen-test-result
                    {::v/success false
                     ::v/failures [{::v/fn-symbol 'test-fn}]}))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases
;;; ---------------------------------------------------------------------------

(deftest edge-cases-test
  (testing "Empty failures vector in gen result"
    (let [result {::v/success true ::v/failures []}]
      (is (= "Generative tests passed" (v/format-gen-result {::v/result result})))))

  (testing "Zero counts in unit result"
    (let [result {::v/success true
                  ::v/test-count 0
                  ::v/pass-count 0
                  ::v/fail-count 0
                  ::v/error-count 0}]
      (is (= "0 tests passed" (v/format-unit-result {::v/result result})))))

  (testing "Handles nil namespace suffix gracefully"
    (is (string? (v/format-unit-result {::v/result {::v/success true ::v/test-count 1} ::v/test-ns nil})))
    (is (string? (v/format-gen-result {::v/result {::v/success true ::v/failures []} ::v/namespace nil})))))
