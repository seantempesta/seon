(ns seon.db
  "The portable database capability API.

   Platform leaves own sessions and ambient invocation context. This namespace
   owns the one public call shape, request/response data, validation, decoding,
   and effect classification shared by the Bun pod and JVM host."
  #?(:clj (:refer-clojure :exclude [await]))
  (:require
   #?(:clj [clojure.edn :as reader]
      :cljs [cljs.reader :as reader])
   [datahike.pull-api :as datahike.pull]
   [datahike.query :as datahike.query]
   [seon.db.branch :as db.branch]
   [seon.db.id.schema]
   [seon.db.internal :as internal]
   [seon.db.leaf :as leaf]
   [seon.db.protocol :as protocol]
   [seon.effect :as effect]
   [seon.error :as error]
   [seon.schema :as schema]
   #?(:cljs [seon.db.session :as session])))

#?(:clj (defmacro await [value] value))

;;; Public data contracts

(schema/register! ::tx-data [:vector :any])
(schema/register! ::opts :map)
(schema/register! ::tx-meta :map)
(schema/register! :seon.capability/op-id [:string {:min 1}])
(schema/register! :seon.capability/replayed? :boolean)
(schema/register!
 ::error
 [:map
  [:seon.error/message :string]
  [:seon.error/kind :keyword]
  [:seon.error/data {:optional true} :map]])
(schema/register! ::branch-head :seon.db.branch/head)
(schema/register! ::query-form [:or [:vector :any] :map :string])
(schema/register! ::query :any)
(schema/register! ::args [:vector :any])
(schema/register! ::pull-pattern [:vector :any])
(schema/register! ::refs [:vector :any])
(schema/register!
 ::read-attribute-dependencies-request
 [:or
  [:map {:closed true}
   [::query ::query-form]
   [::args {:optional true} ::args]]
  [:map {:closed true}
   [::pull-pattern ::pull-pattern]
   [::refs {:optional true} ::refs]]])
(schema/register! ::max-work [:int {:min 1}])
(schema/register! ::max-results [:int {:min 1}])
(schema/register! ::max-result-weight [:int {:min 1}])
(schema/register! ::members [:vector {:min 1 :max 64} :map])
(schema/register! ::results :seon.db.protocol/results)
(schema/register! ::index [:enum :eavt :aevt :avet])
(schema/register! ::components [:vector :any])
(schema/register! ::limit [:int {:min 1 :max 200}])
(schema/register! ::direction :seon.db.protocol/direction)
(schema/register! ::cursor :seon.db.protocol/cursor)
(schema/register! ::installed-schema :seon.db.protocol/schema)
(schema/register! ::user :seon.db/ref)
(schema/register! ::process :seon.db/ref)
(schema/register! ::key :any)
(schema/register! ::handler 'fn?)
(schema/register! ::datom-patterns :seon.db.protocol/datom-patterns)
(schema/register! ::socket-path :seon.db.transport.uds/socket-path)
(schema/register! ::database-name :seon.db.protocol/database-name)
(schema/register! ::database-advanced? :boolean)
(schema/register! ::history? :boolean)
(schema/register! ::backend :seon.db.protocol/backend)
(schema/register! ::database-path :seon.db.protocol/database-path)
(schema/register! ::capabilities :seon.db.protocol/capabilities)
(schema/register! ::session :seon.db.transport.uds/session)
(schema/register! ::databases [:map-of ::database-name :seon.db/db])
(schema/register! ::thunk 'fn?)
(schema/register! ::tx-context :map)
(schema/register! ::managed-scope [:set :qualified-keyword])
(schema/register! ::managed-identity-attrs [:set :qualified-keyword])
(schema/register!
 ::read-evidence-result
 [:map {:closed true}
  [::value :any]
  [::read-evidence ::read-evidence]])

(schema/register!
 ::transact-request
 [:map {:closed true}
  [::tx-data ::tx-data]
  [::db {:optional true} :seon.db/db]
  [::expected-db {:optional true} :seon.db/expected-db]
  [::tx-meta {:optional true} ::tx-meta]
  [::opts {:optional true} ::opts]
  [:seon.capability/op-id {:optional true} :seon.capability/op-id]
  [:seon.db.id/generated-candidates {:optional true}
   :seon.db.id/generated-candidates]])
(schema/register!
 ::transaction-report
 [:map {:closed true}
  [:db-before :db-before]
  [:db-after :db-after]
  [:tx-data :tx-data]
  [:tempids :tempids]
  [:tx-meta :tx-meta]
  [:seon.capability/op-id :seon.capability/op-id]
  [:seon.db.id/eids {:optional true} :seon.db.id/eids]
  [:seon.db.id/recovered-commit?
   {:optional true} :seon.db.id/recovered-commit?]
  [:seon.capability/replayed? {:optional true} :seon.capability/replayed?]])
(schema/register! ::transact-response [:or ::transaction-report ::error])
(schema/register!
 ::query-request
 [:map {:closed true}
  [::query ::query-form]
  [::db {:optional true} :seon.db/db]
  [::args {:optional true} ::args]
  [::max-work {:optional true} ::max-work]
  [::max-results {:optional true} ::max-results]
  [::max-result-weight {:optional true} ::max-result-weight]])
(schema/register!
 ::pull-request
 [:map {:closed true}
  [::pull-pattern ::pull-pattern]
  [::ref :seon.db/ref]
  [::db {:optional true} :seon.db/db]
  [::max-work {:optional true} ::max-work]
  [::max-results {:optional true} ::max-results]
  [::max-result-weight {:optional true} ::max-result-weight]])
(schema/register!
 ::pull-many-request
 [:map {:closed true}
  [::pull-pattern ::pull-pattern]
  [::refs ::refs]
  [::db {:optional true} :seon.db/db]
  [::max-work {:optional true} ::max-work]
  [::max-results {:optional true} ::max-results]
  [::max-result-weight {:optional true} ::max-result-weight]])
(schema/register!
 ::entity-request
 [:map {:closed true}
  [::ref :seon.db/ref]
  [::db {:optional true} :seon.db/db]
  [::max-work {:optional true} ::max-work]
  [::max-results {:optional true} ::max-results]
  [::max-result-weight {:optional true} ::max-result-weight]])
(schema/register!
 ::execute-many-request
 [:map {:closed true}
  [::members ::members]
  [::db {:optional true} :seon.db/db]
  [::max-result-weight {:optional true} ::max-result-weight]])
(schema/register!
 ::index-page-request
 [:map {:closed true}
  [::index ::index]
  [::components {:optional true} ::components]
  [::direction ::direction]
  [::limit ::limit]
  [::cursor {:optional true} ::cursor]
  [::max-result-weight {:optional true} ::max-result-weight]
  [::db {:optional true} :seon.db/db]])
(schema/register!
 ::knn-search-request
 [:map {:closed true}
  [::protocol/query ::protocol/query]
  [::protocol/limit ::protocol/limit]
  [::protocol/entity-ids {:optional true} ::protocol/entity-ids]
  [::db {:optional true} :seon.db/db]])
(schema/register!
 ::listen-request
 [:map {:closed true}
  [::handler ::handler]
  [::db {:optional true} :seon.db/db]
  [::key {:optional true} ::key]
  [::query {:optional true} ::query-form]
  [::dependency-plan {:optional true} :datahike.read/dependency-plan]
  [::datom-patterns {:optional true} ::datom-patterns]])
(schema/register! ::unlisten-request [:map {:closed true} [::key ::key]])
(schema/register! ::unlisten-response :boolean)
(schema/register!
 ::open-session-request
 [:map {:closed true}
  [::socket-path ::socket-path]
  [::database-name ::database-name]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]
  [::initialization {:optional true} ::initialization]
  [::database-advanced? {:optional true} ::database-advanced?]
  [::connection-id {:optional true} :seon.db.branch/connection-id]])
(schema/register!
 ::open-session-response
  [:map {:closed true}
  [::database-name ::database-name]
  [::db :seon.db/db]
  [::capabilities ::capabilities]])


;;; Portable leaf binding and response policy

(def ^:dynamic *leaf* #?(:cljs session/leaf :clj nil))

(declare current-agent-id db as-of since history cas-assert transact! query
         query-with-evidence read-attribute-dependencies pull pull-many entity
         installed-schema execute-many index-page)

(defn bind-leaf
  "Return agent-facing database functions closed over one platform leaf."
  [platform-leaf]
  (into {}
        (map (fn [v] [(symbol (name (:name (meta v))))
                      (with-meta
                        (fn [& args]
                          (binding [*leaf* platform-leaf] (apply @v args)))
                        (meta v))])
             [#'current-agent-id #'db #'as-of #'since #'history #'cas-assert
              #'transact! #'query #'query-with-evidence
              #'read-attribute-dependencies #'pull #'pull-many #'entity
              #'installed-schema #'execute-many #'index-page])))

(defn- leaf-fn [key]
  (or (get *leaf* key)
      (fn [& _] {:seon.error/message "No database platform leaf is bound."
                 :seon.error/kind :configuration
                 :seon.error/data {:seon.db.leaf/key key}})))

(defn- context-fn [key]
  (or (get-in *leaf* [::leaf/context key]) (constantly nil)))

(defn- session-error [message data]
  {:seon.error/message message :seon.error/kind :core-bug :seon.error/data data})
(defn- error-value? [value]
  (and (map? value) (string? (:seon.error/message value))))
(defn- db-value? [value] (protocol/database-value? value))
(declare decode-read-result)
(defn- transport-failure-data [value]
  (get-in value [:seon.error/data :seon.error/ex-data]))
(defn recoverable-transaction-delivery?
  "True when an ambiguous transaction delivery should retain its identity."
  [value]
  (let [failure-data (transport-failure-data value)
        transport-failure (:seon.db.transport.uds/failure failure-data)]
    (or (and (contains? #{:seon.db.transport.uds.failure/busy
                          :seon.db.transport.uds.failure/closed
                          :seon.db.transport.uds.failure/write
                          :seon.db.transport.uds.failure/timeout}
                        transport-failure)
             (not (:seon.db.transport.uds/closed-by-owner? failure-data)))
        (and (false? (::protocol/success? value))
             (or (true? (::protocol/canceled? value))
                 (and (= protocol/request-conflict-error
                         (::protocol/error-kind value))
                      (true? (::protocol/running? value))))))))

(defn- response-error [response]
  ((leaf-fn ::leaf/cache-db!) (:seon.db/current-db response))
  {:seon.error/message (::protocol/error response)
   :seon.error/kind (or (:seon.error/kind response) :core-bug)
   :seon.error/data (select-keys response
                                 [::protocol/error-kind ::protocol/request-id
                                  :seon.db/expected-db :seon.db/current-db
                                  ::protocol/generated-candidate
                                  ::protocol/canceled? ::protocol/running?])})
(defn- send-request! [request timeout-ms]
  ((leaf-fn ::leaf/call!) request timeout-ms))
(defn- ^:async read-db! [request]
  (await ((leaf-fn ::leaf/read-db!) request)))
(defn- ^:async request-db! [request]
  (await ((leaf-fn ::leaf/request-db!) request)))
(defn current-tx-context [] ((context-fn ::leaf/current-tx-context)))
(defn ^{:seon.capability/effect :pure} current-agent-id
  "Return the current invocation's agent id."
  {:malli/schema [:=> [:cat] [:or :nil :string]]}
  [] ((context-fn ::leaf/current-agent-id)))
(defn with-read-evidence [f] ((context-fn ::leaf/with-read-evidence) f))
(defn with-agent [agent-id f] ((context-fn ::leaf/with-agent) agent-id f))
(defn without-agent [f] ((context-fn ::leaf/without-agent) f))
(defn with-tx-context [context f] ((context-fn ::leaf/with-tx-context) context f))
(defn ^:no-doc install-configuration-context! [configuration]
  ((context-fn ::leaf/install-configuration-context!) configuration))
(defn- read-attribution []
  (internal/selected-provenance (or (current-tx-context) {}) (current-agent-id)))
(defn- read-resource-options [policy request]
  ((leaf-fn ::leaf/resource-options) policy request))

(defn- transaction-schema-projection [projection tx-data]
  (let [declared
        (into {}
              (keep (fn [entity]
                      (when (and (map? entity)
                                 (keyword? (:seon.schema/key entity))
                                 (string? (:seon.schema/form entity)))
                        [(:seon.schema/key entity)
                         (reader/read-string (:seon.schema/form entity))])))
              tx-data)]
    (if (seq declared)
      (schema/build-projection
       (merge (or (:seon.schema.projection/forms projection)
                  (schema/registered-schemas))
              declared)
       (or (:seon.schema.projection/function-contracts projection) {}))
      projection)))

(defn- declares-provenance-schema? [tx-data]
  (let [declared (into #{} (keep :seon.schema/key) tx-data)]
    (every? declared internal/tx-meta-attrs)))

(defn open-session! [request] #?(:cljs (session/open-session! request) :clj (session-error "Sessions are pod-only." {})))
(defn close-session! [] #?(:cljs (session/close-session!) :clj false))
(defn attached? [] #?(:cljs (session/attached?) :clj (some? *leaf*)))

(defn- query-source-database
  [request argument-position]
  (if-let [database (::db request)]
    (if (zero? argument-position)
      database
      (nth (::protocol/arguments request) (dec argument-position) nil))
    (nth (::protocol/arguments request) argument-position nil)))

(defn- record-query-evidence! [request response]
  (let [plan (:datahike.read/dependency-plan response)
        positions
        (if (= :all plan)
          (cond->
            (into []
                  (keep-indexed
                   (fn [position argument]
                     (when (db-value? argument) position)))
                  (::protocol/arguments request))
            (::db request) (conj 0))
          (into []
                (map :datahike.query.source/argument-position)
                (:datahike.query.dependency/sources plan)))]
    (doseq [position (distinct positions)
            :let [database (query-source-database request position)]
            :when (db-value? database)]
      ((context-fn ::leaf/record-read-evidence!)
       {::db database
        ::source-argument-position position
        :datahike.read/dependency-plan plan}))))

(defn- record-primary-read-evidence! [request response]
  (when-let [database (::db request)]
    ((context-fn ::leaf/record-read-evidence!)
     {::db database
      ::source-argument-position 0
      :datahike.read/dependency-plan
      (:datahike.read/dependency-plan response)})))


;;; Fiber-local attribution and immutable database values

(defn ^{:async #?(:cljs true :clj false) :seon.capability/effect :read} db
  "Return the current or named immutable database value."
  {:malli/schema [:function [:=> [:cat] [:or :seon.db/db ::error]]
                  [:=> [:cat [:map {:closed true}
                              [::database-name {:optional true} ::database-name]]]
                   [:or :seon.db/db ::error]]]}
  ([] (await ((leaf-fn ::leaf/resolve-db!) nil false)))
  ([request]
   (await ((leaf-fn ::leaf/resolve-db!) (::database-name request) true))))

(defn ^{:seon.capability/effect :pure} as-of
  "Return a database value containing facts through `point`."
  {:malli/schema
   [:=> [:catn [::db :seon.db/db] [::point [:or :int :inst]]] :seon.db/db]}
  [database point]
  (assoc database :as-of point :since nil))

(defn ^{:seon.capability/effect :pure} since
  "Return a database value containing facts added after `point`."
  {:malli/schema
   [:=> [:catn [::db :seon.db/db] [::point [:or :int :inst]]] :seon.db/db]}
  [database point]
  (assoc database :as-of nil :since point))

(defn ^{:seon.capability/effect :pure} history
  "Return a database value containing assertions and retractions."
  {:malli/schema [:=> [:catn [::db :seon.db/db]] :seon.db/db]}
  [database]
  (assoc database :history true))

(defn ^{:seon.capability/effect :pure} cas-assert
  "Return transaction data asserting an unchanged attribute value."
  {:malli/schema [:=> [:catn [::ref :seon.db/ref]
                       [::attr :qualified-keyword] [::value :any]]
                  [:tuple [:= :db.fn/cas] :seon.db/ref
                   :qualified-keyword :any :any]]}
  [ref attr value]
  [:db.fn/cas ref attr value value])

;;; Writes

(defn- ^:async submit-transaction! [arg tx-data tx-meta]
  (let [database (await (read-db! arg))]
    (if (error-value? database)
      database
      ;; Replay identity precedence: an explicit caller identity, then the
      ;; executing form's derived identity (crash re-execution derives the
      ;; SAME one, so the writer replays), then a fresh uuid for a
      ;; system-side caller with no replay coordinates.
      (let [op-id (or (:seon.capability/op-id arg)
                      (effect/next-op-id!)
                      ((leaf-fn ::leaf/uuid)))
            request (protocol/transaction-request
                     (cond-> {::protocol/request-id op-id ::db database
                              ::protocol/transaction-data
                              tx-data}
                       (::expected-db arg) (assoc ::expected-db (::expected-db arg))
                       (seq tx-meta) (assoc ::protocol/transaction-meta tx-meta)
                       (contains? arg :seon.db.id/generated-candidates)
                       (assoc ::protocol/generated-candidates
                              (:seon.db.id/generated-candidates arg))))
            response (await
                      ((leaf-fn ::leaf/transaction-call!)
                       request recoverable-transaction-delivery?))]
        (cond
          (error-value? response) response
          (not (::protocol/success? response)) (response-error response)
          :else (let [report (cond-> (assoc (select-keys response
                                                          [:db-before :db-after
                                                           :tx-data :tempids
                                                           :tx-meta])
                                            :seon.capability/op-id op-id)
                              (seq (::protocol/generated-entity-ids response))
                              (assoc :seon.db.id/eids
                                     (::protocol/generated-entity-ids response))
                              (::protocol/recovered? response)
                              (assoc :seon.db.id/recovered-commit? true
                                     :seon.capability/replayed? true))]
                  ((leaf-fn ::leaf/cache-db!) (:db-after report))
                  ((leaf-fn ::leaf/on-commit!) report)
                  report))))))

(defn ^{:async #?(:cljs true :clj false) :seon.capability/effect :idempotent} transact!
  "Commit ordinary transaction data through the authoritative writer."
  {:malli/schema
   [:function
    [:=> [:catn [::request [:or ::transact-request ::tx-data]]]
     ::transact-response]
    [:=> [:catn [::db :seon.db/db] [::tx-data ::tx-data]]
     ::transact-response]]}
  [& call-args]
  (try
    (let [arg (cond
                (and (= 1 (count call-args))
                     (map? (first call-args))
                     (contains? (first call-args) ::tx-data))
                (first call-args)
                (= 1 (count call-args)) {::tx-data (vec (first call-args))}
                (and (= 2 (count call-args)) (db-value? (first call-args)))
                {::db (first call-args) ::tx-data (vec (second call-args))}
                :else
                (throw (ex-info "`seon.db/transact!` expects transaction data or a database value and transaction data."
                                {:seon.error/kind :user-input})))
          _ (internal/assert-invocation-shape! arg)
          _ (when-not (schema/valid-candidate-value? ::transact-request arg)
              (throw
               (ex-info
                "`seon.db/transact!` received an invalid public request map. Internal transport request identities are not public options; use `:seon.capability/op-id` for replay."
                {:seon.error/kind :user-input
                 ::actual-value arg
                 ::expected-schema ::transact-request})))
          projection (transaction-schema-projection
                      ((context-fn ::leaf/schema-projection))
                      (::tx-data arg))]
      (await
       (internal/with-schema-projection
        projection
        (fn ^:async submit-with-projection! []
          (let [tx-data (-> (::tx-data arg)
                            internal/coerce-identity-symbol-idents
                            internal/normalize-entity-ref-keys
                            internal/omit-nil-entity-values
                            (cond-> (declares-provenance-schema?
                                     (::tx-data arg))
                              (into (conj (internal/tx-meta-datahike-schema)
                                          {:seon.user/id "user"}))))
                storage-tx-data (internal/encode-edn-slot-values tx-data)
                opts (if (declares-provenance-schema? tx-data)
                       (cond-> (or (::opts arg) {})
                         (::tx-meta arg) (assoc :tx-meta (::tx-meta arg)))
                       (internal/merge-tx-context-into-opts
                        (cond-> (::opts arg)
                          (::tx-meta arg) (assoc :tx-meta (::tx-meta arg)))
                        (current-tx-context) (current-agent-id)))
                tx-meta (:tx-meta opts)
                attrs (into (internal/extract-tx-attrs storage-tx-data)
                            (keys tx-meta))
                validate? (not= false
                                ((context-fn ::leaf/schema-validation?)))]
            (when validate?
              (internal/validate-attrs! attrs)
              (internal/validate-values! storage-tx-data)
              (internal/validate-values! [tx-meta]))
            (let [report (await (submit-transaction! arg storage-tx-data tx-meta))]
              (when (and projection (not (error-value? report)))
                ((context-fn ::leaf/cache-schema-projection!) projection))
              report))))))
    (catch #?(:clj Throwable :cljs :default) exception
      (let [value (error/->map exception)]
        (cond-> value
          (nil? (:seon.error/kind value))
          (assoc :seon.error/kind :core-bug))))))

;;; Reads over one immutable database value

(defn- explicit-query-source? [arguments]
  (some db-value? arguments))

(def ^:private unaligned-dependency-arguments
  ::unaligned-dependency-arguments)

(defn- aligned-dependency-arguments
  [query-form arguments]
  (try
    (let [arguments (vec (or arguments []))
          input-count (datahike.query/query-input-count query-form)
          source-bindings (datahike.query/query-source-bindings query-form)]
      (cond
        (= input-count (count arguments))
        arguments

        (and (= input-count (inc (count arguments)))
             (= 1 (count source-bindings)))
        (let [position
              (:datahike.query.source/argument-position
               (first source-bindings))]
          (if (<= 0 position (count arguments))
            (into (conj (subvec arguments 0 position)
                        ::implicit-database-value)
                  (subvec arguments position))
            unaligned-dependency-arguments))

        :else unaligned-dependency-arguments))
    (catch #?(:clj Throwable :cljs :default) _
      unaligned-dependency-arguments)))

(defn ^{:seon.capability/effect :pure} read-attribute-dependencies
  "Return exact query or pull attributes, or `:all` when open."
  {:malli/schema
   [:=> [:cat ::read-attribute-dependencies-request]
    :datahike.query/attribute-dependencies]}
  [request]
  (try
    (if-let [query-form (::query request)]
      (let [arguments
            (aligned-dependency-arguments query-form (::args request))]
        (if (= unaligned-dependency-arguments arguments)
          :all
          (datahike.query/dependency-plan-attributes
           (apply datahike.query/query-dependency-plan
                  query-form arguments))))
      (datahike.query/dependency-plan-attributes
       (datahike.pull/pull-dependency-plan
        (::pull-pattern request)
        (or (::refs request) []))))
    (catch #?(:clj Throwable :cljs :default) _
      :all)))

(defn- ^:async query-wire-request! [request]
  (let [arguments (vec (or (::args request) []))
        database (or (::db request)
                     (::db (current-tx-context))
                     (when-not (explicit-query-source? arguments)
                       (await (read-db! request))))]
    (if (error-value? database)
      database
      (protocol/query-request
       (cond->
         (merge {::protocol/request-id
                 (or (::request-id request) ((leaf-fn ::leaf/uuid)))
                 ::protocol/query-form (::query request)
                 ::protocol/arguments arguments}
                (read-attribution)
                (read-resource-options :query request))
         database (assoc ::db database))))))

(defn- ^:async query-response! [request]
  (let [wire-request (await (query-wire-request! request))]
    (if (error-value? wire-request)
      wire-request
      (let [response (await (send-request! wire-request 30000))]
        (cond
          (error-value? response) response
          (::protocol/success? response)
          (do (record-query-evidence! wire-request response)
              response)
          :else (response-error response))))))

(defn- ^:async query-result! [request]
  (let [response (await (query-response! request))]
    (if (or (error-value? response) (not (::protocol/success? response)))
      response
      (decode-read-result (:datahike.query/result response)))))

(defn ^{:async #?(:cljs true :clj false) :seon.capability/effect :read} query
  "Run one Datalog query with source arguments in their declared positions."
  {:malli/schema
   [:=> [:catn [::request-or-query [:or ::query-request ::query-form]]
                 [::inputs [:* :any]]]
    :any]}
  [request-or-query & inputs]
  (await
   (query-result!
    (if (and (map? request-or-query)
             (contains? request-or-query ::query)
             (empty? inputs))
      request-or-query
      {::query request-or-query ::args (vec inputs)}))))

(defn ^{:async #?(:cljs true :clj false) :seon.capability/effect :read} query-with-evidence
  "Run a query and return its result plus Datahike dependency/cache evidence."
  {:malli/schema [:=> [:cat ::query-request] :any]}
  [request]
  (let [response (await (query-response! request))]
    (if (or (error-value? response) (not (::protocol/success? response)))
      response
      (select-keys response
                   [:datahike.query/result
                    :datahike.read/dependency-plan
                    :datahike.query/attribute-dependencies
                    :datahike.query/cache-evidence
                    :datahike.query/resource-evidence]))))

(defn ^{:async #?(:cljs true :clj false) :seon.capability/effect :read} pull
  "Pull one entity as ordinary data."
  {:malli/schema
   [:function
    [:=> [:cat ::pull-request] :any]
    [:=> [:catn [::pull-pattern ::pull-pattern] [::ref :any]] :any]
    [:=> [:catn [::db :seon.db/db]
                 [::pull-pattern ::pull-pattern]
                 [::ref :any]] :any]]}
  ([request]
   (let [base (await (request-db! request))]
     (if (error-value? base)
       base
       (let [response
             (await
              (send-request!
               (protocol/pull-request
                (merge base
                       {::protocol/selector (::pull-pattern request)
                        ::protocol/entity-id (::ref request)}
                       (read-attribution)
                       (read-resource-options :pull request)))
               30000))]
         (cond
           (error-value? response) response
           (not (::protocol/success? response)) (response-error response)
           :else (do (record-primary-read-evidence!
                      (merge base request) response)
                     (decode-read-result (::protocol/result response))))))))
  ([selector entity-id]
   (await (pull {::pull-pattern selector ::ref entity-id})))
  ([database selector entity-id]
   (await (pull {::db database
                 ::pull-pattern selector
                 ::ref entity-id}))))

(defn ^{:async #?(:cljs true :clj false) :seon.capability/effect :read} pull-many
  "Pull several entities as eager ordinary maps in input order."
  {:malli/schema [:function
                  [:=> [:cat ::pull-many-request] :any]
                  [:=> [:cat ::pull-pattern ::refs] :any]
                  [:=> [:cat :seon.db/db ::pull-pattern ::refs] :any]]}
  ([request]
   (let [base (await (request-db! request))]
     (if (error-value? base)
       base
       (let [response
             (await
              (send-request!
               (protocol/pull-many-request
                (merge base
                       {::protocol/selector (::pull-pattern request)
                        ::protocol/entity-ids (::refs request)}
                       (read-attribution)
                       (read-resource-options :pull request)))
               30000))]
         (cond
           (error-value? response) response
           (not (::protocol/success? response)) (response-error response)
           :else (do (record-primary-read-evidence!
                      (merge base request) response)
                     (decode-read-result (::protocol/result response))))))))
  ([selector entity-ids]
   (await (pull-many {::pull-pattern selector ::refs (vec entity-ids)})))
  ([database selector entity-ids]
   (await (pull-many {::db database
                      ::pull-pattern selector
                      ::refs (vec entity-ids)}))))

(defn ^{:async #?(:cljs true :clj false) :seon.capability/effect :read} entity
  "Pull every attribute of one entity as eager ordinary data."
  {:malli/schema [:function [:=> [:cat [:or ::entity-request :seon.db/ref]] :any]
                  [:=> [:cat :seon.db/db :seon.db/ref] :any]]}
  ([request-or-entity-id]
   (if (and (map? request-or-entity-id)
            (contains? request-or-entity-id ::ref))
     (await (pull (assoc request-or-entity-id ::pull-pattern '[*])))
     (await (pull '[*] request-or-entity-id))))
  ([database entity-id]
   (await (pull database '[*] entity-id))))

(defn ^{:async #?(:cljs true :clj false) :seon.capability/effect :read} installed-schema
  "Return Datahike's installed schema map for an explicit or current database."
  {:malli/schema [:function [:=> [:cat] :any]
                  [:=> [:cat [:or [:map {:closed true}
                                     [::db {:optional true} :seon.db/db]]
                                  :seon.db/db]] :any]]}
  ([] (await (installed-schema {})))
  ([request]
   (let [request (if (db-value? request) {::db request} request)
         base (await (request-db! request))]
     (if (error-value? base)
       base
       (let [response (await (send-request! (protocol/schema-request base) 15000))]
         (cond
           (error-value? response) response
           (not (::protocol/success? response)) (response-error response)
           :else (do (record-primary-read-evidence! base response)
                     (::protocol/schema response))))))))

(defn ^{:async #?(:cljs true :clj false) :seon.capability/effect :read} execute-many
  "Run bounded independent database operations at one database value."
  {:malli/schema [:=> [:cat ::execute-many-request] :any]}
  [request]
  (try
    (let [members (::members request)
          member-databases (->> members (keep ::db) distinct vec)
          selected (or (::db request)
                       (::db (current-tx-context)))
          mixed? (or (> (count member-databases) 1)
                     (and selected
                          (seq member-databases)
                          (not= selected (first member-databases))))]
      (if mixed?
        (session-error
         "execute-many requires one database value for every member."
         {::db selected
          ::member-databases member-databases})
        (let [database (or selected
                           (first member-databases)
                           (await (read-db! request)))]
          (if (error-value? database)
            database
            (let [wire-request
                  (protocol/execute-many-request
                   (cond-> {::protocol/request-id
                            (or (::request-id request) ((leaf-fn ::leaf/uuid)))
                            ::protocol/members
                            (let [attribution (read-attribution)]
                              (mapv #(merge attribution
                                            (assoc % ::db database))
                                    members))}
                     (::max-result-weight request)
                     (assoc :datahike.resource/max-result-weight
                            (::max-result-weight request))))
                  response (await (send-request! wire-request 60000))]
              (cond
                (error-value? response) response
                (not (::protocol/success? response)) (response-error response)
                :else
                (do
                  (doseq [[member member-response]
                          (map vector (::protocol/members wire-request)
                               (::protocol/results response))
                          :when (true? (::protocol/success? member-response))]
                    (if (= protocol/query-operation
                           (::protocol/operation member))
                      (record-query-evidence! member member-response)
                      (record-primary-read-evidence! member member-response)))
                  {::db database
                   ::results (mapv decode-read-result
                                   (::protocol/results response))})))))))
    (catch #?(:clj Throwable :cljs :default) exception
      (let [value (error/->map exception)]
        (cond-> value
          (nil? (:seon.error/kind value))
          (assoc :seon.error/kind :core-bug))))))

(defn ^{:async #?(:cljs true :clj false) :seon.capability/effect :read} index-page
  "Return one eager bounded page in native Datahike index order."
  {:malli/schema [:function [:=> [:cat ::index-page-request] :any]
                  [:=> [:cat :seon.db/db ::index-page-request] :any]]}
  ([request]
   (let [base (await (request-db! request))]
     (if (error-value? base)
       base
       (let [wire-request
             (protocol/index-page-request
              (cond-> (assoc base
                             ::protocol/index (::index request)
                             ::protocol/prefix
                             (vec (or (::components request) []))
                             ::protocol/direction (::direction request)
                             ::protocol/limit (::limit request))
                (::cursor request) (assoc ::protocol/cursor (::cursor request))
                (::max-result-weight request)
                (assoc :datahike.resource/max-result-weight
                       (::max-result-weight request))))
             response (await (send-request! wire-request 30000))]
         (cond
           (error-value? response) response
           (not (::protocol/success? response)) (response-error response)
           :else
           (do
             (record-primary-read-evidence! base response)
             (select-keys response
                          [:datahike.index-page/datoms
                           :datahike.index-page/complete?
                           :datahike.index-page/cursor])))))))
  ([database options]
   (await (index-page (assoc options ::db database)))))

(defn ^:async knn-search!
  "Search the selected database's native semantic index."
  [request]
  (let [base (await (request-db! request))]
    (if (error-value? base)
      base
      (let [response
            (await
             (send-request!
              (protocol/knn-search-request
               (merge base
                      (select-keys request
                                   [::protocol/query ::protocol/limit
                                    ::protocol/entity-ids])))
              60000))]
        (cond
          (error-value? response) response
          (not (::protocol/success? response)) (response-error response)
          :else
          (do
            (record-primary-read-evidence!
             base {:datahike.read/dependency-plan :all})
            (::protocol/hits response)))))))

(schema/register! ::containing-branch-head ::branch-head)
(schema/register! ::transaction-id :seon.db.protocol/transaction-id)
(schema/register!
 ::resolve-transaction-branch-head-request
 [:map {:closed true}
  [::containing-branch-head ::containing-branch-head]
  [::transaction-id ::transaction-id]])


;;; Pod session operations
(defn ^:async listen!
  ([input-or-handler]
   #?(:cljs (await (session/listen! input-or-handler))
      :clj (if-let [listen! (:seon.db.leaf/listen! *leaf*)]
             (listen! (if (map? input-or-handler)
                        input-or-handler
                        {::handler input-or-handler}))
             (session-error "The JVM database leaf has no listener." {}))))
  ([key handler]
   #?(:cljs (await (session/listen! key handler))
      :clj (if-let [listen! (:seon.db.leaf/listen! *leaf*)]
             (listen! {::key key ::handler handler})
             (session-error "The JVM database leaf has no listener." {}))))
  ([database key handler]
   #?(:cljs (await (session/listen! database key handler))
      :clj (if-let [listen! (:seon.db.leaf/listen! *leaf*)]
             (listen! {::db database ::key key ::handler handler})
             (session-error "The JVM database leaf has no listener." {})))))
(defn unlisten!
  [input]
  #?(:cljs (session/unlisten! input)
     :clj (if-let [unlisten! (:seon.db.leaf/unlisten! *leaf*)]
            (unlisten! input)
            false)))
(defn cancel! [request-id] #?(:cljs (session/cancel! request-id) :clj (session-error "Cancellation is pod-only." {})))
(defn release [database] #?(:cljs (session/release! database) :clj (session-error "Release is pod-only." {})))
(defn resolve-transaction-branch-head! [{::keys [containing-branch-head transaction-id]}]
  #?(:cljs (session/resolve-transaction-branch-head! containing-branch-head transaction-id)
     :clj (session-error "Branch resolution is pod-only." {})))

#?(:cljs
   (do
     (defn- persist-error-entities!
       "Persist error projections through the ordinary transaction entry."
       [entities]
       (-> (transact! {::tx-data (vec entities)})
           (.then (fn [result] {::ok? (not (error-value? result))}))
           (.catch (fn [_] {::ok? false}))))

     (error/set-db-hooks!
      {:seon.error/transact! persist-error-entities!
       :seon.error/branch-head session/current-branch-head})))

;;; Pure schema/transaction transforms

(defn malli->datahike-schema [attr-keys]
  (internal/malli->datahike-schema attr-keys))

(defn tx-meta-datahike-schema []
  (internal/tx-meta-datahike-schema))

(defn encode-edn-slot-values
  "Encode mixed-union attribute values before database transport."
  {:malli/schema [:=> [:catn [::tx-data ::tx-data]] ::tx-data]}
  [tx-data]
  (internal/encode-edn-slot-values tx-data))

(declare decode-edn-values)

(defn decode-edn-value
  "Decode one mixed-schema attribute value returned by the database.

   Also reconstructs the registered shape for native cardinality-many
   values. Datahike materializes sets as vectors and expands component refs to
   child maps on pull/entity; the registry — not the wire shape — owns the
   acquired value's form."
  {:malli/schema [:=> [:cat :keyword :any] :any]}
  [attr value]
  (cond
    (and (string? value) (internal/edn-encoded-attr? attr))
    (reader/read-string value)

    (and (map? value) (internal/component-scalar-attr? attr))
    (decode-edn-values value)

    (and (sequential? value) (internal/component-children-attr? attr))
    (let [children (mapv #(if (map? %) (decode-edn-values %) %) value)]
      (if (internal/set-valued-attr? attr)
        (into #{} children)
        children))

    (and (sequential? value) (internal/set-valued-attr? attr))
    (into #{} value)

    :else value))

(defn decode-edn-values
  "Decode every attribute value in one pulled entity tree."
  {:malli/schema [:=> [:cat :map] :map]}
  [values]
  (reduce-kv
   (fn [decoded attribute value]
     (assoc decoded attribute (decode-edn-value attribute value)))
   {}
   values))

(defn decode-read-result
  "Decode pulled entity maps wherever a read result contains them."
  [value]
  (cond
    (map? value) (decode-edn-values value)
    (vector? value) (mapv decode-read-result value)
    (set? value) (into #{} (map decode-read-result) value)
    (sequential? value) (doall (map decode-read-result value))
    :else value))
