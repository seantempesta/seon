(let [bean (java.lang.management.ManagementFactory/getThreadMXBean)
      call (fn [mode n] (seon.mcp/call (clojure.data.json/write-str
                       {"name" "eval_clj" "arguments" {"code" (clojure.string/join "\n" (repeat n "(+ 1 1)"))
                         "agent" "s5areview" "cluster" "default" "mode" mode}})))]
 (vec (for [mode ["sci" "jvm"] n [1 10 100]]
   (do (call mode n)
    {:mode mode :n n
     :rows (mapv (fn [_]
       (let [mark (seon.profile/begin)
             a (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean)
             t (System/nanoTime)
             answer (call mode n)
             ms (/ (- (System/nanoTime) t) 1e6)
             allocated (- (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean bean) a)
             p (seon.profile/explain mark 25)
             decoded (clojure.data.json/read-str answer)]
        {:ms ms :bytes allocated :error (get decoded "isError") :texts (count (get decoded "content"))
         :calls (:seon.profile/calls p)
         :growths (mapv #(select-keys % [:seon.profile/sym :seon.profile/calls :seon.profile/total-ms]) (:seon.profile/growths p))
         :by-calls (mapv #(select-keys % [:seon.profile/sym :seon.profile/calls :seon.profile/total-ms]) (:seon.profile/by-calls p))})) (range 3))}))))
