(ns attack.a3b-child
  "Child for A3b. Holds a LONG-LIVED connection while the parent writes, then
   answers two questions on nudge:
     VISIBLE  -- does my connection see the parent's committed transaction?
     CLAIM    -- do I win the epoch CAS at a wall-clock rendezvous?"
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(defn -main [path & _]
  (let [conn (store/reopen! path)
        run (d/q '[:find ?r . :where [?r :run/id "run/z1"]] (d/db conn))]
    (println "READY" run) (flush)
    ;; question 1: visibility of another process's commit on a held connection
    (read-line)
    (println "VISIBLE" (pr-str (:agent/counter (d/pull (d/db conn) [:agent/counter] [:agent/id "probe"]))))
    (flush)
    ;; question 2: simultaneous CAS at a wall-clock rendezvous
    (let [at (parse-long (read-line))]
      (loop [] (when (< (System/currentTimeMillis) at) (recur)))
      (let [report (driver/claim! conn run "child" 60000)]
        (println "CLAIM" (if report "WON" "LOST")
                 "my-view-epoch" (:run/epoch (d/pull (d/db conn) [:run/epoch] run)))
        (flush)
        (when report
          (println "DROVE" (pr-str (mapv :seon.eval/index (driver/drive-run! conn run "child"))))
          (flush))))
    (println "COUNTER" (:agent/counter (d/pull (d/db conn) [:agent/counter] [:agent/id "z1"]))) (flush)
    (println "CHILDDONE") (flush)
    (System/exit 0)))
