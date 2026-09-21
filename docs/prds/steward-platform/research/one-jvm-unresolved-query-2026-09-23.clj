(let [database (seon.db/db (:seon.boot/cluster-connection (get @seon.operator.runtime/running-instances "s")))
      started (System/nanoTime)
      report (seon.fn/unresolved-callers database)]
  {:seon.source/elapsed-ms (/ (- (System/nanoTime) started) 1000000.0)
   :seon.program/analyzed-count (:seon.program/analyzed-count report)
   :seon.program/unresolved-callers (count (:seon.program/unresolved-callers report))
   :seon.error/message (:seon.error/message report)})
