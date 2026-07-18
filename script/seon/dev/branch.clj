(ns seon.dev.branch
  "Retained native-branch intent and pod-only open/close transitions."
  (:require [babashka.fs :as fs]
            [malli.core :as m]
            [seon.db.branch :as db.branch]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.dev.artifact :as artifact]
            [seon.dev.config :as config]
            [seon.dev.process :as process]
            [seon.dev.state :as state]
            [seon.launch :as launch]
            [seon.schema :as schema]))

(schema/register! ::lifecycle-path [:string {:min 1}])
(schema/register! ::runtime-cluster [:string {:min 1}])
(schema/register! ::target-database-name ::protocol/database-name)
(schema/register! ::target-branch :keyword)
(schema/register! ::process-dir ::launch/process-dir)
(schema/register! ::log-dir ::launch/log-dir)
(schema/register! ::http-port ::launch/http-port)
(schema/register! ::http-port-file ::launch/http-port-file)
(schema/register! ::writable-blob-dir ::launch/path)
(schema/register!
 ::name
 [:re "\\A[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\z"])
(schema/register! ::branch-head-at-launch ::db.branch/head)
(schema/register! ::desired-state [:enum :seon.dev.branch.state/open
                                    :seon.dev.branch.state/closed])
(schema/register!
 ::phase
 [:enum :seon.dev.branch.phase/intent-retained
  :seon.dev.branch.phase/branch-retained
  :seon.dev.branch.phase/pod-starting
  :seon.dev.branch.phase/ready
  :seon.dev.branch.phase/stopping-pod
  :seon.dev.branch.phase/closed])
(schema/register! ::source-descriptor ::launch/descriptor)
(schema/register! ::create-request ::protocol/create-branch-request)
(schema/register! ::create-response ::protocol/create-branch-response)
(schema/register! ::launch-descriptor ::launch/descriptor)
(schema/register! ::target-head ::db.branch/head)
(schema/register! ::release-request ::protocol/release-database-request)
(schema/register! ::release-response ::protocol/release-database-response)
(schema/register! ::delete-request ::protocol/delete-branch-request)
(schema/register! ::delete-response ::protocol/delete-branch-response)
(schema/register! ::absence-response ::protocol/failed-response)
(schema/register! ::stop-result process/clean-or-force-result-schema)

(schema/register!
 ::process-status
 [:map {:closed true}
  [:seon.dev.process/status :keyword]
  [:seon.dev.process/ready? :boolean]
  [:seon.dev.process/pid {:optional true} [:int {:min 1}]]
  [:seon.dev.process/start-instant {:optional true} :string]
  [:seon.dev.process/environment-digest {:optional true} :string]
  [:seon.dev.process/artifact-digest {:optional true} :string]
  [:seon.dev.process/log {:optional true} :string]
  [:seon.dev.process/owner-process-dir {:optional true} :string]
  [:seon.dev.process/readiness {:optional true} :keyword]
  [:seon.dev.process/recorded-artifact-digest {:optional true} :string]])
(schema/register! ::processes [:map-of :keyword ::process-status])
(schema/register!
 ::artifact
 [:map {:closed true}
  [:seon.dev.artifact/flavor {:optional true} :keyword]
  [:seon.dev.artifact/client-build-id {:optional true} :string]
  [:seon.dev.artifact/application-digest {:optional true} :string]
  [:seon.dev.artifact/writer-digest {:optional true} :string]
  [:seon.dev.artifact/client-digest {:optional true} :string]
  [:seon.dev.artifact/bootstrap-digest {:optional true} :string]
  [:seon.dev.artifact/runtime-root {:optional true} :string]])
(schema/register!
 ::endpoints
 [:map {:closed true}
  [:seon.dev.endpoint/cljs-build-id {:optional true} :string]
  [:seon.dev.endpoint/web {:optional true} :string]
  [:seon.dev.endpoint/clj {:optional true} :string]
  [:seon.dev.endpoint/cljs {:optional true} :string]])

(schema/register!
 ::target-private
 [:map {:closed true}
  [::runtime-cluster ::runtime-cluster]
  [::target-database-name ::target-database-name]
  [::target-branch ::target-branch]
  [::process-dir ::process-dir]
  [::log-dir ::log-dir]
  [::http-port ::http-port]
  [::http-port-file ::http-port-file]
  [::writable-blob-dir ::writable-blob-dir]])

(schema/register!
 ::open-request
 [:map {:closed true}
  [::configuration config/configuration-schema]
  [::lifecycle-path ::lifecycle-path]
  [::runtime-cluster ::runtime-cluster]
  [::target-database-name ::target-database-name]
  [::target-branch ::target-branch]
  [::process-dir ::process-dir]
  [::log-dir ::log-dir]
  [::http-port ::http-port]
  [::http-port-file ::http-port-file]
  [::writable-blob-dir ::writable-blob-dir]])

