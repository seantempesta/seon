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
            [seon.cluster.export :as export]
            [seon.cluster.registry :as registry]
            [seon.cluster.source :as source]
            [seon.cluster.store :as store]
            [seon.config :as config]
            [seon.db :as db]
            [seon.env :as env]
            [seon.error.refusal :as refusal]
            [seon.flow :as flow]
            [seon.fs :as fs]
            [seon.operator.runtime :refer [running-instances]]
            [seon.problems :as problems]
            [seon.schema :as schema]
            [seon.sci.eval :as sci.eval]))

(defn join-launcher-errors!
  "Join the work launcher graph's error channel into the cluster fault channel.

  The launcher starts before the cluster graph, so its `::flow/error` outputs
  (a throwing background submission) wait in Flow's own sliding error channel
  (`core.async flow/impl.clj:101-102`) until this join drains them into the one
  committer inbox, exactly as every agent graph's join does. Untagged: a
  launcher fault is no run's fault, and its `::flow/pid` names the launcher.
  Returns the join's completion channel."
  {:malli/schema
   [:=> [:cat [:map
               [:seon.flow/work-launcher
                [:map [:seon.flow/started :seon.flow/started]]]
               [:seon.flow/error-fanout
                [:map [:seon.flow/fault-channel :seon.flow/channel]]]]]
    :seon.flow/channel]}
  [instance]
  (flow/join-error-fanout!
   {::flow/started (get-in instance [::flow/work-launcher ::flow/started])
    ::flow/fault-channel (get-in instance [::flow/error-fanout ::flow/fault-channel])
    ::flow/tag {}}))

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
     (let [recovery (cluster/recover-runs! connection)
           _ (when (and (map? recovery)
                        (contains? recovery :seon.error/at)
                        (contains? recovery :seon.error/layer)
                        (contains? recovery :seon.error/operation))
               (throw (ex-info (:seon.error/message recovery) recovery)))
           instance (publish! (merge instance recovery))
           instance (publish!
                     (assoc instance
                            :seon.boot/config-result
                            (config/apply-compiled! connection compiled-config)))
           process (cluster/process-identity (:seon.boot/advertisement instance))
           _ (cluster/ensure-cluster-entity! connection cluster-name process)
           _ (cluster/seed-root-agent! connection cluster-name process)
           boot-dials (config/effective (db/db connection) cluster-name)
           arm-request {:seon.flow/commit-fault!
                        #(cluster/commit-fault! connection cluster-name process
                                        (config/result-caps boot-dials) %)}
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
              :seon.sci.admit/caps (config/result-caps boot-dials)
              :seon.config/on-core-error
              (:seon.config/on-core-error boot-dials)}))
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
           instance (publish!
                     (assoc instance :seon.sci.eval/ctx
                            (-> bare-ctx
                                (env/carry environment)
                                (env/carry-state projection-state))))
           instance (publish!
                     (assoc instance :seon.flow/work-launcher work-launcher))
           instance (publish!
                     (merge instance
                            (cluster/arm-agents! instance connection cluster-name)))
           _ (join-launcher-errors! instance)
           dials (config/effective (db/db connection) cluster-name)]
       (publish! (cluster/serve! instance dials))))))

(defn- changed-source-paths
  "Discovered inputs whose bytes differ from the published program's digests.
  An input the published program names but the files no longer discover is
  outside this check (measured 459 ms by lane resume-in-seconds)."
  {:malli/schema [:=> [:cat :seon.store/store] [:vector :string]]}
  [store]
  (let [directory (fs/source-directory)
        paths (source/discover-paths directory cluster/source-roots)
        files (source/path-digests directory paths)
        database (source/database store (:seon.source/commit-id (source/current store)))]
    (try
      (let [stored (source/stored-path-digests database paths)]
        (into [] (comp (distinct) (remove #(= (get files %) (get stored %))))
              (sort (concat (keys files) (keys stored)))))
      (finally (d/release-materialized-db database)))))

(defn- stand-boot-layers!
  "Stand the ordered boot layers above the REPL.

  Store → source commit → fork → connection → config are assoc'd
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
        ;; A resume publishes exactly the inputs whose bytes differ from the
        ;; published program (files edited while the JVM was down), and nothing
        ;; when none differ; a first start publishes everything. A refused
        ;; publication refuses the boot: stale program rows never run silently.
        _ (let [published (if (source/current store)
                            (let [changed (changed-source-paths store)]
                              (when (seq changed)
                                (cluster/refresh-source! (:seon.boot/root config) changed)))
                            (cluster/refresh-source! (:seon.boot/root config)))]
            (when (:seon.error/at published)
              (throw (ex-info (:seon.error/message published) published))))
        store-id (get-in @(:seon.store/connection-object store)
                         [:config :store :id])
        cluster-branch (registry/cluster-branch cluster-name)
        existing-cluster? (contains? (registry/roster store) cluster-branch)
        source-base (when-not existing-cluster? (cluster/source-base! store))
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
        (sci.eval/projection-state initial-database initial-projection)
        _ (schema/call-with-projection-state
           initial-projection-state
           #(do
              (cluster/require-coherent-program!
               provisional-connection cluster-name)
              (cluster/accrete-schema-population!
               provisional-connection cluster-name)))
        database @provisional-connection
        projection
        (if (= (db/basis-t initial-database) (db/basis-t database))
          initial-projection
          (schema/projection-from-database database))
        projection-state (sci.eval/projection-state database projection)
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
   (update (refusal/diagnostic
            (assoc (diagnostic message offending cause) :seon.error/throwable throwable))
           :seon.error/chain
           (fn [links]
             (mapv #(if (= offending (:seon.error/data %)) (dissoc % :seon.error/data) %)
                   links)))))

