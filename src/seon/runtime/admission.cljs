(ns seon.runtime.admission
  "One process-local admission boundary for executable runtime work.

   Canonical program/schema facts remain database truth. This namespace owns
   only whether the current process has reconstructed and verified one exact
   committed generation. Closing admission hides process-local wrapper and
   projection surgery from agent, schedule, and web execution boundaries."
  (:require
    [cljs.reader :as reader]
    [seon.db :as db]
    [seon.db.protocol :as protocol]
    [seon.error :as error]
    [seon.instrument :as instrument]
    [seon.schema :as schema]))

(schema/register! ::status
  [:enum :starting :publishing :available :quiescing :unavailable])
(schema/register! ::generation :int)
(schema/register! ::publication :int)
(schema/register! ::reason :string)
(schema/register! ::admitted? :boolean)
(schema/register! ::prepared? :boolean)
(schema/register! ::published? :boolean)
(schema/register! ::recovered? :boolean)
(schema/register! ::detached? :boolean)
(schema/register! ::record-failures? :boolean)
(schema/register! ::instrument? :boolean)
(schema/register! ::prepare-request
                  [:map {:closed true}
                   [::record-failures? {:optional true} ::record-failures?]
                   [::instrument? {:optional true} ::instrument?]])
(schema/register! ::state
  [:map
   [::status ::status]
   [::generation {:optional true} ::generation]
   [::publication {:optional true} ::publication]
   [::reason {:optional true} ::reason]])

(defonce ^:private !state
  (atom {::status :starting}))

(defn state
  "Immutable process admission state.

   The optional generation is the accepted schema projection fingerprint, not
   a second counter or durable program identity."
  {:malli/schema [:=> [:cat] ::state]}
  []
  (select-keys @!state [::status ::generation ::publication ::reason]))

(defn available?
  "True only after the process has verified one committed generation."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (= :available (::status @!state)))

(defn quiescing?
  "True while planned shutdown is draining already-admitted work."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (= :quiescing (::status @!state)))

(defn begin-quiesce!
  "Synchronously refuse new work for one planned drain.

   Returns true only to the caller that changes `:available` to
   `:quiescing`. Repeated callers observe the same closed transition without
   acquiring a second lifecycle owner."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [[before after]
        (swap-vals!
          !state
          (fn [{::keys [status] :as current}]
            (if (= :available status)
              (assoc current ::status :quiescing)
              current)))]
    (and (= :available (::status before))
         (= :quiescing (::status after)))))

(defn unavailable
  "Typed refusal returned by executable boundaries while admission is closed.

   Refusal is observation only. It never records another core fault."
  {:malli/schema [:=> [:cat]
                  [:map
                   [::admitted? [:= false]]
                   [:seon/error :map]]]
   :seon.fn/agent-facing? false}
  []
  (let [{::keys [status generation reason]} @!state]
    {::admitted? false
     :seon/error
     (cond->
       {:seon.error/kind :seon.runtime/unavailable
        :seon.error/message
        (if (= :quiescing status)
          "Runtime is quiescing for planned maintenance; no new executable work is admitted."
          "Runtime program generation is unavailable; inspect the recorded core fault and restart after repairing canonical program facts.")
        :seon.error/data {::status status}}
       generation
       (assoc-in [:seon.error/data ::generation] generation)

       reason
       (assoc-in [:seon.error/data ::reason] reason))}))

(defn begin-publication!
  "Synchronously close executable admission for one publication transition.

   Returns true only to the caller that changed `:starting` or `:available` to
   `:publishing`. Concurrent/repeated callers do not acquire ownership."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [[before after]
        (swap-vals!
          !state
          (fn [{::keys [status] :as current}]
            (if (#{:starting :available :unavailable} status)
              (cond->
                {::status :publishing
                 ::publication (inc (get current ::publication 0))
                 ::previous-projection (schema/current-projection)}
                (contains? current ::instrument?)
                (assoc ::instrument? (::instrument? current))

                (some? (::generation current))
                (assoc ::generation (::generation current)))
              current)))]
    (and (not= before after)
         (= :publishing (::status after)))))

