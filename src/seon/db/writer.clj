(ns seon.db.writer
  "Interpret canonical requests at the authoritative Datahike writer.

   This namespace owns database semantics: connection initialization,
   idempotent writes, generated identities, transaction publication, replay,
   and embedding search. `seon.db.transport.uds` owns only delivery. Every
   database-scoped request names its database explicitly; there is no ambient
   connection path."
  (:require [clojure.edn :as edn]
            [clojure.set :as set]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.constants :as datahike.constants]
            [datahike.db.interface :as dbi]
            [konserve.core :as k]
            [seon.db.coordinate :as coordinate]
            [seon.db.datahike.schema :as datahike.schema]
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
(schema/register! ::transaction-transform 'fn?)
(schema/register! ::knn-search 'fn?)
(schema/register! ::publisher :seon.db.transport.uds/publisher)
(schema/register!
 ::dependencies
 [:map
  [::database-initializer ::database-initializer]
  [::transaction-transform ::transaction-transform]
  [::knn-search ::knn-search]])
(schema/register!
 ::runtime
 [:map
  [::database-initializer ::database-initializer]
  [::transaction-transform ::transaction-transform]
  [::knn-search ::knn-search]
  [::publisher ::publisher]])
(schema/register! ::request-server :seon.db.transport.uds/request-server)
(schema/register! ::database-name :seon.db.protocol/database-name)
(schema/register! ::backend :seon.db.protocol/backend)
(schema/register! ::database-path :seon.db.protocol/database-path)
(schema/register! ::request-socket-path :seon.db.transport.uds/socket-path)
(schema/register! ::publish-socket-path :seon.db.transport.uds/socket-path)
(schema/register!
 ::start-request
 [:map
  [::dependencies ::dependencies]
  [::database-name ::database-name]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]
  [::request-socket-path ::request-socket-path]
  [::publish-socket-path ::publish-socket-path]])
(schema/register!
 ::server
 [:map
  [::request-server ::request-server]
  [::publisher ::publisher]
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

(defn- transform-transaction
  [runtime connection transaction-data]
  (try
    ((::transaction-transform runtime)
     (d/db connection)
     (vec transaction-data))
    (catch Throwable throwable
      (log/error throwable
                 "database transaction transform failed; committing primary facts")
      transaction-data)))

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

(defn- transact-once!
  [runtime connection request]
  (locking connection
    (let [transaction-data (::protocol/transaction-data request)
          transaction-meta (::protocol/transaction-meta request)
          expected-coordinate (::protocol/expected-coordinate request)
          request-id (::protocol/request-id request)
          candidates (::protocol/generated-candidates request)
          generated? (contains? request ::protocol/generated-candidates)
          _ (assert-protocol-attributes-free! transaction-data transaction-meta)
          db-value (d/db connection)
          coerced-data
          (coerce-transaction-data (:schema db-value) transaction-data)
          fingerprint (protocol/logical-transaction-hash request)
          recover-current
          (fn []
            (let [db (d/db connection)]
              (when-let [transaction
                         (committed-transaction db request-id)]
                (recover-committed db transaction request-id fingerprint
                                   candidates))))]
      (or
       (recover-current)
       (let [_ (when (and expected-coordinate
                          (not= expected-coordinate
                                (coordinate/resolved db-value)))
                 (throw
                  (ex-info "The database coordinate changed before commit."
                           {::failure-kind
                            protocol/stale-coordinate-error
                            ::protocol/expected-coordinate
                            expected-coordinate
                            ::protocol/current-coordinate
                            (coordinate/resolved db-value)})))
             caller-tempids
             (id/transaction-tempids
              {::id/db-value db-value
               ::id/transaction-data coerced-data})
             transformed-data
             (transform-transaction runtime connection coerced-data)
             data-with-receipts
             (into (vec transformed-data)
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
         (try
           (let [report (d/transact connection transaction)
                 response
                 (response-from-report-data
                  (transaction-report-data report request-id))
                 generated-entity-ids (::id/generated-eids report)]
             (cond-> response
               (some? generated-entity-ids)
               (assoc ::protocol/generated-entity-ids
                      generated-entity-ids)))
           (catch Throwable throwable
             ;; A commit can win before the acknowledgement is lost. The
             ;; durable receipt, not the delivery failure, is authoritative.
             (or (recover-current)
                 (throw throwable)))))))))

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

;;; Original transaction coordinates

(defn- coordinate-resolution-error
  [kind message data]
  (throw (ex-info message (assoc data ::failure-kind kind))))

(defn- resolution-stored-commit
  [store commit-id]
  (retained-stored-commit
   store commit-id
   (fn [missing-id]
     (coordinate-resolution-error
      protocol/unsupported-history-error
      "Transaction-coordinate ancestry is incomplete."
      {::coordinate/commit-id missing-id}))))

(defn- stored-coordinate
  [stored]
  {::coordinate/database-id (get-in stored [:config :store :id])
   ::coordinate/branch (get-in stored [:config :branch])
   ::coordinate/commit-id (get-in stored [:meta :datahike/commit-id])
   ::coordinate/t (:max-tx stored)})

(defn- transaction-origin?
  [store branch transaction stored]
  (when (and (= branch (get-in stored [:config :branch]))
             (= transaction (:max-tx stored)))
    (let [parents (mapv #(resolution-stored-commit store %)
                        (get-in stored [:meta :datahike/parents]))]
      ;; Datahike commits each ordinary transaction once and advances max-tx.
      ;; Branch and force metadata commits can repeat a transaction number,
      ;; but at least one direct parent already carries that same max-tx.
      (not-any? #(= transaction (:max-tx %)) parents))))

(defn- transaction-origin-candidates
  [store head branch transaction]
  (loop [pending [head]
         visited #{}
         candidates []]
    (if-let [stored (first pending)]
      (let [commit-id (get-in stored [:meta :datahike/commit-id])]
        (if (contains? visited commit-id)
          (recur (next pending) visited candidates)
          (let [basis-t (:max-tx stored)
                candidates (cond-> candidates
                             (transaction-origin?
                              store branch transaction stored)
                             (conj (stored-coordinate stored)))
                parents
                (if (>= basis-t transaction)
                  (mapv #(resolution-stored-commit store %)
                        (get-in stored [:meta :datahike/parents]))
                  [])]
            (recur (into (vec (next pending)) parents)
                   (conj visited commit-id)
                   candidates))))
      candidates)))

(defn- handle-resolve-transaction-coordinate
  [connection request]
  (let [current-db (d/db connection)
        current-coordinate (coordinate/resolved current-db)
        head-coordinate (::protocol/head-coordinate request)
        transaction (::protocol/transaction-id request)
        store (:store current-db)]
    (when (false? (get-in current-db [:config :commit-graph?] true))
      (coordinate-resolution-error
       protocol/unsupported-history-error
       "Transaction-coordinate resolution requires retained commit history."
       {::protocol/head-coordinate head-coordinate
        ::protocol/transaction-id transaction}))
    (when-not (and (= :db (::coordinate/branch current-coordinate))
                   (= :db (::coordinate/branch head-coordinate)))
      (coordinate-resolution-error
       protocol/attachment-mismatch-error
       "Restore completion coordinates resolve only on the live :db lineage."
       {::protocol/head-coordinate head-coordinate
        ::protocol/current-coordinate current-coordinate}))
    (when-not (= (coordinate/attachment current-coordinate)
                 (coordinate/attachment head-coordinate))
      (coordinate-resolution-error
       protocol/attachment-mismatch-error
       "The frozen head names a different database attachment."
       {::protocol/head-coordinate head-coordinate
        ::protocol/current-coordinate current-coordinate}))
    (let [head
          (resolution-stored-commit
           store (::coordinate/commit-id head-coordinate))
          resolved-head (stored-coordinate head)]
      (when-not (= head-coordinate resolved-head)
        (coordinate-resolution-error
         protocol/attachment-mismatch-error
         "The retained commit does not resolve the frozen head coordinate."
         {::protocol/head-coordinate head-coordinate
          ::protocol/current-coordinate resolved-head}))
      (when-not (stored-ancestor?
                 store
                 (::coordinate/commit-id current-coordinate)
                 (::coordinate/commit-id head-coordinate)
                 (fn [commit-id]
                   (coordinate-resolution-error
                    protocol/unsupported-history-error
                    "Transaction-coordinate ancestry is incomplete."
                    {::coordinate/commit-id commit-id})))
        (coordinate-resolution-error
         protocol/non-ancestor-error
         "The frozen head is not an ancestor of the current branch head."
         {::protocol/head-coordinate head-coordinate
          ::protocol/current-coordinate current-coordinate}))
      (let [candidates
            (transaction-origin-candidates
             store head (::coordinate/branch head-coordinate) transaction)]
        (case (count candidates)
          1 (protocol/success {::protocol/coordinate (first candidates)})
          0 (coordinate-resolution-error
             protocol/not-found-error
             "No original commit for the transaction is reachable from the frozen head."
             {::protocol/head-coordinate head-coordinate
              ::protocol/transaction-id transaction})
          (coordinate-resolution-error
           protocol/ambiguous-history-error
           "Several original commits match the transaction on this branch."
           {::protocol/head-coordinate head-coordinate
            ::protocol/transaction-id transaction
            ::candidate-coordinates candidates}))))))

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
      ::protocol/branch-coordinates
      (::registry/branch-coordinates observation)
      ::protocol/branch-roster (::registry/branch-roster observation)})))

(defn- handle-release-database
  [request]
  (let [result
        (registry/release-attachment!
         {::registry/target-database-name
          (keyword (::protocol/target-database-name request))
          ::registry/attachment (::protocol/target-attachment request)
          ::registry/expected-target-head
          (::protocol/expected-target-head request)})]
    (protocol/success
     {::protocol/target-database-name
      (::protocol/target-database-name request)
      ::protocol/target-attachment (::registry/attachment result)
      ::protocol/released? (::registry/released? result)})))

(defn- handle-delete-branch
  [request]
  (let [result
        (registry/delete-branch!
         {::registry/source-database-name
          (keyword (::protocol/source-database-name request))
          ::registry/target-database-name
          (keyword (::protocol/target-database-name request))
          ::registry/attachment (::protocol/target-attachment request)
          ::registry/expected-target-head
          (::protocol/expected-target-head request)})]
    (protocol/success
     {::protocol/target-database-name
      (::protocol/target-database-name request)
      ::protocol/target-attachment (::registry/attachment result)
      ::protocol/source-head (::registry/coordinate result)
      ::protocol/released? (::registry/released? result)
      ::protocol/deleted? (::registry/deleted? result)})))

(defn- connection-for-request
  [request]
  (let [database-name (::protocol/database-name request)
        {::registry/keys [conn attachment coordinate]}
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})]
    (when conn
      {::connection conn
       ::database-name database-name
       ::coordinate/attachment attachment
       ::coordinate/coordinate coordinate})))

(defn- generated-candidate-conflict
  [candidate]
  (protocol/failure
   {::protocol/error-kind protocol/generated-candidate-conflict-error
    ::protocol/error "A generated identity candidate is already in use."
    ::protocol/body {::protocol/generated-candidate candidate}}))

(defn- handle-transact
  [runtime connection request]
  (let [candidates (::protocol/generated-candidates request)
        generated? (contains? request ::protocol/generated-candidates)]
    (try
      (when generated?
        (id/assert-allocation-writer! connection))
      (protocol/success (transact-once! runtime connection request))
      (catch Throwable throwable
        (let [failure-kind (::failure-kind (ex-data throwable))]
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
                   {::id/generated-candidates candidates
                    ::id/throwable throwable})]
              (case (::id/error-status classified)
                :seon.db.id/candidate-conflict
                (generated-candidate-conflict
                 (::id/generated-candidate classified))

                :seon.db.id/protocol-error
                (protocol/failure
                 {::protocol/error-kind protocol/protocol-error
                  ::protocol/error (::id/message classified)})

                :seon.db.id/unrelated
                (throw throwable)))

            :else
            (throw throwable)))))))

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

