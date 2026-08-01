(require '[datahike.api :as d]
         '[seon.ai :as ai]
         '[seon.cluster :as cluster])

(import '[java.lang.management ManagementFactory]
        '[java.util Date UUID])

(defn- memory-sample
  [phase extra]
  (let [usage (.getHeapMemoryUsage (ManagementFactory/getMemoryMXBean))]
    (println
     (pr-str
      (merge
       {:jvm-tuning/phase phase
        :jvm-tuning/uptime-ms
        (.getUptime (ManagementFactory/getRuntimeMXBean))
        :jvm-tuning/heap-used-bytes (.getUsed usage)
        :jvm-tuning/heap-committed-bytes (.getCommitted usage)
        :jvm-tuning/heap-max-bytes (.getMax usage)}
       extra)))
    (flush)))

(defn- closed-run-count
  [db]
  (or
   (d/q '[:find (count ?run) .
          :where
          [?run :seon.cluster.run/id _]
          [?run :seon.cluster.run/closed-at _]]
        db)
   0))

(defn- await-closed-run!
  [connection target]
  (let [result (promise)
        listener-key (random-uuid)
        observe! (fn [db]
                   (when (<= target (closed-run-count db))
                     (deliver result target)))]
    (d/listen connection listener-key #(observe! (:db-after %)))
    (try
      (observe! @connection)
      (when (= ::timeout (deref result 30000 ::timeout))
        (throw
         (ex-info "The scripted turn did not settle."
                  {:jvm-tuning/target target
                   :jvm-tuning/closed-runs
                   (closed-run-count @connection)})))
      (finally
        (d/unlisten connection listener-key)))))

(def reply-source
  (str
   "(do "
   "(pr-str (mapv (fn [n] "
   "{:jvm.tuning/id n "
   ":jvm.tuning/text \"repeated-edn-token-repeated-edn-token\"}) "
   "(range 1500))) "
   "(my.run/complete \"benchmark turn complete\"))"))

(defn- drive-turns!
  [connection turns]
  (doseq [ordinal (range turns)]
    (let [message-id (str "jvm-tuning-" ordinal "-" (UUID/randomUUID))
          content
          (str "Turn " ordinal "\n"
               (apply str (repeat 1024
                                  "repeated prompt token for string allocation; ")))]
      (d/transact
       connection
       [{:seon.cluster.message/id message-id
         :seon.cluster.message/to [:seon.cluster.agent/id "root"]
         :seon.cluster.message/content content
         :seon.cluster.message/at (Date.)}])
      (await-closed-run! connection (inc ordinal)))))

(defn- idle-samples!
  [idle-seconds]
  (doseq [elapsed (range 15 (inc idle-seconds) 15)]
    (Thread/sleep 15000)
    (memory-sample :idle {:jvm-tuning/idle-seconds elapsed})))

(defn- run-init!
  [root idle-seconds]
  (memory-sample :loaded {})
  (let [started (System/nanoTime)
        published (cluster/refresh-source! root)]
    (memory-sample
     :init-complete
     {:jvm-tuning/workload-ms
      (/ (double (- (System/nanoTime) started)) 1000000.0)
      :jvm-tuning/source-digest (:seon.source/digest published)}))
  (idle-samples! idle-seconds))

(defn- run-turn!
  [root turns idle-seconds]
  (memory-sample :loaded {})
  (cluster/refresh-source! root)
  (memory-sample :source-published {})
  (let [calls (atom 0)
        complete (fn [_request]
                   (swap! calls inc)
                   {:seon.ai/text reply-source})]
    (with-redefs [ai/complete complete]
      (let [instance
            (cluster/start!
             {:seon.boot/root root
              :seon.boot/cluster-name "jvm-tuning"})
            connection (:seon.boot/cluster-connection instance)]
        (System/gc)
        (Thread/sleep 1000)
        (memory-sample :turn-baseline
                       {:jvm-tuning/ready-ms
                        (:seon.boot/ready-ms instance)})
        (let [started (System/nanoTime)]
          (drive-turns! connection turns)
          (memory-sample
           :turn-complete
           {:jvm-tuning/workload-ms
            (/ (double (- (System/nanoTime) started)) 1000000.0)
            :jvm-tuning/provider-calls @calls
            :jvm-tuning/closed-runs (closed-run-count @connection)}))
        (idle-samples! idle-seconds)
        ;; Each case owns a one-use process and store. Exit after the final
        ;; flushed sample so flow/http-kit lifetime threads cannot add an
        ;; unrelated orderly-shutdown tail to the measured workload.
        (memory-sample :before-process-exit {})
        (System/exit 0)))))

(let [[mode root count-or-idle idle] *command-line-args*]
  (case mode
    "init" (run-init! root (parse-long count-or-idle))
    "turn" (run-turn! root (parse-long count-or-idle) (parse-long idle))
    (throw
     (ex-info "Use: workload.clj init ROOT IDLE-SECONDS | turn ROOT TURNS IDLE-SECONDS"
              {:jvm-tuning/arguments *command-line-args*}))))

(shutdown-agents)
