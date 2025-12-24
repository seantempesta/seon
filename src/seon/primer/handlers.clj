(ns seon.primer.handlers
  "HTTP handlers for Primer routes."
  (:require [seon.primer.html :as html]
            [seon.primer.state :as state]
            [seon.web.sse :as sse]))

(defn primer-page [_request]
  {:status 200
   :headers {"Content-Type" "text/html"}
   :body (html/primer-page)})

(def primer-sse
  (sse/render-handler
   (fn [_request]
     (html/primer-content))))
