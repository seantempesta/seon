(ns seon.primer.handlers
  "HTTP handlers for Primer routes."
  (:require [seon.primer.html :as html]
            [seon.primer.ctx :as ctx]
            [seon.primer.actions :as actions]
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
       (html/primer-content session-id)))))

(defn action-handler [request]
  (let [action-id (keyword (get-in request [:path-params :action-id]))
        session-id (get-session-id request)]
    (actions/handle-action session-id action-id)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"ok\": true}"}))
