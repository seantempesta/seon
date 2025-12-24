(ns seon.primer.html
  "Primer HTML pages and SSE content."
  (:require [dev.onionpancakes.chassis.core :as h]
            [seon.primer.state :as state]
            [seon.primer.render :as render]
            [seon.primer.render.scene] ; Load to register renderer
            [seon.primer.styles :as styles]))

(defn primer-content []
  (h/html (render/render-view @state/ctx)))

(defn primer-page []
  (h/html
   [:html
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "Primer"]
     [:script {:src "https://cdn.jsdelivr.net/npm/@sudodevnull/datastar@1.0.0-beta.1/bundles/datastar.js"
               :defer true :type "module"}]
     [:style styles/base-css]]
    [:body {:style "margin: 0; padding: 0;"}
     [:div {:data-on-load "@post('/primer')"}]
     [:main#morph [:p {:style "color: #666; text-align: center; padding: 2rem;"} "Loading..."]]]]))