(defn- transition-unavailable!
  ([reason generation]
   (transition-unavailable! reason generation nil))
  ([reason generation publication]
   (let [[before after]
         (swap-vals!
           !state
           (fn [{::keys [status] :as current}]
             (if (and (= :publishing status)
                      (or (nil? publication)
                          (= publication (::publication current))))
               (cond-> {::status :unavailable ::reason reason}
                 (contains? current ::publication)
                 (assoc ::publication (::publication current))

                 (contains? current ::instrument?)
                 (assoc ::instrument? (::instrument? current))

                 generation (assoc ::generation generation))
               current)))]
     (and (= :publishing (::status before))
          (= :unavailable (::status after))))))

(defn mark-unavailable!
  "Fail closed after an owned publication occurrence.

   The first transition from `:publishing` records one core fault. Repeated
   calls and boundary refusals are idempotent and never create an error census.
   An optional `::publication` scopes the failure to the acquisition observed
   by the caller: when a newer publication owns admission, the stale caller's
   failure is a superseded occurrence and transitions nothing."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [:seon.error/raw :any]
      [::reason ::reason]
      [::generation {:optional true} ::generation]
      [::publication {:optional true} ::publication]]]
    :boolean]}
  [{raw :seon.error/raw ::keys [reason generation publication]}]
  (let [owned? (transition-unavailable! reason generation publication)]
    (when owned?
      (error/record! {:seon.error/raw raw :seon.error/fault :core}))
    owned?))

(defn- admit-generation!
  "Open admission for the verified committed projection fingerprint."
  [generation]
  (let [[before after]
        (swap-vals!
          !state
          (fn [{::keys [status] :as current}]
            (if (and (= :publishing status)
                     (= generation (::prepared-generation current)))
              (cond-> {::status :available ::generation generation}
                (contains? current ::publication)
                (assoc ::publication (::publication current))

                (contains? current ::instrument?)
                (assoc ::instrument? (::instrument? current)))
              current)))]
    (and (= :publishing (::status before))
         (= :available (::status after)))))

(def ^:private schema-query
  '[:find ?key ?form
    :where
    [?schema :seon.schema/key ?key]
    [?schema :seon.schema/form ?form]])

(def ^:private function-contract-query
  '[:find ?sym ?form
    :where
    [?function :seon.fn/sym ?sym]
    [?function :seon.fn/spec ?form]])

(defn ^:no-doc committed-projection
  "Build the canonical projection from ordinary acquired rows."
  {:malli/schema [:=> [:catn [::acquired :map]] :map]}
  [{::keys [schema-rows function-contract-rows]}]
  (let [forms
        (into {}
              (map (fn [[key form]]
                     [key (reader/read-string form)]))
              schema-rows)
        function-contracts
        (into {}
              (map (fn [[sym form]]
                     [(symbol sym) (reader/read-string form)]))
              function-contract-rows)]
    (schema/build-projection forms function-contracts)))

(defn- query-member
  [query]
  {::protocol/operation protocol/query-operation
   ::protocol/query-form query
   ::protocol/arguments []
   :datahike.resource/max-work 1000000
   :datahike.resource/max-results 4096
   :datahike.resource/max-result-weight (* 3 1024 1024)})

(defn- query-result
  [member]
  (if (::protocol/success? member)
    (:datahike.query/result member)
    (throw (ex-info "Committed program acquisition failed."
                    {:seon.db/error member :seon.error/kind :core-bug}))))

(defn ^:async ^:private acquire-committed-projection!
  []
  (let [database (await (db/db))
        acquired
        (await
         (db/execute-many
          {::db/db database
           ::db/max-result-weight (* 6 1024 1024)
           ::db/members [(query-member schema-query)
                         (query-member function-contract-query)]}))
        _ (when (:seon.error/message acquired)
            (throw (ex-info "Committed program acquisition failed."
                            {:seon.db/error acquired
                             :seon.error/kind :core-bug})))
        [schemas contracts] (::db/results acquired)]
    {::db/db database
     ::schema-rows (query-result schemas)
     ::function-contract-rows (query-result contracts)}))

