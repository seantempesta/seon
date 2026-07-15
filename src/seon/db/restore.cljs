(ns seon.db.restore
  "Durable completion facts for one fully verified database restore."
  (:require
    [seon.db :as db]
    [seon.db.coordinate :as coordinate]
    [seon.db.id :as db.id]
    [seon.db.protocol :as protocol]
    [seon.error :as error]
    [seon.launch :as launch]
    [seon.schema :as schema]))

;;; Durable completion fact

(schema/register!
  ::id
  [:and {:seon.db/identity true
         :seon.db.id/generator :seon.db.id.generator/compact}
   ::db.id/compact-value])
(schema/register! ::db-name :keyword)
(schema/register! ::database-id ::coordinate/database-id)
(schema/register! ::from-branch :keyword)
(schema/register! ::from-commit-id :uuid)
(schema/register! ::from-t :int)
(schema/register! ::to-branch :keyword)
(schema/register! ::to-commit-id :uuid)
(schema/register! ::to-t :int)
(schema/register! ::forced-commit-id :uuid)
(schema/register! ::undo-branch :keyword)
(schema/register! ::target-branch :keyword)
(schema/register! ::core-overlay-digest :string)
(schema/register! ::config-overlay-digest :string)

(schema/register! ::completion
  [:map {:closed true :seon.db/entity true}
   [::id ::id]
   [::db-name ::db-name]
   [::database-id ::database-id]
   [::from-branch ::from-branch]
   [::from-commit-id ::from-commit-id]
   [::from-t ::from-t]
   [::to-branch ::to-branch]
   [::to-commit-id ::to-commit-id]
   [::to-t ::to-t]
   [::forced-commit-id ::forced-commit-id]
   [::undo-branch ::undo-branch]
   [::target-branch ::target-branch]
   [::core-overlay-digest {:optional true} ::core-overlay-digest]
   [::config-overlay-digest {:optional true} ::config-overlay-digest]])

(schema/register! ::completion-from-launch-request
  [:map {:closed true}
   [::launch/descriptor ::launch/descriptor]])

(defn completion-from-launch
  "Derive one preserve-only completion from validated startup evidence."
  {:malli/schema
   [:=> [:cat ::completion-from-launch-request] ::completion]}
  [{descriptor ::launch/descriptor}]
  (let [descriptor (launch/validate-descriptor descriptor)
        startup (::launch/restore-startup descriptor)
        _ (when-not startup
            (throw
              (ex-info "The launch descriptor has no restore startup evidence."
                       {:seon.error/kind :core-bug})))
        identity (:seon.dev.restore/startup-identity startup)
        admin (:seon.db.restore-admin/result startup)
        database (::launch/database descriptor)
        from (:seon.db.restore-admin/pre-restore-main-coordinate admin)
        to (:seon.db.restore-admin/selected-target-coordinate admin)
        forced (:seon.db.restore-admin/forced-main-coordinate admin)
        undo (:seon.db.restore-admin/undo-coordinate admin)
        target (:seon.db.restore-admin/prepared-target-coordinate admin)]
    {::id (:seon.dev.restore/intent-id identity)
     ::db-name (keyword (::protocol/database-name database))
     ::database-id (::coordinate/database-id forced)
     ::from-branch (::coordinate/branch from)
     ::from-commit-id (::coordinate/commit-id from)
     ::from-t (::coordinate/t from)
     ::to-branch (::coordinate/branch to)
     ::to-commit-id (::coordinate/commit-id to)
     ::to-t (::coordinate/t to)
     ::forced-commit-id (::coordinate/commit-id forced)
     ::undo-branch (::coordinate/branch undo)
     ::target-branch (::coordinate/branch target)}))

;;; Idempotent completion operation

(schema/register! ::ok? :boolean)
(schema/register! ::recorded? :boolean)
(schema/register! ::already-completed? :boolean)
(schema/register! ::completion-coordinate ::coordinate/coordinate)
(schema/register! ::record-response
  [:or
   [:map {:closed true}
    [::ok? [:= true]]
    [::recorded? ::recorded?]
    [::already-completed? ::already-completed?]
    [::completion ::completion]
    [::completion-coordinate ::completion-coordinate]]
   [:map {:closed true}
    [::ok? [:= false]]
    [:seon/error :map]]])

