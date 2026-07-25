(ns attack.a3c-child-writer
  "Child for A3c: commits 40 process-tagged entities against the same file
   store the parent is also writing to."
  (:require [datahike.api :as d] [flow.store :as store]))

(defn -main [path & _]
  (let [conn (store/reopen! path)]
    (println "READY") (flush)
    (read-line)
    (dotimes [i 40]
      (d/transact conn {:tx-data [{:agent/id (str "child-" i)}]})
      (Thread/sleep 5))
    (println "CHILDWROTE 40") (flush)
    (System/exit 0)))
