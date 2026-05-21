(ns seon.web.routes
  "Simple map-based router for HTTP endpoints.
   Includes static file serving for /css/* from resources/public."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [seon.web.handlers :as handlers]
            [seon.web.flows :as flows]
            [seon.web.browser :as browser]
            [seon.ns.routes :as ns-routes]))

;; Use var references (#') so handlers resolve to current fn after reload
(def routes
  {[:get "/"]                  #'handlers/dashboard
   [:post "/"]                 #'handlers/dashboard-sse
   [:get "/api/health"]        #'handlers/health-check
   ;; Log viewer routes
   [:get "/logs"]              #'handlers/log-viewer
   [:post "/logs"]             #'handlers/log-viewer-sse
   [:post "/api/logs/filter"]  #'handlers/log-filter
   [:post "/api/logs/refresh"] #'handlers/log-refresh
   ;; Flow monitor routes
   [:get "/flows"]                 #'flows/flows-page
   [:post "/flows"]                #'flows/flows-sse
   ;; Agent observatory routes — handlers pending restoration against
   ;; the :seon.ai datahike namespace.
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
   ;; Function read: GET /ns/:namespace/:function?:qualified/key=value
   {:method :get
    :pattern #"/ns/([a-z][a-z0-9._-]*)/([a-zA-Z][a-zA-Z0-9_!?*%.-]*)"
    :params [:namespace :function]
    :handler #'ns-routes/function-get-handler}
   ;; Agent detail routes removed in M-2 (see comment in static routes above).
   ;; Namespace view routes: /ns/{namespace}?id=session_id
   ;; Uses seon.ns.view multimethod rendering system
   ;; Also handles legacy /{dotted.namespace} URLs via redirect below
   {:method :get
    :pattern #"/ns/([a-z][a-z0-9._-]*)"
    :params [:namespace]
    :handler #'ns-routes/namespace-page}
   {:method :post
    :pattern #"/ns/([a-z][a-z0-9._-]*)"
    :params [:namespace]
    :handler #'ns-routes/namespace-sse}
   ;; Legacy redirect: /seon.foo.bar -> /ns/seon.foo.bar
   ;; Keeps old bookmarks working while unifying on /ns/ pattern
   {:method :get
    :pattern #"/([a-z][a-z0-9._-]*\.[a-z][a-z0-9._-]*)"
    :params [:namespace]
    :handler (fn [req]
               {:status 301
                :headers {"Location" (str "/ns/" (get-in req [:path-params :namespace]))}
                :body ""})}])

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
        ;; http-kit backslash-escapes ! ? * in URIs — strip backslashes
        path   (str/replace (:uri request) "\\" "")
        route-handler (routes [method path])]
    (if route-handler
      (route-handler request)
      ;; Try dynamic routes
      (if-let [{:keys [handler path-params]} (match-dynamic-route method path)]
        (handler (assoc request :path-params
                        (update-vals path-params #(str/replace % "\\" ""))))
        ;; Try static files
        (or (when (= method :get) (serve-static path))
            {:status 404
             :headers {"Content-Type" "application/json"}
             :body "{\"error\": \"Not found\"}"})))))
