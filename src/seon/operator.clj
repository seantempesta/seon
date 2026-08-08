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
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [clojure.java.io :as io]
            [datahike.api :as d]
            [konserve.core :as k]
            [seon.cluster :as cluster]
            [seon.cluster.registry :as registry]
            [seon.cluster.store :as store]
            [seon.db :as db]
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
    ephemeral-owner :seon.operator/ephemeral-owner
    cluster-name :seon.boot/cluster-name}]
  (attempt #(state/claim-root! repository-root managed-root
                               ephemeral-owner cluster-name)))

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

(defn- cleanup-root-under-lock!
  [repository-root managed-root]
  (let [managed-root (.getCanonicalPath (io/file managed-root))
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
      (state/mark-root-destroyed-under-lock!
       repository-root managed-root result)
      (when-not complete?
        (throw (ex-info "Managed root cleanup left residual paths."
                        {:seon.operator/cleanup result})))
      result))

(defn cleanup-root!
  "Unconditionally remove a managed root after exact processes are gone."
  {:malli/schema
   [:=> [:cat :seon.operator/cleanup-request]
    [:or :seon.operator/cleanup-result :seon.error/value]]}
  [{repository-root :seon.operator/repository-root
    managed-root :seon.operator/managed-root}]
  (attempt
   #(state/with-control-lock!
     repository-root
     (fn [] (cleanup-root-under-lock! repository-root managed-root)))))

(defn- public-stopped-process
  [stopped]
  {:seon.dev.process/generation
   (:seon.operator.process-record/generation stopped)
   :seon.dev.process/pid (:seon.boot/pid stopped)
   :seon.dev.process/start-instant
   (str (.toInstant ^java.util.Date (:seon.boot/start-instant stopped)))
   :seon.operator.reap/stop-path (:seon.operator.reap/stop-path stopped)})

(defn- refusal
  [claim reason message]
  {:seon.operator.claim/id (:seon.operator.claim/id claim)
   :seon.operator.reap/reason reason
   :seon.error/message message})

