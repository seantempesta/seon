;; Per run: the names the task story's :similar listed (seon.context/run-state on the run's
;; branch), and how many of the model's evals named one of them in their code or prose.
(require '[datahike.api :as d] '[seon.root :as c] '[seon.task :as task] '[seon.context :as ctx] '[clojure.string :as str])
(defn similar-stats [sys]
  (vec (for [[rid tid b] (d/q '[:find ?r ?t ?b :where [?e :seon.run/id ?r] [?e :seon.run/task ?te] [?te :seon.task/id ?t]
                                 [?e :seon.run/branch ?b]] @(:conn sys))
             :let [db (:db @(c/branch-db sys b))
                   names (mapv #(symbol (first (str/split % #":"))) (:nearest (:similar (ctx/run-state db (task/task db tid)))))
                   evs (->> (d/q '[:find [?e ...] :where [?e :seon.eval/record]] db)
                            (map #(:seon.eval/record (d/pull db [:seon.eval/record] %)))
                            (filter #(and (:call-id %) (= rid (:run %)))))
                   hit (fn [e] (filter #(let [s (str (:code e) "\n" (:prose e))] (or (str/includes? s (str %)) (str/includes? s (str "/" (name %))))) names))]]
         {:run rid :similar names :evals (count evs)
          :naming (count (filter (comp seq hit) evs)) :named (vec (distinct (mapcat hit evs)))})))
