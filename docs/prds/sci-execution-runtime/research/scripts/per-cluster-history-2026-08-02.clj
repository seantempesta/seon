;; Replay one complete archived GPQA agent episode into otherwise identical
;; history-on and history-off Datahike stores, preserving its transaction
;; boundaries, and measure the regular-file bytes of both results.
;;
;; Run from the repository root:
;;
;;   clojure -M:dev \
;;     docs/prds/sci-execution-runtime/research/scripts/per-cluster-history-2026-08-02.clj
;;
;; Optional arguments are SOURCE-STORE and BRANCH. The default source is the
;; read-only census copy, never an operator root. Every output goes below a new
;; UUID-named directory under tmp/ and is deliberately retained as evidence.

(require '[clojure.java.io :as io]
         '[datahike.api :as d]
         '[datahike.datom :as datom]
         '[datahike.migrate :as migrate]
         '[konserve.core :as k]
         '[konserve.filestore :as filestore]
         '[seon.cluster.store :as store])

(import '[java.io File])

(def ^:private default-source-store
  "tmp/store-census-20260802/d0cd8fbc1fa3-copy/data/clusters/store")

(def ^:private default-branch
  :inspect-grade-inspect-5c022394c4594117-fc8963cc)

(def ^:private default-base-max-tx 536870921)

(defn- regular-file-stats
  [path]
  (let [files (filter #(.isFile ^File %) (file-seq (io/file path)))]
    {:per-cluster-history/objects (count files)
     :per-cluster-history/bytes
     (reduce + 0 (map #(.length ^File %) files))}))

(defn- source-configuration
  [source-store branch]
  (let [path (.getCanonicalPath (io/file source-store))
        konserve (filestore/connect-fs-store path :opts {:sync? true})
        stored-db (k/get konserve branch nil {:sync? true})]
    (when-not stored-db
      (throw (ex-info "the selected archived branch record is absent"
                      {:per-cluster-history/source-store path
                       :per-cluster-history/branch branch})))
    (-> (:config stored-db)
        (assoc :branch branch)
        (assoc :store
               (assoc (:store (:config stored-db))
                      :backend :file
                      :path path)))))

(defn- transaction-groups
  [source-db]
  (->> (d/datoms (d/history source-db) :eavt)
       (remove #(= 536870912 (datom/datom-tx %)))
       (sort-by datom/datom-tx)
       (partition-by datom/datom-tx)
       ;; Each replay transact generates its own transaction instant. Importing
       ;; the archived instant as well would leave one synthetic terminal
       ;; transaction entity beyond the source's current database.
       (mapv #(vec (remove (fn [row] (= :db/txInstant (:a row))) %)))
       (filterv seq)))

(defn- current-domain-projection
  [database]
  (mapv (fn [row] [(:e row) (:a row) (:v row)])
        (remove #(= :db/txInstant (:a %))
                (d/datoms database :eavt))))

(defn- replay!
  [connection transactions]
  ;; Datahike's own import path updates max-tx from imported datoms before
  ;; transacting them (reference-code/datahike/src/datahike/migrate.clj:17-41).
  ;; Replaying one source transaction per call preserves the archived episode's
  ;; retained-commit topology instead of migration's arbitrary 10,000-datom
  ;; batching.
  (doseq [transaction transactions]
    (swap! connection migrate/update-max-tx transaction)
    (d/transact connection transaction))
  @connection)

(defn- run-cell!
  [root label keep-history? base-transactions episode-transactions
   expected-current]
  (let [path (.getCanonicalPath (io/file root (name label)))
        configuration (store/datahike-configuration path keep-history?)]
    (d/create-database configuration)
    (let [connection (d/connect configuration)]
      (try
        (let [_ (replay! connection base-transactions)
              base-stats (regular-file-stats path)
              database (replay! connection episode-transactions)
              final-stats (regular-file-stats path)
              current (current-domain-projection database)]
          (when-not (= expected-current current)
            (throw (ex-info "replayed current database differs from source"
                            {:per-cluster-history/cell label
                             :per-cluster-history/expected-datoms
                             (count expected-current)
                             :per-cluster-history/actual-datoms
                             (count current)})))
          (merge
           {:per-cluster-history/cell label
            :per-cluster-history/path path
            :per-cluster-history/keep-history? keep-history?
            :per-cluster-history/current-datoms (count current)
            :per-cluster-history/max-tx (:max-tx database)
            :per-cluster-history/base-bytes
            (:per-cluster-history/bytes base-stats)
            :per-cluster-history/final-bytes
            (:per-cluster-history/bytes final-stats)
            :per-cluster-history/episode-growth-bytes
            (- (:per-cluster-history/bytes final-stats)
               (:per-cluster-history/bytes base-stats))}
           final-stats))
        (finally
          (d/release connection))))))

(let [[source-argument branch-argument base-max-tx-argument]
      *command-line-args*
      source-store (or source-argument default-source-store)
      branch (if branch-argument (keyword branch-argument) default-branch)
      base-max-tx (if base-max-tx-argument
                    (parse-long base-max-tx-argument)
                    default-base-max-tx)
      source-config (source-configuration source-store branch)
      source (d/connect source-config)
      root (str "tmp/per-cluster-history-" (random-uuid))]
  (try
    (let [source-db @source
          transactions (transaction-groups source-db)
          [base-transactions episode-transactions]
          (split-with #(<= (datom/datom-tx (first %)) base-max-tx)
                      transactions)
          expected-current (current-domain-projection source-db)
          history-on
          (run-cell! root :history-on true
                     base-transactions episode-transactions expected-current)
          history-off
          (run-cell! root :history-off false
                     base-transactions episode-transactions expected-current)
          on-bytes (:per-cluster-history/bytes history-on)
          off-bytes (:per-cluster-history/bytes history-off)
          saving (- on-bytes off-bytes)
          on-growth (:per-cluster-history/episode-growth-bytes history-on)
          off-growth (:per-cluster-history/episode-growth-bytes history-off)
          episode-saving (- on-growth off-growth)]
      (prn
       {:per-cluster-history/source-store
        (.getCanonicalPath (io/file source-store))
        :per-cluster-history/source-branch branch
        :per-cluster-history/source-max-tx (:max-tx source-db)
        :per-cluster-history/base-max-tx base-max-tx
        :per-cluster-history/source-current-datoms (count expected-current)
        :per-cluster-history/source-history-datoms
        (count (d/datoms (d/history source-db) :eavt))
        :per-cluster-history/replayed-transactions (count transactions)
        :per-cluster-history/base-transactions (count base-transactions)
        :per-cluster-history/episode-transactions (count episode-transactions)
        :per-cluster-history/evidence-root
        (.getCanonicalPath (io/file root))
        :per-cluster-history/cells [history-on history-off]
        :per-cluster-history/full-store-saving-bytes saving
        :per-cluster-history/full-store-saving-fraction
        (/ saving (double on-bytes))
        :per-cluster-history/episode-saving-bytes episode-saving
        :per-cluster-history/episode-saving-fraction
        (/ episode-saving (double on-growth))}))
    (finally
      (d/release source))))
