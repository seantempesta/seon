(ns seon.db.restore
  "Durable completion facts for one fully verified database restore."
  (:require
    [seon.db :as db]
    [seon.db.coordinate :as coordinate]
    [seon.db.id :as db.id]
    [seon.error :as error]
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

(def ^:private completion-attrs
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

(defn- exact-existing-result
  [database completion existing]
  (if (not= completion existing)
    (failure
      "Restore completion id already names different facts."
      {::expected completion ::actual existing})
    (let [transaction (completion-transaction database (::id completion))
          head (db/head-coordinate database)]
      (if (= transaction (::coordinate/t head))
        {::ok? true
         ::recorded? false
         ::already-completed? true
         ::completion existing
         ::completion-coordinate head}
        (failure
          "Restore completion transaction is no longer the current head."
          {::completion completion
           ::transaction transaction
           ::completion-coordinate head})))))

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
   fact returns its original current-head coordinate without transacting; the
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
        (exact-existing-result database completion existing)

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
