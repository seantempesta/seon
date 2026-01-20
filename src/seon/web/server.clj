(ns seon.web.server
  "HTTP server Integrant component using http-kit."
  (:require [integrant.core :as ig]
            [org.httpkit.server :as hk]
            [taoensso.timbre :as log]
            [seon.web.routes :as routes]
            [seon.web.jobs :as jobs]
            [seon.web.agents :as agents]
            [seon.web.logs :as logs]
            [seon.web.sse :as sse]))

(defmethod ig/init-key ::http-server
  [_ {:keys [port bind handler node]}]
  ;; Initialize modules with XTDB node
  (when node
    (jobs/init! node)
    (agents/init! node))

  ;; Initialize SSE broadcast infrastructure with 100ms throttle
  (let [refresh-mult (sse/init-sse! :max-refresh-ms 100)]

    ;; Initialize log viewer with state watcher
    (logs/init-log-watcher!)

    ;; Wrap handler with SSE middleware
    ;; Use var wrapper so handler picks up namespace reloads
    (let [handler-fn (or handler #'routes/handler)
          wrapped-handler (sse/wrap-refresh-mult handler-fn refresh-mult)
          server (hk/run-server wrapped-handler {:port port :ip bind})]
      (log/info "HTTP server started" {:port port :bind bind})
      {:server server
       :refresh-mult refresh-mult})))

(defmethod ig/halt-key! ::http-server
  [_ state]
  ;; Shut down SSE first to prevent core.async protocol corruption on reload
  (sse/shutdown-sse!)
  (when-let [server (:server state)]
    (server :timeout 3000)  ;; Graceful shutdown with 3s timeout
    (log/info "HTTP server stopped")))
