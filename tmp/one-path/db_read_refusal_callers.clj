(require '[clojure.edn :as edn] '[clojure.string :as str])
(let [a (:analysis (edn/read-string (slurp (first *command-line-args*))))
      defs (filter #(and (:row %) (:end-row %)) (:var-definitions a))
      by-file (group-by :filename (filter :row (:var-usages a)))
      kws (group-by :filename (filter :row (:keywords a)))
      texts (memoize (fn [f] (vec (str/split-lines (slurp f)))))
      hits (for [d defs
                 :let [in (fn [x] (<= (:row d) (:row x) (:end-row d)))
                       us (filter in (by-file (:filename d)))
                       db (filter #(and (= 'seon.db (:to %)) ('#{q pull pull-many} (:name %))) us)
                       body (str/join "\n" (subvec (texts (:filename d)) (dec (:row d)) (:end-row d)))
                       guarded? (re-find #"read-refusal\?|:seon\.error/at|invalid-read|\(map\? |error-result|sequential\?|seq\? " body)]
                 :when (and (seq db) (not guarded?))]
             [(str/replace (:filename d) "/Users/sean/src/seon/" "") (symbol (str (:ns d)) (str (:name d)))])]
  (prn (into (sorted-map) (update-vals (group-by first hits) #(vec (sort (map second %)))))))
