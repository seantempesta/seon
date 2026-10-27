;; weights compared: log IDF (search3) against 1/(1+df), df counted to a cap (bounded-count: no
;; form count N, at most cap datoms read a candidate); fused R@5 and the plumbing lines
(defn weigh [db cap] (memoize (fn [v] (/ 1.0 (inc (bounded-count cap (d/datoms db :avet :form/refs (symbol v))))))))
(defn run3c [c caps]
  (let [db (agent/db) idf (idf-of db)
        truth (clojure.edn/read-string (slurp (str dir "/truth.edn")))
        plumb #{"seon.contract/checked" "seon.contract/on?" "seon.contract/error?" "seon.config/value" "seon.agent/db" "seon.db/eid"}
        ws (into {:idf idf} (for [cap caps] [cap (weigh db cap)]))
        rows (vec (for [t truth
                        :let [cited (set (map str (filter qualified-symbol? (keep :seon.ref/name (ref/references db (:plan t))))))
                              want (set (remove cited (filter #(get-in c [:by-name [:fns %]]) (:fns t))))
                              qv (first (embed [(str vector/query-prompt (:plan t))]))
                              e (remove cited (:fns (by-embedding db c qv (:task t))))
                              fused (into {} (for [[k w] ws] [k (take 5 (rrf (vec (remove #(or (cited %) (= % "seon.contract/defn")) (:idf (graph-fns db c (:plan t) w)))) e))]))]]
                    {:want want :fused fused}))
        r5 (fn [k] (let [xs (filter (comp seq :want) rows)] (/ (reduce + (map #(/ (count (filter (:want %) (get-in % [:fused k]))) (double (count (:want %)))) xs)) (count xs))))]
    (into {} (for [k (keys ws)] [k {:r5 (r5 k) :plumbing (count (filter plumb (mapcat #(get-in % [:fused k]) rows)))}]))))
