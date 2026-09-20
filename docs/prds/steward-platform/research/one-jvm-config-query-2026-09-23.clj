(require '[seon.config :as config] '[seon.db :as db] '[seon.operator.runtime :as runtime])
; Invoke this function through the scratch cluster's advertised prepl.
; Compilation and acquisition precede the clock; require-functions! performs
; one Datalog query over every qualified symbol in the compiled manifest.
(fn [cluster-name]
  (let [database (db/db (:seon.boot/cluster-connection (get @runtime/running-instances cluster-name)))
        compiled (config/compile-manifest {:seon.boot/cluster-name cluster-name})
        rows (into [(:seon.config/desired-row compiled)] (:seon.config/initialization compiled))
        start (System/nanoTime)
        result (config/require-functions! database rows)]
    {:seon.source/elapsed-ms (/ (- (System/nanoTime) start) 1000000.0)
     :seon.source/result result}))
