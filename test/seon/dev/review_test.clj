(ns seon.dev.review-test
  "Tests for the review namespace - AI code review for the dev hook."
  (:require [clojure.test :refer :all]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [malli.core :as m]
            [seon.dev.review :as review]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

;; Use relative paths that work from project root
(def test-file-path "src/seon/core.clj")
(def nonexistent-file "/nonexistent/path/file.clj")

;;; ---------------------------------------------------------------------------
;;; build-context Tests
;;; ---------------------------------------------------------------------------

(deftest build-context-basic-test
  (testing "Builds context with minimal input"
    (let [result (review/build-context {::review/files #{}})]
      (is (string? (::review/prompt result)))
      (is (= "" (::review/code result)) "Empty files should produce empty code")
      (is (string? (::review/test-summary result)))))

  (testing "Builds context with existing file"
    (when (.exists (io/file test-file-path))
      (let [result (review/build-context {::review/files #{test-file-path}})]
        (is (string? (::review/prompt result)))
        (is (not (str/blank? (::review/code result)))
            "Should include file contents")
        (is (str/includes? (::review/code result) test-file-path)
            "Should include file path as header"))))

  (testing "Handles nonexistent files gracefully"
    (let [result (review/build-context {::review/files #{nonexistent-file}})]
      (is (string? (::review/code result)))
      (is (str/includes? (::review/code result) "[File not found]")
          "Should indicate file not found"))))

(deftest build-context-test-results-test
  (testing "Formats successful test results"
    (let [result (review/build-context
                  {::review/files #{}
                   ::review/test-results {:seon.dev.verify/success true
                                          :seon.dev.verify/test-count 10}})]
      (is (str/includes? (::review/test-summary result) "10 passed")
          "Should show passed count")))

  (testing "Formats failed test results"
    (let [result (review/build-context
                  {::review/files #{}
                   ::review/test-results {:seon.dev.verify/success false
                                          :seon.dev.verify/test-count 10
                                          :seon.dev.verify/fail-count 2
                                          :seon.dev.verify/error-count 1}})]
      (is (str/includes? (::review/test-summary result) "2 failed")
          "Should show failure count")
      (is (str/includes? (::review/test-summary result) "1 errors")
          "Should show error count")))

  (testing "Handles nil test results"
    (let [result (review/build-context {::review/files #{}})]
      (is (str/includes? (::review/test-summary result) "not run")
          "Should indicate tests not run"))))

(deftest build-context-new-functions-test
  (testing "Includes new functions in context"
    (let [result (review/build-context
                  {::review/files #{}
                   ::review/new-functions #{'foo 'bar}})]
      (is (= #{'foo 'bar} (::review/new-functions result)))
      (is (str/includes? (::review/test-summary result) "New functions")
          "Should mention new functions")
      (is (str/includes? (::review/test-summary result) "foo")
          "Should list function names"))))

(deftest build-context-truncation-test
  (testing "Respects max code length"
    ;; Create context with very small max
    (let [result (review/build-context
                  {::review/files #{test-file-path}
                   ::review/max-code-length 100})]
      (when (.exists (io/file test-file-path))
        ;; File is longer than 100 chars, should be truncated
        (is (<= (count (::review/code result)) 120)  ; 100 + "[truncated]"
            "Should truncate code"))))

  (testing "Loads conventions with truncation"
    ;; Just verify it doesn't crash with small limit
    (let [result (review/build-context
                  {::review/files #{}
                   ::review/max-conventions-length 100})]
      (when (::review/conventions result)
        (is (<= (count (::review/conventions result)) 120))))))

;;; ---------------------------------------------------------------------------
;;; format-output Tests
;;; ---------------------------------------------------------------------------

(deftest format-output-test
  (testing "Adds Gemini prefix"
    (let [result (review/format-output {::review/text "Code looks good."})]
      (is (str/starts-with? result "Gemini: "))
      (is (str/includes? result "Code looks good."))))

  (testing "Truncates long output"
    (let [long-text (apply str (repeat 1000 "x"))
          result (review/format-output {::review/text long-text
                                        ::review/max-length 100})]
      (is (<= (count result) 130)  ; 100 + prefix + truncation marker
          "Should truncate long output")
      (is (str/includes? result "[truncated]"))))

  (testing "Preserves short output"
    (let [short-text "Short review"
          result (review/format-output {::review/text short-text})]
      (is (str/includes? result "Short review"))
      (is (not (str/includes? result "[truncated]"))))))

;;; ---------------------------------------------------------------------------
;;; Schema Validation Tests
;;; ---------------------------------------------------------------------------

(deftest schema-validation-test
  (testing "build-context-request schema"
    (is (m/validate ::review/build-context-request
                    {::review/files #{"/path/to/file.clj"}}))
    (is (m/validate ::review/build-context-request
                    {::review/files #{}
                     ::review/test-results {:success true}
                     ::review/new-functions #{'foo}
                     ::review/max-code-length 5000}))
    (is (not (m/validate ::review/build-context-request
                         {:files #{}}))  ; Wrong key ns
        "Should require namespaced keys"))

  (testing "review-context schema"
    (is (m/validate ::review/review-context
                    {::review/prompt "Review this"
                     ::review/code "(defn foo [])"}))
    (is (m/validate ::review/review-context
                    {::review/prompt "Review"
                     ::review/code ""
                     ::review/conventions "..."
                     ::review/test-summary "Tests: passed"
                     ::review/new-functions #{'foo}})))

  (testing "review-result schema"
    (is (m/validate ::review/review-result
                    {::review/success true
                     ::review/text "Looks good!"}))
    (is (m/validate ::review/review-result
                    {::review/success false
                     ::review/error "API error"})))

  (testing "format-output-request schema"
    (is (m/validate ::review/format-output-request
                    {::review/text "Review text"}))
    (is (m/validate ::review/format-output-request
                    {::review/text "Review"
                     ::review/max-length 500}))))

;;; ---------------------------------------------------------------------------
;;; call-gemini Tests (Mocked)
;;; ---------------------------------------------------------------------------

(deftest call-gemini-structure-test
  (testing "Returns proper result structure on error"
    ;; Test with missing API key (will fail but should return proper structure)
    (let [result (review/call-gemini
                  {::review/context {::review/prompt "Test"
                                     ::review/code "(+ 1 1)"}
                   ;; Use very short timeout to fail fast
                   ::review/timeout 1})]
      (is (boolean? (::review/success result))
          "Should have success boolean")
      (is (or (string? (::review/text result))
              (string? (::review/error result)))
          "Should have either text or error"))))

;;; ---------------------------------------------------------------------------
;;; review-edits Integration Test
;;; ---------------------------------------------------------------------------

(deftest review-edits-structure-test
  (testing "Returns map with expected keys"
    ;; This will likely fail due to API issues but should return expected structure
    (let [result (review/review-edits
                  {::review/files #{}
                   ::review/timeout 1})]
      (is (map? result) "Should return a map")
      (is (contains? result ::review/formatted-text) "Should have formatted-text")
      (is (contains? result ::review/prompt) "Should have prompt")
      (is (contains? result ::review/success) "Should have success flag"))))

;;; ---------------------------------------------------------------------------
;;; Edge Cases
;;; ---------------------------------------------------------------------------

(deftest edge-cases-test
  (testing "Empty files set"
    (let [result (review/build-context {::review/files #{}})]
      (is (= "" (::review/code result)))))

  (testing "Nil values in test results"
    (let [result (review/build-context
                  {::review/files #{}
                   ::review/test-results {:success nil}})]
      (is (string? (::review/test-summary result)))))

  (testing "Empty new-functions set"
    (let [result (review/build-context
                  {::review/files #{}
                   ::review/new-functions #{}})]
      ;; Empty set is preserved but should not mention in summary
      (is (not (str/includes? (::review/test-summary result) "New functions"))
          "Should not mention new functions when empty"))))

(deftest file-reading-test
  (testing "Handles mix of existing and nonexistent files"
    (when (.exists (io/file test-file-path))
      (let [result (review/build-context
                    {::review/files #{test-file-path nonexistent-file}})]
        (is (str/includes? (::review/code result) test-file-path))
        (is (str/includes? (::review/code result) "[File not found]"))))))
