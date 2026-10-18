(load-file "tmp/embeddings/embed.clj")
(def decls (clojure.edn/read-string (slurp "tmp/embeddings/decls.edn")))
(def dim 256)
(defn vecs [f task] (into [] (mapcat (fn [part] (:vectors (embed (mapv f part) task dim)))) (partition-all 256 decls)))
(def sets {"doc(title|text)" (vecs #(str "title: " (:sym %) " | text: " (:text %)) nil)
           "sim(text)" (vecs :text "similarity")
           "sim(name)" (vecs (comp str :sym) "similarity")})
(defn top [vs q k] (->> (map vector (map :sym decls) vs) (map (fn [[s v]] [s (reduce + (map * v q))])) (sort-by (comp - second)) (take k) (mapv first)))
(def typos [["seon.db/transcat!" 'seon.db/transact!] ["seon.db/transact" 'seon.db/transact!] ["seon.error/nearest-name" 'seon.error/nearest-names]
            ["my.program/serch" 'my.program/search] ["seon.db/pul" 'seon.db/pull] ["my.task/upate!" 'my.task/update!]
            ["seon.test/reachng-selection" 'seon.test/reaching-selection] ["seon.cache/lookpu" 'seon.cache/lookup]
            ["my.program/write-branch" 'my.program/write-branch!] ["seon.db/q*" 'seon.db/q]
            ["db/transact!" 'seon.db/transact!] ["update-task!" 'my.task/update!] ["program/search" 'my.program/search]
            ["seon.cache/get-or-compute" 'seon.cache/lookup]])
(def intents [["write facts to the database" 'seon.db/transact!] ["search the program text" 'my.program/search]
              ["pull an entity" 'seon.db/pull] ["change a member of my task" 'my.task/update!]
              ["cache a derived value by its content key" 'seon.cache/lookup]])
(doseq [[set-name vs] sets [task-name task] [["sim" "similarity"] ["code" "code"]]]
  (let [hit (fn [cases k] (count (filter (fn [[w want]] (some #{want} (top vs (first (:vectors (embed [w] task dim))) k))) cases)))]
    (println (format "%-16s q=%-5s typos top1 %2d top8 %2d /%d  intents top1 %d top8 %d /%d" set-name task-name
                     (hit typos 1) (hit typos 8) (count typos) (hit intents 1) (hit intents 8) (count intents)))))
(shutdown-agents) (System/exit 0)
