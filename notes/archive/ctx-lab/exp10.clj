;; Experiment 10: a cardinality-many attribute's order: members written in three transactions, c then a
;; then b; what pull answers, what the index holds, and the order the transactions give
(def E10 (let [id "lab/order"]
           (agent/transact! [{:seon.task/id id :seon.task/ns 'app.x :seon.task/name "order" :seon.task/plan "p" :seon.task/execution :running
                              :seon.task/branch :ctx-lab3 :seon.task/kind :work :seon.task/proofs #{'app.t/c}}])
           (agent/transact! [[:db/add [:seon.task/id id] :seon.task/proofs 'app.t/a]])
           (agent/transact! [[:db/add [:seon.task/id id] :seon.task/proofs 'app.t/b]])
           (agent/transact! [[:db/add [:seon.task/id id] :seon.task/proofs 'app.t/a]])   ; again: a set holds it once
           (let [db (agent/db) e (:db/id (d/pull db [:db/id] [:seon.task/id id]))
                 ds (d/datoms db :eavt e :seon.task/proofs)]
             {:pull (:seon.task/proofs (d/pull db [:seon.task/proofs] e))
              :index (mapv :v ds)
              :by-tx (mapv :v (sort-by :tx ds))
              :by-tx-cost (cost/measure (fn [] (mapv :v (sort-by :tx (d/datoms db :eavt e :seon.task/proofs)))) [])
              :pull-cost (cost/measure d/pull [db [:seon.task/proofs] e])})))
