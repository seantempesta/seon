;; Phase 1 quality probe: misspelled names, by query formulation. Brute-force cosine over all declarations.
(load-file "tmp/embeddings/embed.clj")
(def decls (clojure.edn/read-string (slurp "tmp/embeddings/decls.edn")))
(def dim 256)
(def V (into [] (mapcat (fn [part] (:vectors (embed (mapv #(str "title: " (:sym %) " | text: " (:text %)) part) nil dim))))
             (partition-all 256 decls)))
(def names-only (into [] (mapcat (fn [part] (:vectors (embed (mapv #(str (:sym %)) part) "similarity" dim))))
             (partition-all 256 decls)))
(defn top [vs q k] (->> (map vector (map :sym decls) vs) (map (fn [[s v]] [s (reduce + (map * v q))])) (sort-by (comp - second)) (take k) (mapv first)))
(def cases [["seon.db/transcat!" 'seon.db/transact!] ["seon.db/transact" 'seon.db/transact!] ["seon.error/nearest-name" 'seon.error/nearest-names]
            ["my.program/serch" 'my.program/search] ["seon.db/pul" 'seon.db/pull] ["my.task/upate!" 'my.task/update!]
            ["seon.test/reachng-selection" 'seon.test/reaching-selection] ["seon.cache/lookpu" 'seon.cache/lookup]
            ["my.program/write-branch" 'my.program/write-branch!] ["seon.db/q*" 'seon.db/q]])
(doseq [[label f] [["code-query vs doc" (fn [w] (first (:vectors (embed [w] "code" dim))))]
                   ["doc-title vs doc" (fn [w] (first (:vectors (embed [(str "title: " w " | text: " w)] nil dim))))]]]
  (println label (count (filter (fn [[w want]] (some #{want} (top V (f w) 8))) cases)) "/" (count cases)))
(let [f (fn [w] (first (:vectors (embed [w] "similarity" dim))))]
  (println "name-sim vs name-sim" (count (filter (fn [[w want]] (some #{want} (top names-only (f w) 8))) cases)) "/" (count cases))
  (doseq [[w want] cases] (println w (top names-only (f w) 3))))
(shutdown-agents) (System/exit 0)
