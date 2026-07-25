(ns attack.a1-child-commit
  "Child under test for A1/A2. Commits one small transaction at a time,
   announcing BEFORE (PRE n) and AFTER (OK n) each d/transact returns.
   The parent SIGKILLs on a chosen marker, so we can land the kill either
   strictly after a returned commit (durability) or inside the commit."
  (:require [datahike.api :as d]
            [flow.store :as store]))

(defn -main [path & _]
  (let [conn (store/fresh! path)]
    (println "PID" (.pid (java.lang.ProcessHandle/current))) (flush)
    (loop [i 0]
      (when (< i 400)
        (println "PRE" i) (flush)
        (d/transact conn {:tx-data [{:agent/id (str "a" i) :agent/counter (long i)}]})
        (println "OK" i) (flush)
        (recur (inc i))))
    (println "DONE") (flush)))
