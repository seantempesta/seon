(require 'seon.operator 'seon.operator.runtime 'seon.render.transcript 'seon.render.web
         'seon.cluster.prompt 'seon.sci.eval 'seon.repl)

(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      handle (:seon.turn.loop/cluster (get @seon.operator.runtime/running-instances "default"))
      request (#'seon.render.web/session-controls
               (#'seon.render.web/debug-turn-request database connection "juniper"
                 (:seon.sci.admit/caps handle) handle))
      acquired (#'seon.render.transcript/ledger-acquisition request)
      counts (atom {})
      calibration seon.cluster.prompt/agent-calibration
      effects @#'seon.render.transcript/ledger-effects
      summary @#'seon.render.transcript/turn-effects
      directory seon.sci.eval/directory-value
      render-directory seon.repl/render-directory-ai
      current? seon.db/read-evidence-current?]
  (with-redefs-fn
    {#'seon.cluster.prompt/agent-calibration
     (fn [& args] (swap! counts update :calibrations (fnil conj []) (last args)) (apply calibration args))
     #'seon.render.transcript/ledger-effects
     (fn [& args] (swap! counts update :effect-batches (fnil inc 0)) (apply effects args))
     #'seon.render.transcript/turn-effects
     (fn [facts row] (swap! counts update :summaries (fnil conj []) (:seon.turn/id row)) (summary facts row))
     #'seon.sci.eval/directory-value
     (fn [& args] (swap! counts update :directories (fnil inc 0)) (apply directory args))
     #'seon.repl/render-directory-ai
     (fn [& args] (swap! counts update :directory-renders (fnil inc 0)) (apply render-directory args))
     #'seon.db/read-evidence-current?
     (fn [& args] (swap! counts update :read-checks (fnil inc 0)) (apply current? args))}
    (fn []
      (let [retained (#'seon.render.transcript/ledger-acquisition
                      (assoc request :seon.db/db (seon.db/db connection)))
            read-checks (get @counts :read-checks 0)
            data (seon.render.transcript/acquire-ledger-data request
                   (:seon.render.history/entries acquired) (:seon.render.history/segments acquired))
            rows (:seon.render.transcript/rows data)
            evaluations (:seon.render.transcript/evaluations data)
            carried (assoc request :seon.render.transcript/calibrations (:seon.render.transcript/calibrations data))
            _ (mapv (partial #'seon.render.transcript/turn-story carried evaluations) rows)
            _ (mapv #(seon.render.hiccup/->string
                       (#'seon.render.transcript/ledger-turn-body carried rows evaluations %)) (take-last 3 rows))
            ledger (seon.render.hiccup/->string (seon.render.transcript/render-ledger request))]
        (println {:basis (seon.db/basis-t database)
                  :retained-entries? (identical? (:seon.render.history/entries acquired) (:seon.render.history/entries retained))
                  :second-acquisition-read-checks read-checks
                  :calibrations (:calibrations @counts)
                  :effect-batches (:effect-batches @counts)
                  :turn-summary-frequencies (frequencies (vals (frequencies (:summaries @counts))))
                  :directory-calls (get @counts :directories 0)
                  :directory-render-calls (get @counts :directory-renders 0)
                  :not-checked? (clojure.string/includes? ledger "Directory integrity · not checked")})))))
