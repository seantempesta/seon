;; Per run: the context build's wall ms per turn (:seon.turn/split :context-ms), mean and max.
(require '[datahike.api :as d] '[seon.root :as c])
(defn splits [sys]
  (vec (for [[rid b] (d/q '[:find ?r ?b :where [?e :seon.run/id ?r] [?e :seon.run/branch ?b]] @(:conn sys))
             :let [db (:db @(c/branch-db sys b))
                   ms (sort (map second (d/q '[:find ?n ?ms :in $ ?r :where [?ru :seon.run/id ?r] [?e :seon.turn/run ?ru] [?e :seon.turn/origin :model] [?e :seon.turn/n ?n] [?e :seon.turn/split ?s] [(get ?s :context-ms) ?ms]] db rid)))]
             :when (seq ms)]
         [rid (count ms) (long (/ (reduce + ms) (count ms))) (last ms)])))
