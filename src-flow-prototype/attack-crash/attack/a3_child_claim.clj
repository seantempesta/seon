(ns attack.a3-child-claim
  "Child for A3. Connects to a store the parent already created, then on a
   stdin nudge tries to claim!/drive the run -- while the parent tries the
   same thing from its own JVM. Nothing is killed here: the question is
   whether the epoch CAS excludes a SECOND LIVE PROCESS at all."
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(defn -main [path & _]
  (let [conn (store/reopen! path)
        run (d/q '[:find ?r . :where [?r :run/id "run/z1"]] (d/db conn))]
    (println "READY" run) (flush)
    (read-line)
    (let [report (driver/claim! conn run "child" 60000)]
      (println "CLAIM" (if report "WON" "LOST")
               "epoch-now" (:run/epoch (d/pull (d/db conn) [:run/epoch] run))) (flush)
      (when report
        (let [done (driver/drive-run! conn run "child")]
          (println "DROVE" (pr-str (mapv :seon.eval/index done))) (flush))))
    (println "COUNTER" (:agent/counter (d/pull (d/db conn) [:agent/counter] [:agent/id "z1"]))) (flush)
    (println "CHILDDONE") (flush)
    (System/exit 0)))
