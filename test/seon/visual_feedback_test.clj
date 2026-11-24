(ns seon.visual-feedback-test
  "End-to-end proofs for unified visual defect detection, sanitizer drop reporting,
   synchronous visual audit loops (`set-canvas!`, `set-status!`, `render-preview!`, `ui-patch!`),
   loud `<live-viewport>` / `<derived-preview>` alerts, pure theme-aware `seon.render.viz`
   primitives, `seed=42` generative checks, and real WKWebView dark/light live audits."
  (:require [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [jolt.fs :as fs]
            [seon.browser :as browser]
            [seon.browser.measure :as measure]
            [seon.browser.preview :as preview]
            [seon.code :as code]
            [seon.fork.calls :as fcalls]
            [seon.fork.helpers :as helpers]
            [seon.render :as render]
            [seon.render.sanitize :as sanitize]
            [seon.render.status :as rstatus]
            [seon.render.tails :as rtails]
            [seon.render.viz :as viz]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]
            [seon.web.css :as wcss]))

(defn- throwing-canvas-fn [_db _agent]
  (throw (ex-info "intentional canvas boom" {:boom true})))

(defn- clean-canvas-fn [_db agent]
  (viz/bar-chart {:title (str "Agent " (:seon.agent/id agent))
                  :items [{:label "Pass" :value 9} {:label "Fail" :value 1}]}))

