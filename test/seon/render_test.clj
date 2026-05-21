(ns seon.render-test
  "Tests for seon.render — including find-renderer / resolve-renderer.
   Uses the canonical datahike `:memory` fixture. The auto-discovery
   machinery (find-renderer, resolve-renderer, find-page-renderer) is
   dormant on the running system (renderer auto-resolution deferred) —
   but the fns themselves still work when given a populated db, which
   is what these tests exercise."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [seon.db :as db]
            [seon.graph.query :as gq]
            [seon.render :as render]
            [seon.test-utils :as tu]))

;;; ---------------------------------------------------------------------------
;;; Test Fixture
;;; ---------------------------------------------------------------------------

(def ^:private render-malli-schema
  "Schema covering the spec + fn entities the fixture transacts directly
   to exercise find-renderer / resolve-renderer / find-page-renderer."
  [:map
   [:seon.fn/qualified-name :seon.fn/qualified-name]
   [:seon.fn/namespace {:optional true} :string]
   [:seon.fn/name {:optional true} :string]
   [:seon.fn/doc {:optional true} :string]
   [:seon.fn/arglists {:optional true} :string]
   [:seon.fn/row {:optional true} :int]
   [:seon.fn/private {:optional true} :boolean]
   [:seon.fn/updated-at {:optional true} :inst]
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
   [:seon.spec/updated-at {:optional true} :inst]])

(use-fixtures :each
  (fn [f]
    ((tu/with-test-db-fixture
       {::tu/namespaces [:seon.runtime]
        ::tu/schemas    {:seon.runtime render-malli-schema}})
     (fn []
       (gq/invalidate-output-key-cache!)
       (try
         (f)
         (finally
           (gq/invalidate-output-key-cache!)))))))

;;; ---------------------------------------------------------------------------
;;; find-renderer Tests
;;; ---------------------------------------------------------------------------

(deftest find-renderer-basic-test
  (testing "finds a renderer whose required keys are subset of data keys"
    (db/transact! :seon.runtime
                  [{:seon.spec/key :test.render/widget-request
                    :seon.spec/namespace "test.render"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:test/name :test/color]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}
                   {:seon.spec/key :test.render/widget-response
                    :seon.spec/namespace "test.render"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:seon.render/html :seon.render/ai]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}])
    (db/transact! :seon.runtime
                  [{:seon.fn/qualified-name "test.render/widget"
                    :seon.fn/namespace "test.render"
                    :seon.fn/name "widget"
                    :seon.fn/input-spec [:seon.spec/key :test.render/widget-request]
                    :seon.fn/output-spec [:seon.spec/key :test.render/widget-response]
                    :seon.fn/updated-at (java.util.Date.)
                    :seon.fn/private false}])

    (is (= "test.render/widget"
           (render/find-renderer :seon.runtime
                                 {:test/name "Foo" :test/color "red"}
                                 :html)))
    (is (= "test.render/widget"
           (render/find-renderer :seon.runtime
                                 {:test/name "Foo" :test/color "red" :extra/key 42}
                                 :ai))
        "Extra keys in data should still match")))

(deftest find-renderer-no-match-test
  (testing "returns nil when no renderer matches"
    (is (nil? (render/find-renderer :seon.runtime {:foo/bar 1} :html)))))

(deftest find-renderer-specificity-test
  (testing "picks the most specific renderer (most required keys)"
    (db/transact! :seon.runtime
                  [{:seon.spec/key :test.render/general-request
                    :seon.spec/namespace "test.render"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:test/name]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}
                   {:seon.spec/key :test.render/general-response
                    :seon.spec/namespace "test.render"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:seon.render/html]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}])
    (db/transact! :seon.runtime
                  [{:seon.fn/qualified-name "test.render/general"
                    :seon.fn/namespace "test.render"
                    :seon.fn/name "general"
                    :seon.fn/input-spec [:seon.spec/key :test.render/general-request]
                    :seon.fn/output-spec [:seon.spec/key :test.render/general-response]
                    :seon.fn/updated-at (java.util.Date.)
                    :seon.fn/private false}])
    (db/transact! :seon.runtime
                  [{:seon.spec/key :test.render/specific-request
                    :seon.spec/namespace "test.render"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:test/name :test/color]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}
                   {:seon.spec/key :test.render/specific-response
                    :seon.spec/namespace "test.render"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:seon.render/html]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}])
    (db/transact! :seon.runtime
                  [{:seon.fn/qualified-name "test.render/specific"
                    :seon.fn/namespace "test.render"
                    :seon.fn/name "specific"
                    :seon.fn/input-spec [:seon.spec/key :test.render/specific-request]
                    :seon.fn/output-spec [:seon.spec/key :test.render/specific-response]
                    :seon.fn/updated-at (java.util.Date.)
                    :seon.fn/private false}])

    (is (= "test.render/specific"
           (render/find-renderer :seon.runtime
                                 {:test/name "X" :test/color "blue"}
                                 :html))
        "Should pick the more specific (2-key) renderer")))

