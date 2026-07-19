(ns seon.db.restore
  "Define durable completion facts for verified database restores.

   The portable model records the identity and proof of a completed restore;
   filesystem orchestration and writer admission remain outside this namespace."
  (:require
   [seon.db.branch :as branch]
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
        from (:seon.db.restore-admin/pre-restore-main-branch-head admin)
        to (:seon.db.restore-admin/selected-target-branch-head admin)
        forced (:seon.db.restore-admin/forced-main-branch-head admin)
        undo (:seon.db.restore-admin/undo-branch-head admin)
        target (:seon.db.restore-admin/prepared-target-branch-head admin)]
    {::plan-digest (:seon.dev.restore/plan-digest identity)
     ::db-name (keyword (::protocol/database-name database))
     ::database-id (::branch/store-id forced)
     ::from-branch (::branch/name from)
     ::from-commit-id (::branch/commit-id from)
     ::from-t (::branch/basis-t from)
     ::to-branch (::branch/name to)
     ::to-commit-id (::branch/commit-id to)
     ::to-t (::branch/basis-t to)
     ::forced-commit-id (::branch/commit-id forced)
     ::undo-branch (::branch/name undo)
     ::target-branch (::branch/name target)}))

;;; Idempotent completion operation

(schema/register! ::ok? :boolean)
(schema/register! ::recorded? :boolean)
(schema/register! ::already-completed? :boolean)
(schema/register! ::completion-branch-head ::branch/head)
(schema/register! ::expected-db :seon.db/db)
(schema/register! ::current-db :seon.db/db)
(schema/register! ::current-branch-head ::branch/head)
(schema/register! ::installed-schema :map)
(schema/register! ::ready? :boolean)
(schema/register! ::executable? :boolean)
(schema/register! ::record-request
                  [:map {:closed true}
                   [::completion-claim ::completion-claim]
                   [::expected-db ::expected-db]])
(schema/register! ::record-success
                  [:map {:closed true}
                   [::ok? [:= true]]
                   [::recorded? ::recorded?]
                   [::already-completed? ::already-completed?]
                   [::completion ::completion]
                   [::completion-branch-head ::completion-branch-head]])
(schema/register! ::record-failure
                  [:map {:closed true}
                   [::ok? [:= false]]
                   [:seon/error :map]])
(schema/register! ::record-response
                  [:or ::record-success ::record-failure])

(schema/register!
 ::readiness-response
 [:or
  [:map {:closed true}
   [::ready? [:= true]]
   [::executable? [:= false]]
   [::completion ::current-completion]
   [::completion-branch-head ::completion-branch-head]]
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
                  [:tuple :qualified-keyword ::branch/basis-t])
(schema/register! ::publication-rows [:vector ::publication-row])
(schema/register! ::publication-proof-request
                  [:map {:closed true}
                   [::completion ::completion]
                   [::publication-rows ::publication-rows]])
(schema/register! ::transaction ::branch/basis-t)
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
   (do
     (schema/register!
      ::acquire-completion-request
      [:map {:closed true}
       [::plan-digest ::plan-digest]
       [:seon.db/db {:optional true} :seon.db/db]])
     (schema/register!
      ::acquired-completion
      [:map {:closed true}
       [::current-db ::current-db]
       [::installed-schema ::installed-schema]
       [::db.id/generator-policies {:optional true} ::db.id/generator-policies]
       [::completion {:optional true} ::current-completion]
       [::publication-rows ::publication-rows]])))

#?(:cljs
   (def ^:private publication-query
     '[:find ?attribute ?transaction
       :in $ ?identity-attr ?identity
       :where
       [?completion ?identity-attr ?identity]
       [?completion ?attribute _ ?transaction]]))

#?(:cljs
   (def ^:private generator-policy-query
     '[:find ?generator .
       :in $ ?identity-attr
       :where
       [?schema :seon.schema/key ?identity-attr]
       [?schema :seon.db.id/generator ?generator]]))

#?(:cljs
   (defn- acquisition-error
     [operation member]
     {:seon.error/message (str "Restore " operation " acquisition failed.")
      :seon.error/kind (or (:seon.error/kind member) :core-bug)
      :seon.error/data {:seon.db/member member}}))

