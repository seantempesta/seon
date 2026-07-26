(ns seon.dev.cluster
  "Autonomous cluster lifecycle using an existing writer owner."
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [seon.db.protocol :as protocol]
            [seon.dev.artifact :as artifact]
            [seon.dev.config :as config]
            [seon.dev.process :as process]
            [seon.dev.release :as release]
            [seon.dev.state :as state]
            [seon.launch :as launch]
            [seon.packages :as packages]
            [seon.schema :as schema]))

(schema/register!
 ::name
 [:re "\\A[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\z"])
(schema/register!
 ::target-request
 [:map {:closed true}
  [::configuration config/configuration-schema]
  [::name ::name]])
(schema/register!
 ::request
 [:map {:closed true}
  [::configuration config/configuration-schema]
  [::target-configuration config/configuration-schema]
  [::name ::name]])
(schema/register!
 ::apply-result
 [:map
  [:seon.cluster.apply/ok? [:= true]]])

(def ^:private apply-result-byte-limit (* 1024 1024))
(def ^:private apply-diagnostic-character-limit 4096)

(declare bounded-diagnostic)

(defn- template-database-path
  [configuration manifest]
  (let [application-digest
        (:seon.dev.artifact/application-digest manifest)
        cluster-name (:seon.dev.config/cluster-name configuration)]
    (when-not (and (re-matches #"[0-9a-f]{64}" (or application-digest ""))
                   (m/validate ::name cluster-name))
      (throw
       (ex-info "The template database has no exact release and cluster identity."
                {:seon.dev.cluster/application-digest application-digest
                 :seon.dev.cluster/name cluster-name})))
    (fs/path (:seon.dev.config/root configuration)
             "tmp/seon-template-stores"
             application-digest cluster-name "db")))

(defn- database-path
  [configuration]
  (let [configured
        (get-in configuration
                [:seon.dev.config/launch-descriptor
                 ::launch/database
                 ::protocol/database-path])
        expected
        (some-> (:seon.dev.config/cluster-dir configuration)
                (fs/path "db")
                str)
        configured (or configured expected)]
    (when-not (and (string? configured) (= expected configured))
      (throw
       (ex-info "The selected cluster database path is inconsistent."
                {:seon.dev.cluster/database-path configured
                 :seon.dev.cluster/expected-database-path expected
                 :seon.dev.cluster/cluster-dir
                 (:seon.dev.config/cluster-dir configuration)})))
    (fs/path configured)))

(defn- clone-command
  [source target]
  (case (System/getProperty "os.name")
    "Mac OS X" ["/bin/cp" "-cR" (str source) (str target)]
    "Linux" ["cp" "--reflink=auto" "-a" (str source) (str target)]
    nil))

(defn- clone-tree!
  [source target]
  (let [parent (fs/parent target)
        temporary (fs/path parent (str "." (fs/file-name target) "."
                                            (random-uuid) ".tmp"))
        command (clone-command source temporary)]
    (when-not (and (fs/directory? source) command)
      (throw
       (ex-info "An at-rest template database cannot be cloned on this host."
                {:seon.dev.cluster/template-database (str source)
                 :seon.dev.cluster/operating-system
                 (System/getProperty "os.name")})))
    (try
      (fs/create-dirs parent)
      (let [result
            (shell/sh {:continue true
                       :out :string
                       :err :string
                       :cmd command})]
        (when-not (zero? (:exit result))
          (throw
           (ex-info "The at-rest template database clone failed."
                    {:seon.dev.cluster/template-database (str source)
                     :seon.dev.cluster/target-database (str target)
                     :seon.dev.cluster/exit (:exit result)
                     :seon.dev.cluster/error
                     (bounded-diagnostic (:err result))}))))
      (fs/move temporary target {:atomic-move true})
      target
      (finally
        (when (fs/exists? temporary)
          (fs/delete-tree temporary))))))

(defn publish-template-database!
  "Publish one closed cluster database as its exact release template."
  {:malli/schema [:=> [:cat config/configuration-schema :map] :string]}
  [configuration manifest]
  (let [source (database-path configuration)
        target (template-database-path configuration manifest)]
    (when-not (fs/directory? source)
      (throw
       (ex-info "A successful cluster apply has no closed database to publish."
                {:seon.dev.cluster/database-path (str source)})))
    (if (fs/directory? target)
      (str target)
      (str (clone-tree! source target)))))

(defn reset-database!
  "Replace a cluster database from its exact release template when available."
  {:malli/schema
   [:=> [:cat config/configuration-schema :map]
    [:map
     [:seon.dev.cluster/template? :boolean]
     [:seon.dev.cluster/database-path :string]]]}
  [configuration manifest]
  (let [target (database-path configuration)
        template (template-database-path configuration manifest)]
    (when (fs/exists? target)
      (fs/delete-tree target))
    (if (fs/directory? template)
      (do
        (clone-tree! template target)
        {:seon.dev.cluster/template? true
         :seon.dev.cluster/database-path (str target)})
      {:seon.dev.cluster/template? false
       :seon.dev.cluster/database-path (str target)})))

(defn- validate!
  [schema-key value message]
  (when-not (m/validate schema-key value)
    (throw
     (ex-info message
              {:seon.dev.cluster/explanation
               (mapv #(select-keys % [:path :in :type])
                     (:errors (m/explain schema-key value)))})))
  value)

(defn request
  "Derive one autonomous cluster target that retains the configured writer."
  {:malli/schema [:=> [:cat ::target-request] ::request]}
  [{::keys [configuration name] :as target}]
  (validate! ::target-request target "The cluster request is invalid.")
  (if (= name (:seon.dev.config/cluster-name configuration))
    {::configuration configuration
     ::target-configuration (config/select-manifest configuration nil)
     ::name name}
    (let [cluster-base
        (fs/parent (:seon.dev.config/cluster-dir configuration))
        state-root (fs/parent (fs/parent cluster-base))
        source (:seon.dev.config/launch-descriptor configuration)
        cluster-dir (str (fs/path cluster-base name))
        process-dir (str (fs/path state-root "tmp" "seon-clusters" name))
        log-dir (str (fs/path state-root "logs" "clusters" name))
        http-port-file (str (fs/path process-dir "http.port"))
        descriptor
        (launch/shared-writer-cluster-descriptor
         {::launch/source-descriptor source
          ::launch/runtime-cluster name
          ::launch/target-database-name name
         ::protocol/database-path (str (fs/path cluster-dir "db"))
         ::launch/packages-dir (str (fs/path cluster-dir "packages"))
         ::launch/process-dir process-dir
          ::launch/log-dir log-dir
          ::launch/http-port 0
          ::launch/http-port-file http-port-file
          ::launch/writable-blob-dir (str (fs/path cluster-dir "blobs"))})]
    {::configuration configuration
     ::target-configuration
     (-> configuration
         (assoc :seon.dev.config/cluster-name name
                :seon.dev.config/cluster-dir cluster-dir
                :seon.dev.config/process-dir process-dir
                :seon.dev.config/log-dir log-dir
                :seon.dev.config/http-port 0
                :seon.dev.config/http-port-file http-port-file)
         (config/select-launch-descriptor descriptor)
         (config/select-manifest nil))
     ::name name})))

(defn- manifest!
  [configuration]
  (or (if (:seon.dev.config/source-checkout? configuration)
        (artifact/read-manifest configuration)
        (try
          (release/read-manifest!
           (:seon.dev.config/artifact-manifest configuration))
          (catch Throwable _ nil)))
      (throw
       (ex-info "The shared writer artifact manifest is absent."
                {:seon.dev.cluster/artifact-manifest
                 (:seon.dev.config/artifact-manifest configuration)}))))

(defn- current-manifest!
  [configuration]
  (or (if (:seon.dev.config/source-checkout? configuration)
        (artifact/current-manifest configuration)
        (try
          (release/read-manifest!
           (:seon.dev.config/artifact-manifest configuration))
          (catch Throwable _ nil)))
      (throw
       (ex-info "The shared runtime artifact is absent or changed."
                {:seon.dev.cluster/artifact-manifest
                 (:seon.dev.config/artifact-manifest configuration)}))))

(defn- apply-manifest!
  [configuration acquire-owned!]
  (if (= false (:seon.dev.config/source-checkout? configuration))
    (current-manifest! configuration)
    (let [selected (config/select-manifest configuration nil)]
      (or
       (artifact/current-manifest selected)
       (process/current-watcher-manifest selected)
       (let [manifest
             (artifact/build!
              selected
              (fn []
                (process/prepare-watcher!
                 selected
                 (fn [id acquire!]
                   (acquire-owned!
                    id acquire!
                    (fn [] (process/stop! selected id)))))))]
         (process/admit-watcher-artifact! selected manifest)
         manifest)))))

(defn- bounded-diagnostic
  [value]
  (let [value (str/trim (or value ""))]
    (subs value 0
          (min apply-diagnostic-character-limit (count value)))))

(defn- require-apply-readiness!
  [target-configuration manifest pod]
  (let [status (process/status target-configuration manifest)
        writer
        (or (get-in status
                    [:seon.dev.target/processes process/writer-id])
            (get-in status
                    [:seon.dev.target/external-dependencies
                     process/writer-id]))
        retained-pod (process/read-process target-configuration process/pod-id)]
    (when-not (:seon.dev.process/ready? writer)
      (throw
       (ex-info "Cluster apply requires the shared writer to be ready."
                {:seon.dev.process/id process/writer-id
                 :seon.dev.process/status writer})))
    (when retained-pod
      (throw
       (ex-info "Cluster apply requires the selected pod to be closed."
                {:seon.dev.process/id process/pod-id
                 :seon.dev.process/status
                 (process/reported-process-status retained-pod)})))
    (when-not (= process/pod-id (:seon.dev.process/id pod))
      (throw
       (ex-info "The selected artifact has no canonical pod command."
                {:seon.dev.process/id (:seon.dev.process/id pod)})))
    status))

(defn- read-apply-result!
  [path process-result]
  (when-not (zero? (:exit process-result))
    (throw
     (ex-info "Cluster apply exited unsuccessfully."
              {:seon.dev.cluster/exit (:exit process-result)
               :seon.dev.cluster/output
               (bounded-diagnostic (:out process-result))
               :seon.dev.cluster/error
               (bounded-diagnostic (:err process-result))
               :seon.dev.cluster/apply-result-path path})))
  (when-not (fs/regular-file? path)
    (throw
     (ex-info "Cluster apply exited without an EDN result."
              {:seon.dev.cluster/apply-result-path path})))
  (when (> (fs/size path) apply-result-byte-limit)
    (throw
     (ex-info "The cluster apply result exceeds the operator limit."
              {:seon.dev.cluster/apply-result-path path
               :seon.dev.cluster/result-bytes (fs/size path)
               :seon.dev.cluster/result-byte-limit
               apply-result-byte-limit})))
  (let [result (edn/read-string (slurp path))]
    (validate! ::apply-result result
               "The cluster apply result is invalid.")))

(defn- apply-command
  [target-configuration pod result-path]
  {:seon.dev.cluster/argv
   (conj (:seon.dev.process/argv pod) "cluster-apply")
   :seon.dev.cluster/environment
   (assoc (:seon.dev.process/environment pod)
          "SEON_CLUSTER_APPLY_RESULT" result-path)
   :seon.dev.cluster/directory
   (:seon.dev.config/root target-configuration)})

(defn ensure-package-skeleton!
  "Materialize missing manifests for one cluster package root."
  {:malli/schema [:=> [:cat ::launch/descriptor] ::launch/packages-dir]}
  [descriptor]
  (let [packages-dir
        (or (::launch/packages-dir descriptor)
            (throw (ex-info "A cluster descriptor requires a packages root."
                            {::launch/descriptor descriptor})))
        npm-dir (fs/path packages-dir "npm")
        package-json (fs/path npm-dir "package.json")
        deps-edn (fs/path packages-dir "deps.edn")]
    (fs/create-dirs npm-dir)
    (when-not (fs/exists? package-json)
      (spit (str package-json)
            (packages/npm-manifest
             {::packages/rows []
              :seon.config.packages/trusted-lifecycle-scripts :all})))
    (when-not (fs/exists? deps-edn)
      (spit (str deps-edn)
            (packages/deps-manifest {::packages/rows []})))
    packages-dir))

(defn reset-package-skeleton!
  "Replace one cluster package root with empty generated manifests."
  {:malli/schema [:=> [:cat ::launch/descriptor] ::launch/packages-dir]}
  [descriptor]
  (let [packages-dir
        (or (::launch/packages-dir descriptor)
            (throw (ex-info "A cluster descriptor requires a packages root."
                            {::launch/descriptor descriptor})))]
    (when (fs/exists? packages-dir)
      (fs/delete-tree packages-dir))
    (ensure-package-skeleton! descriptor)))

(defn- ensure-under-lock!
  [target-configuration manifest acquire-owned!]
  (let [spec-map (process/specs target-configuration manifest)]
    (doseq [id (process/start-order spec-map)]
      (process/ensure!
       target-configuration (get spec-map id)
       (fn [id acquire!]
         (acquire-owned! id acquire!
                         #(process/stop! target-configuration id)))))
    (process/status target-configuration manifest)))

(defn open!
  "Start or converge one autonomous cluster through the shared writer."
  {:malli/schema [:=> [:cat ::request] :map]}
  [{::keys [configuration target-configuration] :as request}]
  (validate! ::request request "The cluster open request is invalid.")
  (let [manifest (manifest! configuration)]
    (process/with-startup-ownership
     configuration
     (fn [acquire-owned!]
       (state/with-lock
        configuration :cluster 30000
        #(do
           (ensure-package-skeleton!
            (:seon.dev.config/launch-descriptor target-configuration))
           (ensure-under-lock! target-configuration manifest
                               acquire-owned!)))))))

(defn apply!
  "Apply one current release to a closed cluster through the client artifact."
  {:malli/schema [:=> [:cat ::request] ::apply-result]}
  [{::keys [configuration target-configuration] :as request}]
  (validate! ::request request "The cluster apply request is invalid.")
  (process/with-startup-ownership
   configuration
   (fn [acquire-owned!]
     (state/with-lock
      configuration :cluster 600000
      (fn []
        (let [manifest (apply-manifest! configuration acquire-owned!)
              writer (get (process/specs target-configuration manifest)
                          process/writer-id)
              started-writer (atom nil)
              direct-writer? (some? writer)
              _ (when direct-writer?
                  (process/ensure!
                   target-configuration writer
                   (fn [id acquire!]
                     (acquire-owned!
                      id
                      #(let [record (acquire!)]
                         (reset! started-writer record)
                         record)
                      #(if-let [record @started-writer]
                         (process/stop! target-configuration id record)
                         (process/stop! target-configuration id))))))
           pod (get (process/specs target-configuration manifest)
                    process/pod-id)
           _ (require-apply-readiness! target-configuration manifest pod)
           _ (ensure-package-skeleton!
              (:seon.dev.config/launch-descriptor target-configuration))
           process-dir
           (get-in target-configuration
                   [:seon.dev.config/launch-descriptor
                    ::launch/process ::launch/process-dir])
           result-path
           (str (fs/path process-dir "cluster-apply"
                         (str (random-uuid) ".edn")))
           {:seon.dev.cluster/keys [argv environment directory]}
           (apply-command target-configuration pod result-path)
           _ (fs/create-dirs (fs/parent result-path))
           process-result
           (shell/sh {:continue true
                      :dir directory
                      :env environment
                      :out :string
                      :err :string
                      :cmd argv})
           result (read-apply-result! result-path process-result)]
       (when-let [record @started-writer]
         (process/stop! target-configuration process/writer-id record))
       (publish-template-database! target-configuration manifest)
       (config/publish-applied-manifest! target-configuration)
       result))))))

(defn- stop-under-lock!
  [target-configuration operation]
  (process/clean-or-force!
   {:seon.dev.process/configuration target-configuration
    :seon.dev.process/operation operation
    :seon.dev.process/targets
    (set (process/target-process-ids target-configuration))}))

(defn close!
  "Stop the selected cluster processes, preserving its database and blobs."
  {:malli/schema [:=> [:cat ::request] process/clean-or-force-result-schema]}
  [{::keys [configuration target-configuration] :as request}]
  (validate! ::request request "The cluster close request is invalid.")
  (state/with-lock
   configuration :cluster 30000
   #(stop-under-lock! target-configuration
                      :seon.dev.process.operation/down)))

(defn restart!
  "Restart the selected cluster processes through its writer owner."
  {:malli/schema [:=> [:cat ::request] :map]}
  [{::keys [configuration target-configuration] :as request}]
  (validate! ::request request "The cluster restart request is invalid.")
  (let [manifest (manifest! configuration)]
    (process/with-startup-ownership
     configuration
     (fn [acquire-owned!]
       (state/with-lock
        configuration :cluster 30000
        #(do
           (stop-under-lock! target-configuration
                             :seon.dev.process.operation/restart)
           (ensure-under-lock! target-configuration manifest
                               acquire-owned!)))))))

(defn status
  "Return process and shared-writer dependency health for one cluster."
  {:malli/schema [:=> [:cat ::request] :map]}
  [{::keys [configuration target-configuration] :as request}]
  (validate! ::request request "The cluster status request is invalid.")
  (process/status target-configuration (manifest! configuration)))
