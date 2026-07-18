(ns seon.db.writer
  "Interpret canonical requests at the authoritative Datahike writer.

   This namespace owns database semantics: connection initialization,
   idempotent writes, generated identities, addressed database interests,
   and embedding search. `seon.db.transport.uds` owns only delivery. Every
   database-scoped request names its database explicitly; there is no ambient
   connection path."
  (:require [clojure.core.async :as async]
            [clojure.core.async.impl.protocols :as async-protocols]
            [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.committed-report :as committed-report]
            [datahike.connector :as datahike.connector]
            [datahike.datom :as datahike.datom]
            [datahike.db.interface :as dbi]
            [datahike.db.utils :as datahike.db]
            [datahike.impl.entity :as datahike.entity]
            [hasch.core :as hasch]
            [seon.db.branch :as branch]
            [seon.db.datahike.schema :as datahike.schema]
            [seon.db.executor :as executor]
            [seon.db.id :as id]
            [seon.db.process :as process]
            [seon.db.program :as program]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.restore-admin :as restore-admin]
            [seon.db.transport.uds :as uds]
            [seon.dev.restore :as restore]
            [seon.launch :as launch]
            [seon.schema :as schema]
            [seon.schema.internal :as schema.internal]
            [taoensso.timbre :as log]))

(set! *warn-on-reflection* true)

;;; Runtime and server resources

