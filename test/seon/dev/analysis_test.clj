(ns seon.dev.analysis-test
  "Tests for seon.dev.analysis namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.dev.analysis :as analysis]))

(deftest analyze-file-test
  (testing "analyzes valid Clojure file"
    (let [result (analysis/analyze-file
                  {::analysis/file-path "src/seon/dev/analysis.clj"})]
      (is (::analysis/success result))
      (is (= 'seon.dev.analysis (::analysis/namespace result)))
      (is (vector? (::analysis/var-definitions result)))
      (is (vector? (::analysis/var-usages result)))
      (is (vector? (::analysis/namespace-usages result)))
      (is (number? (::analysis/duration-ms result)))))

  (testing "fails gracefully on missing file"
    (let [result (analysis/analyze-file
                  {::analysis/file-path "nonexistent.clj"})]
      (is (not (::analysis/success result)))
      (is (string? (::analysis/error result)))))

  (testing "returns var definitions with expected keys"
    (let [result (analysis/analyze-file
                  {::analysis/file-path "src/seon/dev/analysis.clj"})
          var-defs (::analysis/var-definitions result)]
      (is (seq var-defs) "Should have var definitions")
      (let [first-var (first var-defs)]
        (is (:name first-var) "Var should have name")))))

(deftest callees-of-test
  (testing "extracts functions called by a specific function"
    (let [analysis (analysis/analyze-file
                    {::analysis/file-path "src/seon/dev/analysis.clj"})
          result (analysis/callees-of
                  {::analysis/analysis analysis
                   ::analysis/fn-name 'analyze-file})]
      (is (vector? (::analysis/callees result)))
      ;; analyze-file should call run-clj-kondo
      (let [callee-names (set (map :name (::analysis/callees result)))]
        (is (contains? callee-names 'run-clj-kondo)
            "analyze-file should call run-clj-kondo")))))

(deftest callers-of-test
  (testing "finds functions that call a specific function"
    (let [analysis (analysis/analyze-file
                    {::analysis/file-path "src/seon/dev/analysis.clj"})
          result (analysis/callers-of
                  {::analysis/analysis analysis
                   ::analysis/fn-name 'run-clj-kondo})]
      (is (vector? (::analysis/callers result)))
      ;; run-clj-kondo should be called by analyze-file
      (is (contains? (set (::analysis/callers result)) 'analyze-file)
          "run-clj-kondo should be called by analyze-file"))))

(deftest public-var-definitions-test
  (testing "filters to only public vars"
    (let [analysis (analysis/analyze-file
                    {::analysis/file-path "src/seon/dev/analysis.clj"})
          public-vars (analysis/public-var-definitions
                       {::analysis/analysis analysis})
          public-names (set (map :name public-vars))]
      ;; Should include public functions
      (is (contains? public-names 'analyze-file))
      (is (contains? public-names 'callees-of))
      (is (contains? public-names 'callers-of))
      ;; Should NOT include private helpers
      (is (not (contains? public-names 'run-clj-kondo))
          "Should not include private functions"))))

(deftest lint-issues-test
  (testing "returns lint issues structure"
    (let [analysis (analysis/analyze-file
                    {::analysis/file-path "src/seon/dev/analysis.clj"})
          issues (analysis/lint-issues
                  {::analysis/analysis analysis})]
      (is (number? (:error-count issues)))
      (is (number? (:warning-count issues)))
      (is (vector? (:errors issues)))
      (is (vector? (:warnings issues))))))

(comment
  ;; Run tests
  (require '[kaocha.repl :as k])
  (k/run 'seon.dev.analysis-test)

  nil)
