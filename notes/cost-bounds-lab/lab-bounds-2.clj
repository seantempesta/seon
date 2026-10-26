;; calibration: each term of changes' walk alone, measured, so a bound's constants are measured
;; physics of this function's own steps (the cost proofs' :per-n), not the unit table's
(defn lab-m [f & args] (select-keys (seon.cost/measure f (vec args)) [:ticks :bytes :calls]))
(defn lab-calib-changes []
  (let [f seon.ctx/changes
        t (lab-tree {:evals 0 :runs 0 :cites 0 :len 10})
        fixed (lab-m f t t)
        ;; keys: a collection of n identical members re-made as a new map: n keys visited, no change
        keys-at (fn [n] (let [t (lab-tree {:evals n :runs 0 :cites 0 :len 10})] (lab-m f t (update t :evals #(into (sorted-map-by @#'seon.ctx/newest-first) %)))))
        ;; eq: one plain value of n chars compared and equal (a new string, same text)
        eq-at (fn [n] (let [t (assoc t :x (lab-text 1 n))] (lab-m f t (assoc t :x (apply str (seq (:x t)))))))
        ;; changes: n evals each replaced
        k-at (fn [n] (let [t (lab-tree {:evals n :runs 0 :cites 0 :len 10})]
                       (lab-m f t (reduce (fn [t e] (assoc-in t [:evals e :seon.eval/record :line] "x")) t (keys (:evals t))))))
        per (fn [g a b] (let [x (g a) y (g b)] (into {} (for [d [:ticks :bytes :calls]] [d (/ (- (get y d 0.0) (get x d 0.0)) (- b a))]))))]
    {:fixed fixed :per-key (per keys-at 100 1000) :per-eq (per eq-at 1000 10000) :per-change (per k-at 100 1000)}))

(def lab-changes-c (lab-calib-changes))
(defn lab-changes-bound2
  "changes' work from its walk's sketch: fixed, plus each key visited, each element compared by =,
  each change, at the calibrated (measured) cost of that step."
  [c0 c1]
  (let [{:keys [fixed per-key per-eq per-change]} lab-changes-c
        w (lab-walk-of (if (contains? c0 :tree) (:tree c0) c0) (if (contains? c1 :tree) (:tree c1) c1))]
    (into {} (for [d [:ticks :bytes]]
               [d (+ (get fixed d) (* (:keys w) (get per-key d)) (* (:eq w) (get per-eq d)) (* (:k w) (get per-change d)))]))))
(lab-bound! 'seon.ctx/changes lab-changes-bound2)
