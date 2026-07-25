(ns attack.a9b-plan-race
  "ATTACK 9b: settle whether the start-run! check-then-act race can splice
   one run's step plan out of two different model replies. 12 trials."
  (:require [clojure.java.io :as io]
            [datahike.api :as d]
            [flow.driver :as driver]
            [flow.store :as store]))

(defn trial [path i]
  (when (.exists (io/file path)) (run! io/delete-file (reverse (file-seq (io/file path)))))
  (let [conn (store/fresh! path)]
    (d/transact conn {:tx-data [{:agent/id "r1"}]})
    (let [plan-a (mapv #(format "{:note \"A%d\"}" %) (range 7))
          plan-b (mapv #(format "{:note \"B%d\"}" %) (range 4))
          barrier (java.util.concurrent.CyclicBarrier. 2)
          f1 (future (.await barrier) (driver/start-run! conn {:run-id "run/r1" :agent-id "r1" :sources plan-a}))
          f2 (future (.await barrier) (driver/start-run! conn {:run-id "run/r1" :agent-id "r1" :sources plan-b}))]
      @f1 @f2
      (let [db (d/db conn)
            run (d/q '[:find ?r . :where [?r :run/id "run/r1"]] db)
            plan (into (sorted-map) (d/q '[:find ?i ?s :in $ ?r :where
                                           [?e :step/run ?r] [?e :step/index ?i]
                                           [?e :step/source ?s]] db run))
            tags (mapv (fn [s] (subs s 8 9)) (vals plan))
            runs (count (d/q '[:find ?r :where [?r :run/id "run/r1"]] db))]
        (d/release conn)
        {:trial i :runs runs :total (count plan) :tags (apply str tags)
         :mixed (and (some #{\A} tags) (some #{\B} tags))}))))

(defn -main [path & _]
  (println "=== A9b: 12 trials of concurrent start-run! (plan A=7 steps, plan B=4) ===")
  (println "    tags show which reply each committed step source came from")
  (let [rs (mapv #(trial (str path "-" %) %) (range 12))]
    (doseq [{:keys [trial runs total tags mixed]} rs]
      (println (format "  trial %2d: run-entities=%d steps=%d plan=%s %s"
                       trial runs total tags (if mixed "<== FRANKENSTEIN PLAN" ""))))
    (println)
    (println "  trials producing a spliced plan:" (count (filter :mixed rs)) "/ 12")
    (println "  VERDICT:" (if (some :mixed rs)
                            "CONFIRMED -- one run's step list can be built from two different replies"
                            "not observed in 12 trials (the race exists but one reply won each time)")))
  (System/exit 0))