(deftest find-renderer-format-filter-test
  (testing "only matches renderers that support the requested format"
    (db/transact! :seon.runtime
                  [{:seon.spec/key :test.render/html-only-request
                    :seon.spec/namespace "test.render"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:test/x]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}
                   {:seon.spec/key :test.render/html-only-response
                    :seon.spec/namespace "test.render"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:seon.render/html]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}])
    (db/transact! :seon.runtime
                  [{:seon.fn/qualified-name "test.render/html-only"
                    :seon.fn/namespace "test.render"
                    :seon.fn/name "html-only"
                    :seon.fn/input-spec [:seon.spec/key :test.render/html-only-request]
                    :seon.fn/output-spec [:seon.spec/key :test.render/html-only-response]
                    :seon.fn/updated-at (java.util.Date.)
                    :seon.fn/private false}])

    (is (= "test.render/html-only"
           (render/find-renderer :seon.runtime {:test/x 1} :html)))
    (is (nil? (render/find-renderer :seon.runtime {:test/x 1} :ai))
        "Should not match :ai format when output only has :seon.render/html")))

;;; ---------------------------------------------------------------------------
;;; humanize Tests (pure)
;;; ---------------------------------------------------------------------------

(deftest humanize-basic-test
  (testing "strips namespace and converts kebab-case to Title Case"
    (is (= "Total Volume" (render/humanize :seon.health.workout/total-volume)))
    (is (= "Proposed Schema" (render/humanize :proposed-schema)))
    (is (= "Step Title" (render/humanize "step-title")))
    (is (= "Exercise" (render/humanize :seon.getting-started/exercise)))))

(deftest humanize-abbreviations-test
  (testing "uppercases known abbreviations"
    (is (= "API Key" (render/humanize :api-key)))
    (is (= "User ID" (render/humanize :user-id)))
    (is (= "Base URL" (render/humanize :base-url)))
    (is (= "SSE Target" (render/humanize :sse-target)))))

(deftest humanize-edge-cases-test
  (testing "nil returns empty string"
    (is (= "" (render/humanize nil))))
  (testing "empty string returns empty string"
    (is (= "" (render/humanize ""))))
  (testing "single character keyword"
    (is (= "A" (render/humanize :a))))
  (testing "non-keyword non-string input"
    (is (= "42" (render/humanize 42)))))

(deftest humanize-asterisks-test
  (testing "strips asterisks from dynamic var names"
    (is (= "Ctx" (render/humanize :*ctx*)))
    (is (= "Conn" (render/humanize :*conn*)))))

;;; ---------------------------------------------------------------------------
;;; render-schema Tests (pure)
;;; ---------------------------------------------------------------------------

(deftest render-schema-map-test
  (testing "renders :map schema as field specification table"
    (let [schema [:map [:exercise :string] [:sets :int] [:weight :double]]
          result (render/render-schema schema)]
      (is (= :div (first result)))
      (is (some #(and (vector? %) (= :table (first %)))
                (tree-seq vector? rest result))))))

(deftest render-schema-optional-test
  (testing "marks optional fields correctly"
    (let [schema [:map [:name :string] [:note {:optional true} :string]]
          result (render/render-schema schema)]
      (is (some? result)))))

(deftest render-schema-non-map-test
  (testing "non-map schemas render as type badge"
    (let [result (render/render-schema [:string])]
      (is (= :span (first result))))))

;;; ---------------------------------------------------------------------------
;;; for-html Tests (pure)
;;; ---------------------------------------------------------------------------

(deftest for-html-primitives-test
  (testing "renders primitives as spans"
    (is (= [:span {:class "text-text-400 italic"} "nil"]
           (render/for-html nil)))
    (is (= [:span {:class "text-text-200"} "hello"]
           (render/for-html "hello")))
    (is (= [:span {:class "text-text-200"} "Bar"]
           (render/for-html :foo/bar)))
    (is (= [:span {:class "text-signal font-mono"} "42"]
           (render/for-html 42)))
    (is (= [:span {:class "text-eval font-mono"} "true"]
           (render/for-html true)))))

