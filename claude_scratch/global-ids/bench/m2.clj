;; per-node read cost on Jolt, and whether branch dbs share nodes through the node cache
(require '[datahike.api :as d] '[seon.db :as pdb])
(def path "/Users/sean/src/seon-jolt/next-global-ids/target/m/big")
(def cfg (cond-> (pdb/store-cfg :lmdb path) (second *command-line-args*) (assoc :store-cache-size (parse-long (second *command-line-args*)))))
(def conn (d/connect cfg))
(def storage (-> @conn :store :storage))
(defn st [] @(:stats storage))
(defn ms [t0] (/ (- (System/nanoTime) t0) 1e6))
(defn scan [label db]
  (let [s0 (st) t0 (System/nanoTime) n (count (d/datoms db :eavt)) t (ms t0) s1 (st)
        reads (- (:reads s1) (:reads s0))]
    (println label "datoms" n "ms" (format "%.1f" t) "store-reads" reads "accessed" (- (:accessed s1) (:accessed s0))
             (if (pos? reads) (format "us/read %.1f" (/ (* 1000 t) reads)) ""))))
(println "cache" (select-keys cfg [:store-cache-size]) "mode" (first *command-line-args*))
(case (first *command-line-args*)
  "share"
  (do (scan "b0 cold      " (pdb/head conn :b0))
      (scan "b0 warm      " (pdb/head conn :b0))
      (scan "b1 same-conn " (pdb/head conn :b1))
      (let [c2 (d/connect (assoc cfg :branch :b2))]
        (scan "b2 own-conn  " @c2))
      (scan ":db          " (pdb/head conn :db)))
  "seek"
  (let [db (pdb/head conn :b3)
        es (vec (take 200 (shuffle (map :e (d/datoms db :aevt :form/id)))))
        _ (println "aevt :form/id leaves read" (:reads (st)))
        s0 (st) t0 (System/nanoTime)]
    (doseq [e es] (count (d/datoms db :eavt e)))
    (let [t (ms t0) r (- (:reads (st)) (:reads s0))]
      (println "200 cold eavt seeks ms" (format "%.1f" t) "reads" r (format "us/seek %.1f us/read %.1f" (/ (* 1000 t) 200) (/ (* 1000 t) (max 1 r)))))
    (let [t0 (System/nanoTime)] (doseq [e es] (count (d/datoms db :eavt e)))
      (println "200 warm eavt seeks ms" (format "%.2f" (ms t0)) (format "us/seek %.1f" (/ (* 1000 (ms t0)) 200))))))
