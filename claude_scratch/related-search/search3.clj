;; IDF for related's call graph (task seon.context/related-weighs-a-neighbour-by-its-rarity):
;; search.clj's graph ranking of functions with each neighbour weighed by its inverse document
;; frequency, log(N / (1 + forms naming it)), one AVET count of :form/refs a candidate; recall at
;; 5 and 10 of what each of the 32 merged tasks changed, graph alone and fused with meaning.
(defn idf-of [db]
  (let [n (count (d/datoms db :aevt :form/id))]
    (memoize (fn [v] (Math/log (/ n (inc (count (d/datoms db :avet :form/refs (symbol v))))))))))

(defn graph-fns [db c plan idf]
  (let [cites (ref/references db plan)
        names (set (keep :seon.ref/name cites))
        seed-vars (filter #(get-in c [:by-name [:fns (str %)]]) (filter qualified-symbol? names))
        seed-secs (for [x cites :when (and (some-> (:seon.ref/path x) str (str/ends-with? ".md")) (= :view (docs/citation-kind db x)))
                        :let [a (:seon.ref/address (docs/cited-view db x))] :when (and a (get-in c [:by-name [:sections a]]))] a)
        hop (fn [v] (into (set (map str (docs/callers db v)))
                          (map str (d/q '[:find [?r ...] :in $ ?s :where [?e :var/name ?s] [?e :var/form ?f] [?f :form/refs ?r]] db v))))
        more (filter #(get-in c [:by-name [:fns %]]) (concat (mapcat hop seed-vars) (map str (mapcat #(:names (get-in c [:by-name [:sections %]])) seed-secs))))
        seeds (map str seed-vars)
        rank (fn [w] (vec (distinct (concat seeds (map key (sort-by (fn [[x s]] [(- s) x]) (reduce (fn [m x] (update m x (fnil + 0) (w x))) {} more))))))) ]
    {:count (rank (constantly 1.0)) :idf (rank idf)}))

(defn run3 [c]
  (let [db (agent/db) idf (idf-of db)
        truth (clojure.edn/read-string (slurp (str dir "/truth.edn")))
        rows (vec (for [t truth
                        :let [want (set (filter #(get-in c [:by-name [:fns %]]) (:fns t)))]
                        :when (seq want)
                        :let [qv (first (embed [(str vector/query-prompt (:plan t))]))
                              e (:fns (by-embedding db c qv (:task t)))
                              g (graph-fns db c (:plan t) idf)
                              r (fn [xs n] (/ (count (filter want (take n xs))) (double (count want))))]]
                    {:task (:task t)
                     :scores (into {} (for [[m xs] {:embedding e :graph (:count g) :graph-idf (:idf g)
                                                    :fused (rrf (:count g) e) :fused-idf (rrf (:idf g) e)}
                                            n [5 10]]
                                        [[m n] (r xs n)]))}))]
    (spit (str dir "/results3.edn") (pr-str rows))
    (into (sorted-map) (for [k (keys (:scores (first rows)))] [k (/ (reduce + (map #(get-in % [:scores k]) rows)) (count rows))]))))
