(ns seon.db.writer
  "Interpret canonical requests at the authoritative Datahike writer.

   This namespace owns database semantics: connection initialization,
   idempotent writes, generated identities, transaction publication, replay,
   and embedding search. `seon.db.transport.uds` owns only delivery. Every
   database-scoped request names its database explicitly; there is no ambient
   connection path."
  (:require [clojure.edn :as edn]
            [clojure.string :as str]
            [datahike.api :as d]
            [datahike.constants :as datahike.constants]
            [datahike.db.interface :as dbi]
            [seon.db.coordinate :as coordinate]
            [seon.db.id :as id]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
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
(schema/register! ::stop-response [:map [::stopped? ::stopped?]])
(schema/register! ::database-initialized? :boolean)
(schema/register!
 ::initialize-request
 [:map
  [::runtime ::runtime]
  [::connection ::connection]
  [::database-name ::database-name]])

;;; Datahike values and transaction shapes

(def ^:private schema-properties
  [:db/valueType :db/cardinality :db/unique :db/isComponent])

(def ^:private internal-tempid-prefix "seon.db.protocol.tempid/")

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

(defn- seed-receipt-schema!
  [connection]
  (let [installed (:schema (d/db connection))
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
         protocol/receipt-schema)
        missing (filterv #(not (contains? installed (:db/ident %)))
                         protocol/receipt-schema)]
    (when (seq incompatible)
      (throw
       (ex-info "Database protocol receipt schema is incompatible."
                {::failure-kind protocol/protocol-error
                 ::incompatible-schema (vec incompatible)})))
    (when (seq missing)
      (d/transact connection missing))))

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
     ::protocol/basis-t (basis-t-of db-after)
     ::protocol/basis-t-before (basis-t-of db-before)
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
         ::protocol/basis-t (::protocol/basis-t transaction-data)
         ::protocol/basis-t-before
         (::protocol/basis-t-before transaction-data)
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
  [{::keys [runtime connection database-name]}]
  (seed-receipt-schema! connection)
  (d/listen connection ::transaction-publication
            (transaction-listener runtime database-name))
  ((::database-initializer runtime) connection (keyword database-name))
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
    ::protocol/basis-t (::protocol/basis-t data)
    ::protocol/basis-t-before (::protocol/basis-t-before data)
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
          ::protocol/basis-t transaction
          ::protocol/basis-t-before (dec transaction)
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
          expected-basis-t (::protocol/expected-basis-t request)
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
       (let [caller-tempids
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
               (some? expected-basis-t)
               (assoc :datahike/expected-basis-t expected-basis-t)
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
  [::protocol/since-t :seon.db.protocol/since-t]
  [::protocol/through-t {:optional true} :seon.db.protocol/through-t]
  [::page-size ::page-size]])
(schema/register!
 ::replay-page-response
 [:map
  [::protocol/since-t :seon.db.protocol/since-t]
  [::protocol/through-t :seon.db.protocol/through-t]
  [::protocol/continuation-t :seon.db.protocol/continuation-t]
  [::protocol/complete? :seon.db.protocol/complete?]
  [::protocol/events :seon.db.protocol/events]
  [::protocol/replayed-count :seon.db.protocol/replayed-count]])

(defn- replay-error
  [message data]
  (throw
   (ex-info message (assoc data ::failure-kind protocol/protocol-error))))

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
  [db database-name since-t selected-transaction-ids]
  (let [by-transaction
        (history-by-transaction db since-t selected-transaction-ids)]
    (::events
     (reduce
      (fn [{events ::events previous-basis-t ::previous-basis-t}
           transaction]
        (let [datoms (get by-transaction transaction)]
          (when (empty? datoms)
            (replay-error
             "Replay could not reconstruct a selected transaction."
             {::protocol/basis-t transaction}))
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
                 ::protocol/basis-t transaction
                 ::protocol/basis-t-before previous-basis-t
                 ::protocol/transaction-meta transaction-meta
                 ::protocol/request-id request-id}]
            {::events
             (conj events
                   (transaction-event-from-data database-name data))
             ::previous-basis-t transaction})))
      {::events [] ::previous-basis-t since-t}
      selected-transaction-ids))))

