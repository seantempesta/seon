;; U's upkeep: inserting one commit's Δ datoms into U's three current indexes (plain set insertion), warm, N = 115k
(require '[datahike.api :as d] '[seon.db :as pdb] '[datahike.index.interface :as di] '[datahike.datom :as dd])
(def path "/Users/sean/src/seon-jolt/next-global-ids/target/m/big")
(def cfg (assoc (pdb/store-cfg :lmdb path) :store-cache-size 200000))
(def conn (d/connect cfg))
(def db (pdb/head conn :hist))
(pdb/preload! db)
(defn ms [t0] (/ (- (System/nanoTime) t0) 1e6))
(defn delta [k n] (vec (for [i (range n)] (dd/datom (+ 5000000 (* 1000 k) i) :form/src (str "(defn g" i " [x] x)") (+ 536900000 k) true))))
(doseq [n [10 100 1000]]
  (let [ds (vec (for [k (range 20)] (delta (+ k (* 100 n)) n)))
        t0 (System/nanoTime)
        idx (reduce (fn [[e a v] dl]
                      [(reduce #(di/-insert %1 %2 :eavt 0) e dl)
                       (reduce #(di/-insert %1 %2 :aevt 0) a dl)
                       (reduce #(di/-insert %1 %2 :avet 0) v dl)])
                    [(:eavt db) (:aevt db) (:avet db)] ds)
        t (ms t0)]
    (println "Δ" n "per commit, 20 commits:" (format "%.3f ms/commit, %.2f us/datom (3 indexes)" (/ t 20) (/ (* 1000 t) 20 n))
             "eavt size" (count (first idx)))))
;; the same as one transient batch per commit (what a transaction does)
(doseq [n [10 100 1000]]
  (let [ds (vec (for [k (range 20)] (delta (+ 7 k (* 100 n)) n)))
        ins (fn [idx t dl] (di/-persistent! (reduce #(di/-insert %1 %2 t 0) (di/-transient idx) dl)))
        t0 (System/nanoTime)
        idx (reduce (fn [[e a v] dl] [(ins e :eavt dl) (ins a :aevt dl) (ins v :avet dl)])
                    [(:eavt db) (:aevt db) (:avet db)] ds)
        t (ms t0)]
    (println "transient Δ" n ":" (format "%.3f ms/commit, %.2f us/datom (3 indexes)" (/ t 20) (/ (* 1000 t) 20 n)))))
;; for scale: the same 100 new datoms as one ordinary transaction (d/db-with, in memory)
(let [txs (vec (for [k (range 20)] (vec (for [i (range 100)] {:form/id (str "u" k "/" i) :form/src (str "(defn g" i " [x] x)")}))))
      t0 (System/nanoTime)]
  (reduce d/db-with db txs)
  (println "d/db-with of 100 datoms" (format "%.3f ms/commit" (/ (ms t0) 20))))
