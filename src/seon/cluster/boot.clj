(ns seon.cluster.boot
  "One explicit boot sequence and connected operator request boundary."
  (:require [clojure.core.server :as server]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.gc-guard :as gc-guard]
            [seon.cluster :as cluster]
            [seon.cluster.process :as process]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.cache :as cache]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.fault :as fault]
            [seon.fn.analyzer :as analyzer]
            [seon.fn :as index]
            [seon.error.refusal :as refusal]
            [seon.flow :as flow]
            [seon.fs :as fs]
            [seon.health :as health]
            [seon.maintenance :as maintenance]
            [seon.operator.runtime :refer [running-instances]]
            [seon.print.value :as print.value]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]
            [seon.sci.kernel :as kernel]))

(defn- stand-cluster-runtime!
  {:malli/schema
   [:=> [:cat :seon.boot/instance [:=> [:cat :seon.boot/instance] :seon.boot/instance]
         :seon.config/compiled :seon.db/connection :seon.boot/cluster-name :seon.boot/config
         :seon.sci.eval/projection-state [:or :nil :seon.sci.eval/ctx]]
    :seon.boot/instance]}
  [instance publish! compiled-config connection cluster-name config
   projection-state base-ctx]
  (schema/call-with-projection-state
   projection-state
   (fn []
     (let [;; Recovery and seeding are this cluster's own writes, never an
           ;; enclosing evaluation's custody.
           [instance process]
           (db/call-with-custody
            {:seon.db/connection connection}
            (fn []
              (let [recovery (cluster/recover-runs! connection)
                    _ (when (and (map? recovery)
                                 (contains? recovery :seon.error/at)
                                 (contains? recovery :seon.error/layer)
                                 (contains? recovery :seon.error/operation))
                        (throw (ex-info (:seon.error/message recovery) recovery)))
                    instance (publish! (merge instance recovery))
                    process (cluster/process-identity (:seon.boot/advertisement instance))
                    instance (publish!
                              (assoc instance
                                     :seon.boot/config-result
                                     (cluster/seed-cluster-facts!
                                      connection cluster-name compiled-config process)))]
                (cluster/seed-root-agent! connection cluster-name process)
                [instance process])))
           ;; Boot's dials are the compiled config it just applied: the store's
           ;; own program adopts the checkout's declarations only after boot,
           ;; so a dial the checkout added is absent from its effective config.
           boot-dials (:seon.config/effective compiled-config)
           _ (cache/configure! (:seon.config.cache/max-bytes boot-dials))
           arm-request {:seon.flow/commit-fault!
                        #(fault/record! connection cluster-name process
                                        (config/result-caps boot-dials) %)
                        ;; This cluster's adoption record names the JVM's
                        ;; loaded program at every acquisition (`sci.eval/loaded-source`).
                        :seon.sci.eval/loaded-connection connection}
           ;; Every contracted Var this JVM has loaded is armed once here,
           ;; with its profiling cell and definition digest; adoption later
           ;; re-arms only changed identities (`cluster/refresh-source!`).
           ;; ONE published commit: armed here and named loaded below.
           published-commit (:seon.source/commit-id (source/current (:seon.store/store instance)))
           host-arming (cluster/arm-host-program!
                        (assoc (cluster/published-program (:seon.store/store instance) published-commit)
                               :seon.db/db (db/db connection))
                        boot-dials (:seon.flow/commit-fault! arm-request))
           ;; Launched on the published files and armed against them: the JVM
           ;; runs that commit, so the adoption after boot writes entities only.
           _ (cluster/name-loaded-program! (:seon.store/store instance) published-commit)
           instance (publish! (assoc instance :seon.instrument/applied host-arming))
           bare-ctx
           (if base-ctx
             (sci.eval/fork-cluster-ctx
              base-ctx (db/db connection) connection projection-state arm-request)
             (sci.eval/cluster-ctx
              (db/db connection) connection projection-state arm-request))
           pre-graph-environment
           (env/refuse-incomplete-environment!
            (env/environment
             {:seon.boot/cluster-name cluster-name
              :seon.store/store (:seon.store/store instance)
              :seon.db/connection connection
              :seon.schema/projection
              (:seon.schema/projection @projection-state)
              :seon.db/basis-t (:seon.db/basis-t @projection-state)
              :seon.sci.admit/caps (config/result-caps boot-dials)}))
           work-launcher
           (flow/start-work-launcher!
            {:seon.env/environment pre-graph-environment
             ::flow/configuration
             (select-keys boot-dials flow/flow-workload-attributes)})
           environment
           (env/refuse-incomplete-environment!
            (env/boot-environment
             (assoc pre-graph-environment
                    :seon.flow/work-launcher work-launcher)))
           _ (env/replace-environment! projection-state environment)
           ;; a failed listener hand-off is one stored fault on THIS world (N3)
           _ (store/listen-failures!
              environment (cluster/process-identity (process/current-identity)))
           instance (publish!
                     (assoc instance :seon.sci.eval/ctx
                            (-> bare-ctx
                                (env/carry environment)
                                (env/carry-state projection-state))))
           instance (publish!
                     (assoc instance :seon.flow/work-launcher work-launcher))
           instance (publish!
                     (merge instance
                            (cluster/arm-agents! instance connection cluster-name)))]
       (publish! (cluster/serve! instance boot-dials))))))

