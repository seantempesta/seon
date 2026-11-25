(ns seon.web
  "The `:web` Flow proc (`:io` workload): non-blocking HTTP/SSE edge, incremental `datahike.reads` walk, `/call` & `/mcp` dispatch."
  (:require [clojure.core.async :as a]
            [clojure.core.async.flow :as flow]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [hasch.core :as hasch]
            [ring-chez.adapter :as adapter]
            [seon.render :as render]
            [seon.render.ui :as ui]
            [seon.web.css :as css :refer [datastar-js-text sticky-scroll-js viewport-sync-js]]
            [seon.web.styles :as styles]
            [seon.web.actions :as actions]
            [seon.render.data :as rdata]
            [seon.render.walk :as rwalk]
            [seon.render.surface.entity :as sentity]
            [seon.web.router :as router]
            [jolt.io-poller :as poller]
            [ring-chez.middleware.compress :as compress]
            [ring-chez.sse :as rsse]
            [seon.web.sse :as sse]))

(defn parse-deep-link
  "Translates browser URL deep-links via `seon.web.router/path->show-user-op`."
  {:malli/schema [:function [:=> [:cat :string] :any] [:=> [:cat :any :string] :any]]
   :cost/ticks 1500000}
  ([^String uri] (parse-deep-link nil uri))
  ([db ^String uri]
   (router/path->show-user-op db (or uri "/"))))

(defn- parse-kv-pairs [& args] (apply actions/parse-kv-pairs args))

(defn- parse-form-body [& args] (apply actions/parse-form-body args))

(defn ^:seon/callable toggle-sidebar! [& args] (apply actions/toggle-sidebar! args))

(defn ^:seon/callable toggle-theme! [& args] (apply actions/toggle-theme! args))

(defn ^:seon/callable toggle-expand! [& args] (apply actions/toggle-expand! args))

(defn ^:seon/callable view-agent! [& args] (apply actions/view-agent! args))

(defn ^:seon/callable set-view-mode! [& args] (apply actions/set-view-mode! args))

(defn ^:seon/callable select-branch! [& args] (apply actions/select-branch! args))

(defn ^:seon/callable switch-tab! [& args] (apply actions/switch-tab! args))

(defn ^:seon/callable inspect-entity! [& args] (apply actions/inspect-entity! args))

(defn ^:seon/callable inspect-back! [& args] (apply actions/inspect-back! args))

(defn ^:seon/callable inspect-forward! [& args] (apply actions/inspect-forward! args))

(defn ^:seon/callable inspect-close! [& args] (apply actions/inspect-close! args))

(defn ^:seon/callable jump-inspect-trail! [& args] (apply actions/jump-inspect-trail! args))

(defn ^:seon/callable update-lab-prefs! [& args] (apply actions/update-lab-prefs! args))

(defn ^:seon/callable update-lab-weights-form! [& args] (apply actions/update-lab-weights-form! args))

(defn ^:seon/callable toggle-lab-rule! [& args] (apply actions/toggle-lab-rule! args))

(defn ^:seon/callable reset-lab-prefs! [& args] (apply actions/reset-lab-prefs! args))

(defn ^:seon/callable semantic-search-form! [& args] (apply actions/semantic-search-form! args))

(defn ^:seon/callable refresh-vector-status! [& args] (apply actions/refresh-vector-status! args))

(defn ^:seon/callable power-loss-reboot! [& args] (apply actions/power-loss-reboot! args))

(defn ^:seon/callable run-tests! [& args] (apply actions/run-tests! args))

(defn ^:seon/callable merge-branch! [& args] (apply actions/merge-branch! args))

(defn ^:seon/callable assign-health-fix! [& args] (apply actions/assign-health-fix! args))

(defn ^:seon/callable spawn-agent-form! [& args] (apply actions/spawn-agent-form! args))

(defn ^:seon/callable spawn-on-entity-form! [& args] (apply actions/spawn-on-entity-form! args))

(defn ^:seon/callable register-schema-form! [& args] (apply actions/register-schema-form! args))

(defn ^:seon/callable step-canvas-history! [& args] (apply actions/step-canvas-history! args))

(defn ^:seon/callable jump-canvas-tx! [& args] (apply actions/jump-canvas-tx! args))

(defn ^:seon/callable jump-canvas-live! [& args] (apply actions/jump-canvas-live! args))

(defn ^:seon/callable dismiss-feedback! [& args] (apply actions/dismiss-feedback! args))

(defn ^:seon/callable chat-agent-form! [& args] (apply actions/chat-agent-form! args))

