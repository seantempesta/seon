(ns seon.db.writer
  "Interpret canonical requests at the authoritative Datahike writer.

   This namespace owns database semantics: connection initialization,
   idempotent writes, generated identities, transaction publication, replay,
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
            [datahike.constants :as datahike.constants]
            [datahike.connector :as datahike.connector]
            [datahike.datom :as datahike.datom]
            [datahike.db.interface :as dbi]
            [datahike.db.utils :as datahike.db]
            [datahike.impl.entity :as datahike.entity]
            [hasch.core :as hasch]
            [konserve.core :as k]
            [seon.db.coordinate :as coordinate]
            [seon.db.datahike.schema :as datahike.schema]
            [seon.db.executor :as executor]
            [seon.db.id :as id]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.restore-admin :as restore-admin]
            [seon.db.transport.uds :as uds]
            [seon.dev.restore :as restore]
            [seon.launch :as launch]
            [seon.schema :as schema]
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
(schema/register! ::interest-state 'some?)
(schema/register! ::readiness-owner 'some?)
(schema/register! ::transport-connection 'map?)
(schema/register! ::publisher :seon.db.transport.uds/publisher)
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
  [::interest-state {:optional true} ::interest-state]
  [::readiness-owner {:optional true} ::readiness-owner]
  [::publisher ::publisher]])
(schema/register! ::request-server :seon.db.transport.uds/request-server)
(schema/register! ::database-name :seon.db.protocol/database-name)
(schema/register! ::backend :seon.db.protocol/backend)
(schema/register! ::database-path :seon.db.protocol/database-path)
(schema/register! ::request-socket-path :seon.db.transport.uds/socket-path)
(schema/register! ::publish-socket-path :seon.db.transport.uds/socket-path)
(schema/register! ::selected-processors [:int {:min 1}])
(schema/register!
 ::start-request
 [:map
  [::dependencies ::dependencies]
  [::database-name ::database-name]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]
  [::selected-processors {:optional true} ::selected-processors]
  [::request-socket-path ::request-socket-path]
  [::publish-socket-path ::publish-socket-path]])
(schema/register!
 ::server
 [:map
  [::request-server ::request-server]
  [::publisher ::publisher]
  [::executor ::executor]
  [::runtime ::runtime]
  [::database-name ::database-name]])
(schema/register! ::stopped? :boolean)
(schema/register! ::release-result :seon.db.protocol/writer-release-result)
(schema/register! ::release-results :seon.db.protocol/writer-release-results)
(schema/register! ::stop-response :seon.db.protocol/writer-stop-response)
(schema/register! ::database-initialized? :boolean)
(schema/register!
 ::initialize-request
 [:map
  [::runtime ::runtime]
  [::registry/conn ::connection]
  [::registry/database-name ::registry/database-name]
  [::registry/attachment ::coordinate/attachment]
  [::registry/open-intent ::registry/open-intent]])

;;; Datahike values and transaction shapes

(def ^:private schema-properties
  [:db/valueType :db/cardinality :db/unique :db/isComponent])

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
                ::registry/pre-restore-main-coordinate
                (::restore-admin/pre-restore-main-coordinate base)
                ::registry/selected-target-coordinate
                (::restore-admin/selected-target-coordinate base)
                ::registry/prepared-target-coordinate
                (::restore/prepared-target-coordinate intent)
                ::registry/undo-coordinate (::restore/undo-coordinate intent)
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
                ::restore-admin/forced-main-coordinate
                (::registry/coordinate result)
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

;;; Transaction report and publication

(defn- transaction-report-data
  [report request-id]
  (let [db-after (:db-after report)
        db-before (:db-before report)
        transaction-data (public-transaction-datoms (:tx-data report))]
    {::protocol/transaction-data
     (transaction-data->protocol transaction-data)
     ::protocol/datoms-added (count (filter :added transaction-data))
     ::protocol/datoms-retracted (count (remove :added transaction-data))
     ::protocol/coordinate (coordinate/resolved db-after)
     ::protocol/previous-coordinate
     (coordinate/at
      {::coordinate/db-value db-after
       ::coordinate/target-t (basis-t-of db-before)})
     ::protocol/temporary-ids
     (into {}
           (remove
            (fn [[tempid _entity]]
              (or (= :db/current-tx tempid)
                  (internal-tempid? tempid))))
           (:tempids report))
     ::protocol/transaction-meta (public-transaction-meta (:tx-meta report))
     ::protocol/request-id request-id}))

(defn- transaction-event-from-data
  [database-name transaction-data]
  (let [event
        {::protocol/event protocol/transaction-event
         ::protocol/database-name database-name
         ::protocol/coordinate (::protocol/coordinate transaction-data)
         ::protocol/previous-coordinate
         (::protocol/previous-coordinate transaction-data)
         ::protocol/transaction-data
         (::protocol/transaction-data transaction-data)
         ::protocol/datoms-added (::protocol/datoms-added transaction-data)
         ::protocol/datoms-retracted
         (::protocol/datoms-retracted transaction-data)}]
    (cond-> event
      (seq (::protocol/transaction-meta transaction-data))
      (assoc ::protocol/transaction-meta
             (::protocol/transaction-meta transaction-data))

      (seq (::protocol/request-id transaction-data))
      (assoc ::protocol/request-id
             (::protocol/request-id transaction-data)))))

(defn- transaction-listener
  [runtime database-name]
  (fn [report]
    (try
      (let [request-id (::protocol/request-id (:tx-meta report))
            data (transaction-report-data report request-id)]
        (uds/publish!
         {::uds/publisher (::publisher runtime)
          ::uds/message (transaction-event-from-data database-name data)}))
      (catch Throwable throwable
        (log/error throwable
                   "database transaction publication failed"
                   {::database-name database-name})))))

(defn initialize-connection!
  "Initialize one database connection from the composed writer runtime."
  {:malli/schema [:=> [:cat ::initialize-request]
                  [:map
                   [::database-initialized? ::database-initialized?]]]}
  [{::keys [runtime]
    connection ::registry/conn
    database-name ::registry/database-name
    open-intent ::registry/open-intent}]
  (assert-protocol-native-schema! (d/db connection))
  (when (= :seon.db.registry.open/branch open-intent)
    (assert-declared-secondary-indices-live! (d/db connection)))
  (d/listen connection ::transaction-publication
            (transaction-listener runtime database-name))
  (when (= :seon.db.registry.open/main open-intent)
    ((::database-initializer runtime) connection database-name))
  {::database-initialized? true})

(defn- committed-scope
  [database-name attachment db-value]
  (when-let [identity (d/committed-value-identity db-value)]
    {::executor/database-name database-name
     ::coordinate/attachment attachment
     ::executor/connection-id (:datahike.value/connection-id identity)
     ::executor/generation (:datahike.value/generation identity)}))

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

