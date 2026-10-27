;; Per run of the measured tasks: outcome, turns, and per model turn its prompt, cache hits and
;; misses, completion tokens and cost (seon.model/cost), read from the run's branch.
(require '[datahike.api :as d] '[seon.model :as m] '[seon.root :as c])
(defn run-stats [sys]
  (let [db @(:conn sys)
        tasks (clojure.edn/read-string (slurp (str (System/getenv "SEON_CHECKOUT") "/target/tasks.edn")))]
    (vec (for [tid tasks
               [rid status outcome turns branch model] (d/q '[:find ?r ?s ?o ?n ?b ?m :in $ ?t :where [?te :seon.task/id ?t] [?e :seon.run/task ?te] [?e :seon.run/id ?r]
                                                              [?e :seon.run/status ?s] [(get-else $ ?e :seon.run/outcome :none) ?o] [(get-else $ ?e :seon.run/turns -1) ?n]
                                                              [(get-else $ ?e :seon.run/branch :none) ?b] [(get-else $ ?e :seon.run/model "?") ?m]] db tid)
               :let [bdb (when (not= :none branch) (:db @(c/branch-db sys branch)))
                     ts (when bdb
                          (->> (d/q '[:find ?n ?e :in $ ?r :where [?ru :seon.run/id ?r] [?e :seon.turn/run ?ru] [?e :seon.turn/origin :model] [?e :seon.turn/n ?n]] bdb rid)
                               sort (mapv (fn [[n e]] (let [t (d/pull bdb [:seon.turn/tokens :seon.turn/cache-hit-tokens :seon.turn/cache-miss-tokens :seon.turn/finish :seon.turn/forms :seon.turn/errors] e)]
                                                        (assoc t :n n :usd (m/cost (if (= "?" model) "deepseek-flash" model) t)))))))]]
           {:task tid :run rid :status status :outcome outcome :turns turns
            :per-turn (mapv (fn [t] [(:n t) (:seon.turn/tokens t) (:seon.turn/cache-hit-tokens t) (:seon.turn/cache-miss-tokens t) (:seon.turn/finish t) (:seon.turn/errors t)]) ts)
            :usd (reduce + 0 (keep :usd ts))
            :tokens (reduce + 0 (keep :seon.turn/tokens ts))
            :hit (reduce + 0 (keep :seon.turn/cache-hit-tokens ts)) :miss (reduce + 0 (keep :seon.turn/cache-miss-tokens ts))}))))
