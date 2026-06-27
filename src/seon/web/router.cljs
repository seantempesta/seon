(ns seon.web.router
  "The pod's HTTP front door — reitit over a static route vector.

   This replaces `seon.web.serve`'s hand-rolled method `case` + GET/POST
   `cond` with reitit's data-driven router. The router is built ONCE per
   load (cached in `!ring-handler`) from a static route vector; build-time
   path/name conflict detection catches overlaps the old `cond` silently
   shadowed. Reverse routing (`match-by-name`) and reitit-malli `:parameters`
   coercion are available for free once the route datoms land — see the
   `db->routes` TODO seam below.

   ## The Node↔Ring adapter + the hijack sentinel

   reitit speaks Ring (a request MAP → a response MAP); the pod speaks raw
   `node:http` `(req, res)`. [[node->ring]] builds a Ring request from the node
   `req` (method, uri sans query, query-string, headers) AND injects the raw
   node `req`/`res` under `:seon.http/node-req` / `:seon.http/node-res` so the
   streaming + static handlers can reach the socket. A handler that takes over
   the socket itself (the SSE open, a static file pipe, the gzip /world feed,
   a /call JSON write) returns the **hijack sentinel** `{:seon.http/hijacked
   true}`; [[handle-request]] sees it and writes NOTHING (the handler already
   owns the stream). A handler that returns a plain Ring response map is
   written to the node res by [[write-ring-response!]] — so a future pure-Ring
   handler needs no socket knowledge.

   ## Cycle-free wiring — serve injects, router routes

   `seon.web.serve` keeps its handler fns (they touch serve-state: the SSE
   registry, the create-agent closure) and the same-origin? gate (a test pins
   it). serve `:require`s router and calls [[install!]] with its handler set +
   the same-origin predicate; router requires only the leaf handlers
   (`datastar`/`inspector`/`tile`/`reactive.call`, none of which require serve).
   One direction, no require cycle. On hot-reload serve re-runs [[install!]],
   so the cached router always holds the freshly-reloaded handler fns."
  (:require
    [clojure.string :as str]
    [seon.log :as log]
    [seon.web.datastar :as datastar]
    [seon.web.inspector :as inspector]
    [seon.web.reactive.call :as call]
    [seon.web.tile :as tile]
    [reitit.ring :as rr]))

;; ============================================================
;; Process-lifetime state — the cached reitit ring-handler + the
;; injected same-origin predicate. Both (re)set by install!.
;; ============================================================

(defonce ^:private !ring-handler (atom nil))

(defonce ^{:private true
           :doc "The same-origin? predicate, injected by serve (kept there
                 verbatim because a test pins it). Defaults to allow-all
                 until install! runs."}
  !same-origin-pred (atom (constantly true)))

;; ============================================================
;; Low-level node writes (the adapter's own minimal helpers — the
;; route handlers do their own writing; these cover the adapter's
;; 403 / 404 / ring-response-map paths).
;; ============================================================

(defn- write-text! [^js res code body]
  (.writeHead res code #js {"Content-Type"  "text/plain; charset=utf-8"
                            "Cache-Control" "no-store"})
  (.end res body))

(defn- write-ring-response!
  "Write a plain Ring response map `{:status :headers :body}` to the node
   res. The escape hatch for handlers that return data instead of writing the
   socket; today every route handler hijacks, so this is the forward-looking
   pure-Ring path."
  [^js res {:keys [status headers body]}]
  (.writeHead res (or status 200) (clj->js (or headers {})))
  (.end res (or body "")))

;; ============================================================
;; Node↔Ring adapter.
;; ============================================================

(def ^:private hijacked
  "The handler-owns-the-socket sentinel. A handler that wrote (or will write)
   the node res directly returns this so the adapter double-writes nothing."
  {:seon.http/hijacked true})

(defn- node-req [r] (:seon.http/node-req r))
(defn- node-res [r] (:seon.http/node-res r))

