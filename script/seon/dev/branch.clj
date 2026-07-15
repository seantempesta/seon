(ns seon.dev.branch
  "Retained native-branch intent and pod-only open/close transitions."
  (:require [babashka.fs :as fs]
            [malli.core :as m]
            [seon.db.coordinate :as coordinate]
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
(schema/register! ::target-head ::coordinate/coordinate)
(schema/register! ::release-request ::protocol/release-database-request)
(schema/register! ::release-response ::protocol/release-database-response)
(schema/register! ::delete-request ::protocol/delete-branch-request)
(schema/register! ::delete-response ::protocol/delete-branch-response)
(schema/register! ::absence-response ::protocol/failed-response)

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
  [::absence-response {:optional true} ::absence-response]])

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
         (cond-> {::protocol/database-name (::protocol/database-name database)
                  ::protocol/backend (::protocol/backend database)}
           (::coordinate/attachment database)
           (assoc ::coordinate/attachment
                  (::coordinate/attachment database))
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
      (throw (ex-info "The writer ensured different source coordinates."
                      {:seon.dev.branch/expected expected
                       :seon.dev.branch/actual actual})))
    (launch/with-coordinate
     {::launch/descriptor descriptor
      ::coordinate/coordinate (::coordinate/coordinate response)})))

(defn- target-private
  [request]
  (select-keys request [::runtime-cluster ::target-database-name
                        ::target-branch ::process-dir ::log-dir ::http-port
                        ::http-port-file ::writable-blob-dir]))

(defn- new-intent
  [request source-descriptor]
  (let [source-coordinate
        (get-in source-descriptor [::launch/database ::coordinate/coordinate])
        create-request
        (protocol/create-branch-request
         {::protocol/source-database-name
          (get-in source-descriptor [::launch/database
                                     ::protocol/database-name])
          ::protocol/target-database-name (::target-database-name request)
          ::protocol/source-coordinate source-coordinate
          ::protocol/expected-source-head source-coordinate
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
        without-current-coordinate
        #(update % ::launch/database
                 dissoc ::coordinate/attachment ::coordinate/coordinate)]
    (when-not (= (without-current-coordinate retained)
                 (without-current-coordinate current))
      (throw (ex-info "The retained branch intent names another source owner."
                      {:seon.dev.branch/retained-source retained
                       :seon.dev.branch/current-source current}))))
  record)

(defn- expected-target-coordinate
  [record]
  (assoc (::protocol/source-coordinate (::create-request record))
         ::coordinate/branch
         (::protocol/target-branch (::create-request record))))

(defn- require-create-response!
  [record response]
  (let [request (::create-request record)
        source-database (::launch/database (::source-descriptor record))
        target-coordinate (::protocol/coordinate response)
        expected-attachment
        (coordinate/attachment (expected-target-coordinate record))]
    (when-not (and (= (::protocol/target-database-name request)
                      (::protocol/target-database-name response))
                   (= expected-attachment (::protocol/target-attachment response))
                   (= expected-attachment (coordinate/attachment target-coordinate))
                   (= (::protocol/backend source-database)
                      (::protocol/backend response))
                   (= (::protocol/database-path source-database)
                      (::protocol/database-path response))
                   (not= (::protocol/created? response)
                         (::protocol/adopted? response))
                   (or (::protocol/adopted? response)
                       (= (expected-target-coordinate record)
                          target-coordinate)))
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
      ::launch/target-coordinate (expected-target-coordinate record)
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
        desired (::desired-state record)
        phase (::phase record)
        allowed-phases
        (case desired
          :seon.dev.branch.state/open
          #{:seon.dev.branch.phase/intent-retained
            :seon.dev.branch.phase/branch-retained
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
                   (= (::coordinate/coordinate source-database)
                      (::protocol/source-coordinate create-request))
                   (= (::protocol/source-coordinate create-request)
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
    (when target-head
      (when-not (= (coordinate/attachment target-head)
                   (get-in descriptor
                           [::launch/database ::coordinate/attachment]))
        (consistency-fail! "The retained close head names another attachment."
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
            attachment (::coordinate/attachment target-database)
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
          (when-not (and (= target-name
                             (::protocol/target-database-name release-request)
                             (::protocol/target-database-name release-response)
                             (::protocol/target-database-name delete-request))
                         (= attachment
                            (::protocol/target-attachment release-request)
                            (::protocol/target-attachment release-response)
                            (::protocol/target-attachment delete-request))
                         (= target-head
                            (::protocol/expected-target-head release-request)
                            (::protocol/expected-target-head delete-request))
                         (= (::protocol/source-database-name create-request)
                            (::protocol/source-database-name delete-request))
                         (or (nil? delete-response)
                             (and (= target-name
                                     (::protocol/target-database-name
                                      delete-response))
                                  (= attachment
                                     (::protocol/target-attachment
                                      delete-response)))))
            (consistency-fail! "Retained inverse evidence names another lifecycle."
                               record)))))
    record))

(defn- ensure-created!
  [lifecycle-path record]
  (if (::launch-descriptor record)
    record
    (let [response
          (->> (::create-request record)
               (writer-call! (::source-descriptor record))
               (require-success! ::protocol/create-branch-response)
               (require-create-response! record))
          descriptor (compose-descriptor record)]
      (write-record!
       lifecycle-path
       (assoc record
              ::phase :seon.dev.branch.phase/branch-retained
              ::create-response response
              ::launch-descriptor descriptor)))))

(defn- branch-configuration
  [configuration record]
  (config/select-launch-descriptor configuration (::launch-descriptor record)))

(defn- process-absent!
  [configuration]
  (when (or (process/read-process configuration process/pod-id)
            (seq (process/ownership-conflicts configuration [process/pod-id])))
    (throw (ex-info "The target pod absence is unproved."
                    {:seon.dev.branch/process process/pod-id})))
  true)

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
                   (= (::coordinate/attachment target-database)
                      (coordinate/attachment
                       (::coordinate/coordinate ensured))))
      (throw (ex-info "The writer ensured a different target attachment."
                      {:seon.dev.branch/target-database target-database
                       :seon.dev.branch/ensure-response ensured})))
    ensured))

