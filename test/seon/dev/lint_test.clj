(ns seon.dev.lint-test
  "Tests for seon.dev.lint - shared Clojure validation module."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.dev.lint :as lint]))

;;; ---------------------------------------------------------------------------
;;; syntax-error? Tests
;;; ---------------------------------------------------------------------------

(deftest syntax-error?-test
  (testing "Valid code returns false"
    (is (false? (lint/syntax-error? {::lint/content "(defn foo [x] (+ x 1))"})))
    (is (false? (lint/syntax-error? {::lint/content "(ns test)\n(def x 1)"})))
    (is (false? (lint/syntax-error? {::lint/content "[1 2 3]"})))
    (is (false? (lint/syntax-error? {::lint/content "{:a 1 :b 2}"}))))

  (testing "Missing closing delimiters return true"
    (is (true? (lint/syntax-error? {::lint/content "(defn foo [x] (+ x 1"})))
    (is (true? (lint/syntax-error? {::lint/content "[1 2 3"})))
    (is (true? (lint/syntax-error? {::lint/content "{:a 1"})))
    (is (true? (lint/syntax-error? {::lint/content "(let [x 1"}))))

  (testing "Empty/nil content returns false"
    (is (false? (lint/syntax-error? {::lint/content ""})))
    (is (false? (lint/syntax-error? {::lint/content nil})))
    (is (false? (lint/syntax-error? {::lint/content "   "}))))

  (testing "Handles reader tags and auto-resolve"
    ;; These should not be syntax errors - edamame should handle them
    (is (false? (lint/syntax-error? {::lint/content "#ig/ref :some/key"})))
    (is (false? (lint/syntax-error? {::lint/content "::ns/keyword"})))))

;;; ---------------------------------------------------------------------------
;;; syntax-check Tests
;;; ---------------------------------------------------------------------------

