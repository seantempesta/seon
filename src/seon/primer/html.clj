(ns seon.primer.html
  "Primer HTML pages and SSE content."
  (:require [dev.onionpancakes.chassis.core :as h]
            [seon.primer.ctx :as ctx]
            [seon.primer.render :as render]
            [seon.primer.render.scene] ; Load to register renderer
            [seon.primer.styles :as styles]))

(def ^:const default-session "default")

(defn primer-content
  "Render primer content for a session."
  ([] (primer-content default-session))
  ([session-id]
   (h/html (render/render-view (ctx/get session-id)))))

(defn primer-page []
  (h/html
   [:html
    [:head
     [:meta {:charset "utf-8"}]
     [:meta {:name "viewport" :content "width=device-width, initial-scale=1"}]
     [:title "Primer"]
     [:script {:src "https://cdn.jsdelivr.net/gh/starfederation/datastar@1.0.0-RC.6/bundles/datastar.js"
               :defer true :type "module"}]
     [:style styles/base-css]]
    [:body {:style "margin: 0; padding: 0;"}
     [:div {:data-init "@post('/primer')"}]
     [:main#morph [:p {:style "color: #666; text-align: center; padding: 2rem;"} "Loading..."]]
     ;; Debug toggle - always present, opens debug page
     [:a#debug-toggle {:href "/primer/debug"
                       :target "_blank"
                       :title "Open Debug Panel"
                       :style "position: fixed; bottom: 1rem; right: 1rem; z-index: 9999;
                               background: #1f2937; color: white; padding: 0.5rem 0.75rem;
                               border-radius: 9999px; font-family: monospace; font-size: 0.75rem;
                               text-decoration: none; box-shadow: 0 4px 6px rgba(0,0,0,0.3);
                               opacity: 0.7; transition: opacity 0.2s;"}
      {:data-on:mouseenter "this.style.opacity = '1'"
       :data-on:mouseleave "this.style.opacity = '0.7'"}
      "DBG"]]]))
