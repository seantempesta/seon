(ns seon.web.tile-test
  "Behavior regressions for two surfaces shipped + live-proven this session:

   1. `view->hiccup` — the console-serialization normalizer that extracts a
      tile fn's bare hiccup from its html-response map.
   2. the VALUE-EXPLORER renderer (`value-node` → `value-row` → leaves /
      containers / pruned-markers) over R's `seon.render.value` `:tree` data
      contract — the collapsible drill-down browser of the agent's latest eval
      value. The load-bearing invariant: a real `render-html-data` `:tree`
      renders to collapsible HTML with NO raw marker keyword leaking into the
      output (`:seon.render.value/*` in the HTML was a real bug class).
   3. the prebuilt core views honor the html-response contract
      ({:seon.render/hiccup .. :seon.render/ai ..}) — what makes them nameable
      on a live tile AND gives the agent a text twin.

   Style: assert MECHANISM (structural markers, presence/absence) via
   `str/includes?` — never pin exact rendered strings (these are refactoring
   surfaces). The private fns under test are reached through their vars; the
   `:tree` inputs are PRODUCED by R's real sampler, never hand-built."
  (:require
    [cljs.test :refer [deftest is testing async]]
    [clojure.string :as str]
    ;; required for schema registration on the isolated conn (test #3)
    [seon.agent]
    [seon.client :as client]
    [seon.db :as db]
    [seon.render.value :as rv]
    [seon.schema :as schema]
    [seon.ui.html :as html]
    [seon.web.tile]))

;; ============================================================
;; 1. view->hiccup — the console-serialization normalizer (pure).
;; The canonical tile-fn return is the html-response map; a bare hiccup
;; vector is also accepted; anything else passes through verbatim.
;; ============================================================

(deftest view->hiccup-normalizes-tile-return
  (testing "extracts the hiccup from an html-response map"
    (is (= [:div "x"]
           (@#'seon.web.tile/view->hiccup {:seon.render/hiccup [:div "x"]}))))
  (testing "a bare hiccup vector passes through"
    (is (= [:div "x"] (@#'seon.web.tile/view->hiccup [:div "x"]))))
  (testing "a plain map WITHOUT :seon.render/hiccup passes through unchanged"
    (is (= {:seon.foo/bar 1} (@#'seon.web.tile/view->hiccup {:seon.foo/bar 1})))))

;; ============================================================
;; 2. value-node — render R's `:tree` skeleton to collapsible HTML (pure).
;; A rich nested value exercises every marker class: deep nesting (prune),
;; a long string (clip), a wide vector (elide), and plain containers
;; (collapse). The `:tree` is PRODUCED by the real sampler so we test the
;; renderer against R's actual shapes, not a pinned hand-built marker map.
;; ============================================================

(deftest value-node-renders-tree-to-collapsible-html
  (let [v    {:user   {:name  "Sean"
                       :roles [:a :b]
                       :bio   (apply str (repeat 200 "x"))}
              :items  (vec (range 50))
              :nested {:a {:b {:c {:d 1}}}}
              :tags   #{:x :y :z}}
        tree (:seon.render.value/tree (rv/render-html-data "T" v))
        h    (@#'seon.web.tile/value-node tree 0)
        s    (html/->string h)]
    (is (string? s))
    (is (seq s) "the rich value renders a non-empty HTML string")
    (is (str/includes? s "<details")
        "containers collapse into native <details> disclosure")
    (is (str/includes? s "chars")
        "the 200-char :bio renders as a clipped-string marker (·N chars)")
    (is (str/includes? s "deeper")
        "the depth-pruned :nested subtree renders the passive 'deeper' hint")
    (is (str/includes? s "more")
        "the 50-item vector renders its elided tail (… +N more)")
    (is (not (str/includes? s ":seon.render.value/"))
        "no raw marker keyword leaks into the HTML — the bug class this guards"))
  (testing "a scalar tree renders a non-empty string without throwing"
    (let [tree (:seon.render.value/tree (rv/render-html-data "T" 42))]
      (is (seq (html/->string (@#'seon.web.tile/value-node tree 0)))))))

;; ============================================================
;; 3. prebuilt core views honor the html-response contract.
;; A fresh ISOLATED :memory conn (never the live pod), one agent row seeded
;; via the seon.db sole API — the same lightweight `with-agent-conn` idiom
;; seon.render.live-tile-test uses. db/*conn* is held for the promise chain
;; and restored in .finally. Every parameterless core view (hero excluded —
;; it needs render-agent-tile wiring) must return a map carrying
;; :seon.render/hiccup AND a non-empty :seon.render/ai twin.
;; ============================================================

(defn- with-agent-conn
  "Fresh isolated conn + one agent row, db/*conn* held across the promise
   chain (restored in .finally — `binding` unwinds before an async body's
   awaits run). `body` is (fn [conn] → Promise|value). Returns a Promise."
  [agent-id body]
  (-> (client/open-agent-conn!)
      (.then (fn [conn]
               (let [orig db/*conn*]
                 (set! db/*conn* conn)
                 (-> (db/transact!
                       {:seon.db/tx-data
                        (into (vec (schema/entity-schema-tx-data :seon.agent))
                              [{:seon.agent/id agent-id}])})
                     (.then (fn [_] (body conn)))
                     (.finally (fn [] (set! db/*conn* orig)))))))))

(deftest prebuilt-views-honor-html-response-contract
  (async done
    (-> (with-agent-conn "tiletest-000001"
          (fn [conn]
            (let [input      {:seon.db/db @conn :seon.agent/id "tiletest-000001"}
                  core-views @#'seon.web.tile/core-views]
              (doseq [sym '[seon.web.tile/status-view
                            seon.web.tile/todos-view
                            seon.web.tile/progress-view
                            seon.web.tile/toolkit-view
                            seon.web.tile/context-view
                            seon.web.tile/narration-view
                            seon.web.tile/value-explorer-view
                            seon.web.tile/commentary-view]]
                (let [r ((get core-views sym) input)]
                  (is (and (map? r) (contains? r :seon.render/hiccup))
                      (str sym " returns an html-response map with :seon.render/hiccup"))
                  (is (and (string? (:seon.render/ai r)) (seq (:seon.render/ai r)))
                      (str sym " carries a non-empty :seon.render/ai twin")))))))
        (.then (fn [_] (done)))
        (.catch (fn [e] (is false (str "threw — " e)) (done))))))
