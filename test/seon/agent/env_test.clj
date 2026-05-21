(ns seon.agent.env-test
  "Tests for the agent environment toolkit. Uses the canonical datahike
   `:memory` fixture.

   The fixture installs a merged schema covering both the graph entity
   types (`:seon.ns/*`, `:seon.fn/*`, `:seon.spec/*`, `:seon.call/*`) and
   the agent ctx persistence type (`:seon.ctx/*`)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.agent.env :as env]
            [seon.db :as db]
            [seon.graph.ingest :as ingest]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Fixture
;;; ---------------------------------------------------------------------------

(def ^:private combined-malli-schema
  "Merged Malli :map schema covering ns, fn, call, spec entities + the
   ctx persistence entity (`:seon.ctx/instance-id`, `:seon.ctx/data`,
   `:seon.ctx/updated-at`). Forwarded to `seon.db.datahike.flow`'s
   conn-process at :init via the fixture."
  (into [:map]
        (mapcat rest
                [ingest/ns-entity-schema
                 ingest/fn-entity-schema
                 ingest/spec-entity-schema
                 ingest/call-entity-schema
                 ;; ctx persistence entity (from seon.ctx/ctx-entity-schema)
                 [:map
                  [:seon.ctx/instance-id :seon.ctx/instance-id]
                  [:seon.ctx/namespace {:optional true} :symbol]
                  [:seon.ctx/data :string]
                  [:seon.ctx/updated-at :inst]]])))

(use-fixtures :each
  (tu/with-test-db-fixture
    {::tu/namespaces [:seon.runtime]
     ::tu/schemas    {:seon.runtime combined-malli-schema}}))

;;; ---------------------------------------------------------------------------
;;; Helper: populate graph with synthetic data
;;; ---------------------------------------------------------------------------

