;; read only: every eval record and run on main, written to my scratchpad
(let [db @(:conn sys) e datahike.api/entity
      evs (for [d (datahike.api/datoms db :aevt :seon.eval/id) :let [x (e db (:e d))]]
            {:id (:seon.eval/id x) :by (str (:seon.eval/by x)) :opened (pr-str (:seon.eval/opened x)) :t (:tx d)
             :agent (pr-str (:seon.eval/agent x)) :record (pr-str (:seon.eval/record x))})
      runs (for [d (datahike.api/datoms db :aevt :seon.run/id) :let [x (e db (:e d))]]
             {:id (:seon.run/id x) :task (some-> (:seon.run/task x) :seon.task/id) :plan (:seon.run/plan x) :agent (pr-str (:seon.run/agent x))
              :turns (:seon.run/turns x) :status (str (:seon.run/status x)) :outcome (str (:seon.run/outcome x))})]
  (spit "/private/tmp/claude-501/-Users-sean-src-seon-next/dbf7ed59-fa19-4733-acb2-d111be4c61e9/scratchpad/replay-evals.edn" (with-out-str (doseq [x evs] (prn x))))
  (spit "/private/tmp/claude-501/-Users-sean-src-seon-next/dbf7ed59-fa19-4733-acb2-d111be4c61e9/scratchpad/replay-runs.edn" (with-out-str (doseq [x runs] (prn x))))
  [(count evs) (count runs)])
