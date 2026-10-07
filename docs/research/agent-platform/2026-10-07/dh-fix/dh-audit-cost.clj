(require '[bench] '[datahike.api :as d] '[datahike.query :as q])
(require '[clojure.edn :as edn])
(def input-path (str (System/getProperty "dhfix.scratch") "/cost-inputs.edn"))
(def ins (if (.exists (java.io.File. input-path))
           (edn/read-string (slurp input-path))
           (let [c (bench/fresh-conn)
                 v (try (bench/inputs @c) (finally (d/release c)))]
             (spit input-path (pr-str v)) v)))
(spit (first *command-line-args*)
 (pr-str (let [c (bench/fresh-conn)]
   (try (binding [q/*query-result-cache?* false]
    (into {} (for [[label query] bench/shapes]
               [label (let [v (apply d/q query @c (ins label))]
                        (if (coll? v) (set v) v))])))
    (finally (d/release c))))))
(prn :inputs ins)
(doseq [round (range 1) label [:receipts :analysis :next-ordinal :latest-turn :affected]]
 (prn :round round (bench/run-shape ins label)) (flush))
(shutdown-agents)
