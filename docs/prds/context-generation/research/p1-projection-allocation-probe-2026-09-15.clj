(let [connection (seon.operator/connection "default")
      database (seon.db/db connection)
      projection (seon.db/carried-projection database)
      context (seon.schema/call-with-projection projection seon.sci.eval/build-base-ctx)
      request {:seon.sci.eval/ctx context
               :seon.cluster.eval/source "(+ 1 1)"
               :seon.sci.admit/caps (seon.config/result-caps (seon.config/defaults))
               :seon.sci.eval/time-limit-ms 5000
               :seon.config/on-core-error :panic}
      bean ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
      thread-id (.threadId (Thread/currentThread))
      before (.getThreadAllocatedBytes bean thread-id)
      start (System/nanoTime)
      result (seon.sci.eval/evaluate request)
      elapsed (/ (- (System/nanoTime) start) 1000000.0)
      allocated (- (.getThreadAllocatedBytes bean thread-id) before)]
  {:seon.probe/allocated-bytes allocated
   :seon.probe/elapsed-ms elapsed
   :seon.probe/value (:seon.sci.admit/value result)})
