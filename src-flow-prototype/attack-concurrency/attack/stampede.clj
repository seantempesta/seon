(ns attack.stampede
  "ATTACK 5 -- the wake path under load: every commit fires listen!, every
   listen! submits a full scan!, and every scan! commits again."
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store])
  (:import (java.util.concurrent Executors)))

(defn section [n title] (println (format "\n--- %s. %s" n title)))

(defn one-step [_] ["{:note \"tick\"}"])

(defn -main [& [root]]
  (let [root (or root "/private/tmp/attack")]

    (section "5a" "LISTEN! STAMPEDE: commits per useful run, as n grows")
    (doseq [n [2 5 10]]
      (let [conn (store/fresh! (str root "/st-" n) {:config/lease-ms 600000})
            commits (atom 0)]
        (doseq [i (range n)] (d/transact conn {:tx-data [{:agent/id (str "z" i) :agent/counter 0}]}))
        (reset! driver/claims-lost 0)
        (d/listen conn ::count (fn [_] (swap! commits inc)))
        (driver/wake! conn "c0" one-step nil)
        (let [t0 (System/nanoTime)
              deadline (+ (System/currentTimeMillis) 45000)]
          (d/transact conn {:tx-data (mapv (fn [i] {:message/id (str "m" i)
                                                    :message/to [:agent/id (str "z" i)]
                                                    :message/body "go"})
                                           (range n))})
          (loop []
            (when (and (< (System/currentTimeMillis) deadline)
                       (or (seq (d/q '[:find ?r :where [?r :run/open? true]] (d/db conn)))
                           (< (count (d/q '[:find ?r :where [?r :run/id _]] (d/db conn))) n)))
              (Thread/sleep 200) (recur)))
          (let [settle-at (System/currentTimeMillis)]
            (Thread/sleep 4000)
            (println (format "    n=%-3d  settled in %5d ms  runs=%d  counters ok=%s"
                             n (- settle-at (quot t0 1000000) (- (quot (System/nanoTime) 1000000) (System/currentTimeMillis)))
                             (count (d/q '[:find ?r :where [?r :run/id _]] (d/db conn)))
                             (every? #(= 1 (:agent/counter (d/pull (d/db conn) [:agent/counter] [:agent/id (str "z" %)])))
                                     (range n))))
            (println (format "    n=%-3d  COMMITS=%d (%.1f per run; the useful number is ~4)  LOST CAS claims=%d  still-open=%d"
                             n @commits (/ (double @commits) n) @driver/claims-lost
                             (count (d/q '[:find ?r :where [?r :run/open? true]] (d/db conn)))))
            (Thread/sleep 3000)
            (println (format "    n=%-3d  commits 3s after settling: %d  (a quiet system should not grow)" n @commits))))
        (d/unlisten conn ::driver/wake)
        (d/unlisten conn ::count)))

    (section "5b" "LEASE THEFT WHILE PARKED ON THE SEMAPHORE (the review's admitted item 2)")
    (let [conn (store/fresh! (str root "/park") {:config/compute-permits 1
                                                 :config/time-limit-ms 10000
                                                 :config/lease-ms 300})]
      (doseq [i ["hog" "victim"]] (d/transact conn {:tx-data [{:agent/id i :agent/counter 0}]}))
      (let [rh (driver/start-run! conn {:run-id "H" :agent-id "hog"
                                        :sources ["(do (host/block 3000) {:note \"hog\"})"]})
            rv (driver/start-run! conn {:run-id "V" :agent-id "victim"
                                        :sources ["{:note \"victim\"}"]})
            seen (atom [])
            p (Executors/newVirtualThreadPerTaskExecutor)]
        (.submit p ^Runnable (fn [] (driver/claim! conn rh "hog-c" 300) (driver/drive-run! conn rh "hog-c")))
        (Thread/sleep 200)
        (.submit p ^Runnable (fn [] (when (driver/claim! conn rv "A" 300)
                                      (driver/drive-run! conn rv "A" (fn [i _] (swap! seen conj ["A" i]))))))
        (Thread/sleep 900)                     ; A is parked on the semaphore; its lease is long gone
        (println "    victim claimable while A is parked on the semaphore?"
                 (pr-str (mapv #(:run/id (d/pull (d/db conn) [:run/id] %)) (driver/claimable (d/db conn)))))
        (.submit p ^Runnable (fn [] (when (driver/claim! conn rv "B" 300)
                                      (driver/drive-run! conn rv "B" (fn [i _] (swap! seen conj ["B" i]))))))
        (Thread/sleep 8000)
        (println "    victim step executions:" (pr-str @seen))
        (println "    victim :agent/counter =" (:agent/counter (d/pull (d/db conn) [:agent/counter] [:agent/id "victim"]))
                 " (plan had 1 step)")))

    (println "\nOK")
    (System/exit 0)))
