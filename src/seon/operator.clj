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
            [seon.env :as env]
            [seon.fs :as fs]
            [seon.operator.runtime :as runtime]
            [seon.operator.state :as state]
            [seon.schema.edn :as schema.edn])
  (:import [java.io RandomAccessFile]
           [java.nio.file Files StandardCopyOption]))

(schema.edn/load! {})

(defn- flat-error
  [error]
  (let [data (ex-data error)
        kind (or (:seon.error/kind data) ::failed)]
    (merge (or data {})
           {kind true
            :seon.error/kind kind
            :seon.error/message (or (ex-message error)
                                    "The operator call failed.")
            :seon.error/data (or data {})})))

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

(defn- lifecycle-lock-bound-ms
  [request]
  (or (:seon.config.operator/event-silence-backstop-ms request)
      state/lifecycle-lock-timeout-ms))

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

(defn- custody-environments
  []
  (into (sorted-map)
        (keep (fn [[cluster-name instance]]
                (when-let [environment
                           (some-> instance
                                   :seon.turn.loop/cluster
                                   env/of)]
                  [cluster-name environment])))
        @runtime/running-instances))

(defn- custody-selection-error
  [kind message cluster-names requested-cluster]
  (cond-> {kind true
           :seon.error/kind kind
           :seon.error/message message
           :seon.error/data {::candidate-clusters cluster-names}}
    requested-cluster
    (assoc-in [:seon.error/data ::requested-cluster] requested-cluster)))

(defn- selected-environment
  [requested-cluster]
  (let [environments (custody-environments)
        cluster-names (vec (keys environments))]
    (cond
      requested-cluster
      (or (get environments requested-cluster)
          (custody-selection-error
           ::cluster-custody-unavailable
           (str "Cluster " (pr-str requested-cluster)
                " supplies no live custody at this development REPL; "
                "live clusters with custody are " (pr-str cluster-names) ".")
           cluster-names
           requested-cluster))

      (= 1 (count environments))
      (val (first environments))

      (empty? environments)
      (custody-selection-error
       ::cluster-custody-unavailable
       (str "No live cluster supplies custody at this development REPL; "
            "live clusters with custody are [].")
       cluster-names
       nil)

      :else
      (custody-selection-error
       ::ambiguous-cluster-custody
       (str "Cluster custody is ambiguous at this development REPL; "
            "live clusters with custody are " (pr-str cluster-names)
            ". Pass one name to seon.operator/connection.")
       cluster-names
       nil))))

(defn- selected-connection
  [cluster-name]
  (let [environment (selected-environment cluster-name)]
    (if (error-value? environment)
      environment
      (db/supplied-connection environment))))

(defn connection
  "Return one selected live cluster's connection for development."
  {:malli/schema
   [:function
    [:=> [:cat] [:or :seon.db/connection :seon.error/value]]
    [:=> [:cat :seon.boot/cluster-name]
     [:or :seon.db/connection :seon.error/value]]]}
  ([]
   (selected-connection nil))
  ([cluster-name]
   (selected-connection cluster-name)))

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
           :seon.operator.process-census/result result :seon.operator/process-census-incomplete true}))))))

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

(defn- cleanup-root-under-lock!
  {:seon.fn/destroys
   "an operator root's clusters, store, store lock and staged blobs"}
  [repository-root managed-root]
  (state/cleanup-root-under-lock!
   repository-root managed-root (store/declared-operator-root)))

(defn cleanup-root!
  "Unconditionally remove a managed root after exact processes are gone."
  {:malli/schema
   [:=> [:cat :seon.operator/cleanup-request]
    [:or :seon.operator/cleanup-result :seon.error/value]]}
  [{repository-root :seon.operator/repository-root
    managed-root :seon.operator/managed-root
    :as request}]
  ;; Deliberate keep-serial exception: the launcher already holds the root
  ;; lifecycle lock while its child calls cleanup-root-under-lock!, so moving
  ;; this public JVM path there requires explicit lock-custody transfer.
  (attempt
   #(let [bound-ms (lifecycle-lock-bound-ms request)]
      (state/with-control-lock!
       repository-root
       {:seon.operator.lock/command "cleanup managed root"
        :seon.operator.lock/acquisition-timeout-ms bound-ms
        :seon.operator.lock/hold-timeout-ms bound-ms}
       (fn [] (cleanup-root-under-lock! repository-root managed-root))))))

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