(defn replay-transactions-page
  "Return one bounded page of committed transaction events."
  {:malli/schema [:=> [:cat ::replay-page-request]
                  ::replay-page-response]}
  [{::keys [connection database-name page-size]
    ::protocol/keys [since-t through-t]}]
  (let [db (d/db connection)
        current-t (basis-t-of db)
        since-t (long since-t)
        through-t (if (some? through-t) (long through-t) current-t)]
    (when (> through-t current-t)
      (replay-error "Replay watermark is ahead of the writer."
                    {::protocol/through-t through-t
                     ::current-basis-t current-t}))
    (when (> since-t through-t)
      (replay-error "Replay cursor is ahead of its watermark."
                    {::protocol/since-t since-t
                     ::protocol/through-t through-t}))
    (if (= since-t through-t)
      {::protocol/since-t since-t
       ::protocol/through-t through-t
       ::protocol/continuation-t through-t
       ::protocol/complete? true
       ::protocol/events []
       ::protocol/replayed-count 0}
      (let [candidate-ids
            (page-transaction-ids since-t through-t page-size)
            selected-ids (vec (take page-size candidate-ids))
            more? (> (count candidate-ids) page-size)]
        (when (empty? selected-ids)
          (replay-error "Replay found no transaction before its watermark."
                        {::protocol/since-t since-t
                         ::protocol/through-t through-t}))
        (let [events (replay-events db database-name since-t selected-ids)
              continuation (if more? (peek selected-ids) through-t)]
          {::protocol/since-t since-t
           ::protocol/through-t through-t
           ::protocol/continuation-t continuation
           ::protocol/complete? (not more?)
           ::protocol/events events
           ::protocol/replayed-count (count events)})))))

;;; Canonical operation handlers

(defn- registry-request
  [database-name backend-kind database-path connection-initializer]
  (cond->
   {::registry/database-name (keyword database-name)
    ::registry/backend backend-kind
    ::registry/initialize-connection! connection-initializer}
    database-path (assoc ::registry/path database-path)))

(defn- handle-ensure-database
  [runtime request]
  (let [database-name (::protocol/database-name request)
        entry
        (registry/ensure-database!
         (registry-request
          database-name
          (::protocol/backend request)
          (::protocol/database-path request)
          (fn [connection _database-keyword]
            (initialize-connection!
             {::runtime runtime
              ::connection connection
              ::database-name database-name}))))
        connection (::registry/conn entry)
        backend-kind (::registry/backend entry)
        database-path (::registry/path entry)]
    (protocol/success
     (cond->
       {::protocol/database-name database-name
       ::coordinate/coordinate (coordinate/resolved (d/db connection))
       ::protocol/backend backend-kind}
       database-path
       (assoc ::protocol/database-path database-path)))))

(defn- connection-for-request
  [request]
  (let [database-name (::protocol/database-name request)
        {::registry/keys [conn]}
        (registry/resolve-connection
         {::registry/database-name (keyword database-name)})]
    (when conn
      {::connection conn ::database-name database-name})))

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
        (let [^Throwable cause (loop [^Throwable cause throwable]
                                 (if-let [next-cause (.getCause cause)]
                                   (recur next-cause)
                                   cause))
              cause-data (ex-data cause)
              failure-kind (::failure-kind (ex-data throwable))]
          (cond
            (= :transaction/stale-basis (:error cause-data))
            (protocol/failure
             {::protocol/error-kind protocol/stale-basis-error
              ::protocol/error (.getMessage cause)
              ::protocol/body
              {::protocol/expected-basis-t
               (:datahike/expected-basis-t cause-data)
               ::protocol/current-basis-t
               (:datahike/current-basis-t cause-data)}})

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
       ::protocol/since-t (::protocol/since-t request)
       ::page-size replay-page-size}
       (contains? request ::protocol/through-t)
       (assoc ::protocol/through-t (::protocol/through-t request))))
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

         (if-let [{::keys [connection database-name]}
                  (connection-for-request request)]
           (case (::protocol/operation request)
             :seon.db.protocol.operation/transact
             (handle-transact runtime connection request)

             :seon.db.protocol.operation/replay-transactions
             (handle-replay-transactions connection database-name request)

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
           (or (::failure-kind (ex-data exception)) protocol/database-error)
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
        (try
          (registry/release-database!
           {::registry/database-name (keyword database-name)})
          (catch Throwable _))
        (uds/close-publisher! publisher)
        (throw throwable)))))

(defn stop!
  "Close one writer server and release every registered database."
  {:malli/schema [:=> [:catn [::server ::server]] ::stop-response]}
  [server]
  (uds/close-request-server! (::request-server server))
  (let [{::registry/keys [databases]} (registry/list-databases {})]
    (doseq [{::registry/keys [database-name]} databases]
      (registry/release-database!
       {::registry/database-name database-name})))
  (uds/close-publisher! (::publisher server))
  {::stopped? true})