(schema/register!
 ::close-request
 [:map {:closed true}
  [::configuration config/configuration-schema]
  [::lifecycle-path ::lifecycle-path]])

(schema/register!
 ::target-request
 [:map {:closed true}
  [::configuration config/configuration-schema]
  [::name ::name]])

(schema/register!
 ::record
 [:map {:closed true}
  [::desired-state ::desired-state]
  [::phase ::phase]
  [::source-descriptor ::source-descriptor]
  [::create-request ::create-request]
  [::target-private ::target-private]
  [::create-response {:optional true} ::create-response]
  [::launch-descriptor {:optional true} ::launch-descriptor]
  [::target-head {:optional true} ::target-head]
  [::release-request {:optional true} ::release-request]
  [::release-response {:optional true} ::release-response]
  [::delete-request {:optional true} ::delete-request]
  [::delete-response {:optional true} ::delete-response]
  [::absence-response {:optional true} ::absence-response]
  [::stop-result {:optional true} ::stop-result]])

(schema/register!
 ::status
 [:map {:closed true}
  [:seon.dev.target/status :keyword]
  [:seon.dev.target/name [:= :seon.dev.target/branch]]
  [:seon.dev.target/cluster-name {:optional true} :string]
  [:seon.dev.target/database-path {:optional true} :string]
  [:seon.dev.target/artifact {:optional true} ::artifact]
  [:seon.dev.target/processes ::processes]
  [:seon.dev.target/external-dependencies ::processes]
  [:seon.dev.target/endpoints ::endpoints]
  [:seon.dev.target/url {:optional true} :string]
  [::lifecycle-path ::lifecycle-path]
  [::desired-state ::desired-state]
  [::phase ::phase]
  [::target-private ::target-private]
  [::runtime-cluster ::runtime-cluster]
  [::target-database-name ::target-database-name]
  [::target-branch ::target-branch]
  [::branch-head-at-launch ::branch-head-at-launch]
  [::launch-descriptor {:optional true} ::launch-descriptor]])

(defn- validate!
  [schema-key value message]
  (when-not (m/validate schema-key value)
    (throw
     (ex-info message
              {:seon.dev.branch/explanation
               (mapv #(select-keys % [:path :in :type])
                     (:errors (m/explain schema-key value)))})))
  value)

(defn- writer-call!
  [descriptor request]
  (with-open [channel
              (uds/connect!
               (get-in descriptor
                       [::launch/writer-owner ::launch/request-socket-path]))]
    (uds/call! {::uds/channel channel ::uds/message request})))

(defn- branch-head
  [database-value]
  (let [[store-id branch] (:store-id database-value)]
    {::db.branch/store-id store-id
     ::db.branch/name branch
     ::db.branch/commit-id (:datahike/commit-id database-value)
     ::db.branch/basis-t (:t database-value)}))

(defn- require-success!
  [schema-key response]
  (when-not (::protocol/success? response)
    (throw (ex-info "The database writer rejected a branch lifecycle request."
                    {:seon.dev.branch/response response
                     :seon.error/kind (::protocol/error-kind response)})))
  (validate! schema-key response
             "The database writer returned an invalid lifecycle response."))

(defn- source-manifest!
  [configuration]
  (let [manifest (artifact/read-manifest configuration)]
    (when-not manifest
      (throw (ex-info "The source artifact manifest is absent."
                      {:seon.dev.branch/artifact-manifest
                       (:seon.dev.config/artifact-manifest configuration)})))
    (let [specs (process/specs configuration manifest)
          unavailable
          (->> [process/watcher-id process/writer-id]
               (remove (fn [id]
                         (when-let [record (process/read-process configuration id)]
                           (process/ready? configuration (get specs id) record))))
               vec)]
      (when (seq unavailable)
        (throw (ex-info "The source watcher or writer is unavailable."
                        {:seon.dev.branch/unavailable-source-processes
                         unavailable}))))
    manifest))

(defn- exact-source-descriptor!
  [configuration]
  (let [descriptor (:seon.dev.config/launch-descriptor configuration)
        database (::launch/database descriptor)
        request
        (protocol/ensure-database-request
         (cond-> {::protocol/request-id (str (random-uuid))
                  ::protocol/database-name (::protocol/database-name database)
                  ::protocol/backend (::protocol/backend database)}
           (::db.branch/connection-id database)
           (assoc ::db.branch/connection-id
                  (::db.branch/connection-id database))
           (::protocol/database-path database)
           (assoc ::protocol/database-path
                  (::protocol/database-path database))))
        response
        (require-success! ::protocol/ensure-database-response
                          (writer-call! descriptor request))
        expected
        (select-keys database [::protocol/database-name ::protocol/backend
                               ::protocol/database-path])
        actual
        (select-keys response [::protocol/database-name ::protocol/backend
                               ::protocol/database-path])]
    (when-not (= expected actual)
      (throw (ex-info "The writer ensured different source branch-heads."
                      {:seon.dev.branch/expected expected
                       :seon.dev.branch/actual actual})))
    (launch/with-branch-head
     {::launch/descriptor descriptor
      ::db.branch/head
      (branch-head (:seon.db/db response))})))

