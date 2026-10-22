;; a ~100k-datom main (:db) and 10 branches b0..b9, each with ~100 datoms of its own over 5 commits
(require '[datahike.api :as d] '[seon.db :as pdb])
(def path "/Users/sean/src/seon-jolt/next-global-ids/target/m/big")
(def t0 (System/nanoTime))
(def o (pdb/open! :lmdb path 50000 :db))
(def conn (:conn o))
(println "seeded" (count (d/datoms @conn :eavt)) "datoms in" (/ (- (System/nanoTime) t0) 1e6) "ms")
(doseq [i (range 10) :let [b (keyword (str "b" i))]]
  (d/branch! conn :db b)
  (let [bc (d/connect (assoc (:cfg o) :branch b))]
    (doseq [c (range 5)]
      (d/transact bc {:tx-data (vec (for [k (range 2)]
                                      {:form/id (str "br" i "/c" c "/f" k) :form/src (apply str "(defn g [x] " (repeat 30 "(inc x) ") ")")
                                       :form/ns [:ns/name 'app.m0] :form/order (+ 100 k)}))
                      :tx-meta (pdb/tx-meta)}))
    (d/release bc)))
(pdb/sync! (:cfg o))
(println "branches" (sort (d/branches conn)))
