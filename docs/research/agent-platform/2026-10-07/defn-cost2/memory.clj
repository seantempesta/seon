(do (System/gc)
 (let [heap (.getHeapMemoryUsage (java.lang.management.ManagementFactory/getMemoryMXBean))]
   {:heap-used (.getUsed heap) :heap-committed (.getCommitted heap) :heap-max (.getMax heap)}))