(defn- target-private
  [request]
  (select-keys request [::runtime-cluster ::target-database-name
                        ::target-branch ::process-dir ::log-dir ::http-port
                        ::http-port-file ::writable-blob-dir]))

(defn- branch-record-directory
  [configuration]
  (let [descriptor (:seon.dev.config/launch-descriptor configuration)]
    (str (fs/path (get-in descriptor [::launch/process
                                      ::launch/process-dir])
                  "branches"))))

(defn request
  "Derive one validated retained-branch request from a public name."
  {:malli/schema [:=> [:cat ::target-request] ::open-request]}
  [{::keys [configuration name] :as target-request}]
  (validate! ::target-request target-request
             "The public branch target is invalid.")
  (let [descriptor (:seon.dev.config/launch-descriptor configuration)
        source-runtime (get-in descriptor [::launch/runtime
                                           ::launch/runtime-cluster])
        source-process-dir (get-in descriptor [::launch/process
                                               ::launch/process-dir])
        source-log-dir (get-in descriptor [::launch/process ::launch/log-dir])
        runtime-cluster (str source-runtime "-" name)]
    (validate!
     ::open-request
     {::configuration configuration
      ::lifecycle-path
      (str (fs/path (branch-record-directory configuration)
                    (str runtime-cluster ".edn")))
      ::runtime-cluster runtime-cluster
      ::target-database-name runtime-cluster
      ::target-branch (keyword "seon.branch" runtime-cluster)
      ::process-dir
      (str (fs/path source-process-dir "branch-processes" runtime-cluster))
      ::log-dir (str (fs/path source-log-dir "branches" runtime-cluster))
      ::http-port 0
      ::http-port-file
      (str (fs/path source-process-dir "branch-ports"
                    (str runtime-cluster ".port")))
      ::writable-blob-dir
      (str (fs/path (:seon.dev.config/root configuration)
                    "data" "branches" runtime-cluster "blobs"))}
     "The derived public branch request is invalid.")))

(defn- new-intent
  [request source-descriptor]
  (let [source-branch-head
        (get-in source-descriptor [::launch/database ::db.branch/head])
        create-request
        (protocol/create-branch-request
         {::protocol/request-id (str (random-uuid))
          ::protocol/source-database-name
          (get-in source-descriptor [::launch/database
                                     ::protocol/database-name])
          ::protocol/target-database-name (::target-database-name request)
          ::protocol/source-branch-head source-branch-head
          ::protocol/expected-source-head source-branch-head
          ::protocol/target-branch (::target-branch request)})]
    {::desired-state :seon.dev.branch.state/open
     ::phase :seon.dev.branch.phase/intent-retained
     ::source-descriptor source-descriptor
     ::create-request create-request
     ::target-private (target-private request)}))

(declare validate-record-consistency! branch-missing?)

(defn- read-record!
  [path]
  (when-let [record (state/read-edn path)]
    (->> (validate! ::record record
                    "The retained branch lifecycle record is invalid.")
         (validate-record-consistency!))))

(defn- write-record!
  [path record]
  (state/write-edn!
   path
   (->> (validate! ::record record
                   "Refusing to publish an invalid branch lifecycle record.")
        (validate-record-consistency!))))

(defn- require-retained-request!
  [record request configuration]
  (when-not (= :seon.dev.branch.state/open (::desired-state record))
    (throw (ex-info "The retained branch intent is already closing."
                    {:seon.dev.branch/desired-state
                     (::desired-state record)})))
  (when-not (= (::target-private record) (target-private request))
    (throw (ex-info "The retained branch intent names another target."
                    {:seon.dev.branch/retained (::target-private record)
                     :seon.dev.branch/requested (target-private request)})))
  (let [retained (::source-descriptor record)
        current (:seon.dev.config/launch-descriptor configuration)
        without-current-branch-head
        #(update % ::launch/database
                 dissoc ::db.branch/connection-id ::db.branch/head)]
    (when-not (= (without-current-branch-head retained)
                 (without-current-branch-head current))
      (throw (ex-info "The retained branch intent names another source owner."
                      {:seon.dev.branch/retained-source retained
                       :seon.dev.branch/current-source current}))))
  record)

