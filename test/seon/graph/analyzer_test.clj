(ns seon.graph.analyzer-test
  "Tests for seon.graph.analyzer namespace."
  (:require [clojure.test :refer [deftest is testing]]
            [seon.graph.analyzer :as analyzer]))

(deftest analyze-project-test
  (testing "analyzes src/ directory and returns analysis with namespaces and functions"
    (let [result (analyzer/analyze-project! {})]
      (is (::analyzer/success result))
      (is (map? (::analyzer/raw-analysis result)))
      (is (pos? (::analyzer/duration-ms result)))
      ;; Should have namespace definitions
      (is (seq (:namespace-definitions (::analyzer/raw-analysis result)))
          "Should find namespace definitions")
      ;; Should have var definitions (functions)
      (is (seq (:var-definitions (::analyzer/raw-analysis result)))
          "Should find var definitions")))

  (testing "accepts custom paths"
    (let [result (analyzer/analyze-project! {::analyzer/paths ["src/seon/graph/"]})]
      (is (::analyzer/success result))
      ;; Should find at least the graph namespaces
      (is (seq (:namespace-definitions (::analyzer/raw-analysis result)))))))

(deftest analyze-form-test
  (testing "analyzes a single defn form"
    (let [result (analyzer/analyze-form
                  {::analyzer/source "(defn ema [period data] (reduce + data))"})]
      (is (::analyzer/success result))
      (is (map? (::analyzer/raw-analysis result)))
      ;; Should find the var definition
      (let [var-defs (:var-definitions (::analyzer/raw-analysis result))]
        (is (seq var-defs) "Should find var definitions")
        (is (some #(= 'ema (:name %)) var-defs)
            "Should find the ema function"))))

  (testing "analyzes a form with namespace context"
    (let [result (analyzer/analyze-form
                  {::analyzer/source "(ns my.test)\n(defn foo [x] (inc x))"
                   ::analyzer/file-path "src/my/test.clj"})]
      (is (::analyzer/success result))
      (let [ns-defs (:namespace-definitions (::analyzer/raw-analysis result))]
        (is (some #(= 'my.test (:name %)) ns-defs)
            "Should find the namespace definition"))))

  (testing "handles invalid input gracefully"
    ;; clj-kondo is fairly tolerant so this may still succeed with findings
    (let [result (analyzer/analyze-form
                  {::analyzer/source "(defn"})]
      (is (::analyzer/success result)
          "clj-kondo should still produce output for partial forms"))))

(deftest extract-entities-test
  (testing "transforms project analysis into graph entities"
    (let [project (analyzer/analyze-project! {})
          entities (analyzer/extract-entities
                    {::analyzer/raw-analysis (::analyzer/raw-analysis project)})]
      ;; Should have all entity types
      (is (vector? (::analyzer/namespaces entities)))
      (is (vector? (::analyzer/functions entities)))
      (is (vector? (::analyzer/var-usages entities)))
      (is (vector? (::analyzer/namespace-usages entities)))

      ;; Should have substantial data from the project
      (is (pos? (count (::analyzer/namespaces entities)))
          "Should have namespace entities")
      (is (pos? (count (::analyzer/functions entities)))
          "Should have function entities")
      (is (pos? (count (::analyzer/var-usages entities)))
          "Should have var-usage entities")
      (is (pos? (count (::analyzer/namespace-usages entities)))
          "Should have namespace-usage entities")))

  (testing "namespace entities have correct structure"
    (let [project (analyzer/analyze-project! {::analyzer/paths ["src/seon/graph/"]})
          entities (analyzer/extract-entities
                    {::analyzer/raw-analysis (::analyzer/raw-analysis project)})
          ns-entities (::analyzer/namespaces entities)
          analyzer-ns (first (filter #(= "seon.graph.analyzer" (:graph/name %)) ns-entities))]
      (is analyzer-ns "Should find seon.graph.analyzer namespace")
      (is (= :namespace (:graph/type analyzer-ns)))
      (is (string? (:graph/name analyzer-ns)))
      (is (string? (:graph/file analyzer-ns)))))

  (testing "function entities have correct structure"
    (let [project (analyzer/analyze-project! {::analyzer/paths ["src/seon/graph/"]})
          entities (analyzer/extract-entities
                    {::analyzer/raw-analysis (::analyzer/raw-analysis project)})
          fn-entities (::analyzer/functions entities)
          analyze-fn (first (filter #(= "analyze-project!" (:graph/name %)) fn-entities))]
      (is analyze-fn "Should find analyze-project! function")
      (is (= :function (:graph/type analyze-fn)))
      (is (= "seon.graph.analyzer" (:graph/ns analyze-fn)))
      (is (boolean? (:graph/public? analyze-fn)))))

  (testing "extracts entities from single form analysis"
    (let [form-result (analyzer/analyze-form
                       {::analyzer/source "(defn ema [period data] (reduce + data))"})
          entities (analyzer/extract-entities
                    {::analyzer/raw-analysis (::analyzer/raw-analysis form-result)})
          fn-entities (::analyzer/functions entities)]
      (is (some #(= "ema" (:graph/name %)) fn-entities)
          "Should find the ema function entity")
      (let [ema-fn (first (filter #(= "ema" (:graph/name %)) fn-entities))]
        (is (= :function (:graph/type ema-fn)))
        (is (= true (:graph/public? ema-fn))))))

  (testing "handles nil analysis gracefully"
    (let [entities (analyzer/extract-entities {::analyzer/raw-analysis {}})]
      (is (= [] (::analyzer/namespaces entities)))
      (is (= [] (::analyzer/functions entities)))
      (is (= [] (::analyzer/var-usages entities)))
      (is (= [] (::analyzer/namespace-usages entities))))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.graph.analyzer-test)
  nil)
