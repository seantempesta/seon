(require '[clojure.java.io :as io]
         '[datahike.api :as d]
         '[konserve.impl.storage-layout :as storage-layout])

(import '[java.util Date UUID])

(defn- quantiles
  [samples]
  (let [ordered (vec (sort samples))
        n (count ordered)
        at (fn [fraction]
             (nth ordered (min (dec n) (long (* fraction n)))))]
    {:samples n
     :median-ms (/ (Math/round (/ (at 0.5) 1000.0)) 1000.0)
     :p95-ms (/ (Math/round (/ (at 0.95) 1000.0)) 1000.0)}))

(defn- timed
  [warmups samples f]
  (dotimes [index warmups]
    (f index))
  (mapv
   (fn [index]
     (let [began (System/nanoTime)]
       (f (+ warmups index))
       (- (System/nanoTime) began)))
   (range samples)))

(defn- file-stats
  [path]
  (let [files (filter #(.isFile ^java.io.File %)
                      (file-seq (io/file path)))]
    {:objects (count files)
     :bytes (reduce + 0 (map #(.length ^java.io.File %) files))}))

(defn- force-counts
  [f]
  (let [counts (atom {:file-forces 0 :directory-forces 0})
        original-sync storage-layout/-sync
        original-sync-store storage-layout/-sync-store]
    (with-redefs [storage-layout/-sync
                  (fn [blob env]
                    (swap! counts update :file-forces inc)
                    (original-sync blob env))
                  storage-layout/-sync-store
                  (fn [store env]
                    (swap! counts update :directory-forces inc)
                    (original-sync-store store env))]
      (f))
    @counts))

(defn- configuration
  [path {:keys [keep-history? fuse-index-roots? diff-buf-size
                commit-wait-time]}]
  (cond->
   {:store {:backend :file
            :path path
            :id (UUID/nameUUIDFromBytes (.getBytes ^String path "UTF-8"))}
    :writer {:backend :self
             :commit-wait-time commit-wait-time}
    :keep-history? keep-history?
    :schema-flexibility :write}
    fuse-index-roots? (assoc :fuse-index-roots? true)
    diff-buf-size (assoc :index-config {:diff-buf-size diff-buf-size})))

(def schema
  [{:db/ident :store.probe/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :store.probe/n
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :store.probe/text
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(defn- row
  [prefix index]
  [{:store.probe/id (str prefix index)
    :store.probe/n (long index)
    :store.probe/text (str "representative row " index)}])

(defn- grow!
  [connection]
  (doseq [batch (partition-all 500 (range 7000))]
    (d/transact
     connection
     (mapv (fn [index] (first (row "grown-" index))) batch))))

(defn- measure-commits!
  [root label options]
  (let [path (str root "/" label)
        config (configuration path options)]
    (d/create-database config)
    (let [connection (d/connect config)]
      (try
        (d/transact connection schema)
        (grow! connection)
        (let [next-index (atom 1000000)
              commit! (fn []
                        (d/transact connection
                                    (row "sample-" (swap! next-index inc))))
              force-count (force-counts commit!)
              latency (quantiles (timed 5 25 (fn [_] (commit!))))]
          {:label label
           :options options
           :datoms (count (d/datoms @connection :eavt))
           :force-count force-count
           :latency latency
           :files (file-stats path)})
        (finally
          (d/release connection))))))

(defn- measure-gc!
  [root]
  (let [path (str root "/gc")
        config (configuration path {:keep-history? true
                                    :fuse-index-roots? true
                                    :diff-buf-size 256
                                    :commit-wait-time 0})]
    (d/create-database config)
    (let [connection (d/connect config)]
      (try
        (d/transact connection schema)
        (d/transact connection (row "mutable" 0))
        (let [as-of-t (:max-tx @connection)]
          (doseq [index (range 1 401)]
            (d/transact connection
                        [{:store.probe/id "mutable0"
                          :store.probe/n (long index)
                          :store.probe/text
                          (str "replacement " index " "
                               (apply str (repeat 1000 \x)))}]))
          (let [source-commit (d/commit-id @connection)]
            (d/branch! connection source-commit :retained-source)
            (doseq [index (range 401 501)]
              (d/transact connection
                          [{:store.probe/id "mutable0"
                            :store.probe/n (long index)
                            :store.probe/text
                            (str "replacement " index " "
                                 (apply str (repeat 1000 \x)))}]))
            (let [before (file-stats path)
                  plain-swept (count @(d/gc-storage connection))
                  after-plain (file-stats path)
                  cutoff-swept (count @(d/gc-storage connection (Date.)))
                  after-cutoff (file-stats path)
                  historical-value
                  (d/q '[:find ?n .
                         :where [_ :store.probe/n ?n]]
                       (d/as-of @connection as-of-t))]
              (d/branch! connection source-commit :reforked-source)
              {:before before
               :plain {:swept plain-swept :files after-plain}
               :cutoff {:swept cutoff-swept :files after-cutoff}
               :bytes-reclaimed (- (:bytes before) (:bytes after-cutoff))
               :objects-reclaimed
               (- (:objects before) (:objects after-cutoff))
               :historical-value historical-value
               :source-commit-still-branchable?
               (contains? (d/branches connection) :reforked-source)})))
        (finally
          (d/release connection))))))

(let [root (str "/Users/sean/src/seon/tmp/store-capabilities-"
                (UUID/randomUUID))]
  (.mkdirs (io/file root))
  (prn
   {:commit-measurements
    [(measure-commits!
      root "default-history"
      {:keep-history? true
       :fuse-index-roots? false
       :commit-wait-time 0})
     (measure-commits!
      root "fused-history"
      {:keep-history? true
       :fuse-index-roots? true
       :diff-buf-size 256
       :commit-wait-time 0})
     (measure-commits!
      root "fused-no-history"
      {:keep-history? false
       :fuse-index-roots? true
       :diff-buf-size 256
       :commit-wait-time 0})
     (measure-commits!
      root "fused-history-wait-5"
      {:keep-history? true
       :fuse-index-roots? true
       :diff-buf-size 256
       :commit-wait-time 5})]
    :gc (measure-gc! root)})
  (shutdown-agents))
