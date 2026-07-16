(ns seon.launch
  "Immutable process-launch data shared by the operator and pod."
  (:require [clojure.string :as str]
            [my.blob.schema]
            [seon.client.schema]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.restore-admin.schema]
            [seon.dev.restore.schema]
            [seon.schema :as schema]))

(schema/register! ::path [:string {:min 1}])
(schema/register! ::socket-path ::path)
(schema/register! ::runtime-cluster [:string {:min 1}])
(schema/register! ::writer-cluster [:string {:min 1}])
(schema/register! ::writer-process-dir ::path)
(schema/register! ::artifact-flavor
                  [:enum :seon.dev.artifact.flavor/default
                   :seon.dev.artifact.flavor/acme])
(schema/register! ::client-build-id [:string {:min 1}])
(schema/register! ::execution-build-id [:string {:min 1}])
(schema/register! ::execution-output ::path)
(schema/register! ::execution-digest [:re "^[0-9a-f]{64}$"])
(schema/register! ::cluster-dir ::path)
(schema/register! ::process-dir ::path)
(schema/register! ::log-dir ::path)
(schema/register! ::http-port [:int {:min 0 :max 65535}])
(schema/register! ::http-port-file ::path)
(schema/register! ::writer-repl-port-file ::path)
(schema/register! ::request-socket-path ::socket-path)
(schema/register! ::publish-socket-path ::socket-path)

(schema/register!
 ::runtime
 [:map {:closed true}
  [::runtime-cluster ::runtime-cluster]
  [::artifact-flavor ::artifact-flavor]
  [::client-build-id ::client-build-id]
  [::execution-build-id {:optional true} ::execution-build-id]
  [::execution-output {:optional true} ::execution-output]
  [::execution-digest {:optional true} ::execution-digest]
  [:seon.client/launch-capability :seon.client/launch-capability]])
(schema/register!
 ::database
 [:map {:closed true}
  [::protocol/database-name ::protocol/database-name]
  [::coordinate/attachment {:optional true} ::coordinate/attachment]
  [::coordinate/coordinate {:optional true} ::coordinate/coordinate]
  [::protocol/backend ::protocol/backend]
  [::protocol/database-path ::protocol/database-path]])
(schema/register!
 ::writer-owner
 [:map {:closed true}
  [::writer-cluster ::writer-cluster]
  [::writer-process-dir ::writer-process-dir]
  [::request-socket-path ::request-socket-path]
  [::publish-socket-path ::publish-socket-path]
  [::writer-repl-port-file ::writer-repl-port-file]])
(schema/register!
 ::process
 [:map {:closed true}
  [::process-dir ::process-dir]
  [::log-dir ::log-dir]
  [::http-port ::http-port]
  [::http-port-file ::http-port-file]])

(schema/register! ::blob-storage-view :my.blob/storage-view)
(defn- restore-startup-consistent?
  [startup]
  (let [identity (:seon.dev.restore/startup-identity startup)
        admin (:seon.db.restore-admin/result startup)
        blobs (:my.blob/materialization-result startup)
        consumers (:seon.dev.restore/consumer-generations identity)]
    (and (= (:seon.dev.restore/intent-id identity)
            (:seon.db.restore-admin/intent-id admin))
         (= (:seon.dev.restore/plan-digest identity)
            (:seon.db.restore-admin/plan-digest admin))
         (= (:seon.dev.restore/reachable-hash-digest identity)
            (:my.blob/reachable-hash-digest blobs))
         (= (:seon.db.restore-admin/selected-target-coordinate admin)
            (:my.blob/target-coordinate blobs))
         (contains? consumers :seon.dev.process/pod))))

(schema/register!
 ::restore-startup
 [:map {:closed true}
  [:seon.dev.restore/startup-identity
   :seon.dev.restore/startup-identity]
  [:seon.db.restore-admin/result
   :seon.db.restore-admin/success-result]
  [:my.blob/materialization-result :my.blob/materialization-success]])

