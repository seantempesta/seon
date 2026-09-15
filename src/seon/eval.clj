(ns seon.eval
  "Queries over an agent's ordered evaluation entities."
  (:require [seon.db :as db]
            [seon.error :as error]
            [seon.schema.edn :as schema.edn]))

(schema.edn/load! {})

(defn of-agent
  "Return every evaluation of the agent, in turn transaction and ordinal order.

  The optional pull selector narrows the projection; identity, turn, ordinal and
  transaction remain present. Namespace and read-evidence refs are expanded by
  default. This reads saved facts only;
  it does not execute source or apply a presentation limit. A missing agent
  is a diagnostic, distinct from an existing agent with no evaluations."
  {:malli/schema
   [:function
    [:=> [:cat :seon.db/db :seon.agent/id]
     [:or [:vector [:and :seon.eval/entity
                    [:map [:db/id :int] [:t :seon.db/basis-t]]]] :seon.error/value]]
    [:=> [:cat :seon.db/db :seon.agent/id :seon.db/pull-selector]
     [:or [:vector [:and :seon.eval/entity
                    [:map [:db/id :int] [:t :seon.db/basis-t]]]] :seon.error/value]]]}
  ([database agent-id]
   (of-agent database agent-id
             '[* {:seon.cluster.eval/ns [:db/id :seon.ns/name]}
               {:seon.cluster.eval/read-evidence [*]}]))
  ([database agent-id selector]
  (let [agent-row (db/pull database [:seon.agent/id]
                       [:seon.agent/id agent-id])]
    (cond
      (:seon.error/kind agent-row) agent-row
      (nil? (:seon.agent/id agent-row))
      (error/diagnostic
       {:seon.error/kind ::agent-not-found
        :seon.error/message "Cannot read evaluations of an absent agent."
        :seon.error/diagnostic-layer :seon.eval
        :seon.error/diagnostic-operation 'seon.eval/of-agent
        :seon.error/diagnostic-member :seon.agent/id
        :seon.error/diagnostic-expected :seon.agent/id
        :seon.error/diagnostic-offending agent-id
        :seon.error/diagnostic-cause :seon.db/not-found
        :seon.error/diagnostic-evidence [:seon.agent/id agent-id]})
      :else
      (let [rows
            (db/q '[:find ?t ?turn-id ?ordinal ?evaluation-t
                    (pull ?evaluation ?selector)
                    :in $ ?agent-id ?selector
                    :where
                    [?agent :seon.agent/id ?agent-id]
                    [?agent :seon.agent/runtime ?runtime]
                    [?runtime :seon.runtime/turns ?turn]
                    [?turn :seon.turn/id ?turn-id ?t]
                    [?evaluation :seon.cluster.eval/run ?turn]
                    [?evaluation :seon.cluster.eval/id _ ?evaluation-t]
                    [?evaluation :seon.cluster.eval/ordinal ?ordinal]]
                  database agent-id (vec (distinct (into selector [:db/id :seon.cluster.eval/id
                                                                 :seon.cluster.eval/run
                                                                 :seon.cluster.eval/ordinal]))))]
        (if (:seon.error/kind rows)
          rows
          (mapv #(assoc (nth % 4) :t (nth % 3))
                (sort-by #(subvec % 0 3) rows))))))))
