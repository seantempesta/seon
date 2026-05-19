(ns seon.render-test
  "Tests for spec-05 §15 render dispatch + pretty-print floor.

   A-2 green criterion (spec-05 §10.2):
     • html-dispatch literal hiccup → wrapped in :seon.render/hiccup map
     • html-dispatch unresolvable symbol → pretty-html fallback
     • html-dispatch unqualified symbol → does not throw

   Run interactively via MCP eval:

     (require 'seon.render-test :reload)
     (cljs.test/run-tests 'seon.render-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing]]
    [seon.render :as render]
    [seon.render.default :as default]))

;; ============================================================
;; html-dispatch — literal hiccup short-circuits, missing symbol
;; falls through to pretty-html, unqualified symbol doesn't throw.
;; ============================================================

(deftest html-dispatch-literal-hiccup-wraps-as-is
  (let [out (render/html-dispatch [:h1 "hi"]
                                  {:seon.db/db    nil
                                   :seon.agent/id "x"})]
    (is (= {:seon.render/hiccup [:h1 "hi"]} out))))

(deftest html-dispatch-literal-hiccup-with-attrs-wraps-as-is
  (let [vec [:div {:class "foo"} [:span "bar"]]
        out (render/html-dispatch vec
                                  {:seon.db/db    nil
                                   :seon.agent/id "x"})]
    (is (= {:seon.render/hiccup vec} out))))

(deftest html-dispatch-nonexistent-symbol-falls-through-to-pretty-html
  (let [input {:seon.db/db nil :seon.agent/id "x"}
        out   (render/html-dispatch 'nonexistent/sym input)]
    (is (= (default/pretty-html input) out))
    (is (vector? (:seon.render/hiccup out)))
    (is (= :pre (first (:seon.render/hiccup out))))))

(deftest html-dispatch-unqualified-symbol-does-not-throw
  ;; Spec-05 §15.2: the resolver returns nil for unqualified symbols;
  ;; html-dispatch's symbol-branch sees nil and falls through to
  ;; pretty-html instead of throwing.
  (let [input {:seon.db/db nil :seon.agent/id "x"}]
    (is (= (default/pretty-html input)
           (render/html-dispatch 'bare-sym input)))))

(deftest html-dispatch-nil-slot-falls-through-to-pretty-html
  (let [input {:seon.db/db nil :seon.agent/id "x"}]
    (is (= (default/pretty-html input)
           (render/html-dispatch nil input)))))

(deftest html-dispatch-arbitrary-value-falls-through-to-pretty-html
  (let [input {:seon.db/db nil :seon.agent/id "x"}]
    (is (= (default/pretty-html input)
           (render/html-dispatch 42 input)))
    (is (= (default/pretty-html input)
           (render/html-dispatch "string" input)))))

;; ============================================================
;; ai-dispatch — symbol-only slot; missing → pretty-ai.
;; ============================================================

(deftest ai-dispatch-nonexistent-symbol-falls-through-to-pretty-ai
  (let [input {:seon.db/db nil :seon.agent/id "x"}
        out   (render/ai-dispatch 'nonexistent/sym input)]
    (is (= (default/pretty-ai input) out))
    (is (string? (:seon.render/text out)))))

(deftest ai-dispatch-nil-slot-falls-through-to-pretty-ai
  (let [input {:seon.db/db nil :seon.agent/id "x"}]
    (is (= (default/pretty-ai input)
           (render/ai-dispatch nil input)))))

(deftest ai-dispatch-unqualified-symbol-does-not-throw
  (let [input {:seon.db/db nil :seon.agent/id "x"}]
    (is (= (default/pretty-ai input)
           (render/ai-dispatch 'bare-sym input)))))

;; ============================================================
;; resolve-symbol — globalThis walker for system fns (A-4); bootstrap
;; compile-state path for agent-defined fns (stub until A-8). Never
;; throws on bad input.
;; ============================================================

(deftest resolve-symbol-finds-system-fn
  ;; The :client bundle ships seon.render.default — resolve-symbol
  ;; should walk globalThis and return the callable.
  (let [view-fn (render/resolve-symbol 'seon.render.default/view)
        ctx-fn  (render/resolve-symbol 'seon.render.default/ctx)
        pretty-fn (render/resolve-symbol 'seon.render.default/pretty-html)]
    (is (fn? view-fn))
    (is (fn? ctx-fn))
    (is (fn? pretty-fn))))

(deftest resolve-symbol-returns-nil-for-nonexistent-ns
  (is (nil? (render/resolve-symbol 'no.such.ns/sym)))
  (is (nil? (render/resolve-symbol 'seon.render.default/no-such-fn))))

(deftest resolve-symbol-returns-nil-for-unqualified
  (is (nil? (render/resolve-symbol 'bare))))

(deftest resolve-symbol-returns-nil-for-nil-and-non-symbol
  (is (nil? (render/resolve-symbol nil)))
  (is (nil? (render/resolve-symbol :keyword)))
  (is (nil? (render/resolve-symbol "string"))))

;; ============================================================
;; pretty-print floors — shapes match the spec'd response schemas.
;; ============================================================

(deftest pretty-ai-returns-text-string
  (let [out (default/pretty-ai {:seon.db/db nil :seon.agent/id "x"})]
    (is (map? out))
    (is (contains? out :seon.render/text))
    (is (string? (:seon.render/text out)))))

(deftest pretty-html-returns-hiccup-pre
  (let [out (default/pretty-html {:seon.db/db nil :seon.agent/id "x"})]
    (is (map? out))
    (is (contains? out :seon.render/hiccup))
    (let [h (:seon.render/hiccup out)]
      (is (vector? h))
      (is (= :pre (first h))))))
