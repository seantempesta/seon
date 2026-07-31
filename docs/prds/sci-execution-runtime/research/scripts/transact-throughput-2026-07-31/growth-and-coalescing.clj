;; (a) blobs-per-commit as the database grows; (b) the coalescing curve that
;; produced the old "thousands of tx/s" numbers.
(require '[datahike.api :as d] '[clojure.java.io :as io])
(import '[java.util UUID] '[java.util.concurrent Executors TimeUnit])

(defn quant [samples]
  (let [s (vec (sort samples)) n (count s) at #(nth s (min (dec n) (long (* % n))))]
    {:n n :median-ms (/ (Math/round (/ (at 0.5) 1000.0)) 1000.0)
     :p95-ms (/ (Math/round (/ (at 0.95) 1000.0)) 1000.0)}))
(defn timed [warm n f]
  (dotimes [i warm] (f i))
  (mapv (fn [i] (let [t (System/nanoTime)] (f (+ warm i)) (- (System/nanoTime) t))) (range n)))
(defn ksv-count [dir] (count (filter #(.endsWith (.getName %) ".ksv") (file-seq (io/file dir)))))
(def root (str "/Users/sean/src/seon/tmp/perf-fsync/p2-" (UUID/randomUUID)))
(.mkdirs (io/file root))

(defn mk [path extra]
  (.mkdirs (io/file path))
  (let [cfg (merge {:store (merge {:backend :file :path path
                                   :id (UUID/nameUUIDFromBytes (.getBytes ^String path "UTF-8"))}
                                  (:store extra))
                    :writer (merge {:backend :self} (:writer extra))
                    :keep-history? true :schema-flexibility :write}
                   (dissoc extra :store :writer))]
    (d/delete-database cfg) (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn [{:db/ident :probe/id :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                        {:db/ident :probe/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
                        {:db/ident :probe/text :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
      conn)))
(defn row [i] (let [i (long i)] [{:probe/id (str "r" i) :probe/n i :probe/text (str "row " i)}]))

;;; (a) growth: blobs per commit and ms per commit at increasing datom counts
(let [path (str root "/grow") conn (mk path {})]
  (doseq [target [1000 5000 20000 60000 160000]]
    (loop []
      (when (< (count (d/datoms @conn :eavt)) target)
        (d/transact conn (into [] (map (fn [_] (first (row (rand-int 100000000)))))
                               (range 500)))
        (recur)))
    (let [before (ksv-count path)
          _ (d/transact conn (row (rand-int 100000000)))
          after (ksv-count path)
          q (quant (timed 5 20 (fn [_] (d/transact conn (row (rand-int 100000000))))))]
      (println :datoms (count (d/datoms @conn :eavt)) :new-blobs (- after before) :commit q)
      (flush))))

;;; (b) coalescing: concurrent callers against ONE file store
(defn concurrent-run [conn callers txs]
  (let [pool (Executors/newVirtualThreadPerTaskExecutor)
        latch (java.util.concurrent.CountDownLatch. txs)
        per (long (Math/ceil (/ (double txs) callers)))
        start (System/nanoTime)]
    (dotimes [c callers]
      (.submit pool ^Runnable
               (fn [] (dotimes [j per]
                        (when (pos? (.getCount latch))
                          (try (d/transact conn (row (+ (* c 100000) j (rand-int 1000000))))
                               (catch Exception _ nil))
                          (.countDown latch))))))
    (.await latch)
    (let [wall (/ (- (System/nanoTime) start) 1e6)]
      (.shutdownNow pool)
      {:callers callers :txs txs :wall-ms (Math/round wall)
       :tx-per-s (Math/round (/ (* 1000.0 txs) wall))})))

(let [path (str root "/conc") conn (mk path {})]
  (dotimes [i 100] (d/transact conn (row i)))
  (doseq [callers [1 4 16 64 256 1024]]
    (println :coalescing (concurrent-run conn callers 1024)) (flush)))

;;; (c) group commit via commit-wait-time, still one serial caller
(doseq [wait [0 5 25]]
  (let [path (str root "/wait" wait) conn (mk path {:writer {:backend :self :commit-wait-time wait}})]
    (dotimes [i 100] (d/transact conn (row i)))
    (println :commit-wait-time wait :serial (quant (timed 5 20 (fn [i] (d/transact conn (row (+ 5000 i)))))))
    (println :commit-wait-time wait :concurrent-64 (concurrent-run conn 64 512)) (flush)))
(println :done)
