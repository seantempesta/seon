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
    [cljs.test :as t :refer [deftest is testing async]]
    [seon.client :as client]
    [seon.db :as db]
    [seon.eval :as eval]
    [seon.render :as render]
    [seon.render.default :as default]
    [seon.schema :as schema]
    [seon.ui.html :as html]))

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
    (is (string? (:seon.render/ai out)))))

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

(deftest pretty-ai-returns-ai-string
  ;; Twin-key convergence (PRD live-tiles §8.2): the text twin of a
  ;; render is :seon.render/ai — pretty-ai emits it, never the retired
  ;; producer key :seon.render/text.
  (let [out (default/pretty-ai {:seon.db/db nil :seon.agent/id "x"})]
    (is (map? out))
    (is (contains? out :seon.render/ai))
    (is (string? (:seon.render/ai out)))
    (is (not (contains? out :seon.render/text)))))

(deftest pretty-html-returns-hiccup-pre
  (let [out (default/pretty-html {:seon.db/db nil :seon.agent/id "x"})]
    (is (map? out))
    (is (contains? out :seon.render/hiccup))
    (let [h (:seon.render/hiccup out)]
      (is (vector? h))
      (is (= :pre (first h))))))

;; ============================================================
;; render-agent-tile (unit 1.4) — the agent's ONE live tile.
;;   • default: unwired agent → seon.render.live-tile/welcome.
;;   • override: :seon.render.live-tile/content (covered in
;;     seon.render.live-tile-test; the legacy :seon.render/html tile
;;     fallback was DELETED in the render sweep — PRD §8.1, no legacy).
;;   • missing agent → {:seon.render/hiccup nil}, never a throw.
;; Fresh isolated conn per test (client/open-agent-conn!) — NEVER the
;; live pod conn.
;; ============================================================

(defn- with-tile-conn
  "Open a fresh conn, seed the :seon.agent kind schema entity + one
   agent row, call `body` with the conn bound. Returns a Promise."
  [agent-id body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact!
                       {:seon.db/tx-data
                        (into (vec (schema/entity-schema-tx-data :seon.agent))
                              [{:seon.agent/id    agent-id
                                :seon.agent/state :idle}])})
                     (.then (fn [_]
                              (binding [db/*conn* conn]
                                (body conn))))))))))

(deftest render-agent-tile-default-renders-welcome
  ;; live-tiles U1: an UNWIRED agent's tile is the core welcome
  ;; (seon.render.live-tile/welcome), no longer the :seon.agent kind
  ;; default seon.render.default/view — the tile slot and the generic
  ;; entity-card slot are separate roles now (PRD §8.1).
  (async done
    (-> (with-tile-conn "tiletest-00001"
          (fn [conn]
            (let [{:seon.render/keys [hiccup ai]}
                  (render/render-agent-tile {:seon.db/db @conn
                                             :seon.agent/id "tiletest-00001"})]
              (is (vector? hiccup) "default tile renders hiccup")
              (is (= "seon-tile" (:class (second hiccup)))
                  "it is the welcome's .seon-tile container")
              (is (string? ai) "the welcome carries its :seon.render/ai twin")
              (is (re-find #"Good (morning|afternoon|evening|night)" ai)
                  "twin carries the time-aware greeting"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-tile-ignores-legacy-html-slot
  ;; Render sweep 2026-06-11 (PRD §8.1, no legacy): a per-entity
  ;; :seon.render/html value is the generic ENTITY-CARD slot only —
  ;; the tile ignores it and falls through to the welcome. The
  ;; positive ::content override path is pinned in
  ;; seon.render.live-tile-test.
  (async done
    (-> (with-tile-conn "tileovr-000001"
          (fn [conn]
            (-> (db/transact!
                  {:seon.db/tx-data
                   [{:seon.agent/id    "tileovr-000001"
                     :seon.render/html [:h1 "card-slot-not-a-tile"]}]})
                (.then (fn [_]
                         (binding [db/*conn* conn]
                           (let [{:seon.render/keys [hiccup]}
                                 (render/render-agent-tile
                                   {:seon.db/db @conn
                                    :seon.agent/id "tileovr-000001"})]
                             (is (= "seon-tile" (:class (second hiccup)))
                                 "tile ignores :seon.render/html and renders the welcome")
                             (is (not= [:h1 "card-slot-not-a-tile"] hiccup)
                                 "the entity-card slot never reaches the tile surface"))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; render-entity-html / render-entity-ai — a THROWING renderer is
;; legible, never a silent vanish (demo-polish 2026-06-12; the old
;; `(catch … nil)` made a broken agent-authored renderer's card
;; indistinguishable from "no renderer" and dead-coded the
;; inspector's render-error fallback).
;; ============================================================

(defn throwing-renderer
  "Deliberately broken renderer — resolvable via eval/lookup-value at
   the munged globalThis path, throws on call."
  [_input]
  (throw (ex-info "boom-renderer" {})))

(defn sibling-renderer
  "Healthy sibling renderer."
  [_input]
  {:seon.render/hiccup [:p "sibling-ok"]})

(deftest render-entity-html-throwing-renderer-shows-banner
  (async done
    (-> (with-tile-conn "rethrow-00001"
          (fn [conn]
            (let [broken {:db/id 1
                          :seon.render/html 'seon.render-test/throwing-renderer}
                  good   {:db/id 2
                          :seon.render/html 'seon.render-test/sibling-renderer}
                  render-one (fn [entity]
                               (render/render-entity-html
                                 {:seon.db/db @conn
                                  :seon.agent/id "rethrow-00001"
                                  :seon.render/entity entity}))
                  banner (html/->string (render-one broken))
                  sib    (html/->string (render-one good))]
              (is (re-find #"render error" banner)
                  "throwing renderer → legible banner, not a vanish")
              (is (re-find #"seon.render-test/throwing-renderer" banner)
                  "banner names the broken fn")
              (is (re-find #"boom-renderer" banner)
                  "banner carries the thrown message")
              (is (re-find #"sibling-ok" sib)
                  "sibling cards render untouched"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-entity-ai-throwing-renderer-is-legible
  (async done
    (-> (with-tile-conn "rethrow-00002"
          (fn [conn]
            (let [out (render/render-entity-ai
                        {:seon.db/db @conn
                         :seon.agent/id "rethrow-00002"
                         :seon.render/entity
                         {:db/id 1
                          :seon.render/ai 'seon.render-test/throwing-renderer}})]
              (is (string? out) "throwing AI renderer → string, never nil")
              (is (re-find #"render error" out))
              (is (re-find #"boom-renderer" out)
                  "the agent sees its own renderer's failure"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-agent-tile-missing-agent-renders-nothing
  (async done
    (-> (with-tile-conn "tilesome-00001"
          (fn [conn]
            (let [out (render/render-agent-tile {:seon.db/db @conn
                                                 :seon.agent/id "no-such-agent0"})]
              (is (= {:seon.render/hiccup nil} out)
                  "missing agent → nil hiccup, no throw"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
