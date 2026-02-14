(ns seon.graph.query-test
  "Tests for seon.graph.query namespace.

   Uses a temporary local Datalevin database populated with project analysis."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.graph.analyzer :as analyzer]
            [seon.graph.ingest :as ingest]
            [seon.graph.query :as gq])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-conn* nil)

(defn- temp-dir []
  (let [dir (File/createTempFile "seon-graph-query-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn- delete-dir [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (doseq [child (.listFiles f)]
        (if (.isDirectory child)
          (delete-dir (.getAbsolutePath child))
          (.delete child)))
      (.delete f))))

(defn with-populated-graph [f]
  (let [dir (temp-dir)
        conn (d/get-conn dir)]
    (try
      ;; Populate graph with project analysis (just graph/ namespace for speed)
      (let [project (analyzer/analyze-project! {::analyzer/paths ["src/seon/graph/"]})
            entities (analyzer/extract-entities
                      {::analyzer/raw-analysis (::analyzer/raw-analysis project)})]
        (ingest/ingest-analysis! {::ingest/conn conn
                                  ::ingest/entities entities}))
      (binding [*test-conn* conn]
        (f))
      (finally
        (d/close conn)
        (delete-dir dir)))))

(use-fixtures :each with-populated-graph)

;;; ---------------------------------------------------------------------------
;;; Dependency Query Tests
;;; ---------------------------------------------------------------------------

(deftest dependents-of-test
  (testing "finds namespaces that depend on seon.graph.analyzer"
    (let [deps (gq/dependents-of {::gq/conn *test-conn*
                                  ::gq/ns-name "seon.graph.analyzer"})]
      (is (vector? deps))
      ;; seon.graph.ingest requires seon.graph.analyzer
      (is (some #(= "seon.graph.ingest" %) deps)
          "seon.graph.ingest should depend on seon.graph.analyzer")))

  (testing "returns empty vector for namespace with no dependents"
    (let [deps (gq/dependents-of {::gq/conn *test-conn*
                                  ::gq/ns-name "nonexistent.ns"})]
      (is (= [] deps)))))

(deftest dependencies-of-test
  (testing "finds what seon.graph.ingest depends on"
    (let [deps (gq/dependencies-of {::gq/conn *test-conn*
                                    ::gq/ns-name "seon.graph.ingest"})]
      (is (vector? deps))
      (is (some #(= "seon.graph.analyzer" %) deps)
          "seon.graph.ingest should depend on seon.graph.analyzer")
      (is (some #(= "datalevin.core" %) deps)
          "seon.graph.ingest should depend on datalevin.core")))

  (testing "returns empty vector for namespace with no dependencies"
    (let [deps (gq/dependencies-of {::gq/conn *test-conn*
                                    ::gq/ns-name "nonexistent.ns"})]
      (is (= [] deps)))))

;;; ---------------------------------------------------------------------------
;;; Call Graph Tests
;;; ---------------------------------------------------------------------------

(deftest call-graph-test
  (testing "finds what a known function calls"
    (let [calls (gq/call-graph {::gq/conn *test-conn*
                                ::gq/ns-name "seon.graph.analyzer"
                                ::gq/fn-name "analyze-project!"})]
      (is (vector? calls))
      ;; analyze-project! calls clj-kondo/run!
      (is (seq calls) "analyze-project! should call some functions")))

  (testing "returns empty vector for unknown function"
    (let [calls (gq/call-graph {::gq/conn *test-conn*
                                ::gq/ns-name "seon.graph.analyzer"
                                ::gq/fn-name "nonexistent-fn"})]
      (is (= [] calls)))))

(deftest callers-of-test
  (testing "finds callers of extract-namespace-entities"
    ;; extract-namespace-entities is called by extract-entities
    (let [callers (gq/callers-of {::gq/conn *test-conn*
                                  ::gq/ns-name "seon.graph.analyzer"
                                  ::gq/fn-name "extract-namespace-entities"})]
      (is (vector? callers))
      (is (some #(= "extract-entities" (:graph/from-var %)) callers)
          "extract-entities should call extract-namespace-entities")))

  (testing "returns empty for function with no callers"
    (let [callers (gq/callers-of {::gq/conn *test-conn*
                                  ::gq/ns-name "nonexistent"
                                  ::gq/fn-name "nobody"})]
      (is (= [] callers)))))

;;; ---------------------------------------------------------------------------
;;; Discovery Tests
;;; ---------------------------------------------------------------------------

(deftest functions-in-ns-test
  (testing "finds functions defined in seon.graph.analyzer"
    (let [fns (gq/functions-in-ns {::gq/conn *test-conn*
                                   ::gq/ns-name "seon.graph.analyzer"})]
      (is (vector? fns))
      (is (seq fns) "Should find functions in seon.graph.analyzer")
      (let [fn-names (set (map :graph/name fns))]
        (is (contains? fn-names "analyze-project!")
            "Should find analyze-project!")
        (is (contains? fn-names "analyze-form")
            "Should find analyze-form")
        (is (contains? fn-names "extract-entities")
            "Should find extract-entities"))))

  (testing "returns empty for namespace with no functions"
    (let [fns (gq/functions-in-ns {::gq/conn *test-conn*
                                   ::gq/ns-name "nonexistent.ns"})]
      (is (= [] fns)))))

(deftest search-functions-test
  (testing "finds functions matching pattern"
    (let [results (gq/search-functions {::gq/conn *test-conn*
                                        ::gq/pattern "analyze"})]
      (is (vector? results))
      (is (seq results) "Should find functions matching 'analyze'")
      (is (every? #(clojure.string/includes?
                    (clojure.string/lower-case (:graph/name %))
                    "analyze")
                  results)
          "All results should contain 'analyze' in name")))

  (testing "search is case-insensitive"
    (let [lower (gq/search-functions {::gq/conn *test-conn*
                                      ::gq/pattern "extract"})
          upper (gq/search-functions {::gq/conn *test-conn*
                                      ::gq/pattern "Extract"})]
      (is (= (set (map :graph/name lower))
             (set (map :graph/name upper)))
          "Case should not affect results")))

  (testing "returns empty for no-match pattern"
    (let [results (gq/search-functions {::gq/conn *test-conn*
                                        ::gq/pattern "zzzzzzzzzzz"})]
      (is (= [] results)))))

;;; ---------------------------------------------------------------------------
;;; Integration: Incremental + Query
;;; ---------------------------------------------------------------------------

(deftest incremental-ingest-query-test
  (testing "newly ingested form appears in queries"
    (let [form-result (analyzer/analyze-form
                       {::analyzer/source "(ns seon.graph.test-ns)\n(defn brand-new-fn [a b] (+ a b))"})
          entities (analyzer/extract-entities
                    {::analyzer/raw-analysis (::analyzer/raw-analysis form-result)})]
      (ingest/ingest-incremental! {::ingest/conn *test-conn*
                                    ::ingest/entities entities})

      ;; Should find via functions-in-ns
      (let [fns (gq/functions-in-ns {::gq/conn *test-conn*
                                     ::gq/ns-name "seon.graph.test-ns"})]
        (is (some #(= "brand-new-fn" (:graph/name %)) fns)
            "Newly ingested function should appear in functions-in-ns"))

      ;; Should find via search
      (let [results (gq/search-functions {::gq/conn *test-conn*
                                          ::gq/pattern "brand-new"})]
        (is (some #(= "brand-new-fn" (:graph/name %)) results)
            "Newly ingested function should appear in search results")))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.graph.query-test)
  nil)
