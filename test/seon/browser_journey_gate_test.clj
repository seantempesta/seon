(ns seon.browser-journey-gate-test
  "Headless WKWebView real-browser user-journey gate tests for `seon.browser.journey`."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [datahike.api :as d]
            [jolt.fs :as fs]
            [seon.browser :as browser]
            [seon.browser.journey :as journey]
            [seon.code :as code]
            [seon.schema :as schema]
            [seon.server :as server]
            [seon.store :as store]
            [seon.test-fixture :as tf]
            [seon.transactor :as transactor]
            [seon.web :as web]
            [seon.web.router :as router]))

(deftest ^{:doc "Pure journey helper, synthetic defect & seed=42 generative checks."}
  journey-helpers-and-defect-verdict-test
  (testing "URL normalization, defaults & browser JS script generators"
    (is (= 6 (count journey/default-journeys)))
    (is (= 3 (count journey/default-viewports)))
    (is (= "http://127.0.0.1:9998" (journey/normalize-url-base nil)))
    (is (= "http://127.0.0.1:9998" (journey/normalize-url-base "   ")))
    (is (= "http://127.0.0.1:9998" (journey/normalize-url-base "127.0.0.1:9998///")))
    (is (= "http://localhost:8080" (journey/normalize-url-base "http://localhost:8080/")))
    (let [err-js (journey/install-error-capture-js)
          tel-js (journey/read-page-telemetry-js)]
      (is (str/includes? err-js "onerror"))
      (is (str/includes? err-js "onunhandledrejection"))
      (is (str/includes? err-js "console.error"))
      (is (str/includes? tel-js "navigation"))
      (is (str/includes? tel-js "overlaps")))
    (is (str/includes? (journey/apply-theme-js :light) "'light'"))
    (is (str/includes? (journey/apply-theme-js :dark) "'dark'"))
    (is (str/includes? (journey/apply-theme-js "LIGHT") "'light'")))

  (testing "Clean audit passes & synthetic defects of every class are caught"
    (let [ok (journey/evaluate-page-audit
              {:pixel-fit {:doc-overflow-x false :clipped-items [] :visual-defects []}
               :telemetry {:errors [] :overlaps []}
               :http-ttfb-ms 24.5
               :checks [{:name :nav :ok? true :detail "ok"}]}
              {:ttfb-budget-ms 80.0})]
      (is (:ok? ok))
      (is (empty? (:defects ok)))
      (is (= "PASS" (:summary ok))))
    (let [bad (journey/evaluate-page-audit
               {:pixel-fit {:doc-overflow-x true
                            :viewport {:w 375 :doc-scroll-w 430 :overflow-x? true}
                            :clipped-items [{:selector "#hdr-pill"}]
                            :visual-defects ["SVG #1 paints bright white card in dark theme"
                                             "Low contrast (1.2:1) on span ('muted')"]}
                :telemetry {:errors ["onerror:TypeError"] :overlaps ["Ov:#a<>#b"]}
                :http-ttfb-ms 125.4
                :checks [{:name :hover :ok? false :detail "clipped"}]}
               {:ttfb-budget-ms 80.0})]
      (is (false? (:ok? bad)))
      (is (some #(str/includes? % "JS:onerror:TypeError") (:defects bad)))
      (is (some #(str/includes? % "Overflow:430>375") (:defects bad)))
      (is (some #(str/includes? % "Clip:#hdr-pill") (:defects bad)))
      (is (some #(str/includes? % "bright white card") (:defects bad)))
      (is (some #(str/includes? % "Low contrast") (:defects bad)))
      (is (some #(str/includes? % "Ov:#a<>#b") (:defects bad)))
      (is (some #(str/includes? % "TTFB:125.4>80.0ms") (:defects bad)))
      (is (some #(str/includes? % "Chk[hover]:clipped") (:defects bad))))
    (let [allow-clip (journey/evaluate-page-audit
                      {:pixel-fit {:clipped-items [{:selector "#ok-scroll"}]}
                       :telemetry {:errors [] :overlaps []}}
                      {:allow-clipped? true})]
      (is (:ok? allow-clip))))

  (testing "Summary aggregation & unavailable browser degradation"
    (let [s (journey/summarize-gate-results
             [{:journey :landing :ok? true :http-ttfb-ms 12.0 :defects []}
              {:journey :chat :ok? false :http-ttfb-ms 45.2 :defects ["JS:boom" "Ov:A<>B"]}
              {:journey :time-travel :ok? true :skipped? true :defects []}]
             ["/tmp/a.jpg" "/tmp/a.jpg" "  " "/tmp/b.jpg"])]
      (is (false? (:ok? s)))
      (is (= {:total 3 :passed 1 :failed 1 :skipped 1 :defect-count 2 :max-http-ttfb-ms 45.2}
             (:summary s)))
      (is (= ["/tmp/a.jpg" "/tmp/b.jpg"] (:artifacts s))))
    (let [missing (journey/run-journey-gate! {:url "http://127.0.0.1:1"
                                              :binary-path "/nonexistent/seon-browser"
                                              :viewports [[1280 800]] :themes [:dark]})]
      (is (false? (:ok? missing)))
      (is (= :browser-unavailable (:error missing)))))

  (testing "Seed=42 Malli generative checks on all pure journey helpers"
    (doseq [sym ['seon.browser.journey/normalize-url-base
                 'seon.browser.journey/install-error-capture-js
                 'seon.browser.journey/apply-theme-js
                 'seon.browser.journey/read-page-telemetry-js
                 'seon.browser.journey/evaluate-page-audit
                 'seon.browser.journey/summarize-gate-results]]
      (let [res (schema/stress-var nil sym {:seed 42 :samples 100})]
        (is (:ok? res) (str "stress-var failed for " sym ": " (pr-str res)))))))

(defn- seed-offline-journey-db!
  "Seeds routes, root agent, user state, code entities & 2 timeline txs for offline WKWebView tests."
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
          [{:seon.agent/id :agent/root :seon.agent/entry? true :seon.agent/branch :main
            :seon.agent/read-branch :main :seon.agent/label "Root" :seon.agent/hero-title "Offline Journey Test"}
           {:seon.user/id :user/default :seon.user/viewing [:seon.agent/id :agent/root]
            :seon.user/view-mode :canvas :seon.user/theme :dark :seon.user/viewport-w 1280 :seon.user/viewport-h 800
            :seon.user/expert-mode? false :seon.user/sidebar-open? true}]))})
  (let [mid1 (random-uuid)]
    (d/transact
     conn
     {:tx-data
      [{:seon.agent.message/id mid1 :seon.agent.message/seq 1
        :seon.agent.message/from :user :seon.agent.message/to :agent/root
        :seon.agent.message/type :chat :seon.agent.message/body "Initial timeline prompt"}]})
    (d/transact
     conn
     {:tx-data
      [{:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root
        :seon.agent.eval/agent-ref [:seon.agent/id :agent/root]
        :seon.agent.eval/seen-messages [mid1]
        :seon.agent.eval/code "" :seon.agent.eval/thought "Initial summary for `d/as-of` rewind."
        :seon.agent.eval/turn 1 :seon.agent.eval/ms 3.0}]})))

(defn- make-stub-md-proc
  "Deterministic offline Markdown streamer for Journey 2 without external LLM calls."
  [conn]
  (flow/process
   (fn
     ([] {:ins {:db "DB commits"} :outs {:stream "Stream" :tx "Tx" :exits "Exits"} :workload :compute})
     ([_] {:n 1})
     ([st _] st)
     ([st _ msg]
      (if (= [:message :user :agent/root] (:reason msg))
        (let [n (inc (:n st))
              db @conn
              mids (->> (d/datoms db :avet :seon.agent.message/to :agent/root)
                        (keep #(:seon.agent.message/id (d/pull db [:seon.agent.message/id] (:e %))))
                        vec)
              md (str "Streaming Markdown reply #" n " for `seon.web/web-proc`:\n\n"
                      "```clojure\n(defn greet [x] (str \"hello \" x))\n```\n\n"
                      (str/join "\n" (map #(str "- Timeline bullet item " % " proving sticky and unpinned scroll.")
                                          (range 1 36))))]
          [(assoc st :n n)
           {:stream [{:agent-id :agent/root :status :thinking}
                     {:agent-id :agent/root :status :token :text md}]
            :tx [{:op :transact
                  :tx-data [(cond-> {:seon.agent.eval/id (random-uuid) :seon.agent.eval/agent :agent/root
                                     :seon.agent.eval/agent-ref [:seon.agent/id :agent/root]
                                     :seon.agent.eval/code "" :seon.agent.eval/thought md
                                     :seon.agent.eval/turn n :seon.agent.eval/ms 4.0}
                              (seq mids) (assoc :seon.agent.eval/seen-messages mids))]}]
            :exits [{:ev :turn-idle :agent-id :agent/root}]}])
        [st nil])))))

(deftest ^{:doc "End-to-end offline headless WKWebView 6-journey matrix gate." :cost/ticks 300000000}
  headless-wkwebview-user-journeys-gate-test
  (let [bin (browser/default-binary-path)]
    (is (and (string? bin) (fs/executable? bin)) "target/seon-browser binary must be built & executable")
    (when (and (string? bin) (fs/executable? bin))
      (tf/with-test-store [{:keys [conn cfg dir]}]
        (seed-offline-journey-db! conn)
        (let [server-in (a/chan 64)
              listener (web/start-http-listener! -1)
              adir (str "/tmp/seon-journey-test-" (System/nanoTime))
              g (flow/create-flow
                 {:procs {:server {:proc (flow/process server/server-proc)
                                   :args {:in-ch server-in :conn conn :cfg cfg :repo-dir dir
                                          :vectors-connected? false :browser-connected? false}}
                          :transactor {:proc (flow/process transactor/transactor-proc)
                                       :args {:conn conn :cfg cfg :repo-dir dir :commit-git? false}}
                          :web {:proc (flow/process web/web-proc)
                                :args {:port (:port listener) :listener listener :conn conn :cfg cfg
                                       :repo-dir dir :store-path dir :server-in-ch server-in}}
                          :stub-md {:proc (make-stub-md-proc conn)}}
                  :conns [[[:server :tx] [:transactor :tx]]
                          [[:transactor :db] [:web :db]]
                          [[:transactor :db] [:stub-md :db]]
                          [[:stub-md :stream] [:web :stream]]
                          [[:stub-md :tx] [:transactor :tx]]
                          [[:stub-md :exits] [:web :exits]]]})]
          (try
            (flow/start g)
            (flow/resume g)
            (let [url (str "http://127.0.0.1:" (:port listener))
                  res (journey/run-journey-gate!
                       {:url url :binary-path bin
                        :viewports [[375 812] [1280 800]] :themes [:dark :light]
                        :require-timeline? true :read-only? false
                        :artifact-dir adir :keep-artifacts? false})]
              (is (:ok? res)
                  (str "Headless WKWebView journey gate failed: "
                       (pr-str (mapv #(select-keys % [:journey :step :width :theme :defects :artifact])
                                     (:failures res)))))
              (is (= 0 (get-in res [:summary :skipped])) "All 6 journeys including time-travel must run")
              (is (= #{:landing :chat :navigation :explorer :dev-mode :time-travel}
                     (set (map :journey (:cells res)))))
              (is (not (fs/exists? adir)) "Temp artifact dir must be cleaned up on pass"))
            (finally
              (try (flow/stop g) (catch Throwable _))
              (try (a/close! server-in) (catch Throwable _))
              (try ((:stop! listener)) (catch Throwable _))
              (when (fs/exists? adir) (store/rm-rf! adir)))))))))
