;; (2) composition: each piece's bound linear in the session's measures (evals, cited vars, runs),
;; its terms calibrated on four shapes (base, +evals, +cites, +runs); the caller's bound (world, a
;; later turn) is the sum of its callees' bounds on the values it passes them; predicted on the rest
(def lab-by-shape (into {} (map (juxt :shape identity)) lab-grid))
(defn lab-fit [k]
  (let [m (fn [s d] (get-in lab-by-shape [s k d]))
        b {:n 10 :r 1 :c 5}]
    (into {} (for [d [:ticks :bytes :calls]]
               [d {:base (m b d) :per-eval (/ (- (m {:n 40 :r 1 :c 5} d) (m b d)) 30.0)
                   :per-cite (/ (- (m {:n 10 :r 1 :c 20} d) (m b d)) 15.0) :per-run (/ (- (m {:n 10 :r 3 :c 5} d) (m b d)) 2.0)}]))))
(def lab-pieces [:build :advance :page0 :page1])
(def lab-fits (into {} (for [k (conj lab-pieces :patch :changes)] [k (lab-fit k)])))
(defn lab-piece-bound [k {:keys [n r c]}]
  (into {} (for [[d {:keys [base per-eval per-cite per-run]}] (get lab-fits k)]
             [d (+ base (* per-eval (- n 10)) (* per-cite (- c 5)) (* per-run (- r 1)))])))
(defn lab-world-bound "world's bound at a later turn: its callees' bounds summed." [s]
  (apply merge-with + (map #(lab-piece-bound % s) lab-pieces)))
(defn lab-predict []
  (vec (for [s [{:n 40 :r 1 :c 20} {:n 100 :r 5 :c 15} {:n 200 :r 3 :c 40}]
             :let [g (lab-by-shape s) measured (apply merge-with + (map #(select-keys (get g %) [:ticks :bytes :calls]) lab-pieces))
                   bound (lab-world-bound s)]]
         {:shape s
          :world (into {} (for [d [:ticks :bytes :calls]] [d [(long (get bound d)) (long (get measured d)) (/ (Math/round (* 100.0 (/ (get measured d) (get bound d)))) 100.0)]]))
          :pieces (into {} (for [k (conj lab-pieces :patch :changes)] [k (/ (Math/round (* 100.0 (/ (:bytes (get g k)) (:bytes (lab-piece-bound k s))))) 100.0)]))})))