(defn- pinned-database
  [{::keys [database-name scope connection]
    attachment ::coordinate/attachment
    request ::request}]
  (let [conn connection
        expected-attachment (::protocol/attachment request)
        expected-coordinate (::protocol/coordinate request)]
    (when-not (and conn (= attachment expected-attachment))
      (throw
       (ex-info "The database attachment is no longer current."
                {::failure-kind protocol/stale-coordinate-error
                 ::protocol/expected-coordinate expected-coordinate})))
    (let [head (d/db conn)
          head-coordinate (coordinate/resolved head)
          db-value
          (if (= expected-coordinate head-coordinate)
            head
            (d/commit-as-db conn
                            (::coordinate/commit-id expected-coordinate)))]
      (when-not (and db-value
                     (= expected-coordinate (coordinate/resolved db-value))
                     (= scope
                        (committed-scope database-name attachment db-value)))
        (throw
         (ex-info "The requested database coordinate is unavailable."
                  {::failure-kind protocol/stale-coordinate-error
                   ::protocol/expected-coordinate expected-coordinate
                   ::protocol/current-coordinate head-coordinate})))
      db-value)))

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
  {::protocol/request-id (::protocol/request-id request)
   ::protocol/database-name (::protocol/database-name request)
   ::protocol/attachment (::protocol/attachment request)
   ::protocol/coordinate (::protocol/coordinate request)})

(def ^:private read-operations
  #{protocol/query-operation
    protocol/pull-operation
    protocol/pull-many-operation
    protocol/schema-operation
    protocol/index-page-operation})

(defn- datom-map
  [^datahike.datom.Datom datom]
  {:seon.db/e (.-e datom)
   :seon.db/a (.-a datom)
   :seon.db/v (.-v datom)
   :seon.db/tx (datahike.datom/datom-tx datom)
   :seon.db/added? (boolean (:added datom))})

(defn- datahike-cursor
  [cursor]
  (when cursor
    [(:seon.db/e cursor)
     (:seon.db/a cursor)
     (:seon.db/v cursor)
     (:seon.db/tx cursor)
     (:seon.db/added? cursor)]))

(defn- index-page
  [db-value request]
  (let [history? (true? (::protocol/history? request))
        index (::protocol/index request)
        direction (::protocol/direction request)
        page
        (try
          (d/index-page
           (if history? (d/history db-value) db-value)
           (cond->
             {:index index
              :components (::protocol/prefix request)
              :direction direction
              :limit (::protocol/limit request)}
             (::protocol/cursor request)
             (assoc :cursor (datahike-cursor (::protocol/cursor request)))
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
              (throw throwable))))
        datoms (mapv datom-map (:datahike.index-page/datoms page))
        next-cursor
        (when (:datahike.index-page/cursor page)
          (merge (datom-map (peek (:datahike.index-page/datoms page)))
                 {::protocol/coordinate (::protocol/coordinate request)
                  ::protocol/index index
                  ::protocol/direction direction
                  ::protocol/history? history?}))]
    (cond-> {::protocol/datoms datoms
             ::protocol/complete? (:datahike.index-page/complete? page)}
      next-cursor (assoc ::protocol/cursor next-cursor))))

