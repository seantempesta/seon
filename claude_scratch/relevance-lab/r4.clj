;; read only: every branch's eval records and runs, from the union of the branches' histories
(let [{:keys [db]} @(seon.root/union sys) e datahike.api/entity
      evs (for [d (datahike.api/datoms db :aevt :seon.eval/id) :when (:added d) :let [x (e db (:e d))]]
            {:id (:seon.eval/id x) :by (str (:seon.eval/by x)) :t (:tx d) :agent (some-> (:seon.eval/agent x) :seon.agent/key str)
             :opened (pr-str (:seon.eval/opened x)) :record (pr-str (:seon.eval/record x))})
      runs (for [d (datahike.api/datoms db :aevt :seon.run/id) :when (:added d) :let [x (e db (:e d))]]
             {:id (:seon.run/id x) :task (some-> (:seon.run/task x) :seon.task/id) :plan (:seon.run/plan x)
              :agent (some-> (:seon.run/agent x) :seon.agent/key str) :branch (str (:seon.run/branch x))})]
  (spit "/private/tmp/claude-501/-Users-sean-src-seon-next/dbf7ed59-fa19-4733-acb2-d111be4c61e9/scratchpad/replay-evals.edn" (with-out-str (doseq [x (distinct evs)] (prn x))))
  (spit "/private/tmp/claude-501/-Users-sean-src-seon-next/dbf7ed59-fa19-4733-acb2-d111be4c61e9/scratchpad/replay-runs.edn" (with-out-str (doseq [x (distinct runs)] (prn x))))
  [(count (distinct evs)) (count (distinct runs)) (frequencies (map :agent evs))])
