(ns seon.handlers.test-test
  "Tests for the `:seon.test` entity KIND (coherent-bootstrap-indexing
   PRD, Step 3) and its render handler `seon.handlers.test`.

   Step 3 promoted `:seon.test` from a bag of registered attrs to a real
   renderable entity kind: it now lands in `seon.schema/entity-schema-keys`,
   decomposes into a `:seon.schema` row at boot, and renders per-kind via
   `seon.handlers.test/render-ai` / `render-html` — the same mechanism the
   `:seon.fn` / `:seon.schema` kinds use.

   These tests pin:
     - `:seon.test` IS in `entity-schema-keys` (the kind exists)
     - the handler renders a seeded test entity (ai text + html hiccup),
       showing the sym, the source, and a pass/fail/no-run status
     - `seon.render/render-entity-ai` / `-html` route a `:seon.test` entity
       through the handler (kind resolution end-to-end), once the schema
       decomposition row is in the conn
     - `seon.agent/render-namespace` shows a ns's tests under that ns

   The seed transacts `:seon.test` rows directly + the entity-schema
   decomposition (so kind-resolution has a `:seon.schema` row to read) —
   nothing here drives the agent loop.

   Run interactively via MCP eval:
     (require 'seon.handlers.test-test :reload)
     (cljs.test/run-tests 'seon.handlers.test-test)"
  (:require
    [clojure.string :as str]
    [cljs.test :as t :refer [deftest is testing async]]
    [seon.agent :as agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.handlers.test :as h-test]
    [seon.render :as render]
    [seon.render.live-tile :as tile]
    [seon.schema :as schema]))

;; ---------------------------------------------------------------------------
;; The kind exists — synchronous, no conn needed.
;; ---------------------------------------------------------------------------

(deftest seon-test-is-an-entity-kind
  (is (schema/registered? :seon.test)
      ":seon.test is registered as an entity-shape :map schema")
  (is (contains? (set (schema/entity-schema-keys)) :seon.test)
      ":seon.test appears in entity-schema-keys")
  ;; Required-attrs count: only :seon.test/sym is non-optional. Assert it
  ;; from the DETERMINISTIC decomposition (entity-schema-tx-data, the same
  ;; builder the boot seed runs) rather than the lazy *schema-required-counts
  ;; cache — the cache is a side-effect of decomposition, so reading it
  ;; cold (the pristine :node-test build, where nothing pre-decomposed
  ;; :seon.test) returns nil. Decomposing here both populates the cache and
  ;; gives the authoritative required-attr list to count.
  (let [txd  (schema/entity-schema-tx-data :seon.test)
        reqs (->> txd
                  (filter (fn [[_ _ a _]] (= a :seon.schema/required-attrs)))
                  (mapv (fn [[_ _ _ v]] v)))]
    (is (= [:seon.test/sym] reqs)
        "only :seon.test/sym is a required attr in the decomposition")
    (is (= 1 (schema/schema-required-count :seon.test))
        "required-count is 1 once the kind is decomposed")))

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
    (is (str/includes? pass "[test demo.ns/t-pass]") "header carries the sym")
    (is (str/includes? pass "(deftest t-pass") "source rendered")
    (is (str/includes? pass "✓ test passing") "passing glyph for a passed run")
    (is (str/includes? fail "✗ test failing") "failing glyph for a failed run")
    (is (str/includes? fail "expected 1, got 2") "failure summary shown")
    (is (str/includes? none "• test (no run recorded)")
        "no-run glyph when no last-* present")))

(deftest render-html-is-valid-card
  ;; render-html returns BARE hiccup now (keystone), node under :seon.render/node.
  (let [hiccup (h-test/render-html {:seon.render/node ent-fail})]
    (is (vector? hiccup) "html form is a hiccup vector")
    (is (= :div (first hiccup)) "outer container is a :div")
    (is (tile/valid-hiccup? hiccup) "passes valid-hiccup?")
    (let [s (pr-str hiccup)]
      (is (str/includes? s "demo.ns/t-fail") "sym appears in the hiccup")
      (is (str/includes? s "failing") "failing pill present")
      (is (str/includes? s "(deftest t-fail") "source present"))))

;; ---------------------------------------------------------------------------
;; Kind resolution end-to-end — render-entity-ai/-html route a :seon.test
;; entity through the handler via the :seon.schema decomposition row.
;; Needs a conn carrying that row, so this is the async/DB-backed test.
;; ---------------------------------------------------------------------------

