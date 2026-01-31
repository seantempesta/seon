(ns seon.web.routes
  "Simple map-based router for HTTP endpoints.
   Includes static file serving for /css/* from resources/public/."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [seon.web.handlers :as handlers]
            [seon.web.agents :as agents]
            [seon.web.namespace :as namespace]
            [seon.web.browser :as browser]
            [seon.ns.routes :as ns-routes]
            [seon.primer.handlers :as primer-handlers]
            [seon.web.reactive.demo :as reactive-demo]))

;; Use var references (#') so handlers resolve to current fn after reload
(def routes
  {[:get "/"]                  #'handlers/dashboard
   [:post "/"]                 #'handlers/dashboard-sse
   [:get "/api/health"]        #'handlers/health
   ;; Log viewer routes
   [:get "/logs"]              #'handlers/log-viewer
   [:post "/logs"]             #'handlers/log-viewer-sse
   [:post "/api/logs/filter"]  #'handlers/log-filter
   [:post "/api/logs/refresh"] #'handlers/log-refresh
   ;; Primer routes
   [:get "/primer"]                #'primer-handlers/primer-page
   [:post "/primer"]               #'primer-handlers/primer-sse
   ;; Primer debug routes
   [:get "/primer/ctx"]            #'primer-handlers/ctx-handler
   [:get "/primer/debug"]          #'primer-handlers/debug-page-handler
   ;; Agent observatory routes
   [:get "/agents"]                #'agents/agents-page
   [:post "/agents"]               #'agents/agents-sse
   [:post "/api/agents/toggle-completed"] #'agents/toggle-completed-handler
   ;; Reactive UI demo routes (GET=page, POST=SSE)
   [:get "/reactive-demo"]         #'reactive-demo/handler
   [:post "/reactive-demo"]        #'reactive-demo/handler
   ;; Browser execution bridge result callback
   [:post "/api/browser/result"]   #'browser/result-handler})

;; Dynamic routes with path parameters
;; Use var references (#') so handlers resolve to current fn after reload
(def dynamic-routes
  [;; Function call route: POST /ns/:namespace/:function
   ;; Must come BEFORE namespace routes (more specific pattern)
   ;; Pattern includes % to allow URL-encoded characters like %21 for !
   {:method :post
    :pattern #"/ns/([a-z][a-z0-9._-]*)/([a-zA-Z][a-zA-Z0-9_!?*%.-]*)"
    :params [:namespace :function]
    :handler #'ns-routes/function-call-handler}
   ;; Primer action route: /primer/action/:action-id
   {:method :post
    :pattern #"/primer/action/(.+)"
    :params [:action-id]
    :handler #'primer-handlers/action-handler}
   ;; Agent detail routes: /agents/:agent-id
   {:method :get
    :pattern #"/agents/([a-f0-9]+)"
    :params [:agent-id]
    :handler #'agents/agent-detail-page}
   {:method :post
    :pattern #"/agents/([a-f0-9]+)"
    :params [:agent-id]
    :handler #'agents/agent-detail-sse}
   ;; Namespace view routes: /ns/{namespace}?id=session_id
   ;; Uses seon.ns.view multimethod rendering system
   {:method :get
    :pattern #"/ns/([a-z][a-z0-9._-]*)"
    :params [:namespace]
    :handler #'ns-routes/namespace-page}
   {:method :post
    :pattern #"/ns/([a-z][a-z0-9._-]*)"
    :params [:namespace]
    :handler #'ns-routes/namespace-sse}
   ;; Legacy namespace introspection routes: /{namespace} where namespace contains dots
   ;; e.g., /seon.ai.claude, /seon.web.handlers
   ;; Must be last since it's a catch-all for dotted paths
   {:method :get
    :pattern #"/([a-z][a-z0-9._-]*\.[a-z][a-z0-9._-]*)"
    :params [:namespace]
    :handler #'namespace/namespace-page}
   {:method :post
    :pattern #"/([a-z][a-z0-9._-]*\.[a-z][a-z0-9._-]*)"
    :params [:namespace]
    :handler #'namespace/namespace-sse}])

(defn match-dynamic-route [method path]
  (some (fn [{route-method :method :keys [pattern params handler]}]
          (when (= method route-method)
            (when-let [matches (re-matches pattern path)]
              {:handler handler
               :path-params (zipmap params (rest matches))})))
        dynamic-routes))

(defn- serve-static
  "Serve static files from resources/public. Returns nil if not found.
   Cache headers are handled by wrap-no-cache middleware in server.clj."
  [path]
  (when-let [[content-type] (cond
                              (str/starts-with? path "/css/") ["text/css"]
                              (str/starts-with? path "/js/")  ["application/javascript"]
                              :else nil)]
    (let [resource-path (str "public" path)]
      (when-let [resource (io/resource resource-path)]
        {:status 200
         :headers {"Content-Type" content-type}
         :body (slurp resource)}))))

(defn handler [request]
  (let [method (:request-method request)
        path   (:uri request)
        route-handler (routes [method path])]
    (if route-handler
      (route-handler request)
      ;; Try dynamic routes
      (if-let [{:keys [handler path-params]} (match-dynamic-route method path)]
        (handler (assoc request :path-params path-params))
        ;; Try static files
        (or (when (= method :get) (serve-static path))
            {:status 404
             :headers {"Content-Type" "application/json"}
             :body "{\"error\": \"Not found\"}"})))))
