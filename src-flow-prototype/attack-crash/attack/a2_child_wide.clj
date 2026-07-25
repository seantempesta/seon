(ns attack.a2-child-wide
  "Child for A2. Each transaction writes 200 entities tagged with the same
   :agent/counter, so a torn write is detectable: the reopened store must
   contain 0 or 200 of any given tag, never a number in between."
  (:require [datahike.api :as d]
            [flow.store :as store]))

(def width 200)

(defn -main [path & _]
  (let [conn (store/fresh! path)]
    (println "PID" (.pid (java.lang.ProcessHandle/current))) (flush)
    (loop [i 0]
      (when (< i 200)
        (println "PRE" i) (flush)
        (d/transact conn {:tx-data (mapv (fn [j] {:agent/id (str "b" i "-" j)
                                                  :agent/counter (long i)})
                                         (range width))})
        (println "OK" i) (flush)
        (recur (inc i))))
    (println "DONE") (flush)))
