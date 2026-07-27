(ns seon.runtime.admission
  "One process-local admission boundary for executable runtime work.

   Canonical program/schema facts remain database truth. This namespace owns
   only whether the current process has reconstructed and verified one exact
   committed generation. Closing admission hides process-local wrapper and
   projection surgery from agent, schedule, and web execution boundaries."
  (:require
    [cljs.reader :as reader]
    [seon.db :as db]
    [seon.db.protocol :as db.protocol]
    [seon.error :as error]
    [seon.instrument :as instrument]
    [seon.log :as log]
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
(schema/register! ::reusable-projection :map)
(schema/register! ::base-projection :map)
(schema/register! ::artifact-exports [:set :symbol])
(schema/register! ::prepare-request
                  [:map {:closed true}
                   [::record-failures? {:optional true} ::record-failures?]
                   [::instrument? {:optional true} ::instrument?]
                   [::reusable-projection
                    {:optional true}
                    ::reusable-projection]
                   [::base-projection
                    {:optional true}
                    ::base-projection]
                   [::artifact-exports
                    {:optional true}
                    ::artifact-exports]])
(schema/register! ::state
  [:map
   [::status ::status]
   [::generation {:optional true} ::generation]
   [::publication {:optional true} ::publication]
   [::reason {:optional true} ::reason]])

(defonce ^:private !state
  (atom {::status :starting}))

(defonce ^:private !base-projection
  ;; Installed only by a start request that already passed release/database
  ;; identity verification. Later hot publications reuse the same immutable
  ;; release base and read only the database-homed divergence overlay.
  (atom nil))

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
                   [:seon/error :map]]]}
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

(def ^:private acquisition-page-size 32)
(def ^:private acquisition-page-max-result-weight 60000)

(schema/register!
 :seon.runtime.admission.cache/id
 [:string {:seon.db/identity true}])
(schema/register!
 :seon.runtime.admission.cache/base-fingerprint
 [:int {:seon.db/no-history? true}])
(schema/register!
 :seon.runtime.admission.cache/divergence-fingerprint
 [:int {:seon.db/no-history? true}])
(schema/register!
 :seon.runtime.admission.cache/composed-fingerprint
 [:int {:seon.db/no-history? true}])
(schema/register!
 :seon.runtime.admission.cache/basis-t
 [:int {:seon.db/no-history? true}])
(schema/register!
 :seon.runtime.admission.cache/delta
 [:string {:seon.db/no-history? true}])

(def divergence-cache-ref
  [:seon.runtime.admission.cache/id "committed-projection"])

(def ^:private committed-row-query
  '[:find ?identity ?form (pull ?tx ?provenance-pattern)
    :in $ [?e ...] ?identity-attr ?form-attr ?provenance-pattern
    :where
    [?e ?identity-attr ?identity]
    [?e ?form-attr ?form ?tx]])

