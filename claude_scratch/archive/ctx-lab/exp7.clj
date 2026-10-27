;; Experiment 7: the hook's two parts for one new eval, with the schema held: datoms-diff (what the
;; state holds today) against the transaction's own tx-data, and the patch
(def E7 (let [before (agent/db) rep (d/with before (new-eval-tx)) after (:db-after rep)
              txds (mapv #(dm % (:added %)) (:tx-data rep))
              c {:tree @ctx :ix @ctx-ix}]
          {:evals (count (:seon.eval/_agent @ctx))
           :datoms-diff (cost/measure (fn [] (d/datoms-diff before after :eavt [])) [])
           :patch-from-tx-data (cost/measure (fn [] (binding [*schema* @sch] (patch c after txds))) [])
           :pull-of-the-eval (cost/measure (fn [] (d/pull after (pattern+ :seon.db/eval-fact) [:seon.eval/id (str "s" @n)])) [])}))
