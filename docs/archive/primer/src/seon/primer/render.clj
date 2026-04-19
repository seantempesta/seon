(ns seon.primer.render
  "Render engine - maps ctx keys to render functions.

   The pattern: each namespaced key in ctx can have a registered
   render function. Rendering walks ctx and calls the appropriate
   function for each key that needs to be displayed."
  (:require [dev.onionpancakes.chassis.core :as h]))

;; Registry: keyword -> (fn [ctx value] hiccup)
(defonce renderers (atom {}))

(defn register! [k render-fn]
  (swap! renderers assoc k render-fn))

(defn render-key [ctx k]
  (when-let [renderer (get @renderers k)]
    (renderer ctx (get ctx k))))

;; Render all registered keys for current view
(defn render-view [ctx]
  (let [view-keys (or (:ui/view-keys ctx) [:primer/current-scene])]
    [:div#morph.primer-view
     (for [k view-keys]
       (render-key ctx k))]))
