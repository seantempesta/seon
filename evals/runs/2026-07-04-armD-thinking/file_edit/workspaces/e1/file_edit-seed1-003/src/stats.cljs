(ns stats)

(defn mean-of
  "Returns the arithmetic mean (average) of a vector of numbers."
  [xs]
  (let [n (count xs)]
    (double (/ (reduce + xs) n))))