(defn ^:seon/callable eval-agent-form! [& args] (apply actions/eval-agent-form! args))

(defn ^:seon/callable pin-ref-form! [& args] (apply actions/pin-ref-form! args))

(defn ^:seon/callable unpin-tile! [& args] (apply actions/unpin-tile! args))

(defn ^:seon/callable toggle-vision! [& args] (apply actions/toggle-vision! args))

(defn invoke-call! [& args] (apply actions/invoke-call! args))

(defn ^:seon.flow/edge call-server-edge! [& args] (apply actions/call-server-edge! args))

(defn ^:seon.flow/edge handle-mcp-jsonrpc! [& args] (apply actions/handle-mcp-jsonrpc! args))

(defn- page-shell [& args] (apply sse/page-shell args))

(defn ^:seon.flow/edge start-http-listener!
  {:malli/schema [:=> [:cat :int] :map] :seon.gen/skip true}
  [port]
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
                                      {} tabs)))))))
        schedule-flush! (fn []
                          (when (not @flushing?)
                            (vreset! flushing? true)
                            (a/go (a/<! (a/timeout 8)) (vreset! flushing? false) (flush-now!))))
        handler (fn [req]
                  (let [m (:request-method req) uri (:uri req) qs (:query-string req)
                        full-uri (if (str/blank? qs) uri (str uri "?" qs))
                        d @delegate
                        dl (when (= m :get) (parse-deep-link (when-let [df (:db-fn d)] (df)) full-uri))]
                    (cond
                      (= [:get "/events"] [m uri])
                      (let [ch (a/chan 64) tid (vswap! tab-seq inc)]
                        (vswap! sse-tabs assoc tid ch)
                        (when d ((:on-new-tab! d) ch))
                        (rsse/event-response ch))
                      dl
                      (if d
                        {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
                         :body ((:sync-deep-link! d) dl)}
                        (if-let [html (when (or (= uri "/") (= uri "")) @latest-page)]
                          {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} :body html}
                          {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon booting..."}))
                      :else
                      (if d
                        ((:handle d) req)
                        {:status 503 :headers {"Content-Type" "text/plain"} :body "Seon rebooting..."}))))
        handler (compress/wrap-compress handler)
        srv (when (not= 0 port)
              (adapter/run-server handler
                                  (cond-> {:port (if (neg? port) 0 port) :host "127.0.0.1"}
                                    (poller/available?) (assoc :strategy :fibers))))]
    {:server srv :port (if srv (:port srv) 0) :handler handler
     :set-page! (fn [^String html] (vreset! latest-page html))
     :broadcast! (fn [^String chunk]
                   (when (and (seq @sse-tabs) (not (str/blank? chunk)))
                     (vswap! pending conj chunk) (schedule-flush!)))
     :tab-count (fn [] (count @sse-tabs))
     :set-delegate! (fn [d] (vreset! delegate d) (when d ((:on-delegate! d))))
     :stop! (fn []
              (doseq [[_ ch] @sse-tabs] (a/close! ch))
              (vreset! sse-tabs {})
              (when srv (try (adapter/stop-server srv {:drain-timeout-ms 1500}) (catch Throwable _))))}))

(defn- advance-walk-and-broadcast! [& args] (apply sse/advance-walk-and-broadcast! args))

(defn- launch-async-server-op! [server-in-ch ui-ch {:keys [req slot format-fn]}]
  (let [rch (a/chan 1)]
    (a/put! server-in-ch {:req req :reply-ch rch})
    (a/go
      (let [[ans _] (a/alts! [rch (a/timeout (router/op-timeout-ms req))])]
        (when slot
          (let [val (if format-fn (format-fn (or ans {:ok? false :error :timeout})) (or ans {:ok? false :error :timeout}))]
            (a/put! ui-ch {:ev :action-done :slot slot :val val})))))))

(def http-routes
  "Data-driven HTTP route table for the `web-proc` Ring edge."
  [{:route/id :datastar-js :route/method :get  :route/path "/datastar.js"}
   {:route/id :op          :route/method :post :route/path "/op"}
   {:route/id :mcp         :route/method :post :route/path "/mcp"}
   {:route/id :call        :route/method :post :route/path "/call"}
   {:route/id :preview     :route/method :get  :route/path "/preview"}])

(defn match-http-route
  {:malli/schema [:=> [:cat [:map [:request-method [:enum :get :post :put :delete]] [:uri :string]]]
                  [:maybe [:map [:route/id :keyword]]]]
   :cost/ticks 1000000 :cost/bytes 262144}
  ([req] (router/match-http-route nil req))
  ([req routes] (router/match-http-route nil req routes)))