(defn- changed-source-paths
  "Inputs whose bytes differ from the published program's digests, including
  inputs the published program names that the files no longer have."
  {:malli/schema [:=> [:cat :seon.store/store] [:vector :string]]}
  [store]
  (let [directory (fs/source-directory)
        paths (source/discover-paths directory cluster/source-roots)
        files (source/path-digests directory paths)
        ;; File digests only: the old commit's stored contracts may name
        ;; predicates this JVM's files deleted, and are replaced by the
        ;; publication this comparison selects, so no projection is built.
        commit-id (:seon.source/commit-id (source/current store))
        ;; An absent commit refuses through `source/database`, by name.
        database (or (source/commit-database store commit-id)
                     (source/database store commit-id))]
    (try
      ;; Every stored file entity is compared, so an input the files deleted is
      ;; published as a deletion instead of later read through its callers.
      (let [stored (into {} (d/q '[:find ?path ?digest
                                   :where [?file :seon.fn.file/relative-path ?path]
                                          [?file :seon.fn.file/digest ?digest]]
                                 database))]
        (into [] (comp (distinct) (remove #(= (get files %) (get stored %))))
              (sort (concat (keys files) (keys stored)))))
      (finally (d/release-materialized-db database)))))

(defn- stand-boot-layers!
  "Stand the ordered boot layers above the REPL.

  Store → published head → fork → connection → source commit → config are assoc'd
  as they stand, and the whole value is republished to the registry at every
  step. The instance a failure carries is exactly what stands: absence marks
  where boot stopped."
  {:malli/schema
   [:=> [:cat :seon.boot/instance [:=> [:cat :seon.boot/instance] :seon.boot/instance]
         :seon.config/compiled] :seon.boot/instance]}
  [instance publish! compiled-config]
  (let [config (:seon.boot/config instance)
        cluster-name (:seon.boot/cluster-name config)
        keep-history?
        (get-in compiled-config
                [:seon.config/effective :seon.config.db/keep-history?])
        store (cluster/acquire-root-store! (:seon.boot/store-dir config) keep-history?
                                           (true? (:seon.store/destroy? config)))
        instance (publish! (assoc instance :seon.store/store store))
        _ (cluster/write-advertisement!
           (cluster/cluster-paths (:seon.boot/root config) cluster-name)
           (:seon.boot/advertisement instance))
        ;; Program analysis classifies host bindings from clj-kondo's
        ;; dependency analysis; a root whose cache lacks it (nuke, fresh
        ;; archive) fills it here, in this JVM, before publication reads it.
        _ (analyzer/ensure-dependency-analysis! index/source-roots)
        ;; A resume publishes exactly the inputs whose bytes differ from the
        ;; published program (files edited while the JVM was down), and nothing
        ;; when none differ; a first start publishes everything. A refused
        ;; publication refuses the boot: stale program entities never run silently.
        ;; Its cost is proportional to the changed inputs, so it stands as its
        ;; own layer: the published head boot proceeds from.
        instance (let [published (if (source/current store)
                                   (let [changed (changed-source-paths store)]
                                     (when (seq changed)
                                       (cluster/refresh-source! (:seon.boot/root config) changed)))
                                   (cluster/refresh-source! (:seon.boot/root config)))]
                   (when (:seon.error/at published)
                     (throw (ex-info (:seon.error/message published) published)))
                   (publish! (assoc instance :seon.source/current (source/current store))))
        ;; Before any branch is acquired: a dead process's branches are no
        ;; one's to release, and each would root its data forever.
        instance (publish! (assoc instance :seon.cluster.registry/retired
                                  (registry/retire-unclaimed! store #{source/current-branch})))
        store-id (get-in @(:seon.store/connection-object store)
                         [:config :store :id])
        cluster-branch (registry/cluster-branch cluster-name)
        existing-cluster? (contains? (registry/roster store) cluster-branch)
        source-base (when-not existing-cluster? (cluster/source-base! store cluster-name))
        start-permit (gc-guard/try-reachability-permit! store-id :roster)
        _ (when (:seon.error/retryable? start-permit)
            (throw
             (ex-info
              (if (:datahike.gc-guard/mode start-permit)
                "Reachability publication is currently unavailable; retry start later."
                "A reachability sweep is in progress; retry start later.")
              start-permit)))
        forked
        (try
          (if existing-cluster?
            {:seon.store/branch cluster-branch
             :seon.cluster/created? false}
            (registry/ensure-cluster!
             {:seon.store/store store
              :seon.boot/cluster-name cluster-name
              :seon.source/commit-id
              (:seon.source/commit-id source-base)
              :seon.schema/projection (:seon.schema/projection source-base)
              :datahike.gc-guard/reachability-permit start-permit}))
          (finally
            (gc-guard/release-reachability-permit! start-permit)))
        instance (publish! (assoc instance :seon.store/branch (:seon.store/branch forked)))
        provisional-connection
        (store/open-branch! store (:seon.store/branch forked))
        instance (publish!
                  (assoc instance
                         :seon.boot/cluster-connection provisional-connection))
        initial-database @provisional-connection
        instance (publish! (assoc instance :seon.source/commit-id
                                   (or (:seon.source/commit-id source-base)
                                       (:seon.source/commit-id
                                        (d/pull initial-database [:seon.source/commit-id]
                                                [:seon.cluster/name cluster-name])))))
        initial-projection
        (or (:seon.schema/projection source-base)
            (schema/projection-from-database initial-database))
        initial-projection-state
        (sci.eval/projection-state initial-database initial-projection cluster-name)
        _ (schema/call-with-projection-state
           initial-projection-state
           ;; The provisional connection's own schema accretion.
           #(db/call-with-custody
             {:seon.db/connection provisional-connection}
             (fn []
               (cluster/require-coherent-program!
                provisional-connection cluster-name)
               (cluster/accrete-schema-population!
                provisional-connection cluster-name))))
        database @provisional-connection
        projection
        (if (= (db/basis-t initial-database) (db/basis-t database))
          initial-projection
          (schema/projection-from-database database))
        projection-state (sci.eval/projection-state database projection cluster-name)
        _ (store/release-branch! provisional-connection)
        connection
        (schema/call-with-projection-state
         projection-state
         #(store/open-branch! store (:seon.store/branch forked)))
        instance (publish!
                  (assoc instance :seon.boot/cluster-connection connection))]
    (stand-cluster-runtime!
     instance publish! compiled-config connection cluster-name config
     projection-state (:seon.sci.eval/ctx source-base))))


(defn diagnostic
  "Preserve the underlying evidence at the operator boundary.

  A caught Throwable is handed whole to the one error constructor
  (`seon.error.refusal/diagnostic`), which derives its class and frame (and the
  cause chain) there; this boundary never reduces a failure to its message."
  {:malli/schema
   [:function
    [:=> [:cat :string :seon.schema/value :seon.cluster.boot/disposition]
     :seon.cluster.boot/operation-error]
    [:=> [:cat :string :seon.schema/value :seon.cluster.boot/disposition :seon.error/throwable]
     :seon.cluster.boot/operation-error]]}
  ([message offending cause]
   {:seon.error/at (java.util.Date.)
    :seon.error/layer :seon.operator/operation
    :seon.error/operation 'seon.cluster.boot/request!
    :seon.error/message message
    :seon.cluster.boot/disposition cause
    :seon.error/offending offending
    :seon.error/expected :completed-operation})
  ([message offending cause throwable]
   ;; The outermost link's ex-data is usually the evidence itself; it is
   ;; carried once, as `:seon.error/offending`, never again inside the chain.
   ;; This reply crosses the prepl, a wire: every carried value is bounded text
   ;; from the value renderer's projection-free core, never a live handle
   ;; printed whole, and never a render that can fail with what it reports.
   (let [shown print.value/bounded-text]
     (-> (refusal/diagnostic
          (assoc (diagnostic message (shown offending) cause) :seon.error/throwable throwable))
         (update :seon.error/chain
                 (fn [links]
                   (mapv #(cond-> (dissoc % :seon.error/data)
                            (and (contains? % :seon.error/data) (not= offending (:seon.error/data %)))
                            (assoc :seon.error/shown (shown (:seon.error/data %))))
                         links)))))))

(defn- refuse!
  {:malli/schema [:=> [:cat :string :map] :nil]}
  [message evidence]
  (throw (ex-info message (diagnostic message evidence :refused))))

(defn- server-name
  {:malli/schema [:=> [:cat :string] :string]}
  [name]
  (str "seon.cluster/" name))

;;; ---------------------------------------------------------------------------
;;; The process uncaught handler (flow PRD N4)
;;; ---------------------------------------------------------------------------

(defn- carried-environment
  "The environment carried in the throwable's own `ex-data` chain, or nil.
  Attribution is evidence the failure carries; never a lookup elsewhere."
  {:malli/schema [:=> [:cat :seon.error/throwable] [:maybe :seon.env/environment]]}
  [throwable]
  (some #(env/of (ex-data %)) (take-while some? (iterate ex-cause throwable))))

(defn- uncaught-emergency!
  "Print one uncaught failure that could not be stored, with every cause."
  {:malli/schema [:=> [:cat :string :seon.error/throwable :map] :nil]}
  [thread-name throwable evidence]
  (fault/print-unrecorded! {:seon.error/message "SEON CORE FAULT (uncaught, not stored)"
                            :seon.error/operation `record-uncaught!
                            :seon.error/data (assoc evidence :thread thread-name)}
                           [throwable]))

(defn- handed-off-context
  "The fault context a foreign-thread hand-off names: its boundary, this
  process, and the environment's agent when it carries one."
  {:malli/schema [:=> [:cat :seon.env/environment :qualified-symbol] :seon.fault/context]}
  [environment operation]
  (cond-> {:seon.error/layer ::foreign-thread
           :seon.error/operation operation
           :seon.db.process/id (process/id (process/current-identity))}
    (:seon.agent/id environment) (assoc :seon.agent/id (:seon.agent/id environment))))

(defn record-uncaught!
  "Hand one exception no thread caught to its cluster's work launcher, which
  records it (`seon.flow/hand-off-fault!`); the dying thread never writes.

  The world is the environment the throwable carries, never a guess: without
  one no cluster is chosen (a probe's throwable is not the sole instance's
  fault), and the failure is printed on stderr with every cause, as is one
  the launcher does not admit or cannot store."
  {:malli/schema [:=> [:cat :string :seon.error/throwable] :nil]}
  [thread-name throwable]
  (if-let [environment (carried-environment throwable)]
    (flow/hand-off-fault! environment throwable (handed-off-context environment `record-uncaught!))
    (uncaught-emergency! thread-name throwable
                         {:candidates (vec (keys @running-instances))}))
  nil)

(defn- install-uncaught-handler!
  "Install the process handler, delegating to whatever handler preceded it."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (let [^Thread$UncaughtExceptionHandler previous
        (Thread/getDefaultUncaughtExceptionHandler)]
    (Thread/setDefaultUncaughtExceptionHandler
     (reify Thread$UncaughtExceptionHandler
       (uncaughtException [_ thread throwable]
         (try
           (record-uncaught! (.getName thread) throwable)
           (finally
             (when previous (.uncaughtException previous thread throwable))))))))
  nil)

;;; Once per JVM: a reload of this namespace neither reinstalls nor wraps it
;;; twice, and the handler calls `record-uncaught!` through its Var.
(defonce ^:private uncaught-handler (delay (install-uncaught-handler!)))

(defn- open-runs
  "Every open run on `connection`'s branch, as `[agent-id run-id]` pairs."
  {:malli/schema [:=> [:cat :seon.db/connection] :seon.sci.kernel/in-flight]}
  [connection]
  (vec (db/q '[:find ?agent-id ?run-id
               :where [?run :seon.turn/agent ?agent] (not [?run :seon.turn/closed-tx])
               [?run :seon.turn/id ?run-id] [?agent :seon.agent/id ?agent-id]]
             (db/db connection))))

(defn memory-pressure-crossed!
  "Close new arms once per crossing, naming each running cluster's open runs,
  and hand it as one core fault to every running cluster's work launcher
  (`seon.flow/hand-off-fault!`). Called on the JVM's notification thread
  (`kernel/watch-memory!`), which only closes arms and submits: it never
  writes, prints what it cannot hand off and never throws."
  {:malli/schema [:=> [:cat] :nil]}
  []
  (try
    (let [worlds (keep #(env/of (:seon.sci.eval/ctx %)) (vals @running-instances))]
      (when-let [pressure (kernel/enter-memory-pressure!
                           (into [] (mapcat #(open-runs (:seon.db/connection %))) worlds))]
        (let [refusal (kernel/memory-pressure-error pressure)
              failure (ex-info (:seon.error/message refusal) refusal)]
          (binding [*out* *err*] (println "SEON MEMORY PRESSURE:" (:seon.error/message refusal)) (flush))
          (doseq [world worlds]
            (flow/hand-off-fault! world failure (handed-off-context world `memory-pressure-crossed!))))))
    (catch Throwable failure
      (uncaught-emergency! "memory-pressure" failure {:operation `memory-pressure-crossed!})))
  nil)

;;; Once per JVM, beside the uncaught handler; the JVM calls through the Var.
(defonce ^:private memory-watch (delay (kernel/watch-memory! #(memory-pressure-crossed!))))

(defn listening-instance
  "One instance's structural members: its bootstrap config, the listener it
  advertises and this process's identity. Every layer is
  added to this value as boot acquires it; a test world that stands in for a
  running cluster builds its instance here too, so it is a declared instance."
  {:malli/schema [:=> [:cat :seon.boot/config [:fn seon.cluster/socket-server?]]
                  :seon.boot/instance]}
  [config listener]
  {:seon.boot/config config
   :seon.boot/advertisement
   (merge (process/current-identity)
          {:seon.boot/cluster-name (:seon.boot/cluster-name config)
           :seon.boot/prepl-host (:seon.boot/prepl-host config)
           :seon.boot/prepl-port (.getLocalPort ^java.net.ServerSocket listener)})
   :seon.boot/prepl-server listener})

(def boot-layers
  "The layers a ready instance holds, in the order boot acquires them."
  [:seon.boot/prepl-server :seon.store/store :seon.source/current
   :seon.store/branch :seon.boot/cluster-connection :seon.source/commit-id :seon.boot/config-result
   :seon.sci.eval/ctx :seon.flow/work-launcher :seon.flow/graph
   :seon.render.web/served])

(defn boot-timeline
  "`value` with each boot layer it holds and `prior` did not stamped at `at-ms`.

  The timeline is cumulative milliseconds since the start began, in
  acquisition order, so a layer's own cost is its difference from the one
  before it."
  {:malli/schema [:=> [:cat [:maybe :map] :seon.boot/instance [:int {:min 0}]] :seon.boot/instance]}
  [prior value at-ms]
  (let [added (filterv #(and (some? (get value %)) (nil? (get prior %))) boot-layers)]
    (cond-> value
      (seq added)
      (update :seon.boot/layers (fnil into [])
              (map (fn [layer] {:seon.boot/layer layer :seon.boot/at-ms at-ms}))
              added))))

(defn start!
  "Acquire a listener before the store; retain partial boot after any later refusal.
  A supplied listener was opened by the launcher's minimal core-only entry form."
  {:malli/schema [:=> [:cat :seon.boot/start-request] :seon.boot/instance]}
  [request]
  (let [began (System/nanoTime)
        _ @uncaught-handler
        _ @memory-watch
        config (cluster/resolve-bootstrap
                (dissoc request :seon.config/manifest :seon.config/environment :seon.boot/prepl-server))
        name (:seon.boot/cluster-name config)
        paths (cluster/cluster-paths (:seon.boot/root config) name)
        ;; A partial boot stays in the one instance roster under its name;
        ;; the failure references it (name, reached layers, process), never
        ;; carries the live instance — its connection, context and graphs.
        failed (fn [cause instance]
                 (ex-info (ex-message cause)
                          (cond-> (assoc (diagnostic (ex-message cause) (or (ex-data cause) {}) :boot-failed cause)
                                         :seon.boot/cluster-name name)
                            instance (merge (select-keys instance [:seon.boot/layers])
                                            (select-keys (:seon.boot/advertisement instance)
                                                         [:seon.boot/pid :seon.boot/start-instant])))
                          cause))
        ;; Each publication stamps the boot layers it adds with the
        ;; milliseconds since this start began (`boot-timeline`).
        publish! (fn [value]
                   (let [value (boot-timeline (get @running-instances name) value
                                              (quot (- (System/nanoTime) began) 1000000))]
                     (swap! running-instances assoc name value)
                     value))
        ;; The first value in the table is already an instance: acquiring the
        ;; listener under the claim lock leaves no window in which the table
        ;; holds a placeholder that its readers would have to filter.
        instance (locking running-instances
                   (when (get @running-instances name)
                     (refuse! "Cluster already has an instance." {:seon.boot/cluster-name name}))
                   (let [listener (try (or (:seon.boot/prepl-server request)
                                           (server/start-server
                                            {:name (server-name name) :accept 'seon.operator.prepl/io-prepl
                                             :address (:seon.boot/prepl-host config)
                                             :port (:seon.boot/prepl-port config)
                                             :args [:cluster-name name]}))
                                       (catch Throwable cause (throw (failed cause nil))))]
                     (publish! (listening-instance config listener))))]
    (try
      (let [;; The cluster the launcher opened this JVM for (it supplies the
            ;; listener) is its development cluster: the development overlay
            ;; lies under any `--config` manifest.
            manifest (merge (when (:seon.boot/prepl-server request) (config/development-manifest))
                            (:seon.config/manifest request))
            compiled (config/compile-manifest
                      (cond-> (assoc (select-keys request [:seon.config/environment])
                                     :seon.boot/cluster-name name)
                        manifest (assoc :seon.config/manifest manifest)))]
        (.mkdirs (io/file (:seon.boot/log-dir config)))
        (cluster/warn-low-space! (str (io/file (:seon.boot/store-dir config) "../.."))
                                (:seon.config/effective compiled))
        (let [stood (stand-boot-layers! instance publish! compiled)]
          (publish! (assoc stood :seon.boot/ready-ms
                           (quot (- (System/nanoTime) began) 1000000)))))
      (catch Throwable cause
        ;; The table's entry is this start's latest stage only while it holds
        ;; this start's listener; a competing start's entry is never consumed.
        (let [own (let [entry (get @running-instances name)]
                    (when (identical? (:seon.boot/prepl-server entry) (:seon.boot/prepl-server instance))
                      entry))]
          (if (and own (#{:seon.cluster.store/held-elsewhere :seon.cluster.store/held-by-this-process}
                          (:seon.cluster.store/rule (ex-data cause))))
            (do (server/stop-server (server-name name))
                (swap! running-instances #(cond-> % (identical? own (get % name)) (dissoc name))))
            ;; The partial boot stays for diagnosis; it says it failed, so
            ;; status and the MCP entrance never report it as still starting.
            (when own
              (swap! running-instances
                     #(cond-> % (identical? own (get % name))
                        (assoc name (assoc own :seon.boot/failure
                                           (diagnostic (ex-message cause) (or (ex-data cause) {})
                                                       :boot-failed cause)))))))
          (throw (failed cause own)))))))

(defn readiness
  "Read each acquired layer; missing values are explicit, including partial boot.

  Readiness is the boot's own facts. The cluster's problems are read by their
  owner where they are shown (`seon.health/report`): carried
  here they crossed every start reply whole, every stored failed test run
  included."
  {:malli/schema [:=> [:cat :seon.boot/instance] :seon.boot/readiness]}
  [instance]
  (let [connection (:seon.boot/cluster-connection instance)
        database (when (store/connection? connection) (db/db connection))
        required boot-layers]
    (merge (:seon.boot/advertisement instance)
           {:seon.boot/missing-layers (filterv #(not (get instance %)) required)
            :seon.agent/count (or (when database
                                     ;; Datalog's count over no bindings answers nil, not 0.
                                     (db/q '[:find (count ?a) . :where [?a :seon.agent/id _]] database))
                                   0)}
           (select-keys instance [:seon.boot/ready-ms :seon.boot/layers :seon.boot/recovered-runs
                                  :seon.boot/failure])
           (select-keys (:seon.render.web/served instance) [:seon.render.web/url :seon.render.web/wanted-port]))))

(defn stop!
  "Stop this exact instance; failed release retains its lock and diagnostic listener.

  ## Errors

  - A join `seon.cluster/disarm-agents!` misses is answered (the await
    owner's timeout naming the missing exit) with the instance restored in
    the roster: nothing after the join is released."
  {:malli/schema [:=> [:cat :seon.boot/instance] [:or :nil :seon.await/timeout-error]]}
  [instance]
  (let [name (get-in instance [:seon.boot/config :seon.boot/cluster-name])
        ;; The claim marks the instance itself, so the table's readers keep
        ;; seeing an instance while its layers are released, and a second
        ;; stop of the same instance finds it already claimed.
        claimed? (locking running-instances
                   (let [current (get @running-instances name)]
                     (when (and current
                                (identical? (:seon.boot/prepl-server instance)
                                            (:seon.boot/prepl-server current))
                                (not (:seon.boot/stopping current)))
                       (swap! running-instances assoc name (assoc current :seon.boot/stopping true))
                       true)))]
    (when claimed?
      (if-let [missed (cluster/disarm-agents! instance)]
        (do (swap! running-instances assoc name instance)
            missed)
        (try
          (some-> (:seon.flow/work-launcher instance) flow/stop-work-launcher!)
          (some-> (:seon.boot/cluster-connection instance) store/release-branch!)
          (when (:seon.store/store instance)
            (cluster/release-root-store! (get-in instance [:seon.boot/config :seon.boot/store-dir])))
          (server/stop-server (server-name name))
          (.close ^java.net.ServerSocket (:seon.boot/prepl-server instance))
          (let [file (io/file (:seon.boot/advertisement-file
                              (cluster/cluster-paths (get-in instance [:seon.boot/config :seon.boot/root]) name)))]
            ;; An absent file is the declared case; an unreadable one surfaces.
            (when (and (.isFile file)
                       (= (:seon.boot/advertisement instance) (edn/read-string (slurp file))))
              (java.nio.file.Files/deleteIfExists (.toPath file))))
          (swap! running-instances dissoc name)
          nil
          (catch Throwable cause
            (swap! running-instances assoc name instance)
            (throw cause)))))))

(defn- selected-instance
  {:malli/schema [:=> [:cat [:maybe :string]] :seon.boot/instance]}
  [name]
  (let [instances @running-instances
        selected (if name (get instances name)
                     (when (= 1 (count instances)) (val (first instances))))]
    (or selected (refuse! "Select one live cluster instance."
                          {:seon.boot/requested (or name :absent)
                           :seon.boot/candidates (vec (keys instances))}))))

(defn connection
  "The selected instance's explicit database custody, including partial boot."
  {:malli/schema [:function [:=> [:cat] :seon.db/connection]
                            [:=> [:cat :string] :seon.db/connection]]}
  ([] (or (:seon.boot/cluster-connection (selected-instance nil))
          (refuse! "Cluster connection is unavailable." {})))
  ([name]
   (or (:seon.boot/cluster-connection (selected-instance name))
       (refuse! "Cluster database connection is not acquired." {:seon.boot/cluster-name name}))))

(defn- request-world
  "The world a failed request records in: the environment the failure carries,
  else the named (or sole) live instance's. Nil when no cluster database
  stands (a boot refused before its database stood): the failure is then
  answered unrecorded, as the request's value."
  {:malli/schema [:=> [:cat [:maybe :string] :seon.error/throwable] [:maybe :seon.env/environment]]}
  [name failure]
  (or (carried-environment failure)
      (let [instances (vec (vals (cond-> @running-instances name (select-keys [name]))))]
        (when (= 1 (count instances)) (env/of (:seon.sci.eval/ctx (first instances)))))))

(defn- store-observation
  "The root store's growth for `status`: its key count, and when the root was
  last collected and what that reclaimed (`maintenance/last-collection` over a
  running cluster's database, the typed never-collected refusal included).
  Answers nothing when no cluster runs."
  {:malli/schema [:=> [:cat :seon.operator/managed-root]
                  [:or :nil [:map [:seon.operator/store :seon.operator.store/observation]]]]}
  [managed-root]
  (when-let [instance (first (vals @running-instances))]
    {:seon.operator/store
     {:seon.operator.store/files
      (fs/entry-count (get-in instance [:seon.boot/config :seon.boot/store-dir]))
      :seon.operator.store/last-collection
      (maintenance/last-collection (db/db (:seon.boot/cluster-connection instance))
                                   (.getCanonicalPath (io/file managed-root)))}}))

(defn request!
  "One data request. Verify root and process identity before connected effects."
  {:malli/schema [:=> [:cat :seon.operator/request] :seon.operator/response]}
  [{command :seon.operator/command root :seon.operator/managed-root
    name :seon.boot/cluster-name :as request}]
  (try
    (let [identity (process/current-identity)
          actual (store/declared-operator-root)
          cluster-root (str (io/file root "data/clusters"))]
      (when (and actual (not= actual (.getCanonicalPath (io/file root))))
        (refuse! "Request root does not match this process." request))
      (when (and (not (:seon.boot/prepl-server request))
                 (not= identity (select-keys request [:seon.boot/pid :seon.boot/start-instant])))
        (refuse! "Request process identity does not match this process." request))
      (case command
        :start (let [instance (start! (assoc request :seon.boot/root cluster-root
                                            :seon.boot/cluster-name (or name "default")))]
                 (merge identity {:seon.boot/cluster-name (or name "default")
                                  :seon.boot/readiness (readiness instance)}
                        (select-keys instance [:seon.source/commit-id])))
        ;; Each observation prints through its declared AI face; `--verbose`
        ;; asks for the whole value.
        :status (cond-> (merge identity
                              {:seon.operator/clusters
                               (mapv (fn [[n instance]]
                                       (let [report (health/report {:seon.boot/cluster-name n})
                                             connection (:seon.boot/cluster-connection instance)]
                                         (if (:seon.operator/verbose? request)
                                           report
                                           ;; The operator's request has no evaluation
                                           ;; world: the cluster hands its own database.
                                           (health/render-ai
                                            (cond-> {:seon.render/value report}
                                              (store/connection? connection)
                                              (assoc :seon.db/db (db/db connection)))))))
                                     @running-instances)}
                              (store-observation root))
                  (:seon.operator/verbose? request)
                  (assoc :seon.operator/footprint (fs/filesystem-space (str (io/file root "data")))))
        :open (let [instance (selected-instance name)]
                (if-let [url (get-in instance [:seon.render.web/served :seon.render.web/url])]
                  {:seon.boot/cluster-name (get-in instance [:seon.boot/config :seon.boot/cluster-name])
                   :seon.render.web/url url}
                  (refuse! "Cluster web layer is unavailable." request)))
        :stop (let [instance (selected-instance name)
                    n (get-in instance [:seon.boot/config :seon.boot/cluster-name])]
                (if (:seon.operator/force? request)
                  (locking running-instances
                    (let [siblings (vec (remove #{n} (keys @running-instances)))]
                      (when (seq siblings)
                        (refuse! "Force-stop would terminate sibling instances; use down."
                                 {:seon.boot/cluster-name n :seon.boot/siblings siblings}))
                      ;; Forced termination cannot run shutdown hooks: a hook calling
                      ;; stop! would wait for this same reservation monitor.
                      (.halt (Runtime/getRuntime) 0)))
                  (when-let [missed (stop! instance)]
                    (throw (ex-info (:seon.error/message missed) missed))))
                {:seon.boot/cluster-name n
                 :seon.operator/stopped? true
                 :seon.operator/process-exit? (empty? @running-instances)})
        ;; Reset = unlink the cluster's branch and fork a fresh one from the
        ;; published program entities (plan §7), in this JVM, keeping every cache:
        ;; the store stays held across stop and start, so nothing reopens.
        :reset (let [instance (selected-instance name)
                     config (:seon.boot/config instance)
                     n (:seon.boot/cluster-name config)
                     dir (:seon.boot/store-dir config)
                     began (System/nanoTime)
                     held (cluster/acquire-root-store! dir)]
                 (try
                   (when-let [missed (stop! instance)]
                     (throw (ex-info (:seon.error/message missed) missed)))
                   (let [stopped-ms (quot (- (System/nanoTime) began) 1000000)
                         _ (registry/retire-branch! {:seon.store/store held
                                                     :seon.store/branch (registry/cluster-branch n)})
                         unlinked-ms (quot (- (System/nanoTime) began) 1000000)
                         started (start! (-> config
                                             (dissoc :seon.store/destroy? :seon.operator/force?
                                                     :seon.operator/command :seon.boot/log-dir
                                                     :seon.boot/store-dir)
                                             (assoc :seon.boot/prepl-port 0)))]
                     (merge identity
                            {:seon.boot/cluster-name n
                             :seon.boot/readiness (readiness started)
                             :seon.operator/phases
                             {:seon.operator/stop-ms stopped-ms
                              :seon.operator/unlink-ms (- unlinked-ms stopped-ms)
                              :seon.operator/total-ms (quot (- (System/nanoTime) began) 1000000)}}
                            (select-keys started [:seon.source/commit-id])))
                   (finally (cluster/release-root-store! dir))))
        :down (do (doseq [instance (vals @running-instances)] (stop! instance))
                  {:seon.operator/stopped-processes [identity]
                   :seon.operator/process-exit? (empty? @running-instances)})
        :config-apply
        (let [result (config/apply! {:seon.db/connection (connection (or name "default"))
                                    :seon.boot/cluster-name (or name "default")
                                    :seon.config/manifest (:seon.config/manifest request)})]
          (if (:seon.error/at result) result
              {:seon.boot/cluster-name (or name "default") :seon.reconcile/result result}))
        ;; Will a candidate program boot? Its publication and this cluster's
        ;; adoption run here on disposable branches (`bin/seon check`).
        :check (merge identity
                      (cluster/rehearse-source! cluster-root (:seon.source/directory request)
                                                (or name "default")))
        :init
        (if name
          (let [dir (str (io/file root "data/store"))
                held (cluster/acquire-root-store! dir)]
            (try
              (let [base (cluster/source-base! held name)
                    branch (registry/cluster-branch name)
                    _ (when (and (contains? (registry/roster held) branch)
                                 (not (:seon.operator/force? request)))
                        (refuse! "Branch already exists; explicit --force is required." request))
                    result ((if (:seon.operator/force? request) registry/reset-cluster! registry/ensure-cluster!)
                            {:seon.store/store held :seon.boot/cluster-name name
                             :seon.source/commit-id (:seon.source/commit-id base)
                             :seon.schema/projection (:seon.schema/projection base)})]
                (merge result {:seon.boot/cluster-name name
                               :seon.source/commit-id (:seon.source/commit-id base)}))
              (finally (cluster/release-root-store! dir))))
          (let [dev (:seon.operator/development-cluster request)
                changed (get request :seon.source/changed-paths [])
                published (cluster/refresh-source! cluster-root changed dev)
                ;; Cost follows changed inputs: an edit publication re-applies
                ;; config only when it changed a config document.
                config-changed? (or (empty? changed)
                                    (some (set changed)
                                          (map #(.getCanonicalPath (io/file (fs/source-directory) %))
                                               [config/default-manifest-path
                                                config/development-manifest-path])))]
            ;; A stale-head refusal carries the winner's commit id; it is
            ;; returned whole, never narrowed into the success shape.
            (if (:seon.error/at published)
              published
              (cond-> (select-keys published [:seon.source/commit-id])
                dev (assoc :seon.boot/cluster-name dev)
                ;; A selected development cluster runs under the development overlay.
                (and dev config-changed?)
                (assoc :seon.reconcile/result
                       (config/apply! {:seon.db/connection (connection dev)
                                       :seon.boot/cluster-name dev
                                       :seon.config/manifest (config/development-manifest)}))))))
        (refuse! "Unknown operator command." request)))
    (catch Throwable cause
      ;; A request this boundary refused is its declared case; every other
      ;; failure is a core fault, shown first as the floor of its Throwable and
      ;; recorded in the requested cluster.
      (let [refused? (= :refused (:seon.cluster.boot/disposition (ex-data cause)))
            response (diagnostic (if refused? (ex-message cause) (print.value/throwable-text cause))
                                 (dissoc (or (ex-data cause) request) :seon.boot/prepl-server)
                                 (if refused? :refused :operation-failed) cause)]
        (if refused?
          response
          ;; With no cluster database to record in, the failure is still this
          ;; request's value, its receipt saying it was not stored; the
          ;; operator writes the response to its start log.
          (assoc response :seon.fault/recorded
                 (if-let [world (request-world name cause)]
                   (fault/fault! world cause
                                 {:seon.error/layer :seon.operator/operation
                                  :seon.error/operation `request!
                                  :seon.db.process/id
                                  (cluster/process-identity (process/current-identity))})
                   {:seon.boot/cluster-name (or name "default")
                    :seon.error/layer :seon.operator/operation
                    :seon.error/operation `request!
                    :seon.fault/committed? false})))))))

(defn banner
  "Render observed readiness without treating missing layers as ready."
  {:malli/schema [:=> [:cat :seon.boot/readiness] :string]}
  [ready]
  (str "seon " (:seon.boot/cluster-name ready)
       (if (seq (:seon.boot/missing-layers ready)) " partial boot " " ready ")
       (or (:seon.render.web/url ready) (print.value/bounded-text (:seon.boot/missing-layers ready)))
       " (pid " (:seon.boot/pid ready) ", prepl " (:seon.boot/prepl-port ready) ")"))
