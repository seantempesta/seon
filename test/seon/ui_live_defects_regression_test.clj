(ns seon.ui-live-defects-regression-test
  "Fast pure unit, SSR, latency, and seed=42 regression tests for UI defects:
   1. Responsive `@container` header chrome without server-side `.is-narrow` or stale `:viewport-w` drift.
   2. Dev Mode toggle, centralized `reads-main?` candidate gathering, and `/system` render latency (< 60 ms warm, < 150 ms cold).
   3. Clean SVG sanitization preserving valid author colors, allowing `:on-click` actions, and read-only hiccup fallback."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.code :as code]
            [seon.ctx :as ctx]
            [seon.render :as render]
            [seon.render.markdown :as rmd]
            [seon.render.sanitize :as sanitize]
            [seon.render.surface :as surface]
            [seon.render.ui :as ui]
            [seon.schema :as schema]
            [seon.signals :as sig]
            [seon.store :as store]
            [seon.test-fixture :as tf]
            [seon.web :as web]
            [seon.web.router :as router]
            [seon.web.styles :as styles]))

(def ^:private valid-svg-hiccup-md
  "```hiccup
[:svg {:viewBox \"0 0 340 210\" :width \"100%\" :xmlns \"http://www.w3.org/2000/svg\"}
 [:rect {:width 340 :height 210 :fill \"#f8fafc\" :rx 6}]
 [:text {:x 8 :y 20 :font-size 12 :font-weight \"700\" :fill \"#0f172a\"} \"Cached vs ran tests\"]
 [:rect {:x 8 :y 36 :width 160 :height 14 :rx 2 :fill \"#3b82f6\"}]
 [:text {:x 8 :y 72 :font-size 11 :fill \"#475569\"} \"branch summary\"]]
```")

(def ^:private uneval-for-hiccup-md
  "```hiccup
[:svg {:viewBox \"0 0 340 210\" :width \"100%\"}
 [:rect {:width 340 :height 210 :fill \"#f8fafc\" :rx 6}]
 (for [[i [label c r]] (map-indexed vector [[\"main\" 869 2]])]
   (let [y (+ 26 (* i 15))]
     [:g [:text {:x 8 :y y :fill \"#475569\"} label]]))
 [:text {:x 8 :y 204 :fill \"#64748b\"} \"legend\"]]
```")

(deftest ^{:doc "Pure unit, seed=42, and latency checks for @container header, centralized reads-main?, /system, and SVG sanitization."
           :cost/ticks 120000000}
  ui-live-defects-pure-and-latency-test
  (testing "Defect 1: header-unit omits legacy .is-narrow & stale :viewport-w DOM splits; CSS @container is single authority"
    (tf/with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (vec (concat (router/core-routes-tx)
                                              [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main :seon.agent/label "Root"}
                                               {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root]
                                                :seon.user/view-mode :canvas :seon.user/expert-mode? true
                                                :seon.user/viewport-w 375 :seon.user/viewport-h 812}]))})
      (let [hdr-375 (render/hiccup->html (ui/header-unit @conn {:boot-seq 1 :agent-states {:agent/root :active} :active-count 1}))
            _ (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/viewport-w 1280 :seon.user/viewport-h 800}]})
            hdr-1280 (render/hiccup->html (ui/header-unit @conn {:boot-seq 1 :agent-states {:agent/root :active} :active-count 1}))
            css (styles/full-css)]
        (is (not (str/includes? hdr-375 "is-narrow")))
        (is (not (str/includes? hdr-1280 "is-narrow")))
        (is (str/includes? hdr-375 "class=\"wordmark\""))
        (is (str/includes? hdr-375 "class=\"crumb-sep\""))
        (is (str/includes? hdr-375 "sig-strip"))
        (is (str/includes? hdr-375 "hdr-health-compact"))
        (is (str/includes? hdr-375 "status-active-word"))
        (is (str/includes? hdr-375 "status-toggle-rate"))
        (is (str/includes? css "@container (max-width:899px){#seon-header .sig-strip{display:none}#seon-header .hdr-health-compact{display:inline-flex}}"))
        (is (str/includes? css "@container (max-width:759px){#seon-header"))
        (is (str/includes? css "#seon-header .wordmark,#seon-header .crumb-sep,#seon-header .status-label,#seon-header .status-toggle-rate,#seon-header .status-active-word{display:none}"))
        (is (not (str/includes? css ".topbar.is-narrow"))))))

  (testing "Defect 2: centralized reads-main? in gather-agent-candidates keeps all-signals, /system, and Dev Mode :canvas < 60ms warm & < 150ms cold"
    (tf/with-test-store [{:keys [conn cfg]}]
      (d/transact conn {:tx-data (vec (concat (router/core-routes-tx)
                                              [{:seon.system/id :root :seon.system/title "Seon Runtime" :seon.system/description "Test runtime"}
                                               {:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :branch/root
                                                :seon.agent/read-branch :main :seon.agent/router? true :seon.agent/label "Root"}
                                               {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root]
                                                :seon.user/view-mode :canvas :seon.user/expert-mode? true :seon.user/viewport-w 1280 :seon.user/viewport-h 800}]))})
      (store/branch! conn :main :branch/root)
      (let [bconn (store/connect cfg :branch/root)]
        (try
          (d/transact bconn {:tx-data [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root
                                        :seon.agent.eval/code "" :seon.agent.eval/thought "Branch reply prose"
                                        :seon.agent.eval/turn 1 :seon.agent.eval/ms 2.0}]})
          (finally (d/release bconn))))
      (d/transact conn {:tx-data (vec (concat
                                       (mapcat #(code/file-import-tx @conn (str "src/demo/m" % ".clj")
                                                                     (str "(ns demo.m" % ")\n(defn f" % " [x] (+ x " % "))\n")
                                                                     false {:skip-compiler-analyze? true})
                                               (range 1 21))
                                       (mapv #(hash-map :seon.agent/id (keyword "worker" (str "w" %)) :seon.agent/branch :main :seon.agent/paused? true) (range 1 16))
                                       (mapv #(hash-map :seon.task/id (keyword "task" (str "t" %)) :seon.task/title (str "Task " %) :seon.task/done? false) (range 1 11))
                                       (mapv #(hash-map :seon.error/id (random-uuid) :seon.error/kind :slow-render :seon.error/severity :warning
                                                        :seon.error/status :open :seon.error/message (str "Warn " %)) (range 1 6))))})
      (let [ms-of (fn [f] (let [t0 (System/nanoTime) v (f)] [(/ (- (System/nanoTime) t0) 1e6) v]))
            mdb @conn
            bdb (store/head conn :branch/root)
            gathered (ctx/gather-agent-candidates mdb bdb :agent/root {:target :canvas})
            [sig-cold _] (ms-of #(sig/all-signals mdb))
            [sig-warm _] (ms-of #(sig/all-signals mdb))
            [dev-cold dev-h] (ms-of #(ui/view-unit mdb {:conn conn :expert-mode? true}))
            [dev-warm _] (ms-of #(ui/view-unit mdb {:conn conn :expert-mode? true}))
            sys-db (d/db-with mdb [{:seon.user/id :user/default :seon.user/view-mode :system :seon.user/expert-mode? true}])
            [sys-cold _] (ms-of #(ui/view-unit sys-db {:conn conn :expert-mode? true}))
            [sys-warm _] (ms-of #(surface/render-surface mdb bdb {:viewing-id :agent/root :view-mode :system :expert-mode? true :viewport-w 1280 :viewport-h 800}))]
        (is (map? (:ctx-trace gathered)))
        (is (< sig-cold 150.0) (str "Cold all-signals too slow: " sig-cold "ms"))
        (is (< sig-warm 60.0) (str "Warm all-signals too slow: " sig-warm "ms"))
        (is (< dev-cold 150.0) (str "Cold Dev Mode :canvas too slow: " dev-cold "ms"))
        (is (< dev-warm 60.0) (str "Warm Dev Mode :canvas too slow: " dev-warm "ms"))
        (is (str/includes? (render/hiccup->html dev-h) "Branch reply prose") "Must still render :branch/root conversation prose")
        (is (< sys-cold 150.0) (str "Cold /system too slow: " sys-cold "ms"))
        (is (< sys-warm 60.0) (str "Warm /system too slow: " sys-warm "ms")))))

  (testing "Defect 3: author SVG colors preserved (no adapt-svg band-aid), :on-click actions allowed, read-only hiccup fallback, and seed=42 checks"
    (let [san-h (sanitize/sanitize-hiccup
                 [:svg {:viewBox "0 0 340 210"}
                  [:rect {:width 340 :height 210 :fill "#f8fafc"}]
                  [:rect {:width 120 :height 10 :fill "#3b82f6"}]
                  [:rect {:width 240 :height 4 :fill "#ffffff"}]
                  [:text {:x 8 :y 20 :fill "#0f172a"} "Heading"]
                  [:text {:x 8 :y 40 :fill "#64748b"} "Muted"]])
          h-html (render/hiccup->html san-h)
          san-s (sanitize/sanitize-svg
                 "<svg viewBox=\"0 0 340 210\"><script>evil()</script><rect width=\"340\" height=\"210\" fill=\"#ffffff\"/><circle cx=\"10/4\" cy=\"10\" r=\"3\" onclick=\"x()\"/><text x=\"8\" y=\"20\" fill=\"#0f172a\">Hi</text></svg>")
          rep (sanitize/sanitize-hiccup-with-report [:svg (list (quote for) (quote [[x [1]] [:rect]])) [:circle {:cx 5 :cy 5 :r 3}]])
          seq-ok (sanitize/sanitize-hiccup [:ul (list [:li "a"] [:li "b"])])
          md-uneval (render/hiccup->html (rmd/render-markdown-blocks uneval-for-hiccup-md))
          md-valid  (render/hiccup->html (rmd/render-markdown-blocks valid-svg-hiccup-md))]
      (is (str/includes? h-html "fill=\"#f8fafc\"") "Valid author fills must NOT be rewritten")
      (is (str/includes? h-html "fill=\"#0f172a\"") "Valid dark-on-accent text fills must NOT be rewritten")
      (is (str/includes? h-html "fill=\"#ffffff\"") "Thin white SVG bars must NOT be rewritten")
      (is (str/includes? h-html "fill=\"#3b82f6\""))
      (is (not (str/includes? h-html "var(--base-900)")))
      (is (str/includes? san-s "fill=\"#ffffff\""))
      (is (str/includes? san-s "fill=\"#0f172a\""))
      (is (str/includes? san-s "cx=\"2.5\""))
      (is (not (str/includes? san-s "<script")))
      (is (not (str/includes? san-s "onclick")))
      (is (false? (sanitize/contains-unevaluated-form? [:button {:on-click (list (quote seon.web/navigate-url!) "/chat")} "Go"])))
      (is (false? (sanitize/contains-unevaluated-form? [:button {:on-click (list (quote quote) (list (quote seon.web/navigate-url!) "/chat"))} "Go"])))
      (is (true? (sanitize/contains-unevaluated-form? [:button {:on-click (list (quote clojure.core/eval) 1)} "Bad"])))
      (is (true? (sanitize/contains-unevaluated-form? [:svg (list (quote for) (quote [[x [1]] [:rect]]))])))
      (is (not (str/includes? (render/hiccup->html (:hiccup rep)) "<for>")))
      (is (seq (:dropped rep)))
      (is (= "<ul><li>a</li><li>b</li></ul>" (render/hiccup->html seq-ok)))
      (is (str/includes? md-uneval "md-hiccup-fallback"))
      (is (str/includes? md-uneval "Hiccup fences require pure EDN data"))
      (is (not (str/includes? md-uneval "Run in REPL")) "Unevaluated hiccup fallback must be read-only without Run in REPL button")
      (is (not (str/includes? md-uneval "eval-snippet")))
      (is (not (str/includes? md-uneval "<for>")))
      (is (str/includes? md-valid "md-hiccup-card"))
      (is (str/includes? md-valid "fill=\"#f8fafc\"")))
    (doseq [sym '[seon.render.sanitize/contains-unevaluated-form?
                  seon.render.sanitize/sanitize-svg
                  seon.render.sanitize/classify-url]]
      (let [res (schema/stress-var nil sym {:seed 42 :samples 100})]
        (is (:ok? res) (str "seed=42 stress-var failed for " sym ": " (pr-str res)))))))

(deftest ^{:doc "Fast zero-WKWebView SSR & HTTP regression proof covering responsive header, SVG/hiccup fallback, themes, and /system."
           :cost/ticks 120000000}
  wkwebview-live-ui-defects-regression-test
  (tf/with-test-store [{:keys [conn]}]
    (let [mid1 (random-uuid)
          mid2 (random-uuid)
          _ (d/transact
             conn
             {:tx-data
              (vec (concat
                    (router/core-routes-tx)
                    [{:seon.system/id :root :seon.system/title "Seon Runtime" :seon.system/description "Regression runtime"}
                     {:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main
                      :seon.agent/read-branch :main :seon.agent/router? true :seon.agent/label "Root"}
                     {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root]
                      :seon.user/view-mode :canvas :seon.user/theme :dark :seon.user/viewport-w 375 :seon.user/viewport-h 812
                      :seon.user/expert-mode? false :seon.user/sidebar-open? false}
                     {:seon.agent.message/id mid1 :seon.agent.message/seq 1
                      :seon.agent.message/from :user :seon.agent.message/to :agent/root
                      :seon.agent.message/type :chat :seon.agent.message/body "Show SVG cards"}
                     {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root
                      :seon.agent.eval/agent-ref [:seon.agent/id :agent/root]
                      :seon.agent.eval/seen-messages [mid1] :seon.agent.eval/code ""
                      :seon.agent.eval/thought (str valid-svg-hiccup-md "\n\n" uneval-for-hiccup-md)
                      :seon.agent.eval/turn 1 :seon.agent.eval/ms 2.0}
                     {:seon.agent.message/id mid2 :seon.agent.message/seq 2
                      :seon.agent.message/from :user :seon.agent.message/to :agent/root
                      :seon.agent.message/type :chat :seon.agent.message/body "Overview"}
                     {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root
                      :seon.agent.eval/agent-ref [:seon.agent/id :agent/root]
                      :seon.agent.eval/seen-messages [mid2] :seon.agent.eval/code ""
                      :seon.agent.eval/thought "Clean summary reply."
                      :seon.agent.eval/turn 2 :seon.agent.eval/ms 2.0}]))})
          req-ctx {:ui-ch (a/chan 16) :server-in-ch (a/chan 16) :conn conn}
          get-ssr (fn [uri]
                    (let [resp (web/handle-http-request! req-ctx {:request-method :get :uri uri})]
                      (is (= 200 (:status resp)) (str "SSR GET " uri " failed"))
                      (:body resp)))
          home-html (get-ssr "/")
          _ (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/view-mode :session}]})
          chat-html (get-ssr "/chat")
          _ (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/view-mode :system :seon.user/expert-mode? true}]})
          sys-html  (get-ssr "/system")]
      (doseq [[lbl html] [["/" home-html] ["/chat" chat-html] ["/system" sys-html]]]
        (doseq [id ["seon-app" "seon-css" "seon-header" "seon-view-unit" "seon-composer-unit"]]
          (is (str/includes? html (str "id=\"" id "\"")) (str lbl " missing #" id)))
        (is (not (str/includes? html "is-narrow")) (str lbl " must never emit legacy .is-narrow"))
        (is (str/includes? html "@container (max-width:759px)") (str lbl " missing @container CSS"))
        (is (str/includes? html "@container (max-width:899px)") (str lbl " missing @container 899px CSS")))
      (is (str/includes? chat-html "md-hiccup-card"))
      (is (str/includes? chat-html "md-hiccup-fallback"))
      (is (str/includes? chat-html "Hiccup fences require pure EDN data"))
      (is (not (str/includes? chat-html "Run in REPL")))
      (is (not (str/includes? chat-html "<for>")))
      (is (str/includes? sys-html "Seon Runtime")))))

(deftest ^{:cost/ticks 60000000} wave8d-flat-aevt-sidebar-latency-and-order-test
  (testing "Cached flat-AEVT sidebar-unit stays fast cold & warm while preserving exact ordering"
    (tf/with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (into [{:seon.user/id :user/default :seon.user/sidebar-open? true :seon.user/viewport-w 1280}
                                        {:seon.agent/id :agent/root :seon.agent/branch :main :seon.agent/entry? true :seon.agent/label "Root"}
                                        {:seon.agent/id :example/ex1 :seon.agent/branch :main :seon.agent/label "Ex"}
                                        {:seon.agent/id :worker/arch :seon.agent/branch :main :seon.agent/archived? true}]
                                       (for [i (range 40)]
                                         {:seon.agent/id (keyword "worker" (str "w-" i)) :seon.agent/branch :main :seon.agent/parent :agent/root}))})
      (let [db @conn
            c (seon.code/measure-call #(seon.render.ui/sidebar-unit db {:agent-states {}}))
            w (seon.code/measure-call #(seon.render.ui/sidebar-unit db {:agent-states {}}))
            html (pr-str (:val w))]
        (is (vector? (:val c)))
        (is (= (:val c) (:val w)))
        (is (clojure.string/includes? html "Root"))
        (is (not (clojure.string/includes? html "example")))
        (is (not (clojure.string/includes? html "worker/arch")))
        (is (< (:ms w) 10.0))))))