(schema/register! ::connection :any)
(schema/register! ::database-initializer 'fn?)
(schema/register! ::embedding-enabled? :boolean)
(schema/register! ::embedding-entity-ids 'fn?)
(schema/register! ::embedding-inputs-for-eids 'fn?)
(schema/register! ::embedding-assertions 'fn?)
(schema/register! ::revalidate-embedding-assertions 'fn?)
(schema/register! ::executor ::executor/executor)
(schema/register! ::query-vec 'fn?)
(schema/register! ::knn 'fn?)
(schema/register! ::active-requests 'some?)
(schema/register! ::query-jobs 'some?)
(schema/register! ::interest-state 'some?)
(schema/register! ::readiness-owner 'some?)
(schema/register! ::transport-connection 'map?)
(schema/register!
 ::dependencies
  [:map
  [::database-initializer ::database-initializer]
  [::embedding-enabled? ::embedding-enabled?]
  [::embedding-entity-ids ::embedding-entity-ids]
  [::embedding-inputs-for-eids ::embedding-inputs-for-eids]
  [::embedding-assertions ::embedding-assertions]
  [::revalidate-embedding-assertions ::revalidate-embedding-assertions]
  [::query-vec ::query-vec]
  [::knn ::knn]])
(schema/register!
 ::runtime
  [:map
  [::database-initializer ::database-initializer]
  [::embedding-enabled? ::embedding-enabled?]
  [::embedding-entity-ids ::embedding-entity-ids]
  [::embedding-inputs-for-eids ::embedding-inputs-for-eids]
  [::embedding-assertions ::embedding-assertions]
  [::revalidate-embedding-assertions ::revalidate-embedding-assertions]
  [::query-vec ::query-vec]
  [::knn ::knn]
  [::executor ::executor]
  [::active-requests {:optional true} ::active-requests]
  [::query-jobs {:optional true} ::query-jobs]
  [::interest-state {:optional true} ::interest-state]
  [::readiness-owner {:optional true} ::readiness-owner]])
(schema/register! ::request-server :seon.db.transport.uds/request-server)
(schema/register! ::database-name :seon.db.protocol/database-name)
(schema/register! ::backend :seon.db.protocol/backend)
(schema/register! ::database-path :seon.db.protocol/database-path)
(schema/register! ::request-socket-path :seon.db.transport.uds/socket-path)
(schema/register! ::selected-processors [:int {:min 1}])
(schema/register!
 ::start-request
 [:map
  [::dependencies ::dependencies]
  [::database-name ::database-name]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]
  [::selected-processors {:optional true} ::selected-processors]
  [::request-socket-path ::request-socket-path]])
(schema/register!
 ::server
 [:map
  [::request-server ::request-server]
  [::executor ::executor]
  [::runtime ::runtime]
  [::database-name ::database-name]])
(schema/register! ::stopped? :boolean)
(schema/register! ::release-result :seon.db.protocol/writer-release-result)
(schema/register! ::release-results :seon.db.protocol/writer-release-results)
(schema/register! ::stop-response :seon.db.protocol/writer-stop-response)
(schema/register! ::database-initialized? :boolean)
(schema/register! ::initialization :seon.db/initialization)
(schema/register! ::initialized-db-value :any)
(schema/register!
 ::initialize-request
 [:map
  [::runtime ::runtime]
  [::registry/conn ::connection]
  [::registry/database-name ::registry/database-name]
  [::registry/connection-id ::branch/connection-id]
  [::registry/open-intent ::registry/open-intent]
  [::initialization {:optional true} ::initialization]])

;;; Datahike values and transaction shapes

(def ^:private schema-properties
  [:db/valueType :db/cardinality :db/unique :db/isComponent])

(def ^:private maximum-schema-reference-count 64)

(def ^:private internal-tempid-prefix "seon.db.protocol.tempid/")

(def ^:private protocol-native-schema
  (datahike.schema/malli-map->datahike-schema
   (into [:map]
         (map (fn [attribute]
                [attribute (get (schema/snapshot) attribute)]))
         (sort protocol/reserved-attributes))))

(defn- basis-t-of
  [db]
  (long (dbi/-max-tx db)))

(defn- datom->protocol
  "Convert a Datahike datom to `[e a v t added?]`."
  [^datahike.datom.Datom datom]
  [(.-e datom)
   (.-a datom)
   (.-v datom)
   (.-tx datom)
   (boolean (:added datom))])

(defn- transaction-data->protocol
  [transaction-data]
  (mapv datom->protocol transaction-data))

(defn- value-type-of
  [installed-schema attribute]
  (when (keyword? attribute)
    (get-in installed-schema [attribute :db/valueType])))

(defn- coerce-value-for-attribute
  [installed-schema attribute value]
  (let [value-type (value-type-of installed-schema attribute)]
    (if (and (#{:db.type/double :db.type/float} value-type)
             (integer? value))
      (double value)
      value)))

(defn- coerce-transaction-data
  "Restore numeric types lost at the JavaScript Transit boundary."
  [installed-schema transaction-data]
  (mapv
   (fn [item]
     (cond
       (map? item)
       (reduce-kv
        (fn [result attribute value]
          (assoc result attribute
                 (coerce-value-for-attribute installed-schema
                                             attribute value)))
        {}
        item)

       (and (vector? item)
            (#{:db/add :db/retract} (first item))
            (= 4 (count item)))
       (let [[operation entity attribute value] item]
         [operation entity attribute
          (coerce-value-for-attribute installed-schema attribute value)])

       :else item))
   transaction-data))

(defn- transaction-attributes
  [transaction-data]
  (into #{}
        (mapcat
         (fn [node]
           (cond
             (map? node)
             (cond-> (filterv qualified-keyword? (keys node))
               (qualified-keyword? (:db/ident node))
               (conj (:db/ident node)))

             (and (vector? node)
                  (#{:db/add :db/retract} (first node))
                  (<= 3 (count node)))
             [(nth node 2)]

             :else [])))
        (tree-seq coll? seq transaction-data)))

(defn- transaction-schema-identifiers
  [transaction-data]
  (into #{}
        (keep (fn [item]
                (when (and (map? item)
                           (qualified-keyword? (:db/ident item)))
                  (:db/ident item))))
        transaction-data))

(defn- schema-row-key
  [item]
  (when (map? item)
    (or (:seon.schema/key item)
        (let [entity (:db/id item)]
          (when (and (sequential? entity)
                     (= 2 (count entity))
                     (= :seon.schema/key (first entity)))
            (second entity))))))

(defn- schema-form-strings
  [transaction-data]
  (into {}
        (keep (fn [item]
                (when-let [attribute (schema-row-key item)]
                  (when (contains? item :seon.schema/form)
                    [attribute (:seon.schema/form item)]))))
        transaction-data))

(defn- generated-schema-attributes
  [transaction-data]
  (into #{}
        (keep (fn [item]
                (when (and (map? item)
                           (contains? item :seon.db.id/generator))
                  (schema-row-key item))))
        transaction-data))

(defn- read-schema-form
  [attribute form-string]
  (when-not (string? form-string)
    (throw
     (ex-info "A canonical schema form must be an EDN string."
              {::attribute attribute
               ::schema-form form-string
               :seon.error/kind :user-input})))
  (try
    (edn/read-string form-string)
    (catch Throwable throwable
      (throw
       (ex-info "A canonical schema form is not readable EDN."
                {::attribute attribute
                 ::schema-form form-string
                 :seon.error/kind :user-input}
                throwable)))))

(defn- stored-schema-form-strings
  [db-value attributes]
  (if (and (seq attributes)
           (contains? (:schema db-value) :seon.schema/key)
           (contains? (:schema db-value) :seon.schema/form))
    (into {}
          (d/q '[:find ?attribute ?form
                 :in $ [?attribute ...]
                 :where
                 [?schema :seon.schema/key ?attribute]
                 [?schema :seon.schema/form ?form]]
               db-value attributes))
    {}))

(defn- schema-form-references
  [form]
  (letfn [(references [value]
            (cond
              (qualified-keyword? value)
              #{value}

              (vector? value)
              (let [[schema-type & children] value
                    children (if (map? (first children))
                               (rest children)
                               children)]
                (case schema-type
                  (:enum := :not= :re :fn) #{}
                  :map (into #{} (mapcat #(references (last %))) children)
                  (into #{} (mapcat references) children)))

              :else #{}))]
    (references form)))

(defn- canonical-schema-forms
  [db-value transaction-data candidates]
  (let [transaction-forms (schema-form-strings transaction-data)]
    (loop [pending candidates
           attempted #{}
           forms {}
           reference-count 0]
      (if (empty? pending)
        forms
        (let [same-transaction (select-keys transaction-forms pending)
              stored
              (stored-schema-form-strings
               db-value (set/difference pending (set (keys same-transaction))))
              parsed
              (into {}
                    (map (fn [[attribute form-string]]
                           [attribute
                            (read-schema-form attribute form-string)]))
                    (merge stored same-transaction))
              attempted (into attempted pending)
              dependencies
              (into #{}
                    (comp (mapcat schema-form-references)
                          (remove attempted))
                    (vals parsed))
              reference-count (+ reference-count (count dependencies))]
          (when (> reference-count maximum-schema-reference-count)
            (throw
             (ex-info
              "A canonical schema form references too many other schema forms."
              {::schema-reference-count reference-count
               ::maximum-schema-reference-count
               maximum-schema-reference-count
               :seon.error/kind :user-input})))
          (recur dependencies attempted (into forms parsed)
                 reference-count))))))

(defn- schema-shape
  [declaration]
  (select-keys declaration schema-properties))

(defn- compile-schema-declarations
  [db-value transaction-data candidates]
  (let [installed (:schema db-value)
        forms (canonical-schema-forms db-value transaction-data candidates)
            unresolved (vec (sort-by str (remove #(contains? forms %)
                                                  candidates)))
            _ (when (seq unresolved)
                (throw
                 (ex-info "A transaction attribute has no canonical schema form."
                          {::attributes unresolved
                           :seon.error/kind :user-input})))
            affected (sort-by str candidates)
            declarations
            (mapv
             (fn [attribute]
               (try
                 (datahike.schema/malli-form->datahike-attribute
                  forms attribute (get forms attribute))
                 (catch Throwable throwable
                   (throw
                    (ex-info "A canonical schema form cannot be stored by Datahike."
                             {::attribute attribute
                              ::schema-form (get forms attribute)
                              :seon.error/kind :user-input}
                             throwable)))))
             affected)
            incompatible
            (into []
                  (keep
                   (fn [{:db/keys [ident] :as declaration}]
                     (when-let [actual (get installed ident)]
                       (let [expected (schema-shape declaration)
                             actual (schema-shape actual)]
                         (when (not= expected actual)
                           {::attribute ident
                            ::expected-schema expected
                            ::actual-schema actual})))))
                  declarations)]
        (when (seq incompatible)
          (throw
           (ex-info "A canonical schema form conflicts with installed schema."
                    {::incompatible-schema incompatible
                     :seon.error/kind :user-input})))
        (into []
              (remove #(contains? installed (:db/ident %)))
              declarations)))

(defn- derive-transaction-schema
  [db-value transaction-data]
  (let [installed (:schema db-value)
        admitted (set (keys (schema-form-strings transaction-data)))
        directly-declared (transaction-schema-identifiers transaction-data)
        missing-used
        (into #{}
              (comp
               (filter qualified-keyword?)
               (remove #(= "db" (namespace %)))
               (remove directly-declared)
               (remove #(contains? installed %)))
              (transaction-attributes transaction-data))
        candidates (set/union missing-used
                              (generated-schema-attributes transaction-data)
                              (set/intersection admitted
                                                (set (keys installed))))]
    (if (empty? candidates)
      []
      (compile-schema-declarations db-value transaction-data candidates))))

(defn- derive-declared-schema
  [db-value transaction-data]
  (let [forms
        (into {}
              (map (fn [[schema-key form-string]]
                     [schema-key (read-schema-form schema-key form-string)]))
              (schema-form-strings transaction-data))
        attributes
        (into #{}
              (comp
               (filter
                (fn [[_schema-key form]]
                  (and (schema.internal/map-shape? form)
                       (:seon.db/entity
                        (schema.internal/schema-properties form)))))
               (mapcat (comp schema.internal/map-entries second))
               (keep (fn [entry]
                       (let [attribute (when (vector? entry) (first entry))]
                         (when (qualified-keyword? attribute) attribute)))))
              forms)]
    (if (empty? attributes)
      []
      (compile-schema-declarations db-value transaction-data attributes))))

(defn- assert-protocol-attributes-free!
  [transaction-data transaction-meta]
  (let [used (into (set (keys (or transaction-meta {})))
                   (transaction-attributes transaction-data))
        reserved (vec (sort-by str
                               (filter used protocol/reserved-attributes)))]
    (when (seq reserved)
      (throw
       (ex-info "Transaction data may not assert protocol attributes."
                {::failure-kind protocol/protocol-error
                 ::reserved-attributes reserved})))))

(defn- internal-tempid?
  [value]
  (and (string? value)
       (str/starts-with? value internal-tempid-prefix)))

(defn- public-transaction-datoms
  [datoms]
  (filterv
   (fn [^datahike.datom.Datom datom]
     (not (contains? protocol/reserved-attributes (.-a datom))))
   datoms))

(defn- public-transaction-meta
  "Remove writer receipt facts from caller-visible transaction metadata."
  [transaction-meta]
  (not-empty
   (apply dissoc (or transaction-meta {}) protocol/reserved-attributes)))

(defn- assert-protocol-native-schema!
  [db-value]
  (let [installed (:schema db-value)
        incompatible
        (keep
         (fn [{:db/keys [ident] :as declaration}]
           (when-let [actual (get installed ident)]
             (let [expected (select-keys declaration schema-properties)
                   actual (select-keys actual schema-properties)]
               (when (not= expected actual)
                 {::attribute ident
                  ::expected-schema expected
                  ::actual-schema actual}))))
         protocol-native-schema)
        missing (filterv #(not (contains? installed (:db/ident %)))
                         protocol-native-schema)]
    (when (seq incompatible)
      (throw
       (ex-info "Database protocol receipt schema is incompatible."
                {::failure-kind protocol/protocol-error
                 ::incompatible-schema (vec incompatible)})))
    (when (seq missing)
      (throw
       (ex-info "Database protocol receipt schema is missing."
                {::failure-kind protocol/protocol-error
                 ::missing-schema (mapv :db/ident missing)})))))

(defn- assert-declared-secondary-indices-live!
  [db-value]
  (let [declared (into #{}
                       (keep (fn [[ident entry]]
                               (when (and (keyword? ident)
                                          (map? entry)
                                          (:db.secondary/type entry))
                                 ident)))
                       (:schema db-value))
        live (set (keys (:secondary-indices db-value)))
        missing (vec (sort-by str (set/difference declared live)))]
    (when (seq missing)
      (throw
       (ex-info "A declared secondary index did not restore on branch open."
                {::failure-kind protocol/protocol-error
                 ::missing-secondary-indices missing})))))

(defn validate-observational-db!
  "Validate protocol schema and declared secondary availability without effects."
  {:malli/schema [:=> [:cat :any] :boolean]}
  [db-value]
  (assert-protocol-native-schema! db-value)
  (assert-declared-secondary-indices-live! db-value)
  true)

(defn- bounded-admin-error [throwable]
  (let [message (.toString ^Throwable throwable)]
    (subs message 0 (min 4096 (count message)))))

(defn- restore-error-kind [throwable]
  (let [kind (:seon.error/kind (ex-data throwable))]
    (if (or (contains? protocol/lifecycle-error-kinds kind)
            (#{protocol/protocol-error protocol/database-error
               protocol/internal-error protocol/not-found-error}
             kind))
      kind
      protocol/database-error)))

(defn admin-restore!
  "Run one no-listener restore transition from a validated immutable intent."
  {:malli/schema [:=> [:cat ::restore-admin/request] ::restore-admin/result]}
  [{intent ::restore-admin/intent}]
  (let [intent (restore/validate-intent intent)
        base (restore-admin/result-base intent)
        main-database
        (get-in intent
                [::restore/pre-restore-main-descriptor ::launch/database])]
    (try
      (let [result
            (registry/admin-restore-main!
             (cond->
               {::registry/database-name
                (keyword (::protocol/database-name main-database))
                ::registry/backend (::protocol/backend main-database)
                ::registry/pre-restore-main-branch-head
                (::restore-admin/pre-restore-main-branch-head base)
                ::registry/selected-target-branch-head
                (::restore-admin/selected-target-branch-head base)
                ::registry/prepared-target-branch-head
                (::restore/prepared-target-branch-head intent)
                ::registry/undo-branch-head (::restore/undo-branch-head intent)
                ::registry/expected-branch-roster
                (::restore/expected-branch-roster intent)
                ::registry/validate-db! validate-observational-db!}
               (::protocol/database-path main-database)
               (assoc ::registry/path
                      (::protocol/database-path main-database))))
            outcome
            (case (::registry/admin-outcome result)
              :seon.db.registry.admin/applied
              :seon.db.restore-admin.outcome/applied

              :seon.db.registry.admin/already-applied
              :seon.db.restore-admin.outcome/already-applied)]
        (merge base
               {::restore-admin/outcome outcome
                ::restore-admin/forced-main-branch-head
                (::registry/branch-head result)
                ::restore-admin/branch-roster
                (::registry/branch-roster result)
                ::restore-admin/force-invoked?
                (::registry/force-invoked? result)
                ::restore-admin/connection-state
                (::registry/admin-connection-state result)}))
      (catch Throwable throwable
        (let [data (ex-data throwable)]
          (if-let [connection-state (::registry/admin-connection-state data)]
            (cond->
              (merge base
                     {::restore-admin/error-kind
                      (restore-error-kind throwable)
                      ::restore-admin/error (bounded-admin-error throwable)
                      ::restore-admin/force-invoked?
                      (boolean (::registry/force-invoked? data))
                      ::restore-admin/connection-state connection-state})
              (::registry/branch-roster data)
              (assoc ::restore-admin/branch-roster
                     (::registry/branch-roster data)))
            (merge
             base
             {::restore-admin/error-kind protocol/internal-error
              ::restore-admin/error (bounded-admin-error throwable)
              ::restore-admin/effect-state
              :seon.db.restore-admin.effect/unknown
              ::restore-admin/connection-state
              :seon.db.restore-admin.connection/cleanup-unproved})))))))

;;; Transaction reports

(declare database-value recovered-temporary-ids)

(defn- transaction-report-data
  [database-name db-before-descriptor report request-id]
  (let [db-after (:db-after report)
        db-before (:db-before report)
        transaction-data (public-transaction-datoms (:tx-data report))]
    {:db-before (or db-before-descriptor
                    (database-value database-name db-before))
     :db-after (database-value database-name db-after)
     :tx-data (transaction-data->protocol transaction-data)
     :tempids
     (merge
      (into {}
            (remove
             (fn [[tempid _entity]]
               (or (= :db/current-tx tempid)
                   (internal-tempid? tempid))))
            (:tempids report))
      (recovered-temporary-ids db-after (basis-t-of db-after)))
     :tx-meta (public-transaction-meta (:tx-meta report))
     ::protocol/request-id request-id}))

(declare initialize-program!)

(defn initialize-connection!
  "Initialize one database connection from the composed writer runtime."
  {:malli/schema [:=> [:cat ::initialize-request]
                  [:map
                   [::database-initialized? ::database-initialized?]
                   [::initialized-db-value {:optional true}
                    ::initialized-db-value]]]}
  [{::keys [runtime initialization]
    connection ::registry/conn
    database-name ::registry/database-name
    connection-id ::registry/connection-id
    open-intent ::registry/open-intent}]
  (assert-protocol-native-schema! (d/db connection))
  (when (= :seon.db.registry.open/branch open-intent)
    (assert-declared-secondary-indices-live! (d/db connection))
    (when initialization
      (throw
       (ex-info "A branch database cannot initialize a compiled program."
                {::failure-kind protocol/protocol-error
                 ::registry/database-name database-name
                 ::registry/connection-id connection-id}))))
  (when (= :seon.db.registry.open/main open-intent)
    (when initialization
      (initialize-program! runtime connection (name database-name) connection-id
                           initialization))
    ((::database-initializer runtime) connection database-name))
  (cond-> {::database-initialized? true}
    initialization (assoc ::initialized-db-value (d/db connection))))

(defn- committed-scope
  [database-name connection-id db-value]
  (when-let [identity (d/committed-value-identity db-value)]
    {::executor/database-name database-name
     ::branch/connection-id connection-id
     ::executor/connection-id (:datahike.value/connection-id identity)
     ::executor/generation (:datahike.value/generation identity)}))

(defn- database-value
  "Describe one raw committed Datahike value as ordinary transport data."
  [database-name db-value]
  (let [identity (d/committed-value-identity db-value)]
    {:db-name database-name
     :store-id (:datahike.value/connection-id identity)
     :t (basis-t-of db-value)
     :as-of nil
     :since nil
     :history false
     :datahike/commit-id (d/commit-id db-value)}))

(def ^:private byte-array-class (Class/forName "[B"))

(defn- forbidden-host-value?
  [value]
  (or (datahike.db/db? value)
      (datahike.connector/connection? value)
      (datahike.datom/datom? value)
      (datahike.entity/entity? value)
      (fn? value)
      (instance? clojure.lang.IDeref value)
      (instance? java.lang.Thread value)
      (instance? java.util.concurrent.Future value)
      (instance? Throwable value)))

(defn- ordinary-data?
  [allow-list? value]
  (cond
    (or (forbidden-host-value? value) (record? value)) false
    (map? value) (and (every? #(ordinary-data? allow-list? %) (keys value))
                      (every? #(ordinary-data? allow-list? %) (vals value)))
    (vector? value) (every? #(ordinary-data? allow-list? %) value)
    (set? value) (every? #(ordinary-data? allow-list? %) value)
    (list? value) (and allow-list?
                       (every? #(ordinary-data? allow-list? %) value))
    (sequential? value) false
    :else
    (or (nil? value) (boolean? value) (number? value) (string? value)
        (keyword? value) (symbol? value) (uuid? value) (inst? value)
        (instance? java.net.URI value)
        (instance? byte-array-class value))))

(defn- materialize-result
  "Validate an eager database result without copying its persistent shape."
  [value]
  (when-not (ordinary-data? false value)
    (throw
     (ex-info "A database result contained a host-owned or lazy value."
              {::failure-kind protocol/protocol-error
               ::host-value-class (some-> value class str)})))
  value)

(defn- validate-read-input!
  [request]
  (let [values
        (case (::protocol/operation request)
          :seon.db.protocol.operation/query
          [(::protocol/query-form request) (::protocol/arguments request)]

          :seon.db.protocol.operation/pull
          [(::protocol/selector request) (::protocol/entity-id request)]

          :seon.db.protocol.operation/pull-many
          [(::protocol/selector request) (::protocol/entity-ids request)]

          :seon.db.protocol.operation/schema
          []

          :seon.db.protocol.operation/index-page
          [(::protocol/prefix request) (::protocol/cursor request)])]
    (when-not (every? #(ordinary-data? true %) values)
      (throw
       (ex-info "A database read request contained a host-owned or lazy value."
                {::failure-kind protocol/protocol-error})))
    request))

(defn- query-plan
  "Align query inputs and identify only Datahike source argument positions."
  [request]
  (let [query (::protocol/query-form request)
        supplied (vec (::protocol/arguments request))
        input-count (d/query-input-count query)
        sources (d/query-source-bindings query)
        default-sources
        (filterv #(= '$ (:datahike.query.source/symbol %)) sources)
        implicit-source?
        (and (= input-count (inc (count supplied)))
             (= 1 (count default-sources))
             (some? (:seon.db/db request)))
        arguments
        (cond
          (= input-count (count supplied)) supplied
          implicit-source?
          (let [position (:datahike.query.source/argument-position
                          (first default-sources))]
            (into (conj (subvec supplied 0 position) (:seon.db/db request))
                  (subvec supplied position)))
          :else
          (throw
           (ex-info "The query arguments do not match its parsed :in inputs."
                    {::failure-kind protocol/protocol-error
                     ::protocol/expected-count input-count
                     ::protocol/actual-count (count supplied)})))
        source-descriptors
        (into []
              (keep
               (fn [{:datahike.query.source/keys [argument-position] :as source}]
                 (let [value (nth arguments argument-position)]
                   (when (protocol/database-value? value)
                     (assoc source ::database-descriptor value)))))
              sources)]
    {::query-arguments arguments
     ::query-sources source-descriptors
     ::routing-descriptors (mapv ::database-descriptor source-descriptors)}))

(defn- read-plan
  "Describe the exact database values used by one ordinary read."
  [request]
  (if (= protocol/query-operation (::protocol/operation request))
    (query-plan request)
    {::routing-descriptors [(:seon.db/db request)]}))

(defn- resource-options
  [request]
  (cond-> {}
    (:datahike.resource/max-work request)
    (assoc :max-work (:datahike.resource/max-work request))
    (:datahike.resource/max-results request)
    (assoc :max-results (:datahike.resource/max-results request))
    (:datahike.resource/max-result-weight request)
    (assoc :max-result-weight
           (:datahike.resource/max-result-weight request))))

(defn- read-response-base
  [request]
  {::protocol/request-id (::protocol/request-id request)})

(def ^:private read-operations
  #{protocol/query-operation
    protocol/pull-operation
    protocol/pull-many-operation
    protocol/schema-operation
    protocol/index-page-operation})

(defn- index-page
  [db-value request]
  (let [index (::protocol/index request)
        direction (::protocol/direction request)
        page
        (try
          (d/index-page
           db-value
           (cond->
             {:index index
              :components (::protocol/prefix request)
              :direction direction
              :limit (::protocol/limit request)}
             (::protocol/cursor request)
             (assoc :cursor (::protocol/cursor request))
             (:datahike.resource/max-result-weight request)
             (assoc :max-result-weight
                    (:datahike.resource/max-result-weight request))))
          (catch clojure.lang.ExceptionInfo throwable
            (if (= :datahike.index-page/invalid-request
                   (:type (ex-data throwable)))
              (throw
               (ex-info (.getMessage throwable)
                        (assoc (ex-data throwable)
                               ::failure-kind protocol/protocol-error)
                        throwable))
              (throw throwable))))]
    (cond->
      {:datahike.index-page/datoms
       (mapv datom->protocol (:datahike.index-page/datoms page))
       :datahike.index-page/complete?
       (:datahike.index-page/complete? page)}
      (:datahike.index-page/cursor page)
      (assoc :datahike.index-page/cursor
             (:datahike.index-page/cursor page)))))

(defn- datom-map
  "Describe a committed datom for the existing selective-interest matcher."
  [^datahike.datom.Datom datom]
  {:seon.db/e (.-e datom)
   :seon.db/a (.-a datom)
   :seon.db/v (.-v datom)
   :seon.db/tx (datahike.datom/datom-tx datom)
   :seon.db/added? (boolean (:added datom))})

(defn- execute-db-read
  [db-value request]
  (let [options (resource-options request)]
    (case (::protocol/operation request)
      :seon.db.protocol.operation/pull
      {::protocol/result
       (materialize-result
        (d/pull db-value
                (merge {:selector (::protocol/selector request)
                        :eid (::protocol/entity-id request)}
                       options)))}

      :seon.db.protocol.operation/pull-many
      {::protocol/result
       (materialize-result
        (d/pull-many db-value
                     (merge {:selector (::protocol/selector request)
                             :eids (::protocol/entity-ids request)}
                            options)))}

      :seon.db.protocol.operation/schema
      {::protocol/schema (materialize-result (d/schema db-value))}

      :seon.db.protocol.operation/index-page
      (materialize-result (index-page db-value request)))))

(defn- query-arguments
  [arguments request caller-id]
  (merge
   {:query (::protocol/query-form request)
    :args arguments
    :request-id caller-id}
   (resource-options request)))

(declare request-failure-response)

(defn- query-response
  [request {:keys [status value throwable]}]
  (if (= :ok status)
    (merge (read-response-base request)
           (protocol/success
            (update value :datahike.query/result materialize-result)))
    (request-failure-response throwable)))

(declare acquire-query-call!
         resolve-database-value!
         resolve-execute-many-plan!
         release-resolved-database-values!)

(defn- execute-read!
  [runtime {request ::request :as work}]
  (cond
    (::run-query-call? work) (d/run-q! (::query-call work))
    (::acquire-query? work) (acquire-query-call! runtime work)
    (::resolve-execute-many? work)
    (resolve-execute-many-plan! (::transport-connection work)
                                (::execute-many-plan work))
    (::transport-connection work)
    (let [resolved
          (resolve-database-value! (::transport-connection work)
                                   (:seon.db/db request)
                                   false)]
      (try
        (merge (read-response-base request)
               (execute-db-read (::database-value resolved) request))
        (finally
          (release-resolved-database-values! [resolved]))))
    (::database-value work)
    (protocol/success
     (execute-db-read (::database-value work) request))
    :else
    (throw
     (ex-info "The database read reached execution without a resolved value."
              {::failure-kind protocol/internal-error
               ::protocol/request-id (::protocol/request-id request)}))))

(declare connection-for-request)

(defn- member-failure
  [throwable]
  (let [kind (:seon.error/kind (ex-data throwable))]
    (protocol/failure
     (cond->
      {::protocol/error-kind
       (or (::failure-kind (ex-data throwable)) protocol/database-error)
       ::protocol/error (or (.getMessage ^Throwable throwable)
                            "The database read failed.")}
       kind (assoc :seon.error/kind kind)))))

(declare cancel-query-caller!)

(defn- handle-cancel
  [runtime request target]
  (let [target-request-id (::protocol/target-request-id request)
        dispatcher (::executor runtime)
        jobs (::jobs target)
        query-callers (::query-callers target)
        query-cancellations
        (mapv #(cancel-query-caller! runtime %) (vals query-callers))
        executor-cancellations
        (mapv
         (fn [job-id]
           (if (contains? query-callers job-id)
             nil
             (let [query-job?
                   (or (and (not (::execute-many? target))
                            (= protocol/query-operation
                               (::protocol/operation (::request target))))
                       (and (::execute-many? target)
                            (int? (second job-id))
                            (= protocol/query-operation
                               (::protocol/operation
                                (nth (::protocol/members (::request target))
                                     (second job-id))))))]
               ((if query-job? executor/cancel-queued! executor/cancel!)
                {::executor/executor dispatcher ::executor/job-id job-id}))))
         jobs)
        cancellations (keep ::executor/cancellation executor-cancellations)]
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/target-request-id target-request-id
      ::protocol/canceled?
      (boolean
       (or (some #{:queued :running} cancellations)
           (some :datahike.query.cancel/detached? query-cancellations)))
      ::protocol/running?
      (boolean
       (or (some #{:running} cancellations)
           (some #(and (:datahike.query.cancel/last-waiter? %)
                       (not (:datahike.query.cancel/unstarted-owner? %)))
                 query-cancellations)))})))

(defn- resolve-exact-connection
  [{::keys [database-name scope]}]
  (let [{::registry/keys [conn connection-id]}
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})]
    (when (and conn
               (= scope (committed-scope database-name connection-id (d/db conn))))
      conn)))

(defn- execute-embedding!
  [dependencies {::keys [database-name scope entity-ids]}]
  (try
    (when-let [connection (resolve-exact-connection
                           {::database-name database-name ::scope scope})]
      (let [inputs
            ((::embedding-inputs-for-eids dependencies)
             (d/db connection) entity-ids)]
        (when (seq inputs)
          (let [assertions ((::embedding-assertions dependencies) inputs)]
            (when-let [current-connection
                       (resolve-exact-connection
                        {::database-name database-name ::scope scope})]
              (let [current-assertions
                    ((::revalidate-embedding-assertions dependencies)
                     (d/db current-connection) assertions)]
                (when (seq current-assertions)
                  ;; Datahike writer admission is the release fence. A write on
                  ;; this captured generation is accepted and drained before
                  ;; release returns, or rejected after admission closes.
                  (d/transact current-connection current-assertions))))))))
    (catch Throwable throwable
      (log/warn throwable "asynchronous embedding update failed"
                {::database-name database-name}))))

(defn- embedding-entity-ids
  [report]
  (into []
        (comp (map (fn [^datahike.datom.Datom datom] (.-e datom)))
              (distinct))
        (public-transaction-datoms (:tx-data report))))

(defn- submit-embedding!
  [runtime database-name scope request-id entity-ids]
  (when (and (::embedding-enabled? runtime)
             (::executor runtime)
             scope
             (seq entity-ids))
    (executor/try-submit!
     {::executor/executor (::executor runtime)
      ::executor/work-class :provider
      ::executor/database-name database-name
      ::executor/scope scope
      ::executor/job-id [request-id :embedding]
      ::executor/request {::database-name database-name
                          ::scope scope
                          ::entity-ids entity-ids}})))

(defn- enqueue-embedding!
  [runtime database-name connection-id request-id report]
  (try
    (let [db-after (:db-after report)
          scope (committed-scope database-name connection-id db-after)
          entity-ids (embedding-entity-ids report)]
      (submit-embedding! runtime database-name scope request-id entity-ids))
    (catch Throwable throwable
      ;; Derived work admission can be repaired from current hash mismatch and
      ;; therefore must never change the primary transaction result.
      (log/warn throwable "asynchronous embedding admission failed"
                {::database-name database-name
                 ::protocol/request-id request-id}))))

(defn- enqueue-embedding-backfill!
  [runtime database-name connection-id connection]
  (try
    (let [db-value (d/db connection)
          scope (committed-scope database-name connection-id db-value)
          entity-ids ((::embedding-entity-ids runtime) db-value)]
      (doseq [[batch-index batch] (map-indexed vector (partition-all 256 entity-ids))]
        (submit-embedding!
         runtime database-name scope
         (str "embedding/backfill/" (::executor/generation scope)
              "/" batch-index)
         (vec batch))))
    (catch Throwable throwable
      (log/warn throwable "asynchronous embedding backfill admission failed"
                {::database-name database-name}))))

(defn- response-from-report-data
  [data]
  (cond->
   {::protocol/request-id (::protocol/request-id data)
    :db-before (:db-before data)
    :db-after (:db-after data)
    :tx-data (:tx-data data)
    :tempids (:tempids data)
    :tx-meta (:tx-meta data)}

    (::protocol/recovered? data)
    (assoc ::protocol/recovered? true)))

;;; Durable idempotency recovery

(defn- committed-transaction
  [db request-id]
  (d/q '[:find ?tx .
         :in $ ?request-id
         :where [?tx :seon.db.protocol/request-id ?request-id]]
       db request-id))

(defn- transaction-datoms
  [db transaction]
  (->> (d/datoms (d/since (d/history db) (dec transaction)) :eavt)
       (filterv
        (fn [^datahike.datom.Datom datom]
          (= transaction (Math/abs (long (.-tx datom))))))))

(defn- recovered-temporary-ids
  [db transaction]
  (into {}
        (map (fn [[key-edn entity]]
               [(edn/read-string key-edn) entity]))
        (d/q '[:find ?key-edn ?entity
               :in $ ?transaction
               :where
               [?marker :seon.db.protocol.tempid/key-edn
                ?key-edn ?transaction]
               [?marker :seon.db.protocol.tempid/entity
                ?entity ?transaction]]
             db transaction)))

(defn- recovered-generated-entity-ids
  [datoms candidates]
  (when (seq candidates)
    (into {}
          (map
           (fn [candidate]
             [(::id/key candidate)
              (some
               (fn [^datahike.datom.Datom datom]
                 (when (and (:added datom)
                            (= (::id/identity-attr candidate) (.-a datom))
                            (= (::id/value candidate) (.-v datom)))
                   (.-e datom)))
               datoms)]))
          candidates)))

(defn- recovered-response
  [db database-name transaction request-id candidates]
  (let [all-datoms (transaction-datoms db transaction)
        datoms (public-transaction-datoms all-datoms)
        stored-transaction-meta
        (into {}
              (map
               (fn [^datahike.datom.Datom datom]
                 [(.-a datom) (.-v datom)]))
              (filter
               (fn [^datahike.datom.Datom datom]
                 (= transaction (.-e datom)))
               all-datoms))
        transaction-meta (public-transaction-meta stored-transaction-meta)
        current (database-value database-name db)
        response
        (response-from-report-data
         {::protocol/request-id request-id
          :db-before (assoc current :as-of (dec transaction))
          :db-after (if (= transaction (basis-t-of db))
                      current
                      (assoc current :as-of transaction))
          :tempids (recovered-temporary-ids db transaction)
          :tx-data (transaction-data->protocol datoms)
          :tx-meta transaction-meta
          ::protocol/recovered? true})
        generated-entity-ids
        (recovered-generated-entity-ids datoms candidates)]
    (cond-> response
      (seq generated-entity-ids)
      (assoc ::protocol/generated-entity-ids generated-entity-ids))))

(defn- request-conflict
  [request-id expected-hash actual-hash]
  (ex-info "A request id was reused for different transaction data."
           {::failure-kind protocol/request-conflict-error
            ::protocol/request-id request-id
            ::expected-request-hash expected-hash
            ::actual-request-hash actual-hash}))

(defn- recover-committed
  [db database-name transaction request-id expected-hash candidates]
  (let [actual-hash (::protocol/request-hash (d/entity db transaction))]
    (if (= expected-hash actual-hash)
      (recovered-response db database-name transaction request-id candidates)
      (throw (request-conflict request-id expected-hash actual-hash)))))

(defn- recover-current
  [connection database-name request-id fingerprint candidates]
  (let [db-value (d/db connection)]
    (when-let [transaction (committed-transaction db-value request-id)]
      (recover-committed db-value database-name transaction request-id fingerprint
                         candidates))))

(defn- assert-current-database-value!
  [database-name db-value expected-db]
  (let [current-db (database-value database-name db-value)]
    (when (and expected-db (not= expected-db current-db))
      (throw
       (ex-info "The database changed before commit."
                {::failure-kind protocol/stale-database-value-error
                 :seon.db/expected-db expected-db
                 :seon.db/current-db current-db})))))

(defn- prepare-transaction!
  [runtime connection database-name connection-id request]
  (let [transaction-data (::protocol/transaction-data request)
        transaction-meta (::protocol/transaction-meta request)
        expected-db (:seon.db/expected-db request)
        request-id (::protocol/request-id request)
        candidates (::protocol/generated-candidates request)
        generated? (contains? request ::protocol/generated-candidates)
        fingerprint (protocol/logical-transaction-hash request)
        _ (assert-protocol-attributes-free! transaction-data transaction-meta)]
    (locking connection
      (if-let [response
               (recover-current connection database-name request-id fingerprint
                                candidates)]
        {::response response}
        (let [db-value (d/db connection)
              _ (assert-current-database-value! database-name db-value expected-db)
              schema-declarations
              (derive-transaction-schema db-value transaction-data)
              effective-schema
              (merge (into {}
                           (map (juxt :db/ident identity))
                           schema-declarations)
                     (:schema db-value))
              coerced-data
              (coerce-transaction-data effective-schema transaction-data)
              augmented-data
              (into (vec schema-declarations) coerced-data)
              caller-tempids
              (id/transaction-tempids
               {::id/db-value db-value
                ::id/transaction-data augmented-data})
              data-with-receipts
              (into augmented-data
                    (protocol/tempid-receipts request-id caller-tempids))
              transaction-meta*
              (assoc (or transaction-meta {})
                     ::protocol/request-id request-id
                     ::protocol/request-hash fingerprint
                     ::protocol/version protocol/current-version)
              transaction
              (cond-> {:tx-data data-with-receipts
                       :tx-meta transaction-meta*}
                expected-db
                (assoc :datahike/expected-basis-t
                       (:t expected-db))
                generated?
                (assoc ::id/generated-candidates candidates))]
          {::transaction-result (d/transact! connection transaction)
           ::database-before (database-value database-name db-value)
           ::request-id request-id
           ::fingerprint fingerprint
           ::candidates candidates
           ::database-name database-name
           ::branch/connection-id connection-id
           :seon.db/expected-db expected-db
           ::connection connection
           ::runtime runtime})))))

(defn- serialized-transaction-error
  [database-name connection expected-db ^Throwable throwable]
  (if (= :transaction/stale-basis (:error (ex-data throwable)))
    (ex-info "The database changed before commit."
             {::failure-kind protocol/stale-database-value-error
              :seon.db/expected-db expected-db
              :seon.db/current-db
              (database-value database-name (d/db connection))}
             throwable)
    throwable))

(defn- finish-transaction!
  [{::keys [runtime connection database-name request-id fingerprint candidates]
    connection-id ::branch/connection-id
    db-before-descriptor ::database-before
    expected-db :seon.db/expected-db}
   result]
  (if (instance? Throwable result)
    ;; A commit can win before the acknowledgement is lost. The durable
    ;; receipt, not the delivery failure, is authoritative.
    (if-let [response
             (recover-current connection database-name request-id fingerprint
                              candidates)]
      response
      (throw
       (serialized-transaction-error database-name connection expected-db result)))
    (let [response
          (response-from-report-data
           (transaction-report-data database-name db-before-descriptor result
                                    request-id))
          generated-entity-ids (::id/generated-eids result)]
      (enqueue-embedding! runtime database-name connection-id request-id result)
      (cond-> response
        (some? generated-entity-ids)
        (assoc ::protocol/generated-entity-ids generated-entity-ids)))))

(defn- transact-once!
  [runtime connection database-name connection-id request]
  (let [{::keys [response transaction-result] :as prepared}
        (prepare-transaction! runtime connection database-name connection-id request)]
    (if response
      response
      (finish-transaction!
       prepared
       (try
         @transaction-result
         (catch Throwable throwable throwable))))))

(defn- transact-once-async!
  [runtime connection database-name connection-id request]
  (let [{::keys [response transaction-result] :as prepared}
        (prepare-transaction! runtime connection database-name connection-id request)]
    (if response
      response
      (let [completion (async/promise-chan)]
        (async/take!
         transaction-result
         (fn [result]
           (async/put!
            completion
            (try
              (finish-transaction!
               prepared
               (or result
                   (ex-info "Datahike transaction completion closed."
                            {::protocol/request-id
                             (::protocol/request-id request)})))
              (catch Throwable throwable throwable)))))
        completion))))

(def ^:private maximum-program-initialization-attempts 3)

(def ^:private genesis-attributes
  #{:seon.agent/id
    :seon.db.process/id
    :seon.db/user
    :seon.db/process})

(defn- present-root?
  [db-value]
  (boolean
   (d/q '[:find ?root .
          :where
          [?root :seon.agent/id "root"]]
        db-value)))

(defn- present-process-ids
  [db-value]
  (set
   (d/q '[:find [?id ...]
          :where
          [_ :seon.db.process/id ?id]]
        db-value)))

(defn- genesis-data
  "Derive the native schema and missing provenance identities required before
   the compiled program can transact with root/boot metadata."
  [db-value desired-program]
  (let [processes (process/genesis-entities)
        schema-declarations
        (compile-schema-declarations db-value desired-program
                                     genesis-attributes)
        installed-processes (present-process-ids db-value)]
    (cond-> (vec schema-declarations)
      (not (present-root? db-value))
      (conj {:seon.agent/id "root"})

      true
      (into (remove #(contains? installed-processes
                                (:seon.db.process/id %)))
            processes))))

(defn- identity-attribute
  [db-value declarations row]
  (let [schema
        (into (:schema db-value)
              (map (juxt :db/ident identity))
              declarations)
        attributes
        (into []
              (filter #(= :db.unique/identity
                          (get-in schema [% :db/unique])))
              (keys row))]
    (when-not (= 1 (count attributes))
      (throw
       (ex-info "Initial database data must carry one identity attribute."
                {::initial-data-row row
                 ::identity-attributes attributes
                 :seon.error/kind :core-bug})))
    (first attributes)))

(defn- missing-initial-data
  [db-value declarations initial-data]
  (into []
        (remove
         (fn [row]
           (let [attribute (identity-attribute db-value declarations row)]
             (and (contains? (:schema db-value) attribute)
                  (some? (d/entity db-value
                                   [attribute (get row attribute)]))))))
        initial-data))

(defn- transact-initialization!
  [runtime connection database-name connection-id db-value transaction-data
   transaction-meta]
  (when (seq transaction-data)
    (transact-once!
     runtime connection database-name connection-id
     (protocol/transaction-request
      (cond-> {::protocol/request-id
               (str "database-initialization/" (random-uuid))
               ::protocol/database-name (name database-name)
               ::protocol/transaction-data transaction-data
               :seon.db/expected-db (database-value (name database-name)
                                                   db-value)}
        transaction-meta
        (assoc ::protocol/transaction-meta transaction-meta))))))

(defn initialize-program!
  "Admit one compiled program and its required initial entities atomically.
   No other write is prepared for this connection during admission."
  [runtime connection database-name connection-id initialization]
  (let [desired-program (:seon.db/program initialization)
        initial-data (:seon.db/initial-data initialization)
        boot-meta {:seon.db/user [:seon.agent/id "root"]
                   :seon.db/process
                   (process/lookup-ref :seon.db.process/boot)}]
    (locking connection
      (loop [attempt 1]
        (let [result
              (try
                (let [before-genesis (d/db connection)
                      genesis (genesis-data before-genesis desired-program)
                      _ (transact-initialization!
                         runtime connection database-name connection-id
                         before-genesis genesis nil)
                      before-program (d/db connection)
                      schema-declarations
                      (derive-declared-schema before-program desired-program)
                      transaction-data
                      (into (vec schema-declarations)
                            (concat
                             (program/compile-tx-data before-program
                                                      desired-program)
                             (missing-initial-data before-program
                                                   schema-declarations
                                                   initial-data)))]
                  (transact-initialization!
                   runtime connection database-name connection-id before-program
                   transaction-data boot-meta)
                  (d/db connection))
                (catch Throwable throwable throwable))]
          (if (and (instance? Throwable result)
                   (= protocol/stale-database-value-error
                      (::failure-kind (ex-data result)))
                   (< attempt maximum-program-initialization-attempts))
            (recur (inc attempt))
            (if (instance? Throwable result)
              (throw result)
              result)))))))

(defn- handle-resolve-transaction-branch-head
  [connection request]
  (protocol/success
   {::protocol/branch-head
    (registry/resolve-transaction-branch-head!
     {::registry/conn connection
      ::registry/main-branch-head (::protocol/containing-branch-head request)
      ::registry/transaction-id (::protocol/transaction-id request)})}))

;;; Canonical operation handlers

(defn- registry-request
  [database-name backend-kind database-path connection-id connection-initializer]
  (cond->
   {::registry/database-name (keyword database-name)
    ::registry/backend backend-kind
    ::registry/initial-tx protocol-native-schema
    ::registry/initialize-connection! connection-initializer}
    connection-id (assoc ::registry/connection-id connection-id)
    database-path (assoc ::registry/path database-path)))

(defn- connection-initializer
  [runtime initialization initialized-db]
  (fn [initialize-request]
    (let [result
          (initialize-connection!
           (cond-> (assoc initialize-request ::runtime runtime)
             initialization (assoc ::initialization initialization)))]
      (when-let [db-value (::initialized-db-value result)]
        (vreset! initialized-db db-value))
      result)))

(defn- handle-ensure-database
  [runtime request]
  (let [database-name (::protocol/database-name request)
        initialization (:seon.db/initialization request)
        initialized-db (volatile! nil)
        entry
        (registry/ensure-database!
         (registry-request
          database-name
          (::protocol/backend request)
          (::protocol/database-path request)
          (::branch/connection-id request)
          (connection-initializer runtime initialization initialized-db)))
        connection-id (::registry/connection-id entry)
        _ (when (and initialization
                     (not= :db (second connection-id)))
            (throw
             (ex-info "A branch database cannot initialize a compiled program."
                      {::failure-kind protocol/protocol-error
                       ::registry/database-name (keyword database-name)
                       ::registry/connection-id connection-id})))
        connection (::registry/conn entry)
        _ (when (and initialization (nil? @initialized-db))
            (vreset! initialized-db
                     (initialize-program! runtime connection
                                          database-name connection-id
                                          initialization)))
        backend-kind (::registry/backend entry)
        database-path (::registry/path entry)]
    (enqueue-embedding-backfill! runtime database-name
                                 connection-id connection)
    (protocol/success
     (cond->
       {::protocol/database-name database-name
        :seon.db/db (database-value database-name
                                    (or @initialized-db (d/db connection)))
        ::protocol/backend backend-kind}
       database-path
       (assoc ::protocol/database-path database-path)))))

(defn- handle-create-branch
  [runtime request]
  (let [result
        (registry/create-branch!
         {::registry/source-database-name
          (keyword (::protocol/source-database-name request))
          ::registry/target-database-name
          (keyword (::protocol/target-database-name request))
          ::registry/source-branch-head (::protocol/source-branch-head request)
          ::registry/expected-source-head
          (::protocol/expected-source-head request)
          ::registry/target-branch (::protocol/target-branch request)
          ::registry/initialize-connection!
          (connection-initializer runtime nil (volatile! nil))})]
    (protocol/success
     (cond-> {::protocol/target-database-name
              (::protocol/target-database-name request)
              ::protocol/target-connection-id (::registry/connection-id result)
              ::protocol/branch-head (::registry/branch-head result)
              ::protocol/backend (::registry/backend result)
              ::protocol/created? (::registry/created? result)
              ::protocol/adopted? (::registry/adopted? result)}
       (::registry/path result)
       (assoc ::protocol/database-path (::registry/path result))))))

(defn- handle-observe-database-lifecycle
  [request]
  (let [database-name (::protocol/database-name request)
        observation
        (registry/observe-database-lifecycle
         {::registry/database-name (keyword database-name)})]
    (protocol/success
     {::protocol/database-name database-name
      ::protocol/main-branch-head (::registry/main-branch-head observation)
      ::protocol/main-parent-commit-ids
      (::registry/main-parent-commit-ids observation)
      ::protocol/branch-heads
      (::registry/branch-branch-heads observation)
      ::protocol/branch-roster (::registry/branch-roster observation)
      ::protocol/restore-completions
      (::registry/restore-completions observation)
      ::protocol/completed-restore-ids
      (::registry/completed-restore-ids observation)
      ::protocol/restore-completion-branch-heads
      (::registry/restore-completion-branch-heads observation)})))

(declare await-active-scope!)

(defn- entry-owns-scope?
  [entry scope]
  (contains? (or (::scopes entry) #{(::scope entry)}) scope))

(defn- database-scope
  [database-name expected-connection-id]
  (let [{::registry/keys [conn connection-id]}
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})]
    (when (and conn (= expected-connection-id connection-id))
      (committed-scope database-name connection-id (d/db conn)))))

(defn- drain-database-scope!
  [runtime scope]
  (when-let [active (::active-requests runtime)]
    (let [caller-ids
          (locking active
            (let [request-ids
                  (into [] (keep (fn [[request-id entry]]
                                   (when (entry-owns-scope? entry scope)
                                     request-id)))
                        @active)]
              (doseq [request-id request-ids]
                (swap! active assoc-in [request-id ::canceled?] true))
              (into [] (mapcat #(vals (get-in @active [% ::query-callers])))
                    request-ids)))]
      (run! #(cancel-query-caller! runtime %) caller-ids)))
  (when-let [dispatcher (::executor runtime)]
    (executor/fence-and-drain!
     {::executor/executor dispatcher
      ::executor/scope scope
      ::executor/cancel d/cancel-query!
      ::executor/abandon-work-classes #{:provider}}))
  (await-active-scope! runtime scope)
  scope)

(defn- fence-database-work!
  [runtime database-name expected-connection-id]
  (when-let [scope (database-scope database-name expected-connection-id)]
    (drain-database-scope! runtime scope)))

(declare release-connection-acquisition!)

(defn- handle-release-database
  [runtime transport-connection request]
  (let [database-name (get-in request [:seon.db/db :db-name])
        route (registry/resolve-connection
               {::registry/database-name (keyword database-name)})
        result
        (if (::registry/conn route)
          (release-connection-acquisition!
           runtime transport-connection database-name (::registry/connection-id route))
          {::registry/released? false})]
    (when (::registry/released? result)
      (swap! (::acquisitions transport-connection)
             disj [database-name (::registry/connection-id route)]))
    (protocol/success
     {::protocol/released? (boolean (::registry/released? result))})))

(defn- handle-delete-branch
  [runtime request]
  (let [database-name (::protocol/target-database-name request)
        connection-id (::protocol/target-connection-id request)
        scope (database-scope database-name connection-id)
        released
        (registry/release-connection-id!
         {::registry/target-database-name (keyword database-name)
          ::registry/connection-id connection-id
          ::registry/expected-target-head
          (::protocol/expected-target-head request)
          ::registry/drain!
          (fn [_release]
            (when scope (drain-database-scope! runtime scope)))})
        _
        (when (and scope (::registry/released? released) (::executor runtime))
          (executor/release-scope!
           {::executor/executor (::executor runtime) ::executor/scope scope}))
        result
        (registry/delete-branch!
         {::registry/source-database-name
          (keyword (::protocol/source-database-name request))
          ::registry/target-database-name
          (keyword database-name)
          ::registry/connection-id connection-id
          ::registry/expected-target-head
          (::protocol/expected-target-head request)
          ::registry/drain! (fn [_release] nil)})]
    (protocol/success
     {::protocol/target-database-name
      (::protocol/target-database-name request)
      ::protocol/target-connection-id (::registry/connection-id result)
      ::protocol/source-head (::registry/branch-head result)
      ::protocol/released? (or (::registry/released? released)
                               (::registry/released? result))
      ::protocol/deleted? (::registry/deleted? result)})))

(defn- connection-for-request
  [transport-connection request]
  (let [database-name (or (get-in request [:seon.db/db :db-name])
                          (::protocol/database-name request))
        route (when database-name
                (registry/resolve-connection
                 {::registry/database-name (keyword database-name)}))
        _
        (when (and transport-connection (::registry/conn route))
          (locking (::connection-lock transport-connection)
            (when @(::closed? transport-connection)
              (throw
               (ex-info "The transport connection closed before database acquisition."
                        {::failure-kind protocol/not-found-error
                         ::protocol/database-name database-name})))
            (registry/acquire-database!
             {::registry/database-name (keyword database-name)
              ::registry/connection-id (::registry/connection-id route)
              ::registry/transport-connection transport-connection})
            (swap! (::acquisitions transport-connection)
                   conj [database-name (::registry/connection-id route)])))
        {::registry/keys [conn connection-id branch-head error-kind error]}
        (if transport-connection
          (registry/resolve-connection
           {::registry/database-name (keyword database-name)
            ::registry/transport-connection transport-connection})
          route)]
    (if conn
      {::connection conn
       ::database-name database-name
       ::branch/connection-id connection-id
       ::branch/head branch-head}
      (throw
       (ex-info (or error (str "Unknown or unacquired database: " database-name))
                {::failure-kind
                 (if (= :seon.db.registry.error/cleanup-required error-kind)
                   protocol/cleanup-required-error
                   protocol/not-found-error)
                 ::protocol/database-name database-name})))))

(defn- query-admission
  "Acquire every routed query database and derive its current executor scope."
  [transport-connection plan]
  (let [_
        (when (empty? (::routing-descriptors plan))
          (throw
           (ex-info "A remote database query requires at least one database source."
                    {::failure-kind protocol/protocol-error})))
        routes
        (mapv
         (fn [descriptor]
           (let [{::keys [connection database-name]
                  connection-id ::branch/connection-id}
                 (connection-for-request transport-connection
                                         {:seon.db/db descriptor})]
             {::database-descriptor descriptor
              ::database-name database-name
              ::connection connection
              ::branch/connection-id connection-id
              ::scope (committed-scope database-name connection-id
                                       (d/db connection))}))
         (distinct (::routing-descriptors plan)))
        primary (first routes)]
    (assoc plan
           ::database-name (::database-name primary)
           ::scope (::scope primary)
           ::scopes (into #{} (map ::scope) routes))))

(defn- require-numeric-temporal-point!
  [db-value point]
  (when (and (integer? point)
             (or (> point (basis-t-of db-value))
                 (nil?
                  (d/q '[:find ?instant .
                         :in $ ?tx
                         :where [?tx :db/txInstant ?instant]]
                       db-value point))))
    (throw
     (ex-info "The database temporal point is unavailable."
              {::failure-kind protocol/not-found-error
               ::protocol/transaction-id point}))))

(defn- resolve-database-value!
  "Resolve and retain one ordinary database value for a physical request."
  [transport-connection descriptor primary-only?]
  (when-not (protocol/database-value? descriptor)
    (throw
     (ex-info "The request contains an invalid database value."
              {::failure-kind protocol/protocol-error
               :seon.db/db descriptor})))
  (let [{::keys [connection database-name]
         connection-id ::branch/connection-id}
        (connection-for-request transport-connection {:seon.db/db descriptor})
        head (d/db connection)
        requested-commit (:datahike/commit-id descriptor)
        head? (= requested-commit (d/commit-id head))
        _ (when-not (or head?
                        (registry/commit-reachable?
                         {::registry/conn connection
                          ::registry/commit-id requested-commit}))
            (throw
             (ex-info "The database value is not reachable from its current head."
                      {::failure-kind protocol/not-found-error
                       :seon.db/db descriptor})))
        containing
        (if head?
          head
          (d/commit-as-db
           connection requested-commit
           (cond-> {} primary-only? (assoc :secondary-indices? false))))
        release? (not head?)]
    (try
      (when-not (and containing
                     (= requested-commit (d/commit-id containing))
                     (= (:t descriptor) (basis-t-of containing)))
        (throw
         (ex-info "The database value does not match its retained commit."
                  {::failure-kind protocol/not-found-error
                   :seon.db/db descriptor})))
      (let [as-of (:as-of descriptor)
            since (:since descriptor)
            _ (when as-of (require-numeric-temporal-point! containing as-of))
            _ (when since (require-numeric-temporal-point! containing since))
            temporal (cond
                       as-of (d/as-of containing as-of)
                       since (d/since containing since)
                       :else containing)
            operation-value (if (:history descriptor)
                              (d/history temporal)
                              temporal)]
        {::connection connection
         ::database-name database-name
         ::branch/connection-id connection-id
         ::scope (committed-scope database-name connection-id containing)
         ::database-value operation-value
         ::containing-database-value containing
         ::release-containing-database? release?})
      (catch Throwable throwable
        (when release?
          (try
            (d/release-materialized-db containing)
            (catch Throwable release-error
              (log/error release-error "failed rejected database value release"
                         {:seon.db/db descriptor}))))
        (throw throwable)))))

(defn- release-resolved-database-values!
  [resolved-values]
  (doseq [db-value
          (->> resolved-values
               (keep (fn [{::keys [containing-database-value
                                   release-containing-database?]}]
                       (when release-containing-database?
                         containing-database-value)))
               (reduce (fn [values value]
                         (if (some #(identical? % value) values)
                           values
                           (conj values value)))
                       []))]
    (try
      (d/release-materialized-db db-value)
      (catch Throwable throwable
        (log/error throwable "database value release failed"))))
  nil)

(defn- resolve-query-plan!
  "Rehydrate only parsed source positions and preserve every other argument."
  [transport-connection plan]
  (let [resolved-by-descriptor (atom {})]
    (try
      (doseq [descriptor
              (distinct (map ::database-descriptor (::query-sources plan)))]
        (swap! resolved-by-descriptor assoc descriptor
               (resolve-database-value! transport-connection descriptor false)))
      (let [arguments
            (reduce
             (fn [values
                  {:datahike.query.source/keys [argument-position]
                   ::keys [database-descriptor]}]
               (assoc values argument-position
                      (::database-value
                       (get @resolved-by-descriptor database-descriptor))))
             (::query-arguments plan)
             (::query-sources plan))]
        (assoc plan
               ::query-arguments arguments
               ::resolved-database-values
               (vec (vals @resolved-by-descriptor))))
      (catch Throwable throwable
        (release-resolved-database-values! (vals @resolved-by-descriptor))
        (throw throwable)))))

(defn- generated-candidate-conflict
  [candidate]
  (protocol/failure
   {::protocol/error-kind protocol/generated-candidate-conflict-error
    ::protocol/error "A generated identity candidate is already in use."
    ::protocol/body {::protocol/generated-candidate candidate}}))

(defn- transaction-failure
  [request ^Throwable throwable]
  (let [candidates (::protocol/generated-candidates request)
        generated? (contains? request ::protocol/generated-candidates)
        failure-kind (::failure-kind (ex-data throwable))]
    (cond
      (= failure-kind protocol/stale-database-value-error)
      (protocol/failure
       {::protocol/error-kind protocol/stale-database-value-error
        ::protocol/error (.getMessage throwable)
        ::protocol/body
        {:seon.db/expected-db (:seon.db/expected-db (ex-data throwable))
         :seon.db/current-db (:seon.db/current-db (ex-data throwable))}})

      (= failure-kind protocol/request-conflict-error)
      (protocol/failure
       {::protocol/error-kind protocol/request-conflict-error
        ::protocol/error (.getMessage throwable)})

      (= failure-kind protocol/protocol-error)
      (protocol/failure
       {::protocol/error-kind protocol/protocol-error
        ::protocol/error (.getMessage throwable)})

      generated?
      (let [classified
            (id/classify-allocation-error
             {::id/generated-candidates candidates ::id/throwable throwable})]
        (case (::id/error-status classified)
          :seon.db.id/candidate-conflict
          (generated-candidate-conflict (::id/generated-candidate classified))

          :seon.db.id/protocol-error
          (protocol/failure
           {::protocol/error-kind protocol/protocol-error
            ::protocol/error (::id/message classified)})

          :seon.db.id/unrelated
          (request-failure-response throwable)))

      :else
      (request-failure-response throwable))))

(defn- transaction-outcome
  [request result]
  (try
    (if (instance? Throwable result)
      (throw result)
      (protocol/success result))
    (catch Throwable throwable
      (transaction-failure request throwable))))

(defn- handle-transact
  [runtime connection database-name connection-id request]
  (locking connection
    (try
      (when (contains? request ::protocol/generated-candidates)
        (id/assert-allocation-writer! connection))
      (transaction-outcome
       request
       (transact-once! runtime connection database-name connection-id request))
      (catch Throwable throwable
        (transaction-failure request throwable)))))

(defn- handle-transact-async
  [runtime connection database-name connection-id request]
  (let [result
        (try
          (when (contains? request ::protocol/generated-candidates)
            (id/assert-allocation-writer! connection))
          (transact-once-async! runtime connection database-name connection-id request)
          (catch Throwable throwable throwable))]
    (if (satisfies? async-protocols/ReadPort result)
      (let [completion (async/promise-chan)]
        (async/take! result
                     #(async/put! completion (transaction-outcome request %)))
        completion)
      (transaction-outcome request result))))

(defn- execute-mutation!
  [runtime {::keys [connection database-name]
            connection-id ::branch/connection-id
            request ::request}]
  (handle-transact-async runtime connection database-name connection-id request))

(defn- assert-knn-database-value!
  [descriptor]
  (when (or (some? (:as-of descriptor))
            (some? (:since descriptor))
            (true? (:history descriptor)))
    (throw
     (ex-info
      "Semantic search requires an exact committed database value."
      {::failure-kind protocol/protocol-error
       :seon.db/db descriptor}))))

(defn- execute-knn-provider!
  [dependencies {request ::request
                 transport-connection ::transport-connection
                 :as work}]
  (assert-knn-database-value! (:seon.db/db request))
  (let [resolved (resolve-database-value! transport-connection
                                          (:seon.db/db request) true)]
    (release-resolved-database-values! [resolved]))
  (let [vector (:seon.embed/vector
                ((::query-vec dependencies)
                 {:seon.embed/text (::protocol/query request)}))]
    (executor/continue-with :knn (assoc work ::query-vector vector))))

(defn- execute-provider!
  [dependencies request]
  (if (contains? request ::request)
    (execute-knn-provider! dependencies request)
    (execute-embedding! dependencies request)))

(defn- execute-knn!
  [dependencies {request ::request
                 transport-connection ::transport-connection
                 :as work}]
  (assert-knn-database-value! (:seon.db/db request))
  (let [resolved (resolve-database-value! transport-connection
                                          (:seon.db/db request) false)]
    (try
      (let [rows (or ((::knn dependencies)
                      (::database-value resolved) (::query-vector work)
                      (long (::protocol/limit request))
                      (seq (::protocol/entity-ids request)))
                     [])]
        (protocol/success
         (assoc (read-response-base request)
                ::protocol/hits
                (mapv (fn [{:keys [entity-id distance]}]
                        {:seon.embed/eid (long entity-id)
                         :seon.embed/distance (double distance)})
                      rows))))
      (finally
        (release-resolved-database-values! [resolved])))))

(defn- compact-explanation
  [explanation]
  (when (map? explanation)
    (cond-> (dissoc explanation :value)
      (vector? (:errors explanation))
      (update :errors
              (fn [errors]
                (mapv #(dissoc % :value) errors))))))

(defn- canonical-response
  [request response]
  (let [correlated (assoc response ::protocol/request-id
                          (or (::protocol/request-id request)
                              "invalid-request"))]
    (if (protocol/valid-response? correlated)
      correlated
      (let [explanation
            (compact-explanation (protocol/explain-response correlated))]
        (log/error "database writer constructed an invalid response"
                   {::response-explanation explanation})
        (protocol/failure
         {::protocol/error-kind protocol/internal-error
          ::protocol/error
          "The database writer constructed an invalid response."
          ::protocol/body
          {::protocol/request-id (or (::protocol/request-id request)
                                     "invalid-request")}})))))

(defn- request-failure-response
  [^Throwable throwable]
  (let [kind (:seon.error/kind (ex-data throwable))]
    (protocol/failure
     (cond->
      {::protocol/error-kind
       (cond
         (::failure-kind (ex-data throwable))
         (::failure-kind (ex-data throwable))

         (or (= protocol/not-found-error kind)
             (contains? protocol/lifecycle-error-kinds kind))
         kind

         (= :seon.db.registry.error/releasing kind)
         protocol/release-error

         :else protocol/database-error)
       ::protocol/error
       (str (.getMessage throwable) " " (pr-str (ex-data throwable)))}
       kind (assoc :seon.error/kind kind)))))

(declare claim-request!)

;;; Selective committed-report interests

(def ^:private committed-report-capacity 256)
(def ^:private committed-report-batch-size 32)
(defonce ^:private readiness-lock (Object.))
(defonce ^:private readiness-state
  (atom {::readiness-runtimes {}
         ::readiness-thread nil}))

(defn- empty-interest-state []
  {::by-scope {}
   ::by-source {}})

(defn- interest-ref
  [transport-connection request-id owner]
  [transport-connection request-id owner])

(defn- interest-attributes
  [interest]
  (if (= :all (::dependencies interest))
    :all
    (or (::dependencies interest)
        (into #{} (map :seon.db/a) (::patterns interest)))))

(defn- add-interest-to-entry
  [entry reference interest]
  (let [attributes (interest-attributes interest)]
    (cond-> (update entry ::interest-count (fnil inc 0))
      (= :all attributes)
      (update ::all (fnil conj #{}) reference)

      (set? attributes)
      (update ::by-attribute
              (fn [by-attribute]
                (reduce (fn [index attribute]
                          (update index attribute (fnil conj #{}) reference))
                        (or by-attribute {}) attributes))))))

(defn- remove-interest-from-entry
  [entry reference interest]
  (let [attributes (interest-attributes interest)]
    (cond-> (update entry ::interest-count dec)
      (= :all attributes)
      (update ::all disj reference)

      (set? attributes)
      (update ::by-attribute
              (fn [by-attribute]
                (reduce
                 (fn [index attribute]
                   (let [remaining (disj (get index attribute #{}) reference)]
                     (if (seq remaining)
                       (assoc index attribute remaining)
                       (dissoc index attribute))))
                 by-attribute attributes))))))

(defn- remove-interest-locked!
  [runtime transport-connection request-id]
  (when-let [interest (get @(::interests transport-connection) request-id)]
    (let [state-atom (::interest-state runtime)
          scope (::scope interest)
          reference (interest-ref transport-connection request-id
                                  (::owner interest))
          state @state-atom
          entry (get-in state [::by-scope scope])
          next-entry (when entry
                       (remove-interest-from-entry entry reference interest))]
      (swap! (::interests transport-connection) dissoc request-id)
      (if (and next-entry (pos? (::interest-count next-entry)))
        (reset! state-atom (assoc-in state [::by-scope scope] next-entry))
        (do
          (when-let [source (::source entry)]
            (committed-report/close! source false))
          (reset! state-atom
                  (cond-> (update state ::by-scope dissoc scope)
                    (::source entry) (update ::by-source dissoc
                                             (::source entry))))))
      interest)))

(defn- remove-connection-interests!
  [runtime transport-connection]
  (locking (::interest-lock runtime)
    (doseq [request-id (keys @(::interests transport-connection))]
      (remove-interest-locked! runtime transport-connection request-id))))

(defn- listen-interest
  [request scope]
  (let [dependencies
        (when-let [query-form (::protocol/query-form request)]
          (d/query-attribute-dependencies query-form))]
    (when (and (set? dependencies) (empty? dependencies))
      (throw
       (ex-info "A query interest must depend on a database attribute."
                {::failure-kind protocol/protocol-error
                 ::protocol/request-id (::protocol/request-id request)})))
    {::owner (Object.)
     ::scope scope
     ::dependencies dependencies
     ::patterns (::protocol/datom-patterns request)}))

(defn- install-interest-locked!
  [runtime transport-connection request connection database-name connection-id]
  (let [request-id (::protocol/request-id request)
        db-value (d/db connection)
        scope (committed-scope database-name connection-id db-value)
        interest (listen-interest request scope)
        reference (interest-ref transport-connection request-id
                                (::owner interest))
        state-atom (::interest-state runtime)
        state @state-atom
        existing (get-in state [::by-scope scope])
        source (or (::source existing)
                   (committed-report/open!
                    (::executor/connection-id scope)
                    (::executor/generation scope)
                    committed-report-capacity))
        entry (or existing
                  {::scope scope
                   ::source source
                   ::connection connection
                   ::database-name database-name
                   ::branch/connection-id connection-id
                   ::interest-count 0
                   ::all #{}
                   ::by-attribute {}})
        next-entry (add-interest-to-entry entry reference interest)
        next-state (-> state
                       (assoc-in [::by-scope scope] next-entry)
                       (assoc-in [::by-source source] scope))]
    (swap! (::interests transport-connection) assoc request-id interest)
    (reset! state-atom next-state)
    (database-value database-name db-value)))

(defn- handle-listen!
  [runtime transport-connection request]
  (when-not transport-connection
    (throw (ex-info "Database interests require a live transport connection."
                    {::failure-kind protocol/protocol-error})))
  (let [request-id (::protocol/request-id request)]
    (locking (::connection-lock transport-connection)
      (when (or @(::closed? transport-connection)
                (.get ^java.util.concurrent.atomic.AtomicBoolean
                      (::closing? transport-connection)))
        (throw (ex-info "The transport connection closed before listen."
                        {::failure-kind protocol/not-found-error})))
      (let [{::keys [connection database-name]
             connection-id ::branch/connection-id}
            (connection-for-request transport-connection request)
            database
            (locking (::interest-lock runtime)
              (remove-interest-locked! runtime transport-connection request-id)
              (install-interest-locked! runtime transport-connection request
                                        connection database-name connection-id))]
        (protocol/success
         {::protocol/request-id request-id
          :db-after database
          ::protocol/listening? true})))))

(defn- handle-unlisten!
  [runtime transport-connection request]
  (when-not transport-connection
    (throw (ex-info "Database interests require a live transport connection."
                    {::failure-kind protocol/protocol-error})))
  (locking (::connection-lock transport-connection)
    (locking (::interest-lock runtime)
      (remove-interest-locked! runtime transport-connection
                               (::protocol/target-request-id request)))
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/target-request-id (::protocol/target-request-id request)
      ::protocol/listening? false})))

(defn- bytes-value=
  [left right]
  (if (and (instance? byte-array-class left)
           (instance? byte-array-class right))
    (java.util.Arrays/equals ^bytes left ^bytes right)
    (= left right)))

(defn- datom-matches-pattern?
  [datom pattern]
  (and (= (:seon.db/a datom) (:seon.db/a pattern))
       (or (not (contains? pattern :seon.db/e))
           (= (:seon.db/e datom) (:seon.db/e pattern)))
       (or (not (contains? pattern :seon.db/v))
           (bytes-value= (:seon.db/v datom) (:seon.db/v pattern)))
       (or (not (contains? pattern :seon.db/added?))
           (= (:seon.db/added? datom) (:seon.db/added? pattern)))))

(defn- matching-datoms
  [interest datoms]
  (cond
    (= :all (::dependencies interest)) datoms
    (set? (::dependencies interest))
    (filterv #(contains? (::dependencies interest) (:seon.db/a %)) datoms)
    :else
    (filterv (fn [datom]
               (some #(datom-matches-pattern? datom %)
                     (::patterns interest)))
             datoms)))

(defn- current-interest
  [transport-connection request-id owner]
  (let [interest (get @(::interests transport-connection) request-id)]
    (when (and interest (identical? owner (::owner interest))) interest)))

(defn- close-pressured-connection!
  [transport-connection status request-id]
  (when (= uds/send-authority-full status)
    (log/warn "database event session closed after authority delivery pressure"
              {::protocol/request-id request-id
               ::uds/send-status status}))
  (when (.compareAndSet ^java.util.concurrent.atomic.AtomicBoolean
                        (::closing? transport-connection) false true)
    ((::close! transport-connection))))

(defn- send-interest-event!
  [transport-connection request-id owner event]
  (locking (::connection-lock transport-connection)
    (when (and (not @(::closed? transport-connection))
               (not (.get ^java.util.concurrent.atomic.AtomicBoolean
                          (::closing? transport-connection)))
               (current-interest transport-connection request-id owner))
      (let [result ((::send! transport-connection) event)
            status (::uds/send-status result)]
        (when-not (= uds/send-accepted status)
          (close-pressured-connection! transport-connection status request-id))
        status))))

(defn- send-database-event!
  [transport-connection database-name connection-id event]
  (locking (::connection-lock transport-connection)
    (when (and (not @(::closed? transport-connection))
               (not (.get ^java.util.concurrent.atomic.AtomicBoolean
                          (::closing? transport-connection)))
               (contains? @(::acquisitions transport-connection)
                          [database-name connection-id]))
      (let [result ((::send! transport-connection) event)
            status (::uds/send-status result)]
        (when-not (= uds/send-accepted status)
          (close-pressured-connection! transport-connection status nil))
        status))))

(defn- deliver-database-advanced!
  [origin connection-id database]
  (let [database-name (:db-name database)
        connections
        (::registry/transport-connections
         (registry/acquired-transport-connections
          {::registry/database-name (keyword database-name)
           ::registry/connection-id connection-id}))
        event {::protocol/event protocol/database-advanced-event
               :db-after database}]
    (doseq [transport-connection connections
            :when (not (identical? origin transport-connection))]
      (send-database-event! transport-connection database-name connection-id event))))

(defn- candidate-interests
  [runtime scope datoms]
  (locking (::interest-lock runtime)
    (when-let [entry (get-in @(::interest-state runtime) [::by-scope scope])]
      (let [references
            (into (::all entry)
                  (mapcat #(get (::by-attribute entry) (:seon.db/a %) #{}))
                  datoms)]
        (into []
              (keep
               (fn [[transport-connection request-id owner :as reference]]
                 (when-let [interest
                            (current-interest transport-connection request-id
                                              owner)]
                   [reference interest])))
              references)))))

(defn- deliver-report!
  [runtime scope report]
  (let [datoms (mapv datom-map
                     (public-transaction-datoms (:tx-data report)))
        database-name (::executor/database-name scope)]
    (doseq [[[transport-connection request-id owner] interest]
            (candidate-interests runtime scope datoms)
            :let [matches (matching-datoms interest datoms)]
            :when (seq matches)]
      (send-interest-event!
       transport-connection request-id owner
       (merge
        {::protocol/event protocol/datoms-event
         ::protocol/request-id request-id}
        (dissoc (transaction-report-data database-name nil report request-id)
                ::protocol/request-id))))))

(defn- replace-gapped-source!
  [runtime scope source]
  (locking (::interest-lock runtime)
    (let [state-atom (::interest-state runtime)
          state @state-atom
          entry (get-in state [::by-scope scope])]
      (when (and entry (identical? source (::source entry)))
        (committed-report/close! source false)
        (let [replacement
              (committed-report/open!
               (::executor/connection-id scope)
               (::executor/generation scope)
               committed-report-capacity)
              db-after (database-value (::database-name entry)
                                       (d/db (::connection entry)))
              references
              (into (::all entry) (mapcat val) (::by-attribute entry))
              next-entry (assoc entry ::source replacement)]
          (reset! state-atom
                  (-> state
                      (assoc-in [::by-scope scope] next-entry)
                      (update ::by-source dissoc source)
                      (assoc-in [::by-source replacement] scope)))
          {::source replacement
           :db-after db-after
           ::references references})))))

(defn- deliver-resynchronization!
  [runtime scope source]
  (when-let [{::keys [references]
              db-after :db-after}
             (replace-gapped-source! runtime scope source)]
    (doseq [[transport-connection request-id owner] references]
      (send-interest-event!
       transport-connection request-id owner
       {::protocol/event protocol/resynchronization-event
        ::protocol/request-id request-id
        :db-after db-after}))))

(defn- execute-delivery!
  [runtime {::keys [scope source]}]
  (let [batch (committed-report/poll-batch! source
                                             committed-report-batch-size)]
    (if (= :datahike.committed-report.status/gapped
           (:datahike.committed-report/status batch))
      (deliver-resynchronization! runtime scope source)
      (run! #(deliver-report! runtime scope %)
            (:datahike.committed-report/reports batch)))
    {::scope scope}))

(defn- requeue-scope!
  [runtime scope]
  (when-let [source
             (locking (::interest-lock runtime)
               (get-in @(::interest-state runtime)
                       [::by-scope scope ::source]))]
    (committed-report/requeue-ready! source)))

(defn- submit-ready-source!
  [runtime source]
  (when-let [scope
             (locking (::interest-lock runtime)
               (get-in @(::interest-state runtime) [::by-source source]))]
    (let [admission
          (executor/try-submit!
           {::executor/executor (::executor runtime)
            ::executor/work-class :delivery
            ::executor/database-name (::executor/database-name scope)
            ::executor/scope scope
            ::executor/job-id scope
            ::executor/request {::scope scope ::source source}})]
      (when-not (::executor/accepted? admission)
        (when-not (::executor/joined? admission)
          (committed-report/requeue-ready! source))))))

(defn- readiness-runtime
  [source]
  (some
   (fn [runtime]
     (locking (::interest-lock runtime)
       (when (get-in @(::interest-state runtime) [::by-source source])
         runtime)))
   (vals (::readiness-runtimes @readiness-state))))

(defn- run-readiness!
  []
  (loop []
    (when-not (.isInterrupted (Thread/currentThread))
      (let [continue?
            (try
              (let [source (committed-report/take-ready!)]
                (if-let [runtime (readiness-runtime source)]
                  (submit-ready-source! runtime source)
                  (do
                    (committed-report/requeue-ready! source)
                    (Thread/yield))))
              true
              (catch InterruptedException _ false)
              (catch Throwable throwable
                (log/error throwable "database committed-report readiness failed")
                true))]
        (when continue? (recur))))))

(defn- register-readiness!
  [runtime]
  (locking readiness-lock
    (let [owner (::readiness-owner runtime)
          state (update @readiness-state ::readiness-runtimes assoc owner runtime)]
      (if (::readiness-thread state)
        (reset! readiness-state state)
        (let [thread
              (doto (Thread. ^Runnable run-readiness!
                             "seon-database-committed-report-readiness")
                (.setDaemon true)
                (.start))]
          (reset! readiness-state (assoc state ::readiness-thread thread))))))
  nil)

(defn- unregister-readiness!
  [runtime]
  (locking readiness-lock
    (let [owner (::readiness-owner runtime)
          state (update @readiness-state ::readiness-runtimes dissoc owner)]
      (if (seq (::readiness-runtimes state))
        (reset! readiness-state state)
        (let [thread ^Thread (::readiness-thread state)]
          (when thread
            (.interrupt thread)
            (.join thread))
          (reset! readiness-state (assoc state ::readiness-thread nil))))))
  nil)

(defn- transport-connection
  [{close! ::uds/close! send! ::uds/send!}]
  {::connection-lock (Object.)
   ::closed? (atom false)
   ::closing? (java.util.concurrent.atomic.AtomicBoolean. false)
   ::acquisitions (atom #{})
   ::interests (atom {})
   ::close! close!
   ::send! send!})

(defn- claim-connection-request!
  [runtime transport-connection request frame-bytes complete!]
  (if transport-connection
    (locking (::connection-lock transport-connection)
      (when-not @(::closed? transport-connection)
        (claim-request! runtime transport-connection request frame-bytes
                        complete!)))
    (claim-request! runtime nil request frame-bytes complete!)))

(defn- claim-request!
  [runtime transport-connection request frame-bytes complete!]
  (let [active (::active-requests runtime)
        request-id (::protocol/request-id request)
        owner (Object.)]
    (locking active
      (when-not (contains? @active request-id)
        (swap! active assoc request-id
               {::owner owner
                ::request request
                ::request-bytes frame-bytes
                ::transport-connection transport-connection
                ::complete! complete!
                ::jobs #{}
                ::canceled? false})
        owner))))

(defn- remove-active-request!
  [runtime request-id owner]
  (let [active (::active-requests runtime)]
    (locking active
      (when (identical? owner (get-in @active [request-id ::owner]))
        (swap! active dissoc request-id)
        (.notifyAll ^Object active)
        true))))

(defn- deliver-active-request!
  [runtime request-id owner response]
  (let [active (::active-requests runtime)
        entry (locking active
                (let [entry (get @active request-id)]
                  (when (and entry (identical? owner (::owner entry)))
                    (swap! active dissoc request-id)
                    (.notifyAll ^Object active)
                    entry)))]
    (when entry
      (try
        ((::complete! entry) (canonical-response (::request entry) response))
        (catch Throwable throwable
          (log/error throwable "database request delivery failed"
                     {::protocol/request-id request-id}))))))

(defn- await-active-scope!
  [runtime scope]
  (when-let [active (::active-requests runtime)]
    (locking active
      (loop []
        (when (some (fn [[_ entry]] (entry-owns-scope? entry scope)) @active)
          (.wait ^Object active)
          (recur))))))

(defn- await-active-connection!
  [runtime transport-connection]
  (let [active (::active-requests runtime)]
    (locking active
      (loop []
        (when (some (fn [[_ entry]]
                      (identical? transport-connection
                                  (::transport-connection entry)))
                    @active)
          (.wait ^Object active)
          (recur))))))

(defn- register-query-job!
  [runtime owner-request-id job-id resolved-values]
  (let [jobs (::query-jobs runtime)]
    (locking jobs
      (swap! jobs
             (fn [state]
               (-> state
                   (assoc-in [::by-owner owner-request-id]
                             (cond-> {::job-id job-id ::canceled? false}
                               (seq resolved-values)
                               (assoc ::resolved-database-values
                                      resolved-values)))
                   (assoc-in [::by-job job-id] owner-request-id)))))))

(defn- continue-query-job?
  [runtime owner-request-id job-id]
  (let [jobs (::query-jobs runtime)]
    (locking jobs
      (let [entry (get-in @jobs [::by-owner owner-request-id])]
        (when (and (= job-id (::job-id entry))
                   (not (::canceled? entry)))
          (swap! jobs assoc-in [::by-owner owner-request-id ::phase] :queued)
          true)))))

(defn- finish-query-job!
  [runtime job-id]
  (let [jobs (::query-jobs runtime)
        entry
        (locking jobs
          (when-let [owner-request-id (get-in @jobs [::by-job job-id])]
            (let [entry (get-in @jobs [::by-owner owner-request-id])]
              (swap! jobs
                     (fn [state]
                       (-> state
                           (update ::by-owner dissoc owner-request-id)
                           (update ::by-job dissoc job-id))))
              entry)))]
    (release-resolved-database-values!
     (::resolved-database-values entry))
    entry))

(defn- cancel-unstarted-owner-job!
  [runtime owner-request-id]
  (let [jobs (::query-jobs runtime)
        job-id
        (locking jobs
          (when-let [entry (get-in @jobs [::by-owner owner-request-id])]
            (swap! jobs assoc-in [::by-owner owner-request-id ::canceled?] true)
            (::job-id entry)))]
    (when job-id
      (executor/cancel-queued!
       {::executor/executor (::executor runtime)
        ::executor/job-id job-id}))))

(defn- cancel-query-caller!
  [runtime caller-id]
  (let [result (d/cancel-query! caller-id)]
    (when (:datahike.query.cancel/unstarted-owner? result)
      (cancel-unstarted-owner-job!
       runtime (:datahike.query.cancel/owner-request-id result)))
    result))

(declare complete-execute-many-query!)

(defn- complete-query-call!
  [runtime request-id owner job-id request execute-many?
   release-on-callback? resolved-values completion]
  (if execute-many?
    (complete-execute-many-query! runtime request-id owner job-id completion)
    (try
      (deliver-active-request! runtime request-id owner
                               (query-response request completion))
      (finally
        (when release-on-callback?
          (release-resolved-database-values! resolved-values))))))

(defn- acquire-query-call!
  [runtime {::keys [request database-value caller-id job-id query-plan
                    transport-connection]
            prepared-query-arguments ::query-arguments
            :as work}]
  (let [resolved-plan (when query-plan
                        (resolve-query-plan! transport-connection query-plan))
        arguments (cond
                    resolved-plan (::query-arguments resolved-plan)
                    prepared-query-arguments prepared-query-arguments
                    :else (into [database-value]
                                (::protocol/arguments request)))
        resolved-values (or (::resolved-database-values resolved-plan) [])
        physical-values-owned? (atom false)
        request-id (or (::public-request-id work)
                       (::protocol/request-id request))
        execute-many? (true? (::execute-many-member? work))]
    (try
      (let [call (d/acquire-q! (query-arguments arguments request caller-id))
            state (d/q-call-state call)
            completed? (java.util.concurrent.atomic.AtomicBoolean. false)
            active (::active-requests runtime)
            installed?
            (locking active
              (let [entry (get @active request-id)]
                (when (and entry (identical? (::owner work) (::owner entry)))
                  (swap! active update request-id
                         (fn [current]
                           (cond-> (-> current
                                       (assoc-in [::query-callers job-id]
                                                 caller-id)
                                       (update ::pending-queries (fnil conj #{})
                                               job-id))
                             (not execute-many?)
                             (assoc ::query-callback? true))))
                  true)))]
        (if-not installed?
          (do
            (cancel-query-caller! runtime caller-id)
            (release-resolved-database-values! resolved-values)
            {::query-acquired? true})
          (do
            (when (= :run state)
              (register-query-job! runtime caller-id job-id resolved-values)
              (reset! physical-values-owned? true))
            (d/on-q-complete!
             call
             (fn [completion]
               (when (.compareAndSet completed? false true)
                 (complete-query-call! runtime request-id (::owner work) job-id
                                       request execute-many?
                                       (not= :run state)
                                       resolved-values
                                       completion))))
            (let [canceled?
                  (locking active
                    (true? (get-in @active [request-id ::canceled?])))
                  cancellation (when canceled?
                                 (cancel-query-caller! runtime caller-id))]
              (if (and (= :run state)
                       (not (:datahike.query.cancel/unstarted-owner?
                             cancellation)))
                (executor/continue-with
                 :read {::run-query-call? true ::query-call call}
                 #(continue-query-job? runtime caller-id job-id))
                {::query-acquired? true})))))
      (catch Throwable throwable
        (when-not @physical-values-owned?
          (release-resolved-database-values! resolved-values))
        (throw throwable)))))

(defn- release-connection-acquisition!
  [runtime transport-connection database-name connection-id]
  (let [scope (database-scope database-name connection-id)
        drained? (atom false)
        result
        (registry/release-database-acquisition!
         {::registry/database-name (keyword database-name)
          ::registry/transport-connection transport-connection
          ::registry/drain!
          (fn [_release]
            (when-not scope
              (throw
               (ex-info "The acquired database scope is unavailable for release."
                        {::protocol/database-name database-name
                         ::protocol/target-connection-id connection-id})))
            (reset! drained? true)
            (drain-database-scope! runtime scope))})]
    (when (and @drained? (::registry/released? result) (::executor runtime))
      (executor/release-scope!
       {::executor/executor (::executor runtime) ::executor/scope scope}))
    result))

(defn- handle-acquire-database
  [_runtime transport-connection request]
  (when-not transport-connection
    (throw (ex-info "Database acquisition requires a live transport connection."
                    {::protocol/database-name
                     (::protocol/database-name request)})))
  (let [database-name (::protocol/database-name request)
        result
        (locking (::connection-lock transport-connection)
          (when @(::closed? transport-connection)
            (throw
             (ex-info "The transport connection closed before acquisition."
                      {::protocol/database-name database-name})))
          (let [result
                (registry/acquire-database!
                 {::registry/database-name (keyword database-name)
                  ::registry/transport-connection transport-connection})]
            (swap! (::acquisitions transport-connection)
                   conj [database-name (::registry/connection-id result)])
            result))
        resolved
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)
          ::registry/transport-connection transport-connection})]
    (protocol/success
     {::protocol/database-name database-name
      :seon.db/db
      (database-value database-name (d/db (::registry/conn resolved)))
      ::protocol/acquired? (::registry/acquired? result)})))

(defn- single-outcome-response
  [entry [outcome value]]
  (let [request (::request entry)]
    (if (= ::executor/throwable outcome)
      (if (and (::canceled? entry)
               (= protocol/transact-operation (::protocol/operation request)))
        (if-let [recovered
                 (recover-current (::connection entry)
                                  (::database-name entry)
                                  (::protocol/request-id request)
                                  (protocol/logical-transaction-hash request)
                                  (::protocol/generated-candidates request))]
          (protocol/success recovered)
          (protocol/failure
           {::protocol/error-kind protocol/database-error
            ::protocol/error "The database transaction was canceled."
            ::protocol/body {::protocol/canceled? true}}))
        (request-failure-response value))
      (if (read-operations (::protocol/operation request))
        (protocol/success value)
        value))))

(defn- reserve-single-job!
  [runtime request-id owner scope scopes job-id entry-data]
  (let [active (::active-requests runtime)]
    (locking active
      (when (identical? owner (get-in @active [request-id ::owner]))
        (let [request-bytes (get-in @active [request-id ::request-bytes] 0)]
        (swap! active update request-id
               #(merge % entry-data
                       {::scope scope
                        ::scopes (or scopes #{scope})
                        ::jobs #{job-id}}))
          request-bytes)))))

(declare complete-executor! drive-execute-many!)

(defn- execute-many-member-request
  [request position]
  (let [member (nth (::protocol/members request) position)]
    (if (nil? (:datahike.resource/max-result-weight member))
      (assoc member
             :datahike.resource/max-result-weight
             (:datahike.resource/max-result-weight request))
      member)))

(defn- execute-many-plan
  "Plan every grouped read before any immutable database value is resolved."
  [request]
  (let [member-plans
        (mapv
         (fn [position]
           (let [member (execute-many-member-request request position)
                 plan (read-plan member)]
             (when (empty? (::routing-descriptors plan))
               (throw
                (ex-info
                 "Every grouped remote read requires a database source."
                 {::failure-kind protocol/protocol-error
                  ::protocol/member-position position})))
             (assoc plan ::request member)))
         (range (count (::protocol/members request))))]
    {::member-plans member-plans
     ::routing-descriptors
     (into [] (distinct) (mapcat ::routing-descriptors member-plans))}))

(defn- hydrate-query-plan
  [plan resolved-by-descriptor]
  (assoc
   plan
   ::query-arguments
   (reduce
    (fn [arguments
         {:datahike.query.source/keys [argument-position]
          ::keys [database-descriptor]}]
      (assoc arguments argument-position
             (::database-value
              (get resolved-by-descriptor database-descriptor))))
    (::query-arguments plan)
    (::query-sources plan))))

(defn- resolved-member-plan
  [plan resolved-by-descriptor]
  (let [resolved-values
        (mapv resolved-by-descriptor (::routing-descriptors plan))
        primary (first resolved-values)
        query? (= protocol/query-operation
                  (::protocol/operation (::request plan)))]
    (cond->
      (assoc plan
             ::database-name (::database-name primary)
             ::scope (::scope primary)
             ::scopes (into #{} (map ::scope) resolved-values))
      query? (hydrate-query-plan resolved-by-descriptor)
      (not query?) (assoc ::database-value (::database-value primary)))))

(defn- resolve-execute-many-plan!
  [transport-connection plan]
  (let [resolved-by-descriptor (atom {})]
    (try
      (doseq [descriptor (::routing-descriptors plan)]
        (swap! resolved-by-descriptor assoc descriptor
               (resolve-database-value! transport-connection descriptor false)))
      {::member-plans
       (mapv #(resolved-member-plan % @resolved-by-descriptor)
             (::member-plans plan))
       ::resolved-database-values (vec (vals @resolved-by-descriptor))}
      (catch Throwable throwable
        (release-resolved-database-values! (vals @resolved-by-descriptor))
        (throw throwable)))))

(defn- submit-execute-many-members!
  [runtime request-id owner submissions]
  (doseq [{job-id ::executor/job-id :as submission} submissions]
    (let [active (::active-requests runtime)
          reserved?
          (locking active
            (let [{::keys [jobs result-limit-position] :as entry}
                  (get @active request-id)
                  position (second job-id)]
              (when (and entry (identical? owner (::owner entry))
                         (contains? jobs job-id))
                (if (and result-limit-position
                         (>= position result-limit-position))
                  (do
                    (swap! active update-in [request-id ::jobs] disj job-id)
                    false)
                  true))))]
      (when reserved?
        (try
          (executor/try-submit! submission)
          (catch Throwable throwable
            (complete-executor!
             runtime
             {::executor/job-id job-id
              ::executor/request-id request-id
              ::executor/outcome [::executor/throwable throwable]})))
        (when (locking active
                (let [{::keys [canceled? result-limit-position]}
                      (get @active request-id)]
                  (or canceled?
                      (and result-limit-position
                           (>= (second job-id) result-limit-position)))))
          (executor/cancel!
           {::executor/executor (::executor runtime)
            ::executor/job-id job-id}))))))

(defn- reserve-execute-many-members!
  [runtime request-id owner]
  (let [active (::active-requests runtime)
        dispatcher (::executor runtime)]
    (locking active
      (let [{::keys [request member-plans next-position next-result-position
                     jobs canceled?]
             :as entry}
            (get @active request-id)]
        (when (and entry (identical? owner (::owner entry)) member-plans
                   (not canceled?) (not (::result-limit-position entry))
                   (not (::finishing? entry)))
          (let [members (::protocol/members request)
                member-count (count members)
                active-limit
                (get-in dispatcher
                        [::executor/capacity ::executor/classes :read
                         ::executor/maximum-queued-by-database])
                admitted-not-accepted (- next-position next-result-position)
                count-to-reserve
                (max 0
                     (min (- active-limit admitted-not-accepted)
                          (- member-count next-position)))
                positions (range next-position (+ next-position count-to-reserve))
                submissions
                (mapv
                 (fn [position]
                   (let [{::keys [request database-name scope scopes
                                  database-value query-arguments]}
                         (nth member-plans position)
                         member request
                         job-id [request-id position]
                         caller-id (str "execute-many/"
                                        (hasch/uuid [request-id position]))
                         query? (= protocol/query-operation
                                   (::protocol/operation member))]
                     (cond->
                      {::executor/executor dispatcher
                       ::executor/work-class :read
                       ::executor/database-name database-name
                       ::executor/scope scope
                       ::executor/scopes scopes
                       ::executor/job-id job-id
                       ::executor/request-id request-id
                       ::executor/request
                       {::database-name database-name
                        ::scope scope
                        ::scopes scopes
                        ::database-value database-value
                        ::job-id job-id
                        ::caller-id caller-id
                        ::public-request-id request-id
                        ::owner (::owner entry)
                        ::execute-many-member? query?
                        ::acquire-query? query?
                        ::query-arguments query-arguments
                        ::request member}}
                       query? (assoc ::executor/reserved-work-class :read))))
                 positions)]
            (when (seq submissions)
              (swap! active update request-id
                     (fn [current]
                       (-> current
                           (assoc ::next-position (+ next-position count-to-reserve))
                           (update ::jobs into (map ::executor/job-id submissions))))))
            submissions))))))

(def ^:private execute-many-result-limit
  (protocol/failure
   {::protocol/error-kind protocol/database-error
    ::protocol/error
    "The grouped database result exceeded its resource limit."}))

(defn- bounded-execute-many-member-result
  [request result]
  (if (d/shallow-weight-within
       result (:datahike.resource/max-result-weight request))
    result
    execute-many-result-limit))

(defn- execute-many-canceled-result []
  (member-failure
   (ex-info "The database request was canceled."
            {::failure-kind protocol/database-error})))

(defn- execute-many-result-state
  "Reserve one fixed bounded result at every member position."
  [request]
  (let [member-count (count (::protocol/members request))
        result-limit execute-many-result-limit
        results (vec (repeat member-count result-limit))
        response
        (protocol/success
         (assoc (read-response-base request) ::protocol/results results))
        max-result-weight (:datahike.resource/max-result-weight request)
        result-weight (d/shallow-weight-within response max-result-weight)
        placeholder-weight
        (d/shallow-weight-within result-limit max-result-weight)]
    (when (and result-weight placeholder-weight)
      {::results (vec (repeat member-count nil))
       ::next-result-position 0
       ::result-weight result-weight
       ::result-placeholder-weight placeholder-weight})))

(defn- accept-contiguous-execute-many-results
  "Charge completed member results in vector position order."
  [{::keys [request results next-result-position result-weight
            result-placeholder-weight result-limit-position]
    :as entry}]
  (if result-limit-position
    entry
    (let [member-count (count (::protocol/members request))
          max-result-weight (:datahike.resource/max-result-weight request)
          result-limit execute-many-result-limit]
      (loop [entry entry
             position next-result-position
             result-weight result-weight]
        (if (>= position member-count)
          (assoc entry
                 ::next-result-position position
                 ::result-weight result-weight)
          (if-let [result (nth (::results entry) position)]
            (let [remaining-result-weight
                  (+ (- max-result-weight result-weight)
                     result-placeholder-weight)
                  member-weight
                  (d/shallow-weight-within result remaining-result-weight)]
              (if member-weight
                (recur entry (inc position)
                       (+ (- result-weight result-placeholder-weight)
                          member-weight))
                (assoc entry
                       ::next-position member-count
                       ::next-result-position member-count
                       ::result-limit-position position
                       ::result-weight result-weight
                       ::results
                       (into (subvec results 0 position)
                             (repeat (- member-count position)
                                     result-limit)))))
            (assoc entry
                   ::next-result-position position
                   ::result-weight result-weight)))))))

(defn- cancel-execute-many-result-limit!
  [runtime request-id owner]
  (let [active (::active-requests runtime)
        target
        (locking active
          (let [entry (get @active request-id)]
            (when (and entry
                       (identical? owner (::owner entry))
                       (::result-limit-position entry)
                       (not (::result-limit-canceled? entry)))
              (swap! active assoc-in
                     [request-id ::result-limit-canceled?] true)
              entry)))]
    (when target
      (handle-cancel
       runtime
       {::protocol/request-id request-id
        ::protocol/target-request-id request-id}
       target))))

(defn- finalize-execute-many!
  [runtime request-id owner]
  (let [active (::active-requests runtime)
        final
        (locking active
          (let [{::keys [request jobs pending-queries canceled? next-position]
                 :as entry}
                (get @active request-id)
                member-count (count (::protocol/members request))]
            (when (and entry (identical? owner (::owner entry))
                       (not (::finishing? entry))
                       (empty? jobs)
                       (empty? pending-queries)
                       (or canceled? (= next-position member-count)))
              (let [entry
                    (if canceled?
                      (-> entry
                          (update ::results
                                  #(mapv (fn [result]
                                           (or result
                                               (execute-many-canceled-result)))
                                         %))
                          (accept-contiguous-execute-many-results))
                      entry)
                    results (::results entry)
                    response
                    (protocol/success
                     (assoc (read-response-base request)
                            ::protocol/results results))]
                (swap! active assoc-in [request-id ::finishing?] true)
                [entry response]))))]
    (when final
      (let [[entry response] final
            release-error
            (try
              (release-resolved-database-values!
               (::resolved-database-values entry))
              nil
              (catch Throwable throwable throwable))
            response (if release-error
                       (request-failure-response release-error)
                       response)]
        (when (remove-active-request! runtime request-id owner)
          (try
            ((::complete! entry) (canonical-response (::request entry) response))
            (catch Throwable throwable
              (log/error throwable "execute-many delivery failed"
                         {::protocol/request-id request-id}))))))))

(defn- drive-execute-many!
  [runtime request-id owner]
  (cancel-execute-many-result-limit! runtime request-id owner)
  (when-let [submissions
             (reserve-execute-many-members! runtime request-id owner)]
    (submit-execute-many-members! runtime request-id owner submissions))
  (finalize-execute-many! runtime request-id owner))

(defn- complete-execute-many!
  [runtime request-id owner job-id outcome]
  (let [active (::active-requests runtime)
        resolution? (= job-id [request-id :resolve])
        accepted?
        (locking active
          (let [entry (get @active request-id)]
            (when (and entry (identical? owner (::owner entry))
                       (contains? (::jobs entry) job-id))
              (if resolution?
                (if (= ::executor/throwable (first outcome))
                  (swap! active update request-id
                         #(-> %
                              (update ::jobs disj job-id)
                              (assoc ::failure-response
                                     (request-failure-response
                                      (second outcome)))))
                  (swap! active update request-id
                         #(-> %
                              (update ::jobs disj job-id)
                              (merge (second outcome)))))
                (let [position (second job-id)
                      member
                      (execute-many-member-request (::request entry) position)
                      query? (= protocol/query-operation
                                (::protocol/operation
                                 (nth (::protocol/members (::request entry))
                                      position)))
                      callback? (contains? (::pending-queries entry) job-id)
                      result
                      (bounded-execute-many-member-result
                       member
                       (if (= ::executor/throwable (first outcome))
                         (member-failure (second outcome))
                         (second outcome)))]
                  (swap! active update request-id
                         (fn [current]
                           (->
                            (cond-> (update current ::jobs disj job-id)
                              (and
                               (or (nil? (::result-limit-position current))
                                   (< position
                                      (::result-limit-position current)))
                               (not callback?)
                               (or (not query?)
                                   (= ::executor/throwable (first outcome))))
                              (assoc-in [::results position] result))
                            (accept-contiguous-execute-many-results))))))
              true)))]
    (when (and resolution? (not accepted?)
               (not= ::executor/throwable (first outcome)))
      (release-resolved-database-values!
       (::resolved-database-values (second outcome))))
    (if (and resolution? (= ::executor/throwable (first outcome)))
      (let [entry (locking active (get @active request-id))
            response (::failure-response entry)]
        (when response
          (deliver-active-request! runtime request-id owner response)))
      (drive-execute-many! runtime request-id owner))))

(defn- complete-execute-many-query!
  [runtime request-id owner job-id completion]
  (let [active (::active-requests runtime)]
    (locking active
      (let [entry (get @active request-id)]
        (when (and entry (identical? owner (::owner entry))
                   (contains? (::pending-queries entry) job-id))
          (swap! active update request-id
                 (fn [current]
                   (let [position (second job-id)
                         member
                         (execute-many-member-request (::request current)
                                                      position)
                         current
                         (-> current
                             (update ::pending-queries disj job-id)
                             (update ::query-callers dissoc job-id))]
                     (->
                      (if (or (nil? (::result-limit-position current))
                              (< position (::result-limit-position current)))
                        (assoc-in
                         current [::results position]
                         (bounded-execute-many-member-result
                          member
                          (if (= :ok (:status completion))
                            (protocol/success
                             (update (:value completion)
                                     :datahike.query/result
                                     materialize-result))
                            (member-failure (:throwable completion)))))
                        current)
                      (accept-contiguous-execute-many-results))))))))
    (drive-execute-many! runtime request-id owner)))

(defn- complete-executor!
  [runtime {::executor/keys [request-id job-id outcome]}]
  (finish-query-job! runtime job-id)
  (if request-id
    (let [active (::active-requests runtime)
          entry (locking active (get @active request-id))]
      (when entry
        (if (::execute-many? entry)
          (complete-execute-many! runtime request-id (::owner entry) job-id outcome)
          (when-not (::query-callback? entry)
            (let [request (::request entry)
                  response (single-outcome-response entry outcome)]
              (deliver-active-request! runtime request-id (::owner entry) response)
              (when (and (= protocol/transact-operation
                            (::protocol/operation request))
                         (::protocol/success? response)
                         (not (::protocol/recovered? response))
                         (:db-after response))
                (deliver-database-advanced!
                 (::transport-connection entry)
                 (::branch/connection-id entry)
                 (:db-after response))))))))
    (when (map? job-id)
      (requeue-scope! runtime job-id))))

(defn- handle-request-sync
  "Interpret one complete canonical database protocol request."
  {:malli/schema [:=> [:catn [::runtime ::runtime]
                            [:seon.db.writer/request :map]]
                  :seon.db.protocol/response]}
  [runtime transport-connection request]
  (canonical-response request
   (if-not (protocol/valid-request? request)
     (let [explanation
           (compact-explanation (protocol/explain-request request))]
       (protocol/failure
        {::protocol/error-kind protocol/protocol-error
         ::protocol/error
         (str "Invalid database protocol request: "
              (pr-str explanation))}))
     (try
       (case (::protocol/operation request)
         :seon.db.protocol.operation/ping
         (protocol/success {::protocol/pong? true})

         :seon.db.protocol.operation/capabilities
         (protocol/success
          {::protocol/request-id (::protocol/request-id request)
           ::protocol/capabilities
           (assoc (d/capabilities)
                  ::protocol/version protocol/current-version
                  ::protocol/maximum-frame-bytes
                  protocol/maximum-frame-bytes)})

         :seon.db.protocol.operation/resolve-head
         (let [{::registry/keys [conn database-name]}
               (registry/resolve-connection
                {::registry/database-name
                 (keyword (::protocol/database-name request))})]
           (if conn
             (protocol/success
              {::protocol/request-id (::protocol/request-id request)
               :seon.db/db (database-value (name database-name) (d/db conn))})
             (protocol/failure
              {::protocol/error-kind protocol/not-found-error
               ::protocol/error
               (str "Unknown database: "
                    (::protocol/database-name request))})))

         :seon.db.protocol.operation/query
         (throw (ex-info "Database reads require callback completion." {}))

         :seon.db.protocol.operation/pull
         (throw (ex-info "Database reads require callback completion." {}))

         :seon.db.protocol.operation/pull-many
         (throw (ex-info "Database reads require callback completion." {}))

         :seon.db.protocol.operation/schema
         (throw (ex-info "Database reads require callback completion." {}))

         :seon.db.protocol.operation/index-page
         (throw (ex-info "Database reads require callback completion." {}))

         :seon.db.protocol.operation/execute-many
         (throw (ex-info "Database reads require callback completion." {}))

         :seon.db.protocol.operation/cancel
         (throw (ex-info "Cancellation requires callback completion." {}))

         :seon.db.protocol.operation/ensure-database
         (handle-ensure-database runtime request)

         :seon.db.protocol.operation/observe-database-lifecycle
         (handle-observe-database-lifecycle request)

         :seon.db.protocol.operation/create-branch
         (handle-create-branch runtime request)

         :seon.db.protocol.operation/release-database
         (handle-release-database runtime transport-connection request)

         :seon.db.protocol.operation/delete-branch
         (handle-delete-branch runtime request)

         (if-let [{::keys [connection database-name]
                   connection-id ::branch/connection-id}
                  (connection-for-request transport-connection request)]
           (case (::protocol/operation request)
             :seon.db.protocol.operation/transact
             (handle-transact runtime connection database-name connection-id request)

             :seon.db.protocol.operation/resolve-transaction-branch-head
             (handle-resolve-transaction-branch-head connection request)

             :seon.db.protocol.operation/knn-search
             (throw (ex-info "KNN search requires callback completion." {})))
           (protocol/failure
            {::protocol/error-kind protocol/not-found-error
             ::protocol/error
             (str "Unknown database: "
                  (::protocol/database-name request))})))
       (catch clojure.lang.ExceptionInfo exception
         (request-failure-response exception))
       (catch Throwable throwable
         (log/error throwable "database request failed")
         (protocol/failure
          {::protocol/error-kind protocol/internal-error
           ::protocol/error (.toString throwable)}))))))

(defn- submit-single!
  [runtime request-id owner scope submission]
  (when-let [request-bytes
             (reserve-single-job! runtime request-id owner scope
                                  (::executor/scopes submission)
                                  (::executor/job-id submission)
                                  (select-keys (::executor/request submission)
                                               [::branch/connection-id
                                                ::connection
                                                ::database-name]))]
    (try
      (executor/try-submit!
       (assoc submission ::executor/request-bytes request-bytes))
      (catch Throwable throwable
        (complete-executor!
         runtime
         {::executor/job-id (::executor/job-id submission)
          ::executor/request-id request-id
          ::executor/outcome [::executor/throwable throwable]})))))

(defn- start-read-request!
  [runtime transport-connection request request-id owner]
  (validate-read-input! request)
  (let [query? (= protocol/query-operation (::protocol/operation request))
        plan (if query?
               (query-plan request)
               {::routing-descriptors [(:seon.db/db request)]})
        {::keys [database-name scope scopes] :as admission}
        (query-admission transport-connection plan)]
    (submit-single!
     runtime request-id owner scope
     (cond->
       {::executor/executor (::executor runtime)
        ::executor/work-class :read
        ::executor/database-name database-name
        ::executor/scope scope
        ::executor/scopes scopes
        ::executor/job-id request-id
        ::executor/request-id request-id
        ::executor/request {::database-name database-name
                            ::scope scope
                            ::scopes scopes
                            ::transport-connection transport-connection
                            ::owner owner
                            ::job-id request-id
                            ::caller-id request-id
                            ::request request}}
       query?
       (assoc ::executor/reserved-work-class :read)
       query?
       (assoc-in [::executor/request ::acquire-query?] true)
       query?
       (assoc-in [::executor/request ::query-plan] admission)))))

(defn- start-execute-many-request!
  [runtime transport-connection request request-id owner]
  (run! validate-read-input! (::protocol/members request))
  (if-let [result-state (execute-many-result-state request)]
    (let [{::keys [database-name scope scopes] :as plan}
          (query-admission transport-connection (execute-many-plan request))
          active (::active-requests runtime)
          resolution-id [request-id :resolve]
          request-bytes
          (locking active
            (when (identical? owner (get-in @active [request-id ::owner]))
              (swap! active update request-id
                     (fn [entry]
                       (merge entry result-state
                              {::execute-many? true
                               ::scope scope
                               ::scopes scopes
                               ::jobs #{resolution-id}
                               ::next-position 0})))
              (get-in @active [request-id ::request-bytes] 0)))]
      (when request-bytes
        (try
          (executor/try-submit!
           {::executor/executor (::executor runtime)
            ::executor/work-class :read
            ::executor/database-name database-name
            ::executor/scope scope
            ::executor/scopes scopes
            ::executor/job-id resolution-id
            ::executor/request-id request-id
            ::executor/request-bytes request-bytes
            ::executor/request
            {::database-name database-name
             ::scope scope
             ::scopes scopes
             ::transport-connection transport-connection
             ::resolve-execute-many? true
             ::execute-many-plan plan}})
          (catch Throwable throwable
            (complete-executor!
             runtime
             {::executor/job-id resolution-id
              ::executor/request-id request-id
              ::executor/outcome [::executor/throwable throwable]})))))
    (deliver-active-request!
     runtime request-id owner
     (protocol/failure
      {::protocol/error-kind protocol/database-error
       ::protocol/error
       "The grouped database result limit cannot hold its bounded response."}))))

(defn- start-transact-request!
  [runtime transport-connection request request-id owner]
  (if-let [{::keys [connection database-name]
            connection-id ::branch/connection-id}
           (connection-for-request transport-connection request)]
    (let [scope (committed-scope database-name connection-id (d/db connection))]
      (submit-single!
       runtime request-id owner scope
       {::executor/executor (::executor runtime)
        ::executor/work-class :mutation
        ::executor/database-name database-name
        ::executor/scope scope
        ::executor/job-id request-id
        ::executor/request-id request-id
        ::executor/request {::request request
                            ::connection connection
                            ::database-name database-name
                            ::branch/connection-id connection-id}}))
    (deliver-active-request!
     runtime request-id owner
     (protocol/failure
      {::protocol/error-kind protocol/not-found-error
       ::protocol/error (str "Unknown database: "
                             (::protocol/database-name request))}))))

(defn- start-knn-request!
  [runtime transport-connection request request-id owner]
  (let [{::keys [database-name scope scopes]}
        (query-admission transport-connection
                         {::routing-descriptors [(:seon.db/db request)]})]
    (submit-single!
     runtime request-id owner scope
     {::executor/executor (::executor runtime)
      ::executor/work-class :provider
      ::executor/database-name database-name
      ::executor/scope scope
      ::executor/scopes scopes
      ::executor/job-id request-id
      ::executor/request-id request-id
      ::executor/request
      {::database-name database-name
       ::scope scope
       ::scopes scopes
       ::transport-connection transport-connection
       ::request request}
      ::executor/reserved-work-class :knn
      ::executor/reserved-request-bytes (* 64 1024)})))

(defn- cancel-active-request!
  [runtime transport-connection request]
  (let [target-request-id (::protocol/target-request-id request)
        active (::active-requests runtime)
        target
        (locking active
          (when-let [entry (get @active target-request-id)]
            (when (identical? transport-connection
                              (::transport-connection entry))
              (swap! active assoc-in [target-request-id ::canceled?] true)
              entry)))
        response
        (cond
          (= (::protocol/request-id request) target-request-id)
          (protocol/failure
           {::protocol/error-kind protocol/protocol-error
            ::protocol/error "A cancel request cannot cancel itself."})

          target
          (handle-cancel runtime request target)

          :else
          (protocol/success
           {::protocol/request-id (::protocol/request-id request)
            ::protocol/target-request-id target-request-id
            ::protocol/canceled? false
            ::protocol/running? false}))]
    (when (::execute-many? target)
      (drive-execute-many! runtime target-request-id (::owner target)))
    response))

(defn- close-transport-connection!
  [runtime transport-connection]
  (let [{:keys [requests acquisitions]}
        (locking (::connection-lock transport-connection)
          (when-not @(::closed? transport-connection)
            (reset! (::closed? transport-connection) true)
            (remove-connection-interests! runtime transport-connection)
            (let [active (::active-requests runtime)
                  requests
                  (locking active
                    (let [owned
                          (into []
                                (keep
                                 (fn [[request-id entry]]
                                   (when (identical?
                                          transport-connection
                                          (::transport-connection entry))
                                     {::protocol/request-id request-id
                                      ::owner (::owner entry)
                                      ::execute-many? (::execute-many? entry)
                                      ::entry entry})))
                                @active)]
                      (doseq [{::protocol/keys [request-id]} owned]
                        (swap! active update request-id assoc
                               ::complete! (fn [_response] nil)
                               ::canceled? true))
                      owned))
                  acquisitions @(::acquisitions transport-connection)]
              (reset! (::acquisitions transport-connection) #{})
              {:requests requests :acquisitions acquisitions})))]
    (doseq [{request-id ::protocol/request-id
             owner ::owner
             execute-many? ::execute-many?
             entry ::entry}
            requests]
      (try
        (handle-cancel
         runtime
         {::protocol/request-id request-id
          ::protocol/target-request-id request-id}
         entry)
        (when execute-many?
          (drive-execute-many! runtime request-id owner))
        (catch Throwable throwable
          (log/error throwable "database connection request cancellation failed"
                     {::protocol/request-id request-id}))))
    (when requests
      (await-active-connection! runtime transport-connection))
    (doseq [[database-name connection-id] acquisitions]
      (try
        (let [result (release-connection-acquisition!
                      runtime transport-connection database-name connection-id)]
          (when-not (::registry/released? result)
            (log/error "database connection acquisition release was unproved"
                       {::protocol/database-name database-name
                        ::protocol/target-connection-id connection-id
                        ::registry/release-error
                        (::registry/release-error result)})))
        (catch Throwable throwable
          (log/error throwable "database connection acquisition release failed"
                    {::protocol/database-name database-name
                      ::protocol/target-connection-id connection-id}))))))

(defn handle-request!
  "Start one request and invoke complete! exactly once after physical completion."
  ([runtime request complete!]
   (handle-request! runtime nil request 0 complete!))
  ([runtime transport-connection request complete!]
   (handle-request! runtime transport-connection request 0 complete!))
  ([runtime transport-connection request frame-bytes complete!]
   (if-not (protocol/valid-request? request)
     (try
       (complete! (handle-request-sync runtime transport-connection request))
       (catch Throwable throwable
         (log/error throwable "invalid database request delivery failed")))
     (let [request-id (::protocol/request-id request)]
       (if-let [owner
                (claim-connection-request!
                 runtime transport-connection request frame-bytes complete!)]
         (try
           (case (::protocol/operation request)
             :seon.db.protocol.operation/acquire-database
             (deliver-active-request!
              runtime request-id owner
              (handle-acquire-database runtime transport-connection request))

             :seon.db.protocol.operation/query
             (start-read-request! runtime transport-connection request
                                  request-id owner)

             :seon.db.protocol.operation/pull
             (start-read-request! runtime transport-connection request
                                  request-id owner)

             :seon.db.protocol.operation/pull-many
             (start-read-request! runtime transport-connection request
                                  request-id owner)

             :seon.db.protocol.operation/schema
             (start-read-request! runtime transport-connection request
                                  request-id owner)

             :seon.db.protocol.operation/index-page
             (start-read-request! runtime transport-connection request
                                  request-id owner)

             :seon.db.protocol.operation/execute-many
             (start-execute-many-request! runtime transport-connection request
                                          request-id owner)

             :seon.db.protocol.operation/transact
             (start-transact-request! runtime transport-connection request
                                      request-id owner)

             :seon.db.protocol.operation/knn-search
             (start-knn-request! runtime transport-connection request
                                 request-id owner)

             :seon.db.protocol.operation/cancel
             (deliver-active-request! runtime request-id owner
                                      (cancel-active-request!
                                       runtime transport-connection request))

             :seon.db.protocol.operation/listen
             (locking (::connection-lock transport-connection)
               (deliver-active-request!
                runtime request-id owner
                (handle-listen! runtime transport-connection request)))

             :seon.db.protocol.operation/unlisten
             (locking (::connection-lock transport-connection)
               (deliver-active-request!
                runtime request-id owner
                (handle-unlisten! runtime transport-connection request)))

             (deliver-active-request!
              runtime request-id owner
              (handle-request-sync runtime transport-connection request)))
           (catch Throwable throwable
             (deliver-active-request! runtime request-id owner
                                      (request-failure-response throwable))))
         (try
           (complete!
            (canonical-response
             request
             (protocol/failure
              {::protocol/error-kind protocol/request-conflict-error
               ::protocol/error "The request id is already active or closed."
               ::protocol/body {::protocol/running? true}})))
           (catch Throwable throwable
             (log/error throwable "duplicate database request delivery failed"
                        {::protocol/request-id request-id}))))))))

(defn handle-request
  "Temporarily adapt callback completion to the blocking request server."
  [runtime request]
  (if (::active-requests runtime)
    (let [response (promise)]
      (handle-request! runtime request #(deliver response %))
      @response)
    (handle-request-sync runtime nil request)))

;;; Explicit server lifecycle

(defn start!
  "Start the addressed request server for one writer runtime."
  {:malli/schema [:=> [:cat ::start-request] ::server]}
  [{::keys [dependencies database-name backend database-path selected-processors
            request-socket-path]}]
  (let [active-requests (atom {})
        interest-state (atom (empty-interest-state))
        interest-lock (Object.)
        runtime-ref (atom nil)
        dispatcher
        (executor/start!
         {::executor/capacity (if selected-processors
                                (executor/capacity selected-processors)
                                (executor/capacity))
          ::executor/execute
          {:read (fn [request] (execute-read! @runtime-ref request))
           :provider (partial execute-provider! dependencies)
           :knn (partial execute-knn! dependencies)
           :delivery (fn [request]
                       (execute-delivery! @runtime-ref request))
           :mutation (fn [request]
                       (execute-mutation! @runtime-ref request))}
          ::executor/complete!
          (fn [completion]
            (complete-executor! @runtime-ref completion))})
        query-jobs (atom {::by-owner {} ::by-job {}})
        runtime (assoc dependencies
                       ::executor dispatcher
                       ::active-requests active-requests
                       ::query-jobs query-jobs
                       ::interest-state interest-state
                       ::interest-lock interest-lock
                       ::readiness-owner (Object.))
        _ (reset! runtime-ref runtime)
        _ (register-readiness! runtime)]
    (try
      (let [ensure-response
            (handle-request
             runtime
             (protocol/ensure-database-request
              (cond->
               {::protocol/database-name database-name
                ::protocol/request-id "writer/start"
                ::protocol/backend backend}
                database-path
                (assoc ::protocol/database-path database-path))))]
        (when-not (::protocol/success? ensure-response)
          (throw
           (ex-info "Initial database ensure failed."
                    {::ensure-response ensure-response})))
        {::request-server
         (uds/start-request-server!
          {::uds/socket-path request-socket-path
           ::uds/open-connection! transport-connection
           ::uds/close-connection!
           (partial close-transport-connection! runtime)
           ::uds/handler (partial handle-request! runtime)})
         ::executor dispatcher
         ::runtime runtime
         ::database-name database-name})
      (catch Throwable throwable
        (let [release
              (registry/release-database!
               {::registry/database-name (keyword database-name)})]
          (unregister-readiness! runtime)
          (executor/stop! {::executor/executor dispatcher})
          (if (::registry/release-error release)
            (throw
             (ex-info "Writer start failed and database release was unproved."
                      {::release-failures [release]
                       ::start-error (.toString throwable)}
                      throwable))
            (throw throwable)))))))

(defn stop!
  "Close one writer server and report every database release."
  {:malli/schema [:=> [:catn [::server ::server]] ::stop-response]}
  [server]
  (let [request-server-shutdown
        (uds/close-request-server! (::request-server server))
        request-server-stopped?
        (every? true?
                ((juxt ::uds/selector-stopped?
                       ::uds/workers-stopped?
                       ::uds/cleanup-stopped?)
                 request-server-shutdown))]
    (if-not request-server-stopped?
      (do
        (log/error "Database request server did not stop; retaining database authority"
                   request-server-shutdown)
        {::stopped? false ::release-results []})
      (do
        (unregister-readiness! (::runtime server))
        (if (seq (::by-scope @(::interest-state (::runtime server))))
          (do
            (log/error "Database interests remained after transport shutdown."
                       {::retained-interests
                        (count (::by-scope
                                @(::interest-state (::runtime server))))})
            {::stopped? false ::release-results []})
          (do
            (executor/stop! {::executor/executor (::executor server)})
            (let [{::registry/keys [databases]} (registry/list-databases {})
                  release-results
                  (mapv
                   (fn [{::registry/keys [database-name connection-id branch-head]}]
                     (merge
                      {::registry/database-name database-name
                       ::registry/connection-id connection-id
                       ::registry/branch-head branch-head}
                      (registry/release-database!
                       {::registry/database-name database-name})))
                   databases)]
              {::stopped? (every? ::registry/released? release-results)
               ::release-results release-results})))))))
