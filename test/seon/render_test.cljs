(ns seon.render-test
  "Tests for the render surfaces + the fall-through contract.

   A-2 green criteria:
     • html-render literal hiccup → wrapped in :seon.render/hiccup map
     • html-render unresolvable symbol → pretty-html fallback
     • html-render unqualified symbol → does not throw
     • ai-render unresolvable symbol → pretty-ai fallback

   Plus tests for `seon.eval/lookup-value` — the moved-out
   symbol-resolution primitive both render fns call.

   Run interactively via MCP eval:

     (require 'seon.render-test :reload)
     (cljs.test/run-tests 'seon.render-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing]]
    [seon.eval :as eval]
    [seon.render :as render]
    [seon.render.default :as default]))

;; ============================================================
;; html-render — literal hiccup short-circuits, missing symbol
;; falls through to pretty-html, unqualified symbol doesn't throw.
;; ============================================================

(deftest html-render-literal-hiccup-wraps-as-is
  (let [out (render/html-render [:h1 "hi"]
                                {:seon.db/db    nil
                                 :seon.agent/id "x"})]
    (is (= {:seon.render/hiccup [:h1 "hi"]} out))))

(deftest html-render-literal-hiccup-with-attrs-wraps-as-is
  (let [vec [:div {:class "foo"} [:span "bar"]]
        out (render/html-render vec
                                {:seon.db/db    nil
                                 :seon.agent/id "x"})]
    (is (= {:seon.render/hiccup vec} out))))

(deftest html-render-nonexistent-symbol-falls-through-to-pretty-html
  (let [input {:seon.db/db nil :seon.agent/id "x"}
        out   (render/html-render 'nonexistent/sym input)]
    (is (= (default/pretty-html input) out))
    (is (vector? (:seon.render/hiccup out)))
    (is (= :pre (first (:seon.render/hiccup out))))))

(deftest html-render-unqualified-symbol-does-not-throw
  ;; The resolver returns nil for unqualified symbols;
  ;; html-render's symbol-branch sees nil and falls through to
  ;; pretty-html instead of throwing.
  (let [input {:seon.db/db nil :seon.agent/id "x"}]
    (is (= (default/pretty-html input)
           (render/html-render 'bare-sym input)))))

(deftest html-render-nil-slot-falls-through-to-pretty-html
  (let [input {:seon.db/db nil :seon.agent/id "x"}]
    (is (= (default/pretty-html input)
           (render/html-render nil input)))))

(deftest html-render-arbitrary-value-falls-through-to-pretty-html
  (let [input {:seon.db/db nil :seon.agent/id "x"}]
    (is (= (default/pretty-html input)
           (render/html-render 42 input)))
    (is (= (default/pretty-html input)
           (render/html-render "string" input)))))

;; ============================================================
;; ai-render — symbol-only slot; missing → pretty-ai.
;; ============================================================

(deftest ai-render-nonexistent-symbol-falls-through-to-pretty-ai
  (let [input {:seon.db/db nil :seon.agent/id "x"}
        out   (render/ai-render 'nonexistent/sym input)]
    (is (= (default/pretty-ai input) out))
    (is (string? (:seon.render/text out)))))

(deftest ai-render-nil-slot-falls-through-to-pretty-ai
  (let [input {:seon.db/db nil :seon.agent/id "x"}]
    (is (= (default/pretty-ai input)
           (render/ai-render nil input)))))

(deftest ai-render-unqualified-symbol-does-not-throw
  (let [input {:seon.db/db nil :seon.agent/id "x"}]
    (is (= (default/pretty-ai input)
           (render/ai-render 'bare-sym input)))))

;; ============================================================
;; eval/lookup-value — globalThis walker (moved here from
;; seon.render/resolve-symbol; same semantics, lives next to the
;; analyzer-cache concerns in seon.eval). Never throws on bad input.
;; ============================================================

(deftest lookup-value-finds-system-fn
  ;; The :client bundle ships seon.render.default — lookup-value
  ;; should walk globalThis and return the callable.
  (let [view-fn   (eval/lookup-value 'seon.render.default/view)
        ai-fn     (eval/lookup-value 'seon.agent/assemble-context)
        pretty-fn (eval/lookup-value 'seon.render.default/pretty-html)]
    (is (fn? view-fn))
    (is (fn? ai-fn))
    (is (fn? pretty-fn))))

(deftest lookup-value-returns-nil-for-nonexistent-ns
  (is (nil? (eval/lookup-value 'no.such.ns/sym)))
  (is (nil? (eval/lookup-value 'seon.render.default/no-such-fn))))

(deftest lookup-value-returns-nil-for-unqualified
  (is (nil? (eval/lookup-value 'bare))))

(deftest lookup-value-returns-nil-for-nil-and-non-symbol
  (is (nil? (eval/lookup-value nil)))
  (is (nil? (eval/lookup-value :keyword)))
  (is (nil? (eval/lookup-value "string"))))

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
