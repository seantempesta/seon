(ns seon.web.router
  "The pod's HTTP front door — reitit over a route vector DERIVED from the
   `:seon.route/*` datoms.

   The route vector is `(into (db->routes db) (static-supplement h))`:
   [[db->routes]] is a PURE projection of the seeded `:seon.route/*` datoms
   (the six core routes — `/`, `/world`, `/world/feed`, `/agent/{id}`,
   `/agent/{id}/feed`, `/agent/{id}/call`), and the static supplement carries
   the routes NOT yet seeded as datoms (static assets, the secondary POST
   doors, `/sse`, the flat `/call`). The router is cached in `!ring-handler`
   and is a pure derived value of those datoms — [[rebuild!]] re-derives it
   from the current db (called post-seed by serve/start! and, when Core wires a
   route tx-listener, on every route tx). Build-time path/name conflict
   detection catches overlaps the old `cond` silently shadowed.

   ## Handlers resolve LATE — a symbol per route

   `:seon.route/handler` is a `:db.type/symbol`; [[route-handler]] resolves it
   at REQUEST time via `seon.eval/lookup-value` (the same late-binding the
   render engine uses), so a redefine takes effect with no router rebuild. Each
   seeded handler is a Ring handler that takes the Ring request `r` and
   self-extracts `(:seon.http/node-req r)` / `(:seon.http/node-res r)` /
   `(get-in r [:path-params :id])`, so [[db->routes]] wraps every one
   uniformly. `:seon.route/middleware` keywords resolve through reitit's
   `:reitit.middleware/registry` ([[mw-registry]]).

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
   (`datastar`/`debug`/`reactive.call`, none of which require serve).
   One direction, no require cycle. On hot-reload serve re-runs [[install!]],
   so the cached router always holds the freshly-reloaded handler fns."
  (:require
    [clojure.string :as str]
    [seon.db :as db]
    [seon.eval :as seval]
    [seon.log :as log]
    ;; Build-inclusion only (no alias): db->routes resolves datastar's core
    ;; handler SYMBOLS (`handle!`, `serve-agent-page!`, `open-agent-feed!`) at
    ;; request time via eval/lookup-value, so the ns must be compiled into the
    ;; build. router is its sole requirer.
    [seon.web.datastar]
    [seon.web.debug :as debug]
    [seon.web.reactive.call :as call]
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

(defonce ^{:private true
           :doc "The serve handler set last injected by install! (the static
                 supplement's leaf handlers). Stored so rebuild! can re-derive
                 the router from fresh route datoms WITHOUT serve re-passing the
                 config — a route tx-listener calls (rebuild!) with no args."}
  !router-config (atom {}))

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
   the node res directly returns this so the adapter double-writes nothing.

   LOAD-BEARING INVARIANT: every reitit handler here MUST return a TRUTHY value
   (this sentinel). reitit's sync `ring-handler` is `(or (handler req)
   (default-handler req))` (reitit-ring/ring.cljc:389) — a handler that writes the
   socket then returns a FALSY value makes reitit re-invoke the default-handler
   (`not-found`), which writes the res AGAIN → a 'headers already sent' crash.
   The `hijacked` sentinel is truthy, so the `or` short-circuits and the default
   never double-fires. Keep every handler returning it."
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
;; Middleware registry — the ONE place a `:seon.route/middleware` keyword
;; resolves to its reitit middleware. Threaded into the reitit router as
;; `:reitit.middleware/registry`, so a route's `:middleware [:seon.route/…]`
;; keywords (both the db-projected routes AND the static supplement) resolve
;; here; reitit throws a legible "not found in registry" at BUILD time if a
;; route names an unknown one (surface-errors-loudly, no silent gate bypass).
;; ============================================================

(def ^:private mw-registry
  {:seon.route/same-origin same-origin-mw})

;; ============================================================
;; db->routes — the route vector is a PURE projection of the `:seon.route/*`
;; datoms. GROUP the route entities by `:seon.route/pattern`, nest per
;; `:seon.route/method`, wrap `:seon.route/handler` (a late-bound symbol)
;; with [[route-handler]], and pass `:seon.route/middleware` keywords through
;; to [[mw-registry]]. The handlers are the EXISTING pod handler fns (each
;; refactored to take the Ring request `r`), resolved late at request time.
;; ============================================================

(defn- route-handler
  "A reitit ring handler for a route's late-bound handler SYMBOL `sym`.
   Resolves `sym` via `eval/lookup-value` at REQUEST time (late binding, like
   the render engine's `:seon.render/html` symbols), calls it with the Ring
   request `r` (the handler self-extracts node-req/node-res/path-params), and
   returns the hijack sentinel. An unresolved symbol degrades to a 500 — never
   a falsy return that would re-fire the default-handler (a double write)."
  [sym]
  (fn [r]
    (if-let [f (seval/lookup-value sym)]
      (do (f r) hijacked)
      (do (log/error-console! "seon.web.router" "route handler unresolved"
                              {:sym (str sym) :path (:uri r)})
          (write-text! (node-res r) 500 (str "route handler unresolved: " sym))
          hijacked))))

(defn db->routes
  "Project the `:seon.route/*` datoms in `db` into a reitit route vector.

   GROUP the route entities by `:seon.route/pattern`, nest per
   `:seon.route/method`, wrap `:seon.route/handler` (the late-bound symbol)
   via [[route-handler]], pass `:seon.route/middleware` keywords through to
   [[mw-registry]]. A pure value of the route datoms — a nil/route-less `db`
   yields `[]` (the static supplement keeps the pod serving until the seed
   lands)."
  {:malli/schema [:=> [:catn [::db [:maybe :seon.db/db-val]]] [:vector :any]]}
  [db]
  (if-not db
    []
    (->> (db/query '[:find [(pull ?e [:seon.route/pattern :seon.route/method
                                      :seon.route/handler :seon.route/middleware]) ...]
                     :where [?e :seon.route/pattern]]
                   db)
         (group-by :seon.route/pattern)
         (sort-by key)
         (mapv (fn [[pattern rows]]
                 [pattern
                  (into {}
                        (map (fn [{:seon.route/keys [method handler middleware]}]
                               [method (cond-> {:handler (route-handler handler)}
                                         (seq middleware)
                                         (assoc :middleware (vec middleware)))]))
                        rows)])))))

;; ============================================================
;; The static supplement — the routes NOT (yet) seeded as `:seon.route/*`
;; datoms, so nothing 404s: static assets, the secondary state-changing POST
;; doors (each `:seon.route/same-origin`-gated), `/sse`, the back-compat
;; flat `/call`, and the operator dev tools (`/data` + `/agent/{id}/debug`,
;; `seon.web.debug`). db->routes supplies the six core routes; this supplies
;; the rest. FLAG (coordination → Core): the secondary POST doors below should
;; be seeded as `:seon.route/*` datoms for fully data-driven routing — until
;; then they live here.
;; ============================================================

(defn- post-handler
  "Wrap a serve `(req res)` handler as a reitit ring handler that hijacks."
  [f]
  (fn [r] (f (node-req r) (node-res r)) hijacked))

(defn- static-supplement
  "The non-core reitit routes, built from the injected handler set `h`
   (serve's handlers) + the directly-required `call` leaf handler."
  [h]
  (let [{::keys [sse static chat stop resume clear log create-agent
                 complete agent-run]} h]
    [["/css/{*path}" {:get {:handler (fn [r] (static (node-res r) (:uri r)) hijacked)}}]
     ["/js/{*path}"  {:get {:handler (fn [r] (static (node-res r) (:uri r)) hijacked)}}]
     ["/sse"         {:get {:handler (fn [r] (sse (node-req r) (node-res r)) hijacked)}}]

     ;; Operator dev tools (seon.web.debug) — the datom browser + the
     ;; per-agent two-pane debug inspector. Plain leaf handlers (no serve
     ;; state), required directly; distinct paths from the seeded
     ;; `/agent/{id}` family, so no reitit conflict.
     ["/data"     {:get {:handler (fn [r] (debug/data-page! (node-req r) (node-res r)) hijacked)}}]
     ["/data/sse" {:get {:handler (fn [r] (debug/data-sse! (node-req r) (node-res r)) hijacked)}}]
     ["/agent/{id}/debug"     {:get {:handler (fn [r] (debug/debug-page! (node-req r) (node-res r)
                                                                         (get-in r [:path-params :id]))
                                               hijacked)}}]
     ["/agent/{id}/debug/sse" {:get {:handler (fn [r] (debug/debug-sse! (node-req r) (node-res r)
                                                                        (get-in r [:path-params :id]))
                                               hijacked)}}]

     ["/chat"        {:post {:middleware [:seon.route/same-origin] :handler (post-handler chat)}}]
     ["/stop"        {:post {:middleware [:seon.route/same-origin] :handler (post-handler stop)}}]
     ["/resume"      {:post {:middleware [:seon.route/same-origin] :handler (post-handler resume)}}]
     ["/clear"       {:post {:middleware [:seon.route/same-origin] :handler (post-handler clear)}}]
     ["/log"         {:post {:middleware [:seon.route/same-origin] :handler (post-handler log)}}]
     ["/agents/new"  {:post {:middleware [:seon.route/same-origin] :handler (post-handler create-agent)}}]
     ;; The one-shot composition door: start-or-reuse an agent in THE pod's
     ;; own cluster, deliver the input via the real wake path, run its OWN
     ;; FSM to idle, return the truthful reply + turn/eval metadata as JSON.
     ;; same-origin-gated like the others.
     ["/agents/run"  {:post {:middleware [:seon.route/same-origin] :handler (post-handler agent-run)}}]
     ;; The flat `/call` (back-compat this unit) hands the raw (req,res) to the
     ;; unchanged capability gate; the per-agent `/agent/{id}/call` is the
     ;; SEEDED core door (db->routes) → the same gate.
     ["/call"                {:post {:middleware [:seon.route/same-origin]
                                     :handler (fn [r] (call/handle! (node-req r) (node-res r)) hijacked)}}]
     ["/agent/{id}/complete" {:post {:middleware [:seon.route/same-origin]
                                     :handler (fn [r] (complete (node-req r) (node-res r)
                                                                (get-in r [:path-params :id]))
                                                hijacked)}}]]))

;; ============================================================
;; The no-match default-handler — a graceful redirect HOME (#28). reitit
;; calls this when no route matches (or the matched path has no handler for
;; the method). Rather than a raw 404 dead-end, a miss 302s to `/` (root's
;; system dashboard) so a mistyped/stale URL always lands somewhere live.
;; ============================================================

(defn- not-found [r]
  (let [^js res (node-res r)]
    (.writeHead res 302 #js {"Location" "/" "Cache-Control" "no-store"})
    (.end res ""))
  hijacked)

;; ============================================================
;; Build + dispatch.
;; ============================================================

(defn- build-ring-handler
  "Build the reitit ring-handler for `db` (the route-datom source) + the stored
   serve config: `(into (db->routes db) (static-supplement config))`, with
   [[mw-registry]] threaded in for keyword middleware."
  [db]
  (let [config @!router-config]
    (rr/ring-handler
      (rr/router (into (db->routes db) (static-supplement config))
                 {:reitit.middleware/registry mw-registry})
      not-found)))

(defn rebuild!
  "Re-derive and cache the reitit ring-handler from current route datoms.

   A pure value of `:seon.route/*` in `@*conn*` plus the stored serve
   config. Idempotent. Called post-seed by `seon.web.serve/start!` (the
   seeded routes land AFTER the top-level install!, when *conn* was still
   nil) and, when Core wires a route tx-listener, on every route tx."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (reset! !ring-handler (build-ring-handler (some-> db/*conn* deref)))
  nil)

(defn install!
  "Inject serve's handlers and rebuild the cached reitit ring-handler.

   Injects serve's handler set + same-origin predicate, then (re)builds the
   cached router from the current route datoms. serve calls this
   at load (re-runs on hot-reload, so the cached router tracks reloaded
   handlers + the latest route datoms). `config` keys:
   `:seon.web.router/{sse static chat stop resume clear log create-agent
   complete}` (the serve handler fns) + `:seon.web.router/same-origin?` (the
   predicate). The six CORE routes are NOT in `config` — they project from the
   `:seon.route/*` datoms via [[db->routes]]."
  {:malli/schema [:=> [:catn [::config :map]] :nil]}
  [config]
  (reset! !same-origin-pred (or (::same-origin? config) (constantly true)))
  (reset! !router-config config)
  (rebuild!)
  (log/info-console! "seon.web.router" "router installed"
                     {:supplement (count (static-supplement config))})
  nil)

(defn handle-request
  "The single node `(req, res)` HTTP entry point.

   `seon.web.serve`'s createServer wrapper calls this. Builds a Ring
   request, runs the cached reitit ring-handler, and either lets the
   handler keep the socket (the hijack sentinel → write nothing) or writes
   the returned Ring response map. A throw anywhere degrades to a 500
   (never crash the single pod thread)."
  [^js req ^js res]
  (try
    (let [rh (or @!ring-handler (build-ring-handler (some-> db/*conn* deref)))
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
