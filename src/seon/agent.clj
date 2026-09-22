(ns seon.agent
  "Read the calling agent's own record components."
  (:refer-clojure :exclude [identity])
  (:require [clojure.string :as str]
            [seon.ai :as ai]
            [seon.db :as db]
            [seon.config :as config]
            [malli.core :as m]
            [malli.registry :as mr]))

(defn identity
  "Read stable identity values without projecting a stored ref as a scalar."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :my.agent/identity :seon.error/value]]}
  [database agent-id]
  (let [row (db/pull database
                     [:seon.agent/id {:seon.agent/namespace
                                      [:seon.ns/name {:seon.ns/steward [:seon.agent/id]}]}]
                     [:seon.agent/id agent-id])]
    (cond
      (or (:seon.db/invalid-read row) (:seon.schema/expected-value row)) row
      (not (:seon.agent/id row))
      {:seon.agent/no-such-agent agent-id
       :seon.error/at (java.util.Date.) :seon.error/layer :seon.agent/identity
       :seon.error/operation 'seon.agent/identity
       :seon.error/message (str "No agent has id " (pr-str agent-id) ".")}
      :else
      (cond-> {:my.agent/id (:seon.agent/id row)}
        (get-in row [:seon.agent/namespace :seon.ns/name])
        (assoc :my.agent/namespace (get-in row [:seon.agent/namespace :seon.ns/name]))
        (get-in row [:seon.agent/namespace :seon.ns/steward :seon.agent/id])
        (assoc :my.agent/steward (get-in row [:seon.agent/namespace :seon.ns/steward :seon.agent/id]))))))

(defn archived?
  "Whether this retained agent has a positive archival transaction."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :boolean :seon.error/value]]}
  [database agent-id]
  (let [row (db/pull database [:seon.agent/id :seon.agent/archived-tx]
                     [:seon.agent/id agent-id])]
    (cond
      (or (:seon.db/invalid-read row) (:seon.schema/expected-value row)) row
      (:seon.agent/id row) (boolean (:seon.agent/archived-tx row))
      :else {:seon.agent/no-such-agent agent-id
             :seon.error/at (java.util.Date.) :seon.error/layer :seon.agent/identity
             :seon.error/operation 'seon.agent/archived?
             :seon.error/message (str "No agent has id " (pr-str agent-id) ".")})))

(defn open?
  "Whether this existing agent is not archived; this does not describe graph liveness."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :boolean :seon.error/value]]}
  [database agent-id]
  (let [archived (archived? database agent-id)]
    (if (map? archived) archived (not archived))))

(defn- archive-call
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  :seon.db/tx-data]}
  [database agent-id]
  (let [row (db/pull database [:db/id :seon.agent/archived-tx]
                     [:seon.agent/id agent-id])]
    (cond
      (or (:seon.db/invalid-read row) (:seon.schema/expected-value row))
      (throw (ex-info (:seon.error/message row) row))
      (nil? (:db/id row))
      (throw (ex-info "The agent to archive does not exist."
                      {:seon.agent/no-such-agent agent-id}))
      (:seon.agent/archived-tx row) []
      :else [[:db/add (:db/id row) :seon.agent/archived-tx :db/current-tx]])))

