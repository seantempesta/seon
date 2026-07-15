(ns seon.dev.restore
  "Immutable restore intent and fact-derived retry commands."
  (:require [malli.core :as m]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.dev.restore.schema]
            [seon.launch :as launch]
            [seon.schema :as schema])
  (:import [java.nio.charset StandardCharsets]
           [java.security MessageDigest]))

(schema/register! ::intent-version [:= 1])
(schema/register! ::operation [:enum :seon.dev.restore.operation/restore
                                :seon.dev.restore.operation/undo])
(schema/register! ::pre-restore-main-descriptor ::launch/descriptor)
(schema/register! ::selected-target-descriptor ::launch/descriptor)
(schema/register! ::undo-branch :keyword)
(schema/register! ::prepared-target-branch :keyword)
(schema/register! ::undo-coordinate ::coordinate/coordinate)
(schema/register! ::prepared-target-coordinate ::coordinate/coordinate)
(schema/register! ::expected-branch-roster [:set :keyword])
(schema/register! ::protocol-version [:= protocol/current-version])
(schema/register! ::overlay-selection [:= :seon.dev.restore.overlay/preserve])
(schema/register! ::core-overlay-selection ::overlay-selection)
(schema/register! ::config-overlay-selection ::overlay-selection)
(schema/register! ::writer-artifact-digest ::digest)
(schema/register! ::cluster-dir ::launch/cluster-dir)
(schema/register! ::intent-path ::launch/path)
(schema/register! ::branch-heads [:map-of :keyword ::coordinate/coordinate])
(schema/register! ::main-coordinate ::coordinate/coordinate)
(schema/register! ::forced-main-coordinate ::coordinate/coordinate)
(schema/register! ::forced-main-parent-commit-ids [:set :uuid])
(schema/register! ::completed-intent-ids [:set ::intent-id])
(schema/register!
 ::completion
 [:map {:closed true}
  [:seon.db.restore/id ::intent-id]
  [:seon.db.restore/db-name :keyword]
  [:seon.db.restore/database-id :uuid]
  [:seon.db.restore/from-branch :keyword]
  [:seon.db.restore/from-commit-id :uuid]
  [:seon.db.restore/from-t :int]
  [:seon.db.restore/to-branch :keyword]
  [:seon.db.restore/to-commit-id :uuid]
  [:seon.db.restore/to-t :int]
  [:seon.db.restore/forced-commit-id :uuid]
  [:seon.db.restore/undo-branch :keyword]
  [:seon.db.restore/target-branch :keyword]
  [:seon.db.restore/core-overlay-digest {:optional true} :string]
  [:seon.db.restore/config-overlay-digest {:optional true} :string]])
(schema/register! ::completion-facts [:vector ::completion])
(schema/register! ::selected-completion-id ::intent-id)
(schema/register! ::selected-undo-branch :keyword)
(schema/register!
 ::completion-selector
 [:orn
  [:by-id
   [:map {:closed true}
    [::selected-completion-id ::selected-completion-id]]]
  [:by-undo-branch
   [:map {:closed true}
    [::selected-undo-branch ::selected-undo-branch]]]])
(schema/register!
 ::retained-head-observation
 [:map {:closed true}
  [::main-coordinate ::main-coordinate]
  [::branch-heads ::branch-heads]
  [::completion-facts ::completion-facts]])
(schema/register! ::admin-result-transport
                  [:= :seon.dev.restore.admin.transport/atomic-edn-file])
(schema/register! ::admin-result-path ::launch/path)
(schema/register! ::admin-timeout-ms [:int {:min 1 :max 600000}])
(schema/register!
 ::command
 [:enum :seon.dev.restore.command/create-undo
  :seon.dev.restore.command/create-target
  :seon.dev.restore.command/prepare-exclusive-transition
  :seon.dev.restore.command/reconstruct-and-complete
  :seon.dev.restore.command/prove-readiness
  :seon.dev.restore.command/diagnose-divergence])

