(let [bean (java.lang.management.ManagementFactory/getThreadMXBean)
      thread-id (.threadId (Thread/currentThread))]
 (mapv (fn [_]
  (seon.cache/evict-kinds! #{:seon.program/authored-shapes})
  (let [bytes (.getThreadAllocatedBytes bean thread-id)
        time (System/nanoTime)
        value (#'seon.program/authored-shapes)]
    {:ms (/ (- (System/nanoTime) time) 1e6)
     :bytes (- (.getThreadAllocatedBytes bean thread-id) bytes)
     :shapes (count value)})) (range 3)))
