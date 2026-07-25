(ns flow.crashee
  "A claimant that will be SIGKILLed mid-turn. It announces each step on
   stdout AFTER the :running receipt is committed, so the parent can kill it
   at a known position."
  (:require [datahike.api :as d] [flow.driver :as driver] [flow.store :as store]))

(def sources
  (mapv (fn [i] (format "(do (host/block 400) {:note \"slow step %d\"})" i)) (range 7)))

(defn -main [path & _]
  (let [conn (store/fresh! path {:config/time-limit-ms 30000})]
    (d/transact conn {:tx-data [{:agent/id "c1"}]})
    (let [run (driver/start-run! conn {:run-id "run/c1" :agent-id "c1" :sources sources})]
      (driver/claim! conn run "doomed" 60000)
      (println "PID" (.pid (java.lang.ProcessHandle/current))) (flush)
      (driver/drive-run! conn run "doomed"
                         (fn [i _] (println "RUNNING" i) (flush)))
      (println "FINISHED") (flush))))
