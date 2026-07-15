(ns seon.dev.restore-state
  "Fsync-durable publication of the portable immutable restore intent."
  (:require [babashka.fs :as fs]
            [babashka.process :as shell]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [malli.core :as m]
            [my.blob.schema]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.restore-admin :as restore-admin]
            [seon.db.transport.uds :as uds]
            [seon.dev.artifact :as artifact]
            [seon.dev.branch :as branch]
            [seon.dev.config :as config]
            [seon.dev.process :as process]
            [seon.dev.restore :as restore]
            [seon.dev.state :as state]
            [seon.launch :as launch]
            [seon.schema :as schema]))

(schema/register! ::cluster-dir ::launch/cluster-dir)
(schema/register! ::configuration config/configuration-schema)
(schema/register! ::published? :boolean)
(schema/register! ::observe! 'fn?)
(schema/register! ::effect! 'fn?)
(schema/register! ::max-transitions [:int {:min 1 :max 32}])
(schema/register! ::transition-number [:int {:min 1 :max 32}])
(schema/register! ::branch-name ::branch/name)
(schema/register! ::admin-timeout-ms ::restore/admin-timeout-ms)
(schema/register! ::intent-retained? :boolean)
(schema/register! ::admin-outcome :keyword)
(schema/register! ::restored-coordinate ::coordinate/coordinate)
(schema/register! ::aborted? [:= true])
(schema/register! ::prior-main-coordinate ::coordinate/coordinate)
(schema/register! ::current-main-coordinate ::coordinate/coordinate)
(schema/register! ::selected-target-coordinate ::coordinate/coordinate)
(schema/register! ::current-target-coordinate ::coordinate/coordinate)
(schema/register!
 ::effect-request
 [:map {:closed true}
  [::restore/intent ::restore/intent]
  [::restore/observation ::restore/observation]
  [::restore/command ::restore/command]])
(schema/register!
 ::transition
 [:map {:closed true}
  [::transition-number ::transition-number]
  [::restore/observation ::restore/observation]
  [::restore/command ::restore/command]])
(schema/register! ::transitions [:vector ::transition])
(schema/register!
 ::convergence-request
 [:map {:closed true}
  [::restore/intent ::restore/intent]
  [::observe! ::observe!]
  [::effect! ::effect!]
  [::max-transitions {:optional true} ::max-transitions]])
(schema/register!
 ::convergence-result
 [:map {:closed true}
  [::restore/intent ::restore/intent]
  [::restore/observation ::restore/observation]
  [::transitions ::transitions]])
(schema/register!
 ::plan-request
 [:map {:closed true}
  [::configuration config/configuration-schema]
  [::branch-name ::branch-name]])
(schema/register!
 ::apply-request
 [:map {:closed true}
  [::configuration config/configuration-schema]
  [::branch-name ::branch-name]
  [::restore/plan ::restore/plan]
  [::restore/confirmation-text ::restore/confirmation-text]
  [::admin-timeout-ms {:optional true} ::admin-timeout-ms]])
(schema/register!
 ::resume-request
 [:map {:closed true}
  [::configuration config/configuration-schema]
  [::branch-name ::branch-name]
  [::admin-timeout-ms {:optional true} ::admin-timeout-ms]])
(schema/register!
 ::restore-result
 [:map {:closed true}
  [::restore/intent-id ::restore/intent-id]
  [::restored-coordinate ::restored-coordinate]
  [::admin-outcome ::admin-outcome]
  [::transitions ::transitions]])
(schema/register!
 ::abort-request
 [:map {:closed true}
  [::configuration config/configuration-schema]
  [::branch-name ::branch-name]
  [::restore/confirmation-text ::restore/confirmation-text]])
(schema/register!
 ::abort-result
 [:map {:closed true}
  [::restore/intent-id ::restore/intent-id]
  [::restore/plan-digest ::restore/plan-digest]
  [::aborted? ::aborted?]
  [::prior-main-coordinate ::prior-main-coordinate]
  [::current-main-coordinate ::current-main-coordinate]
  [::selected-target-coordinate ::selected-target-coordinate]
  [::current-target-coordinate {:optional true} ::current-target-coordinate]])
(schema/register!
 ::publication-request
 [:map {:closed true}
  [::cluster-dir ::cluster-dir]
  [::restore/intent ::restore/intent]])
(schema/register!
 ::publication-result
 [:map {:closed true}
  [::restore/intent-path ::restore/intent-path]
  [::restore/intent ::restore/intent]
  [::published? ::published?]])

(defn read-intent!
  "Read and validate the canonical retained restore intent."
  {:malli/schema [:=> [:cat ::cluster-dir] ::restore/intent]}
  [cluster-dir]
  (let [path (restore/intent-path cluster-dir)
        intent (state/read-edn path)]
    (when-not intent
      (throw (ex-info "No retained restore intent exists."
                      {::restore/intent-path path})))
    (restore/validate-intent intent)))

(defn publish-intent!
  "Durably publish one immutable restore intent."
  {:malli/schema [:=> [:cat ::publication-request] ::publication-result]}
  [{::keys [cluster-dir] intent ::restore/intent :as request}]
  (when-not (m/validate ::publication-request request)
    (throw (ex-info "The restore intent publication request is invalid."
                    {:seon.dev.restore-state/explanation
                     (m/explain ::publication-request request)})))
  (let [intent (restore/validate-intent intent)
        path (restore/intent-path cluster-dir)
        retained (state/read-edn path)]
    (when (and retained (not= intent (restore/validate-intent retained)))
      (throw (ex-info "Another immutable restore intent is already retained."
                      {::restore/intent-path path
                       ::restore/intent-id (::restore/intent-id retained)})))
    (when-not retained
      (state/write-edn! path intent))
    {::restore/intent-path path
     ::restore/intent intent
     ::published? (nil? retained)}))

(defn- retained-intent [configuration]
  (let [path (restore/intent-path
              (:seon.dev.config/cluster-dir configuration))]
    (when (fs/regular-file? path)
      (read-intent! (:seon.dev.config/cluster-dir configuration)))))

(defn- require-manifest! [configuration]
  (or (artifact/read-manifest configuration)
      (throw (ex-info "Restore requires one published artifact manifest."
                      {:seon.dev.artifact/path
                       (:seon.dev.config/artifact-manifest configuration)}))))

(def ^:private artifact-identity-keys
  [:seon.dev.artifact/application-digest
   :seon.dev.artifact/client-digest
   :seon.dev.artifact/bootstrap-digest
   :seon.dev.artifact/css-digest
   :seon.dev.artifact/writer-digest])

(defn- manifest-artifact-identity [manifest]
  (select-keys manifest artifact-identity-keys))

(defn- require-artifact-identity!
  [configuration manifest expected]
  (let [published (manifest-artifact-identity manifest)
        current (artifact/current-output-digests configuration)]
    (when-not (= expected published current)
      (throw
       (ex-info
        "Restore runtime outputs changed after the plan was frozen."
        {:seon.dev.restore/expected-artifact-identity expected
         :seon.dev.restore/published-artifact-identity published
         :seon.dev.restore/current-artifact-identity current})))
    expected))

(defn- writer-call! [descriptor request response-schema]
  (let [socket (get-in descriptor
                       [::launch/writer-owner ::launch/request-socket-path])
        response
        (with-open [channel (uds/connect! socket)]
          (uds/call! {::uds/channel channel ::uds/message request}))]
    (when-not (::protocol/success? response)
      (throw (ex-info "The database writer rejected a restore operation."
                      {:seon.dev.restore-state/request request
                       :seon.dev.restore-state/response response
                       :seon.error/kind (::protocol/error-kind response)})))
    (when-not (m/validate response-schema response)
      (throw (ex-info "The database writer returned invalid restore evidence."
                      {:seon.dev.restore-state/response response
                       :seon.dev.restore-state/explanation
                       (m/explain response-schema response)})))
    response))

(defn- observe-lifecycle! [descriptor]
  (let [database (::launch/database descriptor)]
    (writer-call!
     descriptor
     (protocol/observe-database-lifecycle-request
      {::protocol/database-name (::protocol/database-name database)})
     ::protocol/observe-database-lifecycle-response)))

(defn- post-edn! [url request response-schema]
  (let [result
        (shell/sh
         {:continue true :out :string :err :string
          :cmd ["curl" "--fail-with-body" "--silent" "--show-error"
                "--max-time" "120" "--request" "POST"
                "--header" "Content-Type: application/edn"
                "--data-binary" (pr-str request) url]})
        response
        (when-not (str/blank? (:out result))
          (try (edn/read-string (:out result)) (catch Exception _ nil)))]
    (when-not (and (zero? (:exit result))
                   (m/validate response-schema response))
      (throw (ex-info "The retained-blob operator returned invalid evidence."
                      {:seon.dev.restore-state/url url
                       :seon.dev.restore-state/exit (:exit result)
                       :seon.dev.restore-state/response response
                       :seon.dev.restore-state/error (str/trim (:err result))
                       :seon.dev.restore-state/explanation
                       (m/explain response-schema response)})))
    response))

(defn- retained-blob-url [configuration branch-name]
  (let [status (branch/status configuration branch-name)
        url (:seon.dev.target/url status)]
    (when-not (and (= :seon.dev.target.status/ready
                      (:seon.dev.target/status status))
                   (string? url))
      (throw (ex-info "The selected retained branch pod is not ready."
                      {::branch-name branch-name
                       :seon.dev.target/status
                       (:seon.dev.target/status status)})))
    (str url "/_seon/operator/blobs")))

(defn- observe-retained-blobs! [configuration branch-name target-coordinate]
  (let [response
        (post-edn!
         (retained-blob-url configuration branch-name)
         {:my.blob/operator-operation
          :my.blob.operator.operation/observe-retained
          :my.blob/target-coordinate target-coordinate}
         :my.blob/retained-observation-result)]
    (when-not (true? (:my.blob/ok? response))
      (throw (ex-info "The retained blob set could not be frozen."
                      {:seon.dev.restore-state/response response})))
    response))

(defn- fresh-intent-id []
  (random-uuid))

(defn- branch-close-request [configuration branch-name]
  (let [request (branch/request {::branch/configuration configuration
                                 ::branch/name branch-name})]
    {::branch/configuration configuration
     ::branch/lifecycle-path (::branch/lifecycle-path request)}))

(defn- derive-intent!
  [configuration branch-name manifest intent-id consumer-generations]
  (let [ordinary (:seon.dev.config/launch-descriptor configuration)
        lifecycle (observe-lifecycle! ordinary)
        target-descriptor
        (branch/current-descriptor!
         (branch-close-request configuration branch-name))
        target-coordinate
        (get-in target-descriptor
                [::launch/database ::coordinate/coordinate])
        blob-observation
        (observe-retained-blobs!
         configuration branch-name target-coordinate)
        main-descriptor
        (launch/with-coordinate
         {::launch/descriptor ordinary
          ::coordinate/coordinate (::protocol/main-coordinate lifecycle)})
        intent
        (restore/derive-intent
         {::restore/intent-id intent-id
          ::restore/operation :seon.dev.restore.operation/restore
          ::restore/pre-restore-main-descriptor main-descriptor
          ::restore/selected-target-descriptor target-descriptor
          ::restore/expected-branch-roster
          (restore/reserved-branch-roster
           intent-id (::protocol/branch-roster lifecycle))
          ::restore/protocol-version protocol/current-version
          ::restore/artifact-identity
          (manifest-artifact-identity manifest)
          ::restore/consumer-generations
          consumer-generations
          ::restore/core-overlay-selection
          :seon.dev.restore.overlay/preserve
          ::restore/config-overlay-selection
          :seon.dev.restore.overlay/preserve
          ::restore/reachable-hash-digest
          (:my.blob/reachable-hash-digest blob-observation)})]
    intent))

(defn plan!
  "Read current restore facts and return one effect-free immutable plan."
  {:malli/schema [:=> [:cat ::plan-request] ::restore/plan]}
  [{configuration ::configuration
    branch-name ::branch-name
    :as request}]
  (when-not (m/validate ::plan-request request)
    (throw (ex-info "The retained restore plan request is invalid."
                    {:seon.dev.restore-state/explanation
                     (m/explain ::plan-request request)})))
  (when-let [intent (retained-intent configuration)]
    (throw (ex-info "A retained restore intent already owns this cluster."
                    {::restore/intent-id (::restore/intent-id intent)
                     :seon.error/kind
                     :seon.dev.restore.error/retained-intent})))
  (let [manifest (require-manifest! configuration)
        artifact-identity (manifest-artifact-identity manifest)
        _ (require-artifact-identity!
           configuration manifest artifact-identity)
        intent
        (derive-intent!
         configuration branch-name manifest (fresh-intent-id)
         {process/pod-id (random-uuid)})]
    (restore/validate-plan
     {::restore/intent intent
      ::restore/confirmation-text
      (restore/confirmation-text
       {::restore/intent intent
        ::restore/confirmation-action
        :seon.dev.restore.confirmation/apply})})))

(defn- blob-result-path [cluster-dir intent]
  (str cluster-dir "/lifecycle/restore-blobs-"
       (::restore/intent-id intent) ".edn"))

(defn- read-materialization-result [cluster-dir intent]
  (when-let [result (state/read-edn (blob-result-path cluster-dir intent))]
    (when-not (m/validate :my.blob/materialization-success result)
      (throw (ex-info "The retained blob materialization evidence is invalid."
                      {:seon.dev.restore-state/blob-result result})))
    (when-not (and (= (::restore/reachable-hash-digest intent)
                      (:my.blob/reachable-hash-digest result))
                   (= (get-in intent
                              [::restore/selected-target-descriptor
                               ::launch/database ::coordinate/coordinate])
                      (:my.blob/target-coordinate result)))
      (throw (ex-info "The retained blob result names another restore intent."
                      {:seon.dev.restore-state/blob-result result
                       ::restore/intent-id (::restore/intent-id intent)})))
    result))

(defn- materialize-retained-blobs!
  [configuration branch-name intent]
  (let [cluster-dir (:seon.dev.config/cluster-dir configuration)]
    (or (read-materialization-result cluster-dir intent)
        (let [target (::restore/selected-target-descriptor intent)
              main (::restore/pre-restore-main-descriptor intent)
              target-coordinate
              (get-in target [::launch/database ::coordinate/coordinate])
              result
              (post-edn!
               (retained-blob-url configuration branch-name)
               {:my.blob/operator-operation
                :my.blob.operator.operation/materialize-retained
                :seon.dev.restore/startup-identity
                (restore/startup-identity intent)
                :my.blob/target-coordinate target-coordinate
                :my.blob/source-storage-view
                (::launch/blob-storage-view target)
                :my.blob/destination-storage-view
                (::launch/blob-storage-view main)}
               :my.blob/materialization-success)]
          (state/write-edn! (blob-result-path cluster-dir intent) result)
          result))))

(defn- require-created-coordinate!
  [intent role response]
  (let [expected (case role
                   :undo (::restore/undo-coordinate intent)
                   :target (::restore/prepared-target-coordinate intent))
        actual (::protocol/coordinate response)]
    (when-not (and (= expected actual)
                   (not= (::protocol/created? response)
                         (::protocol/adopted? response)))
      (throw (ex-info "The reserved restore branch did not converge exactly."
                      {:seon.dev.restore-state/role role
                       :seon.dev.restore-state/expected expected
                       :seon.dev.restore-state/actual actual
                       :seon.dev.restore-state/response response})))
    response))

(defn- create-reserved-branch!
  [configuration intent role]
  (let [source-descriptor
        (case role
          :undo (::restore/pre-restore-main-descriptor intent)
          :target (::restore/selected-target-descriptor intent))
        source-database (::launch/database source-descriptor)
        source-coordinate
        (get-in source-descriptor
                [::launch/database ::coordinate/coordinate])
        target-coordinate
        (case role
          :undo (::restore/undo-coordinate intent)
          :target (::restore/prepared-target-coordinate intent))
        target-database-name
        (str (:seon.dev.config/cluster-name configuration)
             "-restore-" (name role) "-" (::restore/intent-id intent))
        response
        (writer-call!
         source-descriptor
         (protocol/create-branch-request
          {::protocol/source-database-name
           (::protocol/database-name source-database)
           ::protocol/target-database-name target-database-name
           ::protocol/source-coordinate source-coordinate
           ::protocol/expected-source-head source-coordinate
           ::protocol/target-branch (::coordinate/branch target-coordinate)})
         ::protocol/create-branch-response)]
    (require-created-coordinate! intent role response)))

(defn- stop-retained-pods! [configuration]
  (mapv
   (fn [retained]
     (if (= :seon.dev.branch.state/open (::branch/desired-state retained))
       (branch/stop!
        {::branch/configuration configuration
         ::branch/lifecycle-path (::branch/lifecycle-path retained)})
       (when-let [descriptor (::branch/launch-descriptor retained)]
         ;; A closed lifecycle record should already own process absence. The
         ;; exact descriptor still lets restore fail closed and drain a stale
         ;; retained pod rather than trusting desired state as live evidence.
         (process/clean-or-force!
          {:seon.dev.process/configuration
           (config/select-launch-descriptor configuration descriptor)
           :seon.dev.process/operation
           :seon.dev.process.operation/restore
           :seon.dev.process/targets #{process/pod-id}}))))
   (branch/inventory configuration)))

(defn- stop-main-consumers! [configuration operation]
  (process/clean-or-force!
   {:seon.dev.process/configuration configuration
    :seon.dev.process/operation operation
    :seon.dev.process/targets
    #{process/pod-id process/writer-id process/watcher-id}}))

(defn- lifecycle->observation [_intent lifecycle]
  {::restore/main-coordinate (::protocol/main-coordinate lifecycle)
   ::restore/main-parent-commit-ids
   (::protocol/main-parent-commit-ids lifecycle)
   ::restore/branch-heads (::protocol/branch-coordinates lifecycle)
   ::restore/completed-restore-ids
   (::protocol/completed-restore-ids lifecycle)
   ::restore/completion-facts (::protocol/restore-completions lifecycle)
   ::restore/completion-coordinates
   (::protocol/restore-completion-coordinates lifecycle)})

(defn- observe-restore! [configuration intent]
  (lifecycle->observation
   intent
   (observe-lifecycle! (:seon.dev.config/launch-descriptor configuration))))

(defn- require-next-command!
  [configuration intent expected-command]
  (let [observation (observe-restore! configuration intent)
        actual-command
        (::restore/command
         (restore/next-command {::restore/intent intent
                                ::restore/observation observation}))]
    (when-not (= expected-command actual-command)
      (throw
       (ex-info "Restore facts changed across the exclusive writer fence."
                {::restore/intent-id (::restore/intent-id intent)
                 :seon.dev.restore-state/expected-command expected-command
                 :seon.dev.restore-state/actual-command actual-command
                 ::restore/observation observation})))
    observation))

(defn- admin-invocation [configuration intent timeout-ms]
  (restore/derive-admin-invocation
   {::restore/cluster-dir (:seon.dev.config/cluster-dir configuration)
    ::restore/intent intent
    ::restore/admin-timeout-ms timeout-ms}))

(defn- require-associated-admin-result! [intent result]
  (let [base (restore-admin/result-base intent)
        associated? (every? (fn [[key value]] (= value (get result key))) base)
        forced (::restore-admin/forced-main-coordinate result)
        pre-restore (::restore-admin/pre-restore-main-coordinate base)]
    (when-not associated?
      (throw (ex-info "The restore-admin result names another immutable intent."
                      {:seon.dev.restore-state/expected-admin-base base
                       :seon.dev.restore-state/admin-result result})))
    (when (restore-admin/success-result? result)
      (when-not
       (and (= (::restore/expected-branch-roster intent)
               (::restore-admin/branch-roster result))
            (= :db (::coordinate/branch forced))
            (= (::coordinate/database-id pre-restore)
               (::coordinate/database-id forced)))
       (throw (ex-info "The successful restore-admin result violates intent fences."
                       {:seon.dev.restore-state/admin-result result}))))
    result))

(defn- unknown-admin-result? [result]
  (= :seon.db.restore-admin.effect/unknown
     (::restore-admin/effect-state result)))

(defn- read-admin-result [invocation intent]
  (when-let [result (state/read-edn (::restore/admin-result-path invocation))]
    (when-not (restore-admin/valid-result? result)
      (throw (ex-info "The restore-admin artifact published invalid evidence."
                      {:seon.dev.restore-state/admin-result result
                       :seon.dev.restore-state/explanation
                       (restore-admin/explain-result result)})))
    (require-associated-admin-result! intent result)))

(defn- writer-admin-process [configuration manifest invocation]
  (let [writer-spec (get (process/specs configuration manifest)
                         process/writer-id)
        argv (:seon.dev.process/argv writer-spec)
        writer-output (:seon.dev.config/writer-output configuration)
        jar-index (.indexOf ^java.util.List argv writer-output)]
    (when (neg? jar-index)
      (throw (ex-info "The writer process graph does not select its canonical jar."
                      {:seon.dev.restore-state/writer-argv argv
                       :seon.dev.artifact/path writer-output})))
    {:seon.dev.restore-state/argv
     (into (subvec (vec argv) 0 (inc jar-index))
           ["--restore-admin-intent" (::restore/intent-path invocation)
            "--restore-admin-result" (::restore/admin-result-path invocation)])
     :seon.dev.restore-state/environment
     (:seon.dev.process/environment writer-spec)
     :seon.dev.restore-state/shutdown-grace-ms
     (:seon.dev.process/shutdown-grace-ms writer-spec)}))

(defn- invoke-admin!
  [configuration manifest intent timeout-ms]
  (let [invocation (admin-invocation configuration intent timeout-ms)
        retained (read-admin-result invocation intent)
        _ (when (and retained
                     (not (or (restore-admin/success-result? retained)
                              (unknown-admin-result? retained))))
            (throw (ex-info "The retained restore-admin result is not successful."
                            {:seon.dev.restore-state/admin-result retained})))
        actual-digest (artifact/current-writer-digest configuration)
        expected-digest
        (restore/writer-artifact-digest (::restore/artifact-identity intent))
        _ (when-not (= expected-digest actual-digest
                       (:seon.dev.artifact/writer-digest manifest))
            (throw (ex-info "The writer artifact changed after intent publication."
                            {:seon.dev.restore-state/expected-writer-digest
                             expected-digest
                             :seon.dev.restore-state/actual-writer-digest
                             actual-digest})))
        process-record
        (process/read-process configuration process/restore-admin-id)
        run? (or process-record
                 (not (restore-admin/success-result? retained)))
        process-result
        (when run?
          (let [{::keys [argv environment shutdown-grace-ms]}
                (writer-admin-process configuration manifest invocation)]
            (process/contained-one-shot!
             {:seon.dev.process/configuration configuration
              :seon.dev.process/argv argv
              :seon.dev.process/environment environment
              :seon.dev.process/artifact-digest expected-digest
              :seon.dev.process/application-result-path
              (::restore/admin-result-path invocation)
              :seon.dev.process/timeout-ms timeout-ms
              :seon.dev.process/shutdown-grace-ms shutdown-grace-ms})))
        result (or (read-admin-result invocation intent) retained)]
    (when-not result
      (throw (ex-info "Restore-admin exited without durable evidence."
                      {:seon.dev.restore-state/admin-process process-result})))
    (when-not (restore-admin/success-result? result)
      (throw (ex-info "Restore-admin rejected the frozen transition."
                      {:seon.dev.restore-state/admin-result result})))
    result))

(defn- selected-configuration [configuration descriptor]
  (config/select-launch-descriptor configuration descriptor))

(defn- ensure-processes!
  [configuration manifest ids]
  (let [spec-map (process/specs configuration manifest)
        stale
        (into #{}
              (filter
               (fn [id]
                 (let [record (process/read-process configuration id)]
                   (and record
                        (not (process/converged?
                              configuration (get spec-map id)))))))
              ids)]
    (when (seq stale)
      (process/clean-or-force!
       {:seon.dev.process/configuration configuration
        :seon.dev.process/operation :seon.dev.process.operation/restore
        :seon.dev.process/targets stale}))
    (process/with-startup-ownership
     configuration
     (fn [start-owned!]
       (mapv (fn [id]
               (process/ensure!
                configuration (get spec-map id) start-owned!))
             ids)))))

(defn- forced-main-descriptor [configuration admin-result]
  (launch/with-coordinate
   {::launch/descriptor (:seon.dev.config/launch-descriptor configuration)
    ::coordinate/coordinate
    (::restore-admin/forced-main-coordinate admin-result)}))

(defn- start-writer-after-admin!
  [configuration manifest admin-result]
  (let [forced-config
        (selected-configuration
         configuration (forced-main-descriptor configuration admin-result))]
    (ensure-processes! forced-config manifest [process/writer-id])
    forced-config))

(defn- restore-startup-descriptor
  [configuration intent admin-result blob-result]
  (launch/with-restore-startup
   {::launch/descriptor (forced-main-descriptor configuration admin-result)
    ::launch/restore-startup
    {:seon.dev.restore/startup-identity (restore/startup-identity intent)
     :seon.db.restore-admin/result admin-result
     :my.blob/materialization-result blob-result}}))

(defn- start-restore-pod!
  [configuration manifest intent admin-result blob-result]
  (require-artifact-identity!
   configuration manifest (::restore/artifact-identity intent))
  (let [restore-config
        (selected-configuration
         configuration
         (restore-startup-descriptor
          configuration intent admin-result blob-result))]
    (ensure-processes! restore-config manifest [process/pod-id])
    restore-config))

(defn- require-selected-branch!
  [configuration branch-name intent]
  (let [request (branch/request {::branch/configuration configuration
                                 ::branch/name branch-name})
        selected (::restore/selected-target-descriptor intent)
        selected-branch
        (get-in selected [::launch/database ::coordinate/coordinate
                          ::coordinate/branch])]
    (when-not (= selected-branch (::branch/target-branch request))
      (throw (ex-info "The retained restore intent selects another branch."
                      {::branch-name branch-name
                       :seon.dev.restore-state/selected-branch
                       selected-branch})))
    intent))

(defn- prepare-observation-writer!
  [configuration manifest intent admin-result]
  (if admin-result
    (start-writer-after-admin! configuration manifest admin-result)
    (do
      (ensure-processes! configuration manifest [process/writer-id])
      configuration)))

(defn- prove-restore-pod!
  [configuration manifest intent admin-result blob-result]
  (let [restore-config
        (selected-configuration
         configuration
         (restore-startup-descriptor
          configuration intent admin-result blob-result))
        restore-pod (get (process/specs restore-config manifest) process/pod-id)
        pod-record (process/read-process restore-config process/pod-id)]
    ;; Completion is durable, while the prepared runtime is disposable. A
    ;; retry always recreates the exact restore generation before removing the
    ;; external intent, even when the prior pod vanished after completion.
    (when-not (and pod-record
                   (process/converged? restore-config restore-pod))
      (start-restore-pod!
       configuration manifest intent admin-result blob-result))
    (when-not (process/converged? restore-config restore-pod)
      (throw (ex-info "The restore pod did not converge to its frozen identity."
                      {::restore/intent-id (::restore/intent-id intent)})))
    :seon.dev.restore.runtime/restore))

(declare converge!)

(defn resume!
  "Converge the already-authorized retained restore intent without prompting."
  {:malli/schema [:=> [:cat ::resume-request] ::restore-result]}
  [{configuration ::configuration
    branch-name ::branch-name
    timeout-ms ::admin-timeout-ms
    :or {timeout-ms 120000}
    :as request}]
  (when-not (m/validate ::resume-request request)
    (throw (ex-info "The retained restore resume request is invalid."
                    {:seon.dev.restore-state/explanation
                     (m/explain ::resume-request request)})))
  (let [manifest (require-manifest! configuration)
        intent
        (->> (or (retained-intent configuration)
                 (throw (ex-info "Restore resume requires retained authority."
                                 {:seon.error/kind
                                  :seon.dev.restore.error/missing-intent})))
             (require-selected-branch! configuration branch-name))
        artifact-identity (::restore/artifact-identity intent)
        _ (require-artifact-identity!
           configuration manifest artifact-identity)
        cluster-dir (:seon.dev.config/cluster-dir configuration)
        invocation (admin-invocation configuration intent timeout-ms)
        retained-admin-result (read-admin-result invocation intent)
        !admin-result (atom nil)
        !blob-result (atom (read-materialization-result cluster-dir intent))
        _ (when (and retained-admin-result
                     (not (or (restore-admin/success-result?
                               retained-admin-result)
                              (unknown-admin-result?
                               retained-admin-result))))
            (throw (ex-info "Restore cannot resume from rejected admin evidence."
                            {:seon.dev.restore-state/admin-result
                             retained-admin-result})))
        _ (when (or retained-admin-result
                    (process/read-process
                     configuration process/restore-admin-id))
            (reset! !admin-result
                    (invoke-admin!
                     configuration manifest intent timeout-ms)))
        _ (prepare-observation-writer!
           configuration manifest intent @!admin-result)
        convergence
        (converge!
         {::restore/intent intent
          ::observe!
          (fn [_] (observe-restore! configuration intent))
          ::effect!
          (fn [{command ::restore/command}]
            (case command
              :seon.dev.restore.command/create-undo
              (do
                ;; The pod drain closes executable admission and writer
                ;; shutdown joins every already accepted UDS handler. Reopen
                ;; only the observation writer, reprove the frozen facts, then
                ;; create U through its existing expected-head fence.
                (reset! !blob-result
                        (materialize-retained-blobs!
                         configuration branch-name intent))
                (stop-retained-pods! configuration)
                (stop-main-consumers!
                 configuration
                 :seon.dev.process.operation/restore)
                (require-artifact-identity!
                 configuration manifest artifact-identity)
                (ensure-processes! configuration manifest [process/writer-id])
                (require-next-command!
                 configuration intent
                 :seon.dev.restore.command/create-undo)
                (create-reserved-branch! configuration intent :undo))

              :seon.dev.restore.command/create-target
              (create-reserved-branch! configuration intent :target)

              :seon.dev.restore.command/prepare-exclusive-transition
              (do
                (stop-retained-pods! configuration)
                (stop-main-consumers!
                 configuration
                 :seon.dev.process.operation/restore)
                (reset! !admin-result
                        (invoke-admin!
                         configuration manifest intent timeout-ms))
                (start-writer-after-admin!
                 configuration manifest @!admin-result))

              :seon.dev.restore.command/reconstruct-and-complete
              (do
                (when-not @!blob-result
                  (throw (ex-info
                          "Restore reached reconstruction without retained blob evidence."
                          {::restore/intent-id (::restore/intent-id intent)})))
                (when-not @!admin-result
                  ;; A power cut may publish the force without its atomic
                  ;; result. Re-enter the no-listener artifact; storage facts
                  ;; distinguish already-applied from divergence.
                  (stop-main-consumers!
                   configuration
                   :seon.dev.process.operation/restore)
                  (reset! !admin-result
                          (invoke-admin!
                           configuration manifest intent timeout-ms))
                  (start-writer-after-admin!
                   configuration manifest @!admin-result))
                (start-restore-pod!
                 configuration manifest intent @!admin-result @!blob-result))

              :seon.dev.restore.command/prove-readiness
              (do
                (prove-restore-pod!
                 configuration manifest intent @!admin-result @!blob-result)
                (require-next-command!
                 configuration intent
                 :seon.dev.restore.command/prove-readiness)
                (stop-main-consumers!
                 configuration
                 :seon.dev.process.operation/restore)
                ;; Completion is the durable authority. Delete the intent
                ;; first so a crash can only leave ignored auxiliary evidence;
                ;; deleting proof first would strand a retained intent.
                (state/delete-edn! (restore/intent-path cluster-dir))
                (state/delete-edn! (::restore/admin-result-path invocation))
                (state/delete-edn! (blob-result-path cluster-dir intent))
                (ensure-processes!
                 configuration manifest
                 [process/watcher-id process/writer-id process/pod-id]))))})]
    {::restore/intent-id (::restore/intent-id intent)
     ::restored-coordinate
     (::restore/main-coordinate (::restore/observation convergence))
     ::admin-outcome (::restore-admin/outcome @!admin-result)
     ::transitions (::transitions convergence)}))

(defn apply!
  "Publish one exactly confirmed fresh plan, then enter prompt-free resume."
  {:malli/schema [:=> [:cat ::apply-request] ::restore-result]}
  [{configuration ::configuration
    branch-name ::branch-name
    plan ::restore/plan
    supplied-confirmation ::restore/confirmation-text
    timeout-ms ::admin-timeout-ms
    :or {timeout-ms 120000}
    :as request}]
  (when-not (m/validate ::apply-request request)
    (throw (ex-info "The retained restore apply request is invalid."
                    {:seon.dev.restore-state/explanation
                     (m/explain ::apply-request request)})))
  (let [{intent ::restore/intent
         expected-confirmation ::restore/confirmation-text}
        (restore/validate-plan plan)]
    (when-not (= expected-confirmation supplied-confirmation)
      (throw
       (ex-info
        "The supplied restore confirmation does not exactly authorize the plan."
        {::restore/intent-id (::restore/intent-id intent)
         :seon.error/kind
         :seon.dev.restore.error/confirmation-mismatch})))
    (require-selected-branch! configuration branch-name intent)
    (if-let [retained (retained-intent configuration)]
      (do
        (when-not (= intent retained)
          (throw
           (ex-info
            "Another immutable restore intent is already retained."
            {::restore/intent-id (::restore/intent-id retained)
             :seon.error/kind
             :seon.dev.restore.error/retained-intent})))
        (resume! {::configuration configuration
                  ::branch-name branch-name
                  ::admin-timeout-ms timeout-ms}))
      (let [manifest (require-manifest! configuration)
            artifact-identity (::restore/artifact-identity intent)
            _ (require-artifact-identity!
               configuration manifest artifact-identity)
            fresh
            (derive-intent!
             configuration branch-name manifest
             (::restore/intent-id intent)
             (::restore/consumer-generations intent))]
        (when-not (= intent fresh)
          (throw
           (ex-info
            "The confirmed restore plan became stale before publication."
            {::restore/intent-id (::restore/intent-id intent)
             :seon.dev.restore/confirmed-plan intent
             :seon.dev.restore/current-plan fresh
             :seon.error/kind
             :seon.dev.restore.error/stale-confirmed-plan})))
        (publish-intent!
         {::cluster-dir (:seon.dev.config/cluster-dir configuration)
          ::restore/intent intent})
        (resume! {::configuration configuration
                  ::branch-name branch-name
                  ::admin-timeout-ms timeout-ms})))))

(defn- matching-restore-pod-record [configuration intent]
  (let [expected-generation
        (get (::restore/consumer-generations intent) process/pod-id)
        record (process/read-process configuration process/pod-id)
        recorded-generation
        (get-in record [:seon.dev.process/containment
                        :seon.dev.process.containment/generation])]
    (when (= expected-generation recorded-generation)
      record)))

(defn- require-admin-absent! [configuration]
  (let [absence
        (process/restore-admin-absence!
         {:seon.dev.process/configuration configuration
          :seon.dev.process/lock-timeout-ms 5000})]
    (when-not (true? (:seon.dev.process/absent? absence))
      (throw
       (ex-info
        "Restore abort cannot prove the admin workload absent."
        {:seon.dev.process/restore-admin-absence absence
         :seon.error/kind :seon.dev.restore.error/abort-unsafe})))
    absence))

(defn- require-no-admin-result! [invocation]
  (let [path (::restore/admin-result-path invocation)]
    (when (fs/exists? path)
      (throw
       (ex-info
        "Restore abort is forbidden after any admin result publication."
        {::restore/admin-result-path path
         :seon.error/kind :seon.dev.restore.error/abort-unsafe})))
    path))

(defn- require-abortable-observation! [intent observation]
  (let [intent-id (::restore/intent-id intent)
        completion-ids
        (set (map :seon.db.restore/id (::restore/completion-facts observation)))
        indexed-completion-ids
        (set (keys (::restore/completion-coordinates observation)))
        observed-completion-ids (::restore/completed-restore-ids observation)
        heads (::restore/branch-heads observation)
        reserved #{(::restore/undo-branch intent)
                   (::restore/prepared-target-branch intent)}]
    (when-not (= completion-ids indexed-completion-ids
                 observed-completion-ids)
      (throw
       (ex-info
        "Restore completion evidence is inconsistent; abort fails closed."
        {::restore/intent-id intent-id
         :seon.error/kind :seon.dev.restore.error/abort-unsafe})))
    (when (or (some #(= (::restore/plan-digest intent)
                        (:seon.db.restore/plan-digest %))
                    (::restore/completion-facts observation))
              (some #(contains? heads %) reserved))
      (throw
       (ex-info
        "Restore preparation or completion evidence forbids abort."
        {::restore/intent-id intent-id
         :seon.dev.restore/reserved-branches
         (set (filter #(contains? heads %) reserved))
         :seon.error/kind :seon.dev.restore.error/abort-unsafe})))
    observation))

(defn abort!
  "Delete only a proved pre-preparation retained intent with exact authority."
  {:malli/schema [:=> [:cat ::abort-request] ::abort-result]}
  [{configuration ::configuration
    branch-name ::branch-name
    supplied-confirmation ::restore/confirmation-text
    :as request}]
  (when-not (m/validate ::abort-request request)
    (throw (ex-info "The retained restore abort request is invalid."
                    {:seon.dev.restore-state/explanation
                     (m/explain ::abort-request request)})))
  (let [intent
        (->> (or (retained-intent configuration)
                 (throw (ex-info "Restore abort requires retained authority."
                                 {:seon.error/kind
                                  :seon.dev.restore.error/missing-intent})))
             (require-selected-branch! configuration branch-name))
        expected-confirmation
        (restore/confirmation-text
         {::restore/intent intent
          ::restore/confirmation-action
          :seon.dev.restore.confirmation/abort})]
    (when-not (= expected-confirmation supplied-confirmation)
      (throw
       (ex-info
        "The supplied abort confirmation does not exactly authorize the intent."
        {::restore/intent-id (::restore/intent-id intent)
         :seon.error/kind
         :seon.dev.restore.error/confirmation-mismatch})))
    (let [invocation (admin-invocation configuration intent 120000)
          _ (require-no-admin-result! invocation)
          _ (require-admin-absent! configuration)
          _ (when (matching-restore-pod-record configuration intent)
              (throw
               (ex-info
                "The intent's restore pod generation still has a retained record."
                {::restore/intent-id (::restore/intent-id intent)
                 :seon.error/kind :seon.dev.restore.error/abort-unsafe})))
          observation
          (require-abortable-observation!
           intent (observe-restore! configuration intent))
          cluster-dir (:seon.dev.config/cluster-dir configuration)
          selected-coordinate
          (get-in intent [::restore/selected-target-descriptor
                          ::launch/database ::coordinate/coordinate])
          selected-branch (::coordinate/branch selected-coordinate)
          current-target (get (::restore/branch-heads observation)
                              selected-branch)]
      (state/delete-edn! (blob-result-path cluster-dir intent))
      (require-no-admin-result! invocation)
      (require-admin-absent! configuration)
      (state/delete-edn! (restore/intent-path cluster-dir))
      (cond->
       {::restore/intent-id (::restore/intent-id intent)
        ::restore/plan-digest (::restore/plan-digest intent)
        ::aborted? true
        ::prior-main-coordinate
        (get-in intent [::restore/pre-restore-main-descriptor
                        ::launch/database ::coordinate/coordinate])
        ::current-main-coordinate (::restore/main-coordinate observation)
        ::selected-target-coordinate selected-coordinate}
        current-target (assoc ::current-target-coordinate current-target)))))

(defn converge!
  "Execute the one restore command derived from fresh durable facts.

   `observe!` must return a closed restore observation from storage and
   completion facts. `effect!` performs exactly the supplied command. No phase
   or retry counter is retained: every nonterminal step rereads current facts,
   while prove-readiness is the single terminal effect."
  {:malli/schema [:=> [:cat ::convergence-request] ::convergence-result]}
  [{intent ::restore/intent
    observe! ::observe!
    effect! ::effect!
    max-transitions ::max-transitions
    :or {max-transitions 16}
    :as request}]
  (when-not (m/validate ::convergence-request request)
    (throw (ex-info "The restore convergence request is invalid."
                    {:seon.dev.restore-state/explanation
                     (m/explain ::convergence-request request)})))
  (let [intent (restore/validate-intent intent)]
    (loop [transition-number 1
           transitions []]
      (when (> transition-number max-transitions)
        (throw (ex-info "Restore did not converge within the bounded transition count."
                        {::restore/intent-id (::restore/intent-id intent)
                         ::max-transitions max-transitions
                         ::transitions transitions})))
      (let [observation (observe! intent)
            {command ::restore/command}
            (restore/next-command {::restore/intent intent
                                   ::restore/observation observation})
            transition {::transition-number transition-number
                        ::restore/observation observation
                        ::restore/command command}
            transitions (conj transitions transition)]
        (when (= :seon.dev.restore.command/diagnose-divergence command)
          (throw (ex-info "Restore facts diverged from the immutable intent."
                          {::restore/intent-id (::restore/intent-id intent)
                           ::restore/observation observation
                           ::transitions transitions})))
        (effect! {::restore/intent intent
                  ::restore/observation observation
                  ::restore/command command})
        (if (= :seon.dev.restore.command/prove-readiness command)
          {::restore/intent intent
           ::restore/observation observation
           ::transitions transitions}
          (recur (inc transition-number) transitions))))))
