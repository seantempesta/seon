(ns seon.web.datastar-test
  "Behavior + mechanism regressions for the datastar whole-view SSE streamer
   (`seon.web.datastar`) — the hyperlith `view = f(db)` view roster and the
   `datastar-patch-elements` wire framing that morphs `#app-view`.

   Style: assert MECHANISM — the structural
   SSE framing markers, presence/absence of an agent in the roster,
   pure-fn-of-db determinism, and NEVER-CRASH — via `str/includes?` /
   line-splitting. NEVER pin the exact rendered HTML or prose (these are
   refactoring surfaces). The db is a fresh ISOLATED `:memory` conn carrying
   the pod's full schema (`client/open-agent-conn!`), never the live cluster
   store — so the pod's state is irrelevant and the test is self-contained.

   The bare-agent + throwing-derive cases are the never-crash floor: the
   whole-view morph engine can never abort on one under-populated or failing
   agent. The guard is `agent-tile`'s per-tile try/catch — proven here by
   forcing `derive-state` to throw and asserting the OTHER agent + the
   degraded tile still render (the whole-view error fallback is NOT hit)."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    [seon.client :as client]
    [seon.db :as db]
    [seon.derive :as derive]
    [seon.ui.html :as html]
    [seon.web.brand :as brand]
    [seon.web.datastar :as datastar]))

;; Valid 14-char ids (`:seon.db/id` is [:string {:min 14 :max 14}]).
(def ^:private agent-a "view-aaaa00001")
(def ^:private agent-b "view-bbbb00002")

(defn- with-conn
  "Fresh isolated `:memory` conn (full pod schema). Transact each id in
   `agent-ids` as a BARE agent (only `:seon.agent/id`), then call
   `(body conn)` (→ Promise|value). Returns a Promise. No `db/*conn*`
   juggling — `roster-view` reads the explicit db value we hand it, so the
   tests stay pure functions of a db value."
  [agent-ids body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (if (seq agent-ids)
                 (-> (db/transact!
                       {:seon.db/conn    conn
                        :seon.db/tx-data (mapv (fn [id] {:seon.agent/id id}) agent-ids)})
                     (.then (fn [_] (body conn))))
                 (body conn))))))

;; ============================================================
;; 1. patch-elements — the datastar-patch-elements wire framing (pure).
;; Assert the CONTRACT structurally (event line, per-HTML-line datalines,
;; blank-line terminator), never the exact event bytes.
;; ============================================================

