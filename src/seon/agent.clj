(ns seon.agent
  "Read the calling agent's own record components."
  (:refer-clojure :exclude [identity])
  (:require [seon.turn :as turn]
            [seon.ai :as ai]
            [seon.db :as db]
            [seon.repl :as repl]
            [seon.config :as config]))

(defn identity
  "Read stable identity values without projecting a stored ref as a scalar."
  {:malli/schema [:=> [:cat :seon.db/db :seon.agent/id]
                  [:or :my.agent/identity :seon.error/value]]}
  [database agent-id]
  (let [row (db/pull database
                     [:seon.agent/id {:seon.agent/namespace
                                      [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]
                     [:seon.agent/id agent-id])]
    (cond
      (:seon.error/kind row) row
      (not (:seon.agent/id row))
      {:seon.agent/no-such-agent agent-id
       :seon.error/kind :seon.agent/no-such-agent
       :seon.error/message (str "No agent has id " (pr-str agent-id) ".")}
      :else
      (cond-> {:my.agent/id (:seon.agent/id row)}
        (get-in row [:seon.agent/namespace :seon.ns/name])
        (assoc :my.agent/namespace (get-in row [:seon.agent/namespace :seon.ns/name]))
        (get-in row [:seon.agent/namespace :seon.ns/steward :seon.agent/id])
        (assoc :my.agent/steward (get-in row [:seon.agent/namespace :seon.ns/steward :seon.agent/id]))))))

(defn settings
  "Read your setting overrides; omitted settings inherit the cluster defaults."
  {:malli/schema [:=> [:cat :seon.db/db :seon.agent/id]
                  [:or :my.agent/settings :seon.error/value]]}
  [database agent-id]
  (let [overrides (ai/agent-overlay database agent-id)]
    (if (:seon.error/kind overrides) overrides
        (assoc overrides :my.agent/turns-left (turn/turns-left database agent-id)))))

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
      (ai/agent-overlay (:db-after result) agent-id))))

(defn render-settings-ai
  "Read my overrides and the remaining turns in this session."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [unit]
  (str ";; I should pull my overrides; omitted settings inherit defaults, and turns left is derived rather than stored.\n"
       (repl/source-text
        (list 'seon.db/pull
              (list 'quote
                    '[{:seon.agent/settings
                       [:seon.config.ai/model :seon.config.ai/no-provider
                        :seon.config.eval/time-limit-ms
                        :seon.config.run/max-episode-runs]}])
              [:seon.agent/id (:seon.agent/id unit)]))))

(defn render-settings-html
  "Show every declared agent dial, its effective value, and where it comes from."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.error/value]]}
  [unit]
  (let [database (:seon.db/db unit)
        component (if (and database (:seon.agent/id unit))
                    (get (db/pull database '[{:seon.agent/settings [*]}]
                                  [:seon.agent/id (:seon.agent/id unit)])
                         :seon.agent/settings {})
                    (or (:seon.render/value unit) unit))
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
