(let [rows (read-string (slurp "tmp/store-growth/keys.edn"))
      kind (fn [s] (let [k (read-string s)]
                     (cond (uuid? k) :uuid-node (keyword? k) k
                           (vector? k) (first k) :else (type k))))
      g (group-by (comp kind first) rows)]
  (println "total" (count rows) (reduce + (map second rows)))
  (doseq [[k v] (sort-by (comp - count val) g)]
    (println k (count v) (reduce + (map second v)) (long (/ (reduce + (map second v)) (count v))))))