(defn- execute-db-read
  [db-value request caller-id]
  (let [options (resource-options request)]
    (case (::protocol/operation request)
      :seon.db.protocol.operation/query
      (update
       (d/q-with-evidence
        (merge
         {:query (::protocol/query-form request)
          :args (into [(if (true? (::protocol/history? request))
                        (d/history db-value)
                        db-value)]
                      (::protocol/arguments request))
          :request-id caller-id}
         options))
       :datahike.query/result materialize-result)

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

(defn- execute-read!
  [{request ::request :as work}]
  (cond
    (::resolve-only? work) (pinned-database work)
    (::database-value work)
    (protocol/success
     (execute-db-read (::database-value work) request (::caller-id work)))
    :else
    (let [db-value (pinned-database work)]
      (try
        (merge (read-response-base request)
               (execute-db-read db-value request (::protocol/request-id request)))
        (finally
          (d/release-materialized-db db-value))))))

(declare connection-for-request)

(defn- scope-for-read
  [transport-connection request]
  (let [database-name (::protocol/database-name request)
        {::keys [connection] :as resolved
         attachment ::coordinate/attachment}
        (connection-for-request transport-connection request)]
    (when (= attachment (::protocol/attachment request))
      (assoc resolved ::scope
             (committed-scope database-name attachment (d/db connection))))))

(defn- member-failure
  [throwable]
  (protocol/failure
   {::protocol/error-kind
    (or (::failure-kind (ex-data throwable)) protocol/database-error)
    ::protocol/error (or (.getMessage ^Throwable throwable)
                         "The database read failed.")}))

(defn- cancel-running-query
  [dispatcher target-request-id]
  (let [deadline (+ (System/nanoTime) 100000000)]
    (loop []
      (let [result (d/cancel-query! target-request-id)]
        (if (or (:datahike.query.cancel/found? result)
                (>= (System/nanoTime) deadline))
          result
          (let [cancel-outcome
                (executor/cancel!
                 {::executor/executor dispatcher
                  ::executor/job-id target-request-id})
                cancellation (::executor/cancellation cancel-outcome)
                protocol-request
                (::request (::executor/request cancel-outcome))]
            (if (and (= :running cancellation)
                     (= protocol/query-operation
                        (::protocol/operation protocol-request)))
              (do (Thread/sleep 1) (recur))
              result)))))))

(defn- cancel-running-queries
  [dispatcher jobs]
  (let [deadline (+ (System/nanoTime) 100000000)]
    (loop [remaining (set jobs)
           outcomes []]
      (if (or (empty? remaining) (>= (System/nanoTime) deadline))
        outcomes
        (let [[remaining outcomes]
              (reduce
               (fn [[pending results] [job-id caller-id :as job]]
                 (let [result (d/cancel-query! caller-id)
                       running?
                       (= :running
                          (::executor/cancellation
                           (executor/cancel!
                            {::executor/executor dispatcher
                             ::executor/job-id job-id})))]
                   [(if (and running?
                             (not (:datahike.query.cancel/found? result)))
                      (conj pending job)
                      pending)
                    (conj results result)]))
               [#{} outcomes]
               remaining)]
          (when (seq remaining) (Thread/sleep 1))
          (recur remaining outcomes))))))

(defn- handle-cancel
  [runtime request]
  (let [target-request-id (::protocol/target-request-id request)
        dispatcher (::executor runtime)
        grouped
        (executor/cancel-request!
         {::executor/executor dispatcher
          ::executor/request-id target-request-id})
        cancel-outcome
        (if (= :not-found (::executor/cancellation grouped))
          (executor/cancel!
           {::executor/executor dispatcher
            ::executor/job-id target-request-id})
          grouped)
        cancellation (::executor/cancellation cancel-outcome)
        protocol-request (::request (::executor/request cancel-outcome))
        query-cancellation
        (cond
          (seq (::executor/requests grouped))
          (cancel-running-queries
           dispatcher
           (keep (fn [internal]
                   (when (= protocol/query-operation
                            (::protocol/operation (::request internal)))
                     [(::job-id internal) (::caller-id internal)]))
                 (::executor/requests grouped)))

          (and (= :running cancellation)
               (= protocol/query-operation
                  (::protocol/operation protocol-request)))
          [(cancel-running-query dispatcher target-request-id)]

          :else [])]
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/target-request-id target-request-id
      ::protocol/canceled?
      (boolean
       (or (= :queued cancellation)
           (::executor/canceled? grouped)
           (some :datahike.query.cancel/detached? query-cancellation)))
      ::protocol/running? (= :running cancellation)})))

(defn- resolve-exact-connection
  [{::keys [database-name scope]}]
  (let [{::registry/keys [conn attachment]}
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})]
    (when (and conn
               (= scope (committed-scope database-name attachment (d/db conn))))
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
  [runtime database-name attachment request-id report]
  (try
    (let [db-after (:db-after report)
          scope (committed-scope database-name attachment db-after)
          entity-ids (embedding-entity-ids report)]
      (submit-embedding! runtime database-name scope request-id entity-ids))
    (catch Throwable throwable
      ;; Derived work admission can be repaired from current hash mismatch and
      ;; therefore must never change the primary transaction result.
      (log/warn throwable "asynchronous embedding admission failed"
                {::database-name database-name
                 ::protocol/request-id request-id}))))

(defn- enqueue-embedding-backfill!
  [runtime database-name attachment connection]
  (try
    (let [db-value (d/db connection)
          scope (committed-scope database-name attachment db-value)
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
    ::protocol/coordinate (::protocol/coordinate data)
    ::protocol/previous-coordinate (::protocol/previous-coordinate data)
    ::protocol/temporary-ids (::protocol/temporary-ids data)
    ::protocol/transaction-data (::protocol/transaction-data data)
    ::protocol/datoms-added (::protocol/datoms-added data)
    ::protocol/datoms-retracted (::protocol/datoms-retracted data)}
    (seq (::protocol/transaction-meta data))
    (assoc ::protocol/transaction-meta (::protocol/transaction-meta data))

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
  [db transaction request-id candidates]
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
        response
        (response-from-report-data
         {::protocol/request-id request-id
          ::protocol/coordinate
          (coordinate/at
           {::coordinate/db-value db
            ::coordinate/target-t transaction})
          ::protocol/previous-coordinate
          (coordinate/at
           {::coordinate/db-value db
            ::coordinate/target-t (dec transaction)})
          ::protocol/temporary-ids
          (recovered-temporary-ids db transaction)
          ::protocol/transaction-data (transaction-data->protocol datoms)
          ::protocol/datoms-added (count (filter :added datoms))
          ::protocol/datoms-retracted (count (remove :added datoms))
          ::protocol/transaction-meta transaction-meta
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
  [db transaction request-id expected-hash candidates]
  (let [actual-hash (::protocol/request-hash (d/entity db transaction))]
    (if (= expected-hash actual-hash)
      (recovered-response db transaction request-id candidates)
      (throw (request-conflict request-id expected-hash actual-hash)))))

(defn- recover-current
  [connection request-id fingerprint candidates]
  (let [db-value (d/db connection)]
    (when-let [transaction (committed-transaction db-value request-id)]
      (recover-committed db-value transaction request-id fingerprint
                         candidates))))

(defn- assert-current-coordinate!
  [db-value expected-coordinate]
  (when (and expected-coordinate
             (not= expected-coordinate (coordinate/resolved db-value)))
    (throw
     (ex-info "The database coordinate changed before commit."
              {::failure-kind protocol/stale-coordinate-error
               ::protocol/expected-coordinate expected-coordinate
               ::protocol/current-coordinate
               (coordinate/resolved db-value)}))))

(defn- prepare-transaction!
  [runtime connection database-name attachment request]
  (let [transaction-data (::protocol/transaction-data request)
        transaction-meta (::protocol/transaction-meta request)
        expected-coordinate (::protocol/expected-coordinate request)
        request-id (::protocol/request-id request)
        candidates (::protocol/generated-candidates request)
        generated? (contains? request ::protocol/generated-candidates)
        fingerprint (protocol/logical-transaction-hash request)
        _ (assert-protocol-attributes-free! transaction-data transaction-meta)]
    (locking connection
      (if-let [response
               (recover-current connection request-id fingerprint candidates)]
        {::response response}
        (let [db-value (d/db connection)
              _ (assert-current-coordinate! db-value expected-coordinate)
              coerced-data
              (coerce-transaction-data (:schema db-value) transaction-data)
              caller-tempids
              (id/transaction-tempids
               {::id/db-value db-value
                ::id/transaction-data coerced-data})
              data-with-receipts
              (into (vec coerced-data)
                    (protocol/tempid-receipts request-id caller-tempids))
              transaction-meta*
              (assoc (or transaction-meta {})
                     ::protocol/request-id request-id
                     ::protocol/request-hash fingerprint
                     ::protocol/version protocol/current-version)
              transaction
              (cond-> {:tx-data data-with-receipts
                       :tx-meta transaction-meta*}
                generated?
                (assoc ::id/generated-candidates candidates))]
          {::transaction-result (d/transact! connection transaction)
           ::request-id request-id
           ::fingerprint fingerprint
           ::candidates candidates
           ::database-name database-name
           ::coordinate/attachment attachment
           ::connection connection
           ::runtime runtime})))))

(defn- finish-transaction!
  [{::keys [runtime connection database-name request-id fingerprint candidates]
    attachment ::coordinate/attachment}
   result]
  (if (instance? Throwable result)
    ;; A commit can win before the acknowledgement is lost. The durable
    ;; receipt, not the delivery failure, is authoritative.
    (if-let [response
             (recover-current connection request-id fingerprint candidates)]
      response
      (throw result))
    (let [response
          (response-from-report-data (transaction-report-data result request-id))
          generated-entity-ids (::id/generated-eids result)]
      (enqueue-embedding! runtime database-name attachment request-id result)
      (cond-> response
        (some? generated-entity-ids)
        (assoc ::protocol/generated-entity-ids generated-entity-ids)))))

(defn- transact-once!
  [runtime connection database-name attachment request]
  (let [{::keys [response transaction-result] :as prepared}
        (prepare-transaction! runtime connection database-name attachment request)]
    (if response
      response
      (finish-transaction!
       prepared
       (try
         @transaction-result
         (catch Throwable throwable throwable))))))

(defn- transact-once-async!
  [runtime connection database-name attachment request]
  (let [{::keys [response transaction-result] :as prepared}
        (prepare-transaction! runtime connection database-name attachment request)]
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

;;; Transaction-history replay

(def ^:private replay-page-size 256)

(schema/register! ::page-size [:int {:min 1}])
(schema/register!
 ::replay-page-request
 [:map
  [::connection ::connection]
  [::database-name ::database-name]
  [::protocol/since-coordinate :seon.db.protocol/since-coordinate]
  [::protocol/through-coordinate
   {:optional true}
   :seon.db.protocol/through-coordinate]
  [::page-size ::page-size]])
(schema/register!
 ::replay-page-response
 [:map
  [::protocol/since-coordinate :seon.db.protocol/since-coordinate]
  [::protocol/through-coordinate :seon.db.protocol/through-coordinate]
  [::protocol/continuation-coordinate
   :seon.db.protocol/continuation-coordinate]
  [::protocol/complete? :seon.db.protocol/complete?]
  [::protocol/events :seon.db.protocol/events]
  [::protocol/replayed-count :seon.db.protocol/replayed-count]])

(defn- replay-error
  [message data]
  (throw
   (ex-info message (assoc data ::failure-kind protocol/protocol-error))))

(defn- replay-coordinate
  [request]
  (try
    (coordinate/at request)
    (catch clojure.lang.ExceptionInfo exception
      (replay-error (.getMessage exception) (ex-data exception)))))

(defn- retained-stored-commit
  [store commit-id missing!]
  (or (k/get store commit-id nil {:sync? true})
      (missing! commit-id)))

(defn- stored-ancestor?
  [store container-id ancestor-id missing!]
  (loop [pending [container-id]
         visited #{}]
    (if-let [commit-id (first pending)]
      (cond
        (= commit-id ancestor-id) true
        (contains? visited commit-id)
        (recur (next pending) visited)
        :else
        (let [stored (retained-stored-commit store commit-id missing!)]
          (recur (concat (next pending)
                         (get-in stored [:meta :datahike/parents]))
                 (conj visited commit-id))))
      false)))

(defn- ancestor-commit?
  "True when `ancestor-id` is reachable from the frozen container commit."
  [container-db ancestor-id]
  (stored-ancestor?
   (:store container-db) (d/commit-id container-db) ancestor-id
   (fn [commit-id]
     (replay-error
      "Replay ancestry contains an unavailable commit."
      {::coordinate/commit-id commit-id}))))

(defn- handle-resolve-transaction-coordinate
  [connection request]
  (protocol/success
   {::protocol/coordinate
    (registry/resolve-transaction-coordinate!
     {::registry/conn connection
      ::registry/main-coordinate (::protocol/head-coordinate request)
      ::registry/transaction-id (::protocol/transaction-id request)})}))

(defn- transaction-id
  [^datahike.datom.Datom datom]
  (Math/abs (long (.-tx datom))))

(defn- page-transaction-ids
  [since-t through-t page-size]
  (->> (range (max (inc since-t) (inc datahike.constants/tx0))
              (inc through-t))
       (take (inc page-size))
       vec))

(defn- history-by-transaction
  [db since-t selected-transaction-ids]
  (let [selected (set selected-transaction-ids)]
    (reduce
     (fn [by-transaction ^datahike.datom.Datom datom]
       (let [transaction (transaction-id datom)]
         (if (contains? selected transaction)
           (update by-transaction transaction (fnil conj []) datom)
           by-transaction)))
     {}
     (-> db d/history (d/since since-t) (d/datoms :eavt)))))

(defn- replay-events
  [db attachment database-name since-t selected-transaction-ids]
  (let [by-transaction
        (history-by-transaction db since-t selected-transaction-ids)]
    (::events
     (reduce
      (fn [{events ::events previous-t ::previous-t}
           transaction]
        (let [datoms (get by-transaction transaction)]
          (when (empty? datoms)
            (replay-error
             "Replay could not reconstruct a selected transaction."
             {::coordinate/t transaction}))
          (let [stored-transaction-meta
                (into {}
                      (map
                       (fn [^datahike.datom.Datom datom]
                         [(.-a datom) (.-v datom)]))
                      (filter
                       (fn [^datahike.datom.Datom datom]
                         (= (long (.-e datom)) transaction))
                       datoms))
                request-id (::protocol/request-id stored-transaction-meta)
                transaction-meta
                (public-transaction-meta stored-transaction-meta)
                data
                {::protocol/transaction-data
                 (transaction-data->protocol
                  (public-transaction-datoms datoms))
                 ::protocol/datoms-added (count (filter :added datoms))
                 ::protocol/datoms-retracted
                 (count (remove :added datoms))
                 ::protocol/coordinate
                 (replay-coordinate
                  {::coordinate/db-value db
                   ::coordinate/attachment attachment
                   ::coordinate/target-t transaction})
                 ::protocol/previous-coordinate
                 (replay-coordinate
                  {::coordinate/db-value db
                   ::coordinate/attachment attachment
                   ::coordinate/target-t previous-t})
                 ::protocol/transaction-meta transaction-meta
                 ::protocol/request-id request-id}]
            {::events
             (conj events
                   (transaction-event-from-data database-name data))
             ::previous-t transaction})))
      {::events [] ::previous-t since-t}
      selected-transaction-ids))))

(defn replay-transactions-page
  "Return one bounded page of committed transaction events."
  {:malli/schema [:=> [:cat ::replay-page-request]
                  ::replay-page-response]}
  [{::keys [connection database-name page-size]
    ::protocol/keys [since-coordinate through-coordinate]}]
  (let [current-db (d/db connection)
        current-coordinate (coordinate/resolved current-db)
        attachment (coordinate/attachment current-coordinate)
        _ (when-not (coordinate/same-attachment?
                     current-coordinate since-coordinate)
            (replay-error "Replay cursor belongs to another attachment."
                          {::protocol/since-coordinate since-coordinate
                           ::protocol/coordinate current-coordinate}))
        frozen-db
        (if through-coordinate
          (do
            (when-not (coordinate/same-attachment?
                       current-coordinate through-coordinate)
              (replay-error "Replay watermark belongs to another attachment."
                            {::protocol/through-coordinate through-coordinate
                             ::protocol/coordinate current-coordinate}))
            (or (d/commit-as-db current-db
                                (::coordinate/commit-id through-coordinate))
                (replay-error "Replay watermark commit is unavailable."
                              {::protocol/through-coordinate
                               through-coordinate})))
          current-db)
        through-t (long (or (::coordinate/t through-coordinate)
                            (::coordinate/t current-coordinate)))
        through-coordinate*
        (replay-coordinate
         {::coordinate/db-value frozen-db
          ::coordinate/attachment attachment
          ::coordinate/target-t through-t})
        _ (when (and through-coordinate
                     (not= through-coordinate through-coordinate*))
            (replay-error "Replay watermark does not match its stored commit."
                          {::protocol/through-coordinate through-coordinate
                           ::protocol/coordinate through-coordinate*}))
        since-t (long (::coordinate/t since-coordinate))
        since-db
        (if (= (::coordinate/commit-id since-coordinate)
               (::coordinate/commit-id through-coordinate*))
          frozen-db
          (or (d/commit-as-db current-db
                              (::coordinate/commit-id since-coordinate))
              (replay-error "Replay cursor commit is unavailable."
                            {::protocol/since-coordinate since-coordinate})))
        verified-since
        (replay-coordinate
         {::coordinate/db-value since-db
          ::coordinate/attachment attachment
          ::coordinate/target-t since-t})
        _ (when-not (= since-coordinate verified-since)
            (replay-error "Replay cursor does not match its stored commit."
                          {::protocol/since-coordinate since-coordinate
                           ::protocol/coordinate verified-since}))
        _ (when-not (ancestor-commit?
                     frozen-db (::coordinate/commit-id since-coordinate))
            (replay-error "Replay cursor is not an ancestor of its watermark."
                          {::protocol/since-coordinate since-coordinate
                           ::protocol/through-coordinate
                           through-coordinate*}))
        since-coordinate*
        (replay-coordinate
         {::coordinate/db-value frozen-db
          ::coordinate/attachment attachment
          ::coordinate/target-t since-t})]
    (when (> since-t through-t)
      (replay-error "Replay cursor is ahead of its watermark."
                    {::protocol/since-coordinate since-coordinate
                     ::protocol/through-coordinate through-coordinate*}))
    (if (= since-t through-t)
      {::protocol/since-coordinate since-coordinate*
       ::protocol/through-coordinate through-coordinate*
       ::protocol/continuation-coordinate through-coordinate*
       ::protocol/complete? true
       ::protocol/events []
       ::protocol/replayed-count 0}
      (let [candidate-ids
            (page-transaction-ids since-t through-t page-size)
            selected-ids (vec (take page-size candidate-ids))
            more? (> (count candidate-ids) page-size)]
        (when (empty? selected-ids)
          (replay-error "Replay found no transaction before its watermark."
                        {::protocol/since-coordinate since-coordinate*
                         ::protocol/through-coordinate through-coordinate*}))
        (let [events (replay-events frozen-db attachment database-name
                                    since-t selected-ids)
              continuation-t (if more? (peek selected-ids) through-t)
              continuation-coordinate
              (replay-coordinate
               {::coordinate/db-value frozen-db
                ::coordinate/attachment attachment
                ::coordinate/target-t continuation-t})]
          {::protocol/since-coordinate since-coordinate*
           ::protocol/through-coordinate through-coordinate*
           ::protocol/continuation-coordinate continuation-coordinate
           ::protocol/complete? (not more?)
           ::protocol/events events
           ::protocol/replayed-count (count events)})))))

;;; Canonical operation handlers

(defn- registry-request
  [database-name backend-kind database-path attachment connection-initializer]
  (cond->
   {::registry/database-name (keyword database-name)
    ::registry/backend backend-kind
    ::registry/initial-tx protocol-native-schema
    ::registry/initialize-connection! connection-initializer}
    attachment (assoc ::registry/attachment attachment)
    database-path (assoc ::registry/path database-path)))

(defn- connection-initializer
  [runtime]
  (fn [initialize-request]
    (initialize-connection!
     (assoc initialize-request ::runtime runtime))))

(defn- handle-ensure-database
  [runtime request]
  (let [database-name (::protocol/database-name request)
        entry
        (registry/ensure-database!
         (registry-request
          database-name
          (::protocol/backend request)
          (::protocol/database-path request)
          (::coordinate/attachment request)
          (connection-initializer runtime)))
        backend-kind (::registry/backend entry)
        database-path (::registry/path entry)]
    (enqueue-embedding-backfill! runtime database-name
                                 (::registry/attachment entry)
                                 (::registry/conn entry))
    (protocol/success
     (cond->
       {::protocol/database-name database-name
       ::coordinate/coordinate (::registry/coordinate entry)
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
          ::registry/source-coordinate (::protocol/source-coordinate request)
          ::registry/expected-source-head
          (::protocol/expected-source-head request)
          ::registry/target-branch (::protocol/target-branch request)
          ::registry/initialize-connection!
          (connection-initializer runtime)})]
    (protocol/success
     (cond-> {::protocol/target-database-name
              (::protocol/target-database-name request)
              ::protocol/target-attachment (::registry/attachment result)
              ::protocol/coordinate (::registry/coordinate result)
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
      ::protocol/main-coordinate (::registry/main-coordinate observation)
      ::protocol/main-parent-commit-ids
      (::registry/main-parent-commit-ids observation)
      ::protocol/branch-coordinates
      (::registry/branch-coordinates observation)
      ::protocol/branch-roster (::registry/branch-roster observation)
      ::protocol/restore-completions
      (::registry/restore-completions observation)
      ::protocol/completed-restore-ids
      (::registry/completed-restore-ids observation)
      ::protocol/restore-completion-coordinates
      (::registry/restore-completion-coordinates observation)})))

(declare await-active-scope!)

(defn- database-scope
  [database-name expected-attachment]
  (let [{::registry/keys [conn attachment]}
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})]
    (when (and conn (= expected-attachment attachment))
      (committed-scope database-name attachment (d/db conn)))))

(defn- drain-database-scope!
  [runtime scope]
  (when-let [dispatcher (::executor runtime)]
    (executor/fence-and-drain!
     {::executor/executor dispatcher
      ::executor/scope scope
      ::executor/cancel d/cancel-query!
      ::executor/abandon-work-classes #{:provider}}))
  (await-active-scope! runtime scope)
  scope)

(defn- fence-database-work!
  [runtime database-name expected-attachment]
  (when-let [scope (database-scope database-name expected-attachment)]
    (drain-database-scope! runtime scope)))

(defn- handle-release-database
  [runtime request]
  (let [database-name (::protocol/target-database-name request)
        attachment (::protocol/target-attachment request)
        scope (database-scope database-name attachment)
        result
        (registry/release-attachment!
         {::registry/target-database-name
          (keyword database-name)
          ::registry/attachment attachment
          ::registry/expected-target-head
          (::protocol/expected-target-head request)
          ::registry/drain!
          (fn [_release]
            (when scope (drain-database-scope! runtime scope)))})]
    (when (and scope (::registry/released? result) (::executor runtime))
      (executor/release-scope!
       {::executor/executor (::executor runtime) ::executor/scope scope}))
    (protocol/success
     {::protocol/target-database-name
      (::protocol/target-database-name request)
      ::protocol/target-attachment (::registry/attachment result)
      ::protocol/released? (::registry/released? result)})))

(defn- handle-delete-branch
  [runtime request]
  (let [database-name (::protocol/target-database-name request)
        attachment (::protocol/target-attachment request)
        scope (database-scope database-name attachment)
        released
        (registry/release-attachment!
         {::registry/target-database-name (keyword database-name)
          ::registry/attachment attachment
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
          ::registry/attachment attachment
          ::registry/expected-target-head
          (::protocol/expected-target-head request)
          ::registry/drain! (fn [_release] nil)})]
    (protocol/success
     {::protocol/target-database-name
      (::protocol/target-database-name request)
      ::protocol/target-attachment (::registry/attachment result)
      ::protocol/source-head (::registry/coordinate result)
      ::protocol/released? (or (::registry/released? released)
                               (::registry/released? result))
      ::protocol/deleted? (::registry/deleted? result)})))

(defn- connection-for-request
  [transport-connection request]
  (let [database-name (::protocol/database-name request)
        {::registry/keys [conn attachment coordinate error-kind error]}
        (registry/resolve-connection
         (cond-> {::registry/database-name (keyword database-name)}
           transport-connection
           (assoc ::registry/transport-connection transport-connection)))]
    (if conn
      {::connection conn
       ::database-name database-name
       ::coordinate/attachment attachment
       ::coordinate/coordinate coordinate}
      (throw
       (ex-info (or error (str "Unknown or unacquired database: " database-name))
                {::failure-kind
                 (if (= :seon.db.registry.error/cleanup-required error-kind)
                   protocol/cleanup-required-error
                   protocol/not-found-error)
                 ::protocol/database-name database-name})))))

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
      (= failure-kind protocol/stale-coordinate-error)
      (protocol/failure
       {::protocol/error-kind protocol/stale-coordinate-error
        ::protocol/error (.getMessage throwable)
        ::protocol/body
        {::protocol/expected-coordinate
         (::protocol/expected-coordinate (ex-data throwable))
         ::protocol/current-coordinate
         (::protocol/current-coordinate (ex-data throwable))}})

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
          (throw throwable)))

      :else
      (throw throwable))))

(defn- transaction-outcome
  [request result]
  (try
    (if (instance? Throwable result)
      (throw result)
      (protocol/success result))
    (catch Throwable throwable
      (transaction-failure request throwable))))

(defn- handle-transact
  [runtime connection database-name attachment request]
  (locking connection
    (try
      (when (contains? request ::protocol/generated-candidates)
        (id/assert-allocation-writer! connection))
      (transaction-outcome
       request
       (transact-once! runtime connection database-name attachment request))
      (catch Throwable throwable
        (transaction-failure request throwable)))))

(defn- handle-transact-async
  [runtime connection database-name attachment request]
  (let [result
        (try
          (when (contains? request ::protocol/generated-candidates)
            (id/assert-allocation-writer! connection))
          (transact-once-async! runtime connection database-name attachment request)
          (catch Throwable throwable throwable))]
    (if (satisfies? async-protocols/ReadPort result)
      (let [completion (async/promise-chan)]
        (async/take! result
                     #(async/put! completion (transaction-outcome request %)))
        completion)
      (transaction-outcome request result))))

(defn- execute-mutation!
  [runtime {::keys [connection database-name]
            attachment ::coordinate/attachment
            request ::request}]
  (handle-transact-async runtime connection database-name attachment request))

(defn- handle-replay-transactions
  [connection database-name request]
  (protocol/success
   (assoc
    (replay-transactions-page
     (cond->
      {::connection connection
       ::database-name database-name
       ::protocol/since-coordinate (::protocol/since-coordinate request)
       ::page-size replay-page-size}
       (contains? request ::protocol/through-coordinate)
       (assoc ::protocol/through-coordinate
              (::protocol/through-coordinate request))))
    ::protocol/database-name database-name)))

(defn- execute-knn-provider!
  [dependencies {request ::request :as work}]
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
  [dependencies {request ::request :as work}]
  (let [db-value (pinned-database work)]
    (try
      (let [rows (or ((::knn dependencies)
                      db-value (::query-vector work)
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
        (d/release-materialized-db db-value)))))

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
  (protocol/failure
   {::protocol/error-kind
    (let [kind (:seon.error/kind (ex-data throwable))]
      (cond
        (::failure-kind (ex-data throwable))
        (::failure-kind (ex-data throwable))

        (or (= protocol/not-found-error kind)
            (contains? protocol/lifecycle-error-kinds kind))
        kind

        (= :seon.db.registry.error/releasing kind)
        protocol/release-error

        :else protocol/database-error))
    ::protocol/error
    (str (.getMessage throwable) " " (pr-str (ex-data throwable)))}))

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
  [runtime transport-connection request connection database-name attachment]
  (let [db-value (d/db connection)
        scope (committed-scope database-name attachment db-value)
        request-id (::protocol/request-id request)
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
                   ::coordinate/attachment attachment
                   ::interest-count 0
                   ::all #{}
                   ::by-attribute {}})
        next-entry (add-interest-to-entry entry reference interest)
        next-state (-> state
                       (assoc-in [::by-scope scope] next-entry)
                       (assoc-in [::by-source source] scope))]
    (swap! (::interests transport-connection) assoc request-id interest)
    (reset! state-atom next-state)
    (coordinate/resolved (d/db connection))))

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
             attachment ::coordinate/attachment}
            (connection-for-request transport-connection request)
            _ (when-not (= attachment (::protocol/attachment request))
                (throw
                 (ex-info "The listen attachment is no longer current."
                          {::failure-kind protocol/stale-coordinate-error})))
            coordinate
            (locking (::interest-lock runtime)
              (install-interest-locked! runtime transport-connection request
                                        connection database-name attachment))]
        (protocol/success
         {::protocol/request-id request-id
          ::protocol/database-name database-name
          ::protocol/attachment attachment
          ::protocol/coordinate coordinate
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
        coordinate (coordinate/resolved (:db-after report))]
    (doseq [[[transport-connection request-id owner] interest]
            (candidate-interests runtime scope datoms)
            :let [matches (matching-datoms interest datoms)]
            :when (seq matches)]
      (send-interest-event!
       transport-connection request-id owner
       {::protocol/event protocol/datoms-event
        ::protocol/request-id request-id
        ::protocol/coordinate coordinate
        ::protocol/datoms matches}))))

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
              coordinate (coordinate/resolved (d/db (::connection entry)))
              references
              (into (::all entry) (mapcat val) (::by-attribute entry))
              next-entry (assoc entry ::source replacement)]
          (reset! state-atom
                  (-> state
                      (assoc-in [::by-scope scope] next-entry)
                      (update ::by-source dissoc source)
                      (assoc-in [::by-source replacement] scope)))
          {::source replacement
           ::protocol/coordinate coordinate
           ::references references})))))