(defn- require-target-response!
  [schema-key target-database response]
  (let [response (require-success! schema-key response)]
    (when-not (and (= (::protocol/database-name target-database)
                      (::protocol/target-database-name response))
                   (= (::coordinate/attachment target-database)
                      (::protocol/target-attachment response)))
      (throw (ex-info "The writer response names a different target."
                      {:seon.dev.branch/target-database target-database
                       :seon.dev.branch/response response})))
    response))

(defn- close-record!
  [configuration lifecycle-path record]
  (let [closing (write-record!
                 lifecycle-path
                 (assoc record
                        ::desired-state :seon.dev.branch.state/closed
                        ::phase :seon.dev.branch.phase/stopping-pod))
        target-config (branch-configuration configuration closing)]
    (process/stop! target-config process/pod-id)
    (process-absent! target-config)
    (let [descriptor (::launch-descriptor closing)
          target-database (::launch/database descriptor)
          ensure-request
          (protocol/ensure-database-request
           {::protocol/database-name (::protocol/database-name target-database)
            ::protocol/backend (::protocol/backend target-database)
            ::coordinate/attachment (::coordinate/attachment target-database)
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
              current-head (::coordinate/coordinate ensured)
              attachment (::coordinate/attachment target-database)
              release-request
              (protocol/release-database-request
               {::protocol/target-database-name
                (::protocol/database-name target-database)
                ::protocol/target-attachment attachment
                ::protocol/expected-target-head current-head})
              release-response
              (require-target-response!
               ::protocol/release-database-response target-database
               (writer-call! descriptor release-request))
              delete-request
              (protocol/delete-branch-request
               {::protocol/source-database-name
                (::protocol/source-database-name (::create-request closing))
                ::protocol/target-database-name
                (::protocol/database-name target-database)
                ::protocol/target-attachment attachment
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
          closed)))))

(defn open!
  "Retain exact intent, create/adopt one branch, and start only its pod."
  {:malli/schema [:=> [:cat ::open-request] ::record]}
  [request]
  (validate! ::open-request request "The branch open request is invalid.")
  (let [configuration (::configuration request)
        lifecycle-path (::lifecycle-path request)]
    (state/with-lock
     configuration :branch 30000
     (fn []
       (let [manifest (source-manifest! configuration)
             retained (read-record! lifecycle-path)
             record
             (if retained
               (require-retained-request! retained request configuration)
               (write-record!
                lifecycle-path
                (new-intent request
                            (exact-source-descriptor! configuration))))
             created (ensure-created! lifecycle-path record)
             target-config (branch-configuration configuration created)
             pod (get (process/specs target-config manifest) process/pod-id)
             converged? (process/converged? target-config pod)]
         (write-record! lifecycle-path
                        (assoc created ::phase
                               :seon.dev.branch.phase/pod-starting))
         (try
           (process/ensure! target-config pod)
           (write-record! lifecycle-path
                          (assoc created ::phase
                                 :seon.dev.branch.phase/ready))
           (catch Throwable throwable
             (when-not converged?
               (try
                 (close-record! configuration lifecycle-path created)
                 (catch Throwable cleanup
                   (throw
                    (ex-info "Branch pod launch and exact cleanup both failed."
                             {:seon.dev.branch/launch-error (.toString throwable)
                              :seon.dev.branch/cleanup-error (.toString cleanup)}
                             cleanup)))))
             (throw throwable))))))))

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