(defn- node->ring
  "Build a Ring request map from a node IncomingMessage, injecting the raw
   node `req`/`res` so socket-owning handlers (SSE, static, /call) can reach
   them. `:uri` is the path with the query stripped (reitit matches on it);
   `:query-string` is the raw query; `:request-method` is a lower-cased
   keyword. Headers ride as a plain map for completeness — routing + the
   same-origin gate read the raw node req, so the map is not load-bearing."
  [^js req ^js res]
  (let [url    (or (.-url req) "/")
        qidx   (str/index-of url "?")
        uri    (if qidx (subs url 0 qidx) url)
        qs     (when qidx (subs url (inc qidx)))
        method (-> (or (.-method req) "GET") str/lower-case keyword)]
    {:request-method      method
     :uri                 uri
     :query-string        qs
     :headers             (js->clj (.-headers req))
     :seon.http/node-req  req
     :seon.http/node-res  res}))

;; ============================================================
;; Same-origin middleware — a reitit middleware on every state-changing
;; POST route. Delegates to the injected predicate (serve owns the
;; same-origin? logic verbatim); a cross-origin POST is refused 403 and
;; the request is hijacked (nothing further runs).
;; ============================================================

(def ^:private same-origin-mw
  {:name ::same-origin
   :wrap (fn [handler]
           (fn [r]
             (if (@!same-origin-pred (node-req r))
               (handler r)
               (do
                 (log/info-console! "seon.web.router" "POST cross-origin REFUSED"
                                    {:path (:uri r)})
                 (write-text! (node-res r) 403 "cross-origin POST refused")
                 hijacked))))})

;; ============================================================
;; The route vector.
;;
;; TODO (Phase 5 / db->routes seam): the route vector is STATIC here because
;; the `:seon.route/*` schema isn't landed yet. When it is, replace
;; `(routes h)` with a `db->routes` projection: GROUP `:seon.route/*` datoms
;; by `:seon.route/pattern`, nest per `:seon.route/method`, resolve
;; `:seon.route/handler` via `seon.eval/lookup-value`, map
;; `:seon.route/middleware` keywords through a registry. The router stays a
;; pure derived value of the route datoms, rebuilt on tx via install!.
;;
;; Every CURRENT route is covered so nothing 404s after the swap: the GET
;; pages + static + /sse + /world feed, every state-changing POST (each
;; same-origin-gated), the NEW hierarchical `/agent/{id}/call` (the gate is
;; unchanged — the {id} segment is just the routing level, the fn's own
;; namespace still authorizes), and a kept flat `/call` for back-compat.
;; The inspector + tile sub-trees are NOT reitit routes — they ride the
;; legacy `route?`/`handle!` dispatch via the no-match default-handler
;; (legacy-default), so their internal dispatch stays untouched until those
;; stacks are deleted.
;; ============================================================

(defn- post-handler
  "Wrap a serve `(req res)` handler as a reitit ring handler that hijacks."
  [f]
  (fn [r] (f (node-req r) (node-res r)) hijacked))

