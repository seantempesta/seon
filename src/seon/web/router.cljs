(ns seon.web.router
  "Derive the pod's reitit router from route facts.

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
   seeded handler is a Ring handler that takes the Ring request `r`, reads its
   WHATWG Request from `:seon.http/request`, and uses reitit's path params, so
   [[db->routes]] wraps every one
   uniformly. An optional `:seon.route/middleware` keyword resolves through reitit's
   `:reitit.middleware/registry` ([[mw-registry]]).

   ## Bun Request/Response boundary

   [[request->ring]] translates Bun's WHATWG Request into the ordinary Ring
   routing fields and retains the Request for body/header access. Handlers
   return WHATWG Response values (or Promises of them); only Datastar's body is
   a long-lived direct ReadableStream. No handler takes ownership of a socket.

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
;; HTTP responses remain ordinary WHATWG values all the way to Bun.serve.
;; ============================================================

(defn- text-response [code body]
  (js/Response. body #js {:status code
                          :headers #js {"Content-Type" "text/plain; charset=utf-8"
                                        "Cache-Control" "no-store"}}))

(defn- ring-response [{:keys [status headers body]}]
  (js/Response. (or body "")
                #js {:status (or status 200)
                     :headers (clj->js (or headers {}))}))

;; ============================================================
;; Node↔Ring adapter.
;; ============================================================

(defn- request->ring
  "Translate one WHATWG Request into the ordinary data reitit consumes."
  [^js req ^js server]
  (let [url    (js/URL. (.-url req) "http://127.0.0.1")
        uri    (.-pathname url)
        qs     (some-> (.-search url) (subs 1) not-empty)
        method (-> (or (.-method req) "GET") str/lower-case keyword)]
    {:request-method      method
     :uri                 uri
     :query-string        qs
     :headers             (into {}
                                (map (fn [[k v]] [k v]))
                                (es6-iterator-seq (.entries (.-headers req))))
     :seon.http/request   req
     :seon.http/server    server}))

;; ============================================================
;; Same-origin middleware — a reitit middleware on every state-changing
;; POST route. Delegates to the injected predicate (serve owns the
;; same-origin? logic verbatim); a cross-origin POST is refused 403 and
;; the request is refused before the route handler runs.
;; ============================================================

(def ^:private same-origin-mw
  {:name ::same-origin
   :wrap (fn [handler]
           (fn [r]
             (if ((::same-origin-pred @!router-state) (:seon.http/request r))
               (handler r)
               (do
                 (log/info-console! "seon.web.router" "POST cross-origin REFUSED"
                                    {:path (:uri r)})
                 (text-response 403 "cross-origin POST refused")))))})

(def ^:private loopback-peer-mw
  {:name ::loopback-peer
   :wrap (fn [handler]
           (fn [r]
             (if ((::loopback-peer-pred @!router-state)
                  (:seon.http/request r) (:seon.http/server r))
               (handler r)
               (do
                 (log/info-console! "seon.web.router"
                                    "operator request from non-loopback peer REFUSED"
                                    {:path (:uri r)})
                 (text-response 403 "loopback operator request required")))))})

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
   request `r`, and returns its Response. An unresolved symbol degrades to a
   500 Response."
  [sym]
  (fn [r]
    (if-let [f (seval/lookup-value sym)]
      (f r)
      (do (log/error-console! "seon.web.router" "route handler unresolved"
                              {:sym (str sym) :path (:uri r)})
          (text-response 500 (str "route handler unresolved: " sym))))))

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
  "Wrap a serve request handler as a reitit ring handler."
  [f]
  (fn [r] (f (:seon.http/request r) nil)))

(defn- admitted-post-handler
  "Refuse state-changing web admission before request parsing or domain work."
  [f]
  (fn [r]
    (if (admission/available?)
      (f (:seon.http/request r) nil)
      (text-response
        503
        (get-in (admission/unavailable)
                [:seon/error :seon.error/message])))))

(defn- static-supplement
  "The non-core reitit routes built from serve's injected handlers."
  [h]
  (let [{::keys [static readiness chat stop resume clear log
                 complete agent-run config-apply operator-quiesce
                 operator-blobs operator-processes product-evidence]} h]
    (cond->
     [["/css/{*path}" {:get {:handler (fn [r] (static nil (:uri r)))}}]
     ["/js/{*path}"  {:get {:handler (fn [r] (static nil (:uri r)))}}]

     ;; The data browser is a static operator route, but its live projection
     ;; rides the same canonical Datastar feed registry as every seeded view.
     ["/data"     {:get {:handler debug/data-page!}}]
     ["/data/feed" {:get {:handler debug/data-feed!}}]
     ["/_seon/ready" {:get {:handler
                             (fn [r]
                               (readiness (:seon.http/request r) nil))}}]

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
                                     :handler (fn [r] (complete (:seon.http/request r) nil
                                                                (get-in r [:path-params :id])))}}]]
      operator-quiesce
      (conj ["/_seon/operator/quiesce"
             {:post {:middleware [:seon.route/loopback-peer]
                     :handler (post-handler operator-quiesce)}}])
      operator-blobs
      (conj ["/_seon/operator/blobs"
             {:post {:middleware [:seon.route/loopback-peer]
                     :handler (post-handler operator-blobs)}}])
      operator-processes
      (conj ["/_seon/operator/processes"
             {:get {:middleware [:seon.route/loopback-peer]
                    :handler
                    (fn [r]
                      (operator-processes (:seon.http/request r) nil))}}])
      product-evidence
      (conj ["/_seon/operator/product-evidence"
             {:post {:middleware [:seon.route/loopback-peer]
                     :handler (post-handler product-evidence)}}]))))

;; ============================================================
;; The no-match default-handler — a graceful redirect HOME (#28). reitit
;; calls this when no route matches (or the matched path has no handler for
;; the method). Rather than a raw 404 dead-end, a miss 302s to `/` (root's
;; system dashboard) so a mistyped/stale URL always lands somewhere live.
;; ============================================================

(defn- not-found [_r]
  (js/Response. "" #js {:status 302
                         :headers #js {"Location" "/"
                                       "Cache-Control" "no-store"}}))

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
   config-apply operator-quiesce operator-blobs operator-processes
   product-evidence}` (the serve handler fns) +
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
  "The single Bun.serve request entry point."
  [^js req ^js server]
  (try
    (let [rh (::ring-handler @!router-state)
          normalize (fn [result]
                      (cond
                        (instance? js/Response result) result
                        (map? result) (ring-response result)
                        :else (text-response 404 (str "Not found: " (.-url req)))))
          result (rh (request->ring req server))]
      (if (instance? js/Promise result)
        (.then result normalize)
        (normalize result)))
    (catch :default e
      (log/error-console! "seon.web.router" "handle-request error" e)
      (text-response 500 (str "Internal error: " e)))))