(defn- claim-error-id
  [claim-error]
  (let [path (:seon.operator.claim/path claim-error)
        filename (.getName (io/file path))
        suffix ".edn"
        encoded-id (if (str/ends-with? filename suffix)
                     (subs filename 0 (- (count filename) (count suffix)))
                     filename)]
    (try
      (java.util.UUID/fromString encoded-id)
      (catch IllegalArgumentException _
        (java.util.UUID/nameUUIDFromBytes
         (.getBytes ^String (str path)
                    java.nio.charset.StandardCharsets/UTF_8))))))

(defn- claim-error-refusal
  [claim-error]
  (cond->
   {:seon.operator.claim/id (claim-error-id claim-error)
    :seon.operator.reap/reason :seon.operator.reap/unreadable-claim
    :seon.error/message (:seon.error/message claim-error)
    :seon.operator.claim/path (:seon.operator.claim/path claim-error)
    :seon.operator.claim/invalid-cause
    (:seon.operator.claim/invalid-cause claim-error)}
    (:seon.operator.claim/root claim-error)
    (assoc :seon.operator.claim/root
           (:seon.operator.claim/root claim-error))))

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
     (let [bound-ms (lifecycle-lock-bound-ms request)]
       (state/with-control-lock!
        repository-root
        {:seon.operator.lock/command "reap dead managed roots"
         :seon.operator.lock/acquisition-timeout-ms bound-ms
         :seon.operator.lock/hold-timeout-ms bound-ms}
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
             claim-refusals
             (mapv claim-error-refusal
                   (:seon.operator.state/claim-errors observed))
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
             blocking-refused (into (:refused initial) newly-refused)
             refused (into claim-refusals blocking-refused)
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
         (when (seq blocking-refused)
           (throw
            (ex-info "One or more ephemeral roots were refused."
                     {:seon.error/kind :seon.operator/reap-incomplete
                      :seon.operator.reap/result result :seon.operator/reap-incomplete true})))
         result)))))))

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
   (io/file managed-root "data" "store")))

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

(declare collect-store!)

(defn- quiesce-cluster-under-lock!
  [{repository-root :seon.operator/repository-root
    managed-root :seon.operator/managed-root
    cluster-name :seon.boot/cluster-name}]
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
                 :seon.boot/cluster-name cluster-name :seon.operator/cluster-cleanup-incomplete true})))
    (let [cluster-root (.getCanonicalPath
                        (io/file managed-root "data" "clusters"))
          paths (cluster/cluster-paths cluster-root cluster-name)
          cluster-dir (:seon.boot/cluster-dir paths)
          before (state/footprint cluster-dir)
          instance (get @runtime/running-instances cluster-name)
          stopped? (map? instance)
          _ (when stopped? (cluster/stop! instance))
          present? (.exists (io/file cluster-dir))]
      (when present?
        (fs/delete-recursively! cluster-root cluster-dir))
      {:seon.operator.cluster-cleanup/managed-root managed-root
       :seon.boot/cluster-name cluster-name
       :seon.store/branch (registry/cluster-branch cluster-name)
       :seon.operator.cluster-cleanup/live-instance-stopped? stopped?
       :seon.operator.cluster-cleanup/removed (if present? [cluster-dir] [])
       :seon.operator.cluster-cleanup/reclaimed-bytes
       (:seon.operator.footprint/file-bytes before)
       ::cluster-dir cluster-dir})))

(defn- cleanup-cluster-under-lock!
  [{supplied-store :seon.store/store
    :as request}]
  (let [{branch :seon.store/branch
         :as quiesced} (quiesce-cluster-under-lock! request)
        managed-root (:seon.operator.cluster-cleanup/managed-root quiesced)
        [operation-store release?]
        (acquire-operation-store! managed-root supplied-store)]
    (try
      (registry/retire-branch!
       {:seon.store/store operation-store
        :seon.store/branch branch})
      quiesced
      (finally
        (when release? (store/release-store! operation-store))))))

