(ns my.agent
  "Read the calling agent's own record components."
  (:require [seon.ai :as ai]
            [seon.db :as db]
            [seon.render.value :as value]))

(defn settings
  "Read your setting overrides; omitted settings inherit the cluster defaults."
  {:malli/schema [:=> [:cat :seon.db/db :seon.cluster.agent/id]
                  [:or :seon.config/agent-overlay :seon.error/value]]}
  [database agent-id]
  (ai/agent-overlay database agent-id))

(defn- update-settings-call
  [database agent-id overrides]
  (let [agent (db/pull database '[:db/id {:seon.agent/settings [:db/id]}]
                       [:seon.cluster.agent/id agent-id])]
    (when-not (:db/id agent)
      (throw (ex-info "The agent whose settings were requested does not exist."
                      {:seon.error/kind :seon.cluster.agent/no-such-agent
                       :seon.cluster.agent/no-such-agent agent-id})))
    (let [component (or (get-in agent [:seon.agent/settings :db/id]) "agent-settings")
          attributes (ai/agent-setting-attributes database)
          _ (when (:seon.error/kind attributes)
              (throw (ex-info (:seon.error/message attributes) attributes)))
          admitted (select-keys overrides attributes)]
      (if (seq admitted)
        [(assoc admitted :db/id component)
         [:db/add (:db/id agent) :seon.agent/settings component]]
        []))))

(defn settings!
  "Change your setting overrides. Omitted keys keep their current values;
  settings without an override inherit the cluster defaults. Return the
  resulting overrides. The writer updates the one owned component."
  {:malli/schema [:=> [:cat :seon.config/agent-overlay
                      :seon.db/connection :seon.cluster.agent/id]
                  [:or :seon.config/agent-overlay :seon.error/value]]}
  [overrides connection agent-id]
  (let [result (db/transact! connection
                             {:tx-data [[:db.fn/call #'update-settings-call
                                         agent-id overrides]]
                              :tx-meta {:seon.db/user
                                        [:seon.cluster.agent/id agent-id]}})]
    (if (:seon.error/kind result)
      result
      (settings (:db-after result) agent-id))))

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
