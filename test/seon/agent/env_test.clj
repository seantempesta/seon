(ns seon.agent.env-test
  "Tests for the agent environment toolkit.

   Uses a temp Datalevin connection with graph + ctx schemas."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.agent.env :as env]
            [seon.graph.ingest :as ingest])
  (:import [java.io File]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *test-conn* nil)

(defn- temp-dir []
  (let [dir (File/createTempFile "seon-agent-env-test" "")]
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

(def ^:private combined-schema
  "Graph schema + ctx persistence schema merged."
  (merge ingest/datalevin-schema env/ctx-schema))

(defn with-test-conn [f]
  (let [dir (temp-dir)
        conn (d/get-conn dir combined-schema)]
    (try
      (binding [*test-conn* conn]
        (f))
      (finally
        (d/close conn)
        (delete-dir dir)))))

(use-fixtures :each with-test-conn)

;;; ---------------------------------------------------------------------------
;;; Helper: populate graph with synthetic data
;;; ---------------------------------------------------------------------------

(defn- populate-graph!
  "Insert synthetic function, namespace, and spec entities for testing."
  [conn]
  ;; Namespaces
  (d/transact! conn [{:seon.ns/name "seon.health"}
                      {:seon.ns/name "seon.trading"}])
  ;; Functions
  (d/transact! conn [{:seon.fn/qualified-name "seon.health/log-workout!"
                       :seon.fn/namespace "seon.health"
                       :seon.fn/name "log-workout!"
                       :seon.fn/arglists "([request])"
                       :seon.fn/private false}
                      {:seon.fn/qualified-name "seon.health/calories"
                       :seon.fn/namespace "seon.health"
                       :seon.fn/name "calories"
                       :seon.fn/arglists "([request])"
                       :seon.fn/private false}
                      {:seon.fn/qualified-name "seon.trading/analyze"
                       :seon.fn/namespace "seon.trading"
                       :seon.fn/name "analyze"
                       :seon.fn/arglists "([request])"
                       :seon.fn/private false}])
  ;; Call edges (using lookup refs)
  (d/transact! conn [{:seon.call/from-fn [:seon.fn/qualified-name "seon.health/log-workout!"]
                       :seon.call/to-fn [:seon.fn/qualified-name "seon.health/calories"]
                       :seon.call/row 10}])
  ;; Specs with overlapping keys
  (d/transact! conn [{:seon.spec/key :seon.health/workout
                       :seon.spec/namespace "seon.health"
                       :seon.spec/definition "[:map [:weight :int]]"
                       :seon.spec/base-type :map
                       :seon.spec/contains-keys [:seon.health/weight :seon.health/duration]
                       :seon.spec/updated-at (java.util.Date.)}
                      {:seon.spec/key :seon.health/log-request
                       :seon.spec/namespace "seon.health"
                       :seon.spec/definition "[:map [:weight :int] [:notes :string]]"
                       :seon.spec/base-type :map
                       :seon.spec/contains-keys [:seon.health/weight :seon.health/notes]
                       :seon.spec/updated-at (java.util.Date.)}
                      {:seon.spec/key :seon.trading/signal
                       :seon.spec/namespace "seon.trading"
                       :seon.spec/definition "[:map [:ticker :string]]"
                       :seon.spec/base-type :map
                       :seon.spec/contains-keys [:seon.trading/ticker]
                       :seon.spec/updated-at (java.util.Date.)}]))

;;; ---------------------------------------------------------------------------
;;; Search Tests
;;; ---------------------------------------------------------------------------

(deftest search-test
  (populate-graph! *test-conn*)

  (testing "finds functions matching pattern"
    (let [results (env/search {::env/conn *test-conn* ::env/pattern "calor"})]
      (is (vector? results))
      (is (= 1 (count results)))
      (is (= "calories" (:seon.fn/name (first results))))))

  (testing "returns empty for no match"
    (is (= [] (env/search {::env/conn *test-conn* ::env/pattern "zzzzz"})))))

;;; ---------------------------------------------------------------------------
;;; Functions-in Tests
;;; ---------------------------------------------------------------------------

(deftest functions-in-test
  (populate-graph! *test-conn*)

  (testing "lists functions in a namespace"
    (let [fns (env/functions-in {::env/conn *test-conn* ::env/namespace "seon.health"})]
      (is (vector? fns))
      (is (= 2 (count fns)))
      (is (= #{"log-workout!" "calories"}
             (set (map :seon.fn/name fns))))))

  (testing "returns empty for unknown namespace"
    (is (= [] (env/functions-in {::env/conn *test-conn* ::env/namespace "nonexistent"})))))

;;; ---------------------------------------------------------------------------
;;; Call Graph Tests
;;; ---------------------------------------------------------------------------

(deftest who-calls-test
  (populate-graph! *test-conn*)

  (testing "finds callers of a function"
    (let [callers (env/who-calls {::env/conn *test-conn*
                                  ::env/namespace "seon.health"
                                  ::env/fn-name "calories"})]
      (is (vector? callers))
      (is (some #(= "seon.health/log-workout!" (:seon.call/from-fn %)) callers)))))

(deftest what-calls-test
  (populate-graph! *test-conn*)

  (testing "finds what a function calls"
    (let [calls (env/what-calls {::env/conn *test-conn*
                                 ::env/namespace "seon.health"
                                 ::env/fn-name "log-workout!"})]
      (is (vector? calls))
      (is (some #(= "seon.health/calories" (:seon.call/to-fn %)) calls)))))

;;; ---------------------------------------------------------------------------
;;; Schema Discovery Tests
;;; ---------------------------------------------------------------------------

(deftest related-schemas-test
  (populate-graph! *test-conn*)

  (testing "finds specs sharing keys with given spec"
    (let [related (env/related-schemas {::env/conn *test-conn*
                                        ::env/spec-key :seon.health/workout})]
      (is (vector? related))
      ;; :seon.health/log-request shares :seon.health/weight
      (is (some #(= :seon.health/log-request (:seon.spec/key %)) related))
      ;; :seon.trading/signal should NOT appear (no shared keys)
      (is (not (some #(= :seon.trading/signal (:seon.spec/key %)) related)))))

  (testing "returns empty for unknown spec"
    (is (= [] (env/related-schemas {::env/conn *test-conn*
                                    ::env/spec-key :nonexistent/spec})))))

;;; ---------------------------------------------------------------------------
;;; who-produces / who-consumes Tests
;;; ---------------------------------------------------------------------------

(deftest who-produces-test
  (populate-graph! *test-conn*)

  (testing "returns empty when no fn-spec links exist"
    ;; We haven't linked any functions to output specs
    (is (= [] (env/who-produces {::env/conn *test-conn*
                                 ::env/keys [:seon.health/weight]})))))

(deftest who-consumes-test
  (populate-graph! *test-conn*)

  (testing "returns empty when no fn-spec links exist"
    (is (= [] (env/who-consumes {::env/conn *test-conn*
                                 ::env/keys [:seon.health/weight]})))))

(deftest who-produces-with-links-test
  (populate-graph! *test-conn*)

  (testing "finds functions with output spec containing given keys"
    ;; Link calories fn to workout spec as output
    (let [fn-eid (ffirst (d/q '[:find ?e :in $ ?qn :where [?e :seon.fn/qualified-name ?qn]]
                               @*test-conn* "seon.health/calories"))
          spec-eid (ffirst (d/q '[:find ?e :in $ ?k :where [?e :seon.spec/key ?k]]
                                 @*test-conn* :seon.health/workout))]
      (d/transact! *test-conn* [{:db/id fn-eid :seon.fn/output-spec spec-eid}])

      (let [producers (env/who-produces {::env/conn *test-conn*
                                         ::env/keys [:seon.health/weight]})]
        (is (= 1 (count producers)))
        (is (= "seon.health/calories" (:seon.fn/qualified-name (first producers))))))))

(deftest who-consumes-with-links-test
  (populate-graph! *test-conn*)

  (testing "finds functions with input spec containing given keys"
    ;; Link log-workout! fn to log-request spec as input
    (let [fn-eid (ffirst (d/q '[:find ?e :in $ ?qn :where [?e :seon.fn/qualified-name ?qn]]
                               @*test-conn* "seon.health/log-workout!"))
          spec-eid (ffirst (d/q '[:find ?e :in $ ?k :where [?e :seon.spec/key ?k]]
                                 @*test-conn* :seon.health/log-request))]
      (d/transact! *test-conn* [{:db/id fn-eid :seon.fn/input-spec spec-eid}])

      (let [consumers (env/who-consumes {::env/conn *test-conn*
                                         ::env/keys [:seon.health/weight]})]
        (is (= 1 (count consumers)))
        (is (= "seon.health/log-workout!" (:seon.fn/qualified-name (first consumers))))))))

;;; ---------------------------------------------------------------------------
;;; Context Persistence Tests
;;; ---------------------------------------------------------------------------

(deftest ctx-round-trip-test
  (testing "ctx-save! + ctx-load round-trip"
    (let [data {:results [1 2 3] :status :ok}]
      (env/ctx-save! {::env/conn *test-conn*
                      ::env/instance-id "a13b"
                      ::env/data data})
      (let [loaded (env/ctx-load {::env/conn *test-conn*
                                  ::env/instance-id "a13b"})]
        (is (= data loaded))))))

(deftest ctx-load-nil-test
  (testing "ctx-load returns nil for unknown instance"
    (is (nil? (env/ctx-load {::env/conn *test-conn*
                             ::env/instance-id "zzzz"})))))

(deftest ctx-overwrite-test
  (testing "ctx-save! overwrites previous data for same instance"
    (env/ctx-save! {::env/conn *test-conn*
                    ::env/instance-id "b24c"
                    ::env/data {:v 1}})
    (env/ctx-save! {::env/conn *test-conn*
                    ::env/instance-id "b24c"
                    ::env/data {:v 2}})
    (is (= {:v 2} (env/ctx-load {::env/conn *test-conn*
                                  ::env/instance-id "b24c"})))))

(deftest ctx-isolation-test
  (testing "different instance-ids have independent contexts"
    (env/ctx-save! {::env/conn *test-conn*
                    ::env/instance-id "inst1"
                    ::env/data {:a 1}})
    (env/ctx-save! {::env/conn *test-conn*
                    ::env/instance-id "inst2"
                    ::env/data {:b 2}})
    (is (= {:a 1} (env/ctx-load {::env/conn *test-conn*
                                  ::env/instance-id "inst1"})))
    (is (= {:b 2} (env/ctx-load {::env/conn *test-conn*
                                  ::env/instance-id "inst2"})))))

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.agent.env-test)
  nil)
