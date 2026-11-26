(ns seon.browser-journey-gate-test
  "Fast pure unit, SSR, router, and surface matrix regression tests (zero WKWebView spawn tax)."
  (:require [clojure.core.async :as a]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [seon.browser.measure :as measure]
            [seon.code :as code]
            [seon.render :as render]
            [seon.render.data :as rdata]
            [seon.render.sanitize :as sanitize]
            [seon.render.surface :as surface]
            [seon.render.ui :as ui]
            [seon.schema :as schema]
            [seon.test-fixture :as tf]
            [seon.web :as web]
            [seon.web.router :as router]
            [seon.web.styles :as styles]))

(deftest ^{:doc "Pure browser measurement, routing, sanitization, and seed=42 generative checks."
           :cost/ticks 90000000}
  journey-helpers-and-defect-verdict-test
  (testing "Browser measurement JS, pixel-fit analysis, and hint calibration"
    (let [boxes-js (measure/measure-boxes-js)
          clean-fit (measure/analyze-pixel-fit
                     {:viewport {:w 1280 :h 800 :docScrollW 1280 :overflowX false}
                      :app true
                      :elements [{:selector "#card-ok" :chosenFn "seon.render.entities.work/render-task-card"
                                  :hintMinW 280 :w 300 :h 96 :scrollW 300 :clientW 300 :overflowX false}]})
          bad-fit (measure/analyze-pixel-fit
                   {:viewport {:w 375 :h 812 :docScrollW 430 :overflowX true}
                    :app true
                    :elements [{:selector "#hdr-pill" :chosenFn "seon.signals/render-signals-bar"
                                :hintMinW 220 :w 120 :h 28 :scrollW 180 :clientW 120 :overflowX true}]})
          cal (measure/calibrate-hints bad-fit)]
      (is (string? boxes-js))
      (is (str/includes? boxes-js "getBoundingClientRect"))
      (is (true? (:ok? clean-fit)))
      (is (false? (:doc-overflow-x clean-fit)))
      (is (empty? (:clipped-items clean-fit)))
      (is (false? (:ok? bad-fit)))
      (is (true? (:doc-overflow-x bad-fit)))
      (is (seq (:clipped-items bad-fit)))
      (is (true? (get-in cal ['seon.signals/render-signals-bar :overflow-x?])))))

  (testing "Sanitizer SVG stripping, author color preservation, and legitimate :on-click allowance"
    (let [san-bad (sanitize/sanitize-svg "<svg><script>x()</script><rect width=\"10\" height=\"10\" onclick=\"y()\"/></svg>")
          san-ok  (sanitize/sanitize-svg "<svg viewBox=\"0 0 10 10\"><rect width=\"5\" height=\"5\" fill=\"#ffffff\"/></svg>")]
      (is (not (str/includes? san-bad "<script")))
      (is (not (str/includes? san-bad "onclick")))
      (is (str/includes? san-ok "fill=\"#ffffff\""))
      (is (false? (sanitize/contains-unevaluated-form? [:button {:on-click (list (quote seon.web/navigate-url!) "/chat")} "Chat"])))
      (is (true? (sanitize/contains-unevaluated-form? [:div (list (quote for) (quote [[x [1]] [:span x]]))])))))

  (testing "Seed=42 Malli generative checks on pure measurement & sanitizer helpers"
    (doseq [sym '[seon.browser.measure/analyze-pixel-fit
                  seon.browser.measure/calibrate-hints
                  seon.render.sanitize/sanitize-svg
                  seon.render.sanitize/contains-unevaluated-form?
                  seon.render.sanitize/classify-url]]
      (let [res (schema/stress-var nil sym {:seed 42 :samples 100})]
        (is (:ok? res) (str "stress-var failed for " sym ": " (pr-str res)))))))

(defn- seed-offline-journey-db!
  "Seeds routes, root agent, user state, code entities & 2 timeline txs for fast SSR journey checks."
  [conn]
  (d/transact
   conn
   {:tx-data
    (vec (concat
          (router/core-routes-tx)
          (code/file-import-tx
           @conn "src/seon/web.clj"
           (str "(ns seon.web \"Offline web proc namespace.\")\n\n"
                "(defn start-http-listener! \"Starts listener.\" [port] {:port port})\n\n"
                "(defn web-proc \"HTTP flow proc.\" [] (start-http-listener! 0))\n")
           false {:skip-compiler-analyze? true})
          [{:seon.system/id :root :seon.system/title "Seon Runtime" :seon.system/description "Offline test runtime"}
           {:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main
           :seon.agent/router? true :seon.agent/label "Root"
           :seon.agent/hero-title "Offline Journey Test"}
           {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root]
            :seon.user/view-mode :canvas :seon.user/theme :dark :seon.user/viewport-w 1280 :seon.user/viewport-h 800
            :seon.user/expert-mode? false :seon.user/sidebar-open? true}]))})
  (let [mid1 (random-uuid)
        tx1 (:tx-data
             (d/transact
              conn
              {:tx-data
               [{:seon.agent.message/id mid1 :seon.agent.message/seq 1
                 :seon.agent.message/from :user :seon.agent.message/to :agent/root
                 :seon.agent.message/type :chat :seon.agent.message/body "Initial timeline prompt"}]}))
        tx2 (:tx-data
             (d/transact
              conn
              {:tx-data
               [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root
                 :seon.agent.eval/agent-ref [:seon.agent/id :agent/root]
                 :seon.agent.eval/seen-messages [mid1]
                 :seon.agent.eval/code "" :seon.agent.eval/thought "Initial summary for `d/as-of` rewind."
                 :seon.agent.eval/turn 1 :seon.agent.eval/ms 3.0}]}))]
    {:tx1 (reduce max 0 (map :tx tx1))
     :tx2 (reduce max 0 (map :tx tx2))}))

(deftest ^{:doc "Fast zero-WKWebView SSR & surface journey matrix across routes, viewports, themes, and d/as-of time-travel."
           :cost/ticks 120000000}
  headless-wkwebview-user-journeys-gate-test
  (tf/with-test-store [{:keys [conn]}]
    (let [{:keys [tx1]} (seed-offline-journey-db! conn)
          req-ctx {:ui-ch (a/chan 16) :server-in-ch (a/chan 16) :conn conn}
          css (styles/full-css)]
      (testing "All 6 canonical SSR routes return 200 with required landmarks and zero .is-narrow"
        (doseq [uri ["/" "/chat" "/ns/seon.web" "/var/seon.web/web-proc" "/system" "/context"]]
          (let [resp (web/handle-http-request! req-ctx {:request-method :get :uri uri})
                body (:body resp)]
            (is (= 200 (:status resp)) (str "Route " uri " returned " (:status resp)))
            (doseq [id ["seon-app" "seon-header" "seon-main" "seon-view-unit" "seon-composer-unit"]]
              (is (str/includes? body (str "id=\"" id "\"")) (str uri " missing #" id)))
            (is (not (str/includes? body "is-narrow")) (str uri " must not emit legacy .is-narrow")))))

      (testing "Responsive surface & header render cleanly across [375x812 1280x800] x [:dark :light] + Dev Mode + d/as-of rewind"
        (is (str/includes? css "@container (max-width:759px)"))
        (is (str/includes? css "@container (max-width:899px)"))
        (is (not (str/includes? css ".topbar.is-narrow")))
        (doseq [[w h] [[375 812] [1280 800]]
                theme [:dark :light]]
          (d/transact conn {:tx-data [{:seon.user/id :user/default :seon.user/viewport-w w :seon.user/viewport-h h
                                       :seon.user/theme theme :seon.user/expert-mode? true}]})
          (let [db @conn
                hdr (render/hiccup->html (ui/header-unit db {:boot-seq 1 :agent-states {} :active-count 0}))
                surf (render/hiccup->html (surface/render-surface db db {:conn conn :view-mode :canvas :viewport-w w :viewport-h h :expert-mode? true}))]
            (is (str/includes? hdr (str "data-theme=\"" (name theme) "\"")))
            (is (str/includes? hdr "Explain"))
            (is (str/includes? hdr "System"))
            (is (not (str/includes? hdr "is-narrow")))
            (is (str/includes? surf "id=\"seon-view-unit\""))))
        (let [db @conn
              htxs (rdata/view-history-txs db db :agent/root [])
              past-html (render/hiccup->html (surface/render-surface db db {:conn conn :view-mode :canvas :expert-mode? true :as-of-tx tx1}))]
          (is (>= (count htxs) 2))
          (is (str/includes? past-html "Time-travel")))))))
