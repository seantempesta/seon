;; Measure full stored receipt shapes and N=50 settlement latency.
;; Run from the repository root with `clojure -M:dev` and this file path.
;; Every file store is UUID-named below tmp/ and deleted before exit.

(require '[clojure.java.io :as io]
         '[datahike.api :as d]
         '[seon.cluster.loop :as loop]
         '[seon.cluster.store :as store]
         '[seon.config :as config]
         '[seon.fs :as fs]
         '[seon.render.value :as render.value]
         '[seon.schema.datahike :as schema.datahike]
         '[seon.sci.admit :as admit])

(def ^:private caps (config/result-caps (config/defaults)))

(def ^:private result-blob-smaller-var
  (ns-resolve 'seon.cluster.loop 'result-blob-smaller?))

(def ^:private measured-attributes
  [:seon.config.eval.result/blob-threshold
   :seon.render.value/max-collection
   :seon.cluster.eval/id
   :seon.cluster.eval/result-edn
   :seon.cluster.eval/result-blob
   :seon.cluster.eval/result-size])

(defn- directory-bytes
  [path]
  (reduce + 0
          (map #(.length ^java.io.File %)
               (filter #(.isFile ^java.io.File %)
                       (file-seq (io/file path))))))

(defn- elapsed-ms
  [operation]
  (let [started (System/nanoTime)
        value (operation)]
    {:milliseconds (/ (double (- (System/nanoTime) started)) 1000000.0)
     :value value}))

(defn- percentile
  [fraction values]
  (let [ordered (vec (sort values))]
    (nth ordered
         (min (dec (count ordered))
              (long (Math/floor (* fraction (count ordered))))))))

(defn- admitted-result
  [value]
  (:seon.cluster.eval/result-edn
   (admit/admit
    {:seon.sci.admit/value value
     :seon.sci.admit/interrupt-fn (fn [])
     :seon.sci.admit/caps caps
     :seon.config/on-core-error :record})))

(defn- shape
  [shape-name payload-size marker]
  (let [payload (str marker "-" (apply str (repeat payload-size \r)))
        value (case shape-name
                :scalar payload
                :one-item-vector [payload]
                :wide-vector (vec (repeat 40 payload)))
        result-edn (admitted-result value)
        window-edn
        (render.value/result-window-edn
         {:seon.sci.admit/caps caps
          :seon.render.value/options
          {:seon.render.value/max-collection 8}}
         result-edn)]
    {:blob.followup/shape shape-name
     :blob.followup/payload-size payload-size
     :blob.followup/result-edn result-edn
     :blob.followup/result-size (count result-edn)
     :blob.followup/window-size (count window-edn)}))

(defn- full-shape-cell
  [root shape-value blob?]
  (let [shape-name (:blob.followup/shape shape-value)
        payload-size (:blob.followup/payload-size shape-value)
        path (str root "/" (name shape-name) "-" payload-size "-"
                  (if blob? "blob" "inline"))
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
              settle
              (fn []
                (#'loop/settlement-result
                 {:seon.store/branch-connection connection
                  :seon.sci.admit/caps caps}
                 {:seon.cluster.eval/result-edn
                  (:blob.followup/result-edn shape-value)}))
              settlement
              (if blob?
                (with-redefs-fn {result-blob-smaller-var (fn [_ _] true)} settle)
                (settle))
              _
              (d/transact
               connection
               [(assoc
                 (select-keys
                  settlement
                  [:seon.cluster.eval/result-edn
                   :seon.cluster.eval/result-blob
                   :seon.cluster.eval/result-size])
                 :seon.cluster.eval/id "measured")])
              after (directory-bytes path)]
          (assoc (select-keys shape-value
                              [:blob.followup/shape
                               :blob.followup/payload-size
                               :blob.followup/result-size
                               :blob.followup/window-size])
                 :blob.followup/blob? blob?
                 :blob.followup/stored-result-size
                 (count (:seon.cluster.eval/result-edn settlement))
                 :blob.followup/growth (- after before)))
        (finally
          (d/release connection))))))

(defn- latency-cell
  [root threshold]
  (let [path (str root "/latency-" threshold)
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
         [{:seon.config.eval.result/blob-threshold (long threshold)
           :seon.render.value/max-collection 8}])
        (let [results
              (mapv
               (fn [index]
                 (:blob.followup/result-edn
                  (shape :scalar 420 (str threshold "-" index))))
               (range 50))
              timings
              (mapv
               (fn [result-edn]
                 (:milliseconds
                  (elapsed-ms
                   #(#'loop/settlement-result
                     {:seon.store/branch-connection connection
                      :seon.sci.admit/caps caps}
                     {:seon.cluster.eval/result-edn result-edn}))))
               results)]
          {:blob.followup/threshold threshold
           :blob.followup/n (count timings)
           :blob.followup/result-size-min (apply min (map count results))
           :blob.followup/result-size-max (apply max (map count results))
           :blob.followup/total-ms (reduce + timings)
           :blob.followup/median-ms (percentile 0.5 timings)
           :blob.followup/p95-ms (percentile 0.95 timings)
           :blob.followup/min-ms (apply min timings)
           :blob.followup/max-ms (apply max timings)})
        (finally
          (d/release connection))))))

(let [root (str "tmp/blob-followup-measure-" (random-uuid))
      shapes
      (for [shape-name [:scalar :one-item-vector :wide-vector]
            payload-size [50 100 200 300 400 500 750 1000 2000 4000]]
        (shape shape-name payload-size "measured"))]
  (try
    (prn
     {:blob.followup/full-shape-cells
      (mapv (fn [[shape-value blob?]]
              (full-shape-cell root shape-value blob?))
            (for [shape-value shapes blob? [false true]]
              [shape-value blob?]))
      :blob.followup/latency
      [(latency-cell root 343)
       (latency-cell root 4096)]})
    (finally
      (fs/delete-recursively! root root)
      (shutdown-agents))))
