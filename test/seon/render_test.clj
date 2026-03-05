(ns seon.render-test
  "Tests for seon.render - including Datalevin-based find-renderer."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.db :as db]
            [seon.db.datalevin.conn :as conn]
            [seon.graph.ingest :as ingest]
            [seon.graph.query :as gq]
            [seon.render :as render]
            [seon.test-utils]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *conn* nil)

(defn with-temp-datalevin [f]
  (let [dir (str "tmp/test-render-" (System/currentTimeMillis))
        conn (d/create-conn dir ingest/datalevin-schema)
        fake-mgr {::conn/port 0
                  ::conn/connections (atom {:seon.runtime {::conn/connection conn}})}]
    (try
      (binding [*conn* conn
                db/*direct-mode* true
                db/*conn-manager* fake-mgr]
        (gq/invalidate-output-key-cache!)
        (f))
      (finally
        ;; Clear the conn override to prevent polluting the running system.
        ;; Tests call render/set-conn! which sets a global atom; without this
        ;; cleanup, the dev hook's test run leaves a stale local conn that
        ;; shadows the real system connection.
        (render/set-conn! nil)
        (gq/invalidate-output-key-cache!)
        (d/close conn)
        (let [d (io/file dir)]
          (doseq [file (reverse (file-seq d))]
            (.delete file)))))))

(use-fixtures :each with-temp-datalevin)

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(deftest find-renderer-basic-test
  (testing "finds a renderer whose required keys are subset of data keys"
    ;; Create spec entities (must be transacted BEFORE fn entities for lookup refs)
    (d/transact! *conn*
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
    ;; Create fn entity with spec refs (no render-input-keys - computed at query time)
    (d/transact! *conn*
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
    ;; General renderer: 1 required key
    (d/transact! *conn*
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
    (d/transact! *conn*
                 [{:seon.fn/qualified-name "test.render/general"
                   :seon.fn/namespace "test.render"
                   :seon.fn/name "general"
                   :seon.fn/input-spec [:seon.spec/key :test.render/general-request]
                   :seon.fn/output-spec [:seon.spec/key :test.render/general-response]
                   :seon.fn/updated-at (java.util.Date.)
                   :seon.fn/private false}])
    ;; Specific renderer: 2 required keys
    (d/transact! *conn*
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
    (d/transact! *conn*
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
    ;; Renderer that only supports :html, not :ai
    (d/transact! *conn*
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
    (d/transact! *conn*
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
;;; humanize Tests
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
;;; render-schema Tests
;;; ---------------------------------------------------------------------------

(deftest render-schema-map-test
  (testing "renders :map schema as field specification table"
    (let [schema [:map [:exercise :string] [:sets :int] [:weight :double]]
          result (render/render-schema schema)]
      (is (= :div (first result)))
      ;; Should contain a table
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
;;; for-html Tests
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
    ;; Renderer with 1 required key
    (d/transact! *conn*
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
    (d/transact! *conn*
                 [{:seon.fn/qualified-name "test.page/narrow"
                   :seon.fn/namespace "test.page"
                   :seon.fn/name "narrow"
                   :seon.fn/input-spec [:seon.spec/key :test.page/narrow-request]
                   :seon.fn/output-spec [:seon.spec/key :test.page/narrow-response]
                   :seon.fn/updated-at (java.util.Date.)
                   :seon.fn/private false}])
    ;; Renderer with 2 required keys
    (d/transact! *conn*
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
    (d/transact! *conn*
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
    (render/set-conn! *conn*)
    (let [ns-data {:seon.render/ns-vars {:foo 1} :some/key "val"}
          result (render/render-namespace {::render/ns-data ns-data
                                           ::render/format :html})]
      (is (vector? result) "Default HTML should return hiccup")
      (is (= :table (first result))))))

(deftest render-namespace-default-ai-test
  (testing "uses default renderer for :ai format"
    (render/set-conn! *conn*)
    (let [ns-data {:some/key "hello"}
          result (render/render-namespace {::render/ns-data ns-data
                                           ::render/format :ai})]
      (is (string? result) "AI format should return string"))))

(deftest render-namespace-default-raw-test
  (testing "uses default renderer for :raw format"
    (render/set-conn! *conn*)
    (let [ns-data {:some/key "val"}
          result (render/render-namespace {::render/ns-data ns-data
                                           ::render/format :raw})]
      (is (= ns-data result) "Raw format returns ns-data as-is"))))

;;; ---------------------------------------------------------------------------
;;; default-namespace-render Tests
;;; ---------------------------------------------------------------------------

(deftest default-namespace-render-test
  (testing "dispatches by format"
    (let [data {:a 1 :b "two"}]
      (is (vector? (render/default-namespace-render data :html)))
      (is (string? (render/default-namespace-render data :ai)))
      (is (= data (render/default-namespace-render data :raw))))))

;;; ---------------------------------------------------------------------------
;;; namespace-web-params Tests
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
    ;; Renderer A: requires 1 key
    (d/transact! *conn*
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
    (d/transact! *conn*
                 [{:seon.fn/qualified-name "test.resolve/renderer-a"
                   :seon.fn/namespace "test.resolve"
                   :seon.fn/name "renderer-a"
                   :seon.fn/input-spec [:seon.spec/key :test.resolve/a-request]
                   :seon.fn/output-spec [:seon.spec/key :test.resolve/a-response]
                   :seon.fn/updated-at (java.util.Date.)
                   :seon.fn/private false}])
    ;; Renderer B: requires 2 keys (more specific)
    (d/transact! *conn*
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
    (d/transact! *conn*
                 [{:seon.fn/qualified-name "test.resolve/renderer-b"
                   :seon.fn/namespace "test.resolve"
                   :seon.fn/name "renderer-b"
                   :seon.fn/input-spec [:seon.spec/key :test.resolve/b-request]
                   :seon.fn/output-spec [:seon.spec/key :test.resolve/b-response]
                   :seon.fn/updated-at (java.util.Date.)
                   :seon.fn/private false}])

    ;; Both keys available -> B wins (more specific)
    ;; Test via find-renderer which returns a string (resolve-renderer returns var which won't resolve)
    (is (= "test.resolve/renderer-b"
           (render/find-renderer :seon.runtime
                                 {:test/alpha 1 :test/beta 2}
                                 :html)))))

(deftest resolve-renderer-missing-keys-excludes-test
  (testing "renderer excluded when required keys not all present"
    ;; Renderer needs alpha + beta, but only alpha available
    (d/transact! *conn*
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
    (d/transact! *conn*
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

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.render-test)
  nil)
