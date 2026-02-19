(ns seon.render-test
  "Tests for seon.render - including Datalevin-based find-renderer."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
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
        (d/close conn)
        (let [d (clojure.java.io/file dir)]
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

(comment
  (require '[kaocha.repl :as k])
  (k/run 'seon.render-test)
  nil)
