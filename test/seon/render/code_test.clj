(ns seon.render.code-test
  "Tests for seon.render.code. Ported in M-2b from the legacy datalevin
   `d/create-conn` + `*direct-mode*` + `*conn-manager*` shape to the
   canonical datahike `:memory` fixture. The fixture preloads the same
   test-data graph the pre-port test built (one ns with three fns + two
   spec pairs) via a single `db/transact!` — datahike resolves same-tx
   tempids order-independently."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.db :as db]
            [seon.graph.query :as gq]
            [seon.render.code :as rc]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture
;;; ---------------------------------------------------------------------------

(def ^:private code-malli-schema
  "Schema covering the fn / spec / ns / call / ns.dep entities the
   fixture preloads."
  [:map
   [:seon.fn/qualified-name :seon.fn/qualified-name]
   [:seon.fn/namespace {:optional true} :string]
   [:seon.fn/name {:optional true} :string]
   [:seon.fn/arglists {:optional true} :string]
   [:seon.fn/private {:optional true} :boolean]
   [:seon.fn/doc {:optional true} :string]
   [:seon.fn/row {:optional true} :int]
   [:seon.fn/updated-at {:optional true} :inst]
   [:seon.fn/input-spec {:optional true} :seon.db/ref]
   [:seon.fn/output-spec {:optional true} :seon.db/ref]
   [:seon.spec/key :seon.spec/key]
   [:seon.spec/contains-keys {:optional true} [:vector :keyword]]
   [:seon.spec/optional-keys {:optional true} [:vector :keyword]]
   [:seon.spec/definition {:optional true} :string]
   [:seon.spec/base-type {:optional true} :keyword]
   [:seon.spec/namespace {:optional true} :string]
   [:seon.spec/updated-at {:optional true} :inst]
   [:seon.ns/name :seon.ns/name]
   [:seon.ns/doc {:optional true} :string]
   [:seon.ns.dep/from-ns {:optional true} :string]
   [:seon.ns.dep/to-ns {:optional true} :string]
   [:seon.call/from-fn {:optional true} :seon.db/ref]
   [:seon.call/to-fn {:optional true} :seon.db/ref]
   [:seon.call/row {:optional true} :int]])

(def ^:private test-data
  "Preloaded graph: ns alpha/beta + three fns (fn-a public-with-html,
   fn-b public-with-docs, fn-c private), plus matching spec entities.
   Tempids are integer-keyed so datahike's same-tx resolution wires
   the input-spec / output-spec lookups."
  [;; namespaces
   {:seon.ns/name "seon.test.alpha"
    :seon.ns/doc "Test namespace alpha with two public functions."}
   {:seon.ns/name "seon.test.beta"}
   ;; fn-a specs
   {:db/id -1
    :seon.spec/key :seon.test.alpha/fn-a-request
    :seon.spec/contains-keys [:seon.test.alpha/x :seon.test.alpha/y]
    :seon.spec/optional-keys [:seon.test.alpha/y]
    :seon.spec/definition "[:map [::x ::x] [::y {:optional true} ::y]]"
    :seon.spec/base-type :map}
   {:db/id -2
    :seon.spec/key :seon.test.alpha/fn-a-response
    :seon.spec/contains-keys [:seon.render/html :seon.render/ai]
    :seon.spec/definition "[:map [:seon.render/html :any] [:seon.render/ai :string]]"
    :seon.spec/base-type :map}
   {:seon.fn/qualified-name "seon.test.alpha/fn-a"
    :seon.fn/namespace "seon.test.alpha"
    :seon.fn/name "fn-a"
    :seon.fn/arglists "[\"[{::keys [x y]}]\"]"
    :seon.fn/private false
    :seon.fn/doc "Alpha function A — renders things."
    :seon.fn/row 10
    :seon.fn/input-spec -1
    :seon.fn/output-spec -2}
   ;; fn-b specs
   {:db/id -3
    :seon.spec/key :seon.test.alpha/fn-b-request
    :seon.spec/contains-keys [:seon.test.alpha/x :seon.test.alpha/z]
    :seon.spec/definition "[:map [::x ::x] [::z ::z]]"
    :seon.spec/base-type :map}
   {:db/id -4
    :seon.spec/key :seon.test.alpha/fn-b-response
    :seon.spec/contains-keys [:seon.render/documentation]
    :seon.spec/definition "[:map [:seon.render/documentation :string]]"
    :seon.spec/base-type :map}
   {:seon.fn/qualified-name "seon.test.alpha/fn-b"
    :seon.fn/namespace "seon.test.alpha"
    :seon.fn/name "fn-b"
    :seon.fn/arglists "[\"[{::keys [x z]}]\"]"
    :seon.fn/private false
    :seon.fn/doc "Alpha function B — docs renderer."
    :seon.fn/row 30
    :seon.fn/input-spec -3
    :seon.fn/output-spec -4}
   ;; fn-c: private, no specs
   {:seon.fn/qualified-name "seon.test.alpha/fn-c"
    :seon.fn/namespace "seon.test.alpha"
    :seon.fn/name "fn-c"
    :seon.fn/private true
    :seon.fn/doc "Private helper."
    :seon.fn/row 50}
   ;; fn-d in beta namespace, no specs
   {:seon.fn/qualified-name "seon.test.beta/fn-d"
    :seon.fn/namespace "seon.test.beta"
    :seon.fn/name "fn-d"
    :seon.fn/private false
    :seon.fn/row 5}])

