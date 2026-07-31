;; Where does a 123 ms file-store commit go? Count konserve blob writes and
;; fsyncs per Datahike commit, and price one fsync on this machine's APFS.
(require '[datahike.api :as d]
         '[konserve.filestore :as filestore]
         '[konserve.core :as k]
         '[clojure.java.io :as io])
(import '[java.nio.file Files Paths StandardOpenOption OpenOption]
        '[java.nio.channels FileChannel]
        '[java.util UUID])

(defn quant [samples]
  (let [s (vec (sort samples)) n (count s)
        at #(nth s (min (dec n) (long (* % n))))]
    {:n n :median-ms (/ (Math/round (/ (at 0.5) 1000.0)) 1000.0)
     :p95-ms (/ (Math/round (/ (at 0.95) 1000.0)) 1000.0)}))

(defn timed [warm n f]
  (dotimes [i warm] (f i))
  (mapv (fn [i] (let [t (System/nanoTime)] (f (+ warm i)) (- (System/nanoTime) t))) (range n)))

(def root (str "/Users/sean/src/seon/tmp/perf-fsync/store-" (UUID/randomUUID)))

;;; ---- 1. price one fsync of a small file, and one directory fsync ---------
(.mkdirs (io/file root))
(def fpath (Paths/get (str root "/one.bin") (into-array String [])))
(def fc (FileChannel/open fpath (into-array StandardOpenOption
                                            [StandardOpenOption/WRITE StandardOpenOption/READ
                                             StandardOpenOption/CREATE])))
(.write fc (java.nio.ByteBuffer/wrap (.getBytes "hello world" "UTF-8")) 0)
(println :file-force (quant (timed 20 100 (fn [_] (.force fc true)))))
(def dpath (Paths/get root (into-array String [])))
(println :dir-force (quant (timed 20 100 (fn [_]
                                           (let [c (FileChannel/open dpath (into-array OpenOption []))]
                                             (.force c true) (.close c))))))

;;; ---- 2. price one konserve k/assoc (write + fsync blob + fsync dir) ------
(def kstore (filestore/connect-fs-store (str root "/ks") :opts {:sync? true}))
(println :konserve-assoc
         (quant (timed 20 60 (fn [i] (k/assoc kstore (str "k" i) {:a i :b (range 20)} {:sync? true})))))
(def kstore-nosync (filestore/connect-fs-store (str root "/ks2")
                                               :config {:sync-blob? false}
                                               :opts {:sync? true}))
(println :konserve-assoc-nosync
         (quant (timed 20 60 (fn [i] (k/assoc kstore-nosync (str "k" i) {:a i :b (range 20)} {:sync? true})))))

;;; ---- 3. a Datahike file-store commit: how many blobs written? ------------
(defn ksv-count [dir] (count (filter #(.endsWith (.getName %) ".ksv") (file-seq (io/file dir)))))

(defn open-db [path cfg-extra]
  (let [cfg (merge {:store {:backend :file :path path
                            :id (UUID/nameUUIDFromBytes (.getBytes ^String path "UTF-8"))}
                    :writer {:backend :self}
                    :keep-history? true
                    :schema-flexibility :write}
                   cfg-extra)]
    (d/delete-database cfg)
    (d/create-database cfg)
    (let [conn (d/connect cfg)]
      (d/transact conn [{:db/ident :probe/id :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                        {:db/ident :probe/n :db/valueType :db.type/long
                         :db/cardinality :db.cardinality/one}
                        {:db/ident :probe/text :db/valueType :db.type/string
                         :db/cardinality :db.cardinality/one}])
      [conn cfg])))

(defn row [i] [{:probe/id (str "r" i) :probe/n i :probe/text (str "row " i)}])

(let [path (str root "/dh-hist")
      [conn _] (open-db path {:keep-history? true})]
  (dotimes [i 50] (d/transact conn (row i)))
  (let [before (ksv-count path)
        _ (d/transact conn (row 1000))
        after (ksv-count path)]
    (println :keep-history-true :ksv-before before :ksv-after after :new-blobs (- after before)))
  (println :dh-file-history-on (quant (timed 20 40 (fn [i] (d/transact conn (row (+ 2000 i))))))))

(let [path (str root "/dh-nohist")
      [conn _] (open-db path {:keep-history? false})]
  (dotimes [i 50] (d/transact conn (row i)))
  (let [before (ksv-count path)
        _ (d/transact conn (row 1000))
        after (ksv-count path)]
    (println :keep-history-false :new-blobs (- after before)))
  (println :dh-file-history-off (quant (timed 20 40 (fn [i] (d/transact conn (row (+ 2000 i))))))))

;;; ---- 4. the same, with konserve fsync disabled ---------------------------
(let [path (str root "/dh-nosync")
      [conn _] (open-db path {:keep-history? true
                              :store {:backend :file :path path
                                      :id (UUID/nameUUIDFromBytes (.getBytes ^String path "UTF-8"))
                                      :config {:sync-blob? false}}})]
  (dotimes [i 50] (d/transact conn (row i)))
  (println :dh-file-sync-blob-false (quant (timed 20 40 (fn [i] (d/transact conn (row (+ 2000 i))))))))

;;; ---- 5. memory baseline --------------------------------------------------
(let [cfg {:store {:backend :memory :id (UUID/randomUUID)}
           :writer {:backend :self} :keep-history? true :schema-flexibility :write}]
  (d/create-database cfg)
  (let [conn (d/connect cfg)]
    (d/transact conn [{:db/ident :probe/id :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one :db/unique :db.unique/identity}
                      {:db/ident :probe/n :db/valueType :db.type/long :db/cardinality :db.cardinality/one}
                      {:db/ident :probe/text :db/valueType :db.type/string :db/cardinality :db.cardinality/one}])
    (dotimes [i 50] (d/transact conn (row i)))
    (println :dh-memory (quant (timed 20 100 (fn [i] (d/transact conn (row (+ 2000 i)))))))))

(println :done root)