(defn reap-dead-roots!
  "Stop and remove explicitly ephemeral roots whose exact creator is dead."
  {:malli/schema
   [:=> [:cat :seon.operator.reap/request]
    [:or :seon.operator.reap/result :seon.error/value]]}
  [{repository-root :seon.operator/repository-root
    caller-root :seon.operator/managed-root
    :as request}]
  (attempt
   (fn []
     (state/with-control-lock!
      repository-root
      (fn []
       (let [silence-ms (state/event-silence-backstop-ms
                         repository-root request)
             observed (state/census-observations request)
             census (state/process-census request)
             claims (:seon.operator.state/roots observed)
             processes-by-root
             (group-by :seon.operator.state/root
                       (:seon.operator.state/processes observed))
             unclaimed-roots
             (into #{} (map :seon.operator.state/root)
                   (:seon.operator.state/unclaimed observed))
             candidates
             (filter #(and (:seon.operator.claim/reap-on-owner-exit? %)
                           (not (:seon.operator.claim/destroyed-at %)))
                     claims)
             initial
             (reduce
              (fn [result claim]
                (let [root (:seon.operator.claim/root claim)
                      creator (:seon.operator.claim/creator claim)]
                  (cond
                    (= (state/canonical-path caller-root) root)
                    (update result :refused conj
                            (refusal claim :seon.operator.reap/current-root
                                     "The reaper cannot remove its own root."))

                    (state/process-identity-alive? creator)
                    result

                    (contains? unclaimed-roots root)
                    (update result :refused conj
                            (refusal claim :seon.operator.reap/unclaimed-process
                                     "An observed process has no exact claim."))

                    :else
                    (update result :eligible conj claim))))
              {:eligible [] :refused []}
              candidates)
             stopped
             (mapcat
              (fn [claim]
                (map
                 #(state/stop-recorded-process-under-lock!
                   repository-root
                   (:seon.operator.state/process-record %)
                   (:seon.operator.state/advertisements observed)
                   silence-ms)
                 (get processes-by-root (:seon.operator.claim/root claim))))
              (:eligible initial))
             after (state/census-observations request)
             live-roots
             (into #{}
                   (comp
                    (filter :seon.operator.state/alive?)
                    (map :seon.operator.state/root))
                   (:seon.operator.state/processes after))
             answering-roots
             (into #{}
                   (comp
                    (filter :seon.operator.state/alive?)
                    (map :seon.operator.state/root))
                   (:seon.operator.state/advertisements after))
             cleanup-ready
             (remove #(contains? (into live-roots answering-roots)
                                 (:seon.operator.claim/root %))
                     (:eligible initial))
             newly-refused
             (for [claim (:eligible initial)
                   :when (not (some #{claim} cleanup-ready))]
               (refusal claim :seon.operator.reap/process-remained
                        "An exact process or advertisement remains alive."))
             roots
             (mapv
              (fn [claim]
                (let [cleanup (cleanup-root-under-lock!
                               repository-root
                               (:seon.operator.claim/root claim))]
                  {:seon.operator.claim/id (:seon.operator.claim/id claim)
                   :seon.operator.claim/root
                   (:seon.operator.claim/root claim)
                   :seon.operator.cleanup/reclaimed-bytes
                   (:seon.operator.cleanup/removed-file-bytes cleanup)}))
              cleanup-ready)
             refused (into (:refused initial) newly-refused)
             result
             {:seon.operator.reap/observed-at (java.util.Date.)
              :seon.operator.reap/census census
              :seon.operator.reap/eligible-root-claims
              (mapv :seon.operator.claim/id (:eligible initial))
              :seon.operator.reap/stopped-processes
              (mapv public-stopped-process stopped)
              :seon.operator.reap/roots roots
              :seon.operator.reap/refused (vec refused)
              :seon.operator.reap/reclaimed-bytes
              (reduce + 0 (map :seon.operator.cleanup/reclaimed-bytes roots))
              :seon.operator.reap/complete? (empty? refused)}]
         (when (seq (:seon.operator.state/claim-errors observed))
           (throw
            (ex-info "The reaper cannot read every external claim."
                     {:seon.error/kind :seon.operator/reap-incomplete
                      :seon.operator.reap/result result})))
         (when (seq refused)
           (throw
            (ex-info "One or more ephemeral roots were refused."
                     {:seon.error/kind :seon.operator/reap-incomplete
                      :seon.operator.reap/result result})))
         result))))))

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

(defn- store-dir
  [managed-root]
  (.getCanonicalPath
   (io/file managed-root "data" "clusters" "store")))

(defn- valid-store?
  [value]
  (and (map? value)
       (some-> ^java.nio.channels.FileLock (:seon.store/lock value)
               .isValid)))

(defn- acquire-operation-store!
  [managed-root supplied]
  (let [path (store-dir managed-root)
        held (some-> (get @runtime/root-store-holder path)
                     :seon.store/store)]
    (cond
      (valid-store? supplied) [supplied false]
      (valid-store? held) [held false]
      :else [(store/open-store! {:seon.store/dir path}) true])))

(declare collect-under-lock!)

