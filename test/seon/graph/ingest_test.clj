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
        conn (d/create-conn dir ingest/datalevin-schema)]
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
                            [?e :seon.ns/name ?name]]
                          @*test-conn*)]
        (is (seq ns-names) "Should have namespace entities in DB")
        (is (some #(= ["seon.graph.analyzer"] %) ns-names)
            "Should find seon.graph.analyzer namespace"))

      ;; Verify functions are queryable
      (let [fn-data (d/q '[:find ?name ?ns
                            :where
                            [?e :seon.fn/name ?name]
                            [?e :seon.fn/namespace ?ns]]
                          @*test-conn*)]
        (is (seq fn-data) "Should have function entities in DB")
        (is (some #(= ["analyze-project!" "seon.graph.analyzer"] %) fn-data)
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
                                 [?e :seon.ns/name]]
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
                        [?e :seon.fn/name ?name]
                        [?e :seon.fn/namespace "my.test"]]
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
                          [?e :seon.fn/namespace "my.update-test"]
                          [?e :seon.fn/name ?name]]
                        @*test-conn*)]
          (is (= 2 (count fns))
              "Should have exactly 2 functions after update")
          (is (= #{["updatable"] ["new-fn"]} (set fns))
              "Should have both updatable and new-fn"))))))

(deftest ingest-namespace-retract-stale-test
  (testing "removed function gets retracted on re-ingest"
    (let [now (java.util.Date.)]
      ;; First ingest: two functions
      (ingest/ingest-namespace!
       {::ingest/conn *test-conn*
        ::ingest/ns-name "my.stale-test"
        ::ingest/functions [{:seon.fn/qualified-name "my.stale-test/foo"
                             :seon.fn/namespace "my.stale-test"
                             :seon.fn/name "foo"
                             :seon.fn/private false
                             :seon.fn/updated-at now}
                            {:seon.fn/qualified-name "my.stale-test/bar"
                             :seon.fn/namespace "my.stale-test"
                             :seon.fn/name "bar"
                             :seon.fn/private false
                             :seon.fn/updated-at now}]})
      (is (= 2 (count (d/q '[:find ?e
                               :where [?e :seon.fn/namespace "my.stale-test"]]
                             @*test-conn*))))

      ;; Second ingest: only foo remains
      (ingest/ingest-namespace!
       {::ingest/conn *test-conn*
        ::ingest/ns-name "my.stale-test"
        ::ingest/functions [{:seon.fn/qualified-name "my.stale-test/foo"
                             :seon.fn/namespace "my.stale-test"
                             :seon.fn/name "foo"
                             :seon.fn/private false
                             :seon.fn/updated-at now}]})
      (let [fns (d/q '[:find ?name
                         :where
                         [?e :seon.fn/namespace "my.stale-test"]
                         [?e :seon.fn/name ?name]]
                       @*test-conn*)]
        (is (= #{["foo"]} (set fns))
            "bar should be retracted since it was not in the new scan")))))

(deftest ingest-namespace-vars-test
  (testing "var entities are ingested and queryable"
    (let [now (java.util.Date.)]
      (ingest/ingest-namespace!
       {::ingest/conn *test-conn*
        ::ingest/ns-name "my.var-test"
        ::ingest/vars [{:seon.var/qualified-name "my.var-test/config"
                        :seon.var/namespace "my.var-test"
                        :seon.var/name "config"
                        :seon.var/private false
                        :seon.var/value-type :map
                        :seon.var/updated-at now}
                       {:seon.var/qualified-name "my.var-test/items"
                        :seon.var/namespace "my.var-test"
                        :seon.var/name "items"
                        :seon.var/private false
                        :seon.var/value-type :vector
                        :seon.var/doc "Sample items"
                        :seon.var/updated-at now}]})
      (let [vars (d/q '[:find ?name ?vt
                          :where
                          [?e :seon.var/namespace "my.var-test"]
                          [?e :seon.var/name ?name]
                          [?e :seon.var/value-type ?vt]]
                        @*test-conn*)]
        (is (= #{["config" :map] ["items" :vector]} (set vars))))))

  (testing "stale vars get retracted"
    (let [now (java.util.Date.)]
      ;; Re-ingest with only config
      (ingest/ingest-namespace!
       {::ingest/conn *test-conn*
        ::ingest/ns-name "my.var-test"
        ::ingest/vars [{:seon.var/qualified-name "my.var-test/config"
                        :seon.var/namespace "my.var-test"
                        :seon.var/name "config"
                        :seon.var/private false
                        :seon.var/value-type :map
                        :seon.var/updated-at now}]})
      (let [vars (d/q '[:find ?name
                          :where
                          [?e :seon.var/namespace "my.var-test"]
                          [?e :seon.var/name ?name]]
                        @*test-conn*)]
        (is (= #{["config"]} (set vars))
            "items var should be retracted")))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.graph.ingest-test)
  nil)
