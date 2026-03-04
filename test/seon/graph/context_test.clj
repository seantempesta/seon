(ns seon.graph.context-test
  "Tests for seon.graph.context — topological context builder."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.graph.analyzer :as analyzer]
            [seon.graph.ingest :as ingest]
            [seon.graph.context :as ctx])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures (same pattern as query_test.clj)
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-conn* nil)

(defn- temp-dir []
  (let [dir (File/createTempFile "seon-graph-context-test" "")]
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
;;; pull-subgraph Tests
;;; ---------------------------------------------------------------------------

(deftest pull-subgraph-test
  (testing "pulls related entities from a known function"
    (let [entities (ctx/pull-subgraph {::ctx/conn *test-conn*
                                       ::ctx/seed "seon.graph.query/call-graph"
                                       ::ctx/depth 2})]
      (is (seq entities) "Should return some entities")
      (is (some #(= :fn (:context/type %)) entities)
          "Should include function entities")
      ;; The seed itself should be in the result
      (is (some #(= "seon.graph.query/call-graph" (:seon.fn/qualified-name %)) entities)
          "Should include the seed function")))

  (testing "returns empty for nonexistent seed"
    (let [entities (ctx/pull-subgraph {::ctx/conn *test-conn*
                                       ::ctx/seed "nonexistent/fn"
                                       ::ctx/depth 1})]
      (is (empty? entities))))

  (testing "depth 1 produces smaller subgraph than depth 2"
    (let [d1 (ctx/pull-subgraph {::ctx/conn *test-conn*
                                  ::ctx/seed "seon.graph.analyzer/extract-entities"
                                  ::ctx/depth 1})
          d2 (ctx/pull-subgraph {::ctx/conn *test-conn*
                                  ::ctx/seed "seon.graph.analyzer/extract-entities"
                                  ::ctx/depth 2})]
      (is (<= (count d1) (count d2))
          "Depth 2 should include at least as many entities as depth 1")))

  (testing "max-entities caps output"
    (let [entities (ctx/pull-subgraph {::ctx/conn *test-conn*
                                       ::ctx/seed "seon.graph.analyzer/extract-entities"
                                       ::ctx/depth 3
                                       ::ctx/max-entities 3})]
      (is (<= (count entities) 3)
          "Should respect max-entities cap"))))

;;; ---------------------------------------------------------------------------
;;; toposort Tests
;;; ---------------------------------------------------------------------------

(deftest toposort-test
  (testing "produces valid ordering — no entity appears before its dependencies"
    (let [entities (ctx/pull-subgraph {::ctx/conn *test-conn*
                                       ::ctx/seed "seon.graph.analyzer/extract-entities"
                                       ::ctx/depth 2})
          db @*test-conn*
          sorted (ctx/toposort entities db)
          ;; Build position map for functions
          fn-sorted (filter #(= :fn (:context/type %)) sorted)
          pos (into {} (map-indexed (fn [i e] [(:seon.fn/qualified-name e) i]) fn-sorted))]
      (is (seq sorted) "Should produce non-empty result")
      ;; Specs should come first
      (let [first-fn-idx (some (fn [[i e]] (when (= :fn (:context/type e)) i))
                               (map-indexed vector sorted))
            last-spec-idx (some (fn [[i e]] (when (= :spec (:context/type e)) i))
                                (reverse (map-indexed vector sorted)))]
        (when (and first-fn-idx last-spec-idx)
          (is (< last-spec-idx first-fn-idx)
              "Specs should appear before functions"))))))

;;; ---------------------------------------------------------------------------
;;; build Tests
;;; ---------------------------------------------------------------------------

(deftest build-test
  (testing "produces non-empty context text"
    (let [result (ctx/build {::ctx/conn *test-conn*
                             ::ctx/seed "seon.graph.query/call-graph"
                             ::ctx/depth 2})]
      (is (string? (::ctx/context-text result)))
      (is (pos? (count (::ctx/context-text result))))
      (is (pos? (::ctx/entity-count result)))
      ;; Should mention the seed function
      (is (str/includes? (::ctx/context-text result) "seon.graph.query/call-graph"))))

  (testing "returns empty context for nonexistent seed"
    (let [result (ctx/build {::ctx/conn *test-conn*
                             ::ctx/seed "nonexistent/fn"
                             ::ctx/depth 1})]
      (is (= 0 (::ctx/entity-count result)))))

  (testing "context text contains function details"
    (let [result (ctx/build {::ctx/conn *test-conn*
                             ::ctx/seed "seon.graph.analyzer/analyze-project!"
                             ::ctx/depth 1})]
      ;; Should have markdown headers
      (is (str/includes? (::ctx/context-text result) "###")))))

;;; ---------------------------------------------------------------------------
;;; build-for-namespace Tests
;;; ---------------------------------------------------------------------------

(deftest build-for-namespace-test
  (testing "includes all functions in the namespace"
    (let [result (ctx/build-for-namespace {::ctx/conn *test-conn*
                                           ::ctx/namespace "seon.graph.query"})]
      (is (string? (::ctx/context-text result)))
      (is (pos? (::ctx/entity-count result)))
      ;; Should mention multiple query functions
      (is (str/includes? (::ctx/context-text result) "call-graph"))
      (is (str/includes? (::ctx/context-text result) "callers-of"))
      (is (str/includes? (::ctx/context-text result) "functions-in-ns"))
      ;; Should have namespace header
      (is (str/includes? (::ctx/context-text result) "## Namespace: seon.graph.query"))))

  (testing "returns empty context for nonexistent namespace"
    (let [result (ctx/build-for-namespace {::ctx/conn *test-conn*
                                           ::ctx/namespace "nonexistent.ns"})]
      ;; Still has the header but no functions
      (is (str/includes? (::ctx/context-text result) "nonexistent.ns"))
      (is (= 0 (::ctx/entity-count result)))))

  (testing "max-entities caps output"
    (let [small (ctx/build-for-namespace {::ctx/conn *test-conn*
                                          ::ctx/namespace "seon.graph.analyzer"
                                          ::ctx/max-entities 3})
          large (ctx/build-for-namespace {::ctx/conn *test-conn*
                                          ::ctx/namespace "seon.graph.analyzer"
                                          ::ctx/max-entities 100})]
      (is (<= (::ctx/entity-count small) 3))
      (is (<= (::ctx/entity-count small) (::ctx/entity-count large))))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.graph.context-test)
  nil)