(defn- cleanup-cluster-under-lock!
  [{repository-root :seon.operator/repository-root
    managed-root :seon.operator/managed-root
    cluster-name :seon.boot/cluster-name
    supplied-store :seon.store/store}]
  (let [managed-root (state/canonical-path managed-root)
        claim (state/root-claim repository-root managed-root)]
    (when-not (and claim
                   (contains? (:seon.operator.claim/clusters claim)
                              cluster-name))
      (throw
       (ex-info "The cluster has no exact external root claim."
                {:seon.error/kind
                 :seon.operator/cluster-cleanup-incomplete
                 :seon.operator.claim/root managed-root
                 :seon.boot/cluster-name cluster-name})))
    (let [cluster-root (.getCanonicalPath
                        (io/file managed-root "data" "clusters"))
          paths (cluster/cluster-paths cluster-root cluster-name)
          cluster-dir (:seon.boot/cluster-dir paths)
          before (state/footprint cluster-dir)
          instance (get @runtime/running-instances cluster-name)
          stopped? (map? instance)
          _ (when stopped? (cluster/stop! instance))
          [operation-store release?]
          (acquire-operation-store! managed-root supplied-store)]
      (try
        (let [branch (registry/cluster-branch cluster-name)
              _ (registry/retire-branch!
                 {:seon.store/store operation-store
                  :seon.store/branch branch})
              present? (.exists (io/file cluster-dir))
              _ (when present?
                  (fs/delete-recursively! cluster-root cluster-dir))
              collection (collect-under-lock! managed-root operation-store)
              remaining (cond-> []
                          (.exists (io/file cluster-dir))
                          (conj cluster-dir)
                          (contains? (registry/roster operation-store)
                                     branch)
                          (conj (str branch)))
              complete? (empty? remaining)
              result
              {:seon.operator.cluster-cleanup/managed-root managed-root
               :seon.boot/cluster-name cluster-name
               :seon.store/branch branch
               :seon.operator.cluster-cleanup/live-instance-stopped?
               stopped?
               :seon.operator.cluster-cleanup/branch-retired? true
               :seon.operator.cluster-cleanup/removed
               (if present? [cluster-dir] [])
               :seon.operator.cluster-cleanup/collection collection
               :seon.operator.cluster-cleanup/remaining remaining
               :seon.operator.cluster-cleanup/reclaimed-bytes
               (:seon.operator.footprint/file-bytes before)
               :seon.operator.cluster-cleanup/complete? complete?}]
          (when-not complete?
            (throw
             (ex-info "Cluster cleanup left claimed state."
                      {:seon.error/kind
                       :seon.operator/cluster-cleanup-incomplete
                       :seon.operator.cluster-cleanup/result result})))
          result)
        (finally
          (when release? (store/release-store! operation-store)))))))

(defn cleanup-cluster!
  "Stop, retire, delete, and collect one exactly claimed cluster."
  {:malli/schema
   [:=> [:cat :seon.operator.cluster-cleanup/request]
    [:or :seon.operator.cluster-cleanup/result :seon.error/value]]}
  [{repository-root :seon.operator/repository-root :as request}]
  (attempt
   #(state/with-control-lock!
     repository-root
     (fn [] (cleanup-cluster-under-lock! request)))))

(defn- operation-konserve
  [operation-store]
  (:store @(:seon.store/connection-object operation-store)))

(defn- collection-observation
  [operation-store]
  {:seon.operator.collect/objects
   (count (k/keys (operation-konserve operation-store) {:sync? true}))
   :seon.operator.collect/bytes
   (:seon.operator.footprint/file-bytes
    (state/footprint (:seon.store/dir operation-store)))})

(defn- resolves-to-digest?
  [forms form]
  (loop [current form
         visited #{}]
    (cond
      (= :seon.blob/digest current) true
      (or (not (keyword? current))
          (contains? visited current)
          (not (contains? forms current))) false
      :else (recur (get forms current) (conj visited current)))))

(defn- digest-attributes
  [database]
  (let [serialized
        (db/q '[:find ?attribute ?form
                :where
                [?schema :seon.schema/key ?attribute]
                [?schema :seon.schema/form ?form]]
              database)
        forms (into {} (map (fn [[schema-key form]]
                              [schema-key (edn/read-string form)]))
                    serialized)]
    (into []
          (keep (fn [[attribute form]]
                  (when (resolves-to-digest? forms (edn/read-string form))
                    attribute)))
          serialized)))

(defn- branch-digests
  [operation-store branch]
  (let [database (d/branch-as-db
                  (:seon.store/connection-object operation-store) branch)]
    (try
      (let [history-value (db/history database)
            searchable (if (error-value? history-value)
                         database
                         history-value)]
        (into #{}
              (mapcat
               (fn [attribute]
                 (db/q '[:find [?digest ...]
                         :in $ ?attribute
                         :where [_ ?attribute ?digest]]
                       searchable attribute)))
              (digest-attributes database)))
      (finally
        (d/release-materialized-db database)))))

