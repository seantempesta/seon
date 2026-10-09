;; JVM eval_clj: four owned graphs, each joined before the next starts.
(require 'clojure.core.async 'clojure.core.async.flow)
(let [mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
      run (fn [virtual?]
            (let [factory (if virtual? (.factory (Thread/ofVirtual)) (.factory (.daemon (Thread/ofPlatform) true)))
                  executor (java.util.concurrent.Executors/newThreadPerTaskExecutor factory)
                  step (fn
                         ([] {:ins {:work ""} :outs {}})
                         ([args] args)
                         ([state _] state)
                         ([state _ [reply n]]
                          (let [a (.getCurrentThreadAllocatedBytes mx)
                                c (.getCurrentThreadCpuTime mx)
                                value (reduce + (map #(alength (byte-array (+ 1000 %))) (range n)))]
                            (clojure.core.async/>!! reply
                                                   {:value value
                                                    :allocation (if (neg? a) :unknown (- (.getCurrentThreadAllocatedBytes mx) a))
                                                    :cpu-ns (if (neg? c) :unknown (- (.getCurrentThreadCpuTime mx) c))})
                            [state nil])))
                  graph (clojure.core.async.flow/create-flow
                         {:procs {:probe {:proc (clojure.core.async.flow/process step {:workload :io})}}
                          :conns [] :io-exec executor})
                  reply (clojure.core.async/chan 1)]
              (try
                (clojure.core.async.flow/start graph)
                (clojure.core.async.flow/resume graph)
                (let [t (System/nanoTime)
                      rows (mapv (fn [_]
                                   (clojure.core.async.flow/inject graph [:probe :work] [[reply 100]])
                                   (first (clojure.core.async/alts!!
                                           [reply (clojure.core.async/timeout 5000)] :priority true)))
                                 (range 100))]
                  {:virtual? virtual? :ms (/ (- (System/nanoTime) t) 1e6)
                   :first (first rows) :last (last rows)})
                (finally (clojure.core.async.flow/stop graph)
                         (.shutdown executor)
                         (.awaitTermination executor 5 java.util.concurrent.TimeUnit/SECONDS)
                         (clojure.core.async/close! reply)))))]
  (mapv run [true false true false]))

;; Counter cost, with warmup, on the invoking platform thread.
(let [mx ^com.sun.management.ThreadMXBean (java.lang.management.ManagementFactory/getThreadMXBean)
      os ^com.sun.management.OperatingSystemMXBean (java.lang.management.ManagementFactory/getOperatingSystemMXBean)
      run (fn [f]
            (dotimes [_ 1000] (f))
            (let [t (System/nanoTime)] (dotimes [_ 100000] (f)) (/ (- (System/nanoTime) t) 1e8)))]
  {:nanotime-us (run #(System/nanoTime))
   :allocation-us (run #(.getCurrentThreadAllocatedBytes mx))
   :cpu-us (run #(.getCurrentThreadCpuTime mx))
   :both-us (run #(vector (.getCurrentThreadAllocatedBytes mx) (.getCurrentThreadCpuTime mx)))
   :jvm-counter-pair-us (run #(vector (.getTotalThreadAllocatedBytes mx) (.getProcessCpuTime os)))})