(deftest patch-elements-frames-a-datastar-event
  (testing "single-line HTML → one data: elements dataline, event-framed"
    (let [ev        (datastar/patch-elements "<main id=\"app-view\">solo</main>")
          datalines (->> (str/split-lines ev)
                         (filter #(str/starts-with? % "data: elements ")))]
      (is (str/starts-with? ev "event: datastar-patch-elements\n")
          "begins with the datastar-patch-elements event line")
      (is (str/ends-with? ev "\n\n")
          "a blank line terminates the event")
      (is (= 1 (count datalines))
          "a one-line view yields exactly one data: elements dataline")))
  (testing "multi-line HTML → one data: elements dataline PER HTML line, verbatim"
    (let [src       "<ul>\n<li>a</li>\n<li>b</li>\n</ul>"
          lines     (str/split-lines src)
          ev        (datastar/patch-elements src)
          datalines (->> (str/split-lines ev)
                         (filter #(str/starts-with? % "data: elements ")))]
      (is (< 1 (count datalines))
          "multi-line HTML produces multiple data: elements lines")
      (is (= (count lines) (count datalines))
          "exactly one data: elements dataline per HTML line — the framing contract")
      (is (= (mapv #(str "data: elements " %) lines) datalines)
          "each HTML line is carried verbatim behind the data: elements prefix"))))

;; ============================================================
;; 2. roster-view = f(db) — APPEAR / VANISH, empty roster, determinism.
;; Each test reads ONLY the db value it is handed; assert an agent's tile by
;; its derived id marker (`app-agent-<id>`), never the tile copy.
;; ============================================================

(deftest roster-view-roster-has-a-tile-for-every-agent
  (async done
    (-> (with-conn [agent-a agent-b]
          (fn [conn]
            (let [view (datastar/roster-view @conn)
                  s    (html/->string view)]
              (testing "the root is the #app-view morph target (datastar morphs by id)"
                (is (vector? view) "roster-view returns hiccup, not a thrown error")
                (is (= "app-view" (:id (second view)))
                    "root element carries id=view — the morph target the shim page declares"))
              (testing "every agent in the db gets a roster tile keyed by its id"
                (is (str/includes? s (str "app-agent-" agent-a)))
                (is (str/includes? s (str "app-agent-" agent-b)))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest roster-view-appears-and-vanishes-with-the-db
  (async done
    (-> (with-conn [agent-a]
          (fn [conn]
            (let [s (html/->string (datastar/roster-view @conn))]
              (testing "A present, the absent B never appears"
                (is (str/includes? s (str "app-agent-" agent-a))
                    "the agent in the db is in the roster")
                (is (not (str/includes? s agent-b))
                    "an agent NOT in the db is absent from the roster")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest roster-view-empty-db-is-a-valid-present-roster
  (async done
    (-> (with-conn []
          (fn [conn]
            (let [view (datastar/roster-view @conn)
                  s    (html/->string view)]
              (testing "an empty db still renders a valid, non-crashing roster"
                (is (vector? view) "no agents → still a hiccup view, never a throw")
                (is (seq s) "the empty roster renders a non-empty HTML string")
                (is (some? (re-find #"\d+\s+agent" s))
                    "the roster surfaces an agent count even at zero")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest roster-view-is-deterministic-over-a-db-value
  (async done
    (-> (with-conn [agent-a agent-b]
          (fn [conn]
            (let [dbv @conn]
              (testing "same db value twice → identical output (pure fn of db)"
                (is (= (html/->string (datastar/roster-view dbv))
                       (html/->string (datastar/roster-view dbv))))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; 3. NEVER-CRASH regression — the whole-view morph engine must survive an
;; under-populated or failing agent. (A bare agent does NOT currently make
;; `derive-state` throw — it derives :idle — but the streamer's per-tile
;; guard is the load-bearing floor; the second test forces the throw so the
;; regression holds regardless of which future shape breaks derive-state.)
;; ============================================================

(deftest regression-bare-agent-never-crashes-roster-view
  ;; A bare agent (only :seon.agent/id — no run, no turn) must render in the
  ;; roster. roster-view derives each tile's FSM state; this is the never-crash
  ;; floor for the whole-view morph.
  (async done
    (-> (with-conn [agent-a]
          (fn [conn]
            (let [view (try (datastar/roster-view @conn)
                            (catch :default e {:threw (str e)}))
                  s    (html/->string view)]
              (testing "a bare agent renders WITHOUT throwing"
                (is (vector? view) "roster-view returned a view, not a thrown error")
                (is (str/includes? s (str "app-agent-" agent-a))
                    "the bare agent still gets its roster tile")))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

(deftest regression-roster-view-survives-a-throwing-derive-state
  ;; The load-bearing guard: a single agent whose derived-state read throws must
  ;; NOT abort the whole-view render. `agent-tile` catches per-tile (→ degraded
  ;; state), so the roster — and every OTHER agent — still renders, and the
  ;; whole-view error fallback (`#app-error`) is NOT triggered. Force the
  ;; throw via with-redefs so the regression holds no matter what future shape
  ;; makes derive-state throw.
  (async done
    (-> (with-conn [agent-a]
          (fn [conn]
            (let [dbv @conn]
              (with-redefs [derive/derive-state
                            (fn [_ _] (throw (js/Error. "derive boom")))]
                (let [view (try (datastar/roster-view dbv)
                                (catch :default e {:propagated (str e)}))
                      s    (html/->string view)]
                  (testing "the per-tile throw is CONTAINED — roster-view never propagates it"
                    (is (vector? view) "render did not propagate the per-tile throw"))
                  (testing "the per-tile guard (not the whole-view catch) handled it"
                    (is (str/includes? s (str "app-agent-" agent-a))
                        "the agent's tile still renders despite its failed derive")
                    (is (not (str/includes? s "app-error"))
                        "whole-view error fallback NOT triggered — the per-tile guard caught it")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))

;; ============================================================
;; 4. broadcast! — zero open connections is a silent no-op.
;; The feed registry is isolated to an empty atom (with-redefs), so the live
;; pod's open feeds are untouched and the no-op never renders / reads *conn*.
;; ============================================================

(deftest broadcast-with-zero-connections-is-a-noop
  (with-redefs [datastar/!feeds (atom [])]
    (is (nil? (@#'datastar/broadcast!
                {:seon.db/db nil :seon.db/changed-attrs #{}}))
        "broadcast! over an empty feed registry is a silent no-op (no throw)")
    (is (empty? @datastar/!feeds)
        "no connection was added or mutated")))

(deftest broadcast-renders-once-per-live-view
  (let [renders (atom 0)
        pushes (atom 0)
        render-change (fn [_]
                        (swap! renders inc)
                        [[:div {:id "first-target"} "one"]
                         [:div {:id "second-target"} "two"]])
        conn {:seon.web.feed/key [:agent agent-a]
              :seon.web.feed/live? true
              :seon.web.feed/render-change render-change}]
    (with-redefs [datastar/!feeds (atom [(assoc conn :seon.web.feed/id #uuid "00000000-0000-0000-0000-000000000001")
                                         (assoc conn :seon.web.feed/id #uuid "00000000-0000-0000-0000-000000000002")])
                  datastar/push-event! (fn [_ event]
                                         (is (str/includes? event "first-target"))
                                         (is (str/includes? event "second-target"))
                                         (swap! pushes inc))]
      (@#'datastar/broadcast!
        {:seon.db/db nil :seon.db/changed-attrs #{:example/value}})
      (is (= 1 @renders) "equivalent feeds share one render")
      (is (= 2 @pushes) "the shared event reaches every equivalent feed"))))

(deftest broadcast-ignores-frozen-feeds
  (let [renders (atom 0)]
    (with-redefs [datastar/!feeds
                  (atom [{:seon.web.feed/key [:agent agent-a :as-of 1]
                          :seon.web.feed/live? false
                          :seon.web.feed/render-change
                          (fn [_] (swap! renders inc) [])}])]
      (@#'datastar/broadcast!
        {:seon.db/db nil :seon.db/changed-attrs #{:example/value}})
      (is (zero? @renders) "as-of feeds never rerender on current commits"))))

;; ============================================================
;; 5. PER-CONNECTION views — the streamer renders EACH connection's OWN
;; bound view-fn (the /view roster vs a /agent/{id} view both ride the
;; same broadcast). `view-fn-patch` is the per-conn render core: a bound
;; thunk → its own morph patch, GUARDED so one bad view can't abort the
;; broadcast. (The full gzip-stream path needs a node socket, so the
;; mechanism is proven here at the thunk level.)
;; ============================================================

;; ============================================================
;; 4b. Roster tiles carry the agent's canvas COMPACT FACE (2026-07-11):
;; each non-root tile embeds `render/render-agent-canvas`'s hiccup (the
;; agent's canvas — pinned content, else derived, else the welcome
;; card) clipped + stretch-linked to `/agent/{id}`. Root is skipped by
;; design (root's canvas is the `/` dashboard, which itself renders this
;; roster — embedding would recurse). Assert the MECHANISM (the preview
;; wrapper's stable id, presence for a bare agent via the welcome
;; fallback, absence for root) — not the rendered face.
;; ============================================================

(deftest roster-tile-carries-the-agent-canvas-compact-face
  (async done
    (-> (with-conn [agent-a "root"]
          (fn [conn]
            (let [s (html/->string (datastar/roster-view @conn))]
              (testing "both agents render a roster tile"
                (is (str/includes? s (str "app-agent-" agent-a)))
                (is (str/includes? s "app-agent-root")))
              (testing "a bare agent still gets a compact face (welcome fallback)"
                (is (str/includes? s (str "app-agent-" agent-a "-tile"))
                    "the preview wrapper renders with its stable DOM id"))
              (testing "root gets NO embedded face (its canvas is / itself)"
                (is (not (str/includes? s "app-agent-root-tile"))
                    "no preview wrapper for root")))))
        (.then done))))

;; ============================================================
;; 5b. The shim's feed OPENER lives OUTSIDE the morph target (2026-07-11
;; regression): a `data-init` ON `#app-view` is stripped by the feed's own
;; first whole-element morph (the pushed `[:main#app-view …]` carries no
;; data-init), so datastar cancels the stream ~100ms after open and the
;; roster page goes permanently dead. The opener must be a SIBLING of
;; `<main id="app-view">`.
;; ============================================================

(deftest regression-shim-feed-opener-is-outside-the-morph-target
  (async done
    (-> (client/open-agent-conn!)
        (.then
          (fn [conn]
            (let [orig db/*conn*]
              (set! db/*conn* conn)
              (let [view (@#'datastar/agents-page-html)]
                (testing "#app-view itself carries NO data-init (the morph would strip it)"
                  (is (str/includes? view "<main id=\"app-view\">")
                      "the morph target is a bare <main id=app-view>"))
                (testing "the feed opener is a sibling element carrying the data-init"
                  (is (str/includes? view "app-feed-opener")
                      "the opener div is present")
                  (is (str/includes? view "@get('/agents/feed'")
                      "the opener opens /agents/feed")))
              (set! db/*conn* orig)
              (done)))))))

;; ============================================================
;; 6. The view SHIM heads route through the seon.web.brand seams (#13) —
;; the page users actually navigate to (/agents and /agent/{id}) must carry
;; the downstream brand the same way the debug view does: SEON_BRAND_CSS
;; inlined in the <head>, the brand NAME in the <title>, and `data-theme`
;; from the brand row. Absent brand row + env → the shipped seon defaults.
;; Assert the brand MECHANISM (css present, name in title, theme attr) — not
;; the surrounding shim markup, which is a refactoring surface.
;; ============================================================

(deftest view-shim-heads-route-through-the-brand-seams
  (async done
    (let [env      (.. js/process -env)
          fs       (js/require "fs")
          css-path "tmp/datastar-brand-shim-test.css"]
      (-> (client/open-agent-conn!)
          (.then
            (fn [conn]
              (let [orig db/*conn*]
                (set! db/*conn* conn)
                ;; --- DEFAULT (unbranded): no brand row, no SEON_BRAND_CSS.
                (js-delete env "SEON_BRAND_CSS")
                (let [view (@#'datastar/agents-page-html)
                      agent (@#'datastar/agent-page-html agent-a)]
                  (testing "unbranded → seon defaults, NO brand <style> inlined"
                    (is (str/includes? view "data-theme=\"phosphor\"")
                        "the default phosphor theme rides the <html> tag")
                    (is (str/includes? view "<title>seon · agents</title>")
                        "the roster title falls back to the seon brand name")
                    (is (str/includes? agent
                                       (str "<title>seon · agent " agent-a "</title>"))
                        "the agent title falls back to the seon brand name")
                    (is (str/includes? view "/css/output.css")
                        "output.css is still linked on the default path")))
                ;; --- BRANDED: a brand row + a SEON_BRAND_CSS file (cyan token).
                (.writeFileSync fs css-path ":root{--color-amber-400:#38bdf8;}")
                (aset env "SEON_BRAND_CSS" css-path)
                (-> (db/transact!
                      {:seon.db/conn    conn
                       :seon.db/tx-data [{::brand/id    "brand"
                                          ::brand/name  "Acme"
                                          ::brand/theme "midnight"}]})
                    (.then
                      (fn [_]
                        (let [view (@#'datastar/agents-page-html)
                              agent (@#'datastar/agent-page-html agent-a)]
                          (testing "branded → SEON_BRAND_CSS inlined, brand name + theme in head"
                            (is (str/includes? view "#38bdf8")
                                "the SEON_BRAND_CSS content is inlined in the roster <head>")
                            (is (str/includes? view "Acme · agents")
                                "the brand name flows into the roster <title>")
                            (is (str/includes? view "data-theme=\"midnight\"")
                                "the brand theme rides the <html> tag")
                            (is (str/includes? agent "#38bdf8")
                                "the SEON_BRAND_CSS content is inlined in the agent <head>")
                            (is (str/includes? agent (str "Acme · agent " agent-a))
                                "the brand name flows into the agent <title>")))))
                    (.finally
                      (fn []
                        (set! db/*conn* orig)
                        (js-delete env "SEON_BRAND_CSS")
                        (try (.unlinkSync fs css-path) (catch :default _ nil))))))))
          (.then (fn [_] (done)))
          (.catch (fn [e] (is false (str "threw — " e)) (done)))))))

(deftest view-fn-patch-renders-the-bound-view-and-is-guarded
  (testing "a connection's bound view-fn is rendered into its OWN morph patch"
    (let [patch (@#'datastar/view-fn-patch
                 (fn [] [:main {:id "app-view"} [:div {:id "x"} "BOUND-VIEW"]]))]
      (is (str/starts-with? patch "event: datastar-patch-elements\n")
          "the bound view is framed as a datastar-patch-elements morph")
      (is (str/includes? patch "BOUND-VIEW")
          "the connection's OWN view content rides in its patch")))
  (testing "two connections' views differ — each renders its own bound thunk"
    (let [pa (@#'datastar/view-fn-patch (fn [] [:main {:id "app-view"} "VIEW-A"]))
          pb (@#'datastar/view-fn-patch (fn [] [:main {:id "app-view"} "VIEW-B"]))]
      (is (and (str/includes? pa "VIEW-A") (not (str/includes? pa "VIEW-B")))
          "connection A's patch carries only A's view")
      (is (and (str/includes? pb "VIEW-B") (not (str/includes? pb "VIEW-A")))
          "connection B's patch carries only B's view")))
  (testing "a throwing view-fn degrades to a #app-error morph — never throws"
    (let [patch (@#'datastar/view-fn-patch (fn [] (throw (js/Error. "view boom"))))]
      (is (str/includes? patch "app-error")
          "a per-connection render failure degrades to a visible error, not a crash"))))
