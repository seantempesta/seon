;; Ancestry: parent links derived from forms (alias and :and/:merge arms that
;; are registry references, exactly the edges seon.schema.internal/extends-schema?
;; follows, plus :merge), the nearest declared pair by breadth-first depth, and
;; its agreement with the projection's own lookup (entity-properties).
(let [ms (fn [t0] (/ (Math/round (/ (- (System/nanoTime) t0) 1e4)) 100.0))
      db @(seon.cluster.boot/connection "default")
      projection (seon.schema/projection-from-database db)
      registry (:seon.schema.projection/registry projection)
      forms (:seon.schema.projection/forms projection)
      outputs [:seon.render/ai :seon.render/html]
      strip (fn [form] (if (and (vector? form) (map? (second form))) (into [(first form)] (drop 2 form)) form))
      parents-of (fn parents-of [form]
                   (cond
                     (qualified-keyword? form) #{form}
                     (and (vector? form) (#{:and :merge} (first form)))
                     (into #{} (mapcat parents-of) (rest (strip form)))
                     :else #{}))
      t0 (System/nanoTime)
      parents (into {} (keep (fn [[k f]] (when (keyword? k) (let [p (parents-of f)] (when (seq p) [k p]))))) forms)
      parents-ms (ms t0)
      ;; A schema's OWN pair: properties of its root, else of the map arms of its own :and.
      own-pair (fn [k]
                 (let [f (get forms k)
                       candidates (cond (and (vector? f) (map? (second f))) [(second f)] :else [])
                       arms ((fn arms [f] (when (and (vector? f) (#{:and :merge} (first f)))
                                            (mapcat #(cond (and (vector? %) (= :map (first %)) (map? (second %))) [(second %)]
                                                           (vector? %) (arms %) :else []) (rest (strip f))))) f)]
                   (not-empty (select-keys (apply merge (concat candidates arms)) outputs))))
      own (into {} (keep (fn [k] (when-let [p (own-pair k)] [k p]))) (filter keyword? (keys forms)))
      nearest-face (fn [k face]
                     (loop [frontier [k] seen #{} depth 0]
                       (when (seq frontier)
                         (if-let [hit (some #(when-let [f (get (own %) face)] [% f]) (sort-by str frontier))]
                           {:schema (first hit) :fn (second hit) :depth depth}
                           (let [seen (into seen frontier)]
                             (recur (into [] (comp (mapcat #(get parents % #{})) (remove seen) (distinct)) frontier)
                                    seen (inc depth)))))))
      nearest (fn [k] (let [faces (into {} (keep (fn [o] (when-let [h (nearest-face k o)] [o h]))) outputs)]
                        (when (seq faces) {:pair (update-vals faces :fn) :depth (apply max (map :depth (vals faces)))})))
      keys* (filterv keyword? (keys forms))
      t1 (System/nanoTime)
      walked (into {} (map (fn [k] [k (nearest k)])) keys*)
      walk-ms (ms t1)
      lookup (fn [k] (when-let [s (malli.registry/schema registry k)]
                       (not-empty (select-keys (or (seon.schema.internal/entity-properties s) (malli.core/properties s)) outputs))))
      t2 (System/nanoTime)
      looked (into {} (map (fn [k] [k (lookup k)])) keys*)
      lookup-ms (ms t2)
      disagree (for [k keys* :let [w (:pair (walked k)) l (looked k)] :when (not= w l)] {:k k :walk (walked k) :projection l})]
  {:schemas (count keys*) :with-parents (count parents) :parent-edges (reduce + (map count (vals parents))) :parents-ms parents-ms
   :own-pairs (count own)
   :resolved-by-walk (count (filter (comp :pair walked) keys*))
   :by-depth (frequencies (keep (comp :depth walked) keys*))
   :walk-all-ms walk-ms :projection-all-ms lookup-ms
   :resolved-by-projection (count (filter looked keys*))
   :disagreements (count disagree) :disagree-sample (take 6 disagree)})