(defn- refuse!
  {:malli/schema [:=> [:cat :string :map] :nil]}
  [message evidence]
  (throw (ex-info message (diagnostic message evidence :refused))))

(defn- server-name
  {:malli/schema [:=> [:cat :string] :string]}
  [name]
  (str "seon.cluster/" name))

(defn start!
  "Acquire a listener before the store; retain partial boot after any later refusal.
  A supplied listener was opened by the launcher's minimal core-only entry form."
  {:malli/schema [:=> [:cat :seon.boot/start-request] :seon.boot/instance]}
  [request]
  (let [began (System/nanoTime)
        config (cluster/resolve-bootstrap
                (dissoc request :seon.config/manifest :seon.config/environment :seon.boot/prepl-server))
        name (:seon.boot/cluster-name config)
        paths (cluster/cluster-paths (:seon.boot/root config) name)
        reservation (Object.)
        _ (locking running-instances
            (when (get @running-instances name)
              (refuse! "Cluster already has an instance." {:seon.boot/cluster-name name}))
            (swap! running-instances assoc name reservation))
        latest (atom nil)
        publish! (fn [value]
                   (reset! latest value)
                   (swap! running-instances assoc name value)
                   value)]
    (try
      (let [listener (or (:seon.boot/prepl-server request)
                         (server/start-server
                          {:name (server-name name) :accept 'seon.operator.prepl/io-prepl
                           :address (:seon.boot/prepl-host config)
                           :port (:seon.boot/prepl-port config)
                           :args [:cluster-name name]}))
            advertisement (merge (process/current-identity)
                                 {:seon.boot/cluster-name name
                                  :seon.boot/prepl-host (:seon.boot/prepl-host config)
                                  :seon.boot/prepl-port (.getLocalPort listener)})
            instance (publish! {:seon.boot/config config
                                :seon.boot/advertisement advertisement
                                :seon.boot/prepl-server listener
                                :seon.boot/executors (cluster/root-executors)})
            compiled (config/compile-manifest
                      (assoc (select-keys request [:seon.config/manifest :seon.config/environment])
                             :seon.boot/cluster-name name))]
        (.mkdirs (io/file (:seon.boot/log-dir config)))
        (cluster/warn-low-space! (str (io/file (:seon.boot/store-dir config) "../.."))
                                (:seon.config/effective compiled))
        (let [stood (stand-boot-layers! instance publish! compiled)]
          (publish! (assoc stood :seon.boot/ready-ms
                           (quot (- (System/nanoTime) began) 1000000)))))
      (catch Throwable cause
        (when (or (nil? @latest)
                  (= :seon.cluster.store/held-elsewhere (:seon.cluster.store/rule (ex-data cause)))
                  (= :seon.cluster.store/held-by-this-process (:seon.cluster.store/rule (ex-data cause))))
          (server/stop-server (server-name name))
          (swap! running-instances dissoc name))
        (throw (ex-info (ex-message cause)
                        (assoc (diagnostic (ex-message cause) (or (ex-data cause) {}) :boot-failed cause)
                               :seon.boot/instance @latest)
                        cause))))))