(defn- deliver-resynchronization!
  [runtime scope source]
  (when-let [{::keys [references]
              coordinate ::protocol/coordinate}
             (replace-gapped-source! runtime scope source)]
    (doseq [[transport-connection request-id owner] references]
      (send-interest-event!
       transport-connection request-id owner
       {::protocol/event protocol/resynchronization-event
        ::protocol/request-id request-id
        ::protocol/coordinate coordinate}))))

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
        (when (some (fn [[_ entry]] (= scope (::scope entry))) @active)
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

(defn- release-connection-acquisition!
  [runtime transport-connection database-name attachment]
  (let [scope (database-scope database-name attachment)
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
                         ::protocol/attachment attachment})))
            (reset! drained? true)
            (drain-database-scope! runtime scope))})]
    (when (and @drained? (::registry/released? result) (::executor runtime))
      (executor/release-scope!
       {::executor/executor (::executor runtime) ::executor/scope scope}))
    result))

(defn- handle-acquire-database
  [runtime transport-connection request]
  (when-not transport-connection
    (throw (ex-info "Database acquisition requires a live transport connection."
                    {::protocol/database-name
                     (::protocol/database-name request)})))
  (let [database-name (::protocol/database-name request)
        attachment (::protocol/attachment request)
        result
        (locking (::connection-lock transport-connection)
          (when @(::closed? transport-connection)
            (throw
             (ex-info "The transport connection closed before acquisition."
                      {::protocol/database-name database-name
                       ::protocol/attachment attachment})))
          (let [result
                (try
                  (registry/acquire-database!
                   {::registry/database-name (keyword database-name)
                    ::registry/attachment attachment
                    ::registry/transport-connection transport-connection})
                  (catch clojure.lang.ExceptionInfo exception
                    (if (= :seon.db.registry.error/attachment-conflict
                           (:seon.error/kind (ex-data exception)))
                      (throw
                       (ex-info (.getMessage exception)
                                (assoc (ex-data exception)
                                       ::failure-kind
                                       protocol/attachment-mismatch-error)
                                exception))
                      (throw exception))))]
            (swap! (::acquisitions transport-connection)
                   conj [database-name attachment])
            result))]
    (protocol/success
     {::protocol/database-name database-name
      ::protocol/attachment (::registry/attachment result)
      ::protocol/coordinate (::registry/coordinate result)
      ::protocol/acquired? (::registry/acquired? result)})))

