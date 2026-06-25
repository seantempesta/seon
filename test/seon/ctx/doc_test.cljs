(ns seon.ctx.doc-test
  "seon.ctx.doc contract — the GENERIC markdown-file → context-section
   loader. The mechanism, not any file's prose:
     - a PRESENT file → a renderable section (both views: ai = `;;`
       markdown, html = markdown hiccup);
     - an ABSENT file → NO section (nil — there is NO fallback);
     - it is GENERIC — works for ANY path, not soul-specific.

   File reads hit cwd = repo root (the pod convention). The present-file
   cases use a temp file this test writes under tmp/ (no dependency on
   any particular repo file's wording)."
  (:require
    [cljs.test :refer [deftest is use-fixtures]]
    [clojure.string :as str]
    [seon.ctx.doc :as doc]
    [seon.render :as render]))

(def ^:private tmp-rel "tmp/seon-ctx-doc-test.md")
(def ^:private abs-pathent-rel "tmp/seon-ctx-doc-test-DOES-NOT-EXIST.md")
(def ^:private fixture-text "# Heading\n\nA paragraph with `(some code)` inside.\n")

(defn- abs-path [rel] (str (.cwd js/process) "/" rel))

(defn- write-fixture! []
  (let [fs (js/require "fs")
        full (abs-path tmp-rel)]
    (.mkdirSync fs (abs-path "tmp") #js {:recursive true})
    (.writeFileSync fs full fixture-text "utf8")))

(defn- rm-fixture! []
  (try (.unlinkSync (js/require "fs") (abs-path tmp-rel)) (catch :default _ nil)))

(use-fixtures :once
  {:before (fn [] (write-fixture!))
   :after  (fn [] (rm-fixture!))})

(deftest present-file-yields-a-section-both-views
  (let [sect (doc/doc-section {:seon.ctx.doc/path tmp-rel
                               :seon.ctx/name :fixture
                               :seon.ctx/priority 5})]
    (is (map? sect) "a present file → a section map")
    (is (= :fixture (:seon.ctx/name sect)))
    (is (= 5 (:seon.ctx/priority sect)))
    (is (= tmp-rel (:seon.ctx.doc/path sect)))
    (is (symbol? (:seon.render/ai sect)) "ai slot is a symbol (fresh read each render)")
    (is (symbol? (:seon.render/html sect)) "html slot is a symbol")
    ;; AI view — the file rendered as reader-valid `;;` markdown.
    (let [ai (render/render :seon.render/ai {} sect)]
      (is (string? ai))
      (is (str/includes? ai ";; # Heading") "markdown commented line-by-line")
      (is (every? #(or (str/blank? %) (str/starts-with? % ";;"))
                  (str/split-lines ai))
          "every line is reader-valid (a comment) — keeps the prompt valid source"))
    ;; HTML view — markdown hiccup.
    (let [html (render/render :seon.render/html {} sect)]
      (is (vector? html) "html view is hiccup")
      (is (= :div (first html))))))

(deftest abs-pathent-file-yields-no-section-no-fallback
  (is (nil? (doc/doc-section {:seon.ctx.doc/path abs-pathent-rel
                              :seon.ctx/name :missing
                              :seon.ctx/priority 5}))
      "an abs-pathent file → nil → no section (NO fallback)"))

(deftest loader-is-generic-any-path
  ;; The SAME mechanism produces a section for an unrelated path/name —
  ;; nothing soul-specific is hardcoded.
  (let [a (doc/doc-section {:seon.ctx.doc/path tmp-rel
                            :seon.ctx/name :alpha :seon.ctx/priority 1})
        b (doc/doc-section {:seon.ctx.doc/path tmp-rel
                            :seon.ctx/name :beta :seon.ctx/priority 9})]
    (is (= :alpha (:seon.ctx/name a)))
    (is (= :beta (:seon.ctx/name b)))
    (is (= (:seon.render/ai a) (:seon.render/ai b))
        "same generic render fn regardless of name/priority")))
