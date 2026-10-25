;; production's shape: what the plan cites left out of each ranking and of the truth (the story shows it)
(defn run3b [c]
  (let [db (agent/db) idf (idf-of db)
        truth (clojure.edn/read-string (slurp (str dir "/truth.edn")))
        rows (vec (for [t truth
                        :let [cited (set (map str (filter qualified-symbol? (keep :seon.ref/name (ref/references db (:plan t))))))
                              want (set (remove cited (filter #(get-in c [:by-name [:fns %]]) (:fns t))))]
                        :when (seq want)
                        :let [qv (first (embed [(str vector/query-prompt (:plan t))]))
                              e (remove cited (:fns (by-embedding db c qv (:task t))))
                              g (update-vals (graph-fns db c (:plan t) idf) #(vec (remove cited %)))
                              r (fn [xs n] (/ (count (filter want (take n xs))) (double (count want))))]]
                    {:task (:task t) :want (count want)
                     :top5 {:count (vec (take 5 (:count g))) :idf (vec (take 5 (:idf g)))}
                     :scores (into {} (for [[m xs] {:graph (:count g) :graph-idf (:idf g) :fused (rrf (:count g) e) :fused-idf (rrf (:idf g) e)}
                                            n [5 10]]
                                        [[m n] (r xs n)]))}))
        mean (fn [k] (/ (reduce + (map #(get-in % [:scores k]) rows)) (count rows)))
        wins (fn [a b] (frequencies (map #(compare (get-in % [:scores b]) (get-in % [:scores a])) rows)))]
    (spit (str dir "/results3b.edn") (pr-str rows))
    {:tasks (count rows)
     :means (into (sorted-map) (for [k (keys (:scores (first rows)))] [k (mean k)]))
     :fused5-idf-vs-count (wins [:fused 5] [:fused-idf 5])
     :graph5-idf-vs-count (wins [:graph 5] [:graph-idf 5])
     :top5-plumbing {:count (frequencies (mapcat #(get-in % [:top5 :count]) rows)) }}))