(defn- single-outcome-response
  [request [outcome value]]
  (if (= ::executor/throwable outcome)
    (request-failure-response value)
    (if (read-operations (::protocol/operation request))
      (protocol/success value)
      value)))

(defn- reserve-single-job!
  [runtime request-id owner scope job-id]
  (let [active (::active-requests runtime)]
    (locking active
      (when (identical? owner (get-in @active [request-id ::owner]))
        (let [request-bytes (get-in @active [request-id ::request-bytes] 0)]
        (swap! active update request-id assoc
               ::scope scope ::jobs #{job-id})
          request-bytes)))))

(declare complete-executor! drive-execute-many!)

(defn- submit-execute-many-members!
  [runtime request-id owner submissions]
  (doseq [{job-id ::executor/job-id :as submission} submissions]
    (let [active (::active-requests runtime)
          reserved?
          (locking active
            (and (identical? owner (get-in @active [request-id ::owner]))
                 (contains? (get-in @active [request-id ::jobs]) job-id)))]
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
                (true? (get-in @active [request-id ::canceled?])))
          (executor/cancel!
           {::executor/executor (::executor runtime)
            ::executor/job-id job-id}))))))

(defn- reserve-execute-many-members!
  [runtime request-id owner]
  (let [active (::active-requests runtime)
        dispatcher (::executor runtime)]
    (locking active
      (let [{::keys [request scope database-value next-position jobs canceled?]
             :as entry}
            (get @active request-id)]
        (when (and entry (identical? owner (::owner entry)) database-value
                   (not canceled?) (not (::finishing? entry)))
          (let [members (::protocol/members request)
                member-count (count members)
                active-limit
                (get-in dispatcher
                        [::executor/capacity ::executor/classes :read
                         ::executor/maximum-queued-by-database])
                count-to-reserve
                (min (- active-limit (count jobs))
                     (- member-count next-position))
                positions (range next-position (+ next-position count-to-reserve))
                submissions
                (mapv
                 (fn [position]
                   (let [member (nth members position)
                         job-id [request-id position]
                         caller-id (str "execute-many/"
                                        (hasch/uuid [request-id position]))]
                     {::executor/executor dispatcher
                      ::executor/work-class :read
                      ::executor/database-name (::protocol/database-name request)
                      ::executor/scope scope
                      ::executor/job-id job-id
                      ::executor/request-id request-id
                      ::executor/request
                      {::database-name (::protocol/database-name request)
                       ::scope scope
                       ::database-value database-value
                       ::job-id job-id
                       ::caller-id caller-id
                       ::request member}}))
                 positions)]
            (when (seq submissions)
              (swap! active update request-id
                     (fn [current]
                       (-> current
                           (assoc ::next-position (+ next-position count-to-reserve))
                           (update ::jobs into (map ::executor/job-id submissions))))))
            submissions))))))