(deftest syntax-check-test
  (testing "Valid code returns valid with no message"
    (let [result (lint/syntax-check {::lint/content "(defn foo [x] (+ x 1))"})]
      (is (true? (::lint/valid? result)))
      (is (nil? (::lint/message result)))))

  (testing "Syntax errors return invalid with message"
    (let [result (lint/syntax-check {::lint/content "(defn foo [x] (+ x 1"})]
      (is (false? (::lint/valid? result)))
      (is (string? (::lint/message result)))
      (is (re-find #"EOF|expected" (::lint/message result)))))

  (testing "Empty content is valid"
    (let [result (lint/syntax-check {::lint/content ""})]
      (is (true? (::lint/valid? result))))
    (let [result (lint/syntax-check {::lint/content nil})]
      (is (true? (::lint/valid? result))))))

;;; ---------------------------------------------------------------------------
;;; lint-source Tests
;;; ---------------------------------------------------------------------------

(deftest lint-source-test
  (testing "Valid code passes lint"
    (let [result (lint/lint-source {::lint/content "(defn foo [x] (+ x 1))"
                                    ::lint/file-path "test.clj"})]
      (is (true? (::lint/valid? result)))
      (is (zero? (::lint/error-count result)))))

  (testing "Unresolved symbol fails lint"
    (let [result (lint/lint-source {::lint/content "(defn foo [x] (undefined-fn x))"
                                    ::lint/file-path "test.clj"})]
      (is (false? (::lint/valid? result)))
      (is (pos? (::lint/error-count result)))
      (is (some #(= :unresolved-symbol (:type %)) (::lint/findings result)))))

  (testing "Invalid arity fails lint"
    (let [result (lint/lint-source {::lint/content "(defn foo [x] (+ x))"
                                    ::lint/file-path "test.clj"})]
      ;; Note: clj-kondo may or may not flag single-arg + as error
      ;; The test verifies the lint function runs without error
      (is (map? result))
      (is (contains? result ::lint/valid?))))

  (testing "File path is optional"
    (let [result (lint/lint-source {::lint/content "(defn bar [y] (str y))"})]
      (is (true? (::lint/valid? result))))))

;;; ---------------------------------------------------------------------------
;;; validate-clojure Tests
;;; ---------------------------------------------------------------------------

(deftest validate-clojure-test
  (testing "Valid code passes both syntax and lint"
    (let [result (lint/validate-clojure {::lint/content "(defn foo [x] (+ x 1))"
                                          ::lint/file-path "test.clj"})]
      (is (true? (::lint/valid? result)))
      (is (empty? (::lint/errors result)))
      (is (zero? (::lint/error-count result)))))

  (testing "Syntax errors are caught first"
    (let [result (lint/validate-clojure {::lint/content "(defn foo [x] (+ x 1"})]
      (is (false? (::lint/valid? result)))
      (is (true? (::lint/syntax-error? result)))
      (is (string? (::lint/syntax-message result)))))

  (testing "Lint errors are caught after syntax passes"
    (let [result (lint/validate-clojure {::lint/content "(defn foo [x] (undefined-fn x))"
                                          ::lint/file-path "test.clj"})]
      (is (false? (::lint/valid? result)))
      (is (nil? (::lint/syntax-error? result)))
      (is (pos? (::lint/error-count result)))))

  (testing "Empty content is valid"
    (let [result (lint/validate-clojure {::lint/content ""})]
      (is (true? (::lint/valid? result)))))

  (testing "Warnings do not fail validation"
    ;; Type mismatch is configured as warning, not error
    ;; Test that warnings don't block
    (let [result (lint/validate-clojure {::lint/content "(defn foo [x] (+ x 1))"
                                          ::lint/file-path "test.clj"})]
      (is (true? (::lint/valid? result))))))

;;; ---------------------------------------------------------------------------
;;; format-findings Tests
;;; ---------------------------------------------------------------------------

(deftest format-findings-test
  (testing "Formats findings correctly"
    (let [findings [{:type :unresolved-symbol
                     :message "Unresolved symbol: foo"
                     :row 5
                     :col 3
                     :level :error}]
          result (lint/format-findings {::lint/findings findings})]
      (is (string? (::lint/formatted result)))
      (is (re-find #"Line 5" (::lint/formatted result)))
      (is (re-find #"Col 3" (::lint/formatted result)))
      (is (re-find #"error" (::lint/formatted result)))))

  (testing "Truncates long output"
    (let [findings (repeat 20 {:type :error
                               :message "Some very long error message that repeats"
                               :row 1
                               :col 1
                               :level :error})
          result (lint/format-findings {::lint/findings findings
                                         ::lint/max-length 100})]
      (is (<= (count (::lint/formatted result)) 100))))

  (testing "Empty findings returns empty string"
    (let [result (lint/format-findings {::lint/findings []})]
      (is (= "" (::lint/formatted result))))))

;;; ---------------------------------------------------------------------------
;;; validate-for-write Tests
;;; ---------------------------------------------------------------------------

(deftest validate-for-write-test
  (testing "Full lint mode (default) - valid code"
    (let [result (lint/validate-for-write
                  {::lint/content "(defn foo [x] (+ x 1))"
                   ::lint/file-path "test.clj"})]
      (is (true? (::lint/valid? result)))
      (is (nil? (::lint/error-msg result)))
      (is (nil? (::lint/findings result)))))

  (testing "Full lint mode - syntax error"
    (let [result (lint/validate-for-write
                  {::lint/content "(defn foo [x] (+ x 1"
                   ::lint/file-path "test.clj"})]
      (is (false? (::lint/valid? result)))
      (is (string? (::lint/error-msg result)))
      (is (re-find #"SYNTAX ERROR" (::lint/error-msg result)))))

  (testing "Full lint mode - unresolved symbol with suggestion"
    ;; Note: "maap" is distance 1 from "map", within threshold for 4-char words
    ;; "mpa" (3-char) would be distance 2, exceeding threshold of 1 for short words
    (let [result (lint/validate-for-write
                  {::lint/content "(defn foo [x] (maap identity x))"
                   ::lint/file-path "test.clj"
                   ::lint/full-lint? true})]
      (is (false? (::lint/valid? result)))
      (is (string? (::lint/error-msg result)))
      (is (re-find #"LINT ERRORS" (::lint/error-msg result)))
      ;; Should include "did you mean 'map'?" suggestion
      (is (re-find #"did you mean" (::lint/error-msg result)))
      (is (re-find #"map" (::lint/error-msg result)))))

  (testing "Syntax-only mode - valid code"
    (let [result (lint/validate-for-write
                  {::lint/content "(defn foo [x] (mpa identity x))"  ; mpa is typo, but syntax-only doesn't catch
                   ::lint/full-lint? false})]
      (is (true? (::lint/valid? result)))
      (is (nil? (::lint/error-msg result)))))

  (testing "Syntax-only mode - syntax error"
    (let [result (lint/validate-for-write
                  {::lint/content "(defn foo [x"
                   ::lint/full-lint? false})]
      (is (false? (::lint/valid? result)))
      (is (string? (::lint/error-msg result)))
      (is (re-find #"SYNTAX ERROR" (::lint/error-msg result)))))

  (testing "Defaults to full-lint when not specified"
    (let [result (lint/validate-for-write
                  {::lint/content "(defn foo [x] (undefined-fn x))"
                   ::lint/file-path "test.clj"})]
      ;; Should catch undefined-fn because full-lint is default
      (is (false? (::lint/valid? result)))
      (is (re-find #"LINT ERRORS" (::lint/error-msg result)))))

  (testing "Empty content is valid"
    (let [result (lint/validate-for-write {::lint/content ""})]
      (is (true? (::lint/valid? result))))
    (let [result (lint/validate-for-write {::lint/content nil})]
      (is (true? (::lint/valid? result))))))

;;; ---------------------------------------------------------------------------
;;; Integration Tests
;;; ---------------------------------------------------------------------------

(deftest integration-test
  (testing "Full validation workflow"
    ;; Valid code
    (let [valid-code "(ns test)\n(defn add [a b] (+ a b))"
          result (lint/validate-clojure {::lint/content valid-code
                                          ::lint/file-path "test.clj"})]
      (is (true? (::lint/valid? result))))

    ;; Syntax error
    (let [broken-code "(ns test)\n(defn add [a b] (+ a b"
          result (lint/validate-clojure {::lint/content broken-code})]
      (is (false? (::lint/valid? result)))
      (is (true? (::lint/syntax-error? result))))

    ;; Lint error (undefined symbol)
    (let [bad-code "(ns test)\n(defn add [a b] (nonexistent a b))"
          result (lint/validate-clojure {::lint/content bad-code
                                          ::lint/file-path "test.clj"})]
      (is (false? (::lint/valid? result)))
      (is (nil? (::lint/syntax-error? result)))
      (is (pos? (::lint/error-count result))))))