(deftest for-html-map-test
  (testing "renders maps as tables"
    (let [result (render/for-html {:a 1})]
      (is (= :table (first result)))
      (is (vector? result)))))

(deftest for-html-sequential-test
  (testing "renders vectors as lists"
    (let [result (render/for-html [1 2 3])]
      (is (= :ul (first result))))))

(deftest for-html-nested-test
  (testing "renders nested structures recursively"
    (let [result (render/for-html {:items [1 2] :name "test"})]
      (is (= :table (first result))))))

;;; ---------------------------------------------------------------------------
;;; find-page-renderer Tests
;;; ---------------------------------------------------------------------------

(deftest find-page-renderer-overlap-test
  (testing "finds renderer with most key overlap with ns-data"
    (db/transact! :seon.runtime
                  [{:seon.spec/key :test.page/narrow-request
                    :seon.spec/namespace "test.page"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:test/alpha]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}
                   {:seon.spec/key :test.page/narrow-response
                    :seon.spec/namespace "test.page"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:seon.render/html :seon.render/ai]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}])
    (db/transact! :seon.runtime
                  [{:seon.fn/qualified-name "test.page/narrow"
                    :seon.fn/namespace "test.page"
                    :seon.fn/name "narrow"
                    :seon.fn/input-spec [:seon.spec/key :test.page/narrow-request]
                    :seon.fn/output-spec [:seon.spec/key :test.page/narrow-response]
                    :seon.fn/updated-at (java.util.Date.)
                    :seon.fn/private false}])
    (db/transact! :seon.runtime
                  [{:seon.spec/key :test.page/wide-request
                    :seon.spec/namespace "test.page"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:test/alpha :test/beta]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}
                   {:seon.spec/key :test.page/wide-response
                    :seon.spec/namespace "test.page"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:seon.render/html :seon.render/ai]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}])
    (db/transact! :seon.runtime
                  [{:seon.fn/qualified-name "test.page/wide"
                    :seon.fn/namespace "test.page"
                    :seon.fn/name "wide"
                    :seon.fn/input-spec [:seon.spec/key :test.page/wide-request]
                    :seon.fn/output-spec [:seon.spec/key :test.page/wide-response]
                    :seon.fn/updated-at (java.util.Date.)
                    :seon.fn/private false}])

    (is (= "test.page/wide"
           (render/find-page-renderer :seon.runtime
                                      {:test/alpha 1 :test/beta 2 :test/gamma 3}))
        "Should pick the renderer with the most overlapping keys")))

(deftest find-page-renderer-no-overlap-test
  (testing "returns nil when no keys overlap"
    (is (nil? (render/find-page-renderer :seon.runtime
                                         {:unrelated/key 1})))))

;;; ---------------------------------------------------------------------------
;;; render-namespace Tests
;;; ---------------------------------------------------------------------------

(deftest render-namespace-default-html-test
  (testing "uses default renderer when no page renderer in DB"
    (render/set-conn! nil)  ; no-op stub kept for legacy callers; harmless
    (let [ns-data {:seon.render/ns-vars {:foo 1} :some/key "val"}
          result (render/render-namespace {::render/ns-data ns-data
                                           ::render/format :html})]
      (is (vector? result) "Default HTML should return hiccup")
      (is (= :table (first result))))))

(deftest render-namespace-default-ai-test
  (testing "uses default renderer for :ai format"
    (render/set-conn! nil)
    (let [ns-data {:some/key "hello"}
          result (render/render-namespace {::render/ns-data ns-data
                                           ::render/format :ai})]
      (is (string? result) "AI format should return string"))))

(deftest render-namespace-default-raw-test
  (testing "uses default renderer for :raw format"
    (render/set-conn! nil)
    (let [ns-data {:some/key "val"}
          result (render/render-namespace {::render/ns-data ns-data
                                           ::render/format :raw})]
      (is (= ns-data result) "Raw format returns ns-data as-is"))))

;;; ---------------------------------------------------------------------------
;;; default-namespace-render Tests (pure)
;;; ---------------------------------------------------------------------------

(deftest default-namespace-render-test
  (testing "dispatches by format"
    (let [data {:a 1 :b "two"}]
      (is (vector? (render/default-namespace-render data :html)))
      (is (string? (render/default-namespace-render data :ai)))
      (is (= data (render/default-namespace-render data :raw))))))