(defn- finish-cluster-cleanup!
  [{supplied-store :seon.store/store}
   quiesced]
  (let [{cluster-dir ::cluster-dir
         branch :seon.store/branch} quiesced
        managed-root (:seon.operator.cluster-cleanup/managed-root quiesced)
        [operation-store release?]
        (acquire-operation-store! managed-root supplied-store)]
    (try
      (let [collection (collect-store! managed-root operation-store)
            remaining (cond-> []
                        (.exists (io/file cluster-dir))
                        (conj cluster-dir)
                        (contains? (registry/roster operation-store) branch)
                        (conj (str branch)))
            complete? (empty? remaining)
            result (-> quiesced
                       (dissoc ::cluster-dir)
                       (assoc
                        :seon.operator.cluster-cleanup/branch-retired? true
                        :seon.operator.cluster-cleanup/collection collection
                        :seon.operator.cluster-cleanup/remaining remaining
                        :seon.operator.cluster-cleanup/complete? complete?))]
        (when-not complete?
          (throw
           (ex-info "Cluster cleanup left claimed state."
                    {:seon.error/kind
                     :seon.operator/cluster-cleanup-incomplete
                     :seon.operator.cluster-cleanup/result result
                     :seon.operator/cluster-cleanup-incomplete true})))
        result)
      (finally
        (when release? (store/release-store! operation-store))))))

(defn cleanup-cluster!
  "Stop, retire, delete, and collect one exactly claimed cluster."
  {:malli/schema
   [:=> [:cat :seon.operator.cluster-cleanup/request]
    [:or :seon.operator.cluster-cleanup/result :seon.error/value]]}
  [{repository-root :seon.operator/repository-root :as request}]
  ;; Deliberate keep-serial exception, matching cleanup-root!. Only quiesce and
  ;; branch retirement stay under control custody; writer GC runs afterward.
  (attempt
   #(let [bound-ms (lifecycle-lock-bound-ms request)]
      (let [quiesced (state/with-control-lock!
                      repository-root
                      {:seon.operator.lock/command "quiesce claimed cluster"
                       :seon.operator.lock/acquisition-timeout-ms bound-ms
                       :seon.operator.lock/hold-timeout-ms bound-ms}
                      (fn [] (cleanup-cluster-under-lock! request)))]
        (finish-cluster-cleanup! request quiesced)))))

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
  (let [rows
        (d/q '[:find ?attribute ?form
               :where
               [?schema :seon.schema/key ?attribute]
               [?schema :seon.schema/form ?form]]
             database)
        forms (into {} (map (fn [[schema-key form]]
                              [schema-key (edn/read-string form)]))
                    rows)]
    (into []
          (keep (fn [[attribute form]]
                  (when (resolves-to-digest? forms (edn/read-string form))
                    attribute)))
          rows)))

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
                 (d/q '[:find [?digest ...]
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

(defn- branch-reopens?
  [operation-store branch expected]
  (boolean
   (when expected
     (let [database
           (d/branch-as-db
            (:seon.store/connection-object operation-store) branch)]
       (try
         (= expected (d/commit-id database))
         (finally
           (d/release-materialized-db database)))))))

(defn- digest-reads?
  [operation-store digest]
  (true?
   (k/bget (operation-konserve operation-store)
           digest
           (fn [{input :input-stream}]
             ;; Force one physical read. EOF is a valid empty blob, so
             ;; successful callback entry—not a positive byte—is proof.
             (.read ^java.io.InputStream input)
             true)
           {:sync? true})))

(defn- konserve-key-set
  [operation-store]
  (into #{} (map :key) (k/keys (operation-konserve operation-store)
                               {:sync? true})))