(defn- descriptor-consistent?
  [descriptor]
  (if-let [startup (::restore-startup descriptor)]
    (and (= (get-in startup [:seon.db.restore-admin/result
                             :seon.db.restore-admin/forced-main-coordinate])
            (get-in descriptor [::database ::coordinate/coordinate]))
         (false? (get-in descriptor
                         [::runtime :seon.client/launch-capability
                          :seon.client/autonomous?])))
    true))

(schema/register!
 ::descriptor
 [:map {:closed true}
  [::runtime ::runtime]
  [::database ::database]
  [::writer-owner ::writer-owner]
  [::process ::process]
  [::blob-storage-view ::blob-storage-view]
  [::restore-startup {:optional true} ::restore-startup]])

(schema/register!
 ::default-descriptor-request
 [:map {:closed true}
  [::cluster-dir ::cluster-dir]
  [::artifact-flavor ::artifact-flavor]
  [::client-build-id ::client-build-id]
  [::execution-build-id {:optional true} ::execution-build-id]
  [::execution-output {:optional true} ::execution-output]
  [::request-socket-path ::request-socket-path]
  [::publish-socket-path ::publish-socket-path]
  [::writer-repl-port-file ::writer-repl-port-file]
  [::process-dir ::process-dir]
  [::log-dir ::log-dir]
  [::http-port ::http-port]
  [::http-port-file ::http-port-file]])
(schema/register! ::source-descriptor ::descriptor)
(schema/register! ::target-database-name ::protocol/database-name)
(schema/register! ::target-coordinate ::coordinate/coordinate)
(schema/register! ::writable-blob-dir ::path)
(schema/register!
 ::branch-descriptor-request
 [:map {:closed true}
  [::source-descriptor ::source-descriptor]
  [::runtime-cluster ::runtime-cluster]
  [::target-database-name ::target-database-name]
  [::target-coordinate ::target-coordinate]
  [::process-dir ::process-dir]
  [::log-dir ::log-dir]
  [::http-port ::http-port]
  [::http-port-file ::http-port-file]
  [::writable-blob-dir ::writable-blob-dir]])