(defn- expected-target-branch-head
  [record]
  (assoc (::protocol/source-branch-head (::create-request record))
         ::db.branch/name
         (::protocol/target-branch (::create-request record))))

(defn- require-create-response!
  [record response]
  (let [request (::create-request record)
        source-database (::launch/database (::source-descriptor record))
        target-branch-head (::protocol/branch-head response)
        expected-connection-id
        (db.branch/connection-id (expected-target-branch-head record))]
    (when-not (and (= (::protocol/target-database-name request)
                      (::protocol/target-database-name response))
                   (= expected-connection-id (::protocol/target-connection-id response))
                   (= expected-connection-id (db.branch/connection-id target-branch-head))
                   (= (::protocol/backend source-database)
                      (::protocol/backend response))
                   (= (::protocol/database-path source-database)
                      (::protocol/database-path response))
                   (not= (::protocol/created? response)
                         (::protocol/adopted? response))
                   (or (::protocol/adopted? response)
                       (= (expected-target-branch-head record)
                          target-branch-head)))
      (throw (ex-info "The branch create response does not match retained intent."
                      {:seon.dev.branch/create-request request
                       :seon.dev.branch/create-response response})))
    response))

(defn- compose-descriptor
  [record]
  (let [target (::target-private record)]
    (launch/branch-descriptor
     {::launch/source-descriptor (::source-descriptor record)
      ::launch/runtime-cluster (::runtime-cluster target)
      ::launch/target-database-name (::target-database-name target)
      ::launch/target-branch-head (expected-target-branch-head record)
      ::launch/process-dir (::process-dir target)
      ::launch/log-dir (::log-dir target)
      ::launch/http-port (::http-port target)
      ::launch/http-port-file (::http-port-file target)
      ::launch/writable-blob-dir (::writable-blob-dir target)})))

(defn- consistency-fail!
  [message record]
  (throw (ex-info message {:seon.dev.branch/record record})))

(defn- validate-record-consistency!
  [record]
  (let [source-descriptor (::source-descriptor record)
        source-database (::launch/database source-descriptor)
        create-request (::create-request record)
        target (::target-private record)
        descriptor (::launch-descriptor record)
        create-response (::create-response record)
        target-head (::target-head record)
        release-request (::release-request record)
        release-response (::release-response record)
        delete-request (::delete-request record)
        delete-response (::delete-response record)
        absence-response (::absence-response record)
        stop-result (::stop-result record)
        desired (::desired-state record)
        phase (::phase record)
        allowed-phases
        (case desired
          :seon.dev.branch.state/open
          #{:seon.dev.branch.phase/intent-retained
            :seon.dev.branch.phase/branch-retained
            :seon.dev.branch.phase/stopping-pod
            :seon.dev.branch.phase/pod-starting
            :seon.dev.branch.phase/ready}
          :seon.dev.branch.state/closed
          #{:seon.dev.branch.phase/stopping-pod
            :seon.dev.branch.phase/closed})]
    (when-not (contains? allowed-phases phase)
      (consistency-fail! "The retained desired state and phase disagree."
                         record))
    (when-not (and (= (::protocol/database-name source-database)
                      (::protocol/source-database-name create-request))
                   (= (::db.branch/head source-database)
                      (::protocol/source-branch-head create-request))
                   (= (::protocol/source-branch-head create-request)
                      (::protocol/expected-source-head create-request))
                   (= (::target-database-name target)
                      (::protocol/target-database-name create-request))
                   (= (::target-branch target)
                      (::protocol/target-branch create-request)))
      (consistency-fail! "The retained create request disagrees with its source or target."
                         record))
    ;; Compose even before writer mutation. Besides deriving one authority,
    ;; this proves target-private path non-overlap before create can run.
    (let [expected-descriptor (compose-descriptor record)]
      (when (and descriptor (not= expected-descriptor descriptor))
        (consistency-fail! "The retained launch descriptor disagrees with its intent."
                           record)))
    (when-not (= (some? descriptor) (some? create-response))
      (consistency-fail! "Create response and launch descriptor must appear together."
                         record))
    (when (and (= phase :seon.dev.branch.phase/intent-retained) descriptor)
      (consistency-fail! "An intent-only record cannot claim branch publication."
                         record))
    (when (and (not= phase :seon.dev.branch.phase/intent-retained)
               (nil? descriptor))
      (consistency-fail! "A post-create phase requires the retained descriptor."
                         record))
    (when create-response
      (require-create-response! record create-response))
    (when stop-result
      (let [expected-operation
            (if (= desired :seon.dev.branch.state/closed)
              :seon.dev.process.operation/retained-close
              :seon.dev.process.operation/retained-restart)]
        (when-not
         (and (m/validate process/clean-or-force-result-schema stop-result)
              (= expected-operation
                 (:seon.dev.process/operation stop-result))
              (= [process/pod-id]
                 (mapv :seon.dev.process/id
                       (:seon.dev.process/results stop-result))))
          (consistency-fail!
           "The retained pod stop result names another lifecycle."
           record))))
    (when target-head
      (when-not (= (db.branch/connection-id target-head)
                   (get-in descriptor
                           [::launch/database ::db.branch/connection-id]))
        (consistency-fail! "The retained close head names another connection-id."
                           record)))
    (when (and (= desired :seon.dev.branch.state/open)
               (some some? [target-head release-request release-response
                            delete-request delete-response absence-response]))
      (consistency-fail! "An open record cannot contain close evidence."
                         record))
    (when (and (= phase :seon.dev.branch.phase/stopping-pod)
               (some some? [target-head release-request release-response
                            delete-request delete-response absence-response]))
      (consistency-fail! "A stopping record cannot claim an unretained inverse."
                         record))
    (when (= phase :seon.dev.branch.phase/closed)
      (let [target-database (::launch/database descriptor)
            target-name (::protocol/database-name target-database)
            connection-id (::db.branch/connection-id target-database)
            absent-before-release? (and absence-response (nil? target-head))
            released-and-deleted?
            (and target-head release-request release-response delete-request
                 (or delete-response absence-response))]
        (when-not (or absent-before-release? released-and-deleted?)
          (consistency-fail! "A closed record lacks complete inverse evidence."
                             record))
        (when (and absence-response
                   (not (branch-missing? absence-response)))
          (consistency-fail! "Closed absence evidence is not branch-missing."
                             record))
        (when released-and-deleted?
          (let [released-db (:seon.db/db release-request)]
            (when-not (and (= target-name
                               (:db-name released-db)
                               (::protocol/target-database-name delete-request))
                           (= connection-id
                              (::protocol/target-connection-id delete-request))
                           (= (::db.branch/basis-t target-head) (:t released-db))
                           (= (::db.branch/commit-id target-head)
                              (:datahike/commit-id released-db))
                         (= target-head
                            (::protocol/expected-target-head delete-request))
                         (= (::protocol/source-database-name create-request)
                            (::protocol/source-database-name delete-request))
                         (or (nil? delete-response)
                             (and (= target-name
                                     (::protocol/target-database-name
                                      delete-response))
                                  (= connection-id
                                     (::protocol/target-connection-id
                                      delete-response)))))
              (consistency-fail!
               "Retained inverse evidence names another lifecycle."
               record))))))
    record))

