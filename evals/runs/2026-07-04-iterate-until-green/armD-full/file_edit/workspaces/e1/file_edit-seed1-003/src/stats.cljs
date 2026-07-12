(defn mean-of
  "Mean of a vector of numbers."
  [v]
  (/ (reduce + v) (count v)))
