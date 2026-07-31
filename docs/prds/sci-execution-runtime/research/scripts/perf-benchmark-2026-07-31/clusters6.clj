;; Interference, warm and alternating, so drift cannot masquerade as effect.
(require '[bench.support :as s] '[seon.cluster.store :as store])
(def targets user/targets)
(def measured (first targets))
(def report2 user/report2)
(def probe-set! user/probe-set!)
(def churn-row user/churn-row)
(alter-var-root #'s/web-port (constantly (:port measured)))
(def clients (mapv (fn [_] (s/sse-client "root")) (range 5)))
(s/await-initial-paint! clients)
(Thread/sleep 800)
(probe-set! measured clients 10)      ; warm-up, discarded
(def stop (atom true))
(def counted (atom 0))
(doseq [t (rest targets)]
  (.start (Thread/ofVirtual)
          (fn [] (loop [i 0]
                   (when-not (= :done @stop)
                     (when-not @stop
                       (store/transact! (:connection t) (churn-row (:root t) (str "m" i)))
                       (swap! counted inc))
                     (recur (inc i)))))))
(doseq [round [1 2 3]]
  (reset! stop true) (Thread/sleep 2000)
  (report2 (keyword (str "ALONE-" round)) (probe-set! measured clients 20))
  (reset! stop false) (Thread/sleep 2000) (reset! counted 0)
  (let [t0 (System/nanoTime)
        r (probe-set! measured clients 20)]
    (report2 (keyword (str "WITH-4-NEIGHBOURS-" round)) r)
    (println :NEIGHBOUR-RATE-PER-S (/ (Math/round (* 100.0 (/ (* 1e9 @counted) (- (System/nanoTime) t0)))) 100.0))))
(reset! stop :done)
(run! s/close-client! clients)
(alter-var-root #'s/web-port (constantly 7953))
(println :MEM (pr-str (s/memory)))
(println :CLUSTERS6-DONE)
