(ns seon.web.router
  "The pod's HTTP front door — reitit over a route vector DERIVED from the
   `:seon.route/*` datoms.

   The route vector is `(into (db->routes rows) (static-supplement h))`:
   [[db->routes]] is a PURE projection of the seeded `:seon.route/*` datoms
   (the core routes — `/`,
   `/agent/{id}`, `/agent/{id}/feed`, `/agent/{id}/call`), and the static
   supplement carries
   the routes NOT yet seeded as datoms (static assets, the secondary POST
   doors, and the loopback operator config door). The compiled router is a
   discardable
   cache keyed by the exact route projection plus the static supplement
   config. [[attach!]] installs one query-derived authority interest and reads
   the initial projection at its acknowledged database value. Later matching
   commits reconcile that exact value, while unrelated transactions do no
   routing work. Build-time path/name conflict detection
   catches overlaps the old `cond` silently shadowed.

   ## Handlers resolve LATE — a symbol per route

   `:seon.route/handler` is a `:db.type/symbol`; [[route-handler]] resolves it
   at REQUEST time via `seon.eval/lookup-value` (the same late-binding the
   render engine uses), so a redefine takes effect with no router rebuild. Each
   seeded handler is a Ring handler that takes the Ring request `r` and
   self-extracts `(:seon.http/node-req r)` / `(:seon.http/node-res r)` /
   `(get-in r [:path-params :id])`, so [[db->routes]] wraps every one
   uniformly. An optional `:seon.route/middleware` keyword resolves through reitit's
   `:reitit.middleware/registry` ([[mw-registry]]).

   ## The Node↔Ring adapter + the hijack sentinel

   reitit speaks Ring (a request MAP → a response MAP); the pod speaks raw
   `node:http` `(req, res)`. [[node->ring]] builds a Ring request from the node
   `req` (method, uri sans query, query-string, headers) AND injects the raw
   node `req`/`res` under `:seon.http/node-req` / `:seon.http/node-res` so the
   streaming + static handlers can reach the socket. A handler that takes over
   the socket itself (the SSE open, a static file pipe, the gzip /agent-view feed,
   an agent-action JSON write) returns the **hijack sentinel** `{:seon.http/hijacked
   true}`; [[handle-request]] sees it and writes NOTHING (the handler already
   owns the stream). A handler that returns a plain Ring response map is
   written to the node res by [[write-ring-response!]] — so a future pure-Ring
   handler needs no socket knowledge.

   ## Cycle-free wiring — serve injects, router routes

   `seon.web.serve` keeps its handler fns that touch serve-state and the
   same-origin? gate (a test pins
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
    [seon.runtime.admission :as admission]
    ;; Build-inclusion only (no alias): db->routes resolves datastar's core
    ;; handler symbols (`serve-root!`,
    ;; `serve-agent-page!`, `open-agent-feed!`) at request time via
    ;; eval/lookup-value, so the ns must be compiled into the build. router is
    ;; its sole requirer.
    [seon.web.datastar]
    [seon.web.debug :as debug]
    ;; Build-inclusion only: the database-seeded `/agent/{id}/call` route
    ;; resolves this handler symbol late through seon.eval/lookup-value.
    [seon.web.reactive.call]
    [reitit.ring :as rr]))

;; ============================================================
;; Process-lifetime state — ONE discardable cache owner. The route projection
;; is database-derived; the config and compiled handler are opaque runtime
;; resources supplied by serve. No route fact is duplicated here.
;; ============================================================

(defonce ^{:private true
           :doc "The exact inputs and compiled output of the current router.
                 Route facts remain authoritative in the database; this atom
                 owns only the discardable reitit compilation plus serve's
                 runtime handler functions."}
  !router-state
  (atom {::config           {}
         ::same-origin-pred (constantly true)
         ::loopback-peer-pred (constantly false)
         ::cache-key        nil
         ::ring-handler     nil}))

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
   node `req`/`res` so socket-owning handlers (SSE, static, agent action) can reach
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
             (if ((::same-origin-pred @!router-state) (node-req r))
               (handler r)
               (do
                 (log/info-console! "seon.web.router" "POST cross-origin REFUSED"
                                    {:path (:uri r)})
                 (write-text! (node-res r) 403 "cross-origin POST refused")
                 hijacked))))})

