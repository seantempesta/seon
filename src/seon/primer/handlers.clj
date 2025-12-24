(ns seon.primer.handlers
  "HTTP handlers for Primer routes."
  (:require [seon.primer.html :as html]
            [seon.primer.state :as state]
            [seon.primer.actions :as actions]
            [seon.web.sse :as sse]))

(defn primer-page [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (html/primer-page)})

(def primer-sse
  (sse/render-handler
   (fn [_request]
     (html/primer-content))))

(defn action-handler [request]
  (let [action-id (keyword (get-in request [:path-params :action-id]))]
    (actions/handle-action action-id)
    {:status 200
     :headers {"Content-Type" "application/json"}
     :body "{\"ok\": true}"}))
