;; Per run of the measured tasks (step 6's notes/step0/stats.clj, from 9404092be): outcome, turns,
;; per model turn its tokens, cache hits and misses, and cost; and the model's evals that read the
;; wiki: (my.task/related) or (my.agent/related ...), a seon.docs/view or help read, or a docs address.
(require '[datahike.api :as d] '[seon.model :as m] '[seon.root :as c] '[clojure.string :as str])
(defn run-stats [sys]
  (let [db @(:conn sys)
        tasks (clojure.edn/read-string (slurp "/private/tmp/claude-501/-Users-sean-src-seon-next/89e4bd39-d696-48b6-9c09-34666b1c7927/scratchpad/related-ab/tasks.edn"))]
    (vec (for [tid tasks
               [rid status outcome turns branch model] (d/q '[:find ?r ?s ?o ?n ?b ?m :in $ ?t :where [?te :seon.task/id ?t] [?e :seon.run/task ?te] [?e :seon.run/id ?r]
                                                              [?e :seon.run/status ?s] [(get-else $ ?e :seon.run/outcome :none) ?o] [(get-else $ ?e :seon.run/turns -1) ?n]
                                                              [(get-else $ ?e :seon.run/branch :none) ?b] [(get-else $ ?e :seon.run/model "?") ?m]] db tid)
               :let [bdb (when (not= :none branch) (:db @(c/branch-db sys branch)))
                     ts (when bdb
                          (->> (d/q '[:find ?n ?e :in $ ?r :where [?ru :seon.run/id ?r] [?e :seon.turn/run ?ru] [?e :seon.turn/origin :model] [?e :seon.turn/n ?n]] bdb rid)
                               sort (mapv (fn [[n e]] (let [t (d/pull bdb [:seon.turn/tokens :seon.turn/cache-hit-tokens :seon.turn/cache-miss-tokens :seon.turn/finish :seon.turn/forms :seon.turn/errors] e)]
                                                        (assoc t :n n :usd (m/cost (if (= "?" model) "deepseek-flash" model) t)))))))
                     codes (when bdb (vec (keep #(when (:call-id %) (:code %)) (d/q '[:find [?r ...] :where [_ :seon.eval/record ?r]] bdb))))
                     reads (filterv #(or (str/includes? % "/related") (str/includes? % "seon.docs/view") (str/includes? % "my.agent/help") (re-find #"\.md#" %)) codes)
                     shown (when bdb (d/q '[:find (count ?e) . :where [?e :seon.eval/by "screen"] [?e :seon.eval/record ?r] [(get ?r :code) ?c] [(clojure.string/includes? ?c "related")]] bdb))]]
           {:task tid :run rid :status status :outcome outcome :turns turns
            :per-turn (mapv (fn [t] [(:n t) (:seon.turn/tokens t) (:seon.turn/cache-hit-tokens t) (:seon.turn/cache-miss-tokens t) (:seon.turn/finish t) (:seon.turn/errors t)]) ts)
            :usd (reduce + 0 (keep :usd ts))
            :tokens (reduce + 0 (keep :seon.turn/tokens ts))
            :hit (reduce + 0 (keep :seon.turn/cache-hit-tokens ts)) :miss (reduce + 0 (keep :seon.turn/cache-miss-tokens ts))
            :model-evals (count codes) :wiki-reads reads :screen-related (or shown 0)}))))
