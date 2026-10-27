;; Experiment 13: who made a fact, by query alone: each datom's transaction and that transaction's
;; tx-meta (:seon.tx/branch, written on every transaction Seon makes, seon.db/tx-meta)
(require '[datahike.api :as d] '[seon.agent :as agent])
(agent/transact! [{:seon.message/id "lab-note" :seon.message/from "a1" :seon.message/body "written by the agent"}])
(def E13
  (let [db (agent/db) me (:e (first (d/datoms db :avet :seon.agent/key (:key @agent/state))))
        by (fn [e] (d/q '[:find ?a ?b :in $ ?e :where [?e ?a _ ?tx] [?tx :seon.tx/branch ?b]] db e))
        q '[:find ?b (count ?e) :where [?e :seon.eval/id _ ?tx] [?tx :seon.tx/branch ?b]]]
    {:my-branch (:branch @agent/state)
     :agent-entity (by me)
     :note (by (:e (first (d/datoms db :avet :seon.message/id "lab-note"))))
     :main-tasks-on-branch (d/q '[:find ?b (count ?e) :where [?e :seon.task/id _ ?tx] [?tx :seon.tx/branch ?b]] db)
     :evals-by-branch (d/q q db)}))
