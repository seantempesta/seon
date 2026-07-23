(ns seon.agent.ctx.admin
  "Pod-only context installation and migration operations."
  (:require
    [seon.agent.ctx :as ctx]
    [seon.config :as config]
    [seon.db :as db]))

(defn initial-agent-context
  "Return the complete creation-time context facts for one new agent."
  {:malli/schema
   [:=> [:cat ::ctx/initial-context-request] :seon.config/agent-context-spec]}
  [{:seon.agent/keys [id] :seon.agent.ctx/keys [override]
    configuration :seon.config/configuration}]
  (config/resolve-agent-context id override configuration))

(defn- upsert-ctx-tx [id blocks]
  (into [[:db.fn/retractAttribute [:seon.agent/id id] :seon.agent/ctx]]
        (when (seq blocks)
          [{:seon.agent/id id :seon.agent/ctx (vec blocks)}])))

(defn ^:async ^:private acquire-context-blocks [id]
  (let [entity (await
                (db/pull {::db/pull-pattern '[{:seon.agent/ctx [*]}]
                          ::db/ref [:seon.agent/id id]
                          ::db/max-work 100000
                          ::db/max-results 2048
                          ::db/max-result-weight 262144}))]
    (if (:seon.error/message entity)
      entity
      (->> (:seon.agent/ctx entity)
           (map ctx/decode-block)
           (sort-by (juxt :seon.agent.ctx/priority
                          (comp str :seon.agent.ctx/name)))
           vec))))

(defn- transaction-result [operation names result]
  (if-let [message (:seon.error/message result)]
    {::ctx/ok? false ::ctx/error (str operation " transact failed: " message)}
    {::ctx/ok? true ::ctx/names names}))

(def ^:private obsolete-plan-surface 'my.plan.internal/plan-block-html)
(def ^:private current-plan-surface 'my.plan/plan-surface)

(defn ^:async migrate-plan-surface-default!
  "Replace the obsolete platform plan renderer on copied context blocks."
  {:malli/schema [:=> [:cat] ::ctx/migration-result]}
  []
  (let [database (await (db/db))]
    (if-let [message (:seon.error/message database)]
      {::ctx/ok? false ::ctx/error message}
      (let [rows (await
                  (db/query
                   {::db/db database
                    ::db/query
                    '[:find ?block ?stored
                      :in $ ?stored
                      :where
                      [?block :seon.agent.ctx/name :plan]
                      [?block :seon.render/html ?stored]]
                    ::db/args [(pr-str obsolete-plan-surface)]
                    ::db/max-work 20000
                    ::db/max-results 4096
                    ::db/max-result-weight 262144}))]
        (if-let [message (:seon.error/message rows)]
          {::ctx/ok? false ::ctx/error message}
          (let [tx-data (into []
                              (map (fn [[block stored]]
                                     [:db.fn/cas block :seon.render/html stored
                                      (pr-str current-plan-surface)]))
                              (sort-by first rows))]
            (if (empty? tx-data)
              {::ctx/ok? true ::ctx/changed? false ::ctx/operations 0}
              (let [result (await
                            (db/transact!
                             {::db/db database
                              ::db/expected-db database
                              ::db/tx-data tx-data}))]
                (if-let [message (:seon.error/message result)]
                  {::ctx/ok? false ::ctx/error message}
                  {::ctx/ok? true
                   ::ctx/changed? true
                   ::ctx/operations (count tx-data)})))))))))

(defn ^:async install!
  "Install context blocks into the agent currently in scope."
  {:malli/schema [:=> [:cat ::ctx/install-request] ::ctx/result]}
  [block-or-blocks]
  (let [id (db/current-agent-id)]
    (if (nil? id)
      {::ctx/ok? false
       ::ctx/error (str "install!: no agent in scope — call inside "
                        "(seon.db/with-agent id …).")}
      (let [current (await (acquire-context-blocks id))]
        (if (:seon.error/message current)
          {::ctx/ok? false ::ctx/error (:seon.error/message current)}
          (let [blocks (if (vector? block-or-blocks)
                         block-or-blocks
                         [block-or-blocks])
                new-names (into #{} (map :seon.agent.ctx/name) blocks)
                kept (->> current
                          (remove #(contains? new-names
                                              (:seon.agent.ctx/name %)))
                          (mapv #(dissoc % :db/id)))
                result (await
                        (db/transact!
                         {::db/tx-data
                          (upsert-ctx-tx id (into kept blocks))}))]
            (transaction-result "install!" (vec new-names) result)))))))

(defn ^:async remove!
  "Remove one named context block from the agent currently in scope."
  {:malli/schema [:=> [:catn [:seon.agent.ctx/name :seon.agent.ctx/name]]
                  ::ctx/result]}
  [name]
  (let [id (db/current-agent-id)]
    (if (nil? id)
      {::ctx/ok? false
       ::ctx/error (str "remove!: no agent in scope — call inside "
                        "(seon.db/with-agent id …).")}
      (let [current (await (acquire-context-blocks id))]
        (if (:seon.error/message current)
          {::ctx/ok? false ::ctx/error (:seon.error/message current)}
          (let [kept (->> current
                          (remove #(= name (:seon.agent.ctx/name %)))
                          (mapv #(dissoc % :db/id)))
                result (await
                        (db/transact!
                         {::db/tx-data (upsert-ctx-tx id kept)}))]
            (transaction-result "remove!" [name] result)))))))
