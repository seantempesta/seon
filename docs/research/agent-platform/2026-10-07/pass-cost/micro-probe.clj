(do
  (require 'seon.pass-probe 'seon.cluster.agent 'seon.sci.kernel 'seon.config 'seon.schema 'datahike.query)
  (let [agent-id (System/getProperty "seon.pass-cost.agent" "pass-cost-final")
        world (#'seon.pass-probe/agent-world "default" agent-id)
        cluster (get-in world [:seon.pass-probe/host :seon.turn.loop/cluster])
        ctx (:seon.sci.eval/ctx (seon.cluster.agent/acquire-context! cluster agent-id))
        database @(:seon.db/connection world)
        projection (seon.db/carried-projection database)
        parsed (datahike.query/memoized-parse-query
                '[:find (max ?ordinal) . :in $ ?run-id
                  :where [?run :seon.turn/id ?run-id]
                  [?form :seon.cluster.eval/run ?run]
                  [?form :seon.cluster.eval/ordinal ?ordinal]])
        mx (java.lang.management.ManagementFactory/getThreadMXBean)]
    (mapv
     (fn [[label f]]
       (dotimes [_ 100] (f))
       {:helper label :repetitions 100
        :rows (mapv (fn [_]
                      (let [allocated (.getCurrentThreadAllocatedBytes mx)
                            start (System/nanoTime)]
                        (dotimes [_ 100] (f))
                        {:ms (/ (- (System/nanoTime) start) 1e6)
                         :allocated-bytes (- (.getCurrentThreadAllocatedBytes mx) allocated)}))
                    (range 3))})
     [['seon.sci.kernel/public-functions-in #(seon.sci.kernel/public-functions-in ctx 'scratch.empty)]
      ['seon.config/registration-defaults #(#'seon.config/registration-defaults projection)]
      ['seon.db/query-patterns #(vec (#'seon.db/query-patterns parsed))]])))
