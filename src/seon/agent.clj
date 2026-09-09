(ns seon.agent
  "Read the calling agent's own record components."
  (:require [seon.ai :as ai]
            [seon.db :as db]
            [seon.config :as config]))

(defn settings
  "Read your setting overrides; omitted settings inherit the cluster defaults."
  {:malli/schema [:=> [:cat :seon.db/db :seon.agent/id]
                  [:or :seon.config/agent-overlay :seon.error/value]]}
  [database agent-id]
  (ai/agent-overlay database agent-id))

(defn- update-settings-call
  [database agent-id overrides]
  (let [agent (db/pull database '[:db/id {:seon.agent/settings [:db/id]}]
                       [:seon.agent/id agent-id])]
    (when-not (:db/id agent)
      (throw (ex-info "The agent whose settings were requested does not exist."
                      {:seon.error/kind :seon.agent/no-such-agent
                       :seon.agent/no-such-agent agent-id})))
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
                      :seon.db/connection :seon.agent/id]
                  [:or :seon.config/agent-overlay :seon.error/value]]}
  [overrides connection agent-id]
  (let [result (db/transact! connection
                             {:tx-data [[:db.fn/call #'update-settings-call
                                         agent-id overrides]]
                              :tx-meta {:seon.db/user
                                        [:seon.agent/id agent-id]}})]
    (if (:seon.error/kind result)
      result
      (settings (:db-after result) agent-id))))

(defn render-settings-ai
  "Read overrides and the schema-declared dials, with an example change form."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [_settings]
  (str "; Your overrides inherit omitted defaults; change one with (my.agent/settings! {:seon.config.eval/time-limit-ms 5000}).\n"
       "(my.agent/settings)\n(seon.ai/agent-setting-attributes)"))

(defn render-settings-html
  "Show every declared agent dial, its effective value, and where it comes from."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.error/value]]}
  [unit]
  (let [database (:seon.db/db unit)
        component (or (:seon.render/value unit) unit)
        attributes (if database (ai/agent-setting-attributes database)
                       (set (keys (dissoc component :db/id))))
        cluster-name (when database
                       (db/q '[:find ?name . :where [_ :seon.config/cluster ?name]] database))
        defaults (if cluster-name (config/effective database cluster-name) {})]
    (cond
      (:seon.error/kind attributes) attributes
      (:seon.error/kind defaults) defaults
      :else
      (let [overrides (select-keys component attributes)
            effective (merge defaults overrides)]
        [:section {:class "seon-family-entry seon-agent-settings seon-agent-content"}
         [:h3 "Settings"]
         [:p "Agent overrides take precedence over cluster defaults."]
         [:table {:style {:table-layout "fixed" :width "100%"}}
          [:colgroup [:col {:style {:width "48%"}}]
           [:col {:style {:width "34%"}}] [:col {:style {:width "18%"}}]]
          [:thead [:tr [:th "Setting"] [:th "Value"] [:th "Source"]]]
          (into [:tbody]
                (map (fn [attribute]
                       [:tr
                        [:th {:scope "row" :style {:overflow-wrap "anywhere"
                                                  :text-transform "none" :letter-spacing "normal"}}
                         [:code (str attribute)]]
                        [:td {:style {:overflow-wrap "anywhere"}} [:code (if-let [entry (find effective attribute)]
                                      (pr-str (val entry)) "Not set")]]
                        [:td (cond (find overrides attribute) "Agent override"
                                   (find defaults attribute) "Cluster default"
                                   :else "Not set")]]))
                (sort attributes))]
         [:p "Change an override:"]
         [:pre [:code "(my.agent/settings! {:seon.config.eval/time-limit-ms 5000})"]]]))))
