;; mutation: changes with its identical? skip removed, under changes' own bound
(defn lab-changes-noskip [c0 c1]
  (let [entity? (fn [x] (and (map? x) (contains? x :db/id)))
        members? (fn [x] (and (map? x) (not (entity? x)) (every? entity? (vals x))))
        walk (fn walk [acc p a b]
               (cond (nil? a) (if (nil? b) acc (conj acc [:add p b]))
                     (nil? b) (conj acc [:remove p nil])
                     (or (and (entity? a) (entity? b) (= (:db/id a) (:db/id b))) (and (members? a) (members? b)))
                     (reduce (fn [acc k] (walk acc (conj p k) (get a k) (get b k))) acc (distinct (concat (keys a) (keys b))))
                     (= a b) acc
                     :else (conj acc [:replace p b])))]
    (vec (sort-by (fn [[_ p]] (clojure.string/join "\u0001" (map str p))) (walk [] [] (:tree c0 c0) (:tree c1 c1))))))
(lab-bound! 'root.repl/lab-changes-noskip lab-changes-bound2)