(schema/register!
 ::derive-request
 [:map {:closed true}
  [::intent-id ::intent-id]
  [::operation ::operation]
  [::pre-restore-main-descriptor ::pre-restore-main-descriptor]
  [::selected-target-descriptor ::selected-target-descriptor]
  [::expected-branch-roster ::expected-branch-roster]
  [::protocol-version ::protocol-version]
  [::writer-artifact-digest ::writer-artifact-digest]
  [::consumer-generations ::consumer-generations]
  [::core-overlay-selection ::core-overlay-selection]
  [::config-overlay-selection ::config-overlay-selection]
  [::reachable-hash-digest ::reachable-hash-digest]
  [::retained-head-observation {:optional true} ::retained-head-observation]
  [::completion-selector {:optional true} ::completion-selector]])

(schema/register!
 ::intent
 [:map {:closed true}
  [::intent-version ::intent-version]
  [::intent-id ::intent-id]
  [::operation ::operation]
  [::pre-restore-main-descriptor ::pre-restore-main-descriptor]
  [::selected-target-descriptor ::selected-target-descriptor]
  [::undo-branch ::undo-branch]
  [::prepared-target-branch ::prepared-target-branch]
  [::undo-coordinate ::undo-coordinate]
  [::prepared-target-coordinate ::prepared-target-coordinate]
  [::expected-branch-roster ::expected-branch-roster]
  [::protocol-version ::protocol-version]
  [::writer-artifact-digest ::writer-artifact-digest]
  [::consumer-generations ::consumer-generations]
  [::core-overlay-selection ::core-overlay-selection]
  [::config-overlay-selection ::config-overlay-selection]
  [::reachable-hash-digest ::reachable-hash-digest]
  [::plan-digest ::plan-digest]])

(schema/register!
 ::observation
  [:map {:closed true}
  [::main-coordinate ::main-coordinate]
  [::forced-main-coordinate {:optional true} ::forced-main-coordinate]
  [::forced-main-parent-commit-ids ::forced-main-parent-commit-ids]
  [::branch-heads ::branch-heads]
  [::completed-intent-ids ::completed-intent-ids]])

(schema/register!
 ::admin-invocation-request
 [:map {:closed true}
  [::cluster-dir ::cluster-dir]
  [::intent ::intent]
  [::admin-timeout-ms ::admin-timeout-ms]])

(schema/register!
 ::admin-invocation
  [:map {:closed true}
  [::intent ::intent]
  [::admin-result-transport ::admin-result-transport]
  [::intent-path ::intent-path]
  [::admin-result-path ::admin-result-path]
  [::admin-timeout-ms ::admin-timeout-ms]])

(schema/register!
 ::next-command-request
 [:map {:closed true}
  [::intent ::intent]
  [::observation ::observation]])

(schema/register!
 ::next-command-result
 [:map {:closed true}
  [::intent-id ::intent-id]
  [::command ::command]])

(defn- validate! [schema-key value message]
  (when-not (m/validate schema-key value)
    (throw
     (ex-info message
              {:seon.dev.restore/explanation
               (mapv #(select-keys % [:path :in :type])
                     (:errors (m/explain schema-key value)))})))
  value)

(defn- require-consistency! [condition message data]
  (when-not condition
    (throw (ex-info message
                    (assoc data :seon.error/kind
                           :seon.dev.restore.error/inconsistent-intent)))))

(defn- reserved-branch [role intent-id]
  (keyword (str "seon.restore." (name role)) (str "r-" intent-id)))

(defn- descriptor-coordinate [descriptor]
  (get-in descriptor [::launch/database ::coordinate/coordinate]))

(def ^:private intent-request-keys
  [::intent-id
   ::operation
   ::pre-restore-main-descriptor
   ::selected-target-descriptor
   ::expected-branch-roster
   ::protocol-version
   ::writer-artifact-digest
   ::consumer-generations
   ::core-overlay-selection
   ::config-overlay-selection
   ::reachable-hash-digest])

