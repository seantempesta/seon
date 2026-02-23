(ns seon.render-test
  "Tests for seon.render - including Datalevin-based find-renderer."
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing use-fixtures]]
            [datalevin.core :as d]
            [seon.graph.ingest :as ingest]
            [seon.render :as render]))

;;; ---------------------------------------------------------------------------
;;; Test Fixtures
;;; ---------------------------------------------------------------------------

(def ^:dynamic *conn* nil)

(defn with-temp-datalevin [f]
  (let [dir (str "tmp/test-render-" (System/currentTimeMillis))
        conn (d/get-conn dir ingest/datalevin-schema)]
    (try
      (binding [*conn* conn]
        (f))
      (finally
        ;; Clear the conn override to prevent polluting the running system.
        ;; Tests call render/set-conn! which sets a global atom; without this
        ;; cleanup, the dev hook's test run leaves a stale local conn that
        ;; shadows the real system connection.
        (render/set-conn! nil)
        (d/close conn)
        (let [d (io/file dir)]
          (doseq [file (reverse (file-seq d))]
            (.delete file)))))))

(use-fixtures :each with-temp-datalevin)

;;; ---------------------------------------------------------------------------
;;; Tests
;;; ---------------------------------------------------------------------------

(deftest find-renderer-basic-test
  (testing "finds a renderer whose input keys are subset of data keys"
    ;; Create spec entities
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
    ;; Create fn entity with render-input-keys
    (d/transact! *conn*
                 [{:seon.fn/qualified-name "test.render/widget"
                   :seon.fn/namespace "test.render"
                   :seon.fn/name "widget"
                   :seon.fn/render-input-keys [:test/name :test/color]
                   :seon.fn/input-spec [:seon.spec/key :test.render/widget-request]
                   :seon.fn/output-spec [:seon.spec/key :test.render/widget-response]
                   :seon.fn/updated-at (java.util.Date.)
                   :seon.fn/private false}])

    (is (= "test.render/widget"
           (render/find-renderer *conn*
                                 {:test/name "Foo" :test/color "red"}
                                 :html)))
    (is (= "test.render/widget"
           (render/find-renderer *conn*
                                 {:test/name "Foo" :test/color "red" :extra/key 42}
                                 :ai))
        "Extra keys in data should still match")))

(deftest find-renderer-no-match-test
  (testing "returns nil when no renderer matches"
    (is (nil? (render/find-renderer *conn* {:foo/bar 1} :html)))))

(deftest find-renderer-specificity-test
  (testing "picks the most specific renderer (most input keys)"
    ;; General renderer: 1 key
    (d/transact! *conn*
                 [{:seon.spec/key :test.render/general-response
                   :seon.spec/namespace "test.render"
                   :seon.spec/base-type :map
                   :seon.spec/contains-keys [:seon.render/html]
                   :seon.spec/definition "[:map ...]"
                   :seon.spec/updated-at (java.util.Date.)}
                  {:seon.fn/qualified-name "test.render/general"
                   :seon.fn/namespace "test.render"
                   :seon.fn/name "general"
                   :seon.fn/render-input-keys [:test/name]
                   :seon.fn/output-spec [:seon.spec/key :test.render/general-response]
                   :seon.fn/updated-at (java.util.Date.)
                   :seon.fn/private false}])
    ;; Specific renderer: 2 keys
    (d/transact! *conn*
                 [{:seon.spec/key :test.render/specific-response
                   :seon.spec/namespace "test.render"
                   :seon.spec/base-type :map
                   :seon.spec/contains-keys [:seon.render/html]
                   :seon.spec/definition "[:map ...]"
                   :seon.spec/updated-at (java.util.Date.)}
                  {:seon.fn/qualified-name "test.render/specific"
                   :seon.fn/namespace "test.render"
                   :seon.fn/name "specific"
                   :seon.fn/render-input-keys [:test/name :test/color]
                   :seon.fn/output-spec [:seon.spec/key :test.render/specific-response]
                   :seon.fn/updated-at (java.util.Date.)
                   :seon.fn/private false}])

    (is (= "test.render/specific"
           (render/find-renderer *conn*
                                 {:test/name "X" :test/color "blue"}
                                 :html))
        "Should pick the more specific (2-key) renderer")))

