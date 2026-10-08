;; Measurement form, evaluated through the owned root's operator PREPL.
;; Bind phase, from and to as documented in agent-memory.md. No Vars are changed.
(do
  (require 'seon.pass-probe 'seon.cache 'clojure.core.async.flow)
  (let [instance (get @seon.operator.runtime/running-instances "default")
        handle (:seon.turn.loop/cluster instance)
        routing (:seon.agent/routing handle)
        mx (java.lang.management.ManagementFactory/getMemoryMXBean)
        threads (java.lang.management.ManagementFactory/getThreadMXBean)
        agent-id #(str "agent-memory-" %)
        mcp (fn [id code]
              ((#'seon.pass-probe/mcp-pass {"agent" id "cluster" "default" "mode" "sci" "code" code})))
        rosters #(cons @routing (map deref (vals (:seon.agent/children @routing))))
        snapshot
        (fn []
          (System/gc)
          (System/gc)
          (let [heap (.getHeapMemoryUsage mx)
                rs (vec (rosters))
                contexts (vec (mapcat (comp vals :seon.agent/contexts) rs))
                armed (vec (mapcat (comp vals :seon.agent/armed) rs))]
            {:heap-used (.getUsed heap) :heap-committed (.getCommitted heap) :heap-max (.getMax heap)
             :platform-threads (.getThreadCount threads)
             :children (count (:seon.agent/children @routing))
             :armed (count armed) :contexts (count contexts)
             :unique-base-envs (count (set (keep #(some-> (:seon.sci.eval/base-ctx %) :env System/identityHashCode) contexts)))
             :unique-agent-envs (count (set (keep #(some-> (:seon.sci.eval/ctx %) :env System/identityHashCode) contexts)))
             :cache (seon.cache/status)
             :gc (mapv (fn [g] {:name (.getName g) :count (.getCollectionCount g) :ms (.getCollectionTime g)})
                       (java.lang.management.ManagementFactory/getGarbageCollectorMXBeans))}))]
    (case phase
      :snapshot (snapshot)
      :add (mapv (fn [i]
                   (let [t (System/nanoTime) reply (mcp (agent-id i) "(+ 1 2)")]
                     {:agent i :ms (/ (- (System/nanoTime) t) 1e6) :reply reply})) (range from to))
      :inspect
      {:input-arguments (vec (.getInputArguments (java.lang.management.ManagementFactory/getRuntimeMXBean)))
       :thread-names (frequencies (map #(.getThreadName %) (remove nil? (seq (.getThreadInfo threads (.getAllThreadIds threads))))))
       :cache-entries (.estimatedSize (.synchronous (:seon.cache/cache @(var-get #'seon.cache/process))))
       :db-config (select-keys (:config @(:seon.db/connection handle)) [:cache-size :store-cache-size :index-config])}
      :concurrent
      (let [ledger (atom []) timings (atom [])]
        (seon.test-support/with-provider
         ledger (seon.test-support/reply-queue (vec (repeat 10 "```clojure\n(+ 1 2)\n```"))) timings
         (fn [server]
           (let [setup (mapv (fn [i]
                              (mcp (agent-id i)
                                   (str "(my.agent/settings! "
                                        (seon.id/printed
                                         (merge (seon.test-support/provider-config server)
                                                {:seon.config.ai/no-provider false
                                                 :seon.config.run/max-episode-runs 1})) ")")))
                            (range (+ from 10) (+ from 20)))
                 model-passes (mapv (fn [i] (#'seon.pass-probe/model-pass
                                            (assoc (#'seon.pass-probe/agent-world "default" (agent-id i))
                                                   :seon.pass-probe/ledger ledger))) (range (+ from 10) (+ from 20)))
                 recording (jdk.jfr.Recording. (jdk.jfr.Configuration/getConfiguration "profile"))
                 ready (java.util.concurrent.CountDownLatch. 20)
                 start (java.util.concurrent.CountDownLatch. 1)
                 finished (java.util.concurrent.CountDownLatch. 20)
                 samples (java.util.concurrent.ConcurrentLinkedQueue.)
                 jobs (mapv (fn [work]
                              (let [task (java.util.concurrent.FutureTask.
                                          ^java.util.concurrent.Callable
                                          (fn [] (.countDown ready) (.await start)
                                            (let [t (System/nanoTime)]
                                              (try (let [reply (work)]
                                                     {:ms (/ (- (System/nanoTime) t) 1e6)
                                                      :reply (dissoc reply :seon.pass-probe/shown
                                                                     :seon.pass-probe/provider-requests)})
                                                   (finally (.countDown finished))))))]
                                (.start (Thread. task)) task))
                            (concat (map (fn [i] #(mcp (agent-id i) "(+ 1 2)")) (range from (+ from 10))) model-passes))]
             (try
               (.await ready)
               (System/gc) (System/gc)
               (.start recording)
               (let [before (.getUsed (.getHeapMemoryUsage mx))
                     allocated (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean threads)
                     t (System/nanoTime)]
                 (.countDown start)
                 (loop []
                   (.add samples [(.getUsed (.getHeapMemoryUsage mx)) (.getThreadCount threads)])
                   (when-not (.await finished 5 java.util.concurrent.TimeUnit/MILLISECONDS) (recur)))
                 (let [elapsed (/ (- (System/nanoTime) t) 1e6)
                       results (mapv #(.get %) jobs)]
                   (.stop recording)
                   (.dump recording (java.nio.file.Path/of
                                     "/Users/sean/src/seon/tmp/agent-memory/concurrent.jfr" (make-array String 0)))
                   {:setup setup :before before :peak-heap (reduce max 0 (map first samples))
                    :peak-platform-threads (reduce max 0 (map second samples))
                    :allocated-bytes (- (.getTotalThreadAllocatedBytes ^com.sun.management.ThreadMXBean threads) allocated)
                    :samples (.size samples) :wall-ms elapsed :results results
                    :provider-requests (count @ledger) :provider-timings @timings}))
               (finally (.close recording)))))))
      :retire (mapv (fn [i]
                      {:agent i :result (some-> (:seon.pass-probe/host (#'seon.pass-probe/agent-world "default" (agent-id i)))
                                                          seon.cluster.host/retire!)}) (range from to)))))