(defn- ^:async reconcile-committed!
  [old-projection instrument?]
  (let [acquired (await (acquire-committed-projection!))
        projection (committed-projection acquired)
        stats
        (if instrument?
          (instrument/reconcile-projection!
            {::instrument/old-projection old-projection
             ::instrument/new-projection projection})
          {::instrument/enabled? false
           ::instrument/ok? true
           ::instrument/n-unstrumented 0
           ::instrument/n-instrumented 0
           ::instrument/verification-gaps []})]
    (when (false? (::instrument/ok? stats))
      (let [failure
            (select-keys stats [::instrument/rejected
                                ::instrument/verification-gaps])]
        (throw
          (ex-info
            (str "Committed program generation failed complete wrapper "
                 "verification " (pr-str failure))
            {:seon.instrument/stats stats
             ::generation
             (:seon.schema.projection/fingerprint projection)}))))
    (schema/activate-projection! projection)
    {::projection projection
     ::db/db (::db/db acquired)
     ::instrumentation stats
     ::generation (:seon.schema.projection/fingerprint projection)}))

(defn- retain-prepared-generation!
  [generation]
  (let [[before after]
        (swap-vals!
          !state
          (fn [{::keys [status] :as current}]
            (if (and (= :publishing status)
                     (not (contains? current ::prepared-generation)))
              (assoc current ::prepared-generation generation)
              current)))]
    (and (= :publishing (::status before))
         (not (contains? before ::prepared-generation))
         (= generation (::prepared-generation after)))))

(defn ^:async prepare-committed!
  "Reconstruct and retain one verified committed projection.

   Normal callers acquire publication here. Cold boot may call
   [[begin-publication!]] before replaying stored namespaces, then invoke this
   function while the state is already `:publishing`. One failed attempt is
   recorded once and repaired from a newly frozen current database value. The
   verified projection remains hidden behind `:publishing` until
   [[admit-prepared!]] receives this function's exact generation."
  {:malli/schema
   [:=> [:cat ::prepare-request]
    [:map
     [::prepared? :boolean]
     [::recovered? :boolean]
     [::generation {:optional true} ::generation]
     [::instrumentation {:optional true} :map]
     [:seon/error {:optional true} :map]]]}
  [{::keys [record-failures?] :or {record-failures? true} :as request}]
  (let [current @!state
        instrument? (if (contains? request ::instrument?)
                      (::instrument? request)
                      (get current ::instrument? true))
        owned? (or (and (= :publishing (::status current))
                        (not (contains? current ::prepared-generation)))
                   (begin-publication!))]
    (if-not owned?
      (assoc (unavailable) ::prepared? false ::recovered? false)
      (let [_ (swap! !state assoc ::instrument? instrument?)
            old-projection (::previous-projection @!state)]
        (try
          (let [{::keys [generation instrumentation]}
                (await (reconcile-committed! old-projection instrument?))]
            (if-not (retain-prepared-generation! generation)
              ;; Another settlement (a concurrent prepare or a newer
              ;; publication) closed this window first. Losing retention is
              ;; ordinary supersession, never a core fault: the winning
              ;; settlement owns admission over the same committed facts.
              (assoc (unavailable)
                     ::prepared? false
                     ::recovered? false
                     ::generation generation)
              {::prepared? true
               ::recovered? false
               ::generation generation
               ::instrumentation instrumentation}))
          (catch :default original
            ;; The occurrence is one fault whether reconstruction repairs it
            ;; or the process remains unavailable. Boundary refusals never
            ;; write another row.
            (when record-failures?
              (error/record!
                {:seon.error/raw original :seon.error/fault :core}))
            (try
              (let [{::keys [generation instrumentation]}
                    (await (reconcile-committed! old-projection instrument?))]
                (if-not (retain-prepared-generation! generation)
                  (assoc (unavailable)
                         ::prepared? false
                         ::recovered? false
                         ::generation generation)
                  {::prepared? true
                   ::recovered? true
                   ::generation generation
                   ::instrumentation instrumentation}))
              (catch :default repair
                (let [generation
                      (or (::generation (ex-data repair))
                          (::generation @!state))
                      reason
                      (str "Committed program reconstruction failed: "
                           (or (.-message repair) (str repair)))]
                  (transition-unavailable! reason generation)
                  {::prepared? false
                   ::recovered? false
                   ::generation generation
                   :seon/error
                   (:seon/error (unavailable))})))))))))

