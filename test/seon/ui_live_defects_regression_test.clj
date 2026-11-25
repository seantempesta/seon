(ns seon.ui-live-defects-regression-test
  "Unit, latency, seed=42, and headless WKWebView regression tests for live UI defects:
   1. Header brand clipping at 1280x800 and button overlaps at 375x812 with Working pill & stale viewport-w.
   2. Dev Mode toggle & /system render latency (< 60 ms warm, < 150 ms cold) on diverged read-main branch DBs.
   3. Dark-mode hardcoded bright-white SVG card + low-contrast text + unevaluated hiccup code lists."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [jolt.fs :as fs]
            [seon.browser :as browser]
            [seon.browser.journey :as journey]
            [seon.browser.measure :as measure]
            [seon.code :as code]
            [seon.render :as render]
            [seon.render.markdown :as rmd]
            [seon.render.sanitize :as sanitize]
            [seon.render.surface :as surface]
            [seon.render.ui :as ui]
            [seon.schema :as schema]
            [seon.server :as server]
            [seon.signals :as sig]
            [seon.store :as store]
            [seon.test-fixture :as tf]
            [seon.transactor :as transactor]
            [seon.web :as web]
            [seon.web.router :as router]
            [seon.web.styles :as styles]))

(def ^:private white-svg-hiccup-md
  "```hiccup
[:svg {:viewBox \"0 0 340 210\" :width \"100%\" :xmlns \"http://www.w3.org/2000/svg\"}
 [:rect {:width 340 :height 210 :fill \"#f8fafc\" :rx 6}]
 [:text {:x 8 :y 20 :font-size 12 :font-weight \"700\" :fill \"#0f172a\"} \"Cached vs ran tests\"]
 [:rect {:x 8 :y 36 :width 160 :height 14 :rx 2 :fill \"#3b82f6\"}]
 [:text {:x 8 :y 72 :font-size 11 :fill \"#475569\"} \"branch summary\"]
 [:text {:x 8 :y 196 :font-size 11 :fill \"#64748b\"} \"cached ran failing\"]]
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

(deftest ^{:doc "Pure unit, seed=42, and latency checks for header, signals, /system, Dev Mode, and SVG sanitization."
           :cost/ticks 120000000}
  ui-live-defects-pure-and-latency-test
  (testing "Defect 1: header-unit always emits .wordmark & .crumb-sep; CSS controls narrow breakpoint"
    (tf/with-test-store [{:keys [conn]}]
      (d/transact conn {:tx-data (vec (concat (router/core-routes-tx)
                                              [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main :seon.agent/label "Root"}
                                               {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root]
                                                :seon.user/view-mode :canvas :seon.user/viewport-w 375 :seon.user/viewport-h 812}]))})
      (let [html (render/hiccup->html (ui/header-unit @conn {:boot-seq 1 :agent-states {:agent/root :active} :active-count 1}))
            css (styles/full-css)]
        (is (str/includes? html "class=\"wordmark\"") "wordmark must stay in DOM so desktop never clips to 11px")
        (is (str/includes? html "class=\"crumb-sep\""))
        (is (str/includes? html "Working…"))
        (is (str/includes? css "@container (max-width:759px){#seon-header"))
        (is (str/includes? css "#seon-header .wordmark,#seon-header .crumb-sep{display:none}"))
        (is (str/includes? css "white-space:nowrap")))))

  (testing "Defect 2: all-signals, /system, and Dev Mode :canvas on diverged :branch/root stay < 60ms warm & < 150ms cold"
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
      ;; Advance :main with 20 synthetic namespaces/vars + agents/tasks/errors so :branch/root lags behind :main
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
            [sig-cold _] (ms-of #(sig/all-signals mdb))
            [sig-warm _] (ms-of #(sig/all-signals mdb))
            [dev-cold dev-h] (ms-of #(ui/view-unit mdb {:conn conn :expert-mode? true}))
            [dev-warm _] (ms-of #(ui/view-unit mdb {:conn conn :expert-mode? true}))
            sys-db (d/db-with mdb [{:seon.user/id :user/default :seon.user/view-mode :system :seon.user/expert-mode? true}])
            [sys-cold _] (ms-of #(ui/view-unit sys-db {:conn conn :expert-mode? true}))
            [sys-warm _] (ms-of #(surface/render-surface mdb mdb {:viewing-id :agent/root :view-mode :system :expert-mode? true :viewport-w 1280 :viewport-h 800}))]
        (is (< sig-cold 150.0) (str "Cold all-signals too slow: " sig-cold "ms"))
        (is (< sig-warm 60.0) (str "Warm all-signals too slow: " sig-warm "ms"))
        (is (< dev-cold 150.0) (str "Cold Dev Mode :canvas too slow: " dev-cold "ms"))
        (is (< dev-warm 60.0) (str "Warm Dev Mode :canvas too slow: " dev-warm "ms"))
        (is (str/includes? (render/hiccup->html dev-h) "Branch reply prose") "Must still render :branch/root conversation prose")
        (is (< sys-cold 150.0) (str "Cold /system too slow: " sys-cold "ms"))
        (is (< sys-warm 60.0) (str "Warm /system too slow: " sys-warm "ms")))))

  (testing "Defect 3: dark-mode SVG theme adaptation, unevaluated hiccup fallback, and seed=42 checks"
    (let [san-h (sanitize/sanitize-hiccup
                 [:svg {:viewBox "0 0 340 210"}
                  [:rect {:width 340 :height 210 :fill "#f8fafc"}]
                  [:rect {:width 120 :height 10 :fill "#3b82f6"}]
                  [:rect {:width 12 :height 12 :fill "#ffffff"}]
                  [:text {:x 8 :y 20 :fill "#0f172a"} "Heading"]
                  [:text {:x 8 :y 40 :fill "#64748b"} "Muted"]])
          h-html (render/hiccup->html san-h)
          san-s (sanitize/sanitize-svg
                 "<svg viewBox=\"0 0 340 210\"><rect width=\"340\" height=\"210\" fill=\"#ffffff\"/><text x=\"8\" y=\"20\" fill=\"#0f172a\">Hi</text><rect x=\"8\" y=\"30\" width=\"12\" height=\"12\" fill=\"#ffffff\"/></svg>")
          rep (sanitize/sanitize-hiccup-with-report [:svg (list (quote for) (quote [[x [1]] [:rect]])) [:circle {:cx 5 :cy 5 :r 3}]])
          seq-ok (sanitize/sanitize-hiccup [:ul (list [:li "a"] [:li "b"])])
          md-uneval (render/hiccup->html (rmd/render-markdown-blocks uneval-for-hiccup-md))
          md-valid  (render/hiccup->html (rmd/render-markdown-blocks white-svg-hiccup-md))]
      (is (str/includes? h-html "fill=\"var(--base-900)\""))
      (is (str/includes? h-html "fill=\"var(--text-100)\""))
      (is (str/includes? h-html "fill=\"var(--text-300)\""))
      (is (str/includes? h-html "fill=\"#3b82f6\"") "Accent colors must stay intact")
      (is (str/includes? san-s "fill=\"var(--base-900)\""))
      (is (str/includes? san-s "fill=\"var(--text-100)\""))
      (is (str/includes? san-s "width=\"12\" height=\"12\" fill=\"#ffffff\"") "Tiny swatch rect stays intact")
      (is (not (str/includes? (render/hiccup->html (:hiccup rep)) "<for>")))
      (is (seq (:dropped rep)))
      (is (= "<ul><li>a</li><li>b</li></ul>" (render/hiccup->html seq-ok)))
      (is (str/includes? md-uneval "md-clj-block") "Unevaluated Clojure hiccup fence renders as clean Clojure code card")
      (is (not (str/includes? md-uneval "<for>")))
      (is (str/includes? md-valid "md-hiccup-card"))
      (is (str/includes? md-valid "fill=\"var(--base-900)\"")))
    (doseq [sym '[seon.render.sanitize/contains-unevaluated-form?
                seon.render.sanitize/adapt-svg-theme-colors
                seon.render.sanitize/sanitize-svg
                seon.render.sanitize/classify-url]]
      (let [res (schema/stress-var nil sym {:seed 42 :samples 100})]
        (is (:ok? res) (str "seed=42 stress-var failed for " sym ": " (pr-str res)))))))

(deftest ^{:doc "Real headless WKWebView regression proof covering all 3 live UI defects."
           :cost/ticks 300000000}
  wkwebview-live-ui-defects-regression-test
  (let [bin (browser/default-binary-path)]
    (when (and (string? bin) (fs/executable? bin))
      (tf/with-test-store [{:keys [conn cfg dir]}]
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
                          :seon.user/expert-mode? false :seon.user/sidebar-open? false
                          :seon.user/lab-prefs {:nav-back [{:viewing :agent/root :mode :session :inspect-ref nil :as-of-tx nil}]
                                                :nav-forward [{:viewing :agent/root :mode :system :inspect-ref nil :as-of-tx nil}]}}
                         {:seon.agent.message/id mid1 :seon.agent.message/seq 1
                          :seon.agent.message/from :user :seon.agent.message/to :agent/root
                          :seon.agent.message/type :chat :seon.agent.message/body "Show SVG cards"}
                         {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root
                          :seon.agent.eval/agent-ref [:seon.agent/id :agent/root]
                          :seon.agent.eval/seen-messages [mid1] :seon.agent.eval/code ""
                          :seon.agent.eval/thought (str white-svg-hiccup-md "\n\n" uneval-for-hiccup-md)
                          :seon.agent.eval/turn 1 :seon.agent.eval/ms 2.0}
                         {:seon.agent.message/id mid2 :seon.agent.message/seq 2
                          :seon.agent.message/from :user :seon.agent.message/to :agent/root
                          :seon.agent.message/type :chat :seon.agent.message/body "Overview"}
                         {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root
                          :seon.agent.eval/agent-ref [:seon.agent/id :agent/root]
                          :seon.agent.eval/seen-messages [mid2] :seon.agent.eval/code ""
                          :seon.agent.eval/thought "Clean summary reply."
                          :seon.agent.eval/turn 2 :seon.agent.eval/ms 2.0}]))})
              server-in (a/chan 64)
              exits-ch (a/chan 16)
              listener (web/start-http-listener! -1)
              g (flow/create-flow
                 {:procs {:server {:proc (flow/process server/server-proc)
                                   :args {:in-ch server-in :conn conn :cfg cfg :repo-dir dir
                                          :vectors-connected? false :browser-connected? false}}
                          :transactor {:proc (flow/process transactor/transactor-proc)
                                       :args {:conn conn :cfg cfg :repo-dir dir :commit-git? false}}
                          :web {:proc (flow/process web/web-proc)
                                :args {:port (:port listener) :listener listener :conn conn :cfg cfg
                                       :repo-dir dir :store-path dir :server-in-ch server-in}}
                          :ext-src {:proc (flow/process
                                           (fn
                                             ([] {:outs {:exits "Exits"} :workload :io})
                                             ([_] {::flow/in-ports {:in exits-ch}})
                                             ([st _] st)
                                             ([st _ msg] [st {:exits [msg]}])))}}
                  :conns [[[:server :tx] [:transactor :tx]]
                          [[:transactor :db] [:web :db]]
                          [[:ext-src :exits] [:web :exits]]]})]
          (try
            (flow/start g)
            (flow/resume g)
            (a/>!! exits-ch {:ev :turn-busy :agent-id :agent/root :phase :working})
            (Thread/sleep 80)
            (let [base-url (str "http://127.0.0.1:" (:port listener))
                  sess (journey/open-session! (str base-url "/") {:binary-path bin :width 1280 :height 800})]
              (is (:ok? sess) (str "open-session! failed: " (pr-str sess)))
              (when (:ok? sess)
                (try
                  (let [send! (fn [m]
                                (let [id (vswap! (:seq sess) inc)]
                                  ((get-in sess [:handle :send!]) (assoc m :id id))
                                  (let [dl (+ (System/currentTimeMillis) 6000)]
                                    (loop []
                                      (let [rem (- dl (System/currentTimeMillis))
                                            [v c] (when (pos? rem) (a/alts!! [(:ipc-ch sess) (a/timeout rem)]))]
                                        (cond
                                          (or (<= rem 0) (not= c (:ipc-ch sess)) (nil? v)) {:ok false :error :timeout}
                                          (= id (:id v)) v
                                          :else (recur)))))))
                        nav-and-audit!
                        (fn [path w h theme]
                          (send! {:op "resize" :width w :height h})
                          (send! {:op "navigate" :url (browser/headless-url (str base-url path))})
                          (Thread/sleep 120)
                          (send! {:op "eval_js" :code (journey/install-error-capture-js)})
                          (journey/audit-page! sess {:journey :regression :step (str w "-" (name theme))
                                                     :width w :height h :theme theme :checks []}))
                        a-1280 (nav-and-audit! "/" 1280 800 :dark)
                        _ (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/viewport-w 1280 :seon.user/viewport-h 800}]})
                        _ (Thread/sleep 80)
                        a-375 (nav-and-audit! "/" 375 812 :dark)
                        a-chat-1280-dk (nav-and-audit! "/chat" 1280 800 :dark)
                        a-chat-1280-lt (nav-and-audit! "/chat" 1280 800 :light)
                        a-chat-375-dk  (nav-and-audit! "/chat" 375 812 :dark)
                        safe-svg-html (str "<div class=\"card\">"
                                           (sanitize/sanitize-svg
                                            "<svg viewBox=\"0 0 340 210\" width=\"340\" height=\"210\"><rect width=\"340\" height=\"210\" fill=\"#ffffff\"/><text x=\"12\" y=\"28\" font-size=\"14\" fill=\"#0f172a\">Dark Safe Card</text><text x=\"12\" y=\"54\" font-size=\"12\" fill=\"#64748b\">Secondary line</text></svg>")
                                           "</div>")
                        st-dk (measure/normalize-snippet-measurement (:value (send! {:op "eval_js" :code (measure/stage-hiccup-js safe-svg-html 640 :dark)})))
                        _ (send! {:op "eval_js" :code (measure/clear-stage-js)})
                        _ (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/expert-mode? true :seon.user/view-mode :system}]})
                        _ (Thread/sleep 80)
                        a-sys (nav-and-audit! "/system" 1280 800 :dark)]
                    (is (:ok? a-1280) (str "1280x800 dark / failed (brand clip/overlap): " (pr-str (:defects a-1280))))
                    (is (:ok? a-375) (str "375x812 dark / failed (Working pill overlap): " (pr-str (:defects a-375))))
                    (is (:ok? a-chat-1280-dk) (str "1280x800 dark /chat failed (SVG/contrast): " (pr-str (:defects a-chat-1280-dk))))
                    (is (:ok? a-chat-1280-lt) (str "1280x800 light /chat failed: " (pr-str (:defects a-chat-1280-lt))))
                    (is (:ok? a-chat-375-dk) (str "375x812 dark /chat failed: " (pr-str (:defects a-chat-375-dk))))
                    (is (and (:ok? st-dk) (not (:overflow-x? st-dk)) (pos? (:w st-dk)) (pos? (:h st-dk))) (str "Sanitized white SVG card failed dark stage measurement: " (pr-str st-dk)))
                    (is (:ok? a-sys) (str "1280x800 dark /system failed: " (pr-str (:defects a-sys)))))
                  (finally ((:stop! sess))))))
            (finally
              (try (flow/stop g) (catch Throwable _))
              (try (a/close! exits-ch) (catch Throwable _))
              (try (a/close! server-in) (catch Throwable _))
              (try ((:stop! listener)) (catch Throwable _)))))))))
