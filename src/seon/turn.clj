(ns seon.turn
  "System and virtual turns through the agent's ordinary proc."
  (:require [seon.cluster.agent :as agent]
            [seon.db :as db]
            [seon.error :as error]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn compact-call
  "Retract one idle agent's evaluations at the database writer."
  {:malli/schema [:=> [:cat :seon.db/database-value :seon.cluster.agent/id]
                  :seon.store/transaction-data]}
  [database agent-id]
  (let [agent-eid (db/q '[:find ?agent . :in $ ?id
                         :where [?agent :seon.cluster.agent/id ?id]]
                       database agent-id)
        open-id (when agent-eid
                  (db/q '[:find ?id . :in $ ?agent
                          :where [?turn :seon.cluster.run/agent ?agent]
                          [?turn :seon.cluster.run/id ?id]
                          (not [?turn :seon.cluster.run/closed-at _])]
                        database agent-eid))]
    (when (or (nil? agent-eid) open-id)
      (let [message (if open-id
                      "Compaction requires the agent's current turn to close."
                      "Compaction requires an existing agent.")]
        (throw
         (ex-info message
                  (error/diagnostic
                   {:seon.error/kind ::compaction-refused
                    :seon.error/message message
                    :seon.error/diagnostic-layer :seon.turn
                    :seon.error/diagnostic-operation `compact!
                    :seon.error/diagnostic-member :seon.cluster.agent/id
                    :seon.error/diagnostic-expected :seon.cluster.agent/id
                    :seon.error/diagnostic-offending agent-id
                    :seon.error/diagnostic-cause ::compaction-refused
                    :seon.error/diagnostic-evidence
                    (cond-> {} open-id (assoc :seon.cluster.run/id open-id))})))))
    (mapv (fn [evaluation] [:db.fn/retractEntity evaluation])
          (db/q '[:find [?evaluation ...] :in $ ?agent
                  :where [?turn :seon.cluster.run/agent ?agent]
                  [?evaluation :seon.cluster.eval/run ?turn]]
                database agent-eid))))

(defn compact!
  "Retract an idle agent's evaluations; the next system walk is fresh."
  {:malli/schema [:=> [:cat :seon.turn/compaction-request]
                  [:or :seon.db/transaction-report :seon.error/value]]}
  [{connection :seon.db/connection agent-id :seon.cluster.agent/id}]
  (db/transact! connection [[:db.fn/call #'compact-call agent-id]]))

(defn virtual-turn!
  "Submit a fixture reply to the ordinary agent proc, without a provider.
  Returns its durable turn identity; completion is observed in its facts."
  {:malli/schema [:=> [:cat :seon.turn/virtual-request]
                  [:or :seon.cluster.agent/source-submission-result
                   :seon.error/value]]}
  [request]
  (agent/submit-source!
   (update request :seon.cluster.reply/text #(or % "(+ 1 1)"))))
