;; Ranked fix options, all measured on comparably grown stores (~20k datoms).
(require '[datahike.api :as d] '[clojure.java.io :as io])
(import '[java.util UUID])
(defn quant [samples]
  (let [s (vec (sort samples)) n (count s) at #(nth s (min (dec n) (long (* % n))))]
    {:n n :median-ms (/ (Math/round (/ (at 0.5) 1000.0)) 1000.0)
     :p95-ms (/ (Math/round (/ (at 0.95) 1000.0)) 1000.0)
     :tx-per-s (Math/round (/ 1e9 (double (at 0.5))))}))
(defn timed [warm n f]
  (dotimes [i warm] (f i))
  (mapv (fn [i] (let [t (System/nanoTime)] (f (+ warm i)) (- (System/nanoTime) t))) (range n)))
(def root (str "/Users/sean/src/seon/tmp/perf-fsync/p3-" (UUID/randomUUID)))
(.mkdirs (io/file root))
(defn row [i] (let [i (long i)] [{:probe/id (str "r" i) :probe/n i :probe/text (str "row " i)}]))

(defn run [label cfg-fn]
  (let [path (str root "/" label)
        _ (.mkdirs (io/file path))
        cfg (cfg-fn path)]
    (d/delete-database cfg) (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn [{:db/ident :probe/id :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                        {:db/ident :probe/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
                        {:db/ident :probe/text :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
      (loop [] (when (< (count (d/datoms @conn :eavt)) 20000)
                 (d/transact conn (into [] (map (fn [_] (first (row (rand-int 100000000))))) (range 500)))
                 (recur)))
      (let [ksv (fn [] (count (filter #(.endsWith (.getName %) ".ksv") (file-seq (io/file path)))))
            b (ksv)
            _ (d/transact conn (row (rand-int 100000000)))
            blobs (- (ksv) b)]
        (println label :datoms (count (d/datoms @conn :eavt)) :new-blobs blobs
                 (quant (timed 5 25 (fn [_] (d/transact conn (row (rand-int 100000000))))))))
      (flush))))


;;; Does the store directory's SIZE make its fsync more expensive? The store
;;; accumulates every superseded node until GC runs.
(defn dir-force-ms [dir]
  (let [dp (java.nio.file.Paths/get dir (into-array String []))]
    (quant (timed 10 50 (fn [_] (let [c (java.nio.channels.FileChannel/open
                                          dp (into-array java.nio.file.OpenOption []))]
                                  (.force c true) (.close c)))))))
(doseq [d (filter #(.isDirectory %) (.listFiles (io/file "/Users/sean/src/seon/tmp/perf-fsync")))]
  (doseq [sub (filter #(.isDirectory %) (.listFiles d))]
    (println :dir-force (.getName d) (.getName sub) :entries (count (.list sub)) (dir-force-ms (.getPath sub)))
    (flush)))

(defn base [path store-extra]
  {:store (merge {:backend :file :path path
                  :id (UUID/nameUUIDFromBytes (.getBytes ^String path "UTF-8"))} store-extra)
   :writer {:backend :self} :keep-history? true :schema-flexibility :write})

(run "A-default"        (fn [p] (base p {})))
(run "B-no-sync-blob"   (fn [p] (base p {:config {:sync-blob? false}})))
(run "C-in-place"       (fn [p] (base p {:config {:in-place? true :no-backup? true}})))
(run "D-in-place-nosync" (fn [p] (base p {:config {:in-place? true :no-backup? true :sync-blob? false}})))
(run "E-no-history"     (fn [p] (assoc (base p {}) :keep-history? false)))
(run "F-fuse-roots"     (fn [p] (assoc (base p {}) :fuse-index-roots? true)))
(run "G-diff-buf"       (fn [p] (assoc (base p {}) :index-config {:diff-buf-size 256})))
(run "H-amp-all"        (fn [p] (assoc (base p {}) :fuse-index-roots? true
                                       :index-config {:diff-buf-size 256}
                                       :commit-graph? false)))
(run "I-amp-all-nohist" (fn [p] (assoc (base p {}) :keep-history? false
                                       :fuse-index-roots? true
                                       :index-config {:diff-buf-size 256}
                                       :commit-graph? false)))
(println :done)
