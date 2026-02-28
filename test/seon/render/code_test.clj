(ns seon.render.code-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.render.code :as rc]
            [seon.graph.query :as gq]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures — In-memory Datalevin with graph data
;;; ---------------------------------------------------------------------------

(def ^:private test-schema
  {:seon.fn/qualified-name {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :seon.fn/namespace      {:db/valueType :db.type/string}
   :seon.fn/name           {:db/valueType :db.type/string}
   :seon.fn/arglists       {:db/valueType :db.type/string}
   :seon.fn/private        {:db/valueType :db.type/boolean}
   :seon.fn/doc            {:db/valueType :db.type/string}
   :seon.fn/row            {:db/valueType :db.type/long}
   :seon.fn/updated-at     {:db/valueType :db.type/instant}
   :seon.fn/input-spec     {:db/valueType :db.type/ref}
   :seon.fn/output-spec    {:db/valueType :db.type/ref}
   :seon.spec/key          {:db/valueType :db.type/keyword :db/unique :db.unique/identity}
   :seon.spec/contains-keys {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   :seon.spec/optional-keys {:db/valueType :db.type/keyword :db/cardinality :db.cardinality/many}
   :seon.spec/definition   {:db/valueType :db.type/string}
   :seon.spec/base-type    {:db/valueType :db.type/keyword}
   :seon.ns/name           {:db/valueType :db.type/string :db/unique :db.unique/identity}
   :seon.ns/doc            {:db/valueType :db.type/string}
   :seon.ns.dep/from-ns    {:db/valueType :db.type/string}
   :seon.ns.dep/to-ns      {:db/valueType :db.type/string}
   :seon.call/from-fn      {:db/valueType :db.type/ref}
   :seon.call/to-fn        {:db/valueType :db.type/ref}
   :seon.call/row          {:db/valueType :db.type/long}})

(def ^:private test-data
  [;; Namespace
   {:seon.ns/name "seon.test.alpha"
    :seon.ns/doc "Test namespace alpha with two public functions."}
   {:seon.ns/name "seon.test.beta"}

   ;; Input spec for fn-a
   {:db/id -1
    :seon.spec/key :seon.test.alpha/fn-a-request
    :seon.spec/contains-keys [:seon.test.alpha/x :seon.test.alpha/y]
    :seon.spec/optional-keys [:seon.test.alpha/y]
    :seon.spec/definition "[:map [::x ::x] [::y {:optional true} ::y]]"
    :seon.spec/base-type :map}
   ;; Output spec for fn-a
   {:db/id -2
    :seon.spec/key :seon.test.alpha/fn-a-response
    :seon.spec/contains-keys [:seon.render/html :seon.render/ai]
    :seon.spec/definition "[:map [:seon.render/html :any] [:seon.render/ai :string]]"
    :seon.spec/base-type :map}
   ;; fn-a: public, has specs, produces html+ai
   {:seon.fn/qualified-name "seon.test.alpha/fn-a"
    :seon.fn/namespace "seon.test.alpha"
    :seon.fn/name "fn-a"
    :seon.fn/arglists "[\"[{::keys [x y]}]\"]"
    :seon.fn/private false
    :seon.fn/doc "Alpha function A — renders things."
    :seon.fn/row 10
    :seon.fn/input-spec -1
    :seon.fn/output-spec -2}

   ;; Input spec for fn-b
   {:db/id -3
    :seon.spec/key :seon.test.alpha/fn-b-request
    :seon.spec/contains-keys [:seon.test.alpha/x :seon.test.alpha/z]
    :seon.spec/definition "[:map [::x ::x] [::z ::z]]"
    :seon.spec/base-type :map}
   ;; Output spec for fn-b (produces documentation)
   {:db/id -4
    :seon.spec/key :seon.test.alpha/fn-b-response
    :seon.spec/contains-keys [:seon.render/documentation]
    :seon.spec/definition "[:map [:seon.render/documentation :string]]"
    :seon.spec/base-type :map}
   ;; fn-b: public, has specs, produces documentation
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

(def ^:dynamic *test-conn* nil)

(defn- delete-dir [^String path]
  (let [f (java.io.File. path)]
    (when (.isDirectory f)
      (doseq [child (.listFiles f)]
        (delete-dir (.getAbsolutePath child))
        (.delete child)))
    (.delete f)))

(defn- create-test-conn []
  (let [dir (str "tmp/test-render-code-" (System/currentTimeMillis))
        conn (d/get-conn dir test-schema)]
    (d/transact! conn test-data)
    [conn dir]))

(use-fixtures :each
  (fn [f]
    (let [[conn dir] (create-test-conn)]
      (try
        (binding [*test-conn* conn]
          (gq/invalidate-output-key-cache!)
          (f))
        (finally
          (d/close conn)
          (delete-dir dir))))))

;;; ---------------------------------------------------------------------------
;;; compatible-functions tests
;;; ---------------------------------------------------------------------------

(deftest compatible-functions-basic-test
  (testing "finds functions whose required keys are subset of available keys"
    (let [results (rc/compatible-functions
                   {::rc/conn *test-conn*
                    ::rc/available-keys #{:seon.test.alpha/x :seon.test.alpha/y}})]
      ;; fn-a requires #{:seon.test.alpha/x}, optional #{:seon.test.alpha/y}
      ;; fn-b requires #{:seon.test.alpha/x :seon.test.alpha/z} — z not available
      (is (= 1 (count results)))
      (is (= "seon.test.alpha/fn-a" (:seon.fn/qualified-name (first results)))))))

(deftest compatible-functions-output-filter-test
  (testing "filters by output key when specified"
    (let [results (rc/compatible-functions
                   {::rc/conn *test-conn*
                    ::rc/available-keys #{:seon.test.alpha/x :seon.test.alpha/z}
                    ::rc/output-filter :seon.render/documentation})]
      ;; fn-b has required #{:seon.test.alpha/x :seon.test.alpha/z} and output :seon.render/documentation
      (is (= 1 (count results)))
      (is (= "seon.test.alpha/fn-b" (:seon.fn/qualified-name (first results)))))))

(deftest compatible-functions-empty-test
  (testing "returns empty when no functions match"
    (let [results (rc/compatible-functions
                   {::rc/conn *test-conn*
                    ::rc/available-keys #{:nonexistent/key}})]
      (is (empty? results)))))

(deftest compatible-functions-superset-test
  (testing "functions match when available keys are a superset of required"
    (let [results (rc/compatible-functions
                   {::rc/conn *test-conn*
                    ::rc/available-keys #{:seon.test.alpha/x :seon.test.alpha/y
                                          :seon.test.alpha/z :extra/key}})]
      ;; Both fn-a and fn-b should match
      (is (= 2 (count results)))
      ;; Sorted by most required keys first
      (is (= "seon.test.alpha/fn-b"
             (:seon.fn/qualified-name (first results)))))))

;;; ---------------------------------------------------------------------------
;;; render-ns-docs tests
;;; ---------------------------------------------------------------------------

(deftest render-ns-docs-summary-test
  (testing "summary level renders function names only"
    (let [result (rc/render-ns-docs {::rc/conn *test-conn*
                                     ::rc/ns-name "seon.test.alpha"
                                     ::rc/detail :summary})
          doc (:seon.render/documentation result)]
      (is (string? doc))
      (is (re-find #"## seon\.test\.alpha" doc))
      (is (re-find #"- fn-a" doc))
      (is (re-find #"- fn-b" doc))
      ;; Private fn-c excluded in summary
      (is (not (re-find #"fn-c" doc))))))

(deftest render-ns-docs-interface-test
  (testing "interface level includes arglists and key types"
    (let [result (rc/render-ns-docs {::rc/conn *test-conn*
                                     ::rc/ns-name "seon.test.alpha"})
          doc (:seon.render/documentation result)]
      (is (string? doc))
      (is (re-find #"### fn-a" doc))
      (is (re-find #"Input:" doc))
      ;; Private fn-c excluded
      (is (not (re-find #"fn-c" doc))))))

(deftest render-ns-docs-deep-dive-test
  (testing "deep dive includes private functions and full docs"
    (let [result (rc/render-ns-docs {::rc/conn *test-conn*
                                     ::rc/ns-name "seon.test.alpha"
                                     ::rc/detail :deep-dive})
          doc (:seon.render/documentation result)]
      (is (string? doc))
      ;; Private fn-c included in deep-dive
      (is (re-find #"fn-c" doc))
      ;; Full docstrings included
      (is (re-find #"Alpha function A" doc)))))

(deftest render-ns-docs-no-functions-test
  (testing "handles namespaces with no functions"
    (let [result (rc/render-ns-docs {::rc/conn *test-conn*
                                     ::rc/ns-name "seon.nonexistent"})
          doc (:seon.render/documentation result)]
      (is (string? doc))
      (is (re-find #"No functions found" doc)))))

(deftest render-ns-docs-includes-ns-doc-test
  (testing "includes namespace docstring when present"
    (let [result (rc/render-ns-docs {::rc/conn *test-conn*
                                     ::rc/ns-name "seon.test.alpha"})
          doc (:seon.render/documentation result)]
      (is (re-find #"Test namespace alpha" doc)))))

;;; ---------------------------------------------------------------------------
;;; resolve-docs tests
;;; ---------------------------------------------------------------------------

(deftest resolve-docs-fallback-test
  (testing "returns nil when no custom documentation renderer exists"
    ;; No functions produce :seon.render/documentation in beta namespace
    (let [result (rc/resolve-docs {::rc/conn *test-conn*
                                   ::rc/ns-name "seon.test.beta"})]
      (is (nil? result)))))

(deftest resolve-docs-finds-same-ns-test
  (testing "finds documentation renderer in same namespace"
    ;; fn-b produces :seon.render/documentation and is in seon.test.alpha
    (let [result (rc/resolve-docs {::rc/conn *test-conn*
                                   ::rc/ns-name "seon.test.alpha"
                                   ::rc/available-keys #{:seon.test.alpha/x
                                                         :seon.test.alpha/z}})]
      ;; fn-b requires x and z, both available
      ;; It won't resolve because the var doesn't exist, but we can test the logic
      ;; by checking it returns nil (resolve fails on test functions)
      ;; This is expected — the function doesn't actually exist in the runtime
      (is (nil? result)))))

(defn test-doc-renderer
  "A real function that resolve-docs can find via requiring-resolve."
  [_request]
  {:seon.render/documentation "test docs"})

(deftest resolve-docs-positive-test
  (testing "returns var when a resolvable documentation renderer exists"
    ;; Transact a function entry pointing to our real test-doc-renderer
    (let [qname "seon.render.code-test/test-doc-renderer"]
      (d/transact! *test-conn*
                   [{:db/id -10
                     :seon.spec/key :seon.render.code-test/doc-in
                     :seon.spec/contains-keys [:seon.render.code-test/x]
                     :seon.spec/base-type :map}
                    {:db/id -11
                     :seon.spec/key :seon.render.code-test/doc-out
                     :seon.spec/contains-keys [:seon.render/documentation]
                     :seon.spec/base-type :map}
                    {:seon.fn/qualified-name qname
                     :seon.fn/namespace "seon.render.code-test"
                     :seon.fn/name "test-doc-renderer"
                     :seon.fn/private false
                     :seon.fn/row 1
                     :seon.fn/input-spec -10
                     :seon.fn/output-spec -11}])
      (gq/invalidate-output-key-cache!)
      (let [result (rc/resolve-docs {::rc/conn *test-conn*
                                     ::rc/ns-name "seon.render.code-test"
                                     ::rc/available-keys #{:seon.render.code-test/x}})]
        (is (some? result) "should resolve to a var")
        (is (= #'seon.render.code-test/test-doc-renderer result))))))

;;; ---------------------------------------------------------------------------
;;; context-for-agent tests
;;; ---------------------------------------------------------------------------

(deftest context-for-agent-test
  (testing "combines documentation and call graph context"
    (let [result (rc/context-for-agent {::rc/conn *test-conn*
                                        ::rc/ns-name "seon.test.alpha"})
          doc (:seon.render/documentation result)]
      (is (string? doc))
      ;; Contains namespace docs section
      (is (re-find #"seon\.test\.alpha" doc))
      ;; Has entity count
      (is (nat-int? (::rc/entity-count result))))))

;;; ---------------------------------------------------------------------------
;;; Cache integration tests
;;; ---------------------------------------------------------------------------

(deftest output-key-cache-test
  (testing "invalidate-output-key-cache! clears the cache"
    ;; First call populates cache
    (gq/functions-with-output-key {::gq/conn *test-conn*
                                   ::gq/output-key :seon.render/html})
    ;; Invalidate
    (gq/invalidate-output-key-cache!)
    ;; Second call should still work (repopulates)
    (let [results (gq/functions-with-output-key {::gq/conn *test-conn*
                                                 ::gq/output-key :seon.render/html})]
      (is (= 1 (count results)))
      (is (= "seon.test.alpha/fn-a" (:seon.fn/qualified-name (first results)))))))
