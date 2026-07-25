(ns attack.a8-child-chain
  "Child for A8: installs the real wake!/listen! path over a b1->b2->b3
   message chain and then waits to be killed at an arbitrary instant."
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.program :as program]
            [flow.store :as store]))

(defn -main [path & _]
  (let [conn (store/fresh! path {:config/time-limit-ms 30000 :config/lease-ms 3000})]
    (d/transact conn {:tx-data [{:agent/id "b1"} {:agent/id "b2"} {:agent/id "b3"}
                                {:agent/id "sender"}]})
    (driver/wake! conn "child" program/reply nil)
    (d/transact conn {:tx-data [{:message/id "seed" :message/to [:agent/id "b1"]
                                 :message/from [:agent/id "sender"]
                                 :message/body (pr-str {:chain ["b2" "b3"]})}]})
    (println "SEEDED") (flush)
    (Thread/sleep 60000)
    (println "TIMEOUT") (flush)))
