(ns seon.graph.ingest-test
  "Tests for seon.graph.ingest namespace.

   Uses a temporary local Datalevin database (no server required)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.graph.analyzer :as analyzer]
            [seon.graph.ingest :as ingest])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-conn* nil)

(defn- temp-dir
  "Create a temporary directory for Datalevin."
  []
  (let [dir (File/createTempFile "seon-graph-test" "")]
    (.delete dir)
    (.mkdirs dir)
    (.getAbsolutePath dir)))

(defn- delete-dir
  "Recursively delete a directory."
  [^String path]
  (let [f (File. path)]
    (when (.exists f)
      (doseq [child (.listFiles f)]
        (if (.isDirectory child)
          (delete-dir (.getAbsolutePath child))
          (.delete child)))
      (.delete f))))

(defn with-temp-conn [f]
  (let [dir (temp-dir)
        conn (d/get-conn dir)]
    (try
      (binding [*test-conn* conn]
        (f))
      (finally
        (d/close conn)
        (delete-dir dir)))))

(use-fixtures :each with-temp-conn)

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(deftest ingest-analysis-test
  (testing "ingests full project analysis into Datalevin"
    (let [;; Use a small subset for speed
          project (analyzer/analyze-project! {::analyzer/paths ["src/seon/graph/"]})
          entities (analyzer/extract-entities
                    {::analyzer/raw-analysis (::analyzer/raw-analysis project)})
          result (ingest/ingest-analysis! {::ingest/conn *test-conn*
                                           ::ingest/entities entities})]
      ;; Should report counts
      (is (pos? (::ingest/namespace-count result))
          "Should ingest namespaces")
      (is (pos? (::ingest/function-count result))
          "Should ingest functions")

      ;; Verify namespaces are queryable
      (let [ns-names (d/q '[:find ?name
                            :where
                            [?e :graph/type :namespace]
                            [?e :graph/name ?name]]
                          @*test-conn*)]
        (is (seq ns-names) "Should have namespace entities in DB")
        (is (some #(= ["seon.graph.analyzer"] %) ns-names)
            "Should find seon.graph.analyzer namespace"))

      ;; Verify functions are queryable
      (let [fn-names (d/q '[:find ?name ?ns
                            :where
                            [?e :graph/type :function]
                            [?e :graph/name ?name]
                            [?e :graph/ns ?ns]]
                          @*test-conn*)]
        (is (seq fn-names) "Should have function entities in DB")
        (is (some #(= ["analyze-project!" "seon.graph.analyzer"] %) fn-names)
            "Should find analyze-project! function"))))

  (testing "re-ingesting clears stale data"
    (let [project (analyzer/analyze-project! {::analyzer/paths ["src/seon/graph/"]})
          entities (analyzer/extract-entities
                    {::analyzer/raw-analysis (::analyzer/raw-analysis project)})
          ;; Ingest twice
          _ (ingest/ingest-analysis! {::ingest/conn *test-conn*
                                      ::ingest/entities entities})
          result (ingest/ingest-analysis! {::ingest/conn *test-conn*
                                           ::ingest/entities entities})
          ;; Count namespaces -- should NOT double
          ns-count (count (d/q '[:find ?e
                                 :where
                                 [?e :graph/type :namespace]]
                               @*test-conn*))]
      (is (= (::ingest/namespace-count result) ns-count)
          "Re-ingesting should replace, not duplicate"))))

(deftest ingest-incremental-test
  (testing "incremental ingest adds new function to graph"
    (let [form-result (analyzer/analyze-form
                       {::analyzer/source "(ns my.test)\n(defn my-new-fn [x] (+ x 1))"})
          entities (analyzer/extract-entities
                    {::analyzer/raw-analysis (::analyzer/raw-analysis form-result)})
          result (ingest/ingest-incremental! {::ingest/conn *test-conn*
                                              ::ingest/entities entities})]
      (is (pos? (::ingest/function-count result)))
      ;; Query to confirm function exists
      (let [fns (d/q '[:find ?name
                        :where
                        [?e :graph/type :function]
                        [?e :graph/name ?name]
                        [?e :graph/ns "my.test"]]
                      @*test-conn*)]
        (is (some #(= ["my-new-fn"] %) fns)
            "Should find the newly ingested function"))))

  (testing "incremental ingest replaces existing function"
    ;; First ingest
    (let [form1 (analyzer/analyze-form
                 {::analyzer/source "(ns my.update-test)\n(defn updatable [x] x)"})
          entities1 (analyzer/extract-entities
                     {::analyzer/raw-analysis (::analyzer/raw-analysis form1)})]
      (ingest/ingest-incremental! {::ingest/conn *test-conn*
                                    ::ingest/entities entities1})

      ;; Second ingest with updated function
      (let [form2 (analyzer/analyze-form
                   {::analyzer/source "(ns my.update-test)\n(defn updatable [x y] (+ x y))\n(defn new-fn [z] z)"})
            entities2 (analyzer/extract-entities
                       {::analyzer/raw-analysis (::analyzer/raw-analysis form2)})]
        (ingest/ingest-incremental! {::ingest/conn *test-conn*
                                      ::ingest/entities entities2})

        ;; Should have 2 functions now (updatable + new-fn), not 3
        (let [fns (d/q '[:find ?name
                          :where
                          [?e :graph/type :function]
                          [?e :graph/ns "my.update-test"]
                          [?e :graph/name ?name]]
                        @*test-conn*)]
          (is (= 2 (count fns))
              "Should have exactly 2 functions after update")
          (is (= #{["updatable"] ["new-fn"]} (set fns))
              "Should have both updatable and new-fn"))))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.graph.ingest-test)
  nil)
