(ns seon.eval
  "Queries over an agent's ordered evaluation entities."
  (:require [seon.db :as db]
            [seon.error :as error]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn of-agent
  "Return every evaluation of the agent, in turn transaction and ordinal order.

  Namespace and read-evidence refs are expanded. This reads saved facts only;
  it does not execute source or apply a presentation limit. A missing agent
  is a diagnostic, distinct from an existing agent with no evaluations."
  {:malli/schema
   [:=> [:cat :seon.db/db :seon.cluster.agent/id]
    [:or [:vector [:and :seon.eval/entity
                   [:map [:db/id :int] [:t :seon.db/basis-t]]]]
     :seon.error/value]]}
  [database agent-id]
  (let [agent-row (db/pull database [:seon.cluster.agent/id]
                       [:seon.cluster.agent/id agent-id])]
    (cond
      (:seon.error/kind agent-row) agent-row
      (nil? (:seon.cluster.agent/id agent-row))
      (error/diagnostic
       {:seon.error/kind ::agent-not-found
        :seon.error/message "Cannot read evaluations of an absent agent."
        :seon.error/diagnostic-layer :seon.eval
        :seon.error/diagnostic-operation 'seon.eval/of-agent
        :seon.error/diagnostic-member :seon.cluster.agent/id
        :seon.error/diagnostic-expected :seon.cluster.agent/id
        :seon.error/diagnostic-offending agent-id
        :seon.error/diagnostic-cause :seon.db/not-found
        :seon.error/diagnostic-evidence [:seon.cluster.agent/id agent-id]})
      :else
      (let [rows
            (db/q '[:find ?t ?turn-id ?ordinal ?evaluation-t
                    (pull ?evaluation
                          [* {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
                           {:seon.cluster.eval/read-evidence [*]}])
                    :in $ ?agent-id
                    :where
                    [?agent :seon.cluster.agent/id ?agent-id]
                    [?turn :seon.cluster.run/agent ?agent]
                    [?turn :seon.cluster.run/id ?turn-id ?t]
                    [?evaluation :seon.cluster.eval/run ?turn]
                    [?evaluation :seon.cluster.eval/id _ ?evaluation-t]
                    [?evaluation :seon.cluster.eval/ordinal ?ordinal]]
                  database agent-id)]
        (if (:seon.error/kind rows)
          rows
          (mapv #(assoc (nth % 4) :t (nth % 3))
                (sort-by #(subvec % 0 3) rows)))))))
