(ns seon.web
  "Root's web proc (:web, an :io step-fn, declared in resources/seon/system.edn): it owns
  the HTTP listener (ring-chez-adapter, 127.0.0.1 only, fibers) and the tabs' SSE
  channels, and renders nothing (W1 §5.1; proto/web's transport, session L2). Its parts:

    in :http        the adapter's handlers (edge fibers) put each request here as data,
                    with a promise when it waits for an answer
    in :from-render patches per view, keyframes, composed pages, the agents' index
    out :to-render  the views open in tabs; page, keyframe and context requests
    out :to-api     root's requests for UI calls ({:op :request ...}, answered to render)

  A patch is serialized once (Datastar's datastar-patch-elements) and offered to every tab
  of its view; a full channel marks the tab stale and it gets a keyframe (the view's
  composed page, morphed by id: inputs and DOM identity survive); a closed one is dropped.

  Callbacks are data (DECISIONS): one stateless /call route whose payload is the call
  (fn, agent, args, a form's fields). Root's functions (seon.root/spawn!, submit!, run-tests!,
  merge!, merge-main!, clone!, interrupt!, kill!) become the requests their fns send
  (seon.root/request), each recorded on main as root's evaluation, origin :ui; any other fn
  runs in the agent as an eval of (seon.web/ui-call 'fn args), origin :ui, refused as data
  unless the var is marked ^:seon/callable."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.edn :as sedn] [seon.profile :as profile] [seon.contract :refer [defn defn-]] [clojure.string :as str] [clojure.edn :as edn]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [ring-chez.adapter :as adapter]
            [seon.lib :as lib] [seon.contract :as contract] [seon.config :as config] [seon.render.ui :as ui] [seon.web.html :as h] [seon.step :as step] [seon.root :as c]))

;;; --- the files it serves ------------------------------------------------------------

(def public-dir
  "Where app.css and datastar.js are read (:seon.config.web/public-dir; read per request, so
  a style edit shows on reload)."
  (config/value :seon.config.web/public-dir))

;;; --- SSE framing ------------------------------------------------------------------------

(defn patch-event
  "One Datastar v1 datastar-patch-elements event. No selector and no mode: replace each
  top-level element by its id."
  {:malli/schema [:function [:=> [:cat :string] :string] [:=> [:cat :string [:maybe :string] [:maybe :string]] :string]]}
  ([html] (patch-event html nil nil))
  ([html selector mode]
   (str "event: datastar-patch-elements\n"
        (when selector (str "data: selector " selector "\n"))
        (when mode (str "data: mode " mode "\n"))
        (when-not (str/blank? html) (apply str (for [line (str/split-lines html)] (str "data: elements " line "\n"))))
        "\n")))

(defn chunk-of
  "A render patch as one SSE chunk, serialized once for every tab of its view."
  {:malli/schema [:=> [:cat :seon.render/patch] :string]}
  [{:keys [frags appends prepends removes]}]
  (str (apply str (for [[_ html] frags] (patch-event html)))
       (apply str (for [[sel html] appends] (patch-event html sel "append")))
       (apply str (for [[sel html] prepends] (patch-event html sel "prepend")))
       (apply str (for [id removes] (patch-event "" (str "#" id) "remove")))))

;;; --- UI calls ------------------------------------------------------------------------------

(defn ui-call
  "Run var sym on args in this process if the var is marked ^:seon/callable (the UI
  allowlist); otherwise the refusal as data. What a /call naming an agent's own function
  evaluates in that agent (recorded with origin :ui)."
  {:malli/schema [:=> [:cat :symbol [:maybe :map]] :any]}
  [sym args]
  (let [v (resolve sym)]
    (if (and v (:seon/callable (meta v)))
      (@v (or args {}))
      (contract/error :seon.render/not-callable 'seon.web/ui-call
                      (str sym " is not callable from the UI: mark it ^:seon/callable")
                      {:seon.render/fn sym}))))

(defn requests
  "A UI call as root's requests: [[req target-agent] ...]. Root's function is the request it
  sends (seon.root/request; its refusal when root refuses it); any other fn is an eval of it in
  the agent, through the allowlist (ui-call). index {agent {:pid :branch :status}}."
  {:malli/schema [:=> [:cat :seon.render/call :map] [:vector [:tuple :map [:maybe :keyword]]]]}
  [{f :var :keys [agent args forms]} index]
  (let [one (fn [op id args] [[(c/request op id args (get index id)) id]])]
    (case f
      seon.root/spawn! (vec (repeat (min 100 (max 1 (or (:n args) 1))) [(c/request 'spawn! nil (cond-> {} (not (str/blank? (:task args))) (assoc :task (:task args))) nil) nil]))
      seon.root/submit! (cond (str/blank? forms) []
                              (= :all agent) (vec (for [[id a] (sort-by (comp str key) index) :when (not (c/gone-statuses (:status a)))] [(c/request 'submit! id {:forms forms :origin :ui} a) id]))
                              :else (one 'submit! agent {:forms forms :origin :ui}))
      ;; seon.root/interrupt! is no request: an effect on a process, performed by this proc
      seon.root/interrupt! []
      (if (= "seon.root" (namespace f))
        (one (symbol (name f)) agent args)
        (one 'submit! agent {:origin :ui :forms (sedn/encode (list 'seon.web/ui-call (list 'quote f) (merge args (dissoc (:fields args) :forms))))})))))

;;; --- HTTP edge ----------------------------------------------------------------------------

(defn- form-params {:malli/schema [:=> [:cat [:maybe :string]] :map]}
  [s]
  (into {} (for [kv (str/split (or s "") #"&")
                 :let [[k v] (str/split kv #"=" 2)]
                 :when (seq k)]
             [(java.net.URLDecoder/decode k "UTF-8") (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn- ask!
  "Put m with a fresh promise on the proc's :http in-port and wait for its one answer (the
  edge's blocking read; the proc never waits; at stop every waiting promise answers nil)."
  {:malli/schema [:=> [:cat :seon.host/chan :map] :any]}
  [ch m]
  (let [p (promise)]
    (a/>!! ch (assoc m :p p))
    @p))

(defn- html-response {:malli/schema [:=> [:cat [:maybe :string]] :map]}
  [s]
  (if s
    {:status 200 :headers {"Content-Type" "text/html; charset=utf-8" "Cache-Control" "no-store"} :body s}
    {:status 503 :headers {"Content-Type" "text/plain"} :body "not rendered yet: reload"}))

(defn- view-of
  "The view a path names: :root, or an agent's id."
  {:malli/schema [:=> [:cat [:maybe :string]] :any]}
  [s] (if (or (str/blank? s) (= "root" s)) :root (keyword s)))

(defn handler
  "The routes, as an edge in front of the proc's :http in-port."
  {:malli/schema [:=> [:cat :seon.host/chan] :seon.host/fn]}
  [ch]
  (let [js (delay (slurp (str public-dir "/datastar.js")))]
    (fn [req]
      (let [uri (:uri req) m (:request-method req) q (form-params (:query-string req))]
        (cond
          (and (= m :get) (= uri "/")) (html-response (ask! ch {:op :page :view :root}))
          (and (= m :get) (str/starts-with? uri "/agent/")) (html-response (ask! ch {:op :page :view (view-of (subs uri 7))}))
          (and (= m :get) (= uri "/feed"))
          (let [out (a/chan 64)]
            (a/>!! ch {:op :open :view (view-of (get q "view")) :ch out})
            {:status 200 :headers {"Content-Type" "text/event-stream" "Cache-Control" "no-cache"} :body out})
          (and (= m :get) (= uri "/datastar.js")) {:status 200 :headers {"Content-Type" "text/javascript" "Cache-Control" "max-age=3600"} :body @js}
          (and (= m :get) (= uri "/app.css")) {:status 200 :headers {"Content-Type" "text/css" "Cache-Control" "no-cache"} :body (slurp (str public-dir "/app.css"))}
          (and (= m :get) (= uri "/context.txt"))
          {:status 200 :headers {"Content-Type" "text/plain; charset=utf-8" "Cache-Control" "no-store"} :body (str (ask! ch {:op :context}))}
          (and (= m :get) (= uri "/render.edn"))
          {:status 200 :headers {"Content-Type" "application/edn" "Cache-Control" "no-store"} :body (str (ask! ch {:op :render-stats}))}
          (and (= m :get) (= uri "/web.edn"))
          {:status 200 :headers {"Content-Type" "application/edn"} :body (sedn/encode (ask! ch {:op :stats}))}
          (and (= m :post) (= uri "/call"))
          (let [fields (into {} (for [[k v] (form-params (some-> (:body req) slurp))] [(keyword k) v]))
                args (some-> (get q "args") edn/read-string)
                agent (some-> (get q "agent") keyword)
                f (some-> (get q "fn") symbol)]
            (if f
              (do (a/>!! ch {:op :call :call {:var f :agent agent :forms (:forms fields)
                                             :args (merge args (dissoc fields :forms))}})
                  {:status 204 :headers {}})
              {:status 400 :headers {"Content-Type" "text/plain"} :body "a call names its fn"}))
          :else {:status 404 :headers {"Content-Type" "text/plain"} :body "not found"})))))

;;; --- the proc ------------------------------------------------------------------------------

(defn- views-msg {:malli/schema [:=> [:cat :map] :map]}
  [st] {:op :views :views (into #{} (map :view (vals (:tabs st)))) :tabs (count (:tabs st))})

(defn- offer-all
  "Offer chunk to every fresh tab of view; full tabs turn stale, closed ones leave.
  Answers [st' bytes-sent]."
  {:malli/schema [:=> [:cat :map :any :string] [:tuple :map :int]]}
  [st view chunk]
  (reduce-kv (fn [[st sent] k {:keys [ch] :as tab}]
               (if (or (not= view (:view tab)) (contains? (:stale st) k) (str/blank? chunk))
                 [st sent]
                 (let [ok (a/offer! ch chunk)]
                   (cond (true? ok) [st (+ sent (count chunk))]
                         (false? ok) [(update st :tabs dissoc k) sent]
                         :else [(update st :stale conj k) sent]))))
             [st 0] (:tabs st)))

(defn- keyframe!
  "The view's keyframe (its composed body, morphed by id) to every stale tab of it."
  {:malli/schema [:=> [:cat :map :any [:maybe :string]] :map]}
  [st view html]
  (if-not html
    st
    (let [chunk (patch-event html)]
      (reduce-kv (fn [st k {:keys [ch] :as tab}]
                   (if (and (= view (:view tab)) (contains? (:stale st) k))
                     (let [ok (a/offer! ch chunk)]
                       (cond (true? ok) (-> st (update :stale disj k) (update :bytes + (count chunk)))
                             ;; closed, or still full since it fell behind: the tab is gone (a
                             ;; live one's Datastar reconnects and gets a fresh keyframe)
                             :else (do (a/close! ch) (-> st (update :tabs dissoc k) (update :stale disj k)))))
                     st))
                 st (:tabs st)))))

(defn- page-doc
  "A view's composed body as the whole document (title, styles, Datastar, its feed)."
  {:malli/schema [:=> [:cat :any [:maybe :string]] [:maybe :string]]}
  [view body]
  (when body
    (str "<!doctype html>"
         (h/html (ui/shell (if (= :root view) "Seon on Jolt · root" (str "Seon on Jolt · " (name view)))
                           (str "/feed?view=" (if (= :root view) "root" (name view)))
                           (h/raw body))))))

(defn- stats
  "The web proc's own numbers: its :stats answer and its ping map."
  {:malli/schema [:=> [:cat :map] :map]}
  [st]
  {:tabs (count (:tabs st)) :stale (count (:stale st)) :sent-bytes (:bytes st 0) :patches (:patches st 0)
   :chunk-p50-bytes (lib/p50 (:chunk-bytes st)) :listening (:listening st) :port (:port st) :calls (:calls st 0) :error (:error st)})

(defn- http-in
  "A request from the edge."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :map] :map]}
  [st o m]
  (case (:op m)
    :listener (do (step/out! o :to-api {:op :web :listening (boolean (:listening st)) :port (:port st) :error (:error st)}) st)
    :open (let [[st k] (step/pend st nil)
                st (-> st (assoc-in [:tabs k] {:ch (:ch m) :view (:view m)}) (update :stale conj k))]
            (step/out! o :to-render (views-msg st))
            (step/out! o :to-render {:op :keyframe :view (:view m)})
            st)
    :page (let [[st k] (step/pend st [(:p m) (:view m)])]
            (step/out! o :to-render {:op :page :view (:view m) :k k})
            st)
    (:context :render-stats)
             (let [[st k] (step/pend st [(:p m) :text])]
               (step/out! o :to-render {:op (if (= :context (:op m)) :context :stats) :k k})
               st)
    :stats (do (deliver (:p m) (stats st)) st)
    :call (let [c (:call m)
                reqs (requests c (:index st {}))]
            (when (= 'seon.root/interrupt! (:var c))
              (some-> (get-in st [:index (:agent c) :pid]) (lib/c-kill lib/SIGUSR1)))
            ;; answered to the render proc, which shows each in the stream by its tag; root's
            ;; function is root's evaluation, its form in the tag: the render proc records it on
            ;; main (origin :ui) with what root answered, as root's REPL records one
            (doseq [[req target] reqs
                    :let [{f :var :keys [agent args forms]} c
                          form (when (= "seon.root" (namespace f))
                                 (sedn/encode (cond-> (list f) agent (concat [agent]) (seq args) (concat [args]) forms (concat [forms]))))]]
              (let [tag (cond-> [:web f target] form (conj form))]
                (if (contract/error? req)
                  (step/out! o :to-render {:op :refused :tag tag :r req})
                  (step/out! o :to-api {:op :request :req req :route [:render :answers] :tag tag}))))
            (update st :calls (fnil inc 0)))
    st))

(defn- render-in
  "A message from the render proc."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :map] :map]}
  [st o m]
  (case (:op m)
    ;; an SSE comment with the ms the chunk left this proc: a client can tell transport
    ;; delay from render delay (Datastar ignores comments)
    :patch (let [chunk (str ": at " (System/currentTimeMillis) "\n" (chunk-of m))
                 [st sent] (offer-all st (:view m) chunk)
                 st (-> st (update :bytes + sent) (update :patches (fnil inc 0))
                        (update :chunk-bytes #(lib/push-window % (count chunk) 200)))]
             ;; a tab fallen behind gets the view's keyframe next
             (when (some #(= (:view m) (get-in st [:tabs % :view])) (:stale st))
               (step/out! o :to-render {:op :keyframe :view (:view m)}))
             st)
    :keyframe (keyframe! st (:view m) (:html m))
    ;; the render proc has nothing in flight: an SSE comment on every feed (Datastar ignores
    ;; comments), the event a client or a check waits on before reading a current view
    :settled (reduce (fn [st view] (let [[st sent] (offer-all st view ": settled\n\n")] (update st :bytes + sent)))
                     st (distinct (map :view (vals (:tabs st)))))
    (:page :text) (let [[st [p view]] (step/answered st (:k m))]
                    (some-> p (deliver (if (= :page (:op m)) (page-doc view (:html m)) (:text m))))
                    st)
    :index (assoc st :index (:agents m))
    st))

(defn web-proc
  "Root's web proc (see the namespace doc). Args :port (127.0.0.1 only; system.edn binds
  :seon.config.web/port; 0: no listener). A listener that cannot bind is data in
  its ping (:listening false :error), never a failed boot."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:port "the port on 127.0.0.1 (0: none)"}
       :ins {:from-render "patches, keyframes, pages, the agents' index"}
       :outs {:to-render "views open, page and keyframe requests" :to-api "UI calls as root's requests"}
       ;; socket writes and offers only; rendering is the render proc's
       :workload :io
       :ping-map-fn stats})
  ([args]
   (let [port (:port args)
         http (a/chan 256)
         st {:port port :http http :tabs {} :stale #{} :rid 0 :pending {} :bytes 0 :index {}
             ::flow/in-ports {:http http}}
         ;; whether it listens is an event to the api's view (its first message, on its own
         ;; in-port: init has no outputs), never a ping (owner, 2026-10-21)
         _ (a/put! http {:op :listener})]
     (if (and port (pos? port))
       (let [server (try (adapter/run-server (handler http) {:port port :host "127.0.0.1" :strategy :fibers
                                                             :keep-alive-timeout-ms 600000 :write-timeout-ms 60000})
                         (catch Throwable e (contract/error :seon.web/unbound 'seon.web/web-proc (step/error-msg e) {:port port})))]
         (if (contract/error? server)
           (assoc st :listening false :error server)
           (assoc st :listening true :server server :port (:port server))))
       (assoc st :listening false))))
  ([st tr]
   (if (= tr ::flow/stop)
     (do (doseq [{:keys [ch]} (vals (:tabs st))] (a/close! ch))
         (doseq [[p] (vals (:pending st))] (deliver p nil))
         (when-let [s (:server st)] (adapter/stop-server s {:drain-timeout-ms 0}))
         (assoc st :tabs {} :listening false :server nil))
     st))
  ([st in m]
   (let [o (volatile! {})
         before (into #{} (map :view (vals (:tabs st))))
         st (cond
              (nil? m) st
              (= in :http) (http-in st o m)
              (= in :from-render) (render-in st o m)
              :else st)
         ;; a tab whose channel closed (found at an offer) is gone: the views watched may change
         st (update st :stale (fn [s] (into #{} (filter #(contains? (:tabs st) %)) s)))]
     (when (and (not= :open (:op m)) (not= before (into #{} (map :view (vals (:tabs st))))))
       (step/out! o :to-render (views-msg st)))
     [st @o])))