(defn- root-verification
  "Verify every recorded root and NAME the first one that fails.

  This is the collection's completeness criterion, and the only one: a
  collection is complete when every roster branch reopens at its recorded
  commit ID and every referenced blob this store HELD BEFORE the sweep still
  reads physically. It answers a value rather than a bare boolean so the
  refusal can say WHICH root — the branch, or the digest — was not preserved.

  It is evaluated unconditionally. Conjoined behind a second-pass fixed
  point, `and` short-circuited and this check never ran, while the refusal's
  message still told the caller that root preservation had been checked and
  had failed.

  `held-before` is what keeps the criterion a DIFFERENTIAL rather than a
  shape test. Referenced digests are derived from every attribute whose
  schema resolves to `:seon.blob/digest`, and some of those attributes carry
  content digests that were never konserve keys at all —
  `:seon.db/read-result-digest` is one, measured on a freshly forked cluster
  on 2026-09-17, where it made this check refuse a collection that had lost
  nothing. A digest this store did not hold before the collection is not a
  root the collection lost: it is counted as
  `:seon.operator.collect/unstored-digests`, never silently dropped and never
  a refusal here."
  [operation-store evidence held-before]
  (let [digests (:seon.operator.collect/digests evidence)
        stored (filterv held-before digests)
        unverified-branch
        (some (fn [{branch :seon.store/branch
                    expected :seon.source/commit-id}]
                (when-not (branch-reopens? operation-store branch expected)
                  branch))
              (:seon.operator.collect/branches evidence))
        unverified-digest
        (when-not unverified-branch
          (some (fn [digest]
                  (when-not (digest-reads? operation-store digest) digest))
                stored))]
    (cond-> {:seon.operator.collect/roots-verified?
             (not (or unverified-branch unverified-digest))
             :seon.operator.collect/unstored-digests
             (- (count digests) (count stored))}
      unverified-branch
      (assoc :seon.operator.collect/unverified-branch unverified-branch)

      unverified-digest
      (assoc :seon.operator.collect/unverified-digest unverified-digest))))

(defn- unverified-root-clause
  [result]
  (let [branch (:seon.operator.collect/unverified-branch result)
        digest (:seon.operator.collect/unverified-digest result)]
    (cond
      branch (str " Branch " branch
                  " did not reopen at its recorded commit ID.")
      digest (str " Referenced blob digest " digest " did not read.")
      :else "")))

(defn- incomplete-collection!
  [result failure]
  (throw
   (ex-info
    (if failure
      (str "Collection failed before it could verify every recorded root: "
           (ex-message failure))
      (str "Collection did not preserve and verify every recorded root."
           (unverified-root-clause result)))
    {:seon.error/kind :seon.operator/collection-incomplete
     :seon.operator.collect/result result :seon.operator/collection-incomplete true}
    failure)))

(defn- inventory-facts
  "Project one registry inventory into the collection result's own keys.

  A number the inventory does not carry is ABSENT here, never a stored nil."
  [inventory]
  (into {}
        (keep (fn [[from to]]
                (when-let [value (get inventory from)] [to value])))
        {:seon.cluster.registry/retained-files
         :seon.operator.collect/retained-files
         :seon.cluster.registry/candidate-files
         :seon.operator.collect/candidate-files
         :seon.cluster.registry/candidate-bytes
         :seon.operator.collect/candidate-bytes
         :seon.cluster.registry/mark-duration-ms
         :seon.operator.collect/mark-duration-ms}))

(def ^:private projected-delete-ms-per-file
  ;; The sealed runbook's middle projection: one existence check, delete, and
  ;; FileStore sync per candidate at an illustrative five milliseconds.
  5)

(defn- dry-run-store!
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
        mark-duration (:seon.cluster.registry/mark-duration-ms inventory)
        verification
        (root-verification
         operation-store (collection-evidence operation-store)
         (konserve-key-set operation-store))
        result
        (merge
         (inventory-facts inventory)
         verification
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
          :seon.operator.collect/complete?
          (:seon.operator.collect/roots-verified? verification)
          :seon.operator.collect/dry-run? true
          :seon.operator.collect/projected-duration-ms
          (+ mark-duration (* projected-delete-ms-per-file candidates))})]
    ;; A dry run deletes nothing, so it cannot damage a root — but it reads
    ;; every one, and a root that does not reopen is already lost. Reporting
    ;; the candidate inventory of a store that cannot answer for its own roots
    ;; would be the same absence-as-health the real path just stopped doing.
    (if (:seon.operator.collect/roots-verified? verification)
      result
      (incomplete-collection! result nil))))

