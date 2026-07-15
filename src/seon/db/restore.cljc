(ns seon.db.restore
  "Durable completion facts for one fully verified database restore."
  (:require
   [seon.db.coordinate :as coordinate]
   [seon.db.id :as db.id]
   [seon.db.protocol :as protocol]
   [seon.db.restore.schema]
   [seon.launch :as launch]
   [seon.schema :as schema]
   #?@(:cljs [[seon.db :as db]
              [seon.error :as error]])))

;;; Durable completion fact

(schema/register! ::completion-from-launch-request
                  [:map {:closed true}
                   [::launch/descriptor ::launch/descriptor]])

(defn completion-from-launch
  "Derive one preserve-only completion claim from startup evidence."
  {:malli/schema
   [:=> [:cat ::completion-from-launch-request] ::completion-claim]}
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
    {::plan-digest (:seon.dev.restore/plan-digest identity)
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
(schema/register! ::expected-coordinate ::coordinate/coordinate)
(schema/register! ::ready? :boolean)
(schema/register! ::executable? :boolean)
(schema/register! ::record-request
                  [:map {:closed true}
                   [::completion-claim ::completion-claim]
                   [::expected-coordinate ::expected-coordinate]])
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

(schema/register!
 ::readiness-response
 [:or
  [:map {:closed true}
   [::ready? [:= true]]
   [::executable? [:= false]]
   [::completion ::current-completion]
   [::completion-coordinate ::completion-coordinate]]
  [:map {:closed true}
   [::ready? [:= false]]
   [::executable? ::executable?]]
  [:map {:closed true}
   [::ok? [:= false]]
   [::ready? [:= false]]
   [::executable? [:= false]]
   [:seon/error :map]]])

(def completion-attrs
  "The complete Datahike attribute closure for restore completion facts."
  [::id
   ::plan-digest
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

(def completion-claim-attrs
  "The immutable plan key and payload accepted for new completion facts."
  (vec (remove #{::id} completion-attrs)))

(schema/register! ::publication-row
                  [:tuple :qualified-keyword ::coordinate/t])
(schema/register! ::publication-rows [:vector ::publication-row])
(schema/register! ::publication-proof-request
                  [:map {:closed true}
                   [::completion ::completion]
                   [::publication-rows ::publication-rows]])
(schema/register! ::transaction ::coordinate/t)
(schema/register! ::publication-proof
                  [:or
                   [:map {:closed true}
                    [::ok? [:= true]]
                    [::transaction ::transaction]]
                   [:map {:closed true}
                    [::ok? [:= false]]]])

(defn publication-proof
  "Prove every current completion fact originated in one transaction."
  {:malli/schema
   [:=> [:cat ::publication-proof-request] ::publication-proof]}
  [{::keys [completion publication-rows]}]
  (let [expected-attrs (set (keys completion))
        observed-attrs (mapv first publication-rows)
        transactions (set (map second publication-rows))
        current? (contains? completion ::plan-digest)
        valid-shape? (schema/valid-candidate-value?
                      (if current? ::current-completion ::legacy-completion)
                      completion)]
    (if (and valid-shape?
             (= expected-attrs (set observed-attrs))
             (= (count expected-attrs) (count observed-attrs))
             (= 1 (count transactions)))
      {::ok? true ::transaction (first transactions)}
      {::ok? false})))

#?(:cljs
   (defn- failure
     [message data]
     {::ok? false
      :seon/error
      (error/->map
       (ex-info message (assoc data :seon.error/kind :core-bug)))}))

#?(:cljs
   (defn- completion-value
     [database identity-attr identity]
     (when (contains? (db/installed-schema database) identity-attr)
       (some-> (db/entity {:seon.db/db database
                           :seon.db/ref [identity-attr identity]})
               (select-keys completion-attrs)))))

#?(:cljs
   (defn- publication-rows-for
     [database identity-attr identity]
     (if (contains? (db/installed-schema database) identity-attr)
       (->> (db/query
             {:seon.db/db database
              :seon.db/query
              '[:find ?attribute ?transaction
                :in $ ?identity-attr ?identity
                :where
                [?completion ?identity-attr ?identity]
                [?completion ?attribute _ ?transaction]]
              :seon.db/args [identity-attr identity]})
            (sort-by (comp str first))
            vec)
       [])))

#?(:cljs
   (defn ^:async ^:private exact-existing-result
     [database claim existing recorded?]
     (if (not= claim (dissoc existing ::id))
       (failure
        "Restore completion plan already names different facts."
        {::expected claim ::actual existing})
       (let [proof (publication-proof
                    {::completion existing
                     ::publication-rows
                     (publication-rows-for database ::plan-digest
                                           (::plan-digest claim))})
             transaction (::transaction proof)
             head (db/head-coordinate database)
             completion-coordinate
             (when (::ok? proof)
               (if (= transaction (::coordinate/t head))
                 head
                 (await
                  (db/resolve-transaction-coordinate!
                   {:seon.db/head-coordinate head
                    :seon.db/transaction-id transaction}))))]
         (if (and (::ok? proof)
                  (schema/valid-candidate-value?
                   ::coordinate/coordinate completion-coordinate))
           {::ok? true
            ::recorded? recorded?
            ::already-completed? (not recorded?)
            ::completion existing
            ::completion-coordinate completion-coordinate}
           (failure
            "Restore completion facts do not share one resolvable transaction."
            {::completion existing
             ::publication-proof proof
             ::transaction transaction
             ::completion-coordinate completion-coordinate
             ::head-coordinate head}))))))

#?(:cljs
   (defn- transaction-failure
     [envelope]
     {::ok? false
      :seon/error
      (or (:seon.db/error envelope)
          (error/->map
           (ex-info "Restore completion transaction failed."
                    {::transaction-envelope envelope
                     :seon.error/kind :core-bug})))}))

#?(:cljs
   (defn ^{:async true :seon.fn/agent-facing? false} record!
     "Record or prove one exact restore completion after its frozen head.

      Retry first reads the unique plan digest from one frozen database value.
      An equal claim returns its original transaction coordinate without a
      write. A new claim allocates its compact completion id and commits every
      fact atomically through `seon.db.id/allocate!` at the exact frozen head.

     The caller owns root/boot transaction provenance. Admission remains outside
      this operation."
     {:malli/schema [:=> [:cat ::record-request] ::record-response]}
     [request]
     (if-not (schema/valid-candidate-value? ::record-request request)
       (failure "Restore completion request is invalid."
                {::invalid-request-schema ::record-request})
       (try
        (let [{::keys [completion-claim expected-coordinate]} request
              database @db/*conn*
             missing-schema
             (into [] (remove #(contains? (db/installed-schema database) %))
                   completion-attrs)
             plan-digest (::plan-digest completion-claim)
             existing (completion-value database ::plan-digest plan-digest)]
         (cond
           (seq missing-schema)
           (failure
            "Restore completion schema must be installed before closed publication."
            {::missing-schema missing-schema})

           existing
           (await
            (exact-existing-result database completion-claim existing false))

           (not= expected-coordinate (db/head-coordinate database))
           (failure
            "Restore completion predecessor is no longer the current head."
            {::expected-coordinate expected-coordinate
             ::actual-coordinate (db/head-coordinate database)})

           :else
           (let [envelope
                 (await
                  (db.id/allocate!
                   {::db.id/allocations
                    [{::db.id/key ::completion
                      ::db.id/identity-attr ::id}]
                    ::db.id/transaction-builder
                    (fn [ids]
                      {:seon.db/expected-coordinate expected-coordinate
                       :seon.db/tx-data
                       [(assoc completion-claim ::id (get ids ::completion))]
                       ::db.id/dependent-identities
                       [{::db.id/candidate-key ::completion
                         ::db.id/lookup-ref [::plan-digest plan-digest]}]})
                    :seon.db/conn db/*conn*}))
                 committed @db/*conn*
                 readback
                 (completion-value committed ::plan-digest plan-digest)
                 allocated-id (get-in envelope [::db.id/ids ::completion])]
             (cond
               (and readback
                    (:seon.db/ok? envelope)
                    (= allocated-id (::id readback)))
               (await
                (exact-existing-result
                 committed completion-claim readback true))

               readback
               ;; A concurrent publisher may win either the dependent unique
               ;; claim or the whole-head fence. Adopt only its exact facts.
               (await
                (exact-existing-result
                 committed completion-claim readback false))

               (false? (:seon.db/ok? envelope))
               (transaction-failure envelope)

               :else
               (failure
                "Restore completion allocation committed without exact read-back."
                {::expected completion-claim
                 ::transaction-envelope envelope
                 ::actual readback})))))
        (catch :default exception
          (failure "Restore completion recording failed."
                   {:seon.error/raw exception}))))))

#?(:cljs
   (do
     (schema/register!
     ::readiness-request
      [:map {:closed true}
       [::completion ::current-completion]
       [::completion-coordinate ::completion-coordinate]
       [:seon.runtime.admission/state
        [:map {:closed true}
         [:seon.runtime.admission/status
          [:enum :starting :publishing :available :quiescing :unavailable]]
         [:seon.runtime.admission/generation {:optional true} :int]
         [:seon.runtime.admission/reason {:optional true} :string]]]
       [:seon.db/db :seon.db/db-val]])))

#?(:cljs
   (defn readiness
     "Derive closed restore readiness from one immutable database value."
     {:malli/schema [:=> [:cat ::readiness-request] ::readiness-response]}
     [request]
     (if-not (schema/valid-candidate-value? ::readiness-request request)
       (assoc (failure "Restore readiness request is invalid."
                       {::invalid-request-schema ::readiness-request})
              ::ready? false
              ::executable? false)
       (let [{::keys [completion completion-coordinate]
              admission-state :seon.runtime.admission/state
              database :seon.db/db} request
             current-coordinate (db/head-coordinate database)
             plan-digest (::plan-digest completion)
             existing (completion-value database ::plan-digest plan-digest)
             proof (when existing
                     (publication-proof
                      {::completion existing
                       ::publication-rows
                       (publication-rows-for
                        database ::plan-digest plan-digest)}))
             transaction (::transaction proof)
             executable? (= :available
                            (:seon.runtime.admission/status admission-state))
             ready? (and (= :publishing
                            (:seon.runtime.admission/status admission-state))
                         (= completion existing)
                         (::ok? proof)
                         (schema/valid-candidate-value?
                          ::coordinate/coordinate current-coordinate)
                         (= :db (::coordinate/branch current-coordinate))
                         (= completion-coordinate current-coordinate)
                         (= transaction
                            (::coordinate/t completion-coordinate)))]
         (cond-> {::ready? (boolean ready?)
                  ::executable? executable?}
           ready? (assoc ::completion completion
                         ::completion-coordinate completion-coordinate))))))
