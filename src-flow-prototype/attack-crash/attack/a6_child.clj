(ns attack.a6-child
  "Reusable claimant for the A6 sweep. Creates the run on the first round and
   joins it on later rounds. Announces RUNNING i (after the :running receipt
   is committed, before the eval) and CLOSED (after drive-run! returns), so
   the parent can land a SIGKILL at a chosen position."
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(def n 7)

(def sources
  (mapv (fn [i] (format "(do (host/block 200) {:note \"k%d\"})" i)) (range n)))

(defn -main [path me & _]
  (let [fresh? (not (d/database-exists? (store/cfg path)))
        conn (if fresh?
               (store/fresh! path {:config/time-limit-ms 30000 :config/lease-ms 60000})
               (store/reopen! path))]
    (when fresh? (d/transact conn {:tx-data [{:agent/id "k1"}]}))
    (let [run (driver/start-run! conn {:run-id "run/k1" :agent-id "k1" :sources sources})]
      (println "PID" (.pid (java.lang.ProcessHandle/current))) (flush)
      (if (driver/claim! conn run me 60000)
        (do (driver/drive-run! conn run me (fn [i _] (println "RUNNING" i) (flush)))
            (println "CLOSED") (flush))
        (do (println "CLAIMLOST") (flush)))
      (println "DONE") (flush)
      (System/exit 0))))