(defn- execute-many-canceled-result []
  (member-failure
   (ex-info "The database request was canceled."
            {::failure-kind protocol/database-error})))

(defn- finalize-execute-many!
  [runtime request-id owner]
  (let [active (::active-requests runtime)
        final
        (locking active
          (let [{::keys [request jobs canceled? next-position results]
                 :as entry}
                (get @active request-id)
                member-count (count (::protocol/members request))]
            (when (and entry (identical? owner (::owner entry))
                       (not (::finishing? entry))
                       (empty? jobs)
                       (or canceled? (= next-position member-count)))
              (let [results (if canceled?
                              (mapv #(or % (execute-many-canceled-result)) results)
                              results)
                    response
                    (protocol/success
                     (assoc (read-response-base request)
                            ::protocol/results results))]
                (swap! active assoc-in [request-id ::finishing?] true)
                [entry response]))))]
    (when final
      (let [[entry response] final
            release-error
            (when-let [database-value (::database-value entry)]
              (try
                (d/release-materialized-db database-value)
                nil
                (catch Throwable throwable throwable)))
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
  (when-let [submissions
             (reserve-execute-many-members! runtime request-id owner)]
    (submit-execute-many-members! runtime request-id owner submissions))
  (finalize-execute-many! runtime request-id owner))

(defn- complete-execute-many!
  [runtime request-id owner job-id outcome]
  (let [active (::active-requests runtime)
        resolution? (= job-id [request-id :resolve])]
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
                                 (request-failure-response (second outcome)))))
              (swap! active update request-id
                     #(-> %
                          (update ::jobs disj job-id)
                          (assoc ::database-value (second outcome)))))
            (let [position (second job-id)
                  result (if (= ::executor/throwable (first outcome))
                           (member-failure (second outcome))
                           (second outcome))]
              (swap! active update request-id
                     #(-> %
                          (update ::jobs disj job-id)
                          (assoc-in [::results position] result))))))))
    (if (and resolution? (= ::executor/throwable (first outcome)))
      (let [entry (locking active (get @active request-id))
            response (::failure-response entry)]
        (when response
          (deliver-active-request! runtime request-id owner response)))
      (drive-execute-many! runtime request-id owner))))

