(let [database @(:seon.db/connection
                 (#'seon.pass-probe/agent-world "default" (System/getProperty "seon.pass-cost.agent")))
      mx (java.lang.management.ManagementFactory/getThreadMXBean)]
  (mapv (fn [_]
          (let [prefix (str (random-uuid))
                ids (mapv #(str prefix "-" %) (range 100))
                a (.getCurrentThreadAllocatedBytes mx) t (System/nanoTime)
                values (mapv #(#'seon.turn/evaluation-entity-id database %) ids)]
            {:ms (/ (- (System/nanoTime) t) 1e6)
             :allocated-bytes (- (.getCurrentThreadAllocatedBytes mx) a)
             :absent? (every? nil? values)}))
        (range 3)))