(defn- with-test-kind-conn
  "Open a fresh conn, transact the entity-schema decomposition (so
   render kind-resolution has a `:seon.schema` row for `:seon.test`) +
   a small ns graph with a test attached, then run `body` (1-arg conn)
   with `db/*conn*` bound. Returns a Promise.

   NOTE: the conn is passed EXPLICITLY (`:seon.db/conn conn`) to every
   transact, and `body` resolves the db via `@conn`. A `binding
   [db/*conn* conn]` would NOT be conveyed across the `.then` async
   boundaries in CLJS — each callback runs after the binding frame has
   popped — so the seed must not rely on the dynamic `*conn*`."
  [body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (-> (db/transact! {:seon.db/conn conn
                                  :seon.db/tx-data (schema/all-entity-schemas-tx-data)})
                   (.then (fn [_]
                            (db/transact!
                              {:seon.db/conn conn
                               :seon.db/tx-data
                               [{:seon.ns/name :demo.ns
                                 :seon.ns/source "(ns demo.ns)"}
                                {:seon.test/sym "demo.ns/t-attached"
                                 :seon.test/ns [:seon.ns/name :demo.ns]
                                 :seon.test/source
                                 "(deftest t-attached (is (= 4 (+ 2 2))))"
                                 :seon.test/last-passed-at (js/Date.)}]})))
                   (.then (fn [_]
                            (binding [db/*conn* conn]
                              (body conn)))))))))

(deftest render-entity-routes-through-the-handler
  (async done
    (-> (with-test-kind-conn
          (fn [conn]
            (let [db   @conn
                  ent  {:seon.test/sym "demo.ns/t-attached"
                        :seon.test/source "(deftest t-attached (is (= 4 (+ 2 2))))"
                        :seon.test/last-passed-at (js/Date.)}
                  ai   (render/render-entity-ai
                         {:seon.db/db db :seon.render/entity ent})
                  html (render/render-entity-html
                         {:seon.db/db db :seon.render/entity ent})]
              (is (string? ai) "render-entity-ai resolved the :seon.test kind")
              (is (str/includes? ai "[test demo.ns/t-attached]") "ai shows the sym")
              (is (vector? html) "render-entity-html resolved the :seon.test kind")
              (is (= :div (first html)) "html is a card div"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ---------------------------------------------------------------------------
;; render-namespace shows a ns's tests under that ns.
;; ---------------------------------------------------------------------------

(deftest render-namespace-shows-tests-under-the-ns
  (async done
    (-> (with-test-kind-conn
          (fn [conn]
            (let [db   @conn
                  text (:seon.render/text
                         (agent/render-namespace
                           {:seon.db/db db :seon.ns/name :demo.ns
                            :seon.render/depth 0 :seon.render/format :ai}))]
              (is (str/includes? text ";; ── namespace demo.ns ──")
                  "the ns renders as a comment-shaped block header")
              (is (str/includes? text "[test demo.ns/t-attached]")
                  "the test is rendered under its ns")
              ;; AC3 (AI path): render-namespace must show the test's status
              ;; glyph, NOT just the sym+source. `t-attached` has a
              ;; :last-passed-at seeded so it must render as ✓ passing, via
              ;; the SHARED `h-test/status-line` helper (single glyph source).
              (is (str/includes? text "✓ test passing")
                  "the test's status glyph (✓) is shown in render-namespace AI")
              (is (str/includes? text "(deftest t-attached")
                  "the test source is in view"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest render-namespace-html-includes-tests
  ;; AC3 (HTML path): render-namespace :html must include the ns's tests
  ;; (sym + status pill + source), not just fns/schemas. `t-attached`
  ;; passed, so the `passing` pill must be present.
  (async done
    (-> (with-test-kind-conn
          (fn [conn]
            (let [db     @conn
                  hiccup (:seon.render/hiccup
                           (agent/render-namespace
                             {:seon.db/db db :seon.ns/name :demo.ns
                              :seon.render/depth 0 :seon.render/format :html}))
                  s      (pr-str hiccup)]
              (is (vector? hiccup) "html form is a hiccup vector")
              (is (tile/valid-hiccup? hiccup) "passes valid-hiccup?")
              (is (str/includes? s "demo.ns/t-attached")
                  "the test sym appears in the ns's html")
              (is (str/includes? s "passing")
                  "the test's passing pill is shown (shared status logic)")
              (is (str/includes? s "(deftest t-attached")
                  "the test source is in the html"))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
