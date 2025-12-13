(ns seon.web.routes
  "Simple map-based router for HTTP endpoints."
  (:require [seon.web.handlers :as handlers]))

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
   [:post "/api/logs/toggle-scroll"] handlers/log-toggle-scroll})

(defn handler [request]
  (let [method (:request-method request)
        path   (:uri request)
        route-handler (routes [method path])]
    (if route-handler
      (route-handler request)
      {:status 404
       :headers {"Content-Type" "application/json"}
       :body "{\"error\": \"Not found\"}"})))