(def completion-attrs
  "The complete Datahike attribute closure for restore completion facts."
  [::id
   ::db-name
   ::database-id
   ::from-branch
   ::from-commit-id
   ::from-t
   ::to-branch
   ::to-commit-id
   ::to-t
   ::forced-commit-id
   ::undo-branch
   ::target-branch
   ::core-overlay-digest
   ::config-overlay-digest])

(defn- failure
  [message data]
  {::ok? false
   :seon/error
   (error/->map
     (ex-info message (assoc data :seon.error/kind :core-bug)))})

(defn- completion-value
  [database id]
  (when (contains? (db/installed-schema database) ::id)
    (some-> (db/entity {:seon.db/db database
                        :seon.db/ref [::id id]})
            (select-keys completion-attrs))))

(defn- completion-transaction
  [database id]
  (when (contains? (db/installed-schema database) ::id)
    (db/query
      {:seon.db/db (db/history database)
       :seon.db/query
       '[:find ?transaction .
         :in $ ?id
         :where
         [?completion :seon.db.restore/id ?id ?transaction true]]
       :seon.db/args [id]})))

(defn ^:async ^:private exact-existing-result
  [database completion existing]
  (if (not= completion existing)
    (failure
      "Restore completion id already names different facts."
      {::expected completion ::actual existing})
    (let [transaction (completion-transaction database (::id completion))
          head (db/head-coordinate database)
          completion-coordinate
          (if (= transaction (::coordinate/t head))
            head
            (await
             (db/resolve-transaction-coordinate!
              {:seon.db/head-coordinate head
               :seon.db/transaction-id transaction})))]
      (if (schema/valid-candidate-value?
           ::coordinate/coordinate completion-coordinate)
        {::ok? true
         ::recorded? false
         ::already-completed? true
         ::completion existing
         ::completion-coordinate completion-coordinate}
        (failure
          "Restore completion transaction coordinate could not be resolved."
          {::completion completion
           ::transaction transaction
           ::completion-coordinate completion-coordinate
           ::head-coordinate head})))))

(defn- transaction-failure
  [envelope]
  {::ok? false
   :seon/error
   (or (:seon.db/error envelope)
       (error/->map
         (ex-info "Restore completion transaction failed."
                  {::transaction-envelope envelope
                   :seon.error/kind :core-bug})))})

(defn ^{:async true :seon.fn/agent-facing? false} record!
  "Record or prove one exact restore completion at the current head.

   Retry first reads the identity from one frozen database value. An equal
   fact returns its original transaction coordinate without transacting; the
   same id with any different required or optional value fails closed. A new
   fact commits through `seon.db/transact!` with a whole-head fence, then reads
   back every completion attribute and the identity datom's transaction.

   The caller owns root/boot transaction provenance. Admission remains outside
   this operation."
  {:malli/schema [:=> [:cat ::completion] ::record-response]}
  [completion]
  (try
    (let [database @db/*conn*
          missing-schema
          (into [] (remove #(contains? (db/installed-schema database) %))
                completion-attrs)
          existing (completion-value database (::id completion))]
      (cond
        (seq missing-schema)
        (failure
          "Restore completion schema must be installed before closed publication."
          {::missing-schema missing-schema})

        existing
        (await (exact-existing-result database completion existing))

        :else
        (let [expected-coordinate (db/head-coordinate database)
              envelope
              (await
                (db/transact!
                  {:seon.db/expected-coordinate expected-coordinate
                   :seon.db/tx-data [completion]}))]
          (if (false? (:seon.db/ok? envelope))
            (transaction-failure envelope)
            (let [coordinate (:seon.db/coordinate envelope)
                  committed @db/*conn*
                  readback (completion-value committed (::id completion))
                  transaction
                  (completion-transaction committed (::id completion))
                  current-coordinate (db/head-coordinate committed)]
              (if (and (= completion readback)
                       (= coordinate current-coordinate)
                       (= transaction (::coordinate/t coordinate)))
                {::ok? true
                 ::recorded? true
                 ::already-completed? false
                 ::completion readback
                 ::completion-coordinate coordinate}
                (failure
                  "Restore completion read-back did not prove its commit."
                  {::expected completion
                   ::actual readback
                   ::transaction transaction
                   ::completion-coordinate coordinate
                   ::current-coordinate current-coordinate})))))))
    (catch :default exception
      (failure "Restore completion recording failed."
               {:seon.error/raw exception}))))
