(ns my.agent
  "Read the calling agent's own record components."
  (:require [seon.ai :as ai]
            [seon.render.value :as value]))

(defn settings
  "Read your setting overrides; omitted settings inherit the cluster defaults."
  {:malli/schema [:=> [:cat :seon.db/db :seon.cluster.agent/id]
                  [:or :seon.config/agent-overlay :seon.error/value]]}
  [database agent-id]
  (ai/agent-overlay database agent-id))

(defn render-settings-ai
  "Read the settings component as one concern."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [_settings]
  "; Your setting overrides; omitted settings inherit the cluster defaults.\n(my.agent/settings)")

(defn render-settings-html
  "Show the settings component through the value renderer."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.error/value]]}
  [unit]
  (value/render-html
   (assoc unit :seon.render.value/options
          {:seon.render.value/structural? true})))
