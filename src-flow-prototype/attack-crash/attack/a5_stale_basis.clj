(ns attack.a5-stale-basis
  "ATTACK 5: no crash, no stolen claim, one writer -- just two runs.

   The design's rule is 'each step's basis is the db-after of the previous
   step's commit'. driver/drive-run! captures that basis BEFORE the eval and
   the transform then computes (inc seen) from it and commits AFTER the eval.
   The read and the write are separated by the whole evaluation.

   That is a plain lost update, and it needs no failure at all to fire: two
   messages for the SAME agent open two runs, and wake!/scan! drives them on
   separate virtual threads (driver.clj:171-195 submits one scan per commit).

   Expected if the design is sound: agent/counter == total steps committed.
   Anything less is a silently clobbered write."
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(defn sources-for [tag n]
  (mapv (fn [i] (format "(do (host/block %d) {:note \"%s-%d\"})" (if (even? i) 250 30) tag i))
        (range n)))

(defn -main [path & _]
  (let [conn (store/fresh! path {:config/time-limit-ms 30000 :config/lease-ms 60000})
        n 5]
    (d/transact conn {:tx-data [{:agent/id "m1"}]})
    (println "=== A5: two concurrent runs for ONE agent, one writer, no crash ===")
    (let [r1 (driver/start-run! conn {:run-id "run/m1/a" :agent-id "m1" :sources (sources-for "a" n)})
          r2 (driver/start-run! conn {:run-id "run/m1/b" :agent-id "m1" :sources (sources-for "b" n)})]
      (driver/claim! conn r1 "P1" 60000)
      (driver/claim! conn r2 "P2" 60000)
      (println "two runs of" n "steps each = " (* 2 n) "committed steps expected")
      (let [f1 (future (driver/drive-run! conn r1 "P1"))
            f2 (future (driver/drive-run! conn r2 "P2"))]
        (println "run a executed:" (pr-str (mapv :seon.eval/index @f1)))
        (println "run b executed:" (pr-str (mapv :seon.eval/index @f2))))
      (let [db (d/db conn)
            counter (:agent/counter (d/pull db [:agent/counter] [:agent/id "m1"]))
            logs (:agent/log (d/pull db [:agent/log] [:agent/id "m1"]))
            receipts (d/q '[:find (count ?e) . :where [?e :seon.eval/outcome :ok]] db)]
        (println "\nGROUND TRUTH:")
        (println "  terminal :ok receipts:" receipts "(every step really did commit)")
        (println "  agent/log lines:" (count logs) "of" (* 2 n))
        (println "  agent/counter:" counter " (must be" (* 2 n) ")")
        (println "  VERDICT:" (if (= counter (* 2 n))
                                "no lost update"
                                (format "LOST UPDATE: %d of %d increments silently clobbered"
                                        (- (* 2 n) counter) (* 2 n))))))
    (System/exit 0)))