(defn readiness
  "Read each acquired layer; missing values are explicit, including partial boot."
  {:malli/schema [:=> [:cat :seon.boot/instance] :seon.boot/readiness]}
  [instance]
  (let [connection (:seon.boot/cluster-connection instance)
        database (when (store/connection? connection) (db/db connection))
        required [:seon.boot/prepl-server :seon.store/store :seon.source/commit-id
                  :seon.store/branch :seon.boot/cluster-connection :seon.boot/config-result
                  :seon.sci.eval/ctx :seon.flow/work-launcher :seon.flow/graph
                  :seon.render.web/served]]
    (merge (:seon.boot/advertisement instance)
           {:seon.boot/missing-layers (filterv #(not (get instance %)) required)
            :seon.agent/count (if database (db/q '[:find (count ?a) . :where [?a :seon.agent/id _]] database) 0)
            :seon.problems/problems (if database (problems/problems database {}) {})}
           (select-keys instance [:seon.boot/ready-ms :seon.boot/recovered-runs])
           (select-keys (:seon.render.web/served instance) [:seon.render.web/url :seon.render.web/wanted-port]))))

(defn stop!
  "Stop this exact instance; failed release retains its lock and diagnostic listener."
  {:malli/schema [:=> [:cat :seon.boot/instance] :nil]}
  [instance]
  (let [name (get-in instance [:seon.boot/config :seon.boot/cluster-name])
        marker (Object.)
        claimed? (locking running-instances
                   (when (identical? (:seon.boot/prepl-server instance)
                                     (:seon.boot/prepl-server (get @running-instances name)))
                     (swap! running-instances assoc name marker)
                     true))]
    (when claimed?
      (try
        (cluster/disarm-agents! instance)
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
        (catch Throwable cause
          (swap! running-instances assoc name instance)
          (throw cause)))))
  nil)

(defn- selected-instance
  {:malli/schema [:=> [:cat [:maybe :string]] :seon.boot/instance]}
  [name]
  (let [instances (into {} (filter (comp map? val)) @running-instances)
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

(defn refork!
  "Refork an unconnected branch at the exact supplied publication."
  {:malli/schema [:=> [:cat :seon.operator/refork-request] :seon.cluster.registry/branch-result]}
  [request]
  (let [dir (str (io/file (:seon.operator/managed-root request) "data/store"))
        held (cluster/acquire-root-store! dir)]
    (try
      (registry/reset-cluster! (assoc request :seon.store/store held))
      (finally (cluster/release-root-store! dir)))))

(defn- readable-response
  "Return one plain-EDN response; malformed diagnostic evidence becomes readable data."
  {:malli/schema [:=> [:cat :seon.schema/value] :seon.operator/response]}
  [response]
  (try
    (with-meta (edn/read-string (pr-str response)) (meta response))
    (catch Throwable cause
      (diagnostic "Operator response contained non-EDN evidence."
                  {:seon.operator/response (pr-str response)
                   :seon.operator/reader-error (ex-message cause)}
                  :non-edn-response cause))))

(defn request!
  "One data request. Verify root and process identity before connected effects."
  {:malli/schema [:=> [:cat :seon.operator/request] :seon.operator/response]}
  [{command :seon.operator/command root :seon.operator/managed-root
    name :seon.boot/cluster-name :as request}]
  (readable-response
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
        :status (cond-> (merge identity
                              {:seon.operator/clusters
                               (mapv (fn [[n _]] (cluster/mcp-runtime-observation n))
                                     (filter (comp map? val) @running-instances))})
                  (:seon.operator/verbose? request)
                  (assoc :seon.operator/footprint (fs/footprint (str (io/file root "data")))))
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
                  (stop! instance))
                {:seon.boot/cluster-name n
                 :seon.operator/stopped? true
                 :seon.operator/process-exit? (empty? @running-instances)})
        ;; Reset = unlink the cluster's branch and fork a fresh one from the
        ;; published program rows (plan §7), in this JVM, keeping every cache:
        ;; the store stays held across stop and start, so nothing reopens.
        :reset (let [instance (selected-instance name)
                     config (:seon.boot/config instance)
                     n (:seon.boot/cluster-name config)
                     dir (:seon.boot/store-dir config)
                     began (System/nanoTime)
                     held (cluster/acquire-root-store! dir)]
                 (try
                   (stop! instance)
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
        :down (do (doseq [instance (filter map? (vals @running-instances))] (stop! instance))
                  {:seon.operator/stopped-processes [identity]
                   :seon.operator/process-exit? (empty? @running-instances)})
        :config-apply
        (let [result (config/apply! {:seon.db/connection (connection (or name "default"))
                                    :seon.boot/cluster-name (or name "default")
                                    :seon.config/manifest (:seon.config/manifest request)})]
          (if (:seon.error/at result) result
              {:seon.boot/cluster-name (or name "default") :seon.reconcile/result result}))
        :export {:seon.operator/destination
                 (export/export! {:seon.store/store (:seon.store/store (selected-instance name))
                                  :seon.export/parent-dir (:seon.operator/destination request)})}
        :init
        (if name
          (let [dir (str (io/file root "data/store"))
                held (cluster/acquire-root-store! dir)]
            (try
              (let [base (cluster/source-base! held)
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
                published (cluster/refresh-source! cluster-root
                                                    (get request :seon.source/changed-paths []) dev)]
            ;; A stale-head refusal carries the winner's commit id; it is
            ;; returned whole, never narrowed into the success shape.
            (if (:seon.error/at published)
              published
              (cond-> (select-keys published [:seon.source/commit-id])
                dev (assoc :seon.boot/cluster-name dev)))))
        (refuse! "Unknown operator command." request)))
    (catch Throwable cause
      (diagnostic (ex-message cause)
                  (dissoc (or (ex-data cause) request) :seon.boot/instance :seon.boot/prepl-server)
                  :operation-failed cause)))))

(defn banner
  "Render observed readiness without treating missing layers as ready."
  {:malli/schema [:=> [:cat :seon.boot/readiness] :string]}
  [ready]
  (str "seon " (:seon.boot/cluster-name ready)
       (if (seq (:seon.boot/missing-layers ready)) " partial boot " " ready ")
       (or (:seon.render.web/url ready) (pr-str (:seon.boot/missing-layers ready)))
       " (pid " (:seon.boot/pid ready) ", prepl " (:seon.boot/prepl-port ready) ")"))
