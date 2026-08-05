(ns seon.operator
  "In-JVM operator verbs for a terminal or editor REPL.

  Attach to any advertised cluster io-prepl with
  `rlwrap nc <advertised-host> <advertised-prepl-port>`, require this
  namespace, and call these ordinary functions. The attached prepl is the
  complete terminal/editor control story for this slice; no nREPL server or
  namespace-refresh mechanism is involved.

  This namespace owns the operations contracts shared by operator commands and
  scheduled maintenance. Protected atomic claim records remain outside the
  cluster program graph in `seon.operator.state`; cluster, registry,
  source-publication, and Flow state stay with their existing owners. In-JVM
  `status` describes this JVM's current instances; the foreign-process
  `bin/seon status` additionally reconciles process records and possibly stale
  advertisements."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.fs :as fs]
            [seon.operator.runtime :as runtime]
            [seon.operator.state :as state]
            [seon.schema.edn :as schema.edn])
  (:import [java.io RandomAccessFile]
           [java.nio.file Files StandardCopyOption]))

(schema.edn/load! {})

(defn- flat-error
  [error]
  (let [data (ex-data error)]
    {:seon.error/kind (or (:seon.error/kind data) ::failed)
     :seon.error/message (or (ex-message error)
                             "The operator call failed.")
     :seon.error/data (or data {})}))

(defn- attempt
  [f]
  (try
    (f)
    (catch Throwable error
      (flat-error error))))

(defn- error-value?
  [value]
  (and (map? value)
       (keyword? (:seon.error/kind value))
       (string? (:seon.error/message value))))