(defn- completion-undo-coordinate [completion]
  {::coordinate/database-id (:seon.db.restore/database-id completion)
   ::coordinate/branch (:seon.db.restore/undo-branch completion)
   ::coordinate/commit-id (:seon.db.restore/from-commit-id completion)
   ::coordinate/t (:seon.db.restore/from-t completion)})

(defn- completion-target-coordinate [completion]
  {::coordinate/database-id (:seon.db.restore/database-id completion)
   ::coordinate/branch (:seon.db.restore/to-branch completion)
   ::coordinate/commit-id (:seon.db.restore/to-commit-id completion)
   ::coordinate/t (:seon.db.restore/to-t completion)})

(defn- selected-completions [selector completions]
  (let [{::keys [selected-completion-id selected-undo-branch]} selector]
    (filterv
     (fn [completion]
       (if selected-completion-id
         (= selected-completion-id (:seon.db.restore/id completion))
         (= selected-undo-branch (:seon.db.restore/undo-branch completion))))
     completions)))

(defn- validate-undo-selection! [request]
  (let [observation (::retained-head-observation request)
        selector (::completion-selector request)]
    (require-consistency!
     (and observation selector)
     "Undo requires one exact retained completion observation."
     {::intent-id (::intent-id request)})
    (let [completions (::completion-facts observation)
          matches (selected-completions selector completions)]
      (require-consistency!
       (= 1 (count matches))
       "Undo selection does not resolve exactly one retained completion."
       {::completion-selector selector
        :seon.dev.restore/matching-completion-ids
        (mapv :seon.db.restore/id matches)})
      (let [completion (first matches)
            main (descriptor-coordinate
                  (::pre-restore-main-descriptor request))
            target (descriptor-coordinate
                    (::selected-target-descriptor request))
            observed-main (::main-coordinate observation)
            heads (::branch-heads observation)
            expected-target (completion-undo-coordinate completion)
            new-undo (reserved-branch :undo (::intent-id request))
            new-target (reserved-branch :target (::intent-id request))
            expected-roster (conj (set (keys heads)) new-undo new-target)
            completion-id (:seon.db.restore/id completion)
            ambiguous-by
            (->> completions
                 (remove #(= completion-id (:seon.db.restore/id %)))
                 (filter #(= expected-target (completion-undo-coordinate %)))
                 (mapv :seon.db.restore/id))
            reused-by
            (->> completions
                 (remove #(= completion-id (:seon.db.restore/id %)))
                 (filter #(= expected-target (completion-target-coordinate %)))
                 (mapv :seon.db.restore/id))]
        (require-consistency!
         (and (= main observed-main)
              (= observed-main (get heads :db)))
         "Undo did not retain the actual latest observed main head."
         {::main-coordinate main
          :seon.dev.restore/observed-main-coordinate observed-main})
        (require-consistency!
         (and (every? (fn [[branch point]]
                        (= branch (::coordinate/branch point)))
                      heads)
              (every? #(= (::coordinate/database-id main)
                           (::coordinate/database-id %))
                      (vals heads)))
         "Undo branch observations cross a database or branch attachment."
         {::branch-heads heads})
        (require-consistency!
         (and (= :db (:seon.db.restore/from-branch completion))
              (= (::coordinate/database-id main)
                 (:seon.db.restore/database-id completion))
              (not= :db (:seon.db.restore/undo-branch completion))
              (not= (:seon.db.restore/undo-branch completion)
                    (:seon.db.restore/target-branch completion))
              (not= expected-target (completion-target-coordinate completion)))
         "Undo completion lineage is inconsistent."
         {:seon.db.restore/id completion-id})
        (require-consistency!
         (and (= expected-target target)
              (= expected-target
                 (get heads (::coordinate/branch expected-target))))
         "Undo target is not the selected completion's exact retained head."
         {::selected-target-descriptor
          (::selected-target-descriptor request)
          :seon.dev.restore/expected-undo-coordinate expected-target})
        (require-consistency!
         (= expected-roster (::expected-branch-roster request))
         "Undo branch roster observation is stale or incomplete."
         {::expected-branch-roster (::expected-branch-roster request)
          :seon.dev.restore/observed-post-prepare-roster expected-roster})
        (require-consistency!
         (and (not (contains? heads new-undo))
              (not (contains? heads new-target)))
         "Undo reserved branches already exist before intent publication."
         {::undo-branch new-undo ::prepared-target-branch new-target})
        (require-consistency!
         (empty? ambiguous-by)
         "Undo retained head is claimed by more than one completion."
         {:seon.db.restore/id completion-id
          :seon.dev.restore/ambiguous-completion-ids ambiguous-by})
        (require-consistency!
         (empty? reused-by)
         "Undo completion has already been consumed by a later completion."
         {:seon.db.restore/id completion-id
          :seon.dev.restore/reused-by-completion-ids reused-by})))))

(defn- canonical-digest-data [value]
  (cond
    (map? value)
    [:map (->> value
               (map (fn [[key item]] [(canonical-digest-data key)
                                      (canonical-digest-data item)]))
               (sort-by pr-str)
               vec)]

    (set? value)
    [:set (->> value (map canonical-digest-data) (sort-by pr-str) vec)]

    (vector? value) [:vector (mapv canonical-digest-data value)]
    (sequential? value) [:sequence (mapv canonical-digest-data value)]
    :else value))

(defn- sha-256 [value]
  (let [digest (MessageDigest/getInstance "SHA-256")
        bytes (.getBytes (pr-str (canonical-digest-data value))
                         StandardCharsets/UTF_8)]
    (.update digest bytes)
    (apply str (map #(format "%02x" (bit-and % 0xff)) (.digest digest)))))

(defn- derive-intent-value
  [{::keys [intent-id pre-restore-main-descriptor selected-target-descriptor
            expected-branch-roster]
    :as request}]
  (let [main-database (::launch/database pre-restore-main-descriptor)
        target-database (::launch/database selected-target-descriptor)
        main-coordinate (descriptor-coordinate pre-restore-main-descriptor)
        target-coordinate (descriptor-coordinate selected-target-descriptor)
        main-runtime (::launch/runtime pre-restore-main-descriptor)
        target-runtime (::launch/runtime selected-target-descriptor)
        main-blobs (::launch/blob-storage-view pre-restore-main-descriptor)
        target-blobs (::launch/blob-storage-view selected-target-descriptor)
        undo-branch (reserved-branch :undo intent-id)
        prepared-target-branch (reserved-branch :target intent-id)
        required-roster #{:db (::coordinate/branch target-coordinate)
                          undo-branch prepared-target-branch}]
    (require-consistency! (and main-coordinate target-coordinate)
                          "Restore descriptors must contain exact coordinates."
                          {::intent-id intent-id})
    (require-consistency!
     (and (= :db (::coordinate/branch main-coordinate))
          (not= :db (::coordinate/branch target-coordinate))
          (= (::coordinate/attachment main-database)
             (coordinate/attachment main-coordinate))
          (= (::coordinate/attachment target-database)
             (coordinate/attachment target-coordinate))
          (= (::coordinate/database-id main-coordinate)
             (::coordinate/database-id target-coordinate)))
     "Restore descriptors do not name one physical main and target database."
     {::pre-restore-main-descriptor pre-restore-main-descriptor
      ::selected-target-descriptor selected-target-descriptor})
    (require-consistency!
     (and (= (::protocol/backend main-database)
             (::protocol/backend target-database))
          (= (::protocol/database-path main-database)
             (::protocol/database-path target-database))
          (= (::launch/writer-owner pre-restore-main-descriptor)
             (::launch/writer-owner selected-target-descriptor))
          (= (::launch/artifact-flavor main-runtime)
             (::launch/artifact-flavor target-runtime)))
     "Restore descriptors disagree about the writer artifact or physical store."
     {::intent-id intent-id})
    (require-consistency!
     (and (true? (get-in main-runtime [:seon.client/launch-capability
                                       :seon.client/autonomous?]))
          (false? (get-in target-runtime [:seon.client/launch-capability
                                          :seon.client/autonomous?])))
     "Restore descriptors do not name autonomous main and forensic target roles."
     {::intent-id intent-id})
    (require-consistency!
     (and (not= (:my.blob/writable-dir target-blobs)
                (:my.blob/writable-dir main-blobs))
          (= (:my.blob/writable-dir main-blobs)
             (first (:my.blob/read-only-dirs target-blobs))))
     "The target overlay and main blob archive are not frozen in lookup order."
     {::intent-id intent-id})
    (require-consistency! (every? expected-branch-roster required-roster)
                          "The expected branch roster omits a restore branch."
                          {::expected-branch-roster expected-branch-roster})
    (require-consistency!
     (not (contains? (::consumer-generations request)
                     :seon.dev.process/writer))
     "Writer absence is coordinator evidence, not a consumer generation."
     {::consumer-generations (::consumer-generations request)})
    (validate!
     ::intent
     (let [intent (assoc request
                         ::intent-version 1
                         ::undo-branch undo-branch
                         ::prepared-target-branch prepared-target-branch
                         ::undo-coordinate
                         (assoc main-coordinate ::coordinate/branch undo-branch)
                         ::prepared-target-coordinate
                         (assoc target-coordinate ::coordinate/branch
                                prepared-target-branch))]
       (assoc intent ::plan-digest (sha-256 intent)))
     "The derived restore intent is invalid.")))

(defn derive-intent
  "Derive one immutable restore or completion-bound undo intent."
  {:malli/schema [:=> [:cat ::derive-request] ::intent]}
  [request]
  (validate! ::derive-request request
             "The restore intent request is invalid.")
  (if (= :seon.dev.restore.operation/undo (::operation request))
    (validate-undo-selection! request)
    (require-consistency!
     (and (nil? (::retained-head-observation request))
          (nil? (::completion-selector request)))
     "Ordinary restore does not accept undo completion evidence."
     {::intent-id (::intent-id request)}))
  (derive-intent-value (select-keys request intent-request-keys)))

(defn validate-intent
  "Validate one retained intent and all of its derived relationships."
  {:malli/schema [:=> [:cat ::intent] ::intent]}
  [intent]
  (validate! ::intent intent "The retained restore intent is invalid.")
  (let [request (apply dissoc intent
                       [::intent-version ::plan-digest ::undo-branch
                        ::prepared-target-branch ::undo-coordinate
                        ::prepared-target-coordinate])]
    (require-consistency!
     (= intent (derive-intent-value request))
     "The retained restore intent has inconsistent derived coordinates."
     {::intent-id (::intent-id intent)}))
  intent)

(defn startup-identity
  "Project the immutable intent fields required by a fresh restore pod."
  {:malli/schema [:=> [:cat ::intent] ::startup-identity]}
  [intent]
  (select-keys (validate-intent intent)
               [::intent-id ::plan-digest ::reachable-hash-digest
                ::consumer-generations]))

(defn intent-path
  "Canonical fsync-published intent path for one cluster directory."
  {:malli/schema [:=> [:cat ::cluster-dir] ::intent-path]}
  [cluster-dir]
  (str cluster-dir "/lifecycle/restore.edn"))

(defn derive-admin-invocation
  "Derive the bounded atomic result destination for one admin invocation."
  {:malli/schema [:=> [:cat ::admin-invocation-request] ::admin-invocation]}
  [{::keys [cluster-dir intent admin-timeout-ms] :as request}]
  (validate! ::admin-invocation-request request
             "The restore admin invocation request is invalid.")
  (let [intent (validate-intent intent)]
    {::intent intent
     ::admin-result-transport
     :seon.dev.restore.admin.transport/atomic-edn-file
     ::intent-path (intent-path cluster-dir)
     ::admin-result-path
     (str cluster-dir "/lifecycle/restore-admin-" (::intent-id intent) ".edn")
     ::admin-timeout-ms admin-timeout-ms}))

(defn- validate-observation-consistency! [intent observation]
  (let [pre-restore-main
        (descriptor-coordinate (::pre-restore-main-descriptor intent))
        database-id (::coordinate/database-id pre-restore-main)
        main (::main-coordinate observation)
        forced-main (::forced-main-coordinate observation)
        forced-parents (::forced-main-parent-commit-ids observation)
        heads (::branch-heads observation)
        expected-roster (::expected-branch-roster intent)
        undo-branch (::undo-branch intent)
        prepared-target-branch (::prepared-target-branch intent)
        required-existing-roster
        (disj expected-roster undo-branch prepared-target-branch)
        points (cond-> (into [main] (vals heads)) forced-main (conj forced-main))]
    (require-consistency!
     (every? #(= database-id (::coordinate/database-id %)) points)
     "Restore observations cross physical databases."
     {::intent-id (::intent-id intent)})
    (require-consistency!
     (every? (fn [[branch point]] (= branch (::coordinate/branch point))) heads)
     "A restore branch observation is keyed by another branch."
     {::intent-id (::intent-id intent)})
    (require-consistency!
     (and (= main (get heads :db))
          (every? (set (keys heads)) required-existing-roster)
          (every? expected-roster (keys heads)))
     "The observed durable branch roster is missing or contains an unknown branch."
     {::expected-branch-roster expected-roster
      ::branch-heads heads})
    (when forced-main
      (require-consistency!
       (and (= main forced-main)
            (= (::coordinate/branch pre-restore-main)
               (::coordinate/branch forced-main))
            (not= pre-restore-main forced-main))
       "The observed forced main coordinate is not the exact current main."
       {::main-coordinate main
        ::forced-main-coordinate forced-main}))
    (require-consistency!
     (= (boolean forced-main) (boolean (seq forced-parents)))
     "Forced-main parent evidence exists without its exact result coordinate."
     {::forced-main-coordinate forced-main
      ::forced-main-parent-commit-ids forced-parents})
    observation))

(defn next-command
  "Next restore command derived only from intent and current facts."
  {:malli/schema [:=> [:cat ::next-command-request] ::next-command-result]}
  [{::keys [intent observation] :as request}]
  (validate! ::next-command-request request
             "The restore retry request is invalid.")
  (let [intent (validate-intent intent)
        observation (validate-observation-consistency! intent observation)
        intent-id (::intent-id intent)
        pre-restore-main
        (descriptor-coordinate (::pre-restore-main-descriptor intent))
        selected-target
        (descriptor-coordinate (::selected-target-descriptor intent))
        target (::prepared-target-coordinate intent)
        undo (::undo-coordinate intent)
        heads (::branch-heads observation)
        undo-head (get heads (::undo-branch intent))
        target-head (get heads (::prepared-target-branch intent))
        main (::main-coordinate observation)
        forced-main (::forced-main-coordinate observation)
        pre-restore-main? (= pre-restore-main main)
        selected-target-parent?
        (= #{(::coordinate/commit-id selected-target)}
           (::forced-main-parent-commit-ids observation))
        completed?
        (contains? (::completed-intent-ids observation) intent-id)
        reserved-heads-converged?
        (and (= undo undo-head) (= target target-head))
        command
        (cond
          (and forced-main completed? selected-target-parent?
               reserved-heads-converged?)
          :seon.dev.restore.command/prove-readiness

          completed?
          :seon.dev.restore.command/diagnose-divergence

          (and pre-restore-main? (nil? undo-head))
          :seon.dev.restore.command/create-undo

          (and pre-restore-main? (not= undo undo-head))
          :seon.dev.restore.command/diagnose-divergence

          (and pre-restore-main? (nil? target-head))
          :seon.dev.restore.command/create-target

          (and pre-restore-main? (not= target target-head))
          :seon.dev.restore.command/diagnose-divergence

          pre-restore-main?
          :seon.dev.restore.command/prepare-exclusive-transition

          (and forced-main selected-target-parent?
               reserved-heads-converged?)
          :seon.dev.restore.command/reconstruct-and-complete

          :else
          :seon.dev.restore.command/diagnose-divergence)]
    {::intent-id intent-id ::command command}))