(defn- ensure-created!
  ([lifecycle-path record]
   (ensure-created! lifecycle-path record (constantly nil)))
  ([lifecycle-path record retain-created!]
   (if (::launch-descriptor record)
     record
     (let [response
           (->> (::create-request record)
                (writer-call! (::source-descriptor record))
                (require-success! ::protocol/create-branch-response)
                (require-create-response! record))
           descriptor (compose-descriptor record)
           created (assoc record
                          ::phase :seon.dev.branch.phase/branch-retained
                          ::create-response response
                          ::launch-descriptor descriptor)]
       ;; Retain the exact inverse before publishing the post-mutation phase.
       ;; The surrounding ownership monitor prevents shutdown from observing
       ;; a created native branch without either this inverse or its record.
       (when (::protocol/created? response)
         (retain-created! created))
       (write-record! lifecycle-path created)))))

(defn- branch-configuration
  [configuration record]
  (config/select-launch-descriptor configuration (::launch-descriptor record)))

(defn- retained-open-record!
  [configuration lifecycle-path]
  (let [record
        (or (read-record! lifecycle-path)
            (throw (ex-info "No retained branch intent exists."
                            {::lifecycle-path lifecycle-path})))
        retained-request
        (merge {::configuration configuration
                ::lifecycle-path lifecycle-path}
               (::target-private record))
        record (require-retained-request! record retained-request configuration)]
    (when-not (::launch-descriptor record)
      (throw (ex-info "The retained branch has no published connection-id."
                      {::lifecycle-path lifecycle-path
                       ::phase (::phase record)})))
    record))

(defn- process-absent!
  [configuration]
  (when (or (process/read-process configuration process/pod-id)
            (seq (process/ownership-conflicts configuration [process/pod-id])))
    (throw (ex-info "The target pod absence is unproved."
                    {:seon.dev.branch/process process/pod-id})))
  true)

