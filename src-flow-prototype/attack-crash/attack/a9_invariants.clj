(ns attack.a9-invariants
  "ATTACK 9: two unenforced invariants the resume logic rests on.

   (a) DENSE RECEIPTS. driver/resume computes
         next-index = (if in-flight (key in-flight) (count receipts))
       Using (count receipts) as a position is only correct if receipts are
       dense from 0. Nothing enforces that. What does resume answer if a
       receipt is missing in the middle?

   (b) start-run! IS CHECK-THEN-ACT. driver/start-run! queries for an
       existing run and, if absent, transacts the plan -- with no CAS and no
       fence. wake!/scan! runs one scan per committed transaction on a
       virtual-thread pool, so two scans can call it at the same instant. In
       the real system the sources come from a MODEL, so the two calls do not
       agree. :run/id and :step/id are unique identities, so both transactions
       'succeed' -- and :step/source is cardinality one."
  (:require [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(defn -main [path & _]
  (println "=== A9(a): resume over a receipt set with a hole ===")
  (let [conn (store/fresh! path)]
    (d/transact conn {:tx-data [{:agent/id "h1"}]})
    (let [run (driver/start-run! conn {:run-id "run/h1" :agent-id "h1"
                                       :sources (mapv #(format "{:note \"s%d\"}" %) (range 7))})]
      ;; receipts for 0 1 2 5 6 -- indices 3 and 4 missing, nothing :running
      (d/transact conn {:tx-data (mapv (fn [i] {:seon.eval/id (str "run/h1#" i)
                                                :seon.eval/run run
                                                :seon.eval/index (long i)
                                                :seon.eval/total 7
                                                :seon.eval/source (format "{:note \"s%d\"}" i)
                                                :seon.eval/outcome :ok})
                                       [0 1 2 5 6])})
      (let [r (driver/resume (d/db conn) run)]
        (println "  receipts present for indices [0 1 2 5 6], total steps 7")
        (println "  resume ->" (pr-str (select-keys r [:total :next-index :in-flight])))
        (println "  remaining ->" (pr-str (mapv :index (:remaining r))))
        (println "  VERDICT:"
                 (if (= [3 4 5 6] (mapv :index (:remaining r)))
                   "correct -- the hole is detected"
                   (format "WRONG -- steps %s are silently SKIPPED FOREVER, and %s re-run"
                           (pr-str (remove (set (mapv :index (:remaining r))) [3 4]))
                           (pr-str (filter #{5 6} (mapv :index (:remaining r))))))))
      (d/release conn)))

  (println "\n=== A9(b): concurrent start-run! with two different plans ===")
  (let [path2 (str path "-b")
        conn (store/fresh! path2)]
    (d/transact conn {:tx-data [{:agent/id "r1"}]})
    (let [plan-a (mapv #(format "{:note \"PLAN-A step %d\"}" %) (range 7))
          plan-b (mapv #(format "{:note \"PLAN-B step %d\"}" %) (range 4))
          barrier (java.util.concurrent.CyclicBarrier. 2)
          f1 (future (.await barrier)
                     (driver/start-run! conn {:run-id "run/r1" :agent-id "r1" :sources plan-a}))
          f2 (future (.await barrier)
                     (driver/start-run! conn {:run-id "run/r1" :agent-id "r1" :sources plan-b}))
          e1 @f1 e2 @f2
          db (d/db conn)
          run (d/q '[:find ?r . :where [?r :run/id "run/r1"]] db)
          plan (into (sorted-map) (d/q '[:find ?i ?s :in $ ?r :where
                                         [?e :step/run ?r] [?e :step/index ?i]
                                         [?e :step/source ?s]] db run))
          runs (count (d/q '[:find ?r :where [?r :run/id "run/r1"]] db))]
      (println "  plan A had 7 steps, plan B had 4; both start-run! calls returned:" e1 e2)
      (println "  run entities with :run/id \"run/r1\":" runs)
      (println "  committed plan:")
      (doseq [[i s] plan] (println (format "    %d %s" i s)))
      (let [srcs (vals plan)
            mixed (and (some #(.contains ^String % "PLAN-A") srcs)
                       (some #(.contains ^String % "PLAN-B") srcs))]
        (println "  VERDICT:"
                 (cond mixed "FRANKENSTEIN PLAN -- one run's step list is spliced from two different model replies"
                       (= 7 (count plan)) "plan A won whole"
                       :else "plan B won whole")))
      (d/release conn)))
  (System/exit 0))