(defn admit-prepared!
  "Admit the exact verified projection retained by this publication."
  {:malli/schema
   [:=>
    [:cat
     [:map
      [::prepared? :boolean]
      [::recovered? :boolean]
      [::generation {:optional true} ::generation]
      [::instrumentation {:optional true} :map]
      [:seon/error {:optional true} :map]]]
    [:map
     [::published? :boolean]
     [::recovered? :boolean]
     [::generation {:optional true} ::generation]
     [::instrumentation {:optional true} :map]
     [:seon/error {:optional true} :map]]]}
  [{::keys [prepared? generation] :as preparation}]
  (let [publication (-> preparation
                        (dissoc ::prepared?)
                        (assoc ::published? false))]
    (cond
      (not prepared?)
      publication

      (and generation (admit-generation! generation))
      (assoc publication ::published? true)

      :else
      (assoc publication
             :seon/error
             (error/->map
               (ex-info
                 "Prepared program generation no longer owns publication"
                 {::generation generation
                  ::state (state)}))))))

(defn ^:async publish-committed!
  "Reconstruct, verify, and immediately admit the committed program."
  {:malli/schema
   [:=> [:cat]
    [:map
     [::published? :boolean]
     [::recovered? :boolean]
     [::generation {:optional true} ::generation]
     [::instrumentation {:optional true} :map]
     [:seon/error {:optional true} :map]]]}
  []
  (admit-prepared! (await (prepare-committed! {}))))

(defn detach!
  "Close admission and remove the detached database's live projection.

   Reconciles every wrapper owned by the active projection to the empty
   projection before publishing `:starting`. Repeated calls are idempotent.
   A failed reconciliation leaves admission unavailable and returns an error;
   the lifecycle owner must retain the database session for retry."
  {:malli/schema
   [:=> [:cat]
    [:map {:closed true}
     [::detached? :boolean]
     [::instrumentation {:optional true} :map]
     [:seon/error {:optional true} :map]]]}
  []
  (let [[before after]
        (swap-vals!
          !state
          (fn [{::keys [status] :as current}]
            (if (= :publishing status)
              current
              (cond->
                {::status :publishing
                 ::publication (inc (get current ::publication 0))
                 ::previous-projection (schema/current-projection)}
                (contains? current ::instrument?)
                (assoc ::instrument? (::instrument? current))))))
        acquired? (and (not= before after)
                       (= :publishing (::status after)))]
    (if-not acquired?
      {::detached? false
       :seon/error
       (error/->map
         (ex-info "Runtime publication is already in progress."
                  {::status (::status before)}))}
      (let [old-projection (::previous-projection after)
            empty-projection (schema/build-projection {})
            instrument? (get after ::instrument? true)]
        (try
          (let [instrumentation
                (if instrument?
                  (instrument/reconcile-projection!
                    {::instrument/old-projection old-projection
                     ::instrument/new-projection empty-projection})
                  {::instrument/enabled? false
                   ::instrument/ok? true
                   ::instrument/n-unstrumented 0
                   ::instrument/n-instrumented 0
                   ::instrument/verification-gaps []})]
            (when (false? (::instrument/ok? instrumentation))
              (throw
                (ex-info
                  "Detached projection failed complete wrapper removal"
                  {:seon.instrument/stats instrumentation})))
            (schema/activate-projection! empty-projection)
            (reset! !state {::status :starting
                            ::publication (get after ::publication 0)
                            ::instrument? instrument?})
            {::detached? true
             ::instrumentation instrumentation})
          (catch :default detach-error
            (mark-unavailable!
              {:seon.error/raw detach-error
               ::reason
               (str "Runtime projection detach failed: "
                    (or (.-message detach-error) (str detach-error)))})
            {::detached? false
             :seon/error (error/->map detach-error)}))))))