(defn- routes
  "The static reitit route vector, built from the injected handler set `h`
   (serve's handlers) + the directly-required leaf handlers (datastar / call)."
  [h]
  (let [{::keys [root sse static chat stop resume clear log create-agent
                 complete]} h]
    [["/"            {:get {:handler (fn [r] (root (node-res r)) hijacked)}}]
     ["/css/{*path}" {:get {:handler (fn [r] (static (node-res r) (:uri r)) hijacked)}}]
     ["/js/{*path}"  {:get {:handler (fn [r] (static (node-res r) (:uri r)) hijacked)}}]
     ["/sse"         {:get {:handler (fn [r] (sse (node-req r) (node-res r)) hijacked)}}]
     ["/world"       {:get {:handler (fn [r] (datastar/handle! (node-req r) (node-res r) (:uri r)) hijacked)}}]
     ["/world/feed"  {:get {:handler (fn [r] (datastar/handle! (node-req r) (node-res r) (:uri r)) hijacked)}}]
     ;; Per-agent world (#6 retires the legacy inspector/tile console at the
     ;; same bare `/agent/{id}`): the shim page + the gzip morph feed bound
     ;; to THAT agent's `world-layout`. Both ride the proven datastar
     ;; streamer; the GET routes take precedence over the legacy-default
     ;; inspector delegation, deeper `/agent/{id}/…` GETs still fall through.
     ["/agent/{id}"      {:get {:handler (fn [r] (datastar/serve-agent-page! (node-res r) (get-in r [:path-params :id])) hijacked)}}]
     ["/agent/{id}/feed" {:get {:handler (fn [r] (datastar/open-agent-feed! (node-req r) (node-res r) (get-in r [:path-params :id])) hijacked)}}]

     ["/chat"        {:post {:middleware [same-origin-mw] :handler (post-handler chat)}}]
     ["/stop"        {:post {:middleware [same-origin-mw] :handler (post-handler stop)}}]
     ["/resume"      {:post {:middleware [same-origin-mw] :handler (post-handler resume)}}]
     ["/clear"       {:post {:middleware [same-origin-mw] :handler (post-handler clear)}}]
     ["/log"         {:post {:middleware [same-origin-mw] :handler (post-handler log)}}]
     ["/agents/new"  {:post {:middleware [same-origin-mw] :handler (post-handler create-agent)}}]
     ;; The one action door (flat, kept for back-compat this unit) + the
     ;; NEW hierarchical per-agent door. Both just hand the raw (req,res) to
     ;; the unchanged capability gate (`reactive.call/handle!`), which reads
     ;; `?fn=`/`?args=` and authorizes the fn from its own namespace; the
     ;; `{id}` segment is the routing level, not an auth input.
     ["/call"                {:post {:middleware [same-origin-mw]
                                     :handler (fn [r] (call/handle! (node-req r) (node-res r)) hijacked)}}]
     ["/agent/{id}/call"     {:post {:middleware [same-origin-mw]
                                     :handler (fn [r] (call/handle! (node-req r) (node-res r)) hijacked)}}]
     ["/agent/{id}/complete" {:post {:middleware [same-origin-mw]
                                     :handler (fn [r] (complete (node-req r) (node-res r)
                                                                (get-in r [:path-params :id]))
                                                hijacked)}}]]))

;; ============================================================
;; The no-match default-handler — the legacy inspector + tile delegation.
;; reitit calls this when no route matches (or the matched path has no
;; handler for the method). GET paths get the legacy `route?`/`handle!`
;; dispatch verbatim (their internal routing stays); everything else 404s.
;; ============================================================

(defn- legacy-default [r]
  (let [req    (node-req r)
        res    (node-res r)
        path   (:uri r)
        method (:request-method r)]
    (cond
      (and (= method :get) (inspector/route? path))
      (do (inspector/handle! req res path) hijacked)

      (and (= method :get) (tile/route? path))
      (do (tile/handle! req res path) hijacked)

      :else
      (do (write-text! res 404 (str "Not found: " path)) hijacked))))

;; ============================================================
;; Build + dispatch.
;; ============================================================

(defn- build-ring-handler [h]
  (rr/ring-handler (rr/router (routes h)) legacy-default))

(defn install!
  "(Re)build + cache the reitit ring-handler from serve's handler set, and
   install serve's same-origin predicate for the POST middleware. serve calls
   this at load (re-runs on hot-reload, so the cached router tracks reloaded
   handlers). `config` keys: `:seon.web.router/{root sse static chat stop
   resume clear log create-agent complete}` (the serve handler fns) +
   `:seon.web.router/same-origin?` (the predicate)."
  {:malli/schema [:=> [:catn [::config :map]] :nil]}
  [config]
  (reset! !same-origin-pred (or (::same-origin? config) (constantly true)))
  (reset! !ring-handler (build-ring-handler config))
  (log/info-console! "seon.web.router" "router installed"
                     {:routes (count (routes config))})
  nil)

(defn handle-request
  "The single node `(req, res)` entry point — `seon.web.serve`'s createServer
   wrapper calls this. Builds a Ring request, runs the cached reitit
   ring-handler, and either lets the handler keep the socket (the hijack
   sentinel → write nothing) or writes the returned Ring response map. A
   throw anywhere degrades to a 500 (never crash the single pod thread)."
  [^js req ^js res]
  (try
    (let [rh (or @!ring-handler (build-ring-handler {}))
          result (rh (node->ring req res))]
      (cond
        (nil? result)                  (write-text! res 404 (str "Not found: " (or (.-url req) "/")))
        (:seon.http/hijacked result)   nil
        (map? result)                  (write-ring-response! res result)
        :else                          (write-text! res 404 (str "Not found: " (or (.-url req) "/")))))
    (catch :default e
      (log/error-console! "seon.web.router" "handle-request error" e)
      (try (write-text! res 500 (str "Internal error: " e))
           (catch :default _ nil)))))