(defn- req-body-str [req]
  (cond (nil? (:body req)) "" (string? (:body req)) (:body req) :else (slurp (:body req))))

(defn- not-callable-form? [call-form]
  (or (not (and (seq? call-form) (qualified-symbol? (first call-form))))
      (let [v (resolve (first call-form))] (not (and v (:seon/callable (meta v)))))))

(defn- handle-datastar-js-route [_ctx _req]
  {:status 200 :headers {"Content-Type" "text/javascript; charset=utf-8" "Cache-Control" "public, max-age=3600"}
   :body @datastar-js-text})

(defn- ^:seon.flow/edge handle-op-route [{:keys [server-in-ch]} req]
  (let [raw (req-body-str req)
        op-req (try (binding [*read-eval* false] (edn/read-string raw)) (catch Throwable _ ::bad-edn))]
    (if (or (= ::bad-edn op-req) (not (map? op-req)))
      {:status 400 :headers {"Content-Type" "application/edn; charset=utf-8"}
       :body (pr-str {:ok? false :error :invalid-edn})}
      {:status 200 :headers {"Content-Type" "application/edn; charset=utf-8"}
       :body (pr-str (call-server-edge! server-in-ch op-req (router/op-timeout-ms op-req)))})))

(defn- handle-mcp-route [{:keys [server-in-ch conn]} req]
  (handle-mcp-jsonrpc! server-in-ch (req-body-str req) (when conn @conn)))

(defn- handle-call-route [{:keys [ui-ch]} req]
  (let [qmap (parse-kv-pairs (:query-string req))
        call-form (when-let [fs (:form qmap)] (try (edn/read-string fs) (catch Throwable _ nil)))
        hkey (:h qmap)]
    (if (and (nil? hkey) (not-callable-form? call-form))
      {:status 403 :headers {"Content-Type" "application/edn; charset=utf-8"}
       :body (pr-str {:ok? false :error :seon.web/not-callable :form call-form})}
      (do (a/put! ui-ch {:ev :call :hkey hkey :call-form call-form :form-fields (parse-form-body req)})
          {:status 204 :headers {} :body ""}))))

(defn- handle-exit-msg [& args] (apply sse/handle-exit-msg args))

(defn- handle-stream-msg [& args] (apply sse/handle-stream-msg args))

(defn url-enc
  {:malli/schema [:=> [:cat :string] :string] :cost/ticks 6000 :cost/bytes 4096}
  [s] (render/url-enc s))

(defn ^:seon/callable pin-to-agent!
  {:malli/schema [:function [:=> [:cat [:map [:conn :seon.host/conn]] :keyword [:vector :any]] :map]
                  [:=> [:cat [:map [:conn :seon.host/conn]] :keyword [:vector :any] :boolean] :map]]}
  [& args] (apply actions/pin-to-agent! args))

(defn ^:seon/callable set-viewport!
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]] number? number?] :map]}
  [& args] (apply actions/set-viewport! args))

(defn ^:seon/callable set-explain-lens!
  {:malli/schema [:function [:=> [:cat :map :boolean] :map] [:=> [:cat :map :boolean :any] :map]]}
  [& args] (apply actions/set-explain-lens! args))