(def ^:private loopback-peer-mw
  {:name ::loopback-peer
   :wrap (fn [handler]
           (fn [r]
             (if ((::loopback-peer-pred @!router-state) (node-req r))
               (handler r)
               (do
                 (log/info-console! "seon.web.router"
                                    "operator request from non-loopback peer REFUSED"
                                    {:path (:uri r)})
                 (write-text! (node-res r) 403
                              "loopback operator request required")
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
  {:seon.route/same-origin same-origin-mw
   :seon.route/loopback-peer loopback-peer-mw})

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

(def ^:private route-query
  '[:find [(pull ?route [:seon.route/pattern
                         :seon.route/method
                         :seon.route/handler
                         :seon.route/middleware]) ...]
    :where [?route :seon.route/pattern]])

(defn- route-projection
  "Canonical route rows sorted into one exact cache fingerprint."
  [rows]
  (->> rows
       (sort-by (juxt :seon.route/pattern
                      :seon.route/method
                      #(str (:seon.route/handler %))
                      #(pr-str (:seon.route/middleware %))))
       vec))

(defn- projection->routes
  "Compile a canonical route projection into reitit's route data."
  [projection]
  (->> projection
       (group-by :seon.route/pattern)
       (sort-by key)
       (mapv (fn [[pattern rows]]
               [pattern
                (into {}
                      (map (fn [{:seon.route/keys [method handler middleware]}]
                             [method (cond-> {:handler (route-handler handler)}
                                       (some? middleware)
                                       (assoc :middleware [middleware]))]))
                      rows)]))))

(defn db->routes
  "Compile ordinary `:seon.route/*` rows into a reitit route vector.

   GROUP the route entities by `:seon.route/pattern`, nest per
   `:seon.route/method`, wrap `:seon.route/handler` (the late-bound symbol)
   via [[route-handler]], wrap the optional `:seon.route/middleware` keyword for
   [[mw-registry]]. A pure value of the route datoms — a nil/route-less `db`
   yields `[]` (the static supplement keeps the pod serving until the seed
   lands)."
  {:malli/schema [:=> [:catn [::projection [:vector :map]]] [:vector :any]]}
  [projection]
  (projection->routes (route-projection projection)))

;; ============================================================
;; The static supplement — the routes NOT (yet) seeded as `:seon.route/*`
;; datoms, so nothing 404s: static assets, the secondary state-changing POST
;; doors (each `:seon.route/same-origin`-gated), and the operator dev tools
;; (`/data` + `/agent/{id}/debug`,
;; `seon.web.debug`). db->routes supplies the core routes; this supplies
;; the rest. FLAG (coordination → Core): the secondary POST doors below should
;; be seeded as `:seon.route/*` datoms for fully data-driven routing — until
;; then they live here.
;; ============================================================

(defn- post-handler
  "Wrap a serve `(req res)` handler as a reitit ring handler that hijacks."
  [f]
  (fn [r] (f (node-req r) (node-res r)) hijacked))

(defn- admitted-post-handler
  "Refuse state-changing web admission before request parsing or domain work."
  [f]
  (fn [r]
    (if (admission/available?)
      (f (node-req r) (node-res r))
      (write-text!
        (node-res r) 503
        (get-in (admission/unavailable)
                [:seon/error :seon.error/message])))
    hijacked))

(defn- static-supplement
  "The non-core reitit routes built from serve's injected handlers."
  [h]
  (let [{::keys [static readiness chat stop resume clear log
                 complete agent-run config-apply operator-quiesce
                 operator-blobs]} h]
    (cond->
     [["/css/{*path}" {:get {:handler (fn [r] (static (node-res r) (:uri r)) hijacked)}}]
     ["/js/{*path}"  {:get {:handler (fn [r] (static (node-res r) (:uri r)) hijacked)}}]

     ;; The data browser is a static operator route, but its live projection
     ;; rides the same canonical Datastar feed registry as every seeded view.
     ["/data"     {:get {:handler (fn [r] (debug/data-page! (node-req r) (node-res r)) hijacked)}}]
     ["/data/feed" {:get {:handler (fn [r] (debug/data-feed! r) hijacked)}}]
     ["/_seon/ready" {:get {:handler
                             (fn [r]
                               (readiness (node-req r) (node-res r))
                               hijacked)}}]

     ["/chat"        {:post {:middleware [:seon.route/same-origin] :handler (admitted-post-handler chat)}}]
     ["/stop"        {:post {:middleware [:seon.route/same-origin] :handler (post-handler stop)}}]
     ["/resume"      {:post {:middleware [:seon.route/same-origin] :handler (admitted-post-handler resume)}}]
     ["/clear"       {:post {:middleware [:seon.route/same-origin] :handler (admitted-post-handler clear)}}]
     ["/log"         {:post {:middleware [:seon.route/same-origin] :handler (admitted-post-handler log)}}]
     ;; The one-shot composition door: start-or-reuse an agent in THE pod's
     ;; own cluster, deliver the input via the real wake path, run its OWN
     ;; FSM to idle, return the truthful reply + turn/eval metadata as JSON.
     ;; same-origin-gated like the others.
     ["/agents/run"  {:post {:middleware [:seon.route/same-origin] :handler (admitted-post-handler agent-run)}}]
     ["/_seon/operator/config" {:post {:middleware [:seon.route/same-origin]
                                        :handler (admitted-post-handler config-apply)}}]
     ["/agent/{id}/complete" {:post {:middleware [:seon.route/same-origin]
                                     :handler (fn [r] (complete (node-req r) (node-res r)
                                                                (get-in r [:path-params :id]))
                                                hijacked)}}]]
      operator-quiesce
      (conj ["/_seon/operator/quiesce"
             {:post {:middleware [:seon.route/loopback-peer]
                     :handler (post-handler operator-quiesce)}}])
      operator-blobs
      (conj ["/_seon/operator/blobs"
             {:post {:middleware [:seon.route/loopback-peer]
                     :handler (post-handler operator-blobs)}}]))))

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
  "Build one reitit handler from exact routing facts and runtime config."
  [projection config]
  (rr/ring-handler
    (rr/router (into (projection->routes projection)
                     (static-supplement config))
               {:reitit.middleware/registry mw-registry})
    not-found))

(defn- cache-key
  "The complete immutable inputs whose compiled reitit handler is reusable."
  [projection config]
  {::route-projection projection
   ::config           config})

(defn ^:async ^:private reconcile-cache!
  "Acquire and accept one exact route projection when it is still current."
  [owner database]
  (let [rows (await (db/query {:seon.db/query route-query
                               ::db/db database}))]
    (when (:seon.error/message rows)
      (throw (ex-info "Route projection failed." rows)))
    (let [projection (route-projection rows)
          config (::config @!router-state)
          next-key (cache-key projection config)]
      (when (and (identical? owner (::interest-owner @!router-state))
                 (= database (::desired-db @!router-state)))
        (when-not (= next-key (::cache-key @!router-state))
          (swap! !router-state assoc
                 ::cache-key next-key
                 ::ring-handler (build-ring-handler projection config)))
        (swap! !router-state assoc ::accepted-db database)
        true))))

(defn- refresh-routes!
  [owner database]
  (swap! !router-state
         (fn [state]
           (if (identical? owner (::interest-owner state))
             (assoc state ::desired-db database)
             state)))
  (-> (reconcile-cache! owner database)
      (.catch
       (fn [error]
         (log/error-console! "seon.web.router"
                             "route projection refresh failed" error)))))

(defn ^:async ^:private settle-routes!
  [owner]
  (await
   (loop []
     (let [{::keys [interest-owner desired-db accepted-db]}
           @!router-state]
       (cond
         (not (identical? owner interest-owner)) false
         (= desired-db accepted-db) true
         :else
         (do
           (await (reconcile-cache! owner desired-db))
           (recur)))))))

(defn ^:async attach!
  "Attach route-cache invalidation to the canonical database listener bus.

   The stable listener key makes repeated attach and hot reload replacement
   idempotent in Datahike itself. No parallel registry or installed flag is
   maintained. The current database projection is reconciled before return."
  {:malli/schema [:=> [:cat] :any]}
  []
  (if-let [interest-key (::interest-key @!router-state)]
    interest-key
    (let [owner (js-obj)]
      (swap! !router-state assoc
             ::interest-owner owner
             ::desired-db nil
             ::accepted-db nil)
      (let [listening
            (await
             (db/listen!
              {:seon.db/key ::routes
               :seon.db/query route-query
               :seon.db/handler
               (fn [event]
                 (refresh-routes! owner (:db-after event)))}))]
        (when (:seon.error/message listening)
          (swap! !router-state
                 (fn [state]
                   (if (identical? owner (::interest-owner state))
                     (dissoc state ::interest-owner ::desired-db ::accepted-db)
                     state)))
          (throw (ex-info "Route interest failed." listening)))
        (let [database (await (db/db))]
          (when (:seon.error/message database)
            (throw (ex-info "Route database acquisition failed." database)))
          (swap! !router-state
                 (fn [state]
                   (if (identical? owner (::interest-owner state))
                     (assoc state
                            ::interest-key listening
                            ::desired-db database)
                     state))))
        (await (settle-routes! owner))
        listening))))

(defn ^:async detach!
  "Detach route-cache invalidation from the canonical database listener bus."
  {:malli/schema [:=> [:cat] :any]}
  []
  (let [interest-key (::interest-key @!router-state)]
    (swap! !router-state dissoc
           ::interest-owner ::interest-key ::desired-db ::accepted-db)
    (if interest-key
      (await (db/unlisten! interest-key))
      {:seon.db/ok? true})))

(defn install!
  "Inject serve's handlers and rebuild the cached reitit ring-handler.

   Injects serve's handler set + same-origin predicate. serve calls this at
   load and [[attach!]] acquires the route projection before HTTP admission;
   hot reload rebuilds the compiled handler from the already accepted ordinary
   projection. `config` keys:
   `:seon.web.router/{static chat stop resume clear log complete agent-run
   config-apply operator-quiesce operator-blobs}` (the serve handler fns) +
   `:seon.web.router/same-origin?` and `:seon.web.router/loopback-peer?`
   (the predicates). The CORE routes are NOT in
   `config` — they project from the
   `:seon.route/*` datoms via [[db->routes]]."
  {:malli/schema [:=> [:catn [::config :map]] :nil]}
  [config]
  (swap! !router-state
         (fn [state]
           (let [projection (or (get-in state [::cache-key
                                               ::route-projection]) [])]
             (assoc state
                    ::config config
                    ::same-origin-pred
                    (or (::same-origin? config) (constantly true))
                    ::loopback-peer-pred
                    (or (::loopback-peer? config) (constantly false))
                    ::cache-key (cache-key projection config)
                    ::ring-handler (build-ring-handler projection config)))))
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
    (let [rh (::ring-handler @!router-state)
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