(defn- populate-graph!
  "Insert synthetic function, namespace, and spec entities for testing."
  []
  ;; Namespaces
  (db/transact! :seon.runtime [{:seon.ns/name "seon.health"}
                               {:seon.ns/name "seon.trading"}])
  ;; Functions
  (db/transact! :seon.runtime [{:seon.fn/qualified-name "seon.health/log-workout!"
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
  ;; Call edges (using lookup refs into :seon.fn/qualified-name)
  (db/transact! :seon.runtime [{:seon.call/from-fn [:seon.fn/qualified-name "seon.health/log-workout!"]
                                :seon.call/to-fn [:seon.fn/qualified-name "seon.health/calories"]
                                :seon.call/row 10}])
  ;; Specs with overlapping keys
  (db/transact! :seon.runtime [{:seon.spec/key :seon.health/workout
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
  (populate-graph!)

  (testing "finds functions matching pattern"
    (let [results (env/search {::env/db-name :seon.runtime ::env/pattern "calor"})]
      (is (vector? results))
      (is (= 1 (count results)))
      (is (= "calories" (:seon.fn/name (first results))))))

  (testing "returns empty for no match"
    (is (= [] (env/search {::env/db-name :seon.runtime ::env/pattern "zzzzz"})))))

;;; ---------------------------------------------------------------------------
;;; Functions-in Tests
;;; ---------------------------------------------------------------------------

(deftest functions-in-test
  (populate-graph!)

  (testing "lists functions in a namespace"
    (let [fns (env/functions-in {::env/db-name :seon.runtime ::env/namespace "seon.health"})]
      (is (vector? fns))
      (is (= 2 (count fns)))
      (is (= #{"log-workout!" "calories"}
             (set (map :seon.fn/name fns))))))

  (testing "returns empty for unknown namespace"
    (is (= [] (env/functions-in {::env/db-name :seon.runtime ::env/namespace "nonexistent"})))))

;;; ---------------------------------------------------------------------------
;;; Call Graph Tests
;;; ---------------------------------------------------------------------------

(deftest who-calls-test
  (populate-graph!)

  (testing "finds callers of a function"
    (let [callers (env/who-calls {::env/db-name :seon.runtime
                                  ::env/namespace "seon.health"
                                  ::env/fn-name "calories"})]
      (is (vector? callers))
      (is (some #(= "seon.health/log-workout!" (:seon.call/from-fn %)) callers)))))

(deftest what-calls-test
  (populate-graph!)

  (testing "finds what a function calls"
    (let [calls (env/what-calls {::env/db-name :seon.runtime
                                 ::env/namespace "seon.health"
                                 ::env/fn-name "log-workout!"})]
      (is (vector? calls))
      (is (some #(= "seon.health/calories" (:seon.call/to-fn %)) calls)))))

;;; ---------------------------------------------------------------------------
;;; Schema Discovery Tests
;;; ---------------------------------------------------------------------------

(deftest related-schemas-test
  (populate-graph!)

  (testing "finds specs sharing keys with given spec"
    (let [related (env/related-schemas {::env/db-name :seon.runtime
                                        ::env/spec-key :seon.health/workout})]
      (is (vector? related))
      ;; :seon.health/log-request shares :seon.health/weight
      (is (some #(= :seon.health/log-request (:seon.spec/key %)) related))
      ;; :seon.trading/signal should NOT appear (no shared keys)
      (is (not (some #(= :seon.trading/signal (:seon.spec/key %)) related)))))

  (testing "returns empty for unknown spec"
    (is (= [] (env/related-schemas {::env/db-name :seon.runtime
                                    ::env/spec-key :nonexistent/spec})))))

;;; ---------------------------------------------------------------------------
;;; who-produces / who-consumes Tests
;;; ---------------------------------------------------------------------------

(deftest who-produces-test
  (populate-graph!)

  (testing "returns empty when no fn-spec links exist"
    ;; We haven't linked any functions to output specs
    (is (= [] (env/who-produces {::env/db-name :seon.runtime
                                 ::env/keys [:seon.health/weight]})))))

(deftest who-consumes-test
  (populate-graph!)

  (testing "returns empty when no fn-spec links exist"
    (is (= [] (env/who-consumes {::env/db-name :seon.runtime
                                 ::env/keys [:seon.health/weight]})))))

(deftest who-produces-with-links-test
  (populate-graph!)

  (testing "finds functions with output spec containing given keys"
    ;; Link calories fn to workout spec as output
    (let [fn-eid (ffirst (db/query :seon.runtime
                                   '[:find ?e :in $ ?qn :where [?e :seon.fn/qualified-name ?qn]]
                                   "seon.health/calories"))
          spec-eid (ffirst (db/query :seon.runtime
                                     '[:find ?e :in $ ?k :where [?e :seon.spec/key ?k]]
                                     :seon.health/workout))]
      (db/transact! :seon.runtime [{:db/id fn-eid :seon.fn/output-spec spec-eid}])

      (let [producers (env/who-produces {::env/db-name :seon.runtime
                                         ::env/keys [:seon.health/weight]})]
        (is (= 1 (count producers)))
        (is (= "seon.health/calories" (:seon.fn/qualified-name (first producers))))))))

(deftest who-consumes-with-links-test
  (populate-graph!)

  (testing "finds functions with input spec containing given keys"
    ;; Link log-workout! fn to log-request spec as input
    (let [fn-eid (ffirst (db/query :seon.runtime
                                   '[:find ?e :in $ ?qn :where [?e :seon.fn/qualified-name ?qn]]
                                   "seon.health/log-workout!"))
          spec-eid (ffirst (db/query :seon.runtime
                                     '[:find ?e :in $ ?k :where [?e :seon.spec/key ?k]]
                                     :seon.health/log-request))]
      (db/transact! :seon.runtime [{:db/id fn-eid :seon.fn/input-spec spec-eid}])

      (let [consumers (env/who-consumes {::env/db-name :seon.runtime
                                         ::env/keys [:seon.health/weight]})]
        (is (= 1 (count consumers)))
        (is (= "seon.health/log-workout!" (:seon.fn/qualified-name (first consumers))))))))

;;; ---------------------------------------------------------------------------
;;; Context Persistence Tests
;;; ---------------------------------------------------------------------------

(deftest ctx-round-trip-test
  (testing "ctx-save! + ctx-load round-trip"
    (let [data {:results [1 2 3] :status :ok}]
      (env/ctx-save! {::env/db-name :seon.runtime
                      ::env/instance-id "a13b"
                      ::env/data data})
      (let [loaded (env/ctx-load {::env/db-name :seon.runtime
                                  ::env/instance-id "a13b"})]
        (is (= data loaded))))))

(deftest ctx-load-nil-test
  (testing "ctx-load returns nil for unknown instance"
    (is (nil? (env/ctx-load {::env/db-name :seon.runtime
                             ::env/instance-id "zzzz"})))))

(deftest ctx-overwrite-test
  (testing "ctx-save! overwrites previous data for same instance"
    (env/ctx-save! {::env/db-name :seon.runtime
                    ::env/instance-id "b24c"
                    ::env/data {:v 1}})
    (env/ctx-save! {::env/db-name :seon.runtime
                    ::env/instance-id "b24c"
                    ::env/data {:v 2}})
    (is (= {:v 2} (env/ctx-load {::env/db-name :seon.runtime
                                  ::env/instance-id "b24c"})))))

(deftest ctx-isolation-test
  (testing "different instance-ids have independent contexts"
    (env/ctx-save! {::env/db-name :seon.runtime
                    ::env/instance-id "inst1"
                    ::env/data {:a 1}})
    (env/ctx-save! {::env/db-name :seon.runtime
                    ::env/instance-id "inst2"
                    ::env/data {:b 2}})
    (is (= {:a 1} (env/ctx-load {::env/db-name :seon.runtime
                                  ::env/instance-id "inst1"})))
    (is (= {:b 2} (env/ctx-load {::env/db-name :seon.runtime
                                  ::env/instance-id "inst2"})))))
