(let [span {:filename "a" :row 1 :col 2 :end-row 9 :end-col 7}
      entries [{:filename "a" :row 1 :col 2} {:filename "a" :row 9 :col 7}
               {:filename "a" :row 3 :col 1} {:filename "b" :row 3 :col 1}]
      bean (java.lang.management.ManagementFactory/getThreadMXBean)
      id (.threadId (Thread/currentThread))
      b (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean id)
      t (System/nanoTime)
      result (reduce (fn [n i] (if (#'seon.fn.analyzer/contains-position? span (nth entries (mod i 4))) (inc n) n)) 0 (range 100000))]
 {:matches result :ms (/ (- (System/nanoTime) t) 1e6)
  :bytes (- (.getThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean id) b)})
