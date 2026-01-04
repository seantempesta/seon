(ns seon.dev.compliance-test
  "Tests for convention compliance checking."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.ai.gemini :as gemini]
            [seon.dev.codebase :as codebase]
            [seon.dev.compliance :as compliance]
            [seon.dev.context :as context]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures - Sample functions with various compliance levels
;;; ---------------------------------------------------------------------------

;; For testing, we'll use actual namespaces in the project

;;; ---------------------------------------------------------------------------
;;; analyze-namespace tests
;;; ---------------------------------------------------------------------------

(deftest analyze-namespace-test
  (testing "analyzes a compliant namespace"
    (let [result (compliance/analyze-namespace
                  {::compliance/namespace 'seon.ai.gemini})]
      (is (map? result))
      (is (contains? result ::compliance/compliant?))
      (is (contains? result ::compliance/violations))
      (is (contains? result ::compliance/public-fns))
      (is (contains? result ::compliance/with-schema))
      (is (contains? result ::compliance/with-map-in))
      (is (pos? (::compliance/public-fns result)))
      ;; gemini.clj should have functions with schemas
      (is (pos? (::compliance/with-schema result)))))

  (testing "analyzes a now-compliant namespace"
    ;; seon.dev.codebase was converted to map-in pattern in Phase 8
    (let [result (compliance/analyze-namespace
                  {::compliance/namespace 'seon.dev.codebase})]
      (is (map? result))
      (is (pos? (::compliance/public-fns result)))
      ;; codebase.clj is now fully compliant (has schemas + map-in)
      (is (vector? (::compliance/violations result)))
      (is (empty? (::compliance/violations result)))))

  (testing "analyzes a non-compliant namespace"
    ;; seon.schema uses positional args (not map-in pattern)
    (let [result (compliance/analyze-namespace
                  {::compliance/namespace 'seon.schema})]
      (is (map? result))
      (is (pos? (::compliance/public-fns result)))
      ;; schema.clj has positional args, so should have violations
      (is (vector? (::compliance/violations result)))
      (is (seq (::compliance/violations result)))))

  (testing "handles namespace as symbol"
    (let [result (compliance/analyze-namespace
                  {::compliance/namespace 'seon.schema})]
      (is (map? result))
      (is (integer? (::compliance/public-fns result))))))

;;; ---------------------------------------------------------------------------
;;; check-function tests
;;; ---------------------------------------------------------------------------

(deftest check-function-test
  (testing "checks a compliant function"
    (let [result (compliance/check-function
                  {::compliance/var #'seon.ai.gemini/ask})]
      (is (= "ask" (::compliance/fn-name result)))
      (is (true? (::compliance/has-schema? result)))
      (is (true? (::compliance/has-docstring? result)))
      (is (true? (::compliance/uses-map-in? result)))
      (is (empty? (::compliance/violations result)))))

  (testing "checks a now-compliant function (codebase/clojure-file?)"
    ;; codebase/clojure-file? was converted to map-in pattern in Phase 8
    (let [result (compliance/check-function
                  {::compliance/var #'seon.dev.codebase/clojure-file?})]
      (is (= "clojure-file?" (::compliance/fn-name result)))
      (is (true? (::compliance/has-schema? result)))
      (is (true? (::compliance/has-docstring? result)))
      (is (true? (::compliance/uses-map-in? result)))  ; now uses map-in
      (is (empty? (::compliance/violations result)))))

  (testing "checks a non-compliant function (schema/register!)"
    ;; schema/register! uses positional args
    (let [result (compliance/check-function
                  {::compliance/var #'seon.schema/register!})]
      (is (= "register!" (::compliance/fn-name result)))
      (is (true? (::compliance/has-docstring? result)))
      ;; Should have violations since it doesn't use map-in
      (is (false? (::compliance/uses-map-in? result)))
      (is (seq (::compliance/violations result)))
      (is (some #(= :no-map-in (::compliance/violation-type %))
                (::compliance/violations result)))))

  (testing "detects docstring presence"
    ;; context/record-edit! has a docstring
    (let [result (compliance/check-function
                  {::compliance/var #'seon.dev.context/record-edit!})]
      (is (true? (::compliance/has-docstring? result)))
      ;; context.clj is now compliant, so should have no violations
      (is (true? (::compliance/has-schema? result)))
      (is (true? (::compliance/uses-map-in? result)))
      (is (empty? (::compliance/violations result))))))

;;; ---------------------------------------------------------------------------
;;; format-violations tests
;;; ---------------------------------------------------------------------------

(deftest format-violations-test
  (testing "formats empty violations"
    (let [result (compliance/format-violations
                  {::compliance/violations []})]
      (is (= "All functions comply with conventions."
             (::compliance/formatted result)))))

  (testing "formats single violation"
    (let [violations [{::compliance/fn-name "foo"
                       ::compliance/violation-type :no-malli-schema
                       ::compliance/message "foo is missing :malli/schema"}]
          result (compliance/format-violations
                  {::compliance/violations violations})]
      (is (string? (::compliance/formatted result)))
      (is (.contains (::compliance/formatted result) "Convention violations:"))
      (is (.contains (::compliance/formatted result) "foo"))
      (is (.contains (::compliance/formatted result) "missing :malli/schema"))))

  (testing "formats multiple violations for same function"
    (let [violations [{::compliance/fn-name "bar"
                       ::compliance/violation-type :no-malli-schema
                       ::compliance/message "bar is missing schema"}
                      {::compliance/fn-name "bar"
                       ::compliance/violation-type :no-map-in
                       ::compliance/message "bar not using map-in"}]
          result (compliance/format-violations
                  {::compliance/violations violations})]
      (is (.contains (::compliance/formatted result) "bar"))
      (is (.contains (::compliance/formatted result) "missing :malli/schema"))
      (is (.contains (::compliance/formatted result) "not using map-in"))))

  (testing "respects max-length"
    (let [violations (vec (for [i (range 50)]
                            {::compliance/fn-name (str "function" i)
                             ::compliance/violation-type :no-malli-schema
                             ::compliance/message (str "function" i " missing schema")}))
          result (compliance/format-violations
                  {::compliance/violations violations
                   ::compliance/max-length 100})]
      ;; Should be truncated
      (is (<= (count (::compliance/formatted result)) 112))  ; 100 + "[truncated]"
      (is (.contains (::compliance/formatted result) "[truncated]")))))

;;; ---------------------------------------------------------------------------
;;; compliance-summary tests
;;; ---------------------------------------------------------------------------

(deftest compliance-summary-test
  (testing "returns summary map"
    (let [result (compliance/compliance-summary
                  {::compliance/namespace 'seon.ai.gemini})]
      (is (map? result))
      (is (string? (::compliance/summary result)))
      (is (.contains (::compliance/summary result) "compliant"))
      (is (.contains (::compliance/summary result) "with schema"))
      (is (.contains (::compliance/summary result) "with map-in"))
      (is (integer? (::compliance/compliant-count result)))
      (is (integer? (::compliance/total-count result))))))

;;; ---------------------------------------------------------------------------
;;; Integration test
;;; ---------------------------------------------------------------------------

(deftest integration-test
  (testing "full workflow: analyze -> format (compliant namespace)"
    ;; seon.dev.codebase is now compliant after Phase 8
    (let [analysis (compliance/analyze-namespace
                    {::compliance/namespace 'seon.dev.codebase})
          formatted (compliance/format-violations
                     {::compliance/violations (::compliance/violations analysis)
                      ::compliance/max-length 500})]
      (is (map? analysis))
      (is (map? formatted))
      (is (string? (::compliance/formatted formatted)))
      ;; codebase is now compliant so no violations
      (is (empty? (::compliance/violations analysis)))))

  (testing "full workflow: analyze -> format (non-compliant namespace)"
    ;; Use seon.schema which has violations (positional args)
    (let [analysis (compliance/analyze-namespace
                    {::compliance/namespace 'seon.schema})
          formatted (compliance/format-violations
                     {::compliance/violations (::compliance/violations analysis)
                      ::compliance/max-length 500})]
      (is (map? analysis))
      (is (map? formatted))
      (is (string? (::compliance/formatted formatted)))
      ;; schema has violations so formatted should show them
      (is (.contains (::compliance/formatted formatted) "not using map-in")))))

;;; ---------------------------------------------------------------------------
;;; Deep schema verification tests (Phase 9b)
;;; ---------------------------------------------------------------------------

(deftest deep-schema-verification-test
  (testing "extracts schema refs from :malli/schema metadata"
    ;; gemini/ask has registered schemas, so should have no unregistered refs
    (let [result (compliance/check-function
                  {::compliance/var #'seon.ai.gemini/ask})]
      (is (empty? (filter #(= :unregistered-schema (::compliance/violation-type %))
                          (::compliance/violations result))))))

  (testing "detects correct naming convention"
    ;; gemini/ask follows the fn-name-request/response convention
    (let [result (compliance/check-function
                  {::compliance/var #'seon.ai.gemini/ask})]
      (is (empty? (filter #(= :wrong-naming (::compliance/violation-type %))
                          (::compliance/violations result)))))))

;;; ---------------------------------------------------------------------------
;;; Fix generation tests (Phase 9c)
;;; ---------------------------------------------------------------------------

(deftest generate-fix-test
  (testing "generate-fix returns compliant? true for compliant function"
    (let [result (compliance/generate-fix
                  {::compliance/var #'seon.ai.gemini/ask})]
      (is (true? (::compliance/compliant? result)))
      (is (nil? (::compliance/fix-code result)))))

  (testing "generate-fix returns fix code for non-compliant function"
    (let [result (compliance/generate-fix
                  {::compliance/var #'seon.schema/register!})]
      (is (false? (::compliance/compliant? result)))
      (is (string? (::compliance/fix-code result)))
      ;; Should contain schema registration suggestions
      (is (.contains (::compliance/fix-code result) "needs"))))

  (testing "format-violations with-fixes generates actionable code"
    (let [analysis (compliance/analyze-namespace
                    {::compliance/namespace 'seon.schema})
          formatted (compliance/format-violations
                     {::compliance/violations (::compliance/violations analysis)
                      ::compliance/with-fixes true
                      ::compliance/max-length 2000})]
      (is (string? (::compliance/formatted formatted)))
      ;; Should contain fix suggestions
      (is (.contains (::compliance/formatted formatted) "needs")))))
