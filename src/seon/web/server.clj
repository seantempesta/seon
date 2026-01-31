(ns seon.web.server
  "HTTP server Integrant component using http-kit."
  (:require [integrant.core :as ig]
            [org.httpkit.server :as hk]
            [taoensso.timbre :as log]
            [jsonista.core :as json]
            [seon.ai.agent :as ai-agent]
            [seon.web.routes :as routes]
            [seon.web.jobs :as jobs]
            [seon.web.agents :as agents]
            [seon.web.sse :as sse])
  (:import [java.io InputStream]))

;; ============================================================================
;; Middleware
;; ============================================================================

(defn- wrap-system
  "Middleware that injects system components into requests."
  [handler system]
  (fn [request]
    (handler (assoc request :system system))))

(defn- wrap-json-body
  "Middleware that parses JSON request bodies.

   When Content-Type is application/json, reads the body InputStream and
   parses it as JSON, replacing :body with the parsed map.

   Datastar sends signals as JSON via @post(), so this is required for
   action handlers to receive signal data."
  [handler]
  (fn [request]
    (let [content-type (get-in request [:headers "content-type"] "")
          body (:body request)]
      (log/debug "wrap-json-body" {:uri (:uri request)
                                   :content-type content-type
                                   :body-type (type body)
                                   :headers (:headers request)})
      (if (and (instance? InputStream body)
               (or (.startsWith content-type "application/json")
                   (.startsWith content-type "text/json")))
        (try
          (let [parsed (json/read-value body json/keyword-keys-object-mapper)]
            (log/debug "Parsed JSON body" {:parsed parsed})
            (handler (assoc request :body parsed)))
          (catch Exception e
            (log/warn "Failed to parse JSON body" {:error (.getMessage e)})
            (handler request)))
        (handler request)))))

(defn- wrap-no-cache
  "Middleware that prevents browser caching of ALL responses.

   CRITICAL FOR DEV: Without this, browsers cache HTML/CSS/JS and serve stale
   content even after code changes. This caused hours of debugging where
   'full server restart still served old code' - it was browser cache.

   In dev, caching costs nothing but causes massive headaches. Later we can
   add versioned filenames for production cache optimization."
  [handler]
  (fn [request]
    (when-let [response (handler request)]
      (update response :headers merge
              {"Cache-Control" "no-store, no-cache, must-revalidate"
               "Pragma" "no-cache"
               "Expires" "0"}))))

(defmethod ig/init-key ::http-server
  [_ {:keys [port bind handler node]}]
  ;; Initialize modules with XTDB node
  (when node
    (jobs/init! node)
    (agents/init! node)
    (ai-agent/init! node))

  ;; Initialize SSE broadcast infrastructure with 100ms throttle
  (let [refresh-mult (sse/init-sse! :max-refresh-ms 100)
        ;; Build system map to inject into requests
        system {:seon/xtdb-node node}
        ;; HOT RELOAD FIX: Use requiring-resolve at REQUEST TIME.
        ;;
        ;; WHY: clj-reload can create NEW Var objects when reloading namespaces.
        ;; If we capture #'routes/handler at startup, we hold the OLD Var object.
        ;; The REPL sees the new Var, but our captured reference is "orphaned".
        ;;
        ;; FIX: Use requiring-resolve inside the request handler to always
        ;; fetch the CURRENT Var from the namespace at the moment of the request.
        ;; This guarantees we always use the latest code, regardless of how
        ;; the reload happened.
        ;;
        ;; PERF: requiring-resolve is fast (just a map lookup after first call).
        ;; For production, could conditionally use a static reference.
        handler-sym (if handler
                      (symbol (str (:ns (meta handler))) (str (:name (meta handler))))
                      'seon.web.routes/handler)
        request-handler (fn [req]
                          ;; LATE BINDING: Resolve symbol to CURRENT Var on every request
                          (let [current-handler (requiring-resolve handler-sym)]
                            ((-> current-handler
                                 (wrap-system system)
                                 (sse/wrap-refresh-mult refresh-mult)
                                 (wrap-json-body)
                                 (wrap-no-cache))
                             req)))
        server (hk/run-server request-handler {:port port :ip bind})]
    (log/info "HTTP server started" {:port port :bind bind})
    {:server server
     :refresh-mult refresh-mult}))

(defmethod ig/halt-key! ::http-server
  [_ state]
  ;; Shut down SSE first to prevent core.async protocol corruption on reload
  (sse/shutdown-sse!)
  (when-let [server (:server state)]
    (server :timeout 3000)  ;; Graceful shutdown with 3s timeout
    (log/info "HTTP server stopped")))