(defn- stop-retained-pod!
  [lifecycle-path record configuration operation]
  (let [stopping
        (write-record!
         lifecycle-path
         (-> record
             (dissoc ::stop-result)
             (assoc ::phase :seon.dev.branch.phase/stopping-pod)))
        result
        (process/clean-or-force!
         {:seon.dev.process/configuration configuration
          :seon.dev.process/operation operation
          :seon.dev.process/targets #{process/pod-id}})]
    (write-record! lifecycle-path (assoc stopping ::stop-result result))))

(defn- cleanup-private!
  [record]
  (let [descriptor (::launch-descriptor record)
        process (::launch/process descriptor)
        blobs (::launch/blob-storage-view descriptor)]
    (fs/delete-if-exists (::launch/http-port-file process))
    (doseq [path [(:my.blob/writable-dir blobs)
                  (::launch/log-dir process)
                  (::launch/process-dir process)]]
      (when (fs/exists? path) (fs/delete-tree path)))))

(defn- branch-missing?
  [response]
  (and (false? (::protocol/success? response))
       (= protocol/branch-missing-error (::protocol/error-kind response))))

(defn- require-target-ensure!
  [target-database response]
  (let [ensured
        (require-success! ::protocol/ensure-database-response response)]
    (when-not (and (= (::protocol/database-name target-database)
                      (::protocol/database-name ensured))
                   (= (::protocol/backend target-database)
                      (::protocol/backend ensured))
                   (= (::protocol/database-path target-database)
                      (::protocol/database-path ensured))
                   (= (::db.branch/connection-id target-database)
                      (-> (:seon.db/db ensured)
                          db.branch/head-from-database-value
                          db.branch/connection-id)))
      (throw (ex-info "The writer ensured a different target connection-id."
                      {:seon.dev.branch/target-database target-database
                       :seon.dev.branch/ensure-response ensured})))
    ensured))

(defn- current-target-descriptor!
  [record]
  (let [descriptor (::launch-descriptor record)
        target-database (::launch/database descriptor)
        ensure-request
        (protocol/ensure-database-request
         (cond-> {::protocol/request-id (str (random-uuid))
                  ::protocol/database-name
                  (::protocol/database-name target-database)
                  ::protocol/backend (::protocol/backend target-database)
                  ::db.branch/connection-id
                  (::db.branch/connection-id target-database)}
           (::protocol/database-path target-database)
           (assoc ::protocol/database-path
                  (::protocol/database-path target-database))))
        ensured
        (require-target-ensure!
         target-database (writer-call! descriptor ensure-request))]
    (launch/with-branch-head
     {::launch/descriptor descriptor
      ::db.branch/head
      (branch-head (:seon.db/db ensured))})))

(defn- require-target-response!
  [schema-key target-database response]
  (let [response (require-success! schema-key response)]
    (when-not (and (= (::protocol/database-name target-database)
                      (::protocol/target-database-name response))
                   (= (::db.branch/connection-id target-database)
                      (::protocol/target-connection-id response)))
      (throw (ex-info "The writer response names a different target."
                      {:seon.dev.branch/target-database target-database
                       :seon.dev.branch/response response})))
    response))

(defn- close-record!
  ([configuration lifecycle-path record]
   (close-record! configuration lifecycle-path record false))
  ([configuration lifecycle-path record pod-absence-proved?]
   (if (= :seon.dev.branch.phase/closed (::phase record))
     (do
       (cleanup-private! record)
       (fs/delete-if-exists lifecycle-path)
       record)
     (let [closing-intent
         (write-record!
          lifecycle-path
          (-> record
              (dissoc ::stop-result)
              (assoc ::desired-state :seon.dev.branch.state/closed
                     ::phase :seon.dev.branch.phase/stopping-pod)))
         target-config (branch-configuration configuration closing-intent)
         closing
         (if pod-absence-proved?
           closing-intent
           (stop-retained-pod!
            lifecycle-path closing-intent target-config
            :seon.dev.process.operation/retained-close))]
     (process-absent! target-config)
     (let [descriptor (::launch-descriptor closing)
          target-database (::launch/database descriptor)
          ensure-request
          (protocol/ensure-database-request
           {::protocol/request-id (str (random-uuid))
            ::protocol/database-name (::protocol/database-name target-database)
            ::protocol/backend (::protocol/backend target-database)
            ::db.branch/connection-id (::db.branch/connection-id target-database)
            ::protocol/database-path (::protocol/database-path target-database)})
          ensure-response (writer-call! descriptor ensure-request)]
      (if (branch-missing? ensure-response)
        (let [closed (assoc closing
                            ::phase :seon.dev.branch.phase/closed
                            ::absence-response ensure-response)]
          (write-record! lifecycle-path closed)
          (cleanup-private! closed)
          (fs/delete-if-exists lifecycle-path)
          closed)
        (let [ensured
              (require-target-ensure! target-database ensure-response)
              current-head (branch-head (:seon.db/db ensured))
              connection-id (::db.branch/connection-id target-database)
              release-request
              (protocol/release-database-request
               {::protocol/request-id (str (random-uuid))
                :seon.db/db (:seon.db/db ensured)})
              release-response
              (require-success!
               ::protocol/release-database-response
               (writer-call! descriptor release-request))
              delete-request
              (protocol/delete-branch-request
               {::protocol/request-id (str (random-uuid))
                ::protocol/source-database-name
                (::protocol/source-database-name (::create-request closing))
                ::protocol/target-database-name
                (::protocol/database-name target-database)
                ::protocol/target-connection-id connection-id
                ::protocol/expected-target-head current-head})
              delete-response (writer-call! descriptor delete-request)
              deleted
              (if (branch-missing? delete-response)
                nil
                (require-target-response!
                 ::protocol/delete-branch-response target-database
                 delete-response))
              closed
              (cond-> (assoc closing
                             ::phase :seon.dev.branch.phase/closed
                             ::target-head current-head
                             ::release-request release-request
                             ::release-response release-response
                             ::delete-request delete-request)
                deleted (assoc ::delete-response deleted)
                (branch-missing? delete-response)
                (assoc ::absence-response delete-response))]
          (write-record! lifecycle-path closed)
          (cleanup-private! closed)
          (fs/delete-if-exists lifecycle-path)
          closed)))))))

(defn- open-under-lock!
  [request acquire-owned! initial-record pod-absence-proved?]
  (let [configuration (::configuration request)
        lifecycle-path (::lifecycle-path request)
        manifest (source-manifest! configuration)
        retained (or initial-record (read-record! lifecycle-path))
        record
        (if retained
          (require-retained-request! retained request configuration)
          (write-record!
           lifecycle-path
           (new-intent request (exact-source-descriptor! configuration))))
        owned-branch (atom nil)
        {:seon.dev.branch/keys [created target-config pod]}
        (acquire-owned!
         :seon.dev.branch/native-branch
         (fn []
           (let [created
                 (ensure-created! lifecycle-path record
                                  #(reset! owned-branch %))
                 target-config
                 (branch-configuration configuration created)
                 pod (get (process/specs target-config manifest) process/pod-id)
                 converged? (process/converged? target-config pod)
                 created
                 (cond
                   converged? created
                   pod-absence-proved? created
                   :else
                   (let [stopped
                         (stop-retained-pod!
                          lifecycle-path created target-config
                          :seon.dev.process.operation/retained-restart)]
                     (process-absent! target-config)
                     stopped))]
             ;; A converged pod proves this invocation did not acquire the
             ;; already-live branch runtime.
             (when converged? (reset! owned-branch nil))
             {:seon.dev.branch/created created
              :seon.dev.branch/target-config target-config
              :seon.dev.branch/pod pod}))
         (fn []
           (when-let [created @owned-branch]
             (let [target-config
                   (branch-configuration configuration created)]
               ;; Uncertain pod absence retains the exact native branch.
               (process-absent! target-config)
               (close-record! configuration lifecycle-path created true)))))]
    (write-record! lifecycle-path
                   (assoc created ::phase
                          :seon.dev.branch.phase/pod-starting))
    (process/ensure!
     target-config pod
     (fn [id acquire!]
       (acquire-owned! id acquire! #(process/stop! target-config id))))
    (write-record! lifecycle-path
                   (assoc created ::phase :seon.dev.branch.phase/ready))))

(defn open!
  "Retain exact intent, create/adopt one branch, and start only its pod."
  {:malli/schema [:=> [:cat ::open-request] ::record]}
  [request]
  (validate! ::open-request request "The branch open request is invalid.")
  (let [configuration (::configuration request)]
    (process/with-startup-ownership
     configuration
     (fn [acquire-owned!]
       (state/with-lock
        configuration :branch 30000
        #(open-under-lock! request acquire-owned! nil false))))))

(defn current-descriptor!
  "Read the retained branch's exact current descriptor from the writer."
  {:malli/schema [:=> [:cat ::close-request] ::launch/descriptor]}
  [request]
  (validate! ::close-request request
             "The retained branch descriptor request is invalid.")
  (let [configuration (::configuration request)
        lifecycle-path (::lifecycle-path request)]
    (state/with-lock
     configuration :branch 30000
     #(current-target-descriptor!
       (retained-open-record! configuration lifecycle-path)))))

(defn stop!
  "Stop one retained pod while preserving its open branch and record."
  {:malli/schema [:=> [:cat ::close-request] ::record]}
  [request]
  (validate! ::close-request request
             "The retained branch stop request is invalid.")
  (let [configuration (::configuration request)
        lifecycle-path (::lifecycle-path request)]
    (state/with-lock
     configuration :branch 30000
     (fn []
       (let [record (retained-open-record! configuration lifecycle-path)
             target-config (branch-configuration configuration record)
             stopped
             (cond
               (and (= :seon.dev.branch.phase/branch-retained (::phase record))
                    (::stop-result record))
               record

               (and (= :seon.dev.branch.phase/stopping-pod (::phase record))
                    (::stop-result record))
               record

               :else
               (stop-retained-pod!
                lifecycle-path record target-config
                :seon.dev.process.operation/retained-restart))]
         (process-absent! target-config)
         (if (= :seon.dev.branch.phase/branch-retained (::phase stopped))
           stopped
           (write-record!
            lifecycle-path
            (assoc stopped ::phase :seon.dev.branch.phase/branch-retained))))))))

(defn close!
  "Stop one retained branch pod and delete its exact current native branch."
  {:malli/schema [:=> [:cat ::close-request] ::record]}
  [request]
  (validate! ::close-request request "The branch close request is invalid.")
  (let [configuration (::configuration request)
        lifecycle-path (::lifecycle-path request)]
    (state/with-lock
     configuration :branch 30000
     (fn []
       (let [record (or (read-record! lifecycle-path)
                        (throw (ex-info "No retained branch intent exists."
                                        {::lifecycle-path lifecycle-path})))]
         (close-record! configuration lifecycle-path
                        (ensure-created! lifecycle-path record)))))))

(defn restart!
  "Stop one retained branch pod and reconcile that same open intent."
  {:malli/schema [:=> [:cat ::open-request] ::record]}
  [open-request]
  (validate! ::open-request open-request
             "The branch restart request is invalid.")
  (let [configuration (::configuration open-request)
        lifecycle-path (::lifecycle-path open-request)]
    (process/with-startup-ownership
     configuration
     (fn [acquire-owned!]
       (state/with-lock
        configuration :branch 30000
        (fn []
          (let [record
                (or (read-record! lifecycle-path)
                    (throw (ex-info "No retained branch intent exists."
                                    {::lifecycle-path lifecycle-path})))
                _ (require-retained-request! record open-request configuration)]
            (if (::launch-descriptor record)
              (let [target-config
                    (branch-configuration configuration record)
                    stopped
                    (stop-retained-pod!
                     lifecycle-path record target-config
                     :seon.dev.process.operation/retained-restart)]
                (process-absent! target-config)
                (open-under-lock! open-request acquire-owned! stopped true))
              (open-under-lock!
               open-request acquire-owned! record false)))))))))

(defn- retained-status
  [configuration lifecycle-path record]
  (let [record
        (require-retained-request!
         record
         (merge {::configuration configuration
                 ::lifecycle-path lifecycle-path}
                (::target-private record))
         configuration)
        descriptor (::launch-descriptor record)
        manifest (artifact/read-manifest configuration)
        live (when (and descriptor manifest)
               (let [status (process/status
                             (branch-configuration configuration record)
                             manifest)]
                 (cond-> status
                   (nil? (:seon.dev.target/url status))
                   (dissoc :seon.dev.target/url))))
        target (::target-private record)]
    (cond->
      (merge
       (or live
           {:seon.dev.target/status :seon.dev.target.status/degraded
            :seon.dev.target/name :seon.dev.target/branch
            :seon.dev.target/processes {}
            :seon.dev.target/external-dependencies {}
            :seon.dev.target/endpoints {}})
       {::lifecycle-path lifecycle-path
        ::desired-state (::desired-state record)
        ::phase (::phase record)
        ::target-private target
        ::runtime-cluster (::runtime-cluster target)
        ::target-database-name (::target-database-name target)
        ::target-branch (::target-branch target)
        ::branch-head-at-launch (expected-target-branch-head record)
        :seon.dev.target/name :seon.dev.target/branch})
      descriptor (assoc ::launch-descriptor descriptor))))

(defn status
  "Project one retained branch record with its current process health."
  {:malli/schema
   [:=>
    [:catn [::configuration config/configuration-schema]
     [::name ::name]]
    ::status]}
  [configuration name]
  (let [open-request (request {::configuration configuration ::name name})
        lifecycle-path (::lifecycle-path open-request)
        record
        (or (read-record! lifecycle-path)
            (throw (ex-info "No retained branch intent exists."
                            {::lifecycle-path lifecycle-path})))]
    (retained-status configuration lifecycle-path record)))

(defn inventory
  "Project every validated retained branch record for one source."
  {:malli/schema
   [:=>
    [:catn [::configuration config/configuration-schema]]
    [:vector ::status]]}
  [configuration]
  (let [directory (branch-record-directory configuration)]
    (if-not (fs/directory? directory)
      []
      (->> (fs/list-dir directory "*.edn")
           (sort-by str)
           (mapv (fn [path]
                   (let [lifecycle-path (str path)]
                     (retained-status configuration lifecycle-path
                                      (read-record! lifecycle-path)))))))))
