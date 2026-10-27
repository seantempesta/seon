;; Experiment 4: one new eval's move, split: datoms-diff, patch (the join pulls the new eval), and the
;; patch from the transaction's own tx-data (what agent/transact!'s report holds), on this agent's ctx
(remove-watch agent/state ::ctx)
(def E4 (let [c @ctx before (agent/db)
              rep (d/with before (new-eval-tx))        ; the report a transact! gets
              after (:db-after rep)
              txds (mapv #(dm % (:added %)) (:tx-data rep))
              [gone added] (d/datoms-diff before after :eavt [])
              diffds (vec (concat (map #(dm % false) gone) (map #(dm % true) added)))]
          {:n-evals (count (:seon.eval/_agent (:tree c)))
           :tx-datoms (count txds) :diff-datoms (count diffds)
           :diff (cost/measure (fn [] (d/datoms-diff before after :eavt [])) [])
           :patch (cost/measure patch [c after txds])
           :same? (= (patch c after txds) (patch c after diffds))
           :rebuild (cost/measure tree [(me)])
           :plan-change (let [r (d/with before [{:seon.task/id "lab/ctx" :seon.task/plan "Plan four."}])]
                          (cost/measure patch [c (:db-after r) (mapv #(dm % (:added %)) (:tx-data r))]))}))