(use-fixtures :each
  (fn [f]
    ((tu/with-test-db-fixture
       {::tu/namespaces [:seon.runtime]
        ::tu/schemas    {:seon.runtime code-malli-schema}})
     (fn []
       (db/transact! :seon.runtime test-data)
       (gq/invalidate-output-key-cache!)
       (try
         (f)
         (finally
           (gq/invalidate-output-key-cache!)))))))

;;; ---------------------------------------------------------------------------
;;; compatible-functions tests
;;; ---------------------------------------------------------------------------

(deftest compatible-functions-basic-test
  (testing "finds functions whose required keys are subset of available keys"
    (let [results (rc/compatible-functions
                   {::rc/db-name :seon.runtime
                    ::rc/available-keys #{:seon.test.alpha/x :seon.test.alpha/y}})]
      (is (= 1 (count results)))
      (is (= "seon.test.alpha/fn-a" (:seon.fn/qualified-name (first results)))))))

(deftest compatible-functions-output-filter-test
  (testing "filters by output key when specified"
    (let [results (rc/compatible-functions
                   {::rc/db-name :seon.runtime
                    ::rc/available-keys #{:seon.test.alpha/x :seon.test.alpha/z}
                    ::rc/output-filter :seon.render/documentation})]
      (is (= 1 (count results)))
      (is (= "seon.test.alpha/fn-b" (:seon.fn/qualified-name (first results)))))))

(deftest compatible-functions-empty-test
  (testing "returns empty when no functions match"
    (let [results (rc/compatible-functions
                   {::rc/db-name :seon.runtime
                    ::rc/available-keys #{:nonexistent/key}})]
      (is (empty? results)))))

(deftest compatible-functions-superset-test
  (testing "functions match when available keys are a superset of required"
    (let [results (rc/compatible-functions
                   {::rc/db-name :seon.runtime
                    ::rc/available-keys #{:seon.test.alpha/x :seon.test.alpha/y
                                          :seon.test.alpha/z :extra/key}})]
      (is (= 2 (count results)))
      (is (= "seon.test.alpha/fn-b"
             (:seon.fn/qualified-name (first results)))))))

;;; ---------------------------------------------------------------------------
;;; render-ns-docs tests
;;; ---------------------------------------------------------------------------

(deftest render-ns-docs-summary-test
  (testing "summary level renders function names only"
    (let [result (rc/render-ns-docs {::rc/db-name :seon.runtime
                                     ::rc/ns-name "seon.test.alpha"
                                     ::rc/detail :summary})
          doc (:seon.render/documentation result)]
      (is (string? doc))
      (is (re-find #"## seon\.test\.alpha" doc))
      (is (re-find #"- fn-a" doc))
      (is (re-find #"- fn-b" doc))
      (is (not (re-find #"fn-c" doc))))))

(deftest render-ns-docs-interface-test
  (testing "interface level includes arglists and key types"
    (let [result (rc/render-ns-docs {::rc/db-name :seon.runtime
                                     ::rc/ns-name "seon.test.alpha"})
          doc (:seon.render/documentation result)]
      (is (string? doc))
      (is (re-find #"### fn-a" doc))
      (is (re-find #"Input:" doc))
      (is (not (re-find #"fn-c" doc))))))

(deftest render-ns-docs-deep-dive-test
  (testing "deep dive includes private functions and full docs"
    (let [result (rc/render-ns-docs {::rc/db-name :seon.runtime
                                     ::rc/ns-name "seon.test.alpha"
                                     ::rc/detail :deep-dive})
          doc (:seon.render/documentation result)]
      (is (string? doc))
      (is (re-find #"fn-c" doc))
      (is (re-find #"Alpha function A" doc)))))

(deftest render-ns-docs-no-functions-test
  (testing "handles namespaces with no functions"
    (let [result (rc/render-ns-docs {::rc/db-name :seon.runtime
                                     ::rc/ns-name "seon.nonexistent"})
          doc (:seon.render/documentation result)]
      (is (string? doc))
      (is (re-find #"No functions found" doc)))))

(deftest render-ns-docs-includes-ns-doc-test
  (testing "includes namespace docstring when present"
    (let [result (rc/render-ns-docs {::rc/db-name :seon.runtime
                                     ::rc/ns-name "seon.test.alpha"})
          doc (:seon.render/documentation result)]
      (is (re-find #"Test namespace alpha" doc)))))

;;; ---------------------------------------------------------------------------
;;; resolve-docs tests
;;; ---------------------------------------------------------------------------

(deftest resolve-docs-fallback-test
  (testing "returns nil when no custom documentation renderer exists"
    (let [result (rc/resolve-docs {::rc/db-name :seon.runtime
                                   ::rc/ns-name "seon.test.beta"})]
      (is (nil? result)))))

(deftest resolve-docs-finds-same-ns-test
  (testing "finds documentation renderer in same namespace"
    (let [result (rc/resolve-docs {::rc/db-name :seon.runtime
                                   ::rc/ns-name "seon.test.alpha"
                                   ::rc/available-keys #{:seon.test.alpha/x
                                                         :seon.test.alpha/z}})]
      ;; fn-b requires x and z, both available — but resolving its var fails
      ;; (the function isn't a real Clojure var), so the fn returns nil.
      (is (nil? result)))))

(defn test-doc-renderer
  "A real function that resolve-docs can find via requiring-resolve."
  [_request]
  {:seon.render/documentation "test docs"})

(deftest resolve-docs-positive-test
  (testing "returns var when a resolvable documentation renderer exists"
    (let [qname "seon.render.code-test/test-doc-renderer"]
      (db/transact! :seon.runtime
                    [{:seon.spec/key :seon.render.code-test/doc-in
                      :seon.spec/contains-keys [:seon.render.code-test/x]
                      :seon.spec/base-type :map}
                     {:seon.spec/key :seon.render.code-test/doc-out
                      :seon.spec/contains-keys [:seon.render/documentation]
                      :seon.spec/base-type :map}])
      (db/transact! :seon.runtime
                    [{:seon.fn/qualified-name qname
                      :seon.fn/namespace "seon.render.code-test"
                      :seon.fn/name "test-doc-renderer"
                      :seon.fn/private false
                      :seon.fn/row 1
                      :seon.fn/input-spec [:seon.spec/key :seon.render.code-test/doc-in]
                      :seon.fn/output-spec [:seon.spec/key :seon.render.code-test/doc-out]}])
      (gq/invalidate-output-key-cache!)
      (let [result (rc/resolve-docs {::rc/db-name :seon.runtime
                                     ::rc/ns-name "seon.render.code-test"
                                     ::rc/available-keys #{:seon.render.code-test/x}})]
        (is (some? result) "should resolve to a var")
        (is (= #'seon.render.code-test/test-doc-renderer result))))))

;;; ---------------------------------------------------------------------------
;;; context-for-agent tests
;;; ---------------------------------------------------------------------------

(deftest context-for-agent-test
  (testing "combines documentation and call graph context"
    (let [result (rc/context-for-agent {::rc/db-name :seon.runtime
                                        ::rc/ns-name "seon.test.alpha"})
          doc (:seon.render/documentation result)]
      (is (string? doc))
      (is (re-find #"seon\.test\.alpha" doc))
      (is (nat-int? (::rc/entity-count result))))))

;;; ---------------------------------------------------------------------------
;;; Cache integration tests
;;; ---------------------------------------------------------------------------

(deftest output-key-cache-test
  (testing "invalidate-output-key-cache! clears the cache"
    (gq/functions-with-output-key {::gq/db-name :seon.runtime
                                   ::gq/output-key :seon.render/html})
    (gq/invalidate-output-key-cache!)
    (let [results (gq/functions-with-output-key {::gq/db-name :seon.runtime
                                                 ::gq/output-key :seon.render/html})]
      (is (= 1 (count results)))
      (is (= "seon.test.alpha/fn-a" (:seon.fn/qualified-name (first results)))))))
