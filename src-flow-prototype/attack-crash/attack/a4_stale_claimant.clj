(ns attack.a4-stale-claimant
  "ATTACK 4: the stale claimant, under a genuinely serialized single writer.

   A3/A3c showed the cross-process CAS is not a CAS at all. This attack
   removes that objection: ONE JVM, ONE connection, so every CAS is real and
   every commit is serialized. The question left is the driver's own:

     drive-run! validates the claim ONCE, at the top, and then folds every
     step to the end without ever re-checking the epoch it CAS'd.

   So: claimant A takes the run with a 500ms lease and enters a 4s host call
   (the measured hole -- the time-limit cannot fire inside a host call, so A
   cannot renew and cannot be interrupted). The lease goes stale, a survivor
   legitimately claims the run by CAS and drives it to the end. Then A comes
   back from its host call and keeps committing, because nothing ever told it
   it was fired.

   Ground truth to check afterwards: how many times was each index executed,
   and does :agent/counter still equal the number of steps?"
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(def sources
  (mapv (fn [i]
          (if (= i 2)
            "(do (host/block 4000) {:note \"step 2 -- longer than the lease\"})"
            (format "(do (host/block 60) {:note \"step %d\"})" i)))
        (range 6)))

(defn -main [path & _]
  (let [conn (store/fresh! path {:config/time-limit-ms 30000 :config/lease-ms 500})
        executed (atom [])
        record! (fn [who] (fn [i _] (swap! executed conj [who i])))]
    (d/transact conn {:tx-data [{:agent/id "s1"}]})
    (let [run (driver/start-run! conn {:run-id "run/s1" :agent-id "s1" :sources sources})]
      (println "=== A4: stale claimant, ONE writer, real CAS, 500ms lease ===")
      (println "steps:" (count sources) " step 2 blocks 4000ms inside a host call")
      (let [a (future (driver/claim! conn run "A" 500)
                      (driver/drive-run! conn run "A" (record! :A)))]
        (Thread/sleep 2000)
        (let [cl (driver/claimable (d/db conn))
              lease (:run/lease-until (d/pull (d/db conn) [:run/lease-until :run/claimant :run/epoch] run))]
          (println "\nat t=2s, while A is blocked inside step 2:")
          (println "  run state:" (pr-str (dissoc (d/pull (d/db conn) [:run/epoch :run/claimant :run/lease-until] run) :db/id)))
          (println "  lease expired by:" (- (System/currentTimeMillis) (or lease 0)) "ms")
          (println "  driver/claimable says:" (pr-str cl) (if (seq cl) "-- the run is stealable" "-- not stealable")))
        (println "\nsurvivor claims by CAS:"
                 (if (driver/claim! conn run "SURVIVOR" 60000) "WON" "LOST"))
        (println "  epoch now:" (:run/epoch (d/pull (d/db conn) [:run/epoch] run)))
        (let [s (driver/drive-run! conn run "SURVIVOR" (record! :SURVIVOR))]
          (println "  SURVIVOR executed:" (pr-str (mapv :seon.eval/index s))))
        (println "\nnow waiting for A to come back from its host call...")
        (println "  A executed:" (pr-str (mapv :seon.eval/index @a)))))

    (let [db (d/db conn)
          run (d/q '[:find ?r . :where [?r :run/id "run/s1"]] db)
          per-index (->> @executed (group-by second) (into (sorted-map) (map (fn [[i v]] [i (mapv first v)]))))]
      (println "\nGROUND TRUTH:")
      (println "  executions per index:")
      (doseq [[i whos] per-index]
        (println (format "    index %d executed %dx by %s%s" i (count whos) (pr-str whos)
                         (if (> (count whos) 1) "   <== DOUBLE EXECUTION" ""))))
      (println "  receipts:" (pr-str (sort (d/q '[:find ?i ?o :in $ ?r :where
                                                  [?e :seon.eval/run ?r] [?e :seon.eval/index ?i]
                                                  [?e :seon.eval/outcome ?o]] db run))))
      (println "  run:" (pr-str (dissoc (d/pull db [:run/epoch :run/claimant :run/open?] run) :db/id)))
      (let [n (:agent/counter (d/pull db [:agent/counter] [:agent/id "s1"]))]
        (println "  agent/counter:" n "  (must be" (count sources) "-- one per step)")
        (println "  VERDICT:" (if (= n (count sources))
                                "counter intact"
                                (format "STATE CORRUPTED: counter is %d, should be %d"
                                        n (count sources)))))
      (println "  agent/log lines:" (count (:agent/log (d/pull db [:agent/log] [:agent/id "s1"])))))
    (System/exit 0)))