(defn- handle-knn-search
  [runtime connection request]
  (let [search-request
        (cond->
         {:seon.embed/query (::protocol/query request)
          :seon.embed/k (long (::protocol/limit request))}
          (seq (::protocol/entity-ids request))
          (assoc :seon.embed/eids
                 (set (::protocol/entity-ids request))))
        result ((::knn-search runtime) (d/db connection) search-request)]
    (protocol/success
     {::protocol/hits (:seon.embed/hits result)})))

(defn- compact-explanation
  [explanation]
  (when (map? explanation)
    (cond-> (dissoc explanation :value)
      (vector? (:errors explanation))
      (update :errors
              (fn [errors]
                (mapv #(dissoc % :value) errors))))))

(defn- canonical-response
  [response]
  (if (protocol/valid-response? response)
    response
    (let [explanation
          (compact-explanation (protocol/explain-response response))]
      (log/error "database writer constructed an invalid response"
                 {::response-explanation explanation})
      (protocol/failure
       {::protocol/error-kind protocol/internal-error
        ::protocol/error
        "The database writer constructed an invalid response."}))))

(defn handle-request
  "Interpret one complete canonical database protocol request."
  {:malli/schema [:=> [:catn [::runtime ::runtime]
                            [:seon.db.writer/request :map]]
                  :seon.db.protocol/response]}
  [runtime request]
  (canonical-response
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

         :seon.db.protocol.operation/ensure-database
         (handle-ensure-database runtime request)

         :seon.db.protocol.operation/observe-database-lifecycle
         (handle-observe-database-lifecycle request)

         :seon.db.protocol.operation/create-branch
         (handle-create-branch runtime request)

         :seon.db.protocol.operation/release-database
         (handle-release-database request)

         :seon.db.protocol.operation/delete-branch
         (handle-delete-branch request)

         (if-let [{::keys [connection database-name]}
                  (connection-for-request request)]
           (case (::protocol/operation request)
             :seon.db.protocol.operation/transact
             (handle-transact runtime connection request)

             :seon.db.protocol.operation/replay-transactions
             (handle-replay-transactions connection database-name request)

             :seon.db.protocol.operation/resolve-transaction-coordinate
             (handle-resolve-transaction-coordinate connection request)

             :seon.db.protocol.operation/knn-search
             (handle-knn-search runtime connection request))
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

;;; Explicit server lifecycle

(defn start!
  "Start the request and publication sockets for one writer runtime."
  {:malli/schema [:=> [:cat ::start-request] ::server]}
  [{::keys [dependencies database-name backend database-path
            request-socket-path publish-socket-path]}]
  (let [publisher (uds/start-publisher! publish-socket-path)
        runtime (assoc dependencies ::publisher publisher)]
    (try
      (let [ensure-response
            (handle-request
             runtime
             (protocol/ensure-database-request
              (cond->
               {::protocol/database-name database-name
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
           ::uds/handler (partial handle-request runtime)})
         ::publisher publisher
         ::database-name database-name})
      (catch Throwable throwable
        (let [release
              (registry/release-database!
               {::registry/database-name (keyword database-name)})]
          (uds/close-publisher! publisher)
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
  (uds/close-request-server! (::request-server server))
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
     ::release-results release-results}))