(defn- collect-store!
  [managed-root operation-store]
  (let [store-id
        (get-in @(:seon.store/connection-object operation-store)
                [:config :store :id])
        before (collection-observation operation-store)
        ;; The roots this store HELD before anything was swept. Verification
        ;; is a differential against this set, never a shape test over
        ;; whatever the schema says looks like a digest.
        held-before (konserve-key-set operation-store)
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
         :seon.operator.collect/roots-verified? false
         :seon.operator.collect/complete? false}]
    (try
      (let [inventory
            (registry/collect! operation-store (java.util.Date.) {})
            swept (:seon.cluster.registry/swept inventory)
            after-first (collection-observation operation-store)
            first-result
            (merge
             (inventory-facts inventory)
             (assoc base-result
                    :seon.operator.collect/objects-after
                    (:seon.operator.collect/objects after-first)
                    :seon.operator.collect/swept-objects swept
                    :seon.operator.collect/bytes-after
                    (:seon.operator.collect/bytes after-first)
                    :seon.operator.collect/reclaimed-bytes
                    (max 0 (- (:seon.operator.collect/bytes before)
                              (:seon.operator.collect/bytes after-first)))))]
        (try
          (let [verification-swept
                (registry/collect! operation-store (java.util.Date.))
                after (collection-observation operation-store)
                evidence (collection-evidence operation-store)
                verification
                (root-verification operation-store evidence held-before)
                complete?
                (:seon.operator.collect/roots-verified? verification)
                result
                (merge
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
                        :seon.operator.collect/complete? complete?)
                 verification)]
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

(def ^:private documented-request-keys
  "The keys `collect!` consults, and therefore the ones it documents.

  This is the ONE place they are named: the entry point reads its options
  through this set, and the misspelling check below derives its family from
  the same set, so the two can never disagree. It is not a mirror of the
  declaration either —
  `seon.operator-test/the-documented-collection-request-keys-are-the-declared-ones`
  fails on drift against `:seon.operator.collect/request` in
  `resources/seon/schemas/`.

  It is a value rather than a read of the authored schema population at call
  time. Reading that population here made a collection fail because ANOTHER
  namespace's declaration sat in the wrong resource file — an entry point
  fetching its world at call time, which §2.1 exists to prevent."
  #{:seon.operator/repository-root
    :seon.operator/managed-root
    :seon.operator.collect/dry-run?})

(defn- refuse-misspelled-options!
  "Refuse a request key carrying a documented key's name in another namespace.

  Datahike's `gc-storage!` IGNORES option keys it does not know
  (`datahike/gc.cljc`), so `{:dry-run? true}` — the unqualified spelling of
  `:seon.operator.collect/dry-run?` — reached this entry point, was consulted
  by nobody, and performed a REAL collection on `default` once. A silently
  ignored option is the absence-as-health class with the widest possible blast
  radius, so the one Seon entry point names it.

  Maps stay OPEN (§2.5): a key with an unrelated name is ordinary extra data
  and is ignored, which is what the scheduler's merged maintenance request
  needs. What is refused is only a key that means to be a documented one."
  [request]
  (let [by-name (into {} (map (juxt name identity)) documented-request-keys)]
    (doseq [supplied (keys request)
            :when (and (keyword? supplied)
                       (not (contains? documented-request-keys supplied)))
            :let [declared (get by-name (name supplied))]
            :when declared]
      (throw
       (ex-info
        (str "Collection option " supplied " is not a request key. "
             "Did you mean " declared "?")
        {:seon.error/kind :seon.operator.collect/unrecognized-option
         :seon.operator.collect/option-key supplied
         :seon.operator.collect/unrecognized-option true}))))
  request)

(defn collect!
  "Collect or dry-run one managed store.

  The root lifecycle lock protects operation-store acquisition only. The
  store's flock then preserves operation ownership while writer-side GC waits
  outside lifecycle custody, so a parked collection yields to lifecycle work.

  A COLLECTION IS COMPLETE WHEN EVERY RECORDED ROOT REOPENS — every roster
  branch at its recorded commit ID, every referenced blob digest by a physical
  read — and that check is evaluated unconditionally and reported as
  `:seon.operator.collect/roots-verified?`. When it fails, the refusal names
  the branch or the digest.

  `:seon.operator.collect/verification-pass-swept` is a REPORTED NUMBER, never
  a criterion. It counts a second pass over a store that keeps being written:
  every write that lands during the first pass is older than the second pass's
  cutoff, so under live writers it is expected to be non-zero and a zero fixed
  point is unreachable by construction. A collection's correctness comes from
  Datahike's safe point (`datahike/gc_guard.cljc`), which spares everything an
  in-flight values-then-pointer sequence wrote; it never came from a quiet
  store. Requiring zero there refused a collection that swept 23,449 objects
  and had preserved every root (2026-09-17, `default`).

  Both paths answer the same inventory — retained and candidate files,
  candidate bytes and the mark's duration — because a collection's reclaimed
  bytes mean nothing without the denominator they came from.

  A request key carrying a documented key's name in another namespace is
  refused by name; other keys are ignored, because maps are open."
  {:malli/schema
   [:=> [:cat :seon.operator.collect/request]
    [:or :seon.operator.collect/result :seon.error/value]]}
  [{managed-root :seon.operator/managed-root
    dry-run? :seon.operator.collect/dry-run?
    :as request}]
  (attempt
   #(let [_ (refuse-misspelled-options! request)
          managed-root (state/canonical-path managed-root)
          bound-ms (lifecycle-lock-bound-ms request)
          [operation-store release?]
          (state/with-lifecycle-lock!
           {:seon.operator.lock/path
            (state/root-lifecycle-lock-path managed-root)
            :seon.operator.lock/command "acquire collection store"
            :seon.operator.lock/acquisition-timeout-ms bound-ms
            :seon.operator.lock/hold-timeout-ms bound-ms}
           (fn [] (acquire-operation-store! managed-root nil)))]
      (try
        (if (true? dry-run?)
          (dry-run-store! managed-root operation-store)
          (collect-store! managed-root operation-store))
        (finally
          (when release?
            (store/release-store! operation-store)))))))

(defn- refork-under-lock!
  [{managed-root :seon.operator/managed-root
    source-commit :seon.source/commit-id
    supplied-store :seon.store/store
    :as request}]
  ;; No store is held ACROSS the quiesce arm. Stopping the live
  ;; instance, and the last instance out releases the process-root store
  ;; with its flock (`seon.cluster/release-root-store!`), so a store
  ;; captured before cleanup can be a released connection by the time the
  ;; fork needs it. The branch itself is not retired during quiescence:
  ;; `registry/reset-cluster!` performs one values-before-head replacement,
  ;; so every failure before that replacement leaves the prior branch intact.
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
  (quiesce-cluster-under-lock! request)
  (let [[operation-store release?]
        (acquire-operation-store! managed-root supplied-store)]
    (try
      (let [result
            (registry/reset-cluster!
             {:seon.store/store operation-store
              :seon.boot/cluster-name (:seon.boot/cluster-name request)
              :seon.source/commit-id source-commit})]
        result)
      (finally
        (when release? (store/release-store! operation-store))))))

(defn refork!
  "Compose the one cluster cleanup with an exact source refork."
  {:malli/schema
   [:=> [:cat :seon.operator/refork-request]
    [:or :seon.cluster.registry/branch-result :seon.error/value]]}
  [{repository-root :seon.operator/repository-root :as request}]
  ;; Deliberate keep-serial exception: fresh_operator's child invokes
  ;; refork-under-lock! while its parent owns the root lifecycle lock.
  (attempt
   #(let [bound-ms (lifecycle-lock-bound-ms request)]
      (state/with-control-lock!
       repository-root
       {:seon.operator.lock/command "refork claimed cluster"
        :seon.operator.lock/acquisition-timeout-ms bound-ms
        :seon.operator.lock/hold-timeout-ms bound-ms}
       (fn [] (refork-under-lock! request))))))
