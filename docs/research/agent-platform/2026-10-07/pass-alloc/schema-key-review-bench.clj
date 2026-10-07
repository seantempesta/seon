;; Run in a standalone JVM with the reviewed Datahike archive first on its classpath.
;; SCHEMA_REVIEW_STORE names a clone, never the live store. See schema-key-review-notes.md.
(require '[datahike.api :as d] '[datahike.writing :as w] '[datahike.versioning :as v]
         '[hasch.core :as h] '[konserve.core :as k] '[clojure.java.io :as io])
(import '[java.lang.management ManagementFactory])
(def meta-keys [:schema :rschema :system-entities :ident-ref-map :ref-ident-map])
(defn keyed [db] (:schema-meta-key (second (w/db->stored db false))))
(defn observe [label db]
  (let [expected (h/uuid (select-keys db meta-keys)) actual (keyed db)
        stored (k/get (:store db) (:branch (:config db)) nil {:sync? true})]
    (prn {:case label :equal (= expected actual) :expected expected :actual actual
          :stored-equal (= expected (:schema-meta-key stored))})))
(defn measure [f]
  (let [mx ^com.sun.management.ThreadMXBean (ManagementFactory/getThreadMXBean)
        id (.getId (Thread/currentThread))
        b (.getTotalThreadAllocatedBytes mx) t (.getThreadAllocatedBytes mx id) s (System/nanoTime)
        value (f)]
    {:ms (/ (- (System/nanoTime) s) 1e6) :bytes (- (.getTotalThreadAllocatedBytes mx) b)
     :thread-bytes (- (.getThreadAllocatedBytes mx id) t) :value value}))
(defn median [xs] (nth (vec (sort xs)) (quot (count xs) 2)))
(defn summarize [label rows]
  (prn {:probe label :n (count rows) :ms-median (median (map :ms rows))
        :ms-min (apply min (map :ms rows)) :ms-max (apply max (map :ms rows))
        :bytes-median (median (map :bytes rows)) :thread-bytes-median (median (map :thread-bytes rows))}))

(let [cfg {:attribute-refs? false :branch :cluster-default :crypto-hash? false
           :fuse-index-roots? true :index :datahike.index/persistent-set
           :index-config {:branching-factor 128 :diff-buf-size 32}
           :keep-history? true :schema-flexibility :write :search-cache-size 10000 :store-cache-size 8192
           :writer {:backend :self}
           :store {:backend :file :path (or (System/getenv "SCHEMA_REVIEW_STORE") (throw (ex-info "SCHEMA_REVIEW_STORE must name the disposable clone." {})))
                   :id #uuid "224560ae-b0ba-3d5c-b6db-6d7415ad66e7"
                   :config {:defer-immutable-sync? true :compressor {:type :lz4}}}}
      acquisition (measure #(d/connect cfg))
      base (:value acquisition) branch (keyword (str "schema-review-" (random-uuid)))]
  (try
    (prn (assoc (dissoc acquisition :value) :probe :connect :schema-count (count (:schema @base))))
    (v/branch! base :cluster-default branch)
    (let [c (d/connect (assoc cfg :branch branch))]
      (try
        (d/transact c [{:db/ident :review/value :db/valueType :db.type/long :db/cardinality :db.cardinality/one}])
        (dotimes [i 30] (d/transact c [{:review/value i}]))
        (let [store-before (reduce (fn [n f] (if (.isFile f) (+ n (.length f)) n)) 0 (file-seq (io/file (get-in cfg [:store :path]))))
              before (:max-tx @c)
              rows (mapv (fn [i] (dissoc (measure #(d/transact c [{:review/value i}])) :value)) (range 60))]
          (summarize :durable-commit rows)
          (let [after (reduce (fn [n f] (if (.isFile f) (+ n (.length f)) n)) 0 (file-seq (io/file (get-in cfg [:store :path]))))]
            (prn {:probe :store-bytes :before store-before :after after :growth (- after store-before) :per-commit (/ (- after store-before) 60.0)}))
          (prn {:probe :commit-count :commits (- (:max-tx @c) before) :armed-calls 0}))
        (let [db @c]
          (dotimes [_ 10] (h/uuid (select-keys db meta-keys)))
          (summarize :hash-schema (mapv (fn [_] (dissoc (measure #(h/uuid (select-keys db meta-keys))) :value)) (range 40)))
          (dotimes [_ 10] (keyed db))
          (summarize :serialize-held-db (mapv (fn [_] (dissoc (measure #(keyed db)) :value)) (range 40)))
          (summarize :serialize-new-data-db
                     (mapv (fn [i]
                             (let [next-db (:db-after (d/with db [{:review/value i}]))]
                               (dissoc (measure #(keyed next-db)) :value))) (range 40))))
        (observe :clone-final @c)
        (finally (d/release c))))
    (finally (d/release base))))
(shutdown-agents)
(System/exit 0)
