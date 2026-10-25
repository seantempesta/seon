;; Experiment 9: short keys generated from the schemas, for every entity schema's units: a reverse
;; ref :ns.thing/_attr is the plural of its thing (:seon.eval/_agent -> :evals), a forward ref is its
;; attribute's name (:seon.run/task -> :task); two units of one schema with one name are a collision
(defn short-key [u] (if (rev? u) (keyword (str (last (str/split (namespace u) #"\.")) "s")) (keyword (name u))))
(def E9 (let [ss (for [[k _] contract/forms :when (:seon.db/attributes (contract/properties k))
                       :let [p (contract/properties k) us (distinct (concat (:seon.render/units p) (:seon.render/parts p)))] :when (seq us)]
                   [k (into {} (map (fn [u] [u (short-key u)])) us)])]
          {:aliases (into {} ss)
           :collisions (into {} (for [[k m] ss :let [c (for [[a n] (frequencies (vals m)) :when (> n 1)] a)] :when (seq c)] [k c]))
           ;; would a short key shadow an attribute the entity itself has? (pull's :as renames on the same map)
           :shadows (into {} (for [[k m] ss :let [own (set (map (comp keyword name first) (drop 2 (get contract/forms k))))
                                                  c (filter own (vals m))] :when (seq c)] [k c]))}))