(defn- collection-evidence
  [operation-store]
  (let [branches (sort-by str (registry/roster operation-store))]
    {:seon.operator.collect/branches
     (mapv (fn [branch]
             {:seon.store/branch branch
              :seon.source/commit-id
              (registry/branch-commit-id
               {:seon.store/store operation-store
                :seon.store/branch branch})})
           branches)
     :seon.operator.collect/digests
     (into #{} (mapcat #(branch-digests operation-store %)) branches)}))

(defn- evidence-reopens?
  [operation-store evidence]
  (and
   (every?
    (fn [{branch :seon.store/branch
          expected :seon.source/commit-id}]
      (when expected
        (let [database
              (d/branch-as-db
               (:seon.store/connection-object operation-store) branch)]
          (try
            (= expected (d/commit-id database))
            (finally
              (d/release-materialized-db database))))))
    (:seon.operator.collect/branches evidence))
   (every?
    (fn [digest]
      (true?
       (k/bget (operation-konserve operation-store)
               digest
               (fn [{input :input-stream}]
                 ;; Force one physical read. EOF is a valid empty blob, so
                 ;; successful callback entry—not a positive byte—is proof.
                 (.read ^java.io.InputStream input)
                 true)
               {:sync? true})))
    (:seon.operator.collect/digests evidence))))

(defn- incomplete-collection!
  [result failure]
  (throw
   (ex-info
    "Collection did not preserve and verify every recorded root."
    {:seon.error/kind :seon.operator/collection-incomplete
     :seon.operator.collect/result result}
    failure)))

(def ^:private projected-delete-ms-per-file
  ;; The sealed runbook's middle projection: one existence check, delete, and
  ;; FileStore sync per candidate at an illustrative five milliseconds.
  5)

(defn- dry-run-under-lock!
  [managed-root operation-store]
  (let [inventory
        (registry/collect!
         operation-store
         (java.util.Date.)
         {:seon.operator.collect/dry-run? true})
        retained (:seon.cluster.registry/retained-files inventory)
        candidates (:seon.cluster.registry/candidate-files inventory)
        files (+ retained candidates)
        file-bytes (:seon.cluster.registry/file-bytes inventory)
        mark-duration (:seon.cluster.registry/mark-duration-ms inventory)]
    {:seon.operator.collect/store-id
     (get-in @(:seon.store/connection-object operation-store)
             [:config :store :id])
     :seon.operator.collect/managed-root managed-root
     :seon.operator.collect/branches
     (:seon.cluster.registry/branches inventory)
     :seon.operator.collect/objects-before files
     :seon.operator.collect/objects-after files
     :seon.operator.collect/swept-objects 0
     :seon.operator.collect/bytes-before file-bytes
     :seon.operator.collect/bytes-after file-bytes
     :seon.operator.collect/reclaimed-bytes 0
     :seon.operator.collect/verification-pass-swept 0
     :seon.operator.collect/complete? true
     :seon.operator.collect/dry-run? true
     :seon.operator.collect/retained-files retained
     :seon.operator.collect/candidate-files candidates
     :seon.operator.collect/candidate-bytes
     (:seon.cluster.registry/candidate-bytes inventory)
     :seon.operator.collect/mark-duration-ms mark-duration
     :seon.operator.collect/projected-duration-ms
     (+ mark-duration (* projected-delete-ms-per-file candidates))}))

(defn- collect-under-lock!
  [managed-root operation-store]
  (let [store-id
        (get-in @(:seon.store/connection-object operation-store)
                [:config :store :id])
        before (collection-observation operation-store)
        base-result
        {:seon.operator.collect/store-id store-id
         :seon.operator.collect/managed-root managed-root
         :seon.operator.collect/branches []
         :seon.operator.collect/objects-before
         (:seon.operator.collect/objects before)
         :seon.operator.collect/objects-after
         (:seon.operator.collect/objects before)
         :seon.operator.collect/swept-objects 0
         :seon.operator.collect/bytes-before
         (:seon.operator.collect/bytes before)
         :seon.operator.collect/bytes-after
         (:seon.operator.collect/bytes before)
         :seon.operator.collect/reclaimed-bytes 0
         :seon.operator.collect/verification-pass-swept 0
         :seon.operator.collect/complete? false}]
    (try
      (let [swept (registry/collect! operation-store (java.util.Date.))
            after-first (collection-observation operation-store)
            first-result
            (assoc base-result
                   :seon.operator.collect/objects-after
                   (:seon.operator.collect/objects after-first)
                   :seon.operator.collect/swept-objects swept
                   :seon.operator.collect/bytes-after
                   (:seon.operator.collect/bytes after-first)
                   :seon.operator.collect/reclaimed-bytes
                   (max 0 (- (:seon.operator.collect/bytes before)
                             (:seon.operator.collect/bytes after-first))))]
        (try
          (let [verification-swept
                (registry/collect! operation-store (java.util.Date.))
                after (collection-observation operation-store)
                evidence (collection-evidence operation-store)
                complete? (and (zero? verification-swept)
                               (evidence-reopens? operation-store evidence))
                result
                (assoc first-result
                       :seon.operator.collect/branches
                       (:seon.operator.collect/branches evidence)
                       :seon.operator.collect/objects-after
                       (:seon.operator.collect/objects after)
                       :seon.operator.collect/bytes-after
                       (:seon.operator.collect/bytes after)
                       :seon.operator.collect/reclaimed-bytes
                       (max 0 (- (:seon.operator.collect/bytes before)
                                 (:seon.operator.collect/bytes after)))
                       :seon.operator.collect/verification-pass-swept
                       verification-swept
                       :seon.operator.collect/complete? complete?)]
            (if complete?
              result
              (incomplete-collection! result nil)))
          (catch Throwable failure
            (if (= :seon.operator/collection-incomplete
                   (:seon.error/kind (ex-data failure)))
              (throw failure)
              (incomplete-collection! first-result failure)))))
      (catch Throwable failure
        (if (= :seon.operator/collection-incomplete
               (:seon.error/kind (ex-data failure)))
          (throw failure)
          (incomplete-collection! base-result failure))))))

(defn collect!
  "Collect or dry-run one managed store."
  {:malli/schema
   [:=> [:cat :seon.operator.collect/request]
    [:or :seon.operator.collect/result :seon.error/value]]}
  [{repository-root :seon.operator/repository-root
    managed-root :seon.operator/managed-root
    dry-run? :seon.operator.collect/dry-run?}]
  (attempt
   #(state/with-control-lock!
     repository-root
     (fn []
       (let [managed-root (state/canonical-path managed-root)
             [operation-store release?]
             (acquire-operation-store! managed-root nil)]
         (try
           (if (true? dry-run?)
             (dry-run-under-lock! managed-root operation-store)
             (collect-under-lock! managed-root operation-store))
           (finally
             (when release? (store/release-store! operation-store)))))))))

(defn- refork-under-lock!
  [{managed-root :seon.operator/managed-root
    source-commit :seon.source/commit-id
    supplied-store :seon.store/store
    :as request}]
  ;; No store is held ACROSS the destructive arm. Cleanup stops the live
  ;; instance, and the last instance out releases the process-root store
  ;; with its flock (`seon.cluster/release-root-store!`), so a store
  ;; captured before cleanup can be a released connection by the time the
  ;; fork needs it — the whole refork then failed with
  ;; `:connection-has-been-released` after the old branch was already
  ;; destroyed.
  ;;
  ;; So the fork RE-ASKS after the destructive arm rather than reusing a
  ;; captured value, and `acquire-operation-store!` answers from the one
  ;; fact that says whether a store is live: its flock's validity. A
  ;; supplied store that cleanup released fails that test and the fork
  ;; opens a fresh one; a supplied store cleanup left alone passes it and
  ;; is reused. Passing `nil` here instead lost the second case, and
  ;; `bin/seon init NAME --force` — whose child JVM opens the store
  ;; itself, outside the running-instance holder table — destroyed the
  ;; branch and then refused its own second open of a store it still
  ;; held.
  (cleanup-cluster-under-lock! request)
  (let [[operation-store release?]
        (acquire-operation-store! managed-root supplied-store)]
    (try
      (registry/ensure-cluster!
       {:seon.store/store operation-store
        :seon.boot/cluster-name (:seon.boot/cluster-name request)
        :seon.source/commit-id source-commit})
      (finally
        (when release? (store/release-store! operation-store))))))

(defn refork!
  "Compose the one cluster cleanup with an exact source refork."
  {:malli/schema
   [:=> [:cat :seon.operator/refork-request]
    [:or :seon.cluster.registry/branch-result :seon.error/value]]}
  [{repository-root :seon.operator/repository-root :as request}]
  (attempt
   #(state/with-control-lock!
     repository-root
     (fn [] (refork-under-lock! request)))))
