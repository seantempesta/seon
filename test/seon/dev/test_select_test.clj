(ns seon.dev.test-select-test
  "Tests for dependency-aware test selection."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.dev.test-select :as ts]
            [seon.graph.analyzer :as analyzer]
            [seon.graph.ingest :as ingest]
            [seon.graph.query :as gq])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures - populated graph from real project analysis
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-conn* nil)

(defn- temp-dir []
  (let [dir (File/createTempFile "seon-test-select-test" "")]
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
        conn (d/create-conn dir ingest/datalevin-schema)]
    (try
      ;; Analyze just graph/ namespace for speed - has known dependency chain:
      ;; analyzer <- ingest, query (both depend on analyzer)
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
;;; affected-namespaces tests
;;; ---------------------------------------------------------------------------

(deftest affected-namespaces-test
  (testing "includes the changed namespace itself"
    (let [affected (ts/affected-namespaces
                    {::ts/conn *test-conn*
                     ::ts/ns-name "seon.graph.analyzer"})]
      (is (= "seon.graph.analyzer" (first affected))
          "changed ns should be first")))

  (testing "includes direct dependents"
    (let [affected (ts/affected-namespaces
                    {::ts/conn *test-conn*
                     ::ts/ns-name "seon.graph.analyzer"})]
      (is (some #(= "seon.graph.ingest" %) affected)
          "ingest depends on analyzer")))

  (testing "returns just the ns when nothing depends on it"
    (let [affected (ts/affected-namespaces
                    {::ts/conn *test-conn*
                     ::ts/ns-name "nonexistent.ns"})]
      (is (= ["nonexistent.ns"] affected))))

  (testing "transitive mode finds deeper dependents"
    (let [affected (ts/affected-namespaces
                    {::ts/conn *test-conn*
                     ::ts/ns-name "seon.graph.analyzer"
                     ::ts/depth :transitive})]
      ;; Should still include analyzer and ingest at minimum
      (is (some #(= "seon.graph.analyzer" %) affected))
      (is (>= (count affected) 2)))))

;;; ---------------------------------------------------------------------------
;;; affected-test-namespaces tests
;;; ---------------------------------------------------------------------------

(deftest affected-test-namespaces-test
  (testing "maps affected namespaces to test namespaces that exist"
    (let [test-nses (ts/affected-test-namespaces
                     {::ts/conn *test-conn*
                      ::ts/ns-name "seon.graph.analyzer"})]
      (is (vector? test-nses))
      ;; seon.graph.analyzer-test exists on classpath
      (is (some #(= 'seon.graph.analyzer-test %) test-nses)
          "analyzer-test should be found")))

  (testing "filters out namespaces without tests"
    (let [test-nses (ts/affected-test-namespaces
                     {::ts/conn *test-conn*
                      ::ts/ns-name "nonexistent.ns"})]
      (is (empty? test-nses)
          "nonexistent ns should have no test counterpart"))))

;;; ---------------------------------------------------------------------------
;;; run-affected-tests! tests
;;; ---------------------------------------------------------------------------

(deftest run-affected-tests-basic-test
  (testing "returns success map when no tests to run"
    (let [result (ts/run-affected-tests!
                  {::ts/conn *test-conn*
                   ::ts/ns-name "nonexistent.ns"})]
      (is (true? (::ts/success result)))
      (is (zero? (::ts/total-tests result)))
      (is (= [] (::ts/namespaces-tested result)))))

  (testing "falls back without graph conn"
    (let [result (ts/run-affected-tests!
                  {::ts/conn nil
                   ::ts/ns-name "nonexistent.ns"})]
      (is (true? (::ts/success result)))
      (is (= [] (::ts/namespaces-tested result))))))

(deftest run-affected-tests-real-test
  (testing "runs real tests for a known namespace"
    (let [result (ts/run-affected-tests!
                  {::ts/conn *test-conn*
                   ::ts/ns-name "seon.graph.query"})]
      (is (contains? result ::ts/success))
      (is (vector? (::ts/namespaces-tested result)))
      ;; Should have run at least query-test
      (is (some #(= 'seon.graph.query-test %) (::ts/namespaces-tested result))))))

;;; ---------------------------------------------------------------------------
;;; transitive-dependents-of tests (in query ns, tested here)
;;; ---------------------------------------------------------------------------

(deftest transitive-dependents-of-test
  (testing "finds transitive dependents"
    (let [deps (gq/transitive-dependents-of
                {::gq/conn *test-conn*
                 ::gq/ns-name "seon.graph.analyzer"})]
      (is (vector? deps))
      ;; ingest depends on analyzer directly
      (is (some #(= "seon.graph.ingest" %) deps))))

  (testing "returns empty for namespace with no dependents"
    (let [deps (gq/transitive-dependents-of
                {::gq/conn *test-conn*
                 ::gq/ns-name "nonexistent.ns"})]
      (is (= [] deps))))

  (testing "does not include the input namespace"
    (let [deps (gq/transitive-dependents-of
                {::gq/conn *test-conn*
                 ::gq/ns-name "seon.graph.analyzer"})]
      (is (not (some #(= "seon.graph.analyzer" %) deps))))))
