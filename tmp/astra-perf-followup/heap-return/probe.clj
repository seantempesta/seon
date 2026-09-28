(let [database (seon.db/db (seon.cluster.boot/connection "default"))
      projection (seon.db/carried-projection database)
      heap (.getHeapMemoryUsage (java.lang.management.ManagementFactory/getMemoryMXBean))
      bean (java.lang.management.ManagementFactory/getPlatformMXBean com.sun.management.HotSpotDiagnosticMXBean)]
  {:pid (.pid (java.lang.ProcessHandle/current))
   :start (.getStartTime (java.lang.management.ManagementFactory/getRuntimeMXBean))
   :heap {:used (.getUsed heap) :committed (.getCommitted heap) :max (.getMax heap)}
   :ratios (into {} (map (fn [k] [k (.getValue (.getVMOption bean k))])) ["MinHeapFreeRatio" "MaxHeapFreeRatio"])
   :probe (mapv (fn [_] (let [t (System/nanoTime)
                              value (seon.schema/load-projection database projection)]
                          {:ms (/ (- (System/nanoTime) t) 1e6)
                           :identical (identical? value projection)})) (range 5))})
