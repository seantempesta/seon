(let [rows (read-string (slurp "tmp/store-growth/keys.edn"))
      nodes (filter #(uuid? (read-string (first %))) rows)
      h (group-by #(quot (nth % 2) 600000) nodes)] ; 10 min buckets
  (doseq [[b v] (sort h)]
    (println (java.util.Date. (* b 600000)) (count v) (reduce + (map second v))))
  (let [sz (sort (map second nodes)) n (count sz)]
    (println "size p10 p50 p90 p99" (nth sz (quot n 10)) (nth sz (quot n 2)) (nth sz (quot (* n 9) 10)) (nth sz (quot (* n 99) 100)))
    (println "files <2KB" (count (filter #(< % 2048) sz)) "<4KB" (count (filter #(< % 4096) sz)))))