(defn- ^:seon.flow/edge handle-preview-route [{:keys [conn]} req]
(let [qmap (parse-kv-pairs (:query-string req))
      raw (:ref qmap)
      parsed (when-not (str/blank? raw) (try (edn/read-string raw) (catch Throwable _ nil)))]
  (if-not (and conn parsed)
    {:status 400 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
     :body "<div class=\"hovercard-body mono tiny muted\">Missing or invalid ref.</div>"}
    (let [mdb @conn
          vs (rdata/user-view-state mdb)
          aid (:viewing-id vs)
          dh (or (:schema mdb) (d/schema mdb) {})
          br (or (when (and aid (dh :seon.agent/id))
                   (:seon.agent/branch (d/pull mdb '[:seon.agent/branch] [:seon.agent/id aid])))
                 :main)
          bdb (rdata/branch-db-for mdb conn br)
          res (sentity/render-entity-preview-html mdb bdb parsed)]
      {:status (long (:status res 200))
       :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"}
       :body (:html res)}))))

(def ^:private http-route-handlers
  {:datastar-js handle-datastar-js-route :op handle-op-route :mcp handle-mcp-route :call handle-call-route :preview handle-preview-route})

(def http-route-handler-ids
  "The `:route/id`s covered by `http-route-handlers`."
  (set (keys http-route-handlers)))

(defn ^:seon.flow/edge handle-http-request!
  "Ring handler for `web-proc`."
  {:malli/schema [:=> [:cat [:map [:ui-ch :seon.host/chan] [:server-in-ch :seon.host/chan]] :map] :map]}
  [ctx req]
  (let [db (when-let [c (:conn ctx)] @c)
        uri (:uri req)
        qs (:query-string req)
        full-uri (if (str/blank? qs) uri (str uri "?" qs))]
    (if-let [route (router/match-http-route db req)]
      ((http-route-handlers (:route/id route)) ctx req)
      (if-let [dl (when (and (= :get (:request-method req)) db) (parse-deep-link db full-uri))]
        (let [html (if (and (map? dl) (:sync-deep-link! ctx))
                     ((:sync-deep-link! ctx) dl)
                     (let [res (render/step-walk (render/empty-walk) db nil
                                                 (ui/page-unit-specs {:conn (:conn ctx) :boot-seq 1
                                                                      :live-streams {} :agent-states {} :errors []}))]
                       (page-shell (:walk res) db)))]
          {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} :body html})
        {:status 404 :headers {"Content-Type" "text/plain"} :body "Not found"}))))

(defn ^:seon/callable navigate-url!
  {:malli/schema [:function [:=> [:cat :map :string] :map] [:=> [:cat :map :string [:maybe :map]] :map]]
   :cost/ticks 500000}
  [& args] (apply actions/navigate-url! args))

(defn ^:seon/callable toggle-expert-mode!
  {:malli/schema [:=> [:cat [:map [:conn :seon.host/conn]]] :map] :cost/ticks 200000}
  [& args] (apply actions/toggle-expert-mode! args))

(defn ^:seon/callable eval-snippet!
  {:malli/schema [:=> [:cat :map :string] :map] :cost/ticks 500000}
  [& args] (apply actions/eval-snippet! args))

(defn code-or-merge-commit?
  "True when a `:db` notice represents a merge or code/route/rule/schema change requiring full UI walk refresh."
  {:malli/schema [:=> [:cat :any [:maybe [:sequential :any]]] :boolean] :cost/ticks 200000}
  [reason tx-ds]
  (boolean
   (or (contains? #{:merge :merged :code-merged :fork-merged} reason)
       (and (vector? reason) (contains? #{:merge :merged :code-merged :fork-merged} (first reason)))
       (and (coll? tx-ds) (not (map? tx-ds))
            (some #(let [a (:a %)]
                     (and (keyword? a)
                          (contains? #{"seon.code.form" "seon.code.var" "seon.code.ns" "seon.code.doc"
                                       "seon.route" "seon.rule" "seon.schema"}
                                     (namespace a))))
                  tx-ds)))))

(defn web-proc
  {:malli/schema [:function [:=> [:cat] :map] [:=> [:cat :map] :map] [:=> [:cat :map :any] :map] [:=> [:cat :map :any :any] [:tuple :map :any]]]}
  ([] {:params {:port "Port" :listener "Listener" :conn "Main conn" :cfg "Store cfg" :repo-dir "Git dir" :store-path "LMDB path"
                :fork-parent-pid "Fork PID" :fork-parent-ceiling "GC ceiling" :server-in-ch "Server ch"
                :report-chan "Report ch" :error-chan "Error ch" :on-power-loss "Reboot fn" :boot-seq "Boot seq"}
       :ins {:db "Commits" :stream "Model stream" :exits "Agent exits" :snap "Browser snap"}
       :outs {} :workload :io
       :doc "100% DB-driven Datastar render proc, Reagent /call dispatcher & /mcp endpoint."
       :ping-map-fn (fn [st] {:port (:bound-port st) :tabs (if-let [tc (:tab-count st)] (tc) 0) :frames (:frames st)})})
  ([{:keys [port listener conn cfg repo-dir store-path fork-parent-pid fork-parent-ceiling
            server-in-ch report-chan error-chan on-power-loss boot-seq]}]
   (let [ui-ch (a/chan 128)
         live-st (volatile! nil)
         own-listener (when (and (nil? listener) (pos? (or port 0))) (start-http-listener! port))
         eff-listener (or listener own-listener)
         st-init {:conn conn :cfg cfg :repo-dir repo-dir :store-path store-path
                  :fork-parent-pid fork-parent-pid :fork-parent-ceiling fork-parent-ceiling
                  :server-in-ch server-in-ch :ui-ch ui-ch :on-power-loss on-power-loss
                  :boot-seq (or boot-seq 1) :bound-port (:port eff-listener 0) :own-listener own-listener
                  :set-page! (:set-page! eff-listener) :broadcast! (:broadcast! eff-listener) :tab-count (:tab-count eff-listener)
                  :walk (render/empty-walk) :live-st-vol live-st :live-streams {} :agent-states {}
                  :last-stream-emit-ns 0 :last-view-sig nil :last-agg-rate "" :viewport-screenshot nil
                  :reports [] :errors [] :frames 0}
         st0 (advance-walk-and-broadcast! st-init nil)]
     (when (and conn server-in-ch)
       (when-let [missing (seq (router/missing-core-routes @conn))]
         (a/put! server-in-ch {:req {:op :transact :reason :seed-routes :tx-data (vec missing)}})))
     (when eff-listener
       ((:set-delegate! eff-listener)
        {:db-fn (fn [] (when conn @conn))
         :on-delegate! (fn [] (a/put! ui-ch {:ev :broadcast-keyframe}))
         :on-new-tab!  (fn [ch] (a/put! ui-ch {:ev :new-tab :ch ch}))
         :sync-deep-link! (fn [show-op]
         (let [db0 @conn
               vs0 (rdata/user-view-state db0)
               tgt-tx (let [t (:as-of-tx show-op)] (when (integer? t) (long t)))
               already? (and (or (nil? (:viewing show-op)) (= (:viewing show-op) (:viewing-id vs0)))
                             (or (nil? (:mode show-op)) (= (:mode show-op) (:view-mode vs0)))
                             (= (:inspect-ref show-op) (:inspect-ref vs0))
                             (= tgt-tx (:as-of-tx vs0)))]
           (when-not already? (call-server-edge! server-in-ch show-op 5000))
           (let [db @conn cur-st (or @live-st st0)]
             (if (and (:walk cur-st) (= (:walk-tx cur-st) (:max-tx db)))
               (page-shell (:walk cur-st) db)
               (let [ue (:e (first (d/datoms db :avet :seon.user/id :user/default)))
                     uds (when ue (vec (d/datoms db :eavt ue)))
                     res (render/step-walk (or (:walk cur-st) (render/empty-walk)) db uds (ui/page-unit-specs cur-st))]
                 (page-shell (:walk res) db))))))
         :handle (fn [req] (handle-http-request! {:ui-ch ui-ch :server-in-ch server-in-ch :conn conn} req))}))
     (assoc st0 ::flow/in-ports (cond-> {:ui ui-ch} report-chan (assoc :report report-chan) error-chan (assoc :error error-chan)))))
  ([st transition]
   (when (and (= transition ::flow/stop) (:own-listener st)) ((:stop! (:own-listener st))))
   st)
  ([st cid msg]
   (if (nil? msg)
     [st nil]
     (case cid
       :report [(advance-walk-and-broadcast! (update st :reports #(vec (take 30 (cons msg %)))) [] :report) nil]
       :error (do (binding [*out* *err*] (println "[FLOW ERROR]" (pr-str msg)) (flush))
                  [(advance-walk-and-broadcast! (update st :errors #(vec (take 20 (cons msg %)))) [] :error) nil])
       :snap (let [st1 (assoc st :viewport-screenshot (:snapshot msg))
                   vm (:view-mode (ui/user-view-state @(:conn st1)))]
               (if (contains? #{:context :debug} vm) [(advance-walk-and-broadcast! st1 []) nil] [st1 nil]))
       :stream [(handle-stream-msg st msg) nil]
       :exits [(handle-exit-msg st msg) nil]
       :db (let [reason (:reason msg) tx-ds (:tx-data msg)]
             [(advance-walk-and-broadcast! st (if (code-or-merge-commit? reason tx-ds) nil tx-ds) reason) nil])
       :ui (case (:ev msg)
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
             [(advance-walk-and-broadcast! (assoc st (:slot msg) (:val msg)) nil :action-done) nil]
             :call
             (let [res (invoke-call! st msg)]
               (if-not (:ok? res)
                 [(advance-walk-and-broadcast! (update st :errors #(vec (take 20 (cons (dissoc res :st) %)))) [] :call-error) nil]
                 (let [st1 (:st res)]
                   (when-let [async-spec (:async res)]
                     (launch-async-server-op! (:server-in-ch st1) (:ui-ch st1) async-spec))
                   [(advance-walk-and-broadcast! st1 []) nil])))
             [st nil])))))