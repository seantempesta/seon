(ns seon.handlers.test-test
  "Pure tests for the `:seon.test` entity shape and render handler."
  (:require
    [clojure.string :as str]
    [cljs.test :refer [deftest is]]
    [seon.agent.ctx.render-fns]
    [seon.handlers.test :as h-test]
    [seon.render :as render]
    [seon.render.canvas :as canvas]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; The kind exists — synchronous, no conn needed.
;; ---------------------------------------------------------------------------

(deftest seon-test-is-a-registered-entity-shape
  (is (schema/registered? :seon.test)
      ":seon.test is registered as an entity-shape :map schema")
  (let [row (some #(when (= :seon.test (:seon.schema.catalog/key %)) %)
                  (schema/entity-catalog))]
    (is (some? row) ":seon.test appears in the derived catalog")
    (is (= #{:seon.test/sym}
           (:seon.schema.catalog/required-attrs row))
        "only :seon.test/sym is required")))

;; ---------------------------------------------------------------------------
;; Handler renders a seeded entity — synchronous, the handler reads only
;; the entity map (no DB needed for the per-kind render itself).
;; ---------------------------------------------------------------------------

(def ^:private ent-pass
  {:seon.test/sym "demo.ns/t-pass"
   :seon.test/source "(deftest t-pass (is (= 1 1)))"
   :seon.test/last-passed-at (js/Date.)})

(def ^:private ent-fail
  {:seon.test/sym "demo.ns/t-fail"
   :seon.test/source "(deftest t-fail (is (= 1 2)))"
   :seon.test/last-failed-at (js/Date.)
   :seon.test/last-failure-summary "expected 1, got 2"})

(def ^:private ent-none
  {:seon.test/sym "demo.ns/t-none"
   :seon.test/source "(deftest t-none (is true))"})

(deftest render-ai-shows-sym-source-and-status
  ;; The handler is a CONVERTER now — render-ai returns a BARE String
  ;; (keystone), called with the entity under :seon.render/node.
  (let [pass (h-test/render-ai {:seon.render/node ent-pass})
        fail (h-test/render-ai {:seon.render/node ent-fail})
        none (h-test/render-ai {:seon.render/node ent-none})]
    (is (str/includes? pass "demo.ns/t-pass") "header carries the sym")
    (is (str/includes? pass "(deftest t-pass") "source rendered")
    ;; The three run-states render DISTINCTLY — anchor on the run-state STEM
    ;; (passing / failing / no run), the shared status contract, NOT the
    ;; decorative glyph (✓/✗/•) + exact phrase (a render surface).
    (is (str/includes? pass "passing") "passed run renders the passing state")
    (is (str/includes? fail "failing") "failed run renders the failing state")
    (is (str/includes? none "no run") "no recorded run renders the no-run state")
    ;; …and the three states are mutually distinct (a pass is never shown as
    ;; a fail, etc.) — the behavior the glyphs used to stand in for.
    (is (and (not (str/includes? pass "failing"))
             (not (str/includes? fail "passing"))
             (not= pass fail) (not= fail none) (not= pass none))
        "the three run-states are mutually distinct")
    (is (str/includes? fail "expected 1, got 2") "failure summary shown")))

(deftest render-html-is-valid-card
  ;; render-html returns BARE hiccup now (keystone), node under :seon.render/node.
  (let [hiccup (h-test/render-html {:seon.render/node ent-fail})]
    (is (vector? hiccup) "html form is a hiccup vector")
    (is (= :div (first hiccup)) "outer container is a :div")
    (is (canvas/valid-hiccup? hiccup) "passes valid-hiccup?")
    (let [s (pr-str hiccup)]
      (is (str/includes? s "demo.ns/t-fail") "sym appears in the hiccup")
      (is (str/includes? s "failing") "failing pill present")
      (is (str/includes? s "(deftest t-fail") "source present"))))

;; ---------------------------------------------------------------------------
;; Shape resolution end-to-end — render-entity-ai/-html route a :seon.test
;; entity through the handler via the active schema projection.
;; ---------------------------------------------------------------------------

(deftest render-entity-routes-through-the-handler
  (let [ent  {:seon.test/sym "demo.ns/t-attached"
              :seon.test/source "(deftest t-attached (is (= 4 (+ 2 2))))"
              :seon.test/last-passed-at (js/Date.)}
        ai   (render/render-entity-ai {:seon.render/entity ent})
        html (render/render-entity-html {:seon.render/entity ent})]
    (is (string? ai) "render-entity-ai resolves the :seon.test shape")
    (is (str/includes? ai "demo.ns/t-attached") "ai shows the sym")
    (is (vector? html) "render-entity-html resolves the :seon.test shape")
    (is (= :div (first html)) "html is a card div")))
