(ns seon.web
  "The `:web` Flow proc (`:io` workload) — 100% Inside-Out, Non-Blocking HTTP/SSE Edge +
   120fps Incremental `datahike.reads` Walk + Live MCP:
   - ZERO ephemeral UI navigation state in `web-proc`: where the user is (`:seon.user/viewing`,
     `:seon.user/view-mode`, `:seon.user/sidebar-open?`, `:seon.user/expanded-agents`,
     `:seon.user/selected-branch`) lives exclusively in Datahike (`:user/default`).
   - Restoring LMDB restores the user's exact view; any agent can move the user's screen via
     `(user/show-user! ...)` — `:web` reacts via `datahike.reads` and streams SSE patches at 120 Hz.
   - ZERO `<!!`, `>!!`, `alts!!`, `Thread/sleep`, or `flow/ping` inside `web-proc` (`seon.health` verified).
   - Serves live MCP JSON-RPC 2.0 at `POST /mcp` (`seon_repl`, `seon_health`, `seon_test`, `seon_merge`, `seon_status`)."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.data.json :as json]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [ring-chez.adapter :as adapter]
            [seon.render :as render]
            [seon.render.ui :as ui]
            [seon.store :as store]
            [seon.web.css :as css :refer [app-css datastar-js-text sticky-scroll-js]]
            [seon.web.styles :as styles]
            [seon.web.actions :as actions]))

(defn url-enc [^String s] (render/url-enc s))