;;; ---------------------------------------------------------------------------
;;; namespace-web-params Tests (pure)
;;; ---------------------------------------------------------------------------

(deftest namespace-web-params-basic-test
  (testing "namespaces query params under target namespace"
    (is (= {:seon.health.workout/sort-by "weight"
            :seon.health.workout/page "2"}
           (render/namespace-web-params {"sort-by" "weight" "page" "2"}
                                        "seon.health.workout")))))

(deftest namespace-web-params-excludes-system-params-test
  (testing "excludes system-reserved params"
    (is (= {:seon.health.workout/sort-by "weight"}
           (render/namespace-web-params {"sort-by" "weight"
                                         "instance" "abc123"
                                         "format" "ai"
                                         "view" "introspect"}
                                        "seon.health.workout")))))

(deftest namespace-web-params-empty-test
  (testing "returns nil for empty/nil params"
    (is (nil? (render/namespace-web-params {} "seon.foo")))
    (is (nil? (render/namespace-web-params nil "seon.foo")))))

;;; ---------------------------------------------------------------------------
;;; resolve-renderer Tests
;;; ---------------------------------------------------------------------------

(deftest resolve-renderer-specificity-test
  (testing "picks renderer with most required keys (strict subset)"
    (db/transact! :seon.runtime
                  [{:seon.spec/key :test.resolve/a-request
                    :seon.spec/namespace "test.resolve"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:test/alpha]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}
                   {:seon.spec/key :test.resolve/a-response
                    :seon.spec/namespace "test.resolve"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:seon.render/html]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}])
    (db/transact! :seon.runtime
                  [{:seon.fn/qualified-name "test.resolve/renderer-a"
                    :seon.fn/namespace "test.resolve"
                    :seon.fn/name "renderer-a"
                    :seon.fn/input-spec [:seon.spec/key :test.resolve/a-request]
                    :seon.fn/output-spec [:seon.spec/key :test.resolve/a-response]
                    :seon.fn/updated-at (java.util.Date.)
                    :seon.fn/private false}])
    (db/transact! :seon.runtime
                  [{:seon.spec/key :test.resolve/b-request
                    :seon.spec/namespace "test.resolve"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:test/alpha :test/beta]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}
                   {:seon.spec/key :test.resolve/b-response
                    :seon.spec/namespace "test.resolve"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:seon.render/html]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}])
    (db/transact! :seon.runtime
                  [{:seon.fn/qualified-name "test.resolve/renderer-b"
                    :seon.fn/namespace "test.resolve"
                    :seon.fn/name "renderer-b"
                    :seon.fn/input-spec [:seon.spec/key :test.resolve/b-request]
                    :seon.fn/output-spec [:seon.spec/key :test.resolve/b-response]
                    :seon.fn/updated-at (java.util.Date.)
                    :seon.fn/private false}])

    (is (= "test.resolve/renderer-b"
           (render/find-renderer :seon.runtime
                                 {:test/alpha 1 :test/beta 2}
                                 :html)))))

(deftest resolve-renderer-missing-keys-excludes-test
  (testing "renderer excluded when required keys not all present"
    (db/transact! :seon.runtime
                  [{:seon.spec/key :test.excl/request
                    :seon.spec/namespace "test.excl"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:test/alpha :test/beta]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}
                   {:seon.spec/key :test.excl/response
                    :seon.spec/namespace "test.excl"
                    :seon.spec/base-type :map
                    :seon.spec/contains-keys [:seon.render/html]
                    :seon.spec/definition "[:map ...]"
                    :seon.spec/updated-at (java.util.Date.)}])
    (db/transact! :seon.runtime
                  [{:seon.fn/qualified-name "test.excl/needs-two"
                    :seon.fn/namespace "test.excl"
                    :seon.fn/name "needs-two"
                    :seon.fn/input-spec [:seon.spec/key :test.excl/request]
                    :seon.fn/output-spec [:seon.spec/key :test.excl/response]
                    :seon.fn/updated-at (java.util.Date.)
                    :seon.fn/private false}])

    (is (nil? (render/resolve-renderer :seon.runtime
                                       #{:test/alpha}
                                       "test.excl"))
        "Should not resolve when required key :test/beta is missing")))

(deftest resolve-renderer-no-candidates-test
  (testing "returns nil when no renderers in DB"
    (is (nil? (render/resolve-renderer :seon.runtime
                                       #{:foo/bar}
                                       "foo")))))