(defn- complete-executor!
  [runtime {::executor/keys [request-id job-id outcome]}]
  (if request-id
    (let [active (::active-requests runtime)
          entry (locking active (get @active request-id))]
      (when entry
        (if (::execute-many? entry)
          (complete-execute-many! runtime request-id (::owner entry) job-id outcome)
          (deliver-active-request!
           runtime request-id (::owner entry)
           (single-outcome-response (::request entry) outcome)))))
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
         (let [{::registry/keys [conn database-name attachment coordinate]}
               (registry/resolve-connection
                {::registry/database-name
                 (keyword (::protocol/database-name request))})]
           (if conn
             (protocol/success
              {::protocol/request-id (::protocol/request-id request)
               ::protocol/database-name (name database-name)
               ::protocol/attachment attachment
               ::protocol/coordinate coordinate})
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
         (handle-release-database runtime request)

         :seon.db.protocol.operation/delete-branch
         (handle-delete-branch runtime request)

         (if-let [{::keys [connection database-name]
                   attachment ::coordinate/attachment}
                  (connection-for-request transport-connection request)]
           (case (::protocol/operation request)
             :seon.db.protocol.operation/transact
             (handle-transact runtime connection database-name attachment request)

             :seon.db.protocol.operation/replay-transactions
             (handle-replay-transactions connection database-name request)

             :seon.db.protocol.operation/resolve-transaction-coordinate
             (handle-resolve-transaction-coordinate connection request)

             :seon.db.protocol.operation/knn-search
             (throw (ex-info "KNN search requires callback completion." {})))
           (protocol/failure
            {::protocol/error-kind protocol/not-found-error
             ::protocol/error
             (str "Unknown database: "
                  (::protocol/database-name request))})))
       (catch clojure.lang.ExceptionInfo exception
         (protocol/failure
          {::protocol/error-kind
           (let [kind (:seon.error/kind (ex-data exception))]
             (cond
               (::failure-kind (ex-data exception))
               (::failure-kind (ex-data exception))

               (or (= protocol/not-found-error kind)
                   (contains? protocol/lifecycle-error-kinds kind))
               kind

               :else protocol/database-error))
           ::protocol/error
           (str (.getMessage exception) " " (pr-str (ex-data exception)))}))
       (catch Throwable throwable
         (log/error throwable "database request failed")
         (protocol/failure
          {::protocol/error-kind protocol/internal-error
           ::protocol/error (.toString throwable)}))))))

