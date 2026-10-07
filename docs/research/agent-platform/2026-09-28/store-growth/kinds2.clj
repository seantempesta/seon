(require '[clojure.string :as str])
(let [rows (read-string (slurp "tmp/store-growth/keys.edn"))
      ks (remove #(let [k (read-string (first %))] (or (uuid? k) (string? k))) rows)
      pre (fn [s] (-> s (str/replace #"^:" "") (str/split #"[-0-9]") first))
      g (group-by (comp pre first) ks)]
  (doseq [[k v] (sort-by (comp - count val) g)]
    (println k (count v) (reduce + (map second v))))
  (println "string keys:" (count (filter #(string? (read-string (first %))) rows)))
  (println (take 3 (map first (filter #(string? (read-string (first %))) rows)))))
