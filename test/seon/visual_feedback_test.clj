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
            [seon.render.viz :as viz]
            [seon.schema :as schema]
            [seon.store :as store]
            [seon.test-fixture :refer [with-test-store]]
            [seon.web.css :as wcss]
            [clojure.core.async :as a]
            [seon.agent :as agent]
            [seon.code.graph :as graph]
            [seon.render.fit :as fit]
            [seon.render.wire :as rwire]))

(defn- clean-canvas-fn [_db agent]
  (viz/bar-chart {:title (str "Agent " (:seon.agent/id agent))
                  :items [{:label "Pass" :value 9} {:label "Fail" :value 1}]}))

(deftest sanitize-hiccup-drop-reporting-test
  (testing "sanitize-hiccup-with-report captures forbidden tags, attrs, URLs, actions, styles, raw maps, and sanitize-svg normalizes ratios"
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
              {:allow-seon-actions? true})
          san-svg (sanitize/sanitize-svg
                   "<svg viewBox=\"0 0 100 40\"><script>x()</script><circle onclick=\"evil()\" cx=\"10/3\" cy=\"20\" r=\"5\"/></svg>")]
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
                            [:img {:src "data:text/html,<h1>x</h1>"}] {:seon/raw "<b>raw</b>"} "ok"])))
      (is (string? san-svg))
      (is (not (str/includes? san-svg "<script")))
      (is (not (str/includes? san-svg "onclick")))
      (is (str/includes? san-svg "cx=\"3.33\""))
      (is (nil? (sanitize/sanitize-svg "<div>not-svg</div>"))))))

