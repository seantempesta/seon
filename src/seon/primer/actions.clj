(ns seon.primer.actions
  "Action handlers that mutate ctx."
  (:require [seon.primer.ctx :as ctx]))

(defn handle-action
  "Handle an action for a session. SSE refresh happens automatically via ctx watch."
  [session-id action-id]
  ;; Look up action in current scene
  (let [session-ctx (ctx/get session-id)
        scene (:primer/current-scene session-ctx)
        actions (:scene/actions scene)
        action (first (filter #(= (:action/id %) action-id) actions))]
    (when action
      ;; For now, just update to a new scene
      ;; SSE refresh happens automatically via sessions watch
      (ctx/assoc! session-id :primer/current-scene
                  {:scene/id "scene-2"
                   :scene/template :narrative/page
                   :scene/params {:text "You continued the story! The adventure unfolds..."}
                   :scene/actions [{:action/id :back
                                    :action/label "Go back"
                                    :action/handler 'seon.primer.actions/back}]}))))