(defn parse-deep-link
  "Translates optional browser URL deep-links into `:show-user` ops so Datahike stays the sole truth."
  ([^String uri] (parse-deep-link nil uri))
  ([db ^String uri]
   (let [u (or uri "/")]
     (cond
       (or (= u "/") (= u "")) :current
       (= u "/root/debug") {:op :show-user :viewing (or (when db (ui/entry-agent-id db)) :agent/root) :mode :debug}
       (str/starts-with? u "/agent/")
       (let [parts (str/split (subs u 7) #"/" -1)
             slug (java.net.URLDecoder/decode (first parts) "UTF-8")
             aid (ui/slug->agent-id slug)
             debug? (= "debug" (second parts))]
         (when aid
           {:op :show-user :viewing aid :mode (if debug? :debug :workspace)}))
       :else nil))))

(defn- parse-kv-pairs [s]
  (if (str/blank? s)
    {}
    (into {}
          (keep (fn [part]
                  (when (str/includes? part "=")
                    (let [[k v] (str/split part #"=" 2)]
                      (when (seq k)
                        [(keyword (java.net.URLDecoder/decode k "UTF-8"))
                         (java.net.URLDecoder/decode (or v "") "UTF-8")])))))
          (str/split s #"&"))))

(defn- parse-multipart-fields [^String ct ^String body]
  (if-let [[_ boundary] (re-find #"boundary=([^\s;]+)" (str ct))]
    (let [parts (str/split body (re-pattern (str "--" (java.util.regex.Pattern/quote boundary))))]
      (into {}
            (keep (fn [part]
                    (when-let [[_ fname] (re-find #"name=\"([^\"]+)\"" part)]
                      (let [sep (or (str/index-of part "\r\n\r\n") (str/index-of part "\n\n"))
                            offset (if (str/index-of part "\r\n\r\n") 4 2)]
                        (when sep
                          (let [raw-val (subs part (+ sep offset))
                                val (str/replace raw-val #"(?:\r?\n)$" "")]
                            [(keyword fname) val]))))))
            parts))
    {}))

(defn- parse-form-body [req]
  (let [ct (str/lower-case (or (get-in req [:headers "content-type"]) ""))
        body-str (cond
                   (nil? (:body req)) ""
                   (string? (:body req)) (:body req)
                   :else (slurp (:body req)))]
    (cond
      (str/blank? body-str) {}
      (str/includes? ct "multipart/form-data") (parse-multipart-fields ct body-str)
      (str/includes? ct "application/x-www-form-urlencoded") (parse-kv-pairs body-str)
      ;; Datastar sends client UI signals (e.g. `{"showThinking":false}`) as `application/json`
      ;; on non-form `@post(...)` clicks — never treat client signals as HTML form fields!
      (str/includes? ct "application/json") {}
      :else (parse-kv-pairs body-str))))

(defn ^:seon/callable toggle-sidebar! [& args] (apply actions/toggle-sidebar! args))

(defn ^:seon/callable toggle-theme! [& args] (apply actions/toggle-theme! args))

(defn ^:seon/callable toggle-expand! [& args] (apply actions/toggle-expand! args))

(defn ^:seon/callable view-agent! [& args] (apply actions/view-agent! args))

(defn ^:seon/callable set-view-mode! [& args] (apply actions/set-view-mode! args))

(defn ^:seon/callable select-branch! [& args] (apply actions/select-branch! args))

(defn ^:seon/callable switch-tab! [& args] (apply actions/switch-tab! args))

(defn ^:seon/callable power-loss-reboot! [& args] (apply actions/power-loss-reboot! args))

(defn ^:seon/callable run-tests! [& args] (apply actions/run-tests! args))

(defn ^:seon/callable merge-branch! [& args] (apply actions/merge-branch! args))

(defn ^:seon/callable assign-health-fix! [& args] (apply actions/assign-health-fix! args))

(defn ^:seon/callable spawn-agent-form! [& args] (apply actions/spawn-agent-form! args))

(defn ^:seon/callable register-schema-form! [& args] (apply actions/register-schema-form! args))

(defn ^:seon/callable step-canvas-history! [& args] (apply actions/step-canvas-history! args))

(defn ^:seon/callable jump-canvas-tx! [& args] (apply actions/jump-canvas-tx! args))

(defn ^:seon/callable jump-canvas-live! [& args] (apply actions/jump-canvas-live! args))

(defn ^:seon/callable dismiss-feedback! [& args] (apply actions/dismiss-feedback! args))

(defn ^:seon/callable chat-agent-form! [& args] (apply actions/chat-agent-form! args))

(defn ^:seon/callable chat-root-form! [& args] (apply actions/chat-root-form! args))

(defn ^:seon/callable eval-agent-form! [& args] (apply actions/eval-agent-form! args))

(defn ^:seon/callable unpresent-stage! [& args] (apply actions/unpresent-stage! args))

(defn ^:seon/callable pin-ref-form! [& args] (apply actions/pin-ref-form! args))

(defn ^:seon/callable unpin-tile! [& args] (apply actions/unpin-tile! args))

(defn ^:seon/callable invoke-call! [& args] (apply actions/invoke-call! args))

(defn ^:seon.flow/edge call-server-edge! [& args] (apply actions/call-server-edge! args))

(defn ^:seon.flow/edge handle-mcp-jsonrpc! [& args] (apply actions/handle-mcp-jsonrpc! args))

(defn- page-shell [walk]
  (let [header   (get-in walk [:units "seon-header" :html] "")
        sidebar  (get-in walk [:units "seon-sidebar" :html] "")
        view     (get-in walk [:units "seon-view-unit" :html] "")
        stream   (get-in walk [:units "seon-stream-unit" :html] "")
        composer (get-in walk [:units "seon-composer-unit" :html] "")]
    (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
         "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
         "<title>seon</title>"
         "<link rel=\"icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Ccircle cx='8' cy='8' r='6' fill='%23f0b429'/%3E%3C/svg%3E\">"
         (or (get-in walk [:units "seon-css" :html])
         (str "<style id=\"seon-css\">" (styles/full-css) "</style>"))
         "<script type=\"module\" src=\"/datastar.js\"></script>"
         "<script>" sticky-scroll-js "</script>"
         "</head><body>"
         "<div id=\"seon-app\" class=\"app-shell\" data-signals=\"{showThinking: false}\" data-thinking=\"closed\" data-attr:data-thinking=\"$showThinking ? 'open' : 'closed'\">"
         header
         "<div class=\"app-body\">"
         sidebar
         "<div class=\"main-col\">"
         "<main id=\"seon-main\" class=\"stage-scroll\">"
         view
         "</main>"
         "<div id=\"seon-bottom-dock\" class=\"bottom-dock\">"
         stream
         composer
         "</div>"
         "</div></div></div>"
         "<div style=\"display:none\" data-init=\"@get('/events', {retry: 'always', retryMaxCount: Infinity, openWhenHidden: true})\"></div>"
         "</body></html>")))

(defn ^:seon.flow/edge start-http-listener! [port]
  (let [delegate (volatile! nil)
        latest-page (volatile! nil)
        sse-tabs (volatile! {})
        tab-seq (volatile! 0)
        pending (volatile! [])
        flushing? (volatile! false)
        flush-now! (fn []
                     (let [chunks @pending]
                       (vreset! pending [])
                       (when (seq chunks)
                         (let [merged (apply str chunks)]
                           (vswap! sse-tabs
                                   (fn [tabs]
                                     (reduce-kv
                                      (fn [acc tid ch]
                                        (if (a/offer! ch merged)
                                          (assoc acc tid ch)
                                          (do (a/close! ch) acc)))
                                      {}
                                      tabs)))))))
        schedule-flush! (fn []
                          (when (not @flushing?)
                            (vreset! flushing? true)
                            (a/go
                              (a/<! (a/timeout 8))
                              (vreset! flushing? false)
                              (flush-now!))))
        handler (fn [req]
                  (let [m (:request-method req)
                        uri (:uri req)]
                    (cond
                      (= [:get "/events"] [m uri])
                      (let [ch (a/chan 64)
                            tid (vswap! tab-seq inc)]
                        (vswap! sse-tabs assoc tid ch)
                        (when-let [d @delegate]
                          ((:on-new-tab! d) ch))
                        {:status 200
                         :headers {"Content-Type" "text/event-stream"
                                   "Cache-Control" "no-cache"
                                   "Connection" "keep-alive"}
                         :body ch})

                      (and (= m :get) (parse-deep-link uri))
                      (let [dl (parse-deep-link uri)]
                        (if (= :current dl)
                          (if-let [html @latest-page]
                            {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} :body html}
                            {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon booting..."})
                          (if-let [d @delegate]
                            (let [html ((:sync-deep-link! d) dl)]
                              {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} :body html})
                            {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon rebooting..."})))

                      :else
                      (if-let [d @delegate]
                        ((:handle d) req)
                        {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon rebooting..."}))))
        srv (when (not= 0 port)
              (adapter/run-server handler {:port (if (neg? port) 0 port) :host "127.0.0.1"}))
        bound-port (if srv (:port srv) 0)]
    {:server srv
     :port bound-port
     :handler handler
     :set-page! (fn [^String html] (vreset! latest-page html))
     :broadcast! (fn [^String chunk]
                   (when (and (seq @sse-tabs) (not (str/blank? chunk)))
                     (vswap! pending conj chunk)
                     (schedule-flush!)))
     :tab-count (fn [] (count @sse-tabs))
     :set-delegate! (fn [d]
                      (vreset! delegate d)
                      (when d ((:on-delegate! d))))
     :stop! (fn []
              (doseq [[_ ch] @sse-tabs] (a/close! ch))
              (vreset! sse-tabs {})
              (when srv
                (try (adapter/stop-server srv {:drain-timeout-ms 1500}) (catch Throwable _))))}))

(defn- walk->summary [walk]
  (mapv (fn [uid]
          (let [u (get-in walk [:units uid])]
            {:id uid :reads-count (:reads-count u 0) :reads-idx (:reads-idx u)}))
        (remove #(= "seon-stream-unit" %) (:order walk))))

(defn- advance-walk-and-broadcast!
  ([st tx-datoms] (advance-walk-and-broadcast! st tx-datoms nil))
  ([st tx-datoms reason]
   (let [t0 (System/nanoTime)
         db @(:conn st)
         specs (ui/page-unit-specs st)
         {:keys [walk patches render-errors]} (render/step-walk (:walk st) db tx-datoms specs)
         ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
         fault-reason? (and (vector? reason) (#{:fault :retract-fault :resolve-fault} (first reason)))
         st1 (cond-> (-> st (assoc :walk walk) (update :frames inc))
               (not= tx-datoms []) (assoc :walk-summary (walk->summary walk))
               (seq render-errors) (update :errors #(vec (take 20 (concat render-errors %)))))
         st2 (if (and (seq patches) (not fault-reason?) (:server-in-ch st1))
               (let [{:keys [view-mode]} (ui/user-view-state db)
                     fid (hasch/uuid [:perf :slow-render (or view-mode :canvas)])
                     prev (get-in st1 [:perf-alerts fid])
                     persisted? (boolean (first (d/datoms db :avet :seon.health.fault/id fid)))]
                 (cond
                   (and (> ms 40.0) (or (nil? prev) (> ms (* 1.25 (:ms prev 0.0)))))
                   (do (a/put! (:server-in-ch st1)
                               {:req {:op :record-fault
                                      :id fid
                                      :class :slow-render
                                      :severity :warning
                                      :proc :web
                                      :ns 'seon.render.ui
                                      :subject (str "seon.render.ui/render:" (name (or view-mode :canvas)))
                                      :message (str "Slow UI render on :" (name (or view-mode :canvas))
                                                    " view: " ms " ms (> 40 ms frame budget).")}})
                       (assoc-in st1 [:perf-alerts fid] {:ms ms}))

                   (and (<= ms 35.0) (or prev persisted?))
                   (do (a/put! (:server-in-ch st1)
                               {:req {:op :retract-fault :id fid}})
                       (update st1 :perf-alerts dissoc fid))

                   :else st1))
               st1)]
     (when-let [sp (:set-page! st2)]
       (sp (page-shell walk)))
     (when (and (seq patches) (:broadcast! st2))
       ((:broadcast! st2) (apply str patches)))
     st2)))

(defn- launch-async-server-op! [server-in-ch ui-ch {:keys [req slot format-fn]}]
  (let [rch (a/chan 1)]
    (a/put! server-in-ch {:req req :reply-ch rch})
    (a/go
      (let [[ans _] (a/alts! [rch (a/timeout 30000)])]
        (when slot
          (let [val (if format-fn (format-fn (or ans {:ok? false :error :timeout})) (or ans {:ok? false :error :timeout}))]
            (a/put! ui-ch {:ev :action-done :slot slot :val val})))))))

(defn web-proc
  ([] {:params {:port "HTTP port"
                :listener "Persistent HTTP listener map"
                :conn "Read access to store via :main conn"
                :cfg "Pure store cfg"
                :repo-dir "Git repository dir"
                :store-path "LMDB store path"
                :fork-parent-pid "PID of 1-thread Fork Parent"
                :fork-parent-ceiling "Chez GC ceiling of Fork Parent"
                :server-in-ch "Channel to :server"
                :report-chan "Flow report channel"
                :error-chan "Flow error channel"
                :on-power-loss "0-arg callback for Power-Loss reboot"
                :boot-seq "Boot sequence integer"}
       :ins {:db "Commit notices {:commit :tx :tx-data :reason} from :transactor"
             :stream "120fps live token & agent stream deltas from :model"
             :exits "Agent turn/branch completion notices from :agents"
             :snap "Latest live WKWebView viewport screenshot `{:snapshot}` from :browser"}
       :outs {}
       :workload :io
       :doc "100% DB-driven inside-out 120fps Datastar render proc, Reagent /call dispatcher & /mcp endpoint."
       :ping-map-fn (fn [st]
                      {:port (:bound-port st)
                       :tabs (if-let [tc (:tab-count st)] (tc) 0)
                       :frames (:frames st)})})
  ([{:keys [port listener conn cfg repo-dir store-path fork-parent-pid fork-parent-ceiling
            server-in-ch report-chan error-chan on-power-loss boot-seq]}]
   (let [ui-ch (a/chan 128)
         own-listener (when (and (nil? listener) (pos? (or port 0)))
                        (start-http-listener! port))
         eff-listener (or listener own-listener)
         st-init {:conn conn
                  :cfg cfg
                  :repo-dir repo-dir
                  :store-path store-path
                  :fork-parent-pid fork-parent-pid
                  :fork-parent-ceiling fork-parent-ceiling
                  :server-in-ch server-in-ch
                  :ui-ch ui-ch
                  :on-power-loss on-power-loss
                  :boot-seq (or boot-seq 1)
                  :bound-port (:port eff-listener 0)
                  :own-listener own-listener
                  :set-page! (:set-page! eff-listener)
                  :broadcast! (:broadcast! eff-listener)
                  :tab-count (:tab-count eff-listener)
                  :walk (render/empty-walk)
                  :live-streams {}
                  :agent-states {}
                  :branch-renders {}
                  :viewport-screenshot nil
                  :reports []
                  :errors []
                  :frames 0}
         st0 (advance-walk-and-broadcast! st-init nil)]
     (when eff-listener
       ((:set-delegate! eff-listener)
        {:on-delegate! (fn [] (a/put! ui-ch {:ev :broadcast-keyframe}))
         :on-new-tab!  (fn [ch] (a/put! ui-ch {:ev :new-tab :ch ch}))
         :sync-deep-link! (fn [show-op]
                            (call-server-edge! server-in-ch show-op 5000)
                            (let [res (render/step-walk (render/empty-walk) @conn nil
                                                        (ui/page-unit-specs st0))]
                              (page-shell (:walk res))))
         :handle
         (fn [req]
           (let [m (:request-method req)
                 uri (:uri req)]
             (cond
               (and (= m :get) (= uri "/datastar.js"))
               {:status 200
                :headers {"Content-Type" "text/javascript; charset=utf-8" "Cache-Control" "public, max-age=3600"}
                :body @datastar-js-text}

               (and (= m :post) (= uri "/op"))
               (let [body-str (if (string? (:body req)) (:body req) (slurp (:body req)))
                     edn-req (edn/read-string body-str)
                     ans (call-server-edge! server-in-ch edn-req 30000)]
                 {:status 200
                  :headers {"Content-Type" "application/edn; charset=utf-8"}
                  :body (pr-str ans)})

               (and (= m :post) (= uri "/mcp"))
               (let [body-str (if (string? (:body req)) (:body req) (slurp (:body req)))]
                 (handle-mcp-jsonrpc! server-in-ch body-str))

               (and (= m :post) (= uri "/call"))
               (let [qmap (parse-kv-pairs (:query-string req))
                     form-fields (parse-form-body req)
                     call-form (when-let [fs (:form qmap)]
                                 (try (edn/read-string fs) (catch Throwable _ nil)))
                     hkey (:h qmap)]
                 (if (and (nil? hkey)
                          (or (not (and (seq? call-form) (qualified-symbol? (first call-form))))
                              (let [v (resolve (first call-form))]
                                (not (and v (:seon/callable (meta v)))))))
                   {:status 403
                    :headers {"Content-Type" "application/edn; charset=utf-8"}
                    :body (pr-str {:ok? false :error :seon.web/not-callable :form call-form})}
                   (do (a/put! ui-ch {:ev :call :hkey hkey :call-form call-form :form-fields form-fields})
                       {:status 200
                        :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"}
                        :body ""})))

               :else
               {:status 404 :headers {"Content-Type" "text/plain"} :body "Not found"})))}))
     (assoc st0
            ::flow/in-ports (cond-> {:ui ui-ch}
                              report-chan (assoc :report report-chan)
                              error-chan  (assoc :error error-chan)))))
  ([st transition]
   (when (and (= transition ::flow/stop) (:own-listener st))
     ((:stop! (:own-listener st))))
   st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (case cid
       :report
       (let [st1 (update st :reports #(vec (take 30 (cons msg %))))]
         [(advance-walk-and-broadcast! st1 []) nil])

       :error
       (let [st1 (update st :errors #(vec (take 20 (cons msg %))))]
         (binding [*out* *err*]
           (println "[FLOW ERROR]" (pr-str msg))
           (flush))
         [(advance-walk-and-broadcast! st1 []) nil])

       :snap
       (let [st1 (assoc st :viewport-screenshot (:snapshot msg))
             view-mode (:view-mode (ui/user-view-state @(:conn st1)))]
         (if (= view-mode :debug)
           [(advance-walk-and-broadcast! st1 []) nil]
           [st1 nil]))

       :stream
       (let [{:keys [agent-id status text]} msg
             st1 (case status
                   ;; Keep card calmly mounted while tool calls execute — `:turn-idle` tears it down cleanly!
                   :done
                   (if (contains? (:live-streams st) agent-id)
                     (update-in st [:live-streams agent-id]
                                (fn [prev]
                                  (assoc prev :status :running-tools)))
                     st)

                   :thinking
                   (-> st
                       (update-in [:live-streams agent-id]
                                  (fn [prev]
                                    (let [prev-r (str (:reasoning prev ""))]
                                      {:status :thinking
                                       :text ""
                                       :reasoning (if (str/blank? prev-r)
                                                    ""
                                                    (str prev-r "\n\n── next step ──\n"))})))
                       (assoc-in [:agent-states agent-id] :active))

                   :reasoning-token
                   (-> st
                       (update-in [:live-streams agent-id]
                                  (fn [prev]
                                    {:status :reasoning
                                     :text (or (:text prev) "")
                                     :reasoning (str (or (:reasoning prev) "") (or text ""))}))
                       (cond-> (not= :active (get-in st [:agent-states agent-id]))
                         (assoc-in [:agent-states agent-id] :active)))

                   ;; `:token` or other live status delta — keeps `:agent-states` unchanged (`=`) so 0 DB seeks!
                   (-> st
                       (update-in [:live-streams agent-id]
                                  (fn [prev]
                                    {:status status
                                     :reasoning (or (:reasoning prev) "")
                                     :text (str (or (:text prev) "") (or text ""))}))
                       (cond-> (not= :active (get-in st [:agent-states agent-id]))
                         (assoc-in [:agent-states agent-id] :active))))]
         [(advance-walk-and-broadcast! st1 []) nil])

       :exits
       (let [aid (:agent-id msg)]
         (case (:ev msg)
           (:forked :turn-busy)
           (let [st1 (assoc-in st [:agent-states aid] :active)]
             [(advance-walk-and-broadcast! st1 []) nil])

           :turn-idle
           (let [st1 (-> st
                         (update :agent-states dissoc aid)
                         (update :live-streams dissoc aid))]
             [(advance-walk-and-broadcast! st1 nil) nil])

           :exited
           (let [st1 (-> st
                         (update :agent-states dissoc aid)
                         (update :live-streams dissoc aid))]
             [(advance-walk-and-broadcast! st1 []) nil])

           :branch-render
           (let [st1 (assoc-in st [:branch-renders aid]
                               (select-keys msg [:branch :branch-tx :render-fn :hiccup :error]))]
             [(advance-walk-and-broadcast! st1 nil) nil])

           :branch-moved
           [(advance-walk-and-broadcast! st nil) nil]

           [st nil]))

       :db
       (let [reason (:reason msg)
             tx-ds (:tx-data msg)
             code-or-merge? (or (= :merge reason)
                                (and (vector? reason) (= :merge (first reason)))
                                (some #(#{:seon.code.form/src :seon.agent/render-fn} (:a %)) tx-ds))]
         [(advance-walk-and-broadcast! st (if code-or-merge? nil tx-ds) reason) nil])

       :ui
       (case (:ev msg)
         :new-tab
         (let [ch (:ch msg)
               kf (render/datastar-patch-event (render/compose-keyframe (:walk st)))]
           (a/offer! ch kf)
           [st nil])

         :broadcast-keyframe
         (let [st1 (advance-walk-and-broadcast! st nil)
               kf (render/datastar-patch-event (render/compose-keyframe (:walk st1)))]
           (when (:broadcast! st1)
             ((:broadcast! st1) kf))
           [st1 nil])

         :action-done
         (let [{:keys [slot val]} msg
               st1 (assoc st slot val)]
           [(advance-walk-and-broadcast! st1 nil) nil])

         :call
         (let [res (invoke-call! st msg)]
           (if-not (:ok? res)
             (let [st1 (update st :errors #(vec (take 20 (cons (dissoc res :st) %))))]
               [(advance-walk-and-broadcast! st1 []) nil])
             (let [st1 (:st res)
                   _ (when-let [async-spec (:async res)]
                       (launch-async-server-op! (:server-in-ch st1) (:ui-ch st1) async-spec))]
               [(advance-walk-and-broadcast! st1 []) nil])))

         [st nil])))))

(defn ^:seon/callable toggle-vision! [& args] (apply actions/toggle-vision! args))