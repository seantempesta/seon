(ns seon.primer.handlers
  "HTTP handlers for Primer routes."
  (:require [seon.primer.html :as html]
            [seon.primer.ctx :as ctx]
            [seon.primer.actions :as actions]
            [seon.primer.debug :as debug]
            [seon.web.sse :as sse]))

(defn- get-session-id
  "Extract session-id from request, defaulting to 'default'."
  [request]
  (or (get-in request [:params :session-id])
      (get-in request [:query-params "session-id"])
      html/default-session))

(defn primer-page [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (html/primer-page)})

(def primer-sse
  (sse/render-handler
   (fn [request]
     (let [session-id (get-session-id request)]
       ;; Ensure session exists with initial scene before rendering
       (actions/ensure-session! session-id)
       (html/primer-content session-id)))))

(defn action-handler [request]
  (let [action-id (keyword (get-in request [:path-params :action-id]))
        session-id (get-session-id request)]
    (actions/handle-action session-id action-id)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"ok\": true}"}))

;;; === Debug Endpoints ===

(defn ctx-handler
  "EDN ctx dump - the canonical debug format.
   GET /primer/ctx -> Pretty-printed EDN"
  [request]
  (let [session-id (get-session-id request)]
    (actions/ensure-session! session-id)
    {:status 200
     :headers {"Content-Type" "application/edn"}
     :body (debug/ctx->edn (ctx/get session-id))}))

(defn debug-page-handler
  "Standalone debug page.
   GET /primer/debug"
  [request]
  (let [session-id (get-session-id request)]
    (actions/ensure-session! session-id)
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (debug/debug-page session-id)}))
