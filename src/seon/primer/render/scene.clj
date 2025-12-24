(ns seon.primer.render.scene
  "Scene renderer - handles :primer/current-scene"
  (:require [seon.primer.render :as r]))

(defn render-action [{:keys [action/id action/label]}]
  [:button.action-btn
   {:data-on:click (str "@post('/primer/action/" (name id) "')")}
   label])

(defn render-scene [ctx scene]
  (let [{:keys [scene/template scene/params scene/actions]} scene]
    [:div.scene {:data-template (name template)}
     ;; Background layer (z-0)
     [:div.layer.layer-bg
      (when-let [bg (:background params)]
        [:div.background {:style {:background-image (str "url(" bg ")")}}])]

     ;; Content layer (z-10)
     [:div.layer.layer-content
      (case template
        :narrative/page
        [:div.narrative
         [:p.story-text (:text params)]]

        ;; Default: just show params
        [:pre (pr-str params)])]

     ;; Actions layer (z-20)
     [:div.layer.layer-actions
      (when (seq actions)
        [:div.action-bar
         (map render-action actions)])]]))

;; Register this renderer
(r/register! :primer/current-scene render-scene)
