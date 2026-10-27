;; read only: each run's branch's evals in transaction order, with the run's plan
(let [main @(:conn sys) e datahike.api/entity
      runs (for [d (datahike.api/datoms main :aevt :seon.run/id) :let [x (e main (:e d))]]
             {:id (:seon.run/id x) :plan (:seon.run/plan x) :branch (:seon.run/branch x) :agent (some-> (:seon.run/agent x) :seon.agent/key str)})
      out (vec (for [{:keys [branch] :as r} runs
                     :let [res (deref (seon.root/branch-db sys branch) 60000 {:timeout true})
                           db (:db res)]]
                 (assoc r :evals (if db
                                   (vec (sort-by :t (for [d (datahike.api/datoms db :aevt :seon.eval/id) :let [x (e db (:e d))]]
                                                      {:id (:seon.eval/id x) :t (:tx d) :by (str (:seon.eval/by x)) :record (pr-str (:seon.eval/record x))})))
                                   (pr-str (dissoc res :db))))))]
  (spit "/private/tmp/claude-501/-Users-sean-src-seon-next/dbf7ed59-fa19-4733-acb2-d111be4c61e9/scratchpad/replay.edn" (with-out-str (doseq [x out] (prn x))))
  (mapv (fn [r] [(:id r) (:branch r) (if (vector? (:evals r)) (count (:evals r)) (:evals r))]) out))
