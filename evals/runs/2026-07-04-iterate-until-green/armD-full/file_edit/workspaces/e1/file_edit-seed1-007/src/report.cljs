(defn column-total
  "Total of the russet column."
  [rows]
  (reduce + 0 (map :russet rows)))
(def default-limit 3)
