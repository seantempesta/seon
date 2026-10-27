(require '[datahike.api :as d] '[seon.db :as pdb] '[datahike.versioning :as v])
(def path (first *command-line-args*))
(def cfg (pdb/store-cfg :lmdb path))
(def conn (d/connect cfg))
(def store (:store @conn))
(def bs (sort (d/branches conn)))
(defn ms [t0] (/ (- (System/nanoTime) t0) 1e6))
(defn hist [db] (set (d/datoms (d/history db) :eavt)))
(doseq [m (filter #(nil? (namespace %)) bs)]
  (let [mdb (pdb/head conn m)]
    (println "main" m "datoms" (count (d/datoms mdb :eavt)) "history" (count (hist mdb)) "max-eid" (:max-eid mdb) "max-tx" (:max-tx mdb))))
(defn own-and-fork [b]
  (loop [todo [(v/commit-id (pdb/head conn b))] own #{} outside #{}]
    (if-let [c (peek todo)]
      (let [r (v/commit-graph-record store c)]
        (cond (own c) (recur (pop todo) own outside)
              (= b (:datahike/branch r)) (recur (into (pop todo) (:datahike/parents r)) (conj own c) outside)
              :else (recur (pop todo) own (conj outside c))))
      [own (first (sort-by #(:datahike/generation (v/commit-graph-record store %)) outside))])))
(println "branch | own-commits | fork-datoms(hist) | head-datoms(hist) | delta asserted | delta retracted | value-bytes(pr-str) | eids-new | diff-ms(current eavt)")
(def rows (atom []))
(doseq [b bs :when (namespace b)]
  (let [head (pdb/head conn b) [own fork] (own-and-fork b)
        fdb (d/commit-as-db conn fork)
        hh (hist head) hf (hist fdb)
        delta (remove hf hh)
        t1 (System/nanoTime) [ob oh] (d/datoms-diff fdb head :eavt []) t-diff (ms t1)]
    (swap! rows conj (count delta))
    (println b "|" (count own) "|" (count hf) "|" (count hh) "|" (count (filter :added delta)) "|" (count (remove :added delta))
             "|" (reduce + (map #(count (pr-str (:v %))) delta)) "|" (- (:max-eid head) (:max-eid fdb)) "|" (format "%.2f (only-fork %d only-b %d)" t-diff (count ob) (count oh)))))
(println "per-branch delta datoms:" (sort @rows))