(deftest viewport-tail-and-preview-summary-defects-test
  (testing "wire live-viewport block emits crisp factual DOM metadata and attaches real :image when vision? is true"
  (with-test-store [{:keys [conn]}]
    (d/transact conn {:tx-data [{:seon.agent/id :agent/vf-vp :seon.agent/branch :main}]})
    (let [db @conn
          snap {:route "/" :width 1280 :height 820 :mime "image/jpeg" :base64 "IMGDATA"
                :dom {:viewing ":agent/root" :mode "canvas" :theme "dark" :overflowX false :svgCount 2}}
          vis-w (rwire/build-live-wire-request db db :agent/vf-vp {:viewport-screenshot snap :vision? true :force-vision? true})
          txt-w (rwire/build-live-wire-request db db :agent/vf-vp {:viewport-screenshot snap :vision? false :force-vision? true})
          vis-res (first (filter #(= "live-viewport" (:tag %)) (:tail-blocks vis-w)))
          txt-res (first (filter #(= "live-viewport" (:tag %)) (:tail-blocks txt-w)))
          vis-txt (:text vis-res)]
      (is (= "live-viewport" (:tag vis-res)))
      (is (= {:mime "image/jpeg" :base64 "IMGDATA" :raw snap} (:image vis-res)))
      (is (nil? (:image txt-res)))
      (is (str/includes? vis-txt ":viewing \":agent/root\""))
      (is (str/includes? vis-txt ":mode \"canvas\""))
      (is (str/includes? vis-txt ":theme \"dark\""))
      (is (str/includes? vis-txt ":overflowX false"))
      (is (str/includes? vis-txt ":svgCount 2"))
      (is (not (str/includes? vis-txt "VISUAL DEFECTS"))))))

  (testing "format-preview-summary coaches missing :examples, single-large-renderer nudge, and multi-footprint siblings"
    (let [f-single (preview/normalize-ephemeral-frame
                    (quote demo/big-card) :generated
                    {:seon.media/width 360 :seon.media/height 180 :seon.media/label "big-card · 360px"
                     :seon.media/dom {:w 360 :h 180 :overflow-x? false}}
                    {:tokens 142 :has-examples? false :input-schemas [:demo/item]
                     :siblings [{:var (quote demo/big-card) :tokens 142}]})
          f-single-wide (preview/normalize-ephemeral-frame
                         (quote demo/big-card) :generated
                         {:seon.media/width 640 :seon.media/height 160 :seon.media/label "big-card · 640px"
                          :seon.media/dom {:w 640 :h 160 :overflow-x? false}}
                         {:tokens 142 :has-examples? false :input-schemas [:demo/item]
                          :siblings [{:var (quote demo/big-card) :tokens 142}]})
          s1 (preview/format-preview-summary [f-single f-single-wide])
          f-multi (preview/normalize-ephemeral-frame
                   (quote demo/card) :db
                   {:seon.media/width 360 :seon.media/height 110 :seon.media/label "card · 360px"
                    :seon.media/dom {:w 360 :h 110 :overflow-x? false}}
                   {:tokens 74 :has-examples? true :input-schemas [:demo/item]
                    :siblings [{:var (quote demo/full) :tokens 210}
                               {:var (quote demo/chip) :tokens 18}
                               {:var (quote demo/card) :tokens 74}]})
          s2 (preview/format-preview-summary [f-multi])]
      (is (not (contains? f-single :visual-defects)))
      (is (not (contains? f-single :visual-ok?)))
      (is (str/includes? s1 "Rendered edited `:seon.ui/hiccup` component(s) in WKWebView"))
      (is (str/includes? s1 "- demo/big-card (input=generated, 142 tok) [big-card · 360px]: 360×180px · overflowX=false"))
      (is (= 1 (count (re-seq #"missing `:examples`" s1))) "Missing :examples note emitted once per var")
      (is (str/includes? s1 "Footprints for `:demo/item`: 1 renderer (`demo/big-card` 142 tok). Consider adding a compact variation"))
      (is (not (str/includes? s2 "missing `:examples`")))
      (is (str/includes? s2 "Footprints for `:demo/item`: demo/chip (18 tok), demo/card (74 tok), demo/full (210 tok).")))))

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

  (testing "seed=42 Malli generative checks pass across viz, sanitize, measure, preview, and tail pure functions"
    (with-test-store [{:keys [conn]}]
      (let [db @conn]
        (doseq [sym (quote [seon.render.viz/bar-chart
                            seon.render.viz/line-chart
                            seon.render.viz/sparkline
                            seon.render.viz/stat-grid
                            seon.render.sanitize/sanitize-svg
                            seon.browser.measure/normalize-snippet-measurement
                            seon.browser.measure/analyze-pixel-fit
                            seon.browser.preview/normalize-ephemeral-frame
                            seon.browser.preview/format-preview-summary
                            seon.render.entities.infra/render-message-card])]
          (let [r (schema/stress-var db sym {:samples 100 :seed 42})]
            (is (true? (:ok? r)) (str "seed=42 stress failed for " sym ": " (pr-str r)))))))))

(deftest ^{:cost/ticks 120000000} wkwebview-live-visual-audit-negative-and-positive-test
  (when-let [bin (browser/default-binary-path)]
    (let [tmp (fs/create-temp-dir {:prefix "seon-visual-measure-"})
          host-file (str (fs/path tmp "host.html"))
          host-url (str "file://" host-file)]
      (try
        (spit host-file (str "<!doctype html><html><head><meta charset=\"utf-8\"><style>"
                             wcss/app-css
                             "</style></head><body><div id=\"seon-app\" data-theme=\"dark\"><div>a</div><div>b</div></div></body></html>"))
        (testing "Real WKWebView measures ALL 4 seon.render.viz primitives cleanly at [360 960] in :dark and :light themes"
          (let [viz-html (str (render/hiccup->html
                               [:div {:class "stack"}
                                (viz/bar-chart {:title "Timings" :subtitle "ms" :unit "ms"
                                                :items [{:label "Parse" :value 1153/12} {:label "Render" :value 42.5} {:label "Measure" :value 18}]})
                                (viz/line-chart {:title "Trend" :unit "t"
                                                 :series [{:name "Turns" :points (mapv #(hash-map :x (inc %) :y (+ 8 (* % 2)) :label (str "W" (inc %))) (range 8))}]})
                                [:div {:class "card"} (viz/sparkline {:values [10 14 12 18 22 25] :label "Velocity"})]
                                (viz/stat-grid {:title "KPIs"
                                                :items [{:label "Auto-Merge" :value "9/9" :delta "+100%" :tone :good :sparkline [4 6 8 9]}
                                                        {:label "Avg Turns" :value 13/2 :delta "-22%" :tone :info}]})]))
                r-dark (browser/render-hiccup-frames! host-url viz-html {:widths [360 960] :theme :dark :binary-path bin})
                r-light (browser/render-hiccup-frames! host-url viz-html {:widths [360 960] :theme :light :binary-path bin})]
            (is (true? (:ok? r-dark)))
            (is (true? (:ok? r-light)))
            (doseq [f (concat (:frames r-dark) (:frames r-light))
                    :let [dom (:seon.media/dom f)]]
              (is (pos? (long (:w dom 0))) (str "Expected w > 0 in " (pr-str dom)))
              (is (pos? (long (:h dom 0))) (str "Expected h > 0 in " (pr-str dom)))
              (is (false? (:overflow-x? dom)) (str "Expected overflow-x? false in " (pr-str dom))))))

        (testing "measure-schema-renderers! measures all variations in WKWebView and populates fit/cached-browser-box"
          (with-test-store [{:keys [conn]}]
            (preview/clear-preview-cache!)
            (fit/clear-browser-box-cache!)
            (let [src (str "(ns seon.render.entities)\n"
                           "(defn render-task-chip {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] (seon.render.entities/render-task-chip m))\n"
                           "(defn render-task-card {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [m] (seon.render.entities/render-task-card m))\n")]
              (d/transact conn {:tx-data (concat [{:seon.task/id :task/wk-box :seon.task/title "Measure Task" :seon.task/done? false}]
                                                 (code/file-import-tx @conn "src/seon/render/entities.clj" src false))})
              (d/transact conn {:tx-data (graph/backfill-codebase-analysis-tx @conn {:analyze-compiler? false})})
              (let [db @conn
                    res (preview/measure-schema-renderers! host-url db :seon.task/entity {:widths [360 640] :binary-path bin})]
                (is (true? (:ok? res)))
                (is (= :seon.task/entity (:schema res)))
                (is (= :db (:source res)))
                (is (= 2 (count (:renderers res))))
                (doseq [{:keys [var boxes]} (:renderers res)
                        :let [fh (:seon.code.form/hash (d/pull db (quote [:seon.code.form/hash]) [:seon.code.form/key (str var)]))
                              b360 (fit/cached-browser-box var fh 360)
                              b640 (fit/cached-browser-box var fh 640)]]
                  (is (= 2 (count boxes)))
                  (is (every? #(and (pos? (:w %)) (pos? (:h %))) boxes))
                  (is (and (map? b360) (pos? (:w b360)) (pos? (:h b360))) (str "Missing cached 360px box for " var))
                  (is (and (map? b640) (pos? (:w b640)) (pos? (:h b640))) (str "Missing cached 640px box for " var)))))))
        (finally
          (store/rm-rf! tmp))))))

(deftest ^{:cost/ticks 120000000} synchronous-canvas-status-and-preview-audit-integration-test
  (with-test-store [{:keys [conn cfg]}]
    (preview/clear-preview-cache!)
    (fit/clear-browser-box-cache!)
    (d/transact conn {:tx-data [{:seon.config/key :seon.config/root :seon.config/ai-model "deepseek-flash" :seon.config/viewport-screenshot? true}
                                {:seon.agent/id :agent/viz-test :seon.agent/branch :main :seon.agent/label "VizTester"}
                                {:seon.agent/id :agent/worker-canvas :seon.agent/branch :branch/worker-canvas :seon.agent/label "CanvasWorker"}
                                {:seon.agent/id :agent/worker-status :seon.agent/branch :branch/worker-status :seon.agent/label "StatusWorker"}
                                {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/viz-test] :seon.user/viewport-w 1280 :seon.user/viewport-h 820}
                                {:seon.task/id :task/viz-prev :seon.task/title "Viz Preview" :seon.task/done? false}]})
    (let [ok-render (fn [_ {:keys [widths]}]
                      {:ok? true :widths widths
                       :frames (mapv #(hash-map :seon.media/width % :seon.media/height 120 :seon.media/base64 "AA=="
                                                :seon.media/label (str % "px")
                                                :seon.media/dom {:slot-w % :w % :h 120 :overflow-x? false}) widths)})]
      (binding [fcalls/*agent-ctx* {:conn conn :agent-id :agent/viz-test :branch :main}]
        (testing "set-status! sanitizes SVG and returns clean factual map without heuristic defect keys"
          (let [r-san (rstatus/set-status! {:label "San" :svg "<svg viewBox=\"0 0 100 40\"><script>x()</script><circle cx=\"10/3\" cy=\"20\" r=\"5\"/></svg>"})
                r-rej (rstatus/set-status! {:label "Rej" :svg "<div>not svg</div>"})
                r-ok (rstatus/set-status! {:label "Good" :svg "<svg viewBox=\"0 0 100 40\"><rect x=\"12\" y=\"10\" width=\"60\" height=\"16\" fill=\"#f0b429\"/></svg>"})]
            (doseq [r [r-san r-rej r-ok]]
              (is (true? (:ok? r)))
              (is (not (contains? r :visual-ok?)))
              (is (not (contains? r :visual-defects))))
            (is (not (str/includes? (str (get-in r-san [:status :seon.ui.status/svg])) "<script")))
            (is (str/includes? (str (get-in r-san [:status :seon.ui.status/svg])) "3.33"))
            (is (nil? (get-in r-rej [:status :seon.ui.status/svg])))))

        (testing "set-canvas! returns fast factual drop/warning feedback with zero browser calls"
          (let [c-drop (fcalls/set-canvas! [:div {:onclick "evil()"} [:script "x"] "hi"])
                c-unres (fcalls/set-canvas! (quote seon.nonexistent.ns/missing-canvas-fn))
                c-clean (fcalls/set-canvas! [:div {:class "card"} "Clean"])
                c-fn (fcalls/set-canvas! (quote seon.visual-feedback-test/clean-canvas-fn))]
            (doseq [c [c-drop c-unres c-clean c-fn]]
              (is (true? (:ok? c)))
              (is (not (contains? c :visual-ok?)))
              (is (not (contains? c :visual-defects))))
            (is (some #(str/includes? % ":script") (:dropped c-drop)))
            (is (= "Renderer var not resolved yet" (:warning c-unres)))
            (is (not (contains? c-clean :dropped)))
            (is (not (contains? c-fn :warning)))))

        (testing "render-preview!, ui-patch!, and measure-renderers! return clean factual authoring maps"
          (let [rp (helpers/render-preview! [:div "x"] {:widths [360] :render-fn ok-render})
                demo-ns (quote seon.demo.viz-patch)
                demo-var (quote seon.demo.viz-patch/render-demo)]
            (is (true? (:ok? rp)))
            (is (not (contains? rp :visual-ok?)))
            (is (not (contains? rp :visual-defects)))
            (try
              (create-ns demo-ns)
              (d/transact conn {:tx-data (code/file-import-tx @conn "src/seon/demo/viz_patch.clj"
                                                              (str "(ns seon.demo.viz-patch)\n"
                                                                   "(defn render-demo {:malli/schema [:=> [:cat [:map [:seon.task/entity :seon.task/entity]]] [:map [:seon.ui/hiccup :seon.ui/hiccup]]]} [{t :seon.task/entity}] {:seon.ui/hiccup [:div \"Old\" (:seon.task/title t)]})\n")
                                                              false)})
              (let [up (helpers/ui-patch! demo-var "\"Old\"" "\"New\"" {:render-fn ok-render :widths [360]})]
                (is (true? (:ok? up)))
                (is (false? (get-in up [:preview :has-examples?])))
                (is (pos? (long (get-in up [:preview :tokens] 0))))
                (is (not (contains? up :visual-ok?)))
                (is (not (contains? up :visual-defects))))
              (finally
                (remove-ns demo-ns))))))

      (testing "seon.agent dynamic vision enables image_url for deepseek-flash and captures viewport on Canvas & Status edits"
        (store/branch! conn :main :branch/worker-canvas)
        (store/branch! conn :main :branch/worker-status)
        (let [bconn1 (store/connect cfg :branch/worker-canvas)
              bconn2 (store/connect cfg :branch/worker-status)]
          (try
            (d/transact bconn1 {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/worker-canvas
                                           :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
                                           :seon.agent.eval/code "(user/set-canvas! [:div \"Live Chart\"])"}]})
            (d/transact bconn2 {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/worker-status
                                           :seon.agent.eval/turn 1 :seon.agent.eval/call-index 0
                                           :seon.agent.eval/code "(user/set-status! {:label \"Building\" :pct 50})"}]})
            (finally (d/release bconn1) (d/release bconn2)))
          (let [st0 (agent/agents-proc {:conn conn :cfg cfg :fork-out (a/chan 4) :fork-agent-in (a/chan 4)
                                        :max-os-procs 2 :browser-enabled? true :vision? false})
                [st1 out1] (agent/agents-proc st0 :fork-ev {:ev :child-frame :agent-id :agent/worker-canvas :pid 11
                                                            :frame {:ev :model-request :req-id "c-1" :branch :branch/worker-canvas}})
                [_ out2] (agent/agents-proc st0 :fork-ev {:ev :child-frame :agent-id :agent/worker-status :pid 12
                                                          :frame {:ev :model-request :req-id "s-1" :branch :branch/worker-status}})
                [_ fin1] (agent/agents-proc st1 :browser-res {:req-id "c-1" :agent-id :agent/worker-canvas
                                                              :snapshot {:route "/" :width 1280 :height 820 :mime "image/jpeg" :base64 "FLASHIMG" :dom {:theme "dark"}}})
                tail-parts (:content (peek (get-in fin1 [:request 0 :wire-messages])))]
            (is (some #(= :snapshot (:op %)) (:browser-req out1)) "Canvas paint must trigger viewport snapshot")
            (is (some #(= :snapshot (:op %)) (:browser-req out2)) "Status update must trigger viewport snapshot")
            (is (vector? tail-parts) "Dynamic vision for deepseek-flash must attach multimodal content parts even when :vision? st is false")
            (is (some #(str/includes? (str (get-in % [:image_url :url])) "FLASHIMG") tail-parts))))))))