(defn- stale-coordinate-response
  [request]
  (protocol/failure
   {::protocol/error-kind protocol/stale-coordinate-error
    ::protocol/error "The database attachment is no longer current."
    ::protocol/body {::protocol/request-id (::protocol/request-id request)}}))

(defn- submit-single!
  [runtime request-id owner scope submission]
  (when-let [request-bytes
             (reserve-single-job! runtime request-id owner scope
                                  (::executor/job-id submission))]
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
  (if-let [{::keys [scope connection]
            attachment ::coordinate/attachment}
           (scope-for-read transport-connection request)]
    (submit-single!
     runtime request-id owner scope
     {::executor/executor (::executor runtime)
      ::executor/work-class :read
      ::executor/database-name (::protocol/database-name request)
      ::executor/scope scope
      ::executor/job-id request-id
      ::executor/request-id request-id
      ::executor/request {::database-name (::protocol/database-name request)
                          ::scope scope
                          ::connection connection
                          ::coordinate/attachment attachment
                          ::request request}})
    (deliver-active-request! runtime request-id owner
                             (stale-coordinate-response request))))

(defn- start-execute-many-request!
  [runtime transport-connection request request-id owner]
  (run! validate-read-input! (::protocol/members request))
  (if-let [{::keys [scope connection]
            attachment ::coordinate/attachment}
           (scope-for-read transport-connection request)]
    (let [active (::active-requests runtime)
          resolution-id [request-id :resolve]
          initialized?
          (locking active
            (when (identical? owner (get-in @active [request-id ::owner]))
              (swap! active update request-id assoc
                     ::execute-many? true
                     ::scope scope
                     ::jobs #{resolution-id}
                     ::next-position 0
                     ::results (vec (repeat (count (::protocol/members request))
                                            nil)))
              (get-in @active [request-id ::request-bytes] 0)))]
      (when initialized?
        (try
          (executor/try-submit!
           {::executor/executor (::executor runtime)
            ::executor/work-class :read
            ::executor/database-name (::protocol/database-name request)
            ::executor/scope scope
            ::executor/job-id resolution-id
            ::executor/request-id request-id
            ::executor/request-bytes initialized?
            ::executor/request
            {::database-name (::protocol/database-name request)
             ::scope scope
             ::connection connection
             ::coordinate/attachment attachment
             ::resolve-only? true
             ::request request}})
          (catch Throwable throwable
            (complete-executor!
             runtime
             {::executor/job-id resolution-id
              ::executor/request-id request-id
              ::executor/outcome [::executor/throwable throwable]})))))
    (deliver-active-request! runtime request-id owner
                             (stale-coordinate-response request))))

(defn- start-transact-request!
  [runtime transport-connection request request-id owner]
  (if-let [{::keys [connection database-name]
            attachment ::coordinate/attachment}
           (connection-for-request transport-connection request)]
    (let [scope (committed-scope database-name attachment (d/db connection))]
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
                            ::coordinate/attachment attachment}}))
    (deliver-active-request!
     runtime request-id owner
     (protocol/failure
      {::protocol/error-kind protocol/not-found-error
       ::protocol/error (str "Unknown database: "
                             (::protocol/database-name request))}))))

(defn- start-knn-request!
  [runtime transport-connection request request-id owner]
  (if-let [{::keys [scope connection]
            attachment ::coordinate/attachment}
           (scope-for-read transport-connection request)]
    (submit-single!
     runtime request-id owner scope
     {::executor/executor (::executor runtime)
      ::executor/work-class :provider
      ::executor/database-name (::protocol/database-name request)
      ::executor/scope scope
      ::executor/job-id request-id
      ::executor/request-id request-id
      ::executor/request
      {::database-name (::protocol/database-name request)
       ::scope scope
       ::connection connection
       ::coordinate/attachment attachment
       ::request request}
      ::executor/reserved-work-class :knn
      ::executor/reserved-request-bytes (* 64 1024)})
    (deliver-active-request! runtime request-id owner
                             (stale-coordinate-response request))))

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
          (handle-cancel runtime request)

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
                                      ::execute-many? (::execute-many? entry)})))
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
             execute-many? ::execute-many?}
            requests]
      (try
        (handle-cancel
         runtime
         {::protocol/request-id request-id
          ::protocol/target-request-id request-id})
        (when execute-many?
          (drive-execute-many! runtime request-id owner))
        (catch Throwable throwable
          (log/error throwable "database connection request cancellation failed"
                     {::protocol/request-id request-id}))))
    (when requests
      (await-active-connection! runtime transport-connection))
    (doseq [[database-name attachment] acquisitions]
      (try
        (let [result (release-connection-acquisition!
                      runtime transport-connection database-name attachment)]
          (when-not (::registry/released? result)
            (log/error "database connection acquisition release was unproved"
                       {::protocol/database-name database-name
                        ::protocol/attachment attachment
                        ::registry/release-error
                        (::registry/release-error result)})))
        (catch Throwable throwable
          (log/error throwable "database connection acquisition release failed"
                     {::protocol/database-name database-name
                      ::protocol/attachment attachment}))))))

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
               ::protocol/error "The request id is already active or closed."})))
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
  "Start the request and publication sockets for one writer runtime."
  {:malli/schema [:=> [:cat ::start-request] ::server]}
  [{::keys [dependencies database-name backend database-path selected-processors
            request-socket-path publish-socket-path]}]
  (let [publisher (uds/start-publisher! publish-socket-path)
        active-requests (atom {})
        interest-state (atom (empty-interest-state))
        interest-lock (Object.)
        runtime-ref (atom nil)
        dispatcher
        (executor/start!
         {::executor/capacity (if selected-processors
                                (executor/capacity selected-processors)
                                (executor/capacity))
          ::executor/execute
          {:read execute-read!
           :provider (partial execute-provider! dependencies)
           :knn (partial execute-knn! dependencies)
           :delivery (fn [request]
                       (execute-delivery! @runtime-ref request))
           :mutation (fn [request]
                       (execute-mutation! @runtime-ref request))}
          ::executor/complete!
          (fn [completion]
            (complete-executor! @runtime-ref completion))})
        runtime (assoc dependencies
                       ::publisher publisher
                       ::executor dispatcher
                       ::active-requests active-requests
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
         ::publisher publisher
         ::executor dispatcher
         ::runtime runtime
         ::database-name database-name})
      (catch Throwable throwable
        (let [release
              (registry/release-database!
               {::registry/database-name (keyword database-name)})]
          (unregister-readiness! runtime)
          (uds/close-publisher! publisher)
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
                   (fn [{::registry/keys [database-name attachment coordinate]}]
                     (merge
                      {::registry/database-name database-name
                       ::registry/attachment attachment
                       ::registry/coordinate coordinate}
                      (registry/release-database!
                       {::registry/database-name database-name})))
                   databases)]
              (uds/close-publisher! (::publisher server))
              {::stopped? (every? ::registry/released? release-results)
               ::release-results release-results})))))))