(defn archive!
  "Archive an agent for presentation, retaining its identity and all live machinery."
  {:malli/schema [:=> [:cat :seon.db/connection :seon.agent/id]
                  [:or :seon.db/transaction-report :seon.error/value]]}
  [connection agent-id]
  (db/transact! connection [[:db.fn/call #'archive-call agent-id]]))

(defn settings
  "Read your setting overrides; omitted settings inherit the cluster defaults."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id]
                  [:or :my.agent/settings :seon.error/value]]}
  [database agent-id]
  (ai/agent-overlay database agent-id))

(defn- update-settings-call
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.agent/id
                       :seon.config/agent-overlay] :seon.db/tx-data]}
  [database agent-id overrides]
  (let [agent (db/pull database '[:db/id {:seon.agent/settings [:db/id]}]
                       [:seon.agent/id agent-id])]
    (when-not (:db/id agent)
      (throw (ex-info "The agent whose settings were requested does not exist."
                      {:seon.agent/no-such-agent agent-id})))
    (let [component (or (get-in agent [:seon.agent/settings :db/id]) "agent-settings")
          attributes (ai/agent-setting-attributes database)
          _ (when (or (:seon.db/invalid-read attributes)
                      (:seon.schema/expected-value attributes))
              (throw (ex-info (:seon.error/message attributes) attributes)))
          admitted (select-keys overrides attributes)]
      (if (seq admitted)
        [(assoc admitted :db/id component :seon.config/agent (:db/id agent))
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
    (if (or (:seon.db.write.attempt/request-id result)
            (:seon.db/invalid-read result) (:seon.schema/expected-value result))
      result
      (ai/agent-overlay (:db-after result) agent-id))))

(defn effective-settings
  "Read effective actionable settings on demand.
  Set :seon.config.agent/show-all-settings true to include every effective dial."
  {:malli/schema
   [:=> [:cat :seon.db/database-value :seon.agent/id]
    [:or [:vector [:map-of :qualified-keyword :seon.schema/value]] :seon.error/value]]}
  [database agent-id]
  (let [cluster-name (db/q '[:find ?name . :where [_ :seon.config/cluster ?name]] database)
        defaults (when (string? cluster-name) (config/effective database cluster-name))
        overrides (ai/agent-overlay database agent-id)
        attributes (ai/agent-setting-attributes database)
        refusal (some #(when (or (:seon.db/invalid-read %)
                                 (:seon.schema/expected-value %)
                                 (:seon.config/error-key %)) %)
                      [cluster-name defaults overrides attributes])]
    (cond
      refusal refusal
      (nil? defaults) {:seon.error/at (java.util.Date.)
                      :seon.error/layer :seon.agent/identity
                      :seon.error/operation 'seon.agent/effective-settings
                      :seon.error/message "The cluster configuration is absent."}
      :else
      (let [resolved (ai/settings defaults overrides)
            effective (select-keys resolved
                        (if (:seon.config.agent/show-all-settings resolved)
                          attributes
                          [:seon.config.ai/model :seon.config.ai/no-provider
                           :seon.config.eval/time-limit-ms :seon.config.run/max-episode-runs
                           :seon.config.ai.retry/base-delay-ms
                           :seon.config.ai.retry/jitter-fraction
                           :seon.config.ai.retry/maximum-delay-ms
                           :seon.config.ai.retry/maximum-retries
                           :seon.config.ai.retry/maximum-total-delay-ms
                           :seon.config.ai.retry/multiplier]))
            order {"seon.config.ai" 0 "seon.config.ai.retry" 1
                   "seon.config.eval" 2 "seon.config.run" 3}]
        (mapv (fn [[_ entries]] (into (sorted-map) entries))
                    (sort-by (fn [[group _]] [(get order group 4) group])
                             (group-by (comp namespace key) effective)))))))

(defn render-settings-ai
  "Read stable setting overrides; effective cluster defaults are available on demand."
  {:malli/schema [:=> [:cat :seon.render/unit] :seon.render/source]}
  [_unit]
  ";; My setting overrides; (seon.agent/effective-settings) shows inherited cluster defaults on demand.\n(seon.agent/settings)")

(defn render-settings-html
  "Show every declared agent dial, its effective value, and where it comes from."
  {:malli/schema [:=> [:cat :seon.render/unit]
                  [:or :seon.render/hiccup :seon.db/error-result
                       :seon.schema/unknown-shape-error
                       :seon.schema/validation-refusal
                       :seon.config/missing-effective-error]]}
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
        defaults (if cluster-name (config/effective database cluster-name) {})
        registry (:seon.schema.projection/registry
                  (or (some-> database db/carried-projection)
                      (:seon.schema/projection unit)))]
    (cond
      (nil? registry) (db/projection-fallback 'seon.agent/render-settings-html)
      (or (:seon.error/at attributes) (:seon.schema/unknown-shape attributes)) attributes
      (or (:seon.error/at defaults) (:seon.config/missing-effective defaults)) defaults
      :else
      (let [overrides (select-keys component attributes)
            inherited (apply dissoc (select-keys defaults attributes) (keys overrides))
            absent (remove #(or (find overrides %) (find inherited %)) attributes)
            label (fn [attribute]
                    (or (:seon.config/display-label
                         (some-> (mr/schema registry attribute) m/properties))
                        (str attribute)))
            display (fn [attribute value]
                      (let [{divisor :seon.config/display-divisor unit :seon.config/display-unit}
                            (some-> (mr/schema registry attribute) m/properties)
                            value (if (and divisor (number? value)) (/ value divisor) value)]
                        (str (cond
                               (integer? value) (format "%,d" value)
                               (ratio? value) (double value)
                               (keyword? value) (name value)
                               (sequential? value) (str/join ", " (map str value))
                               :else value)
                             (when unit (str " " unit)))))
            title (fn [attribute]
                    (or (:description (some-> (mr/schema registry attribute) m/properties))
                        (str attribute)))]
        [:section {:class "seon-family-entry seon-agent-settings seon-agent-content"}
         [:h3 "Settings"]
         [:table {:class "seon-settings-table"}
          [:thead [:tr [:th "Setting"] [:th "Value"]]]
          (into [:tbody]
                (map (fn [[attribute value]]
                       [:tr
                        [:th {:scope "row" :title (title attribute)}
                         (label attribute)
                         [:span {:class "seon-setting-override"} "● override"]]
                        [:td (display attribute value)]]))
                (sort-by key overrides))
          [:tbody [:tr [:td {:colspan 2}
                        [:details {:class "seon-settings-defaults" :data-preserve-attr "open"}
                         [:summary (str "defaults (" (count inherited) ")")]
                         (into [:dl]
                               (map (fn [[attribute value]]
                                      [:div [:dt {:title (title attribute)} (label attribute)]
                                       [:dd (display attribute value)]]))
                               (sort-by key inherited))]]]]]
         (when (seq absent)
           [:details {:class "seon-settings-absent"}
            [:summary (str "Unset settings (" (count absent) ")")]
            (into [:dl]
                  (map (fn [attribute]
                         [:div [:dt {:title (title attribute)} (label attribute)]
                          [:dd "Unset"]]))
                  (sort absent))])]))))