(deftest find-renderer-format-filter-test
  (testing "only matches renderers that support the requested format"
    ;; Renderer that only supports :html, not :ai
    (d/transact! *conn*
                 [{:seon.spec/key :test.render/html-only-response
                   :seon.spec/namespace "test.render"
                   :seon.spec/base-type :map
                   :seon.spec/contains-keys [:seon.render/html]
                   :seon.spec/definition "[:map ...]"
                   :seon.spec/updated-at (java.util.Date.)}
                  {:seon.fn/qualified-name "test.render/html-only"
                   :seon.fn/namespace "test.render"
                   :seon.fn/name "html-only"
                   :seon.fn/render-input-keys [:test/x]
                   :seon.fn/output-spec [:seon.spec/key :test.render/html-only-response]
                   :seon.fn/updated-at (java.util.Date.)
                   :seon.fn/private false}])

    (is (= "test.render/html-only"
           (render/find-renderer *conn* {:test/x 1} :html)))
    (is (nil? (render/find-renderer *conn* {:test/x 1} :ai))
        "Should not match :ai format when output only has :seon.render/html")))

;;; ---------------------------------------------------------------------------
;;; for-html Tests
;;; ---------------------------------------------------------------------------

(deftest for-html-primitives-test
  (testing "renders primitives as spans"
    (is (= [:span {:class "text-text-400 italic"} "nil"]
           (render/for-html nil)))
    (is (= [:span {:class "text-text-200"} "hello"]
           (render/for-html "hello")))
    (is (= [:span {:class "text-amber-400 font-mono"} ":foo/bar"]
           (render/for-html :foo/bar)))
    (is (= [:span {:class "text-cyan-400 font-mono"} "42"]
           (render/for-html 42)))
    (is (= [:span {:class "text-purple-400 font-mono"} "true"]
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
    ;; Renderer with 1 overlapping key
    (d/transact! *conn*
                 [{:seon.spec/key :test.page/narrow-response
                   :seon.spec/namespace "test.page"
                   :seon.spec/base-type :map
                   :seon.spec/contains-keys [:seon.render/html :seon.render/ai]
                   :seon.spec/definition "[:map ...]"
                   :seon.spec/updated-at (java.util.Date.)}
                  {:seon.fn/qualified-name "test.page/narrow"
                   :seon.fn/namespace "test.page"
                   :seon.fn/name "narrow"
                   :seon.fn/render-input-keys [:test/alpha]
                   :seon.fn/output-spec [:seon.spec/key :test.page/narrow-response]
                   :seon.fn/updated-at (java.util.Date.)
                   :seon.fn/private false}])
    ;; Renderer with 2 overlapping keys
    (d/transact! *conn*
                 [{:seon.spec/key :test.page/wide-response
                   :seon.spec/namespace "test.page"
                   :seon.spec/base-type :map
                   :seon.spec/contains-keys [:seon.render/html :seon.render/ai]
                   :seon.spec/definition "[:map ...]"
                   :seon.spec/updated-at (java.util.Date.)}
                  {:seon.fn/qualified-name "test.page/wide"
                   :seon.fn/namespace "test.page"
                   :seon.fn/name "wide"
                   :seon.fn/render-input-keys [:test/alpha :test/beta]
                   :seon.fn/output-spec [:seon.spec/key :test.page/wide-response]
                   :seon.fn/updated-at (java.util.Date.)
                   :seon.fn/private false}])

    (is (= "test.page/wide"
           (render/find-page-renderer *conn*
                                      {:test/alpha 1 :test/beta 2 :test/gamma 3}))
        "Should pick the renderer with the most overlapping keys")))

(deftest find-page-renderer-no-overlap-test
  (testing "returns nil when no keys overlap"
    (is (nil? (render/find-page-renderer *conn*
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

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.render-test)
  nil)
