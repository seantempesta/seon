(ns seon.render-test
  "Tests for the render surfaces + the fall-through contract.

   A-2 green criteria:
     • html-render literal hiccup → wrapped in :seon.render/hiccup map
     • html-render unresolvable QUALIFIED symbol → pending-html placeholder
     • html-render unqualified/nil/other slot → pretty-html fallback
     • ai-render unresolvable symbol → pretty-ai fallback

   Plus tests for `seon.eval/lookup-value` — the moved-out
   symbol-resolution primitive both render fns call.

   Run interactively via MCP eval:

     (require 'seon.render-test :reload)
     (cljs.test/run-tests 'seon.render-test)"
  (:require
    [cljs.test :as t :refer [deftest is testing async]]
    [seon.agent.ctx :as ctx]
    [seon.client :as client]
    [seon.config :as config]
    [seon.db :as db]
    [seon.error :as error]
    [seon.eval :as eval]
    [seon.render :as render]
    [seon.render.default :as default]
    [seon.render.live-tile :as live-tile]
    [seon.schema :as schema]
    [seon.ui.html :as html]))

;; The graceful-guard tests below (throwing-renderer → banner / legible line)
;; assert the PROD fallback, so they must run with the fail-loud dial OFF —
;; under strict (the harness default, SEON_RENDER_STRICT=1) those same renders
;; THROW by design. Force env off for the whole ns (process-global, async-safe
;; — a scoped `with-redefs` restores before an async body runs). The dedicated
;; `render-strict-dial-screams-vs-guards` test pins the dial via its own inner
;; `with-redefs`, so it is immune to this fixture.
(defonce ^:private prior-strict-env
  (atom nil))

(t/use-fixtures :once
  {:before (fn []
             (reset! prior-strict-env
                     (.. js/globalThis -process -env -SEON_RENDER_STRICT))
             (set! (.. js/globalThis -process -env -SEON_RENDER_STRICT) "0"))
   :after  (fn []
             (set! (.. js/globalThis -process -env -SEON_RENDER_STRICT)
                   (or @prior-strict-env "")))})

;; ============================================================
;; html-render — literal hiccup short-circuits, unresolvable qualified
;; symbol → pending-html placeholder, unqualified/nil/other → pretty-html.
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

(deftest html-render-nonexistent-symbol-falls-through-to-pending-html
  ;; A qualified symbol that doesn't resolve is the agent's own tile fn
  ;; not (yet) loaded — render the calm `pending-html` placeholder, NOT a
  ;; pretty-html dump of the whole render-context map.
  (let [input {:seon.db/db nil :seon.agent/id "x"}
        out   (render/html-render 'nonexistent/sym input)]
    (is (= (default/pending-html 'nonexistent/sym) out))
    (is (vector? (:seon.render/hiccup out)))
    (is (= :div (first (:seon.render/hiccup out))))))

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
        ai-fn     (eval/lookup-value 'seon.agent/context-root)
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
  ;; Render-key convergence (PRD live-tiles §8.2): the ai render is
  ;; :seon.render/ai — pretty-ai emits it, never the retired producer
  ;; key :seon.render/text.
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
                              [{:seon.agent/id    agent-id}])})
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
              (is (string? ai) "the welcome carries its :seon.render/ai render")
              (is (re-find #"Good (morning|afternoon|evening|night)" ai)
                  "the ai render carries the time-aware greeting"))))
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
;; debug view's render-error fallback).
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
                  ;; The throwing renderer is a seon.* sym → :core fault;
                  ;; deliberate here, so bracket it as EXPECTED (the render is
                  ;; synchronous — record! fires inside this call).
                  banner (html/->string
                           (error/expecting-core-fault! (fn [] (render-one broken))))
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
            (let [out (error/expecting-core-fault!
                        (fn []
                          (render/render-entity-ai
                            {:seon.db/db @conn
                             :seon.agent/id "rethrow-00002"
                             :seon.render/entity
                             {:db/id 1
                              :seon.render/ai 'seon.render-test/throwing-renderer}})))]
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

;; ============================================================
;; slot — place a named block's html render into a stable-id tile slot
;; (the Lane-U gate). A slot ALWAYS returns a [:div {:id "tile-<name>"}]
;;   • a slot renders the named block's :seon.render/html into the div;
;;   • a MISSING block surfaces a :seon/error tile and NEVER throws —
;;     the slot div keeps its stable id and a sibling slot is untouched.
;; ============================================================

(defn- with-block-conn
  "Fresh conn seeded with the :seon.agent kind schema, one agent row, and
   one :seon.agent/ctx block named :mytile whose :seon.render/html is a
   literal hiccup. Calls `body` with the conn bound; returns a Promise."
  [agent-id body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact!
                       {:seon.db/tx-data
                        (into (vec (schema/entity-schema-tx-data :seon.agent))
                              [{:seon.agent/id   agent-id
                                :seon.agent/ctx
                                [{:seon.agent.ctx/name     :mytile
                                  :seon.agent.ctx/priority 50
                                  :seon.render/html        [:h1 "tile!"]}]}])})
                     (.then (fn [_]
                              (binding [db/*conn* conn]
                                (body conn))))))))))

(deftest slot-renders-named-block-html
  (async done
    (-> (with-block-conn "slottest-00001"
          (fn [conn]
            (let [out (render/slot {:seon.db/db @conn :seon.agent/id "slottest-00001"}
                                   :mytile)]
              (is (vector? out) "slot returns hiccup")
              (is (= :div (first out)) "wrapped in a div")
              (is (= "tile-mytile" (:id (second out)))
                  "stable DOM id #tile-<name> for idiomorph")
              (is (= "mytile" (:data-slot (second out)))
                  "carries data-slot=<name> (string, no colon)")
              (is (re-find #"tile!" (html/->string out))
                  "the named block's :seon.render/html renders into the slot"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(defn- with-blocks-conn
  "Fresh conn seeded with the :seon.agent kind schema, one agent row, and the
   given :seon.agent/ctx `blocks`. Calls `body` with the conn bound; Promise.
   The flexible twin of [[with-block-conn]] (which fixes one literal-hiccup
   block) — for slots over symbol-html and map-envelope blocks."
  [agent-id blocks body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (binding [db/*conn* conn]
                 (-> (db/transact!
                       {:seon.db/tx-data
                        (into (vec (schema/entity-schema-tx-data :seon.agent))
                              [{:seon.agent/id  agent-id
                                :seon.agent/ctx (vec blocks)}])})
                     (.then (fn [_]
                              (binding [db/*conn* conn]
                                (body conn))))))))))

(deftest slot-missing-block-routes-through-overridable-error-never-throws
  ;; CONVERGENCE: a missing (or throwing) block surfaces THROUGH the
  ;; overridable seon.render.live-tile/error-tile seam — not a hardcoded
  ;; div — so a consumer's set! override (acme's branded card) applies on
  ;; the agent view too. Never throws; stable id + sibling kept.
  (async done
    (-> (with-block-conn "slottest-00002"
          (fn [conn]
            (let [ctx  {:seon.db/db @conn :seon.agent/id "slottest-00002"}
                  orig live-tile/error-tile]
              (try
                (set! live-tile/error-tile
                      (fn [_] [:div.acme-override "OVERRIDE CARD"]))
                (let [missing (render/slot ctx :no-such-block)
                      sibling (render/slot ctx :mytile)]
                  (is (vector? missing)
                      "a missing block still returns a slot div, never throws")
                  (is (= "tile-no-such-block" (:id (second missing)))
                      "the slot div keeps its stable id even on error")
                  (is (re-find #"OVERRIDE CARD" (html/->string missing))
                      "the error tile routes through the overridable error-tile seam")
                  (is (re-find #"tile!" (html/->string sibling))
                      "a sibling slot renders untouched (never-crash-always-surface)"))
                (finally (set! live-tile/error-tile orig))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest slot-map-envelope-block-renders-hiccup-not-empty
  ;; THE BUG FIX: a block whose :seon.render/html SYMBOL returns the
  ;; :seon.render/html-response MAP envelope must render its HICCUP in the
  ;; slot — not a raw/empty map body. welcome returns the envelope.
  (async done
    (-> (with-blocks-conn "slotmap-00001"
          [{:seon.agent.ctx/name     :wtile
            :seon.agent.ctx/priority 50
            :seon.render/html        'seon.render.live-tile/welcome}]
          (fn [conn]
            (let [out  (render/slot {:seon.db/db @conn :seon.agent/id "slotmap-00001"}
                                    :wtile)
                  body (nth out 2 nil)]
              (is (= "tile-wtile" (:id (second out)))
                  "stable slot id preserved")
              (is (vector? body)
                  "the map envelope is unwrapped to hiccup, not a raw map body")
              (is (not (map? body))
                  "body is not the raw :seon.render/html-response map")
              (is (re-find #"seon-tile" (html/->string out))
                  "the welcome envelope's hiccup actually renders into the slot"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; Fail-loud render dial (seon.config/render-strict?) — a render/converter
;; failure SCREAMS under strict (throws with the offending block + the full
;; malli explain), guards gracefully in prod. Plus the specific transcript
;; root-cause fix: format-eval-row tolerates an off-shape (non-string) stored
;; narration/source instead of throwing invalid-input.
;; ============================================================

(defn ^:no-doc boom-ai-render
  "A converter that throws — the induced render failure for the dial tests."
  [_] (throw (js/Error. "boom-detail-XYZ")))

(deftest eval-row-tolerates-off-shape-rows
  (testing "the previously-failing eval-row shape (non-string narration) now
            renders instead of throwing"
    (let [row (ctx/format-eval-row {:seon.eval/source "(+ 1 2)"
                                    :seon.eval/ok? true
                                    :seon.eval/result-edn "3"
                                    :seon.eval/id "e1"
                                    :seon.eval/narration 5}
                                   false)]
      (is (string? row))
      (is (re-find #"result/e1" row) "the eval row renders whole"))))

(deftest render-strict-dial-screams-vs-guards
  (let [node {:seon.render/ai        'seon.render-test/boom-ai-render
              :seon.agent.ctx/name   :demoblock}]
    (testing "STRICT OFF → graceful one-line guard, no throw (prod behavior)"
      (with-redefs [config/render-strict? (constantly false)]
        ;; boom-ai-render throws → :core fault (seon.* converter); deliberate
        ;; in both dial branches, so bracket as EXPECTED (sync render call —
        ;; STRICT OFF returns the guard string, STRICT ON re-throws; the
        ;; bracket re-propagates either way).
        (let [out (error/expecting-core-fault!
                    (fn [] (render/render :seon.render/ai {} node)))]
          (is (string? out))
          (is (re-find #"⚠ \[:demoblock\] render failed" out)
              "the graceful guard renders the calm one-liner"))))
    (testing "STRICT ON → THROWS loud, naming the offending block"
      (with-redefs [config/render-strict? (constantly true)]
        (let [caught (try (error/expecting-core-fault!
                            (fn [] (render/render :seon.render/ai {} node)))
                          ::no-throw
                          (catch :default e e))]
          (is (not= ::no-throw caught) "strict mode re-throws, never swallows")
          (is (re-find #"\[demoblock\] render failed: boom-detail-XYZ"
                       (ex-message caught))
              "the throw names the block + carries the failure detail")
          (is (true? (:seon.render/strict? (ex-data caught))))
          (is (= :demoblock (:seon.render/where (ex-data caught)))))))))