(defn start!
  "Start one named cluster in this JVM."
  {:malli/schema
   [:=> [:cat :seon.boot/start-request]
    [:or :seon.boot/instance :seon.error/value]]}
  [request]
  (attempt #(cluster/start! request)))

(defn stop!
  "Stop one addressed cluster instance."
  {:malli/schema
   [:=> [:cat :seon.boot/instance]
    [:or :nil :seon.error/value]]}
  [instance]
  (attempt #(cluster/stop! instance)))

(defn restart!
  "Stop and start one addressed cluster instance."
  {:malli/schema
   [:=> [:cat :seon.boot/instance]
    [:or :seon.boot/instance :seon.error/value]]}
  [instance]
  (let [stopped (stop! instance)]
    (if (error-value? stopped)
      stopped
      (start! (:seon.boot/config instance)))))

(defn status
  "Derived readiness and Flow observations for this JVM's clusters."
  {:malli/schema [:=> [:cat] [:or :seon.operator/status :seon.error/value]]}
  []
  (attempt
   #(let [cluster-names (sort (keys @runtime/running-instances))]
      {:seon.operator/clusters
       (mapv
        (fn [cluster-name]
          (let [observation (cluster/mcp-runtime-observation cluster-name)]
            (cond->
             {:seon.boot/cluster-name cluster-name
              :seon.operator/health (:seon.dev.mcp/health observation)
              :seon.operator/flow (:seon.dev.mcp/flow observation)}
              (:seon.dev.mcp/readiness observation)
              (assoc :seon.operator/readiness
                     (:seon.dev.mcp/readiness observation)))))
        cluster-names)})))

(defn banner
  "Human-readable readiness for this JVM's clusters."
  {:malli/schema [:=> [:cat] [:or :string :seon.error/value]]}
  []
  (let [current (status)]
    (if (error-value? current)
      current
      (attempt
       #(str/join
         "\n\n"
         (keep (fn [{ready :seon.operator/readiness}]
                 (when ready (cluster/banner ready)))
               (:seon.operator/clusters current)))))))

(defn clusters
  "The held branch roster and live advertisements for this JVM."
  {:malli/schema [:=> [:cat] [:or :seon.operator/census :seon.error/value]]}
  []
  (attempt
   #(let [instances (vals @runtime/running-instances)
          stores (keep :seon.store/store
                       (vals @runtime/root-store-holder))]
      {:seon.operator/advertisements
       (->> instances
            (filter map?)
            (keep :seon.boot/advertisement)
            (sort-by :seon.boot/cluster-name)
            vec)
       :seon.operator/branches
       (into #{} (mapcat registry/roster) stores)})))

(defn claim-root!
  "Publish an external root claim before any managed path is created."
  {:malli/schema
   [:=> [:cat :seon.operator/root-request]
    [:or :map :seon.error/value]]}
  [{repository-root :seon.operator/repository-root
    managed-root :seon.operator/managed-root
    ephemeral? :seon.operator/ephemeral?
    cluster-name :seon.boot/cluster-name}]
  (attempt #(state/claim-root! repository-root managed-root
                               ephemeral? cluster-name)))

(defn existence
  "Return the claim-first root/store/cluster census without opening Datahike."
  {:malli/schema
   [:=> [:cat :seon.operator/existence-request]
    [:or :seon.operator/existence :seon.error/value]]}
  [{repository-root :seon.operator/repository-root}]
  (attempt #(state/existence repository-root)))

(defn census-processes!
  "Observe external claims, exact OS identities, and advertisements."
  {:malli/schema
   [:=> [:cat :seon.operator/process-census-request]
    [:or :seon.operator.process-census/result :seon.error/value]]}
  [request]
  (attempt
   #(let [result (state/process-census request)]
      (if (:seon.operator.process-census/complete? result)
        result
        (throw
         (ex-info
          "The process census could not read every external claim."
          {:seon.error/kind :seon.operator/process-census-incomplete
           :seon.operator.process-census/result result}))))))

(defn- low-space?
  [footprint request]
  (or (when-let [minimum (:seon.config.maintenance/min-usable-bytes request)]
        (< (:seon.operator.footprint/usable-bytes footprint) minimum))
      (when-let [minimum (:seon.config.maintenance/min-usable-ratio request)]
        (< (:seon.operator.footprint/usable-ratio footprint) minimum))))

(defn observe-footprint!
  "Record and return one managed root's current disk footprint."
  {:malli/schema
   [:=> [:cat :seon.operator/footprint-request]
    [:or :seon.operator/footprint-observation :seon.error/value]]}
  [{repository-root :seon.operator/repository-root
    managed-root :seon.operator/managed-root
    :as request}]
  (attempt
   ;; low-space? reads only the statfs fields, which the recorded
   ;; observation already carries for the same volume — a second
   ;; recursive walk of the whole managed root bought nothing.
   #(let [observation (state/record-footprint! repository-root managed-root)]
      (assoc observation
             :seon.operator/low-space?
             (boolean (low-space? observation request))))))

(defn- existing-children
  [path]
  (let [file (io/file path)]
    (if (.isDirectory file)
      (mapv #(.getCanonicalPath ^java.io.File %) (or (.listFiles file) []))
      [])))

(defn cleanup-root!
  "Unconditionally remove a managed root's database, scratch, and logs.

  Authorization is the explicit call itself. This function never opens the
  database or consults liveness claims; callers must reap exact processes
  before entering it. The returned contract is success-shaped only when no
  managed path remains."
  {:malli/schema
   [:=> [:cat :seon.operator/cleanup-request]
    [:or :seon.operator/cleanup-result :seon.error/value]]}
  [{repository-root :seon.operator/repository-root
    managed-root :seon.operator/managed-root
    control-lock-held? :seon.operator/control-lock-held?}]
  (attempt
   #(let [managed-root (.getCanonicalPath (io/file managed-root))
          target (.getCanonicalPath (io/file managed-root "data" "clusters"))
          before (state/footprint target)
          present? (.exists (io/file target))
          _ (when present? (fs/delete-recursively! managed-root target))
          remaining (existing-children target)
          complete? (and (empty? remaining) (not (.exists (io/file target))))
          result {:seon.operator.cleanup/root managed-root
                  :seon.operator.cleanup/target target
                  :seon.operator.cleanup/removed (if present? [target] [])
                  :seon.operator.cleanup/removed-file-bytes
                  (:seon.operator.footprint/file-bytes before)
                  :seon.operator.cleanup/remaining remaining
                  :seon.operator.cleanup/complete? complete?}]
      (if control-lock-held?
        ((requiring-resolve
          'seon.operator.state/mark-root-destroyed-under-lock!)
         repository-root managed-root result)
        (state/mark-root-destroyed! repository-root managed-root result))
      (when-not complete?
        (throw (ex-info "Managed root cleanup left residual paths."
                        {:seon.operator/cleanup result})))
      result)))

(defn- archive-path
  [log-path index]
  (str log-path "." index))

(defn rotate-logs!
  "Bound one live log while preserving its inode for the detached JVM."
  {:malli/schema
   [:=> [:cat :seon.operator/log-request]
    [:or :seon.operator/log-result :seon.error/value]]}
  [{log-dir :seon.boot/log-dir
    max-bytes :seon.config.maintenance/log-max-bytes
    retained :seon.config.maintenance/log-retained-files}]
  (attempt
   #(let [log-file (io/file log-dir "seon.log")
          log-path (.getCanonicalPath log-file)
          before (if (.isFile log-file) (.length log-file) 0)
          rotate? (> before max-bytes)]
      (when rotate?
        (doseq [index (range retained 1 -1)]
          (let [from (io/file (archive-path log-path (dec index)))
                to (io/file (archive-path log-path index))]
            (when (.isFile from)
              (Files/move (.toPath from) (.toPath to)
                          (into-array java.nio.file.CopyOption
                                      [StandardCopyOption/REPLACE_EXISTING])))))
        (when (pos? retained)
          (Files/copy (.toPath log-file)
                      (.toPath (io/file (archive-path log-path 1)))
                      (into-array java.nio.file.CopyOption
                                  [StandardCopyOption/REPLACE_EXISTING])))
        ;; The detached process keeps this inode open. Truncating it bounds
        ;; future writes immediately; renaming it would strand the live fd.
        (with-open [file (RandomAccessFile. log-file "rw")]
          (.setLength file 0)))
      {:seon.operator.log/path log-path
       :seon.operator.log/bytes-before before
       :seon.operator.log/bytes-after (if (.isFile log-file)
                                        (.length log-file) 0)
       :seon.operator.log/rotated? rotate?
       :seon.operator.log/retained-files retained})))

(defn publish!
  "Publish the current source tree onto `current-src`."
  {:malli/schema
   [:=> [:cat :seon.operator/publish-request]
    [:or :seon.source/published :seon.error/value]]}
  [{root :seon.boot/root changed-paths :seon.operator/changed-paths}]
  (attempt
   #(if changed-paths
      (cluster/refresh-source! root changed-paths)
      (cluster/refresh-source! root))))

(defn refork!
  "Destroy and refork one addressed cluster branch."
  {:malli/schema
   [:=> [:cat :seon.boot/instance]
    [:or :seon.cluster.registry/branch-result :seon.error/value]]}
  [instance]
  (attempt #(cluster/refork! instance)))