(defn- basename
  [path]
  (last (remove str/blank? (str/split path #"/"))))

(defn- normalize-path
  [path]
  (let [absolute? (str/starts-with? path "/")
        segments
        (reduce
         (fn [result segment]
           (cond
             (or (str/blank? segment) (= "." segment)) result
             (= ".." segment)
             (if (and (seq result) (not= ".." (peek result)))
               (pop result)
               (conj result segment))
             :else (conj result segment)))
         []
         (str/split (str/replace path #"\\\\" "/") #"/"))
        normalized (str (when absolute? "/") (str/join "/" segments))]
    (if (str/blank? normalized) "." normalized)))

(defn- paths-overlap?
  [left right]
  (let [left (normalize-path left)
        right (normalize-path right)]
    (or (= left right)
        (str/starts-with? left (str right "/"))
        (str/starts-with? right (str left "/")))))

(defn- invariant!
  [condition message data]
  (when-not condition
    (throw (ex-info message
                    (assoc data :seon.error/kind
                           :seon.launch.error/invariant)))))

(defn validate-restore-startup
  "Validate one restore startup value and its cross-owner evidence."
  {:malli/schema [:=> [:cat :map] ::restore-startup]}
  [startup]
  (invariant!
   (schema/valid-candidate-value? ::restore-startup startup)
   "The restore startup value is not a closed portable value."
   {::restore-startup startup})
  (invariant!
   (restore-startup-consistent? startup)
   "Restore startup evidence does not describe one frozen transition."
   {::restore-startup startup})
  startup)

(defn validate-descriptor
  "Validate one launch descriptor and optional restore startup evidence."
  {:malli/schema [:=> [:cat :map] ::descriptor]}
  [descriptor]
  (invariant!
   (schema/valid-candidate-value? ::descriptor descriptor)
   "The process launch descriptor is invalid."
   {::descriptor descriptor})
  (when-let [startup (::restore-startup descriptor)]
    (validate-restore-startup startup))
  (invariant!
   (descriptor-consistent? descriptor)
   "Restore startup expects another fresh main coordinate."
   {::descriptor descriptor})
  descriptor)

(schema/register!
 ::with-restore-startup-request
 [:map {:closed true}
  [::descriptor ::descriptor]
  [::restore-startup ::restore-startup]])

(defn with-restore-startup
  "Attach restore evidence and close the launch to autonomous effects."
  {:malli/schema [:=> [:cat ::with-restore-startup-request] ::descriptor]}
  [{descriptor ::descriptor startup ::restore-startup}]
  (validate-descriptor
   (-> descriptor
       (assoc ::restore-startup (validate-restore-startup startup))
       (assoc-in [::runtime :seon.client/launch-capability]
                 {:seon.client/autonomous? false}))))

(defn default-descriptor
  "Derive one ordinary autonomous-cluster launch descriptor."
  {:malli/schema [:=> [:cat ::default-descriptor-request] ::descriptor]}
  [{::keys [cluster-dir artifact-flavor client-build-id execution-build-id
            execution-output request-socket-path
            publish-socket-path writer-repl-port-file process-dir log-dir
            http-port http-port-file]}]
  (let [cluster-dir (normalize-path cluster-dir)
        cluster (basename cluster-dir)]
    (invariant! (not (str/blank? cluster))
                "A cluster directory must have a basename."
                {::cluster-dir cluster-dir})
    (validate-descriptor
      {::runtime
      (cond->
       {::runtime-cluster cluster
        ::artifact-flavor artifact-flavor
        ::client-build-id client-build-id
        :seon.client/launch-capability {:seon.client/autonomous? true}}
        execution-build-id (assoc ::execution-build-id execution-build-id)
        execution-output (assoc ::execution-output execution-output))
      ::database
      {::protocol/database-name cluster
       ::protocol/backend :file
       ::protocol/database-path (str cluster-dir "/db")}
      ::writer-owner
      {::writer-cluster cluster
       ::writer-process-dir (normalize-path process-dir)
       ::request-socket-path request-socket-path
       ::publish-socket-path publish-socket-path
       ::writer-repl-port-file writer-repl-port-file}
      ::process
      {::process-dir (normalize-path process-dir)
       ::log-dir (normalize-path log-dir)
       ::http-port http-port
       ::http-port-file (normalize-path http-port-file)}
      ::blob-storage-view
      {:my.blob/writable-dir
       (str cluster-dir "/blobs")
       :my.blob/read-only-dirs []}})))

(schema/register!
 ::with-coordinate-request
 [:map {:closed true}
  [::descriptor ::descriptor]
  [::coordinate/coordinate ::coordinate/coordinate]])

(defn with-coordinate
  "Return `descriptor` pinned to one writer-returned complete coordinate."
  {:malli/schema [:=> [:cat ::with-coordinate-request] ::descriptor]}
  [{descriptor ::descriptor point ::coordinate/coordinate}]
  (let [database (::database descriptor)
        retained-attachment (::coordinate/attachment database)
        attachment (coordinate/attachment point)]
    (invariant! (or (nil? retained-attachment)
                    (= retained-attachment attachment))
                "The writer coordinate does not match the launch attachment."
                {::coordinate/attachment retained-attachment
                 ::coordinate/coordinate point})
    (validate-descriptor
     (assoc descriptor ::database
            (assoc database
                   ::coordinate/attachment attachment
                   ::coordinate/coordinate point)))))

(schema/register!
 ::with-execution-artifact-request
 [:map {:closed true}
  [::descriptor ::descriptor]
  [::execution-build-id ::execution-build-id]
  [::execution-output ::execution-output]
  [::execution-digest ::execution-digest]])

(defn with-execution-artifact
  "Bind one launch to its flavor-owned execution artifact."
  {:malli/schema
   [:=> [:cat ::with-execution-artifact-request] ::descriptor]}
  [{descriptor ::descriptor
    execution-build-id ::execution-build-id
    execution-output ::execution-output
    execution-digest ::execution-digest}]
  (let [runtime (::runtime descriptor)]
    (invariant! (= execution-build-id (::execution-build-id runtime))
                "The execution build does not match the launch flavor."
                {::execution-build-id execution-build-id
                 ::runtime runtime})
    (invariant! (= (normalize-path execution-output)
                   (normalize-path (::execution-output runtime)))
                "The execution output does not match the launch flavor."
                {::execution-output execution-output
                 ::runtime runtime})
    (validate-descriptor
     (assoc descriptor ::runtime
            (assoc runtime ::execution-digest execution-digest)))))

(defn branch-descriptor
  "Derive one non-autonomous branch descriptor from its source launch."
  {:malli/schema [:=> [:cat ::branch-descriptor-request] ::descriptor]}
  [{::keys [source-descriptor runtime-cluster target-database-name
            target-coordinate process-dir log-dir http-port http-port-file
            writable-blob-dir]}]
  (let [source-runtime (::runtime source-descriptor)
        source-database (::database source-descriptor)
        source-attachment (::coordinate/attachment source-database)
        target-attachment (coordinate/attachment target-coordinate)
        source-blobs (::blob-storage-view source-descriptor)
        read-only-dirs
        (vec
         (distinct
          (map normalize-path
               (cons (:my.blob/writable-dir source-blobs)
                     (:my.blob/read-only-dirs source-blobs)))))
        source-bases (conj read-only-dirs
                           (normalize-path
                            (::protocol/database-path source-database)))
        target-private
        (mapv normalize-path
              [process-dir log-dir http-port-file writable-blob-dir])]
    (invariant! (some? source-attachment)
                "A branch launch requires its writer-owned source attachment."
                {::source-descriptor source-descriptor})
    (invariant! (= (::coordinate/database-id source-attachment)
                   (::coordinate/database-id target-attachment))
                "A native branch must retain its source database identity."
                {::coordinate/attachment source-attachment
                 ::target-coordinate target-coordinate})
    (invariant! (not= :db (::coordinate/branch target-attachment))
                "A branch launch cannot target the protected main branch."
                {::target-coordinate target-coordinate})
    (invariant! (not-any? true?
                          (for [target target-private
                                source source-bases]
                            (paths-overlap? target source)))
                "Branch-private paths must not overlap source database or blob bases."
                {::target-private-paths target-private
                 ::source-paths source-bases})
    (validate-descriptor
     {::runtime
      (cond->
       {::runtime-cluster runtime-cluster
        ::artifact-flavor (::artifact-flavor source-runtime)
        ::client-build-id (::client-build-id source-runtime)
        :seon.client/launch-capability {:seon.client/autonomous? false}}
        (::execution-build-id source-runtime)
        (assoc ::execution-build-id (::execution-build-id source-runtime))
        (::execution-output source-runtime)
        (assoc ::execution-output (::execution-output source-runtime)))
      ::database
      {::protocol/database-name target-database-name
       ::coordinate/attachment target-attachment
       ::coordinate/coordinate target-coordinate
       ::protocol/backend (::protocol/backend source-database)
       ::protocol/database-path (::protocol/database-path source-database)}
      ::writer-owner (::writer-owner source-descriptor)
      ::process
      {::process-dir (normalize-path process-dir)
       ::log-dir (normalize-path log-dir)
       ::http-port http-port
       ::http-port-file (normalize-path http-port-file)}
      ::blob-storage-view
      {:my.blob/writable-dir (normalize-path writable-blob-dir)
       :my.blob/read-only-dirs read-only-dirs}})))
