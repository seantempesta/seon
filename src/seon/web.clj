(ns seon.web
  "Root's web proc (:web, an :io step-fn, declared in resources/seon/system.edn): it owns
  the HTTP listener (ring-chez-adapter, 127.0.0.1 only, fibers) and the tabs' SSE
  channels, and renders nothing (W1 §5.1; proto/web's transport, lane L2). Its parts:

    in :http        the adapter's handlers (edge fibers) put each request here as data,
                    with a promise when it waits for an answer
    in :from-render patches per view, keyframes, composed pages, the agents' index
    out :to-render  the views open in tabs; page, keyframe and context requests
    out :to-api     root's requests for UI calls ({:op :request ...}, answered to render)

  A patch is serialized once (Datastar's datastar-patch-elements) and offered to every tab
  of its view; a full channel marks the tab stale and it gets a keyframe (the view's
  composed page, morphed by id: inputs and DOM identity survive); a closed one is dropped.

  Callbacks are data (DECISIONS): one stateless /call route whose payload is the call
  (fn, agent, args, a form's fields). Root's verbs (seon.root/spawn!, submit!, run-tests!,
  merge!, merge-main!, clone!, interrupt!, kill!) become root's requests, each recorded on
  main as root's evaluation, origin :ui; any other fn runs in the agent as an eval of
  (seon.web/ui-call 'fn args), origin :ui, refused as data unless the var is marked
  ^:seon/callable."
  (:refer-clojure :exclude [defn defn-])
  (:require [seon.contract :refer [defn defn-]] [clojure.string :as str] [clojure.edn :as edn]
            [clojure.core.async :as a] [clojure.core.async.flow :as flow] [ring-chez.adapter :as adapter]
            [seon.lib :as lib] [seon.contract :as contract] [seon.render.ui :as ui] [seon.web.html :as h]))

;;; --- the files it serves ------------------------------------------------------------

(defmacro ^:private checkout-public
  "resources/public's absolute path in the checkout this binary was built from."
  [] (.getAbsolutePath (java.io.File. "resources/public")))

(def public-dir
  "Where app.css and datastar.js are read: SEON_WEB_PUBLIC, else the checkout's
  resources/public (read per request, so a style edit shows on reload)."
  (or (System/getenv "SEON_WEB_PUBLIC") (checkout-public)))

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

(def ^:private root-verbs
  "Root's verbs a UI call may name, by the request each becomes."
  '#{seon.root/spawn! seon.root/submit! seon.root/run-tests! seon.root/merge! seon.root/merge-main!
     seon.root/clone! seon.root/interrupt! seon.root/kill!})

(defn- live? {:malli/schema [:=> [:cat [:maybe :map]] :boolean]}
  [a] (boolean (and a (not (#{:dead :killed :ended} (:status a))))))

(defn requests
  "A UI call as root's requests: [[req target-agent] ...]. index {agent {:pid :branch
  :status}}; fields: the posting form's fields."
  {:malli/schema [:=> [:cat :seon.render/call :map] [:vector [:tuple :map [:maybe :keyword]]]]}
  [{f :var :keys [agent args forms]} index]
  (let [one (fn [req] [[req agent]])]
    (case f
      seon.root/spawn! (vec (repeat (min 100 (max 1 (or (:n args) 1)))
                                    [{:op :spawn :opts (cond-> {} (not (str/blank? (:task args))) (assoc :task (:task args)))} nil]))
      seon.root/submit! (if (str/blank? forms)
                          []
                          (if (= :all agent)
                            (vec (for [[id a] (sort-by (comp str key) index) :when (live? a)]
                                   [{:op :eval :id id :forms forms :origin :ui} id]))
                            (one {:op :eval :id agent :forms forms :origin :ui})))
      seon.root/run-tests! (one {:op :tests :id agent :timeout-ms 30000})
      seon.root/merge! (if-let [b (get-in index [agent :branch])]
                         (one {:op :merge-tests :id agent :merge-branch b :data-attrs :all :timeout-ms 30000})
                         [])
      seon.root/merge-main! (one {:op :merge-main :id agent :data-attrs :all})
      seon.root/clone! (one {:op :clone :parent agent :opts {}})
      seon.root/kill! (one {:op :kill :id agent})
      ;; an effect on a process, not a request: performed by this proc (interrupt!)
      seon.root/interrupt! []
      (one {:op :eval :id agent :origin :ui :forms (pr-str (list 'seon.web/ui-call (list 'quote f) (merge args (dissoc (:fields args) :forms))))}))))

(defn- record-tx
  "Root's evaluation of a UI call of its verb, recorded on main (origin :ui), as
  seon.entrance records an outside agent's."
  {:malli/schema [:=> [:cat :seon.render/call] :seon.db/tx-data]}
  [{f :var :keys [agent args forms]}]
  [{:seon.cluster.eval/id (str "root-ui-" (lib/now-ns) "/0") :seon.cluster.eval/agent "agent"
    :seon.cluster.eval/record (pr-str {:tag :ret :form (pr-str (cond-> (list f) agent (concat [agent]) (seq args) (concat [args]) forms (concat [forms])))
                                       :shown "requested" :origin :ui})}
   {:db/id "agent" :seon.agent/id :root}])

;;; --- HTTP edge ----------------------------------------------------------------------------

(defn- form-params {:malli/schema [:=> [:cat [:maybe :string]] :map]}
  [s]
  (into {} (for [kv (str/split (or s "") #"&")
                 :let [[k v] (str/split kv #"=" 2)]
                 :when (seq k)]
             [(java.net.URLDecoder/decode k "UTF-8") (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn- ask!
  "Put m with a fresh promise on the proc's :http in-port and wait for the answer (the
  edge's blocking read; the proc never waits)."
  {:malli/schema [:=> [:cat :seon.host/chan :map] :any]}
  [ch m]
  (let [p (promise)]
    (a/>!! ch (assoc m :p p))
    (deref p 10000 nil)))

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
          {:status 200 :headers {"Content-Type" "application/edn"} :body (pr-str (ask! ch {:op :stats}))}
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
  [st] {:op :views :views (into #{} (map :view (vals (:tabs st)))) :tabs (count (:tabs st)) :bytes-per-s (:bytes-per-s st 0)})

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
    (let [body (second (re-find #"(?s)<body[^>]*>(.*)</body>" html))
          chunk (patch-event (or body html))]
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

(defn- step-out {:malli/schema [:=> [:cat :seon.host/volatile :keyword :any] :nil]}
  [o port m] (vswap! o update port (fnil conj []) m) nil)

(defn- http-in
  "A request from the edge."
  {:malli/schema [:=> [:cat :map :seon.host/volatile :map] :map]}
  [st o m]
  (case (:op m)
    :open (let [k (inc (:next st 0))
                st (-> st (assoc :next k) (assoc-in [:tabs k] {:ch (:ch m) :view (:view m)}) (update :stale conj k))]
            (step-out o :to-render (views-msg st))
            (step-out o :to-render {:op :keyframe :view (:view m)})
            st)
    :page (let [k (inc (:next st 0))]
            (step-out o :to-render {:op :page :view (:view m) :k k})
            (-> st (assoc :next k) (assoc-in [:waiting k] [(:p m) (:view m)])))
    (:context :render-stats)
             (let [k (inc (:next st 0))]
               (step-out o :to-render {:op (if (= :context (:op m)) :context :stats) :k k})
               (-> st (assoc :next k) (assoc-in [:waiting k] [(:p m) :text])))
    :stats (do (deliver (:p m) {:tabs (count (:tabs st)) :stale (count (:stale st)) :sent-bytes (:bytes st 0)
                                :patches (:patches st 0) :chunk-p50-bytes (lib/p50 (:chunk-bytes st)) :listening (:listening st)
                                :port (:port st) :calls (:calls st 0) :error (:error st)})
               st)
    :call (let [c (:call m)
                n (inc (:calls st 0))
                reqs (requests c (:index st {}))]
            (when (= 'seon.root/interrupt! (:var c))
              (some-> (get-in st [:index (:agent c) :pid]) (lib/c-kill lib/SIGUSR1)))
            (doseq [[i [req target]] (map-indexed vector reqs)]
              (step-out o :to-api {:op :request :req req :route [:render :answers] :tag [:web (+ (* 1000 n) i) (:var c) target]}))
            (when (contains? root-verbs (:var c))
              (step-out o :to-api {:op :request :req {:op :write :req {:op :tx :branch :db :tx-data (record-tx c)}}
                                   :route [:render :answers] :tag [:web-record n]}))
            (assoc st :calls n))
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
               (step-out o :to-render {:op :keyframe :view (:view m)}))
             st)
    :keyframe (keyframe! st (:view m) (:html m))
    :page (if-let [[p view] (get-in st [:waiting (:k m)])]
            (do (deliver p (page-doc view (:html m))) (update st :waiting dissoc (:k m)))
            st)
    :text (if-let [[p] (get-in st [:waiting (:k m)])]
            (do (deliver p (:text m)) (update st :waiting dissoc (:k m)))
            st)
    :index (assoc st :index (:agents m))
    st))

(defn web-proc
  "Root's web proc (see the namespace doc). Args :port (127.0.0.1 only; system.edn says
  7777, the dial SEON_WEB_PORT overrides it; 0: no listener). A listener that cannot bind is data in
  its ping (:listening false :error), never a failed boot."
  {:malli/schema :seon.step/step-fn}
  ([] {:params {:port "the port on 127.0.0.1 (0: none)"}
       :ins {:from-render "patches, keyframes, pages, the agents' index"}
       :outs {:to-render "views open, page and keyframe requests" :to-api "UI calls as root's requests"}
       ;; socket writes and offers only; rendering is the render proc's
       :workload :io
       :ping-map-fn (fn [st] {:listening (:listening st) :port (:port st) :tabs (count (:tabs st))
                              :stale (count (:stale st)) :patches (:patches st 0) :calls (:calls st 0)
                              :bytes (:bytes st 0) :error (:error st)})})
  ([args]
   (let [port (or (some-> (System/getenv "SEON_WEB_PORT") parse-long) (:port args))
         http (a/chan 256)
         st {:port port :http http :tabs {} :stale #{} :waiting {} :bytes 0 :index {}
             ::flow/in-ports {:http http}}]
     (if (and port (pos? port))
       (let [server (try (adapter/run-server (handler http) {:port port :host "127.0.0.1" :strategy :fibers
                                                             :keep-alive-timeout-ms 600000 :write-timeout-ms 60000})
                         (catch Throwable e {:error (or (ex-message e) (str (type e)))}))]
         (if (:error server)
           (assoc st :listening false :error (:error server))
           (assoc st :listening true :server server :port (:port server))))
       (assoc st :listening false))))
  ([st tr]
   (if (= tr ::flow/stop)
     (do (doseq [{:keys [ch]} (vals (:tabs st))] (a/close! ch))
         (doseq [[p] (vals (:waiting st))] (deliver p nil))
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
       (step-out o :to-render (views-msg st)))
     [st @o])))