(defn ^:no-doc committed-projection
  "Build the canonical projection from ordinary acquired rows."
  {:malli/schema
   [:function
    [:=> [:catn [::acquired :map]] :map]
    [:=> [:catn [::acquired :map] [::reusable-projection :map]] :map]]}
  ([acquired]
   (committed-projection acquired {}))
  ([{::keys [schema-rows function-contract-rows function-source-rows
             artifact-exports]}
    reusable-projection]
   (schema/projection-from-rows
    {:seon.schema/schema-rows schema-rows
     :seon.schema/function-contract-rows function-contract-rows
     :seon.schema/function-source-rows function-source-rows
     :seon.schema/artifact-exports
     (or artifact-exports
         (:seon.schema.projection/artifact-exports reusable-projection)
         #{})}
    reusable-projection)))

(defn- acquisition-error!
  [stage value]
  (throw (ex-info "Committed program acquisition failed."
                  {:seon.db/error value
                   :seon.runtime.admission/stage stage
                   :seon.error/kind :core-bug})))

(defn- failed-read?
  [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- ^:async acquire-row-pages!
  [database entity-ids identity-attr form-attr]
  (loop [pending-ids (seq entity-ids)
         rows []]
    (if-let [entity-id (first pending-ids)]
      (let [page-rows
            (await
             (db/query
              {::db/db database
               ::db/query committed-row-query
               ;; One canonical row is the minimum exact page. Forms are
               ;; variable-length strings, so no larger entity-count can
               ;; imply a result-weight bound.
               ::db/args
               [[entity-id] identity-attr form-attr
                schema/asserting-transaction-provenance-pattern]
               ::db/max-result-weight acquisition-page-max-result-weight}))
            _ (when (failed-read? page-rows)
                (acquisition-error! :query page-rows))]
        (recur (next pending-ids) (into rows page-rows)))
      rows)))

(defn- ^:async acquire-identity-stream!
  [database identity-attr form-attr]
  (loop [cursor nil
         rows []]
    (let [page
          (await
           (db/index-page
            (cond-> {::db/db database
                     ::db/index :aevt
                     ::db/components [identity-attr]
                     ::db/direction :forward
                     ::db/limit acquisition-page-size
                     ::db/max-result-weight
                     acquisition-page-max-result-weight}
              cursor (assoc ::db/cursor cursor))))
          _ (when (failed-read? page)
              (acquisition-error! :index-page page))
          entity-ids (mapv first (:datahike.index-page/datoms page))
          page-rows
          (if (seq entity-ids)
            (await (acquire-row-pages!
                    database entity-ids identity-attr form-attr))
            [])
          next-rows (into rows page-rows)
          _ (log/info-console!
             "seon.runtime.admission"
             "committed acquisition page received"
             {:seon.runtime.admission/identity-attribute identity-attr
              :seon.runtime.admission/page-row-count (count page-rows)
              :seon.runtime.admission/acquired-row-count (count next-rows)
              :seon.runtime.admission/page-complete?
              (:datahike.index-page/complete? page)})]
      (if (:datahike.index-page/complete? page)
        next-rows
        (recur (:datahike.index-page/cursor page) next-rows)))))

(defn ^:async ^:private acquire-committed-projection!
  []
  (let [database (await (db/db))
        _ (when (failed-read? database)
            (acquisition-error! :database database))
        schemas
        (await
         (acquire-identity-stream!
          database :seon.schema/key :seon.schema/form))
        contracts
        (await
         (acquire-identity-stream!
          database :seon.fn/sym :seon.fn/spec))
        sources
        (await
         (acquire-identity-stream!
          database :seon.fn/sym :seon.fn/source))]
    {::db/db database
     ::schema-rows schemas
     ::function-contract-rows contracts
     ::function-source-rows sources}))

(defn divergence-cache-row
  "Return the one no-history cache row for a composed projection delta."
  {:malli/schema
   [:function
    [:=> [:cat :map :map :int] :map]
    [:=> [:cat :map :map :int :int] :map]]}
  ([base delta basis-t]
   (divergence-cache-row
    base delta
    (:seon.schema.projection/fingerprint
     (schema/compose-projection-data base delta))
    basis-t))
  ([base delta composed-fingerprint basis-t]
   {:seon.runtime.admission.cache/id "committed-projection"
    :seon.runtime.admission.cache/base-fingerprint
    (:seon.schema.projection/fingerprint base)
    :seon.runtime.admission.cache/divergence-fingerprint
    (schema/canonical-data-fingerprint delta)
    :seon.runtime.admission.cache/composed-fingerprint composed-fingerprint
    :seon.runtime.admission.cache/basis-t basis-t
    :seon.runtime.admission.cache/delta (pr-str delta)}))

(defn cache-delta
  "Read and verify the complete ordinary divergence value in one cache row."
  [cache]
  (let [encoded (:seon.runtime.admission.cache/delta cache)
        delta (when (string? encoded) (reader/read-string encoded))]
    (when (and
           (map? delta)
           (= (schema/canonical-data-fingerprint delta)
              (:seon.runtime.admission.cache/divergence-fingerprint cache)))
      delta)))

(defn maintain-divergence-cache-row
  "Update the complete overlay only at the identities admitted by this tx."
  [cache candidate changed-schema-keys changed-function-symbols basis-t]
  (let [base @!base-projection
        delta (cache-delta cache)]
    (when-not
     (and base delta
          (= (:seon.schema.projection/fingerprint base)
             (:seon.runtime.admission.cache/base-fingerprint cache)))
      (throw
       (ex-info
        "The divergence cache cannot be maintained from an invalid base key."
        {:seon.runtime.admission/cache cache
         :seon.error/kind :core-bug})))
    (let [maintained
          (schema/maintain-projection-delta
           base delta candidate changed-schema-keys changed-function-symbols)]
      (divergence-cache-row
       base maintained
       (:seon.schema.projection/fingerprint candidate)
       basis-t))))

(defn- verified-cache-composition
  [base cache _database]
  (let [delta (cache-delta cache)
        composed (when (map? delta)
                   (schema/compose-projection-data base delta))]
    (when (and
           (= (:seon.schema.projection/fingerprint base)
              (:seon.runtime.admission.cache/base-fingerprint cache))
           (= (:seon.schema.projection/fingerprint composed)
              (:seon.runtime.admission.cache/composed-fingerprint cache)))
      composed)))

(def ^:private changed-program-entity-query
  '[:find ?e ?attribute
    :in $ [?attribute ...]
    :where
    [?e ?attribute _ _ _]])

(def ^:private changed-program-identity-query
  '[:find ?e ?identity
    :in $ [?e ...] ?identity-attribute
    :where
    [?e ?identity-attribute ?identity _ true]])

(defn- stale-database-failure?
  [value]
  (= db.protocol/stale-database-value-error
     (get-in value
             [:seon.error/data ::db.protocol/error-kind])))

(defn- row-map
  [rows identity-fn value-fn]
  (into {}
        (map
         (fn [[raw-identity raw-value asserting-transaction]]
           [(identity-fn raw-identity)
            {:seon.runtime.admission/value (value-fn raw-value)
             :seon.runtime.admission/admission
             (schema/admission-from-asserting-transaction
              asserting-transaction)}]))
        rows))

(defn- repair-candidate
  [composed schema-identities function-identities acquired]
  (let [schemas (row-map (::schema-rows acquired) keyword
                         reader/read-string)
        contracts (row-map (::function-contract-rows acquired) symbol
                           reader/read-string)
        sources (row-map (::function-source-rows acquired) symbol identity)
        replace-identities
        (fn [values identities rows value-key]
          (reduce
           (fn [result identity]
             (if-let [row (get rows identity)]
               (assoc result identity (get row value-key))
               (dissoc result identity)))
           values
           identities))
        replace-admissions
        (fn [admissions identities rows]
          (reduce
           (fn [result identity]
             (if-let [row (get rows identity)]
               (assoc result identity
                      (:seon.runtime.admission/admission row))
               (dissoc result identity)))
           admissions
           identities))
        forms
        (replace-identities
         (:seon.schema.projection/forms composed)
         schema-identities schemas :seon.runtime.admission/value)
        function-contracts
        (replace-identities
         (:seon.schema.projection/function-contracts composed)
         function-identities contracts :seon.runtime.admission/value)]
    (schema/build-projection
     forms function-contracts
     {:seon.schema/schema-admissions
      (replace-admissions
       (:seon.schema.projection/schema-admissions composed)
       schema-identities schemas)
      :seon.schema/function-admissions
      (replace-admissions
       (:seon.schema.projection/function-admissions composed)
       function-identities contracts)
      :seon.schema/function-source-admissions
      (replace-admissions
       (:seon.schema.projection/function-source-admissions composed)
       function-identities sources)
      :seon.schema/artifact-exports
      (:seon.schema.projection/artifact-exports composed)
      :seon.schema/pure-predicate-symbols
      (:seon.schema.projection/pure-predicate-symbols composed)})))

(defn- ^:async repair-identities-for!
  [database entity-ids identity-attribute]
  (if (seq entity-ids)
    (await
     (db/query
      {::db/db (db/history database)
       ::db/query changed-program-identity-query
       ::db/args [entity-ids identity-attribute]
       ::db/max-result-weight acquisition-page-max-result-weight}))
    []))

(defn- ^:async changed-identities!
  [database basis-t]
  (let [changed
        (await
         (db/query
          {::db/db (db/history (db/since database basis-t))
           ::db/query changed-program-entity-query
           ::db/args
           [[:seon.schema/form :seon.fn/spec :seon.fn/source]]
           ::db/max-result-weight acquisition-page-max-result-weight}))
        _ (when (failed-read? changed)
            (acquisition-error! :divergence-history changed))
        schema-ids
        (into [] (comp (filter #(= :seon.schema/form (second %)))
                       (map first) distinct)
              changed)
        function-ids
        (into [] (comp (filter #(contains? #{:seon.fn/spec :seon.fn/source}
                                            (second %)))
                         (map first) distinct)
              changed)
        schema-identities
        (into #{}
              (map (comp keyword second))
              (await
               (repair-identities-for!
                database schema-ids :seon.schema/key)))
        function-identities
        (into #{}
              (map (comp symbol second))
              (await
               (repair-identities-for!
                database function-ids :seon.fn/sym)))
        acquired
        {::schema-rows
         (await (acquire-row-pages!
                 database schema-ids :seon.schema/key :seon.schema/form))
         ::function-contract-rows
         (await (acquire-row-pages!
                 database function-ids :seon.fn/sym :seon.fn/spec))
         ::function-source-rows
         (await (acquire-row-pages!
                 database function-ids :seon.fn/sym :seon.fn/source))}]
    {::schema-identities schema-identities
     ::function-identities function-identities
     ::acquired acquired
     ::changed-row-count (count changed)}))

(defn- ^:async repair-divergence-cache!
  [base cache]
  (loop []
    (let [database (await (db/db))
          current-cache (await (db/entity database divergence-cache-ref))
          delta (cache-delta current-cache)
          basis-t (:seon.runtime.admission.cache/basis-t current-cache)
          composed
          (when (and delta
                     (= (:seon.schema.projection/fingerprint base)
                        (:seon.runtime.admission.cache/base-fingerprint
                         current-cache)))
            (schema/compose-projection-data base delta))]
      (when-not (and composed (int? basis-t) (< basis-t (:t database)))
        (throw
         (ex-info
          "Only a valid cache whose basis lags the head can be delta-repaired."
          {:seon.runtime.admission/cache current-cache
           :seon.runtime.admission/database-basis (:t database)
           :seon.error/kind :core-bug})))
      (let [{::keys [schema-identities function-identities acquired
                     changed-row-count]}
            (await (changed-identities! database basis-t))
            candidate
            (if (or (seq schema-identities) (seq function-identities))
              (repair-candidate
               composed schema-identities function-identities acquired)
              (schema/materialize-projection composed))
            maintained
            (schema/maintain-projection-delta
             base delta candidate schema-identities function-identities)
            next-basis (inc (:t database))
            row
            (divergence-cache-row
             base maintained
             (:seon.schema.projection/fingerprint candidate)
             next-basis)
            _ (log/error-console!
               "seon.runtime.admission"
               "SEON-CORE-FAULT divergence cache delta-only repair"
               {:seon.runtime.admission/cached-basis basis-t
                :seon.runtime.admission/head-basis (:t database)
                :seon.runtime.admission/changed-row-count changed-row-count})
            report
            (await
             (db/transact!
              {::db/db database
               ::db/expected-db database
               ::db/tx-data [row]}))]
        (if (stale-database-failure? report)
          (recur)
          (do
            (when (failed-read? report)
              (acquisition-error! :divergence-repair report))
            {::db/db (:db-after report)
             ::projection candidate
             ::repaired? true
             ::changed-row-count changed-row-count}))))))

(defn- ^:async acquire-preprocessed-projection!
  "Verify all three cache keys and rematerialize one admitted projection."
  [base artifact-exports]
  (let [database (await (db/db))
        _ (when (failed-read? database)
            (acquisition-error! :database database))
        cache (await (db/entity database divergence-cache-ref))
        _ (when (failed-read? cache)
            (acquisition-error! :divergence-cache cache))
        composed (verified-cache-composition base cache database)
        valid-content?
        (some? composed)
        current?
        (and valid-content?
             (= (:t database)
                (:seon.runtime.admission.cache/basis-t cache)))
        repaired
        (when (and valid-content?
                   (< (:seon.runtime.admission.cache/basis-t cache)
                      (:t database)))
          (await (repair-divergence-cache! base cache)))
        composed (or (::projection repaired) composed)
        database (or (::db/db repaired) database)
        _ (when-not (or current? repaired)
            (throw
             (ex-info
              (str "The committed projection divergence cache does not match "
                   "the verified release and database basis. START refuses "
                   "population derivation; refresh the cache while applying "
                   "the committed change.")
              {:seon.runtime.admission/base-fingerprint
               (:seon.schema.projection/fingerprint base)
               :seon.runtime.admission/database-basis (:t database)
               :seon.runtime.admission/cache cache})))
        process-projection
        (schema/compose-projection-data
         composed
         {:seon.schema.projection/artifact-exports artifact-exports})]
    {::db/db database
     ::projection (schema/materialize-projection process-projection)}))

(defn- ^:async reconcile-committed!
  [old-projection instrument? reusable-projection base-projection
   artifact-exports]
  (let [acquired
        (await
         (acquire-preprocessed-projection!
          base-projection
          (or artifact-exports
              (:seon.schema.projection/artifact-exports reusable-projection)
              (:seon.schema.projection/artifact-exports old-projection)
              #{})))
        projection (::projection acquired)
        _ (log/info-console!
           "seon.runtime.admission"
           "committed projection instrumentation started")
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
    (log/info-console!
     "seon.runtime.admission"
     "committed projection instrumentation completed"
     {:seon.runtime.admission/instrumented-count
      (::instrument/n-instrumented stats)})
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
  [{::keys [record-failures? reusable-projection base-projection
             artifact-exports]
    :or {record-failures? true}
    :as request}]
  (let [_ (when base-projection (reset! !base-projection base-projection))
        base-projection (or base-projection @!base-projection)
        _ (when-not base-projection
            (throw
             (ex-info
              "No verified release base projection is installed."
              {:seon.runtime.admission/stage :base-projection})))
        current @!state
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
                (await
                 (reconcile-committed!
                  old-projection instrument? reusable-projection
                  base-projection artifact-exports))]
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
                    (await
                     (reconcile-committed!
                      old-projection instrument? reusable-projection
                      base-projection artifact-exports))]
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
   [:function
    [:=> [:cat]
     [:map
      [::published? :boolean]
      [::recovered? :boolean]
      [::generation {:optional true} ::generation]
      [::instrumentation {:optional true} :map]
      [:seon/error {:optional true} :map]]]
    [:=> [:cat ::prepare-request]
     [:map
      [::published? :boolean]
      [::recovered? :boolean]
      [::generation {:optional true} ::generation]
      [::instrumentation {:optional true} :map]
      [:seon/error {:optional true} :map]]]]}
  ([]
   (publish-committed! {}))
  ([request]
   (admit-prepared! (await (prepare-committed! request)))))

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
