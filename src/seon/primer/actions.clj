(ns seon.primer.actions
  "Action handlers that mutate ctx."
  (:require [seon.primer.state :as state]))

(defn handle-action [action-id]
  ;; Look up action in current scene
  (let [scene (:primer/current-scene @state/ctx)
        actions (:scene/actions scene)
        action (first (filter #(= (:action/id %) action-id) actions))]
    (when action
      ;; For now, just update to a new scene
      ;; SSE refresh happens automatically via ctx watch
      (state/update-ctx! assoc :primer/current-scene
                         {:scene/id "scene-2"
                          :scene/template :narrative/page
                          :scene/params {:text "You continued the story! The adventure unfolds..."}
                          :scene/actions [{:action/id :back
                                           :action/label "Go back"
                                           :action/handler 'seon.primer.actions/back}]}))))