(deftest sanitize-hiccup-drop-reporting-test
  (testing "sanitize-hiccup-with-report captures forbidden tags, attrs, URLs, actions, styles, and raw maps"
    (let [r1 (sanitize/sanitize-hiccup-with-report
              [:div {:class "box" :onclick "evil()" :style "background:expression(1)"}
               [:script "alert(1)"]
               [:style "body{display:none}"]
               [:a {:href "javascript:alert(1)"} "bad-link"]
               [:img {:src "data:text/html,<h1>x</h1>"}]
               {:seon/raw "<b>raw</b>"}
               "ok"])
          r2 (sanitize/sanitize-hiccup-with-report
              [:button {:type "button" :on-click (quote (clojure.core/eval 1))} "Run"]
              {:allow-seon-actions? true})
          r3 (sanitize/sanitize-hiccup-with-report
              [:div {:class "card"} [:h2 "Clean"] [:p "Safe text"]])
          r4 (sanitize/sanitize-hiccup-with-report
              [:button {:type "button" :on-click (quote (seon.web/navigate-url! "/chat"))} "Chat"]
              {:allow-seon-actions? true})]
      (is (= [:div {:class "box"} [:a "bad-link"] [:img] "ok"] (:hiccup r1)))
      (is (some #(str/includes? % ":onclick") (:dropped r1)))
      (is (some #(str/includes? % ":style") (:dropped r1)))
      (is (some #(str/includes? % ":script") (:dropped r1)))
      (is (some #(str/includes? % ":href") (:dropped r1)))
      (is (some #(str/includes? % ":src") (:dropped r1)))
      (is (some #(str/includes? % "raw HTML/map") (:dropped r1)))
      (is (= [:button {:type "button"} "Run"] (:hiccup r2)))
      (is (some #(str/includes? % ":on-click") (:dropped r2)))
      (is (= [] (:dropped r3)))
      (is (= [:div {:class "card"} [:h2 "Clean"] [:p "Safe text"]] (:hiccup r3)))
      (is (= [] (:dropped r4)))
      (is (= (:hiccup r1) (sanitize/sanitize-hiccup
                           [:div {:class "box" :onclick "evil()" :style "background:expression(1)"}
                            [:script "alert(1)"] [:style "body{display:none}"]
                            [:a {:href "javascript:alert(1)"} "bad-link"]
                            [:img {:src "data:text/html,<h1>x</h1>"}] {:seon/raw "<b>raw</b>"} "ok"]))))))

(deftest viewport-tail-and-preview-summary-defects-test
  (testing "render-viewport-tail emits crisp 0 defects when clean and loud numbered VISUAL DEFECTS even with vision? = false"
    (let [clean-res (rtails/render-viewport-tail
                     {:seon.tail/viewport
                      {:enabled? true :vision? false
                       :snapshot {:route "/" :width 1280 :height 820
                                  :dom {:viewing ":agent/root" :mode "session" :theme "dark"
                                        :overflowX false :svgCount 2 :visual-defects []}}}})
          clean-txt (render/hiccup->text (:seon.ui/hiccup clean-res))
          bad-res (rtails/render-viewport-tail
                   {:seon.tail/viewport
                    {:enabled? true :vision? false
                     :snapshot {:route "/" :width 1280 :height 820
                                :dom {:viewing ":agent/root" :mode "session" :theme "dark"
                                      :overflowX true :svgCount 1 :clipped-count 2
                                      :clipped-selectors ["#seon-header" ".card"]
                                      :visual-defects ["SVG #1 paints bright white card in dark theme"
                                                       "Low contrast (1.3:1) on p ('dim text')"]}}}})
          bad-txt (render/hiccup->text (:seon.ui/hiccup bad-res))]
      (is (= "live-viewport" (:tag clean-res)))
      (is (nil? (:image clean-res)))
      (is (str/includes? clean-txt "0 defects"))
      (is (not (str/includes? clean-txt "VISUAL DEFECTS")))
      (is (= "live-viewport" (:tag bad-res)))
      (is (nil? (:image bad-res)))
      (is (str/includes? bad-txt "VISUAL DEFECTS (4) — FIX BEFORE ANSWERING:"))
      (is (str/includes? bad-txt "[1] SVG #1 paints bright white card in dark theme"))
      (is (str/includes? bad-txt "[2] Low contrast (1.3:1) on p ('dim text')"))
      (is (str/includes? bad-txt "[3] Horizontal document overflow"))
      (is (str/includes? bad-txt "[4] 2 clipped element(s): #seon-header, .card"))))

  (testing "format-preview-summary and aggregate-frame-defects surface clean vs defective frames"
    (let [ok-f (preview/normalize-ephemeral-frame (quote demo/ok-card) :db
                                                  {:seon.media/width 360 :seon.media/height 140 :seon.media/label "ok · 360px"
                                                   :seon.media/dom {:overflow-x? false :visual-defects []}})
          bad-f (preview/normalize-ephemeral-frame (quote demo/bad-card) :db
                                                   {:seon.media/width 360 :seon.media/height 140 :seon.media/label "bad · 360px"
                                                    :seon.media/dom {:overflow-x? true :visual-defects ["SVG #1 is empty (300x160px with 0 primitives)"]}})
          ok-s (preview/format-preview-summary [ok-f])
          bad-s (preview/format-preview-summary [bad-f])
          agg-ok (measure/aggregate-frame-defects [ok-f])
          agg-bad (measure/aggregate-frame-defects [bad-f])]
      (is (true? (:visual-ok? ok-f)))
      (is (false? (:visual-ok? bad-f)))
      (is (str/includes? ok-s "0 defects"))
      (is (str/includes? bad-s "DEFECTS(2):"))
      (is (str/includes? bad-s "SVG #1 is empty"))
      (is (= {:visual-ok? true :visual-defects []} agg-ok))
      (is (false? (:visual-ok? agg-bad)))
      (is (= ["[360px] SVG #1 is empty (300x160px with 0 primitives)" "[360px] Horizontal overflow at 360px"]
             (:visual-defects agg-bad))))))

(deftest ^{:cost/ticks 120000000} viz-primitives-and-seed42-generative-test
  (testing "seon.render.viz builders emit zero Clojure ratio literals and handle empty, single, and dense data cleanly"
    (let [bar-h (viz/bar-chart {:title "Latencies" :unit "ms" :items [{:label "A" :value 1153/12} {:label "B" :value 7/3}]})
          bar-v (viz/bar-chart {:title "Vertical" :orientation :vertical :items (mapv #(hash-map :label (str "D" %) :value (+ 5 (* % 3/2))) (range 10))})
          line-h (viz/line-chart {:title "Velocity" :series [{:name "S1" :points (mapv #(hash-map :x % :y (+ 10 (/ % 3)) :label (str "W" %)) (range 18))}]})
          line-1 (viz/line-chart {:title "Single" :series [{:name "Only" :points [{:x 1 :y 5/2 :label "Now"}]}]})
          spark-h (viz/sparkline {:values [1/2 3/2 5/4 9/4 11/3]})
          stat-h (viz/stat-grid {:title "KPIs" :items [{:label "Rate" :value 95/2 :delta "+4%" :tone :good :sparkline [1 2 3]}]})
          empty-all [:div (viz/bar-chart {:items []}) (viz/line-chart {:series []}) (viz/sparkline {:values []}) (viz/stat-grid {:items []})]
          html (str (render/hiccup->html [:div bar-h bar-v line-h line-1 spark-h stat-h]))
          empty-html (str (render/hiccup->html empty-all))]
      (is (not (re-find #"(?:cx|cy|x|y|x1|y1|x2|y2|width|height|d|points|viewBox)=\"[^\"]*\d+/\d+" html))
          "Rendered viz SVG must contain zero Clojure ratio literals")
      (is (not (str/includes? html "NaN")))
      (is (not (str/includes? html "Infinity")))
      (is (not (str/includes? empty-html "<svg")) "Empty viz states must not emit empty SVG canvases")))

  (testing "seed=42 Malli generative checks pass across viz, sanitize, measure, and tail pure functions"
    (with-test-store [{:keys [conn]}]
      (let [db @conn]
        (doseq [sym (quote [seon.render.viz/bar-chart
                            seon.render.viz/line-chart
                            seon.render.viz/sparkline
                            seon.render.viz/stat-grid
                            seon.render.sanitize/sanitize-svg
                            seon.render.sanitize/svg-static-defects
                            seon.browser.measure/aggregate-frame-defects
                            seon.render.tails/render-viewport-tail])]
          (let [r (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (true? (:ok? r)) (str "seed=42 stress failed for " sym ": " (pr-str r)))))))))

(deftest ^{:cost/ticks 120000000} wkwebview-live-visual-audit-negative-and-positive-test
  (when-let [bin (browser/default-binary-path)]
    (let [tmp (fs/create-temp-dir {:prefix "seon-visual-audit-"})
          host-file (str (fs/path tmp "host.html"))
          host-url (str "file://" host-file)]
      (try
        (spit host-file (str "<!doctype html><html><head><meta charset=\"utf-8\"><style>"
                             wcss/app-css
                             "</style></head><body><div id=\"seon-app\" data-theme=\"dark\"><div>a</div><div>b</div></div></body></html>"))
        (testing "Negative WKWebView audit catches white dark-mode card, ratio attr, collapsed circles, overlapping SVG text, and low contrast"
          (let [bad-html (str "<div class=\"card\" style=\"background:#ffffff;color:#111;width:320px;height:80px\">"
                              "<p style=\"color:#1f1e1d;background:#1a1918\">Unreadable dark text</p>"
                              "<svg viewBox=\"0 0 300 160\" width=\"300\" height=\"160\">"
                              "<rect x=\"0\" y=\"0\" width=\"300\" height=\"160\" fill=\"#ffffff\"/>"
                              "<circle cx=\"1153/12\" cy=\"40\" r=\"5\" fill=\"#f0b429\"/>"
                              "<circle cx=\"0\" cy=\"60\" r=\"5\" fill=\"#f0b429\"/>"
                              "<circle cx=\"1\" cy=\"80\" r=\"5\" fill=\"#f0b429\"/>"
                              "<text x=\"50\" y=\"50\" fill=\"#111\" font-size=\"14\">Label A</text>"
                              "<text x=\"52\" y=\"51\" fill=\"#111\" font-size=\"14\">Label B</text>"
                              "</svg></div>")
                res (browser/render-hiccup-frames! host-url bad-html {:widths [360] :theme :dark :binary-path bin})
                dom (:seon.media/dom (first (:frames res)))
                defs (:visual-defects dom)]
            (is (true? (:ok? res)))
            (is (false? (:visual-ok? dom)))
            (is (some #(str/includes? % "bright white card") defs))
            (is (some #(str/includes? % "collapsed on left edge") defs))
            (is (some #(str/includes? % "overlapping <text>") defs))
            (is (some #(str/includes? % "Low contrast") defs))
            (is (some #(str/includes? % "invalid numeric") defs))))

        (testing "Positive WKWebView audit passes ALL 4 seon.render.viz primitives cleanly at [360 960] in BOTH :dark and :light themes"
          (let [viz-html (str (render/hiccup->html
                               [:div {:class "stack"}
                                (viz/bar-chart {:title "Timings" :subtitle "ms" :unit "ms"
                                                :items [{:label "Parse" :value 1153/12} {:label "Render" :value 42.5} {:label "Audit" :value 18}]})
                                (viz/line-chart {:title "Trend" :unit "t"
                                                 :series [{:name "Turns" :points (mapv #(hash-map :x (inc %) :y (+ 8 (* % 2)) :label (str "W" (inc %))) (range 8))}]})
                                [:div {:class "card"} (viz/sparkline {:values [10 14 12 18 22 25] :label "Velocity"})]
                                (viz/stat-grid {:title "KPIs"
                                                :items [{:label "Auto-Merge" :value "9/9" :delta "+100%" :tone :good :sparkline [4 6 8 9]}
                                                        {:label "Avg Turns" :value 13/2 :delta "-22%" :tone :info}]})]))
                r-dark (browser/render-hiccup-frames! host-url viz-html {:widths [360 960] :theme :dark :binary-path bin})
                r-light (browser/render-hiccup-frames! host-url viz-html {:widths [360 960] :theme :light :binary-path bin})
                agg-d (measure/aggregate-frame-defects (:frames r-dark))
                agg-l (measure/aggregate-frame-defects (:frames r-light))]
            (is (true? (:ok? r-dark)))
            (is (true? (:ok? r-light)))
            (is (= {:visual-ok? true :visual-defects []} agg-d))
            (is (= {:visual-ok? true :visual-defects []} agg-l))))
        (finally
          (store/rm-rf! tmp))))))

(deftest ^{:cost/ticks 120000000} synchronous-canvas-status-and-preview-audit-integration-test
  (with-test-store [{:keys [conn]}]
    (d/transact conn {:tx-data [{:seon.agent/id :agent/viz-test :seon.agent/branch :main :seon.agent/label "VizTester"}
                                {:seon.task/id :task/viz-prev :seon.task/title "Viz Preview" :seon.task/done? false}]})
    (let [ok-render (fn [_ {:keys [widths]}]
                      {:ok? true :widths widths
                       :frames (mapv #(hash-map :seon.media/width % :seon.media/height 120 :seon.media/base64 "AA=="
                                                :seon.media/label (str % "px")
                                                :seon.media/dom {:w % :h 120 :overflow-x? false :visual-defects [] :visual-ok? true}) widths)})
          bad-render (fn [_ {:keys [widths]}]
                       {:ok? true :widths widths
                        :frames (mapv #(hash-map :seon.media/width % :seon.media/height 120 :seon.media/base64 "AA=="
                                                 :seon.media/label (str % "px")
                                                 :seon.media/dom {:w % :h 120 :overflow-x? false :visual-defects ["SVG #1 paints bright white card in dark theme"] :visual-ok? false}) widths)})]
      (binding [fcalls/*agent-ctx* {:conn conn :agent-id :agent/viz-test :branch :main}]
        (testing "set-status! immediately flags broken/script/ratio SVGs and passes clean SVG"
          (let [r-bad (rstatus/set-status! {:label "Bad" :svg "<svg viewBox=\"0 0 100 40\"><script>x()</script><circle cx=\"10/3\" cy=\"20\" r=\"5\"/></svg>" :render-fn ok-render})
                r-rej (rstatus/set-status! {:label "Rej" :svg "<div>not svg</div>" :render-fn ok-render})
                r-ok (rstatus/set-status! {:label "Good" :svg "<svg viewBox=\"0 0 100 40\"><rect x=\"12\" y=\"10\" width=\"60\" height=\"16\" fill=\"#f0b429\"/></svg>" :render-fn ok-render})]
            (is (false? (:visual-ok? r-bad)))
            (is (true? (:svg-normalized? r-bad)))
            (is (some #(str/includes? % "<script>") (:visual-defects r-bad)))
            (is (some #(str/includes? % "ratio") (:visual-defects r-bad)))
            (is (false? (:visual-ok? r-rej)))
            (is (some #(str/includes? % "rejected") (:visual-defects r-rej)))
            (is (true? (:visual-ok? r-ok)))
            (is (false? (:svg-normalized? r-ok)))
            (is (= [] (:visual-defects r-ok)))))

        (testing "set-canvas! and audit-canvas-entry! immediately catch dropped <script>, throwing fn, and visual defects"
          (let [c-drop (fcalls/set-canvas! [:div {:onclick "evil()"} [:script "x"] "hi"] {:render-fn ok-render})
                c-boom (fcalls/set-canvas! (quote seon.visual-feedback-test/throwing-canvas-fn) {:render-fn ok-render})
                c-def (fcalls/set-canvas! [:div "looks white"] {:render-fn bad-render})
                c-ok (fcalls/set-canvas! (quote seon.visual-feedback-test/clean-canvas-fn) {:render-fn ok-render})]
            (is (false? (:visual-ok? c-drop)))
            (is (some #(str/includes? % ":script") (:dropped c-drop)))
            (is (false? (:visual-ok? c-boom)))
            (is (str/includes? (str (:eval-error c-boom)) "intentional canvas boom"))
            (is (false? (:visual-ok? c-def)))
            (is (some #(str/includes? % "bright white card") (:visual-defects c-def)))
            (is (true? (:visual-ok? c-ok)))
            (is (= [] (:dropped c-ok)))
            (is (= [] (:visual-defects c-ok)))))

        (testing "render-preview! and ui-patch! surface :visual-ok? and :visual-defects"
          (let [rp-bad (helpers/render-preview! [:div "x"] {:widths [360] :render-fn bad-render})
                rp-ok (helpers/render-preview! [:div "x"] {:widths [360] :render-fn ok-render})
                demo-ns (quote seon.demo.viz-patch)
                demo-var (quote seon.demo.viz-patch/render-demo)]
            (is (false? (:visual-ok? rp-bad)))
            (is (seq (:visual-defects rp-bad)))
            (is (true? (:visual-ok? rp-ok)))
            (is (= [] (:visual-defects rp-ok)))
            (try
              (create-ns demo-ns)
              (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/viz_patch.clj"
                                                              (str "(ns seon.demo.viz-patch)\n"
                                                                   "(defn render-demo {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [{t :seon.task/entity}] {:seon.ui/hiccup [:div \"Old\" (:seon.task/title t)]})\n")
                                                              false)})
              (let [up (helpers/ui-patch! demo-var "\"Old\"" "\"New\"" {:render-fn bad-render :widths [360]})]
                (is (true? (:ok? up)))
                (is (false? (:visual-ok? up)))
                (is (some #(str/includes? % "bright white card") (:visual-defects up))))
              (finally
                (remove-ns demo-ns)))))))))
