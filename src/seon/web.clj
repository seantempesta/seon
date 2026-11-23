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
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [ring-chez.adapter :as adapter]
            [seon.render :as render]
            [seon.render.ui :as ui]
            [seon.web.css :as css :refer [app-css datastar-js-text sticky-scroll-js viewport-sync-js]]
            [seon.web.styles :as styles]
            [seon.web.actions :as actions]
            [seon.render.data :as rdata]
            [seon.render.walk :as rwalk]))

(defn parse-deep-link
  "Translates optional browser URL deep-links into `:show-user` ops so Datahike stays the sole truth."
  {:malli/schema [:function [:=> [:cat :string] :any] [:=> [:cat :any :string] :any]]}
  ([^String uri] (parse-deep-link nil uri))
  ([db ^String uri]
   (let [u (or uri "/")]
     (cond
       (or (= u "/") (= u "")) :current
       (= u "/root/debug") {:op :show-user :viewing (or (when db (ui/entry-agent-id db)) :agent/root) :mode :debug}
       (str/starts-with? u "/agent/")
       (let [parts (str/split (subs u 7) #"/" -1)
             slug (java.net.URLDecoder/decode (first parts) "UTF-8")
             aid (rdata/slug->agent-id slug)
             debug? (= "debug" (second parts))]
         (when aid
           {:op :show-user :viewing aid :mode (if debug? :debug :workspace)}))
       :else nil))))

(defn- parse-kv-pairs [& args] (apply actions/parse-kv-pairs args))

(defn- parse-form-body [& args] (apply actions/parse-form-body args))

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

(defn ^:seon/callable eval-agent-form! [& args] (apply actions/eval-agent-form! args))

(defn ^:seon/callable pin-ref-form! [& args] (apply actions/pin-ref-form! args))

(defn ^:seon/callable unpin-tile! [& args] (apply actions/unpin-tile! args))

(defn ^:seon/callable invoke-call! [& args] (apply actions/invoke-call! args))

(defn ^:seon.flow/edge call-server-edge! [& args] (apply actions/call-server-edge! args))

(defn ^:seon.flow/edge handle-mcp-jsonrpc! [& args] (apply actions/handle-mcp-jsonrpc! args))

(defn- page-shell
([walk] (page-shell walk nil))
([walk db]
 (let [header   (get-in walk [:units "seon-header" :html] "")
       sidebar  (get-in walk [:units "seon-sidebar" :html] "")
       view     (rwalk/compose-view-html walk db)
       stream   (get-in walk [:units "seon-stream-unit" :html] "")
       composer (get-in walk [:units "seon-composer-unit" :html] "")]
   (str "<!DOCTYPE html><html><head><meta charset=\"utf-8\">"
        "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
        "<title>seon</title>"
        "<link rel=\"icon\" href=\"data:image/svg+xml,%3Csvg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 16 16'%3E%3Ccircle cx='8' cy='8' r='6' fill='%23f0b429'/%3E%3C/svg%3E\">"
        (or (get-in walk [:units "seon-css" :html])
            (str "<style id=\"seon-css\">" ((var-get #'styles/full-css)) "</style>"))
        "<script type=\"module\" src=\"/datastar.js\"></script>"
        "<script>" sticky-scroll-js "</script>"
        "<script>" viewport-sync-js "</script>"
        "</head><body>"
        "<div id=\"seon-app\" class=\"app-shell\" data-signals=\"{showThinking: false, statusOpen: false, aggRate: ''}\" data-thinking=\"closed\" data-attr:data-thinking=\"$showThinking ? 'open' : 'closed'\" data-status=\"closed\" data-attr:data-status=\"$statusOpen ? 'open' : 'closed'\">"
        header
        "<div class=\"app-body\">"
        sidebar
        "<main id=\"seon-main\" class=\"stage-scroll\">"
        view
        "</main>"
        "<div id=\"seon-bottom-dock\" class=\"bottom-dock\">"
        composer
        "</div>"
        stream
        "</div></div>"
        "<div style=\"display:none\" data-init=\"@get('/events', {retry: 'always', retryMaxCount: Infinity, openWhenHidden: true})\"></div>"
        "</body></html>"))))

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
         vs (ui/user-view-state db)
         view-sig [(:view-mode vs) (:viewing-id vs) (:as-of-tx vs) (:viewport-w vs)]
         use-vt? (rwalk/discrete-view-transition? (:last-view-sig st) view-sig reason)
         specs (ui/page-unit-specs st)
         {:keys [walk patches render-errors]} (render/step-walk (:walk st) db tx-datoms specs
                                                                (when use-vt? {:use-view-transition? true :vt-unit-ids #{"seon-view-unit"}}))
         patches (rwalk/effective-patches (:walk st) walk patches db use-vt?)
         agg-rate (ui/aggregate-stream-rate (:live-streams st))
         sig-patch (when (not= agg-rate (:last-agg-rate st "")) (render/datastar-patch-signals {:aggRate agg-rate}))
         out-chunks (cond-> (vec patches) sig-patch (conj sig-patch))
         ms (/ (Math/round (/ (double (- (System/nanoTime) t0)) 1e4)) 100.0)
         fault-reason? (and (vector? reason) (#{:fault :retract-fault :resolve-fault} (first reason)))
         st1 (cond-> (-> st (assoc :walk walk :last-view-sig view-sig :last-agg-rate agg-rate) (update :frames inc))
               (not= tx-datoms []) (assoc :walk-summary (walk->summary walk))
               (seq render-errors) (update :errors #(vec (take 20 (concat render-errors %)))))
         st2 (if (and (seq patches) (not fault-reason?) (:server-in-ch st1))
               (let [view-mode (:view-mode vs)
                     fid (hasch/uuid [:perf :slow-render (or view-mode :canvas)])
                     prev (get-in st1 [:perf-alerts fid])
                     persisted? (boolean (first (d/datoms db :avet :seon.error/id fid)))]
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
       (sp (page-shell walk db)))
     (when (and (seq out-chunks) (:broadcast! st2))
       ((:broadcast! st2) (apply str out-chunks)))
     st2)))

(defn- launch-async-server-op! [server-in-ch ui-ch {:keys [req slot format-fn]}]
  (let [rch (a/chan 1)]
    (a/put! server-in-ch {:req req :reply-ch rch})
    (a/go
      (let [[ans _] (a/alts! [rch (a/timeout 30000)])]
        (when slot
          (let [val (if format-fn (format-fn (or ans {:ok? false :error :timeout})) (or ans {:ok? false :error :timeout}))]
            (a/put! ui-ch {:ev :action-done :slot slot :val val})))))))

(def http-routes
  "Data-driven HTTP route table for the `web-proc` Ring edge. Pure data: each entry maps a
   `:route/id` dispatch key to an exact `:route/method` (`:get`/`:post`) & `:route/path`
   (`:uri`) match. Order matters — the first entry whose method+path both match wins."
  [{:route/id :datastar-js :route/method :get  :route/path "/datastar.js"}
   {:route/id :op          :route/method :post :route/path "/op"}
   {:route/id :mcp         :route/method :post :route/path "/mcp"}
   {:route/id :call        :route/method :post :route/path "/call"}])

(defn match-http-route
  "Pure HTTP route matcher — returns the first entry of `routes` (default `http-routes`)
   whose `:route/method` & `:route/path` match `req`, or nil when unmatched (404)."
  {:malli/schema [:=> {:examples [[[{:request-method :get :uri "/datastar.js"}]
                                   {:route/id :datastar-js :route/method :get :route/path "/datastar.js"}]
                                  [[{:request-method :post :uri "/mcp"}]
                                   {:route/id :mcp :route/method :post :route/path "/mcp"}]
                                  [[{:request-method :get :uri "/missing"}] nil]]}
                  [:cat [:map [:request-method [:enum :get :post :put :delete]] [:uri :string]]]
                  [:maybe [:map [:route/id :keyword]]]]
   :cost/ticks 5000
   :cost/bytes 4096}
  ([req] (match-http-route req http-routes))
  ([req routes]
   (let [m (:request-method req) uri (:uri req)]
     (some (fn [r] (when (and (= m (:route/method r)) (= uri (:route/path r))) r)) routes))))

(defn- req-body-str
  "Ring request body as a string (handles String, InputStream & nil bodies)."
  [req]
  (cond (nil? (:body req)) ""
        (string? (:body req)) (:body req)
        :else (slurp (:body req))))

(defn- not-callable-form?
  "True when `call-form` is not `(qualified-sym & args)` targeting a `^:seon/callable` var."
  [call-form]
  (or (not (and (seq? call-form) (qualified-symbol? (first call-form))))
      (let [v (resolve (first call-form))]
        (not (and v (:seon/callable (meta v)))))))

(defn- handle-datastar-js-route
  "`GET /datastar.js` — the Datastar runtime, long-cached."
  [_ctx _req]
  {:status 200
   :headers {"Content-Type" "text/javascript; charset=utf-8"
             "Cache-Control" "public, max-age=3600"}
   :body @datastar-js-text})

(defn- handle-op-route
  "`POST /op` — one EDN op form round-tripped through the `:server` edge."
  [{:keys [server-in-ch]} req]
  (let [op-req (edn/read-string (req-body-str req))
      timeout-ms (if (= :test (:op op-req)) 120000 30000)
      ans (call-server-edge! server-in-ch op-req timeout-ms)]
  {:status 200,
   :headers {"Content-Type" "application/edn; charset=utf-8"},
   :body (pr-str ans)}))

(defn- handle-mcp-route
"`POST /mcp` — live MCP JSON-RPC 2.0 endpoint."
[{:keys [server-in-ch conn]} req]
(handle-mcp-jsonrpc! server-in-ch (req-body-str req) (when conn @conn)))

(defn- handle-call-route
  "`POST /call` — hand a `?h=` Reagent closure or a stateless callable form to the proc
   loop (CQRS `204 No Content`), or 403 when the form is not `^:seon/callable`."
  [{:keys [ui-ch]} req]
  (let [qmap (parse-kv-pairs (:query-string req))
        call-form (when-let [fs (:form qmap)]
                    (try (edn/read-string fs) (catch Throwable _ nil)))
        hkey (:h qmap)]
    (if (and (nil? hkey) (not-callable-form? call-form))
      {:status 403
       :headers {"Content-Type" "application/edn; charset=utf-8"}
       :body (pr-str {:ok? false :error :seon.web/not-callable :form call-form})}
      (do (a/put! ui-ch {:ev :call
                         :hkey hkey
                         :call-form call-form
                         :form-fields (parse-form-body req)})
          {:status 204
           :headers {}
           :body ""}))))

(def ^:private http-route-handlers
  "Impure per-route Ring handlers keyed by `:route/id` from `http-routes`."
  {:datastar-js handle-datastar-js-route
   :op handle-op-route
   :mcp handle-mcp-route
   :call handle-call-route})

(def http-route-handler-ids
  "The `:route/id`s covered by `http-route-handlers`, as data so tests can cross-check it
   against `http-routes` (a route with no handler would 500 at runtime)."
  (set (keys http-route-handlers)))

(defn ^:seon.flow/edge handle-http-request!
  "Ring handler for `web-proc`: matches `req` against `http-routes` and dispatches to the matching
   per-route handler, else 404. `ctx` carries `:ui-ch` and `:server-in-ch`."
  {:malli/schema [:=> [:cat [:map [:ui-ch :seon.host/chan] [:server-in-ch :seon.host/chan]] :map] :map]}
  [ctx req]
  (if-let [route (match-http-route req)]
    ((http-route-handlers (:route/id route)) ctx req)
    {:status 404 :headers {"Content-Type" "text/plain"} :body "Not found"}))

(defn- viewing-affected? [st aid]
  (let [{:keys [viewing-id view-mode]} (when-let [c (:conn st)] (ui/user-view-state @c))]
    (or (= view-mode :debug) (= aid viewing-id))))

(defn- handle-exit-msg [st msg]
  (let [{:keys [ev agent-id phase]} msg
        aid agent-id]
    (case ev
      (:forked :turn-busy)
      (let [now-ms (System/currentTimeMillis)
            st1 (cond-> (assoc-in st [:agent-states aid] :active)
                  (and (= :turn-busy ev) (not (contains? (:live-streams st) aid)))
                  (assoc-in [:live-streams aid]
                            {:status :working :phase (or phase :working)
                             :started-ms now-ms :last-ms now-ms :step-chars 0 :chars 0 :reasoning "" :text ""})
                  (and phase (contains? (:live-streams st) aid))
                  (assoc-in [:live-streams aid :phase] phase))]
        (advance-walk-and-broadcast! st1 []))

      (:turn-idle :exited)
      (let [st1 (-> st (update :agent-states dissoc aid) (update :live-streams dissoc aid))]
        (advance-walk-and-broadcast! st1 (if (and (= :turn-idle ev) (viewing-affected? st1 aid)) nil [])))

      :branch-moved
      (let [st1 (if (and phase (contains? (:live-streams st) aid))
                  (assoc-in st [:live-streams aid :phase] phase)
                  st)]
        (cond (viewing-affected? st1 aid) (advance-walk-and-broadcast! st1 nil)
              (not= st1 st) (advance-walk-and-broadcast! st1 [])
              :else st1))

      st)))

(defn- handle-stream-msg [st msg]
  (let [{:keys [agent-id status text phase context-tokens max-context-tokens]} msg
        now-ms (System/currentTimeMillis)
        now-ns (System/nanoTime)
        prev (get-in st [:live-streams agent-id])
        prev-phase (:phase prev)
        tstr (str (or text ""))
        dlen (long (count tstr))
        started (long (or (:started-ms prev) now-ms))
        chars (long (or (:chars prev) 0))
        step-chars (long (or (:step-chars prev) 0))
        ctx-toks (long (or (when (and (number? context-tokens) (pos? (long context-tokens))) context-tokens)
                           (:context-tokens prev) 0))
        max-ctx (or (when (and (number? max-context-tokens) (pos? (long max-context-tokens))) (long max-context-tokens))
                    (:max-context-tokens prev))
        with-ctx (fn [m]
                   (when m
                     (cond-> m
                       (pos? ctx-toks) (assoc :context-tokens ctx-toks)
                       max-ctx (assoc :max-context-tokens max-ctx))))
        entry (with-ctx
              (case status
                :ctx-meta
                (or prev {:status :thinking :phase :thinking :started-ms now-ms :last-ms now-ms :step-chars 0 :chars 0 :text "" :reasoning ""})

                :done
                (when prev
                  (assoc prev :status :running-tools :phase (or phase :tools) :last-ms now-ms :step-chars 0))

                :thinking
                (let [pr (str (:reasoning prev ""))]
                  {:status :thinking :phase (or phase :thinking)
                   :started-ms now-ms :last-ms now-ms :step-chars 0 :chars chars :text ""
                   :reasoning (if (str/blank? pr) "" (str pr "\n\n── next step ──\n"))})

                :reasoning-token
                {:status :reasoning :phase (or phase :thinking)
                 :started-ms started :last-ms now-ms :step-chars (+ step-chars dlen) :chars (+ chars dlen)
                 :text (or (:text prev) "") :reasoning (str (or (:reasoning prev) "") tstr)}

                (:tools :eval :testing :merging :working)
                (assoc (or prev {:reasoning "" :text "" :chars 0 :step-chars 0})
                       :status status :phase (or phase (if (= status :eval) :tools status))
                       :started-ms started :last-ms now-ms)

                {:status status :phase (or phase :writing)
                 :started-ms started :last-ms now-ms :step-chars (+ step-chars dlen) :chars (+ chars dlen)
                 :reasoning (or (:reasoning prev) "") :text (str (or (:text prev) "") tstr)}))
        st1 (if entry
              (cond-> (assoc-in st [:live-streams agent-id] entry)
                (and (not= status :done) (not= :active (get-in st [:agent-states agent-id])))
                (assoc-in [:agent-states agent-id] :active))
              st)
        delta-only? (and (or (= status :token) (= status :reasoning-token))
                         (= prev-phase (:phase entry))
                         (= (:agent-states st) (:agent-states st1)))
        due? (or (not delta-only?) (>= (- now-ns (long (:last-stream-emit-ns st 0))) 80000000))]
    (if due?
      (-> st1 (assoc :last-stream-emit-ns now-ns) (advance-walk-and-broadcast! [] :stream))
      st1)))

(defn web-proc
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map] [:=> [:cat :map :any] :map] [:=> [:cat :map :any :any] [:tuple :map :any]]]}
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
       :doc "100% DB-driven inside-out Datastar render proc, Reagent /call dispatcher & /mcp endpoint."
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
                 :last-stream-emit-ns 0 :last-view-sig nil :last-agg-rate ""
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
                              (page-shell (:walk res) @conn)))
         :handle
         (fn [req] (handle-http-request! {:ui-ch ui-ch :server-in-ch server-in-ch :conn conn} req))}))
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
       [(handle-stream-msg st msg) nil]

       :exits
       [(handle-exit-msg st msg) nil]

       :db
       (let [reason (:reason msg)
             tx-ds (:tx-data msg)
             code-or-merge? (or (= :merge reason)
                                (and (vector? reason) (= :merge (first reason)))
                                (some #(= :seon.code.form/src (:a %)) tx-ds))]
         [(advance-walk-and-broadcast! st (if code-or-merge? nil tx-ds) reason) nil])

       :ui
       (case (:ev msg)
         :new-tab
         (let [ch (:ch msg)
               kf (render/datastar-patch-event (rwalk/compose-keyframe-html (:walk st) (when-let [c (:conn st)] @c)))
               ar (or (:last-agg-rate st) "")]
           (a/offer! ch (if (str/blank? ar) kf (str kf (render/datastar-patch-signals {:aggRate ar}))))
           [st nil])

         :broadcast-keyframe
         (let [st1 (advance-walk-and-broadcast! st nil)
               kf (str (render/datastar-patch-event (rwalk/compose-keyframe-html (:walk st1) (when-let [c (:conn st1)] @c)))
                       (render/datastar-patch-signals {:aggRate (or (:last-agg-rate st1) "")}))]
           (when (:broadcast! st1) ((:broadcast! st1) kf))
           [st1 nil])

         :action-done
         (let [{:keys [slot val]} msg
               st1 (assoc st slot val)]
           [(advance-walk-and-broadcast! st1 nil :action-done) nil])

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

(defn url-enc
  "Percent-encode `s` for an HTTP query-string (`?form=...` / `?h=...`). Delegates to `seon.render/url-enc` (java.net.URLEncoder UTF-8)."
  {:malli/schema [:=> {:examples [[["a b"] "a+b"]]} [:cat :string] :string]
   :cost/ticks 6000
   :cost/bytes 4096}
  [s]
  (render/url-enc s))

(defn ^:seon/callable pin-to-agent!
  {:malli/schema [:function [:=> [:cat [:map [:conn :seon.host/conn]] :keyword [:vector :any]] :map] [:=> [:cat [:map [:conn :seon.host/conn]] :keyword [:vector :any] :boolean] :map]]}
  [& args] (apply actions/pin-to-agent! args))

(defn ^:seon/callable set-viewport!
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] number? number?] :map]}
  [& args]
  (apply actions/set-viewport! args))