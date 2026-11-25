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
"Packs fast-tier namespaces into at most `max-workers` (default 4) LPT-balanced worker bins so repo-wide overlays compile O(max-workers) times instead of O(namespaces) times."
{:malli/schema [:function
                [:=> [:cat :any :any :any :any [:maybe :map] [:vector :map] [:vector :map] :int :int] [:vector :map]]
                [:=> [:cat :any :any :any :any [:maybe :map] [:vector :map] [:vector :map] :int :int [:maybe [:int {:min 1 :max 64}]]] [:vector :map]]]
 :cost/ticks 5000000}
([parent-id tier-tag cfg branch overlay uncached-tests uncached-gens samples seed]
 (build-namespace-subs parent-id tier-tag cfg branch overlay uncached-tests uncached-gens samples seed 4))
([parent-id tier-tag cfg branch overlay uncached-tests uncached-gens samples seed max-workers]
 (let [ov (sub-overlay overlay)
       tests-by-ns (group-by :ns uncached-tests)
       gens-by-ns (group-by :ns uncached-gens)
       all-ns (vec (sort (distinct (concat (keys tests-by-ns) (keys gens-by-ns)))))
       ns-items (mapv (fn [nsym]
                        (let [ts (get tests-by-ns nsym [])
                              gs (get gens-by-ns nsym [])
                              est (+ (reduce + 0.0 (map #(double (or (:est-ms %) 10.0)) ts))
                                     (* 2.0 (count gs)))]
                          {:ns nsym :var nsym :est-ms (max 1.0 est) :tests ts :gens gs}))
                      all-ns)
       bins (pack-lpt-bins ns-items (max 1 (long (or max-workers 4))))]
   (vec (map-indexed
         (fn [idx bin-nss]
           {:sub-id (str parent-id "-" tier-tag "-" idx)
            :parent-id parent-id :cfg cfg :branch branch :overlay ov
            :test-items (vec (mapcat (fn [b] (map #(dissoc % :ns :est-ms) (:tests b))) bin-nss))
            :gen-items (vec (mapcat (fn [b] (map #(dissoc % :ns :est-ms) (:gens b))) bin-nss))
            :samples samples :seed seed})
         bins)))))

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