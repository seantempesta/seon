(ns attack.a7-poison-transform
  "ATTACK 7: kill the driver with the agent's own return value.

   driver/transform splices the agent's returned :facts STRAIGHT into the
   step's tx-data (driver.clj:49 `(into (vec facts) ...)`), and drive-run!
   wraps no part of that transaction in a try. flow.eval promises 'never
   throws into the loop' -- but the eval SUCCEEDS here. The poison is in the
   value, and it detonates in the driver's own d/transact, outside every
   guard the design has.

   Three probes:
     (a) malformed tx-data       -- does the exception escape drive-run!?
     (b) after the escape        -- what state is the run left in, and does a
                                    survivor retry forever?
     (c) hostile-but-valid facts -- can a step retract its own run entity, or
                                    write to a DIFFERENT agent?"
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(defn -main [path & _]
  (println "=== A7: the agent's returned :facts reach d/transact unguarded ===")

  ;; (a) + (b) malformed tx-data
  (let [conn (store/fresh! path {:config/time-limit-ms 30000 :config/lease-ms 1000})
        sources ["{:note \"fine\"}"
                 "{:note \"poison\" :facts [[:db/add \"not-an-eid\" :nope 1]]}"
                 "{:note \"never reached\"}"]]
    (d/transact conn {:tx-data [{:agent/id "p1"}]})
    (let [run (driver/start-run! conn {:run-id "run/p1" :agent-id "p1" :sources sources})]
      (driver/claim! conn run "A" 1000)
      (println "\n(a) driving a run whose step 1 returns malformed :facts")
      (let [outcome (try {:ok (mapv :seon.eval/index (driver/drive-run! conn run "A"))}
                         (catch Throwable t {:threw (str (class t) ": "
                                                         (first (clojure.string/split-lines (str (.getMessage t)))))}))]
        (println "  drive-run! ->" (pr-str outcome))
        (println "  VERDICT:" (if (:threw outcome)
                                "the exception ESCAPED drive-run! -- nothing caught it"
                                "handled as a value")))
      (println "\n(b) state left behind:")
      (let [db (d/db conn)]
        (println "  receipts:" (pr-str (sort (d/q '[:find ?i ?o :in $ ?r :where
                                                    [?e :seon.eval/run ?r] [?e :seon.eval/index ?i]
                                                    [?e :seon.eval/outcome ?o]] db run))))
        (println "  run:" (pr-str (dissoc (d/pull db [:run/open? :run/epoch :run/claimant :run/lease-until] run) :db/id)))
        (println "  resume says:" (pr-str (select-keys (driver/resume db run) [:total :next-index :in-flight])))
        (println "  any fault recorded anywhere? receipts hold only"
                 (pr-str (into #{} (map second) (d/q '[:find ?i ?o :in $ ?r :where
                                                       [?e :seon.eval/run ?r] [?e :seon.eval/index ?i]
                                                       [?e :seon.eval/outcome ?o]] db run)))))
      (println "\n  a survivor now retries the same run (lease 1000ms, so it IS claimable):")
      (Thread/sleep 1200)
      (dotimes [attempt 3]
        (let [claimable? (some #{run} (driver/claimable (d/db conn)))]
          (if-not claimable?
            (println (format "    retry %d: not claimable" attempt))
            (do (driver/claim! conn run (str "S" attempt) 1000)
                (let [r (try (do (driver/drive-run! conn run (str "S" attempt)) :finished)
                             (catch Throwable _ :threw-again))]
                  (println (format "    retry %d: claimable, drive-run! -> %s" attempt (name r))))
                (Thread/sleep 1200)))))
      (println "  VERDICT: the run is a POISON PILL -- claimable forever, throws forever,"
               "\n           no fault fact, no attempt counter, no dead-letter path")
      (d/release conn)))

  ;; (c) hostile but perfectly valid facts
  (let [path2 (str path "-c")
        conn (store/fresh! path2 {:config/time-limit-ms 30000 :config/lease-ms 60000})]
    (d/transact conn {:tx-data [{:agent/id "p2"} {:agent/id "victim" :agent/counter 0}]})
    (let [sources ["{:note \"writing to someone else's entity\" :facts [[:db/add [:agent/id \"victim\"] :agent/counter 424242]]}"
                   "{:note \"retracting my own run\" :facts [[:db/retract [:run/id \"run/p2\"] :run/open? true]]}"]
          run (driver/start-run! conn {:run-id "run/p2" :agent-id "p2" :sources sources})]
      (driver/claim! conn run "A" 60000)
      (println "\n(c) hostile-but-VALID facts (no exception, everything commits):")
      (let [r (try (mapv :seon.eval/index (driver/drive-run! conn run "A"))
                   (catch Throwable t (str "threw: " (.getMessage t))))]
        (println "  drove:" (pr-str r)))
      (let [db (d/db conn)]
        (println "  victim's counter is now:"
                 (:agent/counter (d/pull db [:agent/counter] [:agent/id "victim"]))
                 " <- written by a DIFFERENT agent's step")
        (println "  run entity after the step retracted its own :run/open?:"
                 (pr-str (dissoc (d/pull db [:run/open? :run/epoch :run/claimant] run) :db/id)))))
    (d/release conn))
  (System/exit 0))