#?(:cljs
   (defn ^{:async true :seon.fn/agent-facing? false} acquire-completion!
     "Acquire restore completion, publication facts, schema, and branch-head."
     {:malli/schema
      [:=> [:cat ::acquire-completion-request]
       [:or ::acquired-completion
        [:map
         [:seon.error/message :string]
         [:seon.error/kind :keyword]
         [:seon.error/data {:optional true} :map]]]]}
     [{::keys [plan-digest] requested-db :seon.db/db}]
     (let [database (or requested-db (await (db/db)))
           acquired
           (await
            (db/execute-many
             {:seon.db/db database
              :seon.db/members
                [{::protocol/operation protocol/schema-operation}
                 {::protocol/operation protocol/pull-operation
                  ::protocol/selector completion-attrs
                  ::protocol/entity-id [::plan-digest plan-digest]
                  :datahike.resource/max-work 100000
                  :datahike.resource/max-results 1
                  :datahike.resource/max-result-weight 65536}
                 {::protocol/operation protocol/query-operation
                  ::protocol/query-form publication-query
                  ::protocol/arguments [::plan-digest plan-digest]
                  :datahike.resource/max-work 100000
                  :datahike.resource/max-results 32
                  :datahike.resource/max-result-weight 65536}
                 {::protocol/operation protocol/query-operation
                  ::protocol/query-form generator-policy-query
                  ::protocol/arguments [::id]
                  :datahike.resource/max-work 100000
                  :datahike.resource/max-results 1
                  :datahike.resource/max-result-weight 65536}]}))
           [schema-member completion-member publication-member policy-member]
           (:seon.db/results acquired)]
       (cond
         (:seon.error/message acquired)
         acquired

         (not (true? (::protocol/success? schema-member)))
         (acquisition-error "schema" schema-member)

         :else
         (let [installed (::protocol/schema schema-member)
               missing (remove #(contains? installed %) completion-attrs)]
           (if (seq missing)
             {::current-db database
              ::installed-schema installed
              ::publication-rows []}
             (cond
               (not (true? (::protocol/success? completion-member)))
               (acquisition-error "completion" completion-member)

               (not (true? (::protocol/success? publication-member)))
               (acquisition-error "publication" publication-member)

               (not (true? (::protocol/success? policy-member)))
               (acquisition-error "generated-id policy" policy-member)

               :else
               (cond->
                 {::current-db database
                  ::installed-schema installed
                  ::db.id/generator-policies
                  {::id (:datahike.query/result policy-member)}
                  ::publication-rows
                  (->> (:datahike.query/result publication-member)
                       (sort-by (comp str first))
                       vec)}
                 (::protocol/result completion-member)
                 (assoc ::completion
                        (select-keys (::protocol/result completion-member)
                                     completion-attrs))))))))))

#?(:cljs
   (defn ^:async ^:private exact-existing-result
     [claim existing publication-rows database recorded?]
     (if (not= claim (dissoc existing ::id))
       (failure
        "Restore completion plan already names different facts."
        {::expected claim ::actual existing})
       (let [head (branch/head-from-database-value database)
             proof (publication-proof
                    {::completion existing
                     ::publication-rows publication-rows})
             transaction (::transaction proof)
             completion-branch-head
             (when (::ok? proof)
               (if (= transaction (::branch/basis-t head))
                 head
                 (await
                  (db/resolve-transaction-branch-head!
                   {:seon.db/containing-branch-head head
                    :seon.db/transaction-id transaction}))))]
         (if (and (::ok? proof)
                  (schema/valid-candidate-value?
                   ::branch/head completion-branch-head))
           {::ok? true
            ::recorded? recorded?
            ::already-completed? (not recorded?)
            ::completion existing
            ::completion-branch-head completion-branch-head}
           (failure
            "Restore completion facts do not share one resolvable transaction."
            {::completion existing
             ::publication-proof proof
             ::transaction transaction
             ::completion-branch-head completion-branch-head
             ::head-branch-head head}))))))

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
      An equal claim returns its original transaction branch-head without a
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
        (let [{::keys [completion-claim expected-db]} request
             plan-digest (::plan-digest completion-claim)
             acquired (await (acquire-completion! {::plan-digest plan-digest}))
             current-db (::current-db acquired)
             installed (::installed-schema acquired)
             missing-schema
             (into [] (remove #(contains? installed %)) completion-attrs)
             existing (::completion acquired)]
         (cond
           (:seon.error/message acquired)
           {::ok? false :seon/error acquired}

           (seq missing-schema)
           (failure
            "Restore completion schema must be installed before closed publication."
            {::missing-schema missing-schema})

           existing
           (await
            (exact-existing-result completion-claim existing
                                   (::publication-rows acquired)
                                   current-db false))

           (not= expected-db current-db)
           (failure
            "Restore completion predecessor is no longer the current database value."
            {::expected-db expected-db
             ::current-db current-db})

           :else
           (let [envelope
                 (await
                  (db.id/allocate!
                   {::db.id/allocations
                    [{::db.id/key ::completion
                      ::db.id/identity-attr ::id}]
                    ::db.id/generator-policies
                    (::db.id/generator-policies acquired)
                    ::db.id/transaction-builder
                    (fn [ids]
                      {:seon.db/expected-db expected-db
                       :seon.db/tx-data
                       [(assoc completion-claim ::id (get ids ::completion))]
                       ::db.id/dependent-identities
                       [{::db.id/candidate-key ::completion
                         ::db.id/lookup-ref [::plan-digest plan-digest]}]})}))
                 readback-db (:db-after envelope)
                 readback-acquired
                 (await
                  (acquire-completion!
                   (cond-> {::plan-digest plan-digest}
                     readback-db (assoc :seon.db/db readback-db))))
                 readback (::completion readback-acquired)
                 allocated-id (get-in envelope [::db.id/ids ::completion])]
             (cond
               (:seon.error/message readback-acquired)
               {::ok? false :seon/error readback-acquired}

               (and readback
                    readback-db
                    (= allocated-id (::id readback)))
               (await
                (exact-existing-result
                 completion-claim readback
                 (::publication-rows readback-acquired)
                 (::current-db readback-acquired) true))

               readback
               ;; A concurrent publisher may win either the dependent unique
               ;; claim or the whole-head fence. Adopt only its exact facts.
               (await
                (exact-existing-result
                 completion-claim readback
                 (::publication-rows readback-acquired)
                 (::current-db readback-acquired) false))

               (:seon.error/message envelope)
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
       [::current-completion ::current-completion]
       [::completion-branch-head ::completion-branch-head]
       [::current-branch-head ::current-branch-head]
       [::publication-rows ::publication-rows]
       [:seon.runtime.admission/state
        [:map {:closed true}
         [:seon.runtime.admission/status
          [:enum :starting :publishing :available :quiescing :unavailable]]
         [:seon.runtime.admission/generation {:optional true} :int]
         [:seon.runtime.admission/reason {:optional true} :string]]]])))

#?(:cljs
   (defn readiness
     "Derive closed restore readiness from ordinary acquired facts."
     {:malli/schema [:=> [:cat ::readiness-request] ::readiness-response]}
     [request]
     (if-not (schema/valid-candidate-value? ::readiness-request request)
       (assoc (failure "Restore readiness request is invalid."
                       {::invalid-request-schema ::readiness-request})
              ::ready? false
              ::executable? false)
       (let [{::keys [completion current-completion completion-branch-head
                     current-branch-head publication-rows]
              admission-state :seon.runtime.admission/state} request
             proof (when current-completion
                     (publication-proof
                      {::completion current-completion
                       ::publication-rows publication-rows}))
             transaction (::transaction proof)
             executable? (= :available
                            (:seon.runtime.admission/status admission-state))
             ready? (and (= :publishing
                            (:seon.runtime.admission/status admission-state))
                         (= completion current-completion)
                         (::ok? proof)
                         (schema/valid-candidate-value?
                          ::branch/head current-branch-head)
                         (= :db (::branch/name current-branch-head))
                         (= completion-branch-head current-branch-head)
                         (= transaction
                            (::branch/basis-t completion-branch-head)))]
         (cond-> {::ready? (boolean ready?)
                  ::executable? executable?}
           ready? (assoc ::completion completion
                         ::completion-branch-head completion-branch-head))))))
