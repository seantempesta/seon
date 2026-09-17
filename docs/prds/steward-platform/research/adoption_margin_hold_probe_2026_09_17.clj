(require '[clojure.edn :as edn]
         '[seon.operator.state :as state])

(let [root "tmp/adoption-margin-root"
      silence (:seon.config.operator/event-silence-backstop-ms
               (edn/read-string (slurp "config/default.edn")))
      progress (atom "schema declarations")
      request {:seon.operator.lock/path (state/root-lifecycle-lock-path root)
               :seon.operator.lock/command "adoption phase liveness probe"
               :seon.operator.lock/acquisition-timeout-ms silence
               :seon.config.operator/event-silence-backstop-ms silence
               :seon.operator.lock/progress progress}
      started (System/nanoTime)]
  (state/with-lifecycle-lock!
   request
   (fn []
     (dotimes [phase 36]
       (reset! progress (str "completed batch " phase))
       (println "progress" phase "elapsed-ms" (quot (- (System/nanoTime) started) 1000000))
       (flush)
       ;; Synthetic phase work; the monitor receives only completed work events.
       (Thread/sleep 15000))))
  (println "progressing-holder-completed-ms" (quot (- (System/nanoTime) started) 1000000))
  (let [release (promise)
        started (System/nanoTime)
        refusal (try
                  (state/with-lifecycle-lock!
                   request
                   (fn [] (reset! progress "SCI acquisition") @release))
                  nil
                  (catch clojure.lang.ExceptionInfo failure (ex-data failure)))]
    (println "stalled-holder-refused-ms" (quot (- (System/nanoTime) started) 1000000)
             (pr-str refusal))
    (assert (= :seon.operator/lock-hold-timeout (:seon.error/kind refusal)))
    (assert (= "SCI acquisition" (get-in refusal [:seon.operator.lock/holder :seon.operator.lock/phase])))
    (deliver release :done)
    ;; Joining by the same kernel lock proves the holder actually released it.
    (state/with-lifecycle-lock! (dissoc request :seon.operator.lock/progress) (constantly :released))))
(shutdown-agents)
