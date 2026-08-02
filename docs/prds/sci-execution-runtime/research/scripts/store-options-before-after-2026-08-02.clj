;; Reproduce the store-option measurements for the 2026-08-02 amplification
;; audit. Run from the repository root:
;;
;;   clojure -M:dev \
;;     docs/prds/sci-execution-runtime/research/scripts/store-options-before-after-2026-08-02.clj
;;
;; Every run creates a UUID-named private store below tmp/. It never opens,
;; mutates, collects, or deletes data/clusters or another operator's root.

(require '[clojure.java.io :as io]
         '[datahike.api :as d]
         '[datahike.writing :as writing]
         '[konserve.core :as k]
         '[konserve.impl.storage-layout :as storage-layout]
         '[konserve.utils :as konserve-utils]
         '[seon.cluster.store :as seon-store])

(import '[java.util Date UUID])

(def ^:private row-count 1200)
(def ^:private warmup-count 2)
(def ^:private sample-count 7)
(def ^:private burst-size 24)
(def ^:private burst-count 3)

(def ^:private schema
  [{:db/ident :store.options/id
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one
    :db/unique :db.unique/identity}
   {:db/ident :store.options/n
    :db/valueType :db.type/long
    :db/cardinality :db.cardinality/one}
   {:db/ident :store.options/text
    :db/valueType :db.type/string
    :db/cardinality :db.cardinality/one}])

(defn- file-stats
  [path]
  (let [files (filter #(.isFile ^java.io.File %)
                      (file-seq (io/file path)))]
    {:store-options/object-count (count files)
     :store-options/bytes (reduce + 0 (map #(.length ^java.io.File %) files))}))

(defn- median
  [values]
  (nth (vec (sort values)) (quot (count values) 2)))

(defn- milliseconds
  [nanoseconds]
  (/ (Math/round (/ nanoseconds 1000.0)) 1000.0))

(defn- rows
  [prefix indexes]
  (mapv (fn [index]
          {:store.options/id (str prefix index)
           :store.options/n (long index)
           :store.options/text (str "row " index)})
        indexes))

(defn- populate!
  [connection]
  (d/transact connection schema)
  (doseq [batch (partition-all 400 (range row-count))]
    (d/transact connection (rows "row-" batch))))

(defn- configuration
  [path {:store-options/keys [fuse-index-roots? diff-buf-size
                              keep-history? commit-wait-time]}]
  (cond-> (seon-store/datahike-configuration path)
    (false? fuse-index-roots?)
    (dissoc :fuse-index-roots?)

    (nil? diff-buf-size)
    (dissoc :index-config)

    (some? diff-buf-size)
    (assoc :index-config {:diff-buf-size diff-buf-size})

    (some? keep-history?)
    (assoc :keep-history? keep-history?)

    (some? commit-wait-time)
    (assoc :writer {:backend :self
                    :commit-wait-time commit-wait-time})))

(defn- one-commit!
  [connection index ordered-batch?]
  (let [counts (atom {:store-options/blob-forces 0})
        original-sync storage-layout/-sync
        began (System/nanoTime)]
    (with-redefs [storage-layout/-sync
                  (fn [blob environment]
                    (swap! counts update :store-options/blob-forces inc)
                    (original-sync blob environment))
                  konserve-utils/multi-key-capable?
                  (fn [_] ordered-batch?)]
      (d/transact connection
                  [{:store.options/id "row-0"
                    :store.options/n (long (+ 1000000 index))
                    :store.options/text (str "sample " index)}]))
    (assoc @counts
           :store-options/elapsed-ms
           (milliseconds (- (System/nanoTime) began)))))

(defn- measure-variant!
  [root {:store-options/keys [label ordered-batch?] :as options}]
  (let [path (str root "/" (name label))
        config (configuration path options)]
    (d/create-database config)
    (let [connection (d/connect config)]
      (try
        (populate! connection)
        (dotimes [index warmup-count]
          (one-commit! connection index ordered-batch?))
        (let [samples (mapv #(one-commit! connection
                                          (+ warmup-count %)
                                          ordered-batch?)
                            (range sample-count))]
          {:store-options/label label
           :store-options/requested-options options
           :store-options/effective-options
           (select-keys (:config @connection)
                        [:keep-history? :fuse-index-roots?
                         :index-config :writer :commit-graph?])
           :store-options/datoms (count (d/datoms @connection :eavt))
           :store-options/median-elapsed-ms
           (median (map :store-options/elapsed-ms samples))
           :store-options/median-blob-forces
           (median (map :store-options/blob-forces samples))
           :store-options/files (file-stats path)})
        (finally
          (d/release connection))))))

(defn- burst!
  [connection burst-index]
  (let [gate (promise)
        tasks
        (mapv (fn [index]
                (future
                  @gate
                  (d/transact
                   connection
                   [{:store.options/id (str "burst-" burst-index "-" index)
                     :store.options/n (long index)
                     :store.options/text "concurrent writer admission"}])))
              (range burst-size))
        began (System/nanoTime)]
    (deliver gate true)
    (doseq [task tasks]
      (deref task 120000 ::timed-out))
    (- (System/nanoTime) began)))

(defn- measure-writer-wait!
  [root commit-wait-time]
  (let [label (keyword (str "writer-wait-" commit-wait-time))
        path (str root "/" (name label))
        config (configuration
                path
                {:store-options/fuse-index-roots? true
                 :store-options/diff-buf-size 256
                 :store-options/keep-history? true
                 :store-options/commit-wait-time commit-wait-time})]
    (d/create-database config)
    (let [connection (d/connect config)]
      (try
        (populate! connection)
        (let [physical-commits (atom 0)
              original-commit! writing/commit!
              elapsed-samples
              (with-redefs [writing/commit!
                            (fn [& arguments]
                              (swap! physical-commits inc)
                              (apply original-commit! arguments))]
                (mapv #(burst! connection %) (range burst-count)))]
          {:store-options/label label
           :store-options/logical-transactions (* burst-count burst-size)
           :store-options/physical-commits @physical-commits
           :store-options/median-burst-elapsed-ms
           (milliseconds (median elapsed-samples))
           :store-options/transactions-per-second
           (/ (* 1.0e9 burst-size) (median elapsed-samples))})
        (finally
          (d/release connection))))))

(defn- measure-gc!
  [root]
  (let [path (str root "/gc-cutoff")
        config (configuration
                path
                {:store-options/fuse-index-roots? true
                 :store-options/diff-buf-size 256
                 :store-options/keep-history? true
                 :store-options/commit-wait-time 0})]
    (d/create-database config)
    (let [connection (d/connect config)]
      (try
        (d/transact connection schema)
        (d/transact connection
                    [{:store.options/id "mutable"
                      :store.options/n 0
                      :store.options/text "before"}])
        (let [old-commit-id (d/commit-id @connection)]
          (doseq [index (range 1 81)]
            (d/transact connection
                        [{:store.options/id "mutable"
                          :store.options/n (long index)
                          :store.options/text
                          (str "replacement-" index "-"
                               (apply str (repeat 512 \x)))}]))
          (let [before (file-stats path)
                plain-swept (count @(d/gc-storage connection))
                after-plain (file-stats path)
                cutoff-swept (count @(d/gc-storage connection (Date.)))
                after-cutoff (file-stats path)
                store (:store @connection)]
            {:store-options/before before
             :store-options/plain
             {:store-options/swept plain-swept
              :store-options/files after-plain}
             :store-options/cutoff-now
             {:store-options/swept cutoff-swept
              :store-options/files after-cutoff}
             :store-options/cutoff-reclaimed-bytes
             (- (:store-options/bytes before)
                (:store-options/bytes after-cutoff))
             :store-options/cutoff-reclaimed-objects
             (- (:store-options/object-count before)
                (:store-options/object-count after-cutoff))
             :store-options/old-commit-record-present-after-cutoff?
             (some? (k/get store old-commit-id nil {:sync? true}))}))
        (finally
          (d/release connection))))))

(defn- raw-cache-count
  [store]
  (count @(:cache store)))

(defn- measure-caches!
  [root]
  (let [path (str root "/cache")
        config (configuration
                path
                {:store-options/fuse-index-roots? true
                 :store-options/diff-buf-size 256
                 :store-options/keep-history? true
                 :store-options/commit-wait-time 0})]
    (d/create-database config)
    (let [connection (d/connect config)]
      (populate! connection)
      (d/release connection))
    (let [connection (d/connect config)]
      (try
        (let [store (:store @connection)
              node-cache (:cache (:storage store))
              raw-before (raw-cache-count store)
              node-cache-before (count @node-cache)
              node-before @(:stats (:storage store))
              _ (dotimes [_ 3]
                  (k/get store :db nil {:sync? true}))
              raw-after-direct-reads (raw-cache-count store)
              _ (d/q '[:find (count ?e) .
                       :where [?e :store.options/id]]
                     @connection)
              node-cache-after-first-query (count @node-cache)
              node-after-first-query @(:stats (:storage store))
              _ (d/q '[:find (count ?e) .
                       :where [?e :store.options/id]]
                     @connection)
              node-cache-after-second-query (count @node-cache)
              node-after-second-query @(:stats (:storage store))]
          {:store-options/konserve-cache-threshold
           (:store-cache-size (:config @connection))
           :store-options/konserve-cache-present? (some? (:cache store))
           :store-options/node-cache-present? (some? node-cache)
           :store-options/caches-identical?
           (identical? (:cache store) node-cache)
           :store-options/konserve-cache-entry-count-before raw-before
           :store-options/konserve-cache-entry-count-after-three-core-reads
           raw-after-direct-reads
           :store-options/node-cache-entry-count-before node-cache-before
           :store-options/node-cache-entry-count-after-first-query
           node-cache-after-first-query
           :store-options/node-cache-entry-count-after-second-query
           node-cache-after-second-query
           :store-options/node-cache-stats-before node-before
           :store-options/node-cache-stats-after-first-query
           node-after-first-query
           :store-options/node-cache-stats-after-second-query
           node-after-second-query})
        (finally
          (d/release connection))))))

(let [root (str "tmp/store-options-before-after-2026-08-02/"
                (UUID/randomUUID))
      _ (.mkdirs (io/file root))
      variants
      [{:store-options/label :legacy-sequential
        :store-options/fuse-index-roots? false
        :store-options/diff-buf-size nil
        :store-options/keep-history? true
        :store-options/commit-wait-time 0
        :store-options/ordered-batch? false}
       {:store-options/label :legacy-ordered
        :store-options/fuse-index-roots? false
        :store-options/diff-buf-size nil
        :store-options/keep-history? true
        :store-options/commit-wait-time 0
        :store-options/ordered-batch? true}
       {:store-options/label :fused-only-ordered
        :store-options/fuse-index-roots? true
        :store-options/diff-buf-size nil
        :store-options/keep-history? true
        :store-options/commit-wait-time 0
        :store-options/ordered-batch? true}
       {:store-options/label :current-fused-diff-ordered
        :store-options/fuse-index-roots? true
        :store-options/diff-buf-size 256
        :store-options/keep-history? true
        :store-options/commit-wait-time 0
        :store-options/ordered-batch? true}
       {:store-options/label :current-fused-diff-history-off
        :store-options/fuse-index-roots? true
        :store-options/diff-buf-size 256
        :store-options/keep-history? false
        :store-options/commit-wait-time 0
        :store-options/ordered-batch? true}
       {:store-options/label :current-fused-diff-wait-5
        :store-options/fuse-index-roots? true
        :store-options/diff-buf-size 256
        :store-options/keep-history? true
        :store-options/commit-wait-time 5
        :store-options/ordered-batch? true}]
      result
      {:store-options/revisions
       {:store-options/datahike "256b714d97a0e8f952b01a47c693eff2976ccee7"
        :store-options/konserve "737697d9205e5e8f0bc08a666e4c97dad55e9dbe"
        :store-options/persistent-sorted-set
        "e1a17bbe767c7801e67407c81f64efabfd2f1601"}
       :store-options/private-root (.getCanonicalPath (io/file root))
       :store-options/current-seon-creation-config
       (select-keys (seon-store/datahike-configuration
                     (str root "/configuration-only"))
                    [:writer :keep-history? :fuse-index-roots? :index-config])
       :store-options/commit-variants
       (mapv #(measure-variant! root %) variants)
       :store-options/writer-wait
       [(measure-writer-wait! root 0)
        (measure-writer-wait! root 5)]
       :store-options/gc (measure-gc! root)
       :store-options/caches (measure-caches! root)}]
  (prn result)
  (shutdown-agents))
