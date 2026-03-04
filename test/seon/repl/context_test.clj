(ns seon.repl.context-test
  "Tests for seon.repl.context — context cockpit for AI agents."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.graph.analyzer :as analyzer]
            [seon.graph.ingest :as ingest]
            [seon.repl.context :as context])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures (same pattern as graph tests)
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-conn* nil)

(defn- temp-dir []
  (let [dir (File/createTempFile "seon-repl-context-test" "")]
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
      (binding [*test-conn* conn
                db/*direct-write* true]
        (let [project (analyzer/analyze-project! {::analyzer/paths ["src/seon/graph/"]})
              entities (analyzer/extract-entities
                        {::analyzer/raw-analysis (::analyzer/raw-analysis project)})]
          (ingest/ingest-analysis! {::ingest/conn conn
                                    ::ingest/entities entities}))
        (f))
      (finally
        (d/close conn)
        (delete-dir dir)))))

(use-fixtures :each with-populated-graph)

;;; ---------------------------------------------------------------------------
;;; for-function Tests
;;; ---------------------------------------------------------------------------

(deftest for-function-test
  (testing "returns non-empty context string for known function"
    (let [result (context/for-function {::context/conn *test-conn*
                                         ::context/qualified-name "seon.graph.query/call-graph"})]
      (is (string? result))
      (is (pos? (count result)))
      (is (str/includes? result "seon.graph.query/call-graph"))))

  (testing "returns empty-ish context for nonexistent function"
    (let [result (context/for-function {::context/conn *test-conn*
                                         ::context/qualified-name "nonexistent/fn"})]
      (is (string? result))
      ;; Empty context is just an empty string
      (is (str/blank? result)))))

;;; ---------------------------------------------------------------------------
;;; for-namespace Tests
;;; ---------------------------------------------------------------------------

(deftest for-namespace-test
  (testing "returns context with namespace header"
    (let [result (context/for-namespace {::context/conn *test-conn*
                                          ::context/namespace "seon.graph.query"})]
      (is (string? result))
      (is (pos? (count result)))
      (is (str/includes? result "seon.graph.query"))
      ;; Should contain function names from the namespace
      (is (str/includes? result "call-graph"))))

  (testing "returns header for nonexistent namespace"
    (let [result (context/for-namespace {::context/conn *test-conn*
                                          ::context/namespace "nonexistent.ns"})]
      (is (string? result))
      (is (str/includes? result "nonexistent.ns")))))

;;; ---------------------------------------------------------------------------
;;; for-data Tests
;;; ---------------------------------------------------------------------------

(deftest for-data-test
  (testing "returns message when no renderers match"
    (let [result (context/for-data {::context/conn *test-conn*
                                     ::context/data {:some/random-key "value"}})]
      (is (string? result))
      (is (str/includes? result "No matching renderers"))))

  (testing "handles empty data map"
    (let [result (context/for-data {::context/conn *test-conn*
                                     ::context/data {}})]
      (is (string? result)))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.repl.context-test)
  nil)
