(ns seon.repl.context-test
  "Tests for seon.repl.context — context cockpit for AI agents. Uses
   the canonical datahike `:memory` fixture.

   The analyzer + ingest pipeline currently breaks against datahike
   (lookup-ref to a not-yet-existent `:seon.fn` entity throws
   `:entity-id/missing`). To keep the tests focused on their target
   surface — `context/for-function` / `for-namespace` / `for-data`
   against a populated graph — these tests substitute a minimal
   hand-transacted graph stub for analyzer + ingest. The surface under
   test (build → linearize → format) is exercised; the ingest path is
   tracked separately."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.string :as str]
            [seon.db :as db]
            [seon.repl.context :as context]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture
;;; ---------------------------------------------------------------------------

(def ^:private context-malli-schema
  "Minimal graph schema — just the entity attrs `seon.graph.context/build`
   queries when seeding from a function qualified-name."
  [:map
   [:seon.fn/qualified-name :seon.fn/qualified-name]
   [:seon.fn/namespace {:optional true} :string]
   [:seon.fn/name {:optional true} :string]
   [:seon.fn/doc {:optional true} :string]
   [:seon.fn/arglists {:optional true} :string]
   [:seon.fn/row {:optional true} :int]
   [:seon.fn/private {:optional true} :boolean]
   [:seon.fn/input-spec {:optional true} :seon.db/ref]
   [:seon.fn/output-spec {:optional true} :seon.db/ref]
   [:seon.fn/input-shape {:optional true} :seon.db/ref]
   [:seon.fn/output-shape {:optional true} :seon.db/ref]
   [:seon.spec/key :seon.spec/key]
   [:seon.spec/namespace {:optional true} :string]
   [:seon.spec/definition {:optional true} :string]
   [:seon.spec/base-type {:optional true} :keyword]
   [:seon.spec/contains-keys {:optional true} [:vector :keyword]]
   [:seon.spec/optional-keys {:optional true} [:vector :keyword]]
   [:seon.spec/references {:optional true} [:vector :keyword]]
   [:seon.spec/updated-at {:optional true} :inst]
   [:seon.ns/name :seon.ns/name]
   [:seon.ns/doc {:optional true} :string]
   [:seon.ns/file {:optional true} :string]
   [:seon.call/from-fn {:optional true} :seon.db/ref]
   [:seon.call/to-fn {:optional true} :seon.db/ref]])

(use-fixtures :each
  (fn [f]
    ((tu/with-test-db-fixture
       {::tu/namespaces [:seon.runtime]
        ::tu/schemas    {:seon.runtime context-malli-schema}})
     (fn []
       ;; Seed: one fn (`seon.graph.query/call-graph`) the for-function
       ;; and for-namespace tests look for. The minimal entity is enough
       ;; for `seon.graph.context/build` to seed off; the linearizer
       ;; outputs a header that includes the qualified-name string.
       (db/transact! :seon.runtime
                     [{:seon.fn/qualified-name "seon.graph.query/call-graph"
                       :seon.fn/namespace "seon.graph.query"
                       :seon.fn/name "call-graph"
                       :seon.fn/private false}])
       (f)))))

;;; ---------------------------------------------------------------------------
;;; for-function Tests
;;; ---------------------------------------------------------------------------

(deftest for-function-test
  (testing "returns non-empty context string for known function"
    (let [result (context/for-function {::context/db-name :seon.runtime
                                        ::context/qualified-name "seon.graph.query/call-graph"})]
      (is (string? result))
      (is (pos? (count result)))
      (is (str/includes? result "seon.graph.query/call-graph"))))

  (testing "returns empty-ish context for nonexistent function"
    (let [result (context/for-function {::context/db-name :seon.runtime
                                        ::context/qualified-name "nonexistent/fn"})]
      (is (string? result))
      (is (str/blank? result)))))

;;; ---------------------------------------------------------------------------
;;; for-namespace Tests
;;; ---------------------------------------------------------------------------

(deftest for-namespace-test
  (testing "returns context with namespace header"
    (let [result (context/for-namespace {::context/db-name :seon.runtime
                                         ::context/namespace "seon.graph.query"})]
      (is (string? result))
      (is (pos? (count result)))
      (is (str/includes? result "seon.graph.query"))
      (is (str/includes? result "call-graph"))))

  (testing "returns header for nonexistent namespace"
    (let [result (context/for-namespace {::context/db-name :seon.runtime
                                         ::context/namespace "nonexistent.ns"})]
      (is (string? result))
      (is (str/includes? result "nonexistent.ns")))))

;;; ---------------------------------------------------------------------------
;;; for-data Tests
;;; ---------------------------------------------------------------------------

(deftest for-data-test
  (testing "returns message when no renderers match"
    ;; The renderer auto-discovery machinery is dormant per remaining.md
    ;; Forward decisions §"Renderer auto-resolution: deferred", so
    ;; find-renderer returns nil for both :ai and :html.
    (let [result (context/for-data {::context/db-name :seon.runtime
                                    ::context/data {:some/random-key "value"}})]
      (is (string? result))
      (is (str/includes? result "No matching renderers"))))

  (testing "handles empty data map"
    (let [result (context/for-data {::context/db-name :seon.runtime
                                    ::context/data {}})]
      (is (string? result)))))
