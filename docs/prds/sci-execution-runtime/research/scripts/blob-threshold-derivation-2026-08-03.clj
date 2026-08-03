;; Reproduce the blob-threshold crossover measured on 2026-08-03.
;; Run from the repository root:
;;
;;   clojure -M:dev \
;;     docs/prds/sci-execution-runtime/research/scripts/blob-threshold-derivation-2026-08-03.clj
;;
;; The memory cells are the cheap regression model. Each file cell creates a
;; UUID-named private store below tmp/ and deletes it after measurement. The
;; script never opens, mutates, or deletes an operator store.

(require '[clojure.edn :as edn]
         '[clojure.java.io :as io]
         '[datahike.api :as d]
         '[org.replikativ.persistent-sorted-set.fressian :as pss-fress]
         '[seon.blob :as blob]
         '[seon.cluster.loop :as loop]
         '[seon.cluster.store :as store]
         '[seon.config :as config]
         '[seon.fs :as fs]
         '[seon.schema.datahike :as schema.datahike])

(import '[datahike.datom Datom]
        '[java.io File]
        '[org.replikativ.persistent_sorted_set ANode PersistentSortedSet])

(def ^:private byte-array-class (class (byte-array 0)))

(declare raw-payload-bytes)

(defn- raw-payload-bytes
  [value]
  (cond
    (string? value) (alength (.getBytes ^String value "UTF-8"))
    (instance? byte-array-class value) (alength ^bytes value)
    (instance? Datom value) (raw-payload-bytes (.-v ^Datom value))
    (instance? PersistentSortedSet value)
    (reduce + 0 (map raw-payload-bytes (seq value)))
    (instance? ANode value) (raw-payload-bytes (pss-fress/node->map value))
    (map? value) (reduce + 0 (map raw-payload-bytes (vals value)))
    (coll? value) (reduce + 0 (map raw-payload-bytes value))
    :else 0))

(defn- state-size
  [connection]
  (let [state @(-> @connection :store :state)]
    (reduce-kv
     (fn [total _key [metadata value]]
       (+ total (raw-payload-bytes metadata) (raw-payload-bytes value)))
     0
     state)))

(def ^:private measured-attributes
  [:seon.config.eval.result/blob-threshold
   :seon.render.value/max-collection
   :seon.cluster.eval/id
   :seon.cluster.eval/result-edn
   :seon.cluster.eval/result-blob
   :seon.cluster.eval/result-size])

(defn- exact-result-edn
  [target-size index]
  (let [prefix (str index "-")
        content (str prefix
                     (apply str
                            (repeat (max 0 (- target-size 2 (count prefix)))
                                    \r)))
        serialized (pr-str content)]
    (assert (= target-size (count serialized)))
    serialized))

(defn- memory-cell
  [threshold]
  (let [database-id (random-uuid)
        database-config
        {:store {:backend :memory :id database-id}
         :writer {:backend :self :commit-wait-time 0}
         :keep-history? true
         :schema-flexibility :write}]
    (d/create-database database-config)
    (let [connection (d/connect database-config)]
      (try
        (d/transact
         connection
         (schema.datahike/malli->datahike-schema measured-attributes))
        (d/transact
         connection
         [{:seon.config.eval.result/blob-threshold (long threshold)
           :seon.render.value/max-collection 8}])
        (let [before (state-size connection)
              caps (config/result-caps (config/defaults))
              sizes [59 59 59 59 4100 8000 13256]
              rows
              (mapv
               (fn [[index size]]
                 (let [settlement
                       (#'loop/settlement-result
                        {:seon.store/branch-connection connection
                         :seon.sci.admit/caps caps}
                        {:seon.cluster.eval/result-edn
                         (exact-result-edn size index)})]
                   (d/transact
                    connection
                    [(assoc
                      (select-keys
                       settlement
                       [:seon.cluster.eval/result-edn
                        :seon.cluster.eval/result-blob
                        :seon.cluster.eval/result-size])
                      :seon.cluster.eval/id (str "memory-" index))])
                   settlement))
               (map-indexed vector sizes))]
          {:blob-threshold threshold
           :blob-count (count (keep :seon.cluster.eval/result-blob rows))
           :raw-payload-growth (- (state-size connection) before)})
        (finally
          (d/release connection)
          (d/delete-database database-config))))))

(defn- directory-bytes
  [path]
  (reduce + 0
          (map #(.length ^File %)
               (filter #(.isFile ^File %) (file-seq (io/file path))))))

(defn- elapsed-ms
  [operation]
  (let [started (System/nanoTime)
        value (operation)]
    {:milliseconds (/ (double (- (System/nanoTime) started)) 1000000.0)
     :value value}))

(defn- median
  [values]
  (let [ordered (vec (sort values))]
    (nth ordered (quot (count ordered) 2))))

(defn- file-cell
  [root size blob?]
  (let [path (str root "/" size "-" (if blob? "blob" "inline"))
        database-config
        (assoc (store/datahike-configuration path)
               :writer {:backend :self :commit-wait-time 0}
               :keep-history? true)]
    (d/create-database database-config)
    (let [connection (d/connect database-config)]
      (try
        (d/transact
         connection
         (schema.datahike/malli->datahike-schema measured-attributes))
        (d/transact
         connection
         [{:seon.config.eval.result/blob-threshold
           (long (if blob? 1 Long/MAX_VALUE))
           :seon.render.value/max-collection 8}])
        (let [before (directory-bytes path)
              result-edn (exact-result-edn size 0)
              settlement-timing
              (elapsed-ms
               #(#'loop/settlement-result
                 {:seon.store/branch-connection connection
                  :seon.sci.admit/caps (config/result-caps (config/defaults))}
                 {:seon.cluster.eval/result-edn result-edn}))
              settlement (:value settlement-timing)
              transaction-timing
              (elapsed-ms
               #(d/transact
                 connection
                 [(assoc
                   (select-keys
                    settlement
                    [:seon.cluster.eval/result-edn
                     :seon.cluster.eval/result-blob
                     :seon.cluster.eval/result-size])
                   :seon.cluster.eval/id "measured")]))
              digest (:seon.cluster.eval/result-blob settlement)
              first-read (when digest (elapsed-ms #(blob/get connection digest)))
              warm-reads
              (when digest
                (mapv (fn [_]
                        (:milliseconds (elapsed-ms #(blob/get connection digest))))
                      (range 7)))
              parsed-reads
              (when digest
                (mapv
                 (fn [_]
                   (:milliseconds
                    (elapsed-ms
                     #(edn/read-string (blob/get connection digest)))))
                 (range 7)))
              after (directory-bytes path)]
          {:size size
           :blob? blob?
           :growth (- after before)
           :settlement-ms (:milliseconds settlement-timing)
           :transaction-ms (:milliseconds transaction-timing)
           :first-blob-read-ms (:milliseconds first-read)
           :warm-blob-read-median-ms (when warm-reads (median warm-reads))
           :warm-blob-read-plus-edn-median-ms
           (when parsed-reads (median parsed-reads))})
        (finally
          (d/release connection))))))

(let [root (str "tmp/blob-threshold-derivation-" (random-uuid))
      broad-sizes [64 256 1024 4096 4100 8192 13256 65536]
      boundary-sizes [337 338 339 340 341 342 343 344 345]]
  (try
    (prn
     {:memory
      (mapv memory-cell [64 256 343 1024 4096 65536])
      :file
      (mapv (fn [[size blob?]] (file-cell root size blob?))
            (for [size (distinct (concat broad-sizes boundary-sizes))
                  blob? [false true]]
              [size blob?]))})
    (finally
      (fs/delete-recursively! root root))))
