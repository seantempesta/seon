(ns seon.test.lpt
  "Extracted child namespace of `seon.test`.")

(defn pack-lpt-bins
  "Packs items into at most max-workers balanced bins via Longest-Processing-Time-first (LPT)
   scheduling weighted by :est-ms (defaulting to 500.0 ms when unknown)."
  {:malli/schema [:=> [:cat [:vector :map] [:int {:min 1 :max 64}]] [:vector [:vector :map]]]
   :cost/ticks 2000000}
  [items max-workers]
  (if (empty? items)
    []
    (let [n-bins (min (long max-workers) (count items))
          wt (fn [it]
               (let [ms (:est-ms it)]
                 (if (and (number? ms) (> (double ms) 0.0) (< (double ms) 1.0e15))
                   (double ms)
                   500.0)))
          ordered (sort-by (fn [it] [(- (wt it)) (str (:var it))]) items)
          bins (reduce
                (fn [bs it]
                  (let [w (wt it)
                        nsym (:ns it)
                        idx (loop [i 1 best 0 bl (:load (nth bs 0)) bs? (contains? (:nss (nth bs 0)) nsym)]
                              (if (>= i n-bins)
                                best
                                (let [b (nth bs i) l (:load b) s? (contains? (:nss b) nsym)]
                                  (if (or (< l bl) (and (== l bl) s? (not bs?)))
                                    (recur (inc i) i l s?)
                                    (recur (inc i) best bl bs?)))))]
                    (update bs idx (fn [b]
                                     (-> b
                                         (update :load + w)
                                         (update :nss #(if nsym (conj % nsym) %))
                                         (update :items conj it))))))
                (vec (repeat n-bins {:load 0.0 :nss #{} :items []}))
                ordered)]
      (mapv (fn [b] (vec (sort-by (fn [x] [(str (:ns x)) (str (:var x))]) (:items b))))
            (filter #(seq (:items %)) bins)))))

(defn sub-overlay
  {:malli/schema [:=> [:cat [:maybe :map]] [:maybe :map]], :cost/ticks 500000}
  [overlay]
  (when overlay
    (select-keys overlay [:upserts :retracted-keys :schema-upserts :retracted-schemas :rule-upserts :retracted-rules])))

(defn build-namespace-subs
  {:malli/schema [:=> [:cat :any :any :any :any [:maybe :map] [:vector :map] [:vector :map] :int :int] [:vector :map]], :cost/ticks 2000000}
  [parent-id tier-tag cfg branch overlay uncached-tests uncached-gens samples seed]
  (let [ov (sub-overlay overlay)
        all-ns (vec (sort (distinct (concat (map :ns uncached-tests) (map :ns uncached-gens)))))]
    (vec (map-indexed
          (fn [idx nsym]
            {:sub-id (str parent-id "-" tier-tag "-" idx)
             :parent-id parent-id :cfg cfg :branch branch :overlay ov
             :test-items (->> uncached-tests (filter #(= nsym (:ns %))) (mapv #(dissoc % :ns :est-ms)))
             :gen-items (->> uncached-gens (filter #(= nsym (:ns %))) (mapv #(dissoc % :ns :est-ms)))
             :samples samples :seed seed})
          all-ns))))

(defn build-lpt-slow-subs
  {:malli/schema [:=> [:cat :any :any :any :any [:maybe :map] [:vector :map] [:maybe [:int {:min 1, :max 64}]] :int :int] [:vector :map]], :cost/ticks 2000000}
  [parent-id tier-tag cfg branch overlay slow-tests max-workers samples seed]
  (let [ov (sub-overlay overlay)
        bins (pack-lpt-bins slow-tests (max 1 (long (or max-workers 4))))]
    (vec (map-indexed
          (fn [idx bin-items]
            {:sub-id (str parent-id "-" tier-tag "-" idx)
             :parent-id parent-id :cfg cfg :branch branch :overlay ov
             :test-items (mapv #(dissoc % :ns :est-ms) bin-items)
             :gen-items [] :samples samples :seed seed})
          bins))))