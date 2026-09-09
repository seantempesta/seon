(ns seon.render.agent
  "Identity projections for agent entities reached by the walk.

  Lifecycle state derives from turn facts at the turn owner.")

;;; ---------------------------------------------------------------------------
;;; The renders
;;; ---------------------------------------------------------------------------

(defn agent-ai
  "Name the agent from its identity; lifecycle belongs to its turn facts."
  {:malli/schema [:=> [:cat :seon.render/unit] [:maybe :string]]}
  [unit]
  (when-let [id (get unit :seon.cluster.agent/id)]
    (str "Agent " id ".")))

(defn agent-html
  "Render the agent identity as HTML."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:maybe :seon.render/hiccup]]}
  [unit]
  (when-let [text (agent-ai unit)]
    [:article {:class "seon-family-entry seon-agent-entry"}
     [:p text]]))
