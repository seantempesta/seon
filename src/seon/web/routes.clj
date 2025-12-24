(ns seon.web.routes
  "Simple map-based router for HTTP endpoints."
  (:require [clojure.string :as str]
            [seon.web.handlers :as handlers]
            [seon.primer.handlers :as primer-handlers]))

(def routes
  {[:get "/"]                  handlers/dashboard
   [:post "/"]                 handlers/dashboard-sse
   [:get "/api/health"]        handlers/health
   [:post "/api/import/start"] handlers/start-import
   [:post "/api/import/stop"]  handlers/stop-import
   [:get "/api/import/status"] handlers/job-status
   [:get "/api/stats"]         handlers/database-stats
   ;; Log viewer routes
   [:get "/logs"]              handlers/log-viewer
   [:post "/logs"]             handlers/log-viewer-sse
   [:post "/api/logs/filter"]  handlers/log-filter
   [:post "/api/logs/refresh"] handlers/log-refresh
   [:post "/api/logs/toggle-scroll"] handlers/log-toggle-scroll
   ;; Primer routes
   [:get "/primer"]                primer-handlers/primer-page
   [:post "/primer"]               primer-handlers/primer-sse})

;; Dynamic routes with path parameters
(def dynamic-routes
  [;; Primer action route: /primer/action/:action-id
   {:method :post
    :pattern #"/primer/action/(.+)"
    :params [:action-id]
    :handler primer-handlers/action-handler}])

(defn match-dynamic-route [method path]
  (some (fn [{route-method :method :keys [pattern params handler]}]
          (when (= method route-method)
            (when-let [matches (re-matches pattern path)]
              {:handler handler
               :path-params (zipmap params (rest matches))})))
        dynamic-routes))

(defn handler [request]
  (let [method (:request-method request)
        path   (:uri request)
        route-handler (routes [method path])]
    (if route-handler
      (route-handler request)
      ;; Try dynamic routes
      (if-let [{:keys [handler path-params]} (match-dynamic-route method path)]
        (handler (assoc request :path-params path-params))
        {:status 404
         :headers {"Content-Type" "application/json"}
         :body "{\"error\": \"Not found\"}"}))))
