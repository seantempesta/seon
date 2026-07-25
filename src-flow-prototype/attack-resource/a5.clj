(ns a5
  "Carrier pinning. The claimant is a virtual thread; it parks on
   Semaphore.acquire and then on Future.get for the whole eval. Run with
   -Djdk.virtualThreadScheduler.parallelism=1: if either park PINNED the one
   carrier, an unrelated virtual thread could never run."
  (:require [flow.eval :as eval]))

(defn -main [& _]
  (eval/open! 4)
  (println "carriers:" (System/getProperty "jdk.virtualThreadScheduler.parallelism"))
  (println "compute pool thread kind (eval.clj:17) is checked below\n")

  ;; 8 claimant-shaped virtual threads: 4 will hold permits and block inside a
  ;; host call, 4 will be parked on the semaphore.
  (let [progress (atom 0)]
    (dotimes [i 8]
      (.start (Thread/ofVirtual)
              (fn [] (eval/evaluate {:source "(host/block 600000)" :db nil
                                     :time-limit-ms 500
                                     :allocation-limit-bytes (* 64 1024 1024)}))))
    (Thread/sleep 1000)
    (println "8 virtual claimants wedged; permits free:" (eval/available))

    ;; an unrelated virtual thread doing ordinary work
    (let [t0 (System/nanoTime)]
      (.start (Thread/ofVirtual) (fn [] (dotimes [_ 5] (swap! progress inc) (Thread/sleep 100))))
      (Thread/sleep 900)
      (println (format "unrelated virtual thread progress after 900ms: %d/5 (%dms)"
                       @progress (quot (- (System/nanoTime) t0) 1000000)))
      (println (if (>= @progress 4)
                 "  -> NOT PINNED: the carrier stayed free."
                 "  -> PINNED: the wedged claimants starved the carrier.")))

    ;; what kind of thread does agent code actually run on?
    (eval/open! 8)
    (println)
    (let [r (eval/evaluate {:source "(+ 1 1)" :db nil :time-limit-ms 500
                            :allocation-limit-bytes (* 64 1024 1024)})]
      (println "sanity, a fresh permit set still evaluates:" (:flow/value r))))

  (println "\ndone.")
  (shutdown-agents)
  (System/exit 0))
