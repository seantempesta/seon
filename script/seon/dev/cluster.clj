(ns seon.dev.cluster
  "Autonomous pod lifecycle for cluster databases using an existing writer."
  (:require [babashka.fs :as fs]
            [malli.core :as m]
            [seon.db.protocol :as protocol]
            [seon.dev.artifact :as artifact]
            [seon.dev.config :as config]
            [seon.dev.process :as process]
            [seon.dev.state :as state]
            [seon.launch :as launch]
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
  (let [root (:seon.dev.config/root configuration)
        source (:seon.dev.config/launch-descriptor configuration)
        cluster-dir (str (fs/path root "data" "clusters" name))
        process-dir (str (fs/path root "tmp" "seon-clusters" name))
        descriptor
        (launch/shared-writer-cluster-descriptor
         {::launch/source-descriptor source
          ::launch/runtime-cluster name
          ::launch/target-database-name name
          ::protocol/database-path (str (fs/path cluster-dir "db"))
          ::launch/process-dir process-dir
          ::launch/log-dir (str (fs/path root "logs" "clusters" name))
          ::launch/http-port 0
          ::launch/http-port-file (str (fs/path process-dir "http.port"))
          ::launch/writable-blob-dir (str (fs/path cluster-dir "blobs"))})]
    {::configuration configuration
     ::target-configuration
     (-> configuration
         (config/select-launch-descriptor descriptor)
         (config/select-manifest nil))
     ::name name}))

(defn- manifest!
  [configuration]
  (or (artifact/read-manifest configuration)
      (throw
       (ex-info "The shared writer artifact manifest is absent."
                {:seon.dev.cluster/artifact-manifest
                 (:seon.dev.config/artifact-manifest configuration)}))))

(defn- ensure-under-lock!
  [target-configuration manifest acquire-owned!]
  (let [pod (get (process/specs target-configuration manifest) process/pod-id)]
    (process/ensure!
     target-configuration pod
     (fn [id acquire!]
       (acquire-owned! id acquire!
                       #(process/stop! target-configuration id))))
    (process/status target-configuration manifest)))

(defn open!
  "Start or converge one autonomous cluster pod through the shared writer."
  {:malli/schema [:=> [:cat ::request] :map]}
  [{::keys [configuration target-configuration] :as request}]
  (validate! ::request request "The cluster open request is invalid.")
  (let [manifest (manifest! configuration)]
    (process/with-startup-ownership
     configuration
     (fn [acquire-owned!]
       (state/with-lock
        configuration :cluster 30000
        #(ensure-under-lock! target-configuration manifest acquire-owned!))))))

(defn- stop-under-lock!
  [target-configuration operation]
  (process/clean-or-force!
   {:seon.dev.process/configuration target-configuration
    :seon.dev.process/operation operation
    :seon.dev.process/targets #{process/pod-id}}))

(defn close!
  "Stop only the selected cluster pod, preserving its database and blobs."
  {:malli/schema [:=> [:cat ::request] process/clean-or-force-result-schema]}
  [{::keys [configuration target-configuration] :as request}]
  (validate! ::request request "The cluster close request is invalid.")
  (state/with-lock
   configuration :cluster 30000
   #(stop-under-lock! target-configuration
                      :seon.dev.process.operation/down)))

(defn restart!
  "Restart only the selected cluster pod through its existing writer owner."
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
  "Return current pod and shared writer dependency health for one cluster."
  {:malli/schema [:=> [:cat ::request] :map]}
  [{::keys [configuration target-configuration] :as request}]
  (validate! ::request request "The cluster status request is invalid.")
  (process/status target-configuration (manifest! configuration)))
