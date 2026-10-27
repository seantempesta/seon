;; sentence fusion (root, 2026-10-25): meaning ranked by the whole plan's vector against each
;; sentence's ranking fused by rrf; alone and fused with the rarity-weighted graph (related's), what
;; the plan cites left out; recall at 5 and 10 of functions and sections over the merged tasks
(defn sentences [plan] (vec (remove #(< (count %) 12) (map str/trim (str/split plan (re-pattern "(?<=[.!?])\\s+|\\n+"))))))
(defn embed-all [ts] (vec (mapcat embed (partition-all 32 (map #(str vector/query-prompt %) ts)))))
(def rows3d (atom []))
(defn run3d [c from to]
  (let [db (agent/db) w (weigh db 16)
        truth (subvec (clojure.edn/read-string (slurp (str dir "/truth.edn"))) from (min to 31))
        rows (vec (for [t truth
                        :let [cited (set (map str (filter qualified-symbol? (keep :seon.ref/name (ref/references db (:plan t))))))
                              cited-secs (set (for [x (ref/references db (:plan t)) :let [a (:seon.ref/address x)] :when a] a))
                              want {:fns (set (remove cited (filter #(get-in c [:by-name [:fns %]]) (:fns t))))
                                    :sections (set (remove cited-secs (filter #(get-in c [:by-name [:sections %]]) (:sections t))))}
                              ss (sentences (:plan t))
                              [qv & svs] (embed-all (cons (:plan t) ss))
                              whole (by-embedding db c qv (:task t))
                              per (mapv #(by-embedding db c % (:task t)) svs)
                              sent {:fns (vector/rrf (mapv :fns per)) :sections (vector/rrf (mapv :sections per))}
                              g (graph-fns db c (:plan t) w)
                              gs (:sections (by-graph db c (:plan t) (:task t)))
                              clean {:fns #(vec (remove (fn [x] (or (cited x) (= x "seon.contract/defn"))) %)) :sections #(vec (remove cited-secs %))}
                              methods {:whole whole :sentences sent
                                       :whole+graph {:fns (vector/rrf [((clean :fns) (:idf g)) ((clean :fns) (:fns whole))]) :sections (vector/rrf [((clean :sections) gs) ((clean :sections) (:sections whole))])}
                                       :sentences+graph {:fns (vector/rrf [((clean :fns) (:idf g)) ((clean :fns) (:fns sent))]) :sections (vector/rrf [((clean :sections) gs) ((clean :sections) (:sections sent))])}}]]
                    {:task (:task t) :n (count ss)
                     :scores (into {} (for [[m r] methods k [:fns :sections] :when (seq (want k)) n [5 10]]
                                        [[m k n] (/ (count (filter (want k) (take n ((clean k) (get r k))))) (double (count (want k))))]))}))
        _ (swap! rows3d into rows)
        rows @rows3d
        ks (distinct (mapcat (comp keys :scores) rows))]
    (spit (str dir "/results3d.edn") (pr-str rows))
    {:tasks (count rows) :mean-sentences (/ (reduce + (map :n rows)) (double (count rows)))
     :means (into (sorted-map) (for [k ks :let [xs (keep #(get-in % [:scores k]) rows)]] [k [(count xs) (/ (reduce + xs) (count xs))]]))
     :wins-fns5 (frequencies (for [r rows :let [a (get-in r [:scores [:whole+graph :fns 5]]) b (get-in r [:scores [:sentences+graph :fns 5]])] :when a] (compare b a)))
     :wins-secs5 (frequencies (for [r rows :let [a (get-in r [:scores [:whole+graph :sections 5]]) b (get-in r [:scores [:sentences+graph :sections 5]])] :when a] (compare b a)))}))
