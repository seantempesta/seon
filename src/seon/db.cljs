(ns seon.db
  "The pod's asynchronous database API.

   The JVM writer owns Datahike connections, immutable database values,
   indexes, caches, and serialization. This namespace owns only ordinary
   request/response data and one multiplexed transport session."
  (:require
   [cljs.reader :as reader]
   [seon.db.coordinate :as db.coordinate]
   [seon.db.id.schema]
   [seon.db.internal :as internal]
   [seon.db.protocol :as protocol]
   [seon.db.transport.uds :as uds]
   [seon.error :as error]
   [seon.schema :as schema]))

;;; Public data contracts

(schema/register! ::tx-data [:vector :any])
(schema/register! ::opts :map)
(schema/register! ::tx-meta :map)
(schema/register! ::coordinate :seon.db.coordinate/coordinate)
(schema/register! ::expected-coordinate ::coordinate)
(schema/register! ::query-form [:or [:vector :any] :map :string])
(schema/register! ::query :any)
(schema/register! ::args [:vector :any])
(schema/register! ::pull-pattern [:vector :any])
(schema/register! ::ref :any)
(schema/register! ::refs [:vector :any])
(schema/register! ::max-work [:int {:min 1}])
(schema/register! ::max-results [:int {:min 1}])
(schema/register! ::max-result-weight [:int {:min 1}])
(schema/register! ::history? :boolean)
(schema/register! ::members :seon.db.protocol/members)
(schema/register! ::results :seon.db.protocol/results)
(schema/register! ::index [:enum :eavt :aevt :avet])
(schema/register! ::components [:vector :any])
(schema/register! ::index-limit [:int {:min 1 :max 200}])
(schema/register! ::direction :seon.db.protocol/direction)
(schema/register! ::cursor :seon.db.protocol/cursor)
(schema/register! ::complete? :boolean)
(schema/register! ::datoms :seon.db.protocol/datoms)
(schema/register! ::installed-schema :seon.db.protocol/schema)
(schema/register! ::user :seon.db/ref)
(schema/register! ::process :seon.db/ref)
(schema/register! ::ok? :boolean)
(schema/register! ::tempids :map)
(schema/register! ::tx :int)
(schema/register! ::tx-count [:int {:min 0}])
(schema/register! ::added [:int {:min 0}])
(schema/register! ::retracted [:int {:min 0}])
(schema/register! ::error :map)
(schema/register! ::key :any)
(schema/register! ::handler 'fn?)
(schema/register! ::datom-patterns :seon.db.protocol/datom-patterns)
(schema/register! ::socket-path :seon.db.transport.uds/socket-path)
(schema/register! ::database-name :seon.db.protocol/database-name)
(schema/register! ::backend :seon.db.protocol/backend)
(schema/register! ::database-path :seon.db.protocol/database-path)
(schema/register! ::attachment :seon.db.coordinate/attachment)
(schema/register! ::capabilities :seon.db.protocol/capabilities)
(schema/register! ::session :seon.db.transport.uds/session)
(schema/register! ::thunk 'fn?)
(schema/register! ::tx-context :map)

(schema/register!
 ::transact-request
 [:map {:closed true}
  [::tx-data ::tx-data]
  [::opts {:optional true} ::opts]
  [::expected-coordinate {:optional true} ::expected-coordinate]
  [:seon.db.id/generated-candidates {:optional true}
   :seon.db.id/generated-candidates]])
(schema/register!
 ::transact-response
 [:or
  [:map {:closed false}
   [::ok? [:= true]]
   [::coordinate ::coordinate]
   [::tempids ::tempids]
   [::tx ::tx]
   [::tx-count ::tx-count]
   [::added ::added]
   [::retracted ::retracted]]
  [:map {:closed true}
   [::ok? [:= false]]
   [::error ::error]]])
(schema/register!
 ::query-request
 [:map {:closed true}
  [::query ::query-form]
  [::args {:optional true} ::args]
  [::max-work {:optional true} ::max-work]
  [::max-results {:optional true} ::max-results]
  [::max-result-weight {:optional true} ::max-result-weight]
  [::history? {:optional true} ::history?]
  [::coordinate {:optional true} ::coordinate]])
(schema/register!
 ::pull-request
 [:map {:closed true}
  [::pull-pattern ::pull-pattern]
  [::ref ::ref]
  [::max-work {:optional true} ::max-work]
  [::max-results {:optional true} ::max-results]
  [::max-result-weight {:optional true} ::max-result-weight]
  [::coordinate {:optional true} ::coordinate]])
(schema/register!
 ::pull-many-request
 [:map {:closed true}
  [::pull-pattern ::pull-pattern]
  [::refs ::refs]
  [::max-work {:optional true} ::max-work]
  [::max-results {:optional true} ::max-results]
  [::max-result-weight {:optional true} ::max-result-weight]
  [::coordinate {:optional true} ::coordinate]])
(schema/register!
 ::execute-many-request
 [:map {:closed true}
  [::members ::members]
  [::coordinate {:optional true} ::coordinate]
  [::max-result-weight {:optional true} ::max-result-weight]])
(schema/register!
 ::index-page-request
 [:map {:closed true}
  [::index ::index]
  [::components {:optional true} ::components]
  [::direction ::direction]
  [::index-limit ::index-limit]
  [::cursor {:optional true} ::cursor]
  [::history? {:optional true} ::history?]
  [::max-result-weight {:optional true} ::max-result-weight]
  [::coordinate {:optional true} ::coordinate]])
(schema/register!
 ::knn-search-request
 [:map {:closed true}
  [::protocol/query ::protocol/query]
  [::protocol/limit ::protocol/limit]
  [::protocol/entity-ids {:optional true} ::protocol/entity-ids]
  [::coordinate {:optional true} ::coordinate]])
(schema/register!
 ::listen-request
 [:map {:closed true}
  [::handler ::handler]
  [::key {:optional true} ::key]
  [::query {:optional true} ::query-form]
  [::datom-patterns {:optional true} ::datom-patterns]])
(schema/register!
 ::listen-response
 [:map {:closed true}
  [::key ::key]
  [::coordinate {:optional true} ::coordinate]])
(schema/register! ::unlisten-request [:map {:closed true} [::key ::key]])
(schema/register! ::unlisten-response [:map {:closed true} [::ok? :boolean]])
(schema/register!
 ::open-session-request
 [:map {:closed true}
  [::socket-path ::socket-path]
  [::database-name ::database-name]
  [::backend ::backend]
  [::database-path {:optional true} ::database-path]
  [::attachment {:optional true} ::attachment]])
(schema/register!
 ::open-session-response
 [:map {:closed true}
  [::database-name ::database-name]
  [::attachment ::attachment]
  [::coordinate ::coordinate]
  [::capabilities ::capabilities]])

;;; Process session

(defonce ^:private !session (atom nil))

(defn- session-error [message data]
  {:seon.error/message message
   :seon.error/kind :core-bug
   :seon.error/data data})

(defn- error-value? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- response-error [response]
  {:seon.error/message (::protocol/error response)
   :seon.error/kind (or (:seon.error/kind response) :core-bug)
   :seon.error/data
   (cond-> {::protocol/error-kind (::protocol/error-kind response)
            ::protocol/request-id (::protocol/request-id response)}
     (::protocol/expected-coordinate response)
     (assoc ::protocol/expected-coordinate
            (::protocol/expected-coordinate response))
     (::protocol/current-coordinate response)
     (assoc ::protocol/current-coordinate
            (::protocol/current-coordinate response)))})

(defn- valid-response-for? [request response]
  (and (protocol/valid-response? response)
       (= (::protocol/request-id request)
          (::protocol/request-id response))))

(defn- request-on-session! [session request timeout-ms]
  (if-not (protocol/valid-request? request)
    (js/Promise.resolve
     (session-error "The database request is invalid."
                    {::protocol/request-id (::protocol/request-id request)
                     ::protocol/error (protocol/explain-request request)}))
    (-> (uds/request! {::uds/session session
                       ::uds/message request
                       ::uds/timeout-ms timeout-ms})
        (.then (fn [response]
                 (if (valid-response-for? request response)
                   response
                   (session-error
                    "The database authority returned an invalid response."
                    {::protocol/request-id (::protocol/request-id request)
                     ::protocol/error (protocol/explain-response response)}))))
        (.catch (fn [exception]
                  (session-error
                   (or (.-message exception) "The database session failed.")
                   (cond-> {::protocol/request-id (::protocol/request-id request)}
                     (ex-data exception)
                     (assoc :seon.error/ex-data (ex-data exception)))))))))

(defn- session-event! [event]
  (when-let [handler (get-in @!session
                             [::interest-handlers
                              (::protocol/request-id event) ::handler])]
    (try
      (let [result (handler event)]
        (when (instance? js/Promise result)
          (.catch result
                  (fn [exception]
                    (js/console.warn "[seon.db/listen!] async-rejected:"
                                     (error/->message exception))))))
      (catch :default exception
        (js/console.warn "[seon.db/listen!] threw:"
                         (error/->message exception))))))

(defn- active-session []
  (let [state @!session]
    (when (and (::session state) (uds/connected? (::session state))) state)))

(defn- session-result [state]
  {::database-name (::database-name state)
   ::attachment (::attachment state)
   ::coordinate (::opened-coordinate state)
   ::capabilities (::capabilities state)})

(defn- ^:async connect-selection! [selection owner]
  (let [{::keys [socket-path database-name backend database-path attachment]}
        selection
        opened (atom nil)]
    (try
      (let [session
            (await
             (uds/connect!
              {::uds/socket-path socket-path
               ::uds/on-event! session-event!
               ::uds/on-close!
               (fn [_]
                 (swap! !session
                        (fn [current]
                          (if (identical? owner (::owner current)) nil current))))}))
            _ (reset! opened session)
            capabilities-response
            (await
             (request-on-session!
              session
              (protocol/capabilities-request
               {::protocol/request-id (str (random-uuid))})
              5000))
            _ (when-not (::protocol/success? capabilities-response)
                (throw (ex-info "Database capability negotiation failed."
                                capabilities-response)))
            ensure-response
            (await
             (request-on-session!
              session
              (protocol/ensure-database-request
               (cond-> {::protocol/request-id (str (random-uuid))
                        ::protocol/database-name database-name
                        ::protocol/backend backend}
                 database-path (assoc ::protocol/database-path database-path)
                 attachment (assoc ::db.coordinate/attachment attachment)))
              15000))
            _ (when-not (::protocol/success? ensure-response)
                (throw (ex-info "Opening the database failed." ensure-response)))
            point (::db.coordinate/coordinate ensure-response)
            attachment (db.coordinate/attachment point)
            acquire-response
            (await
             (request-on-session!
              session
              (protocol/acquire-database-request
               {::protocol/request-id (str (random-uuid))
                ::protocol/database-name database-name
                ::protocol/attachment attachment})
              15000))
            _ (when-not (::protocol/success? acquire-response)
                (throw (ex-info "Acquiring the database failed." acquire-response)))
            state {::owner owner
                   ::selection selection
                   ::session session
                   ::database-name database-name
                   ::attachment attachment
                   ::capabilities (::protocol/capabilities capabilities-response)
                   ::opened-coordinate (::protocol/coordinate acquire-response)
                   ::interest-handlers {}}]
        (swap! !session
               (fn [current]
                 (if (identical? owner (::owner current)) state current)))
        (if (identical? owner (::owner @!session))
          (session-result state)
          (do (uds/close! session)
              (throw (ex-info "Database session closed while opening."
                              {::selection selection
                               :seon.error/kind :core-bug})))))
      (catch :default exception
        (when-let [session @opened] (uds/close! session))
        (swap! !session
               (fn [current]
                 (if (identical? owner (::owner current)) nil current)))
        (throw exception)))))

(defn ^:async open-session!
  "Open and acquire this process's one multiplexed database session."
  {:malli/schema [:=> [:cat ::open-session-request] ::open-session-response]}
  [selection]
  (let [current @!session]
    (cond
      (and (= selection (::selection current)) (active-session))
      (session-result current)

      (and (= selection (::selection current)) (::opening current))
      (await (::opening current))

      current
      (throw (ex-info "Another database session owns this process."
                      {::selection selection :seon.error/kind :core-bug}))

      :else
      (let [owner (js-obj)
            opening (connect-selection! selection owner)]
        (reset! !session {::owner owner ::selection selection
                          ::opening opening ::interest-handlers {}})
        (await opening)))))

(defn close-session!
  "Close this process's database session."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [closed (atom nil)]
    (swap! !session (fn [current] (reset! closed current) nil))
    (if-let [session (::session @closed)] (uds/close! session) false)))

(defn attached?
  "True when this process has one live database attachment."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (some? (active-session)))

(defn- send-request! [request timeout-ms]
  (if-let [state (active-session)]
    (request-on-session! (::session state) request timeout-ms)
    (js/Promise.resolve
     (session-error "This process has no open database session."
                    {::protocol/request-id (::protocol/request-id request)}))))

(defn- resolve-head-response! []
  (if-let [{::keys [database-name]} (active-session)]
    (send-request!
     (protocol/resolve-head-request
      {::protocol/request-id (str (random-uuid))
       ::protocol/database-name database-name})
     15000)
    (js/Promise.resolve
     (session-error "This process has no open database session." {}))))

;;; Fiber-local attribution and immutable read coordinates

(defn current-tx-context
  "Return this async fiber's transaction context, if one is active."
  []
  (internal/current-tx-context))

(defn ^:seon.fn/agent-facing? current-agent-id
  "Return this async fiber's agent id, if one is active."
  []
  (internal/current-agent-id))

(defn with-agent
  "Run a zero-argument function in one async-fiber agent scope."
  [agent-id thunk]
  (internal/run-with-agent agent-id thunk))

(defn without-agent
  "Run a zero-argument function with no inherited agent scope."
  [thunk]
  (internal/run-without-agent thunk))

(defn with-tx-context
  "Run a zero-argument function in one async-fiber transaction context."
  [tx-context thunk]
  (internal/run-with-tx-context tx-context thunk))

(defn- ^:async read-coordinate! [request]
  (if-let [point (or (::coordinate request)
                     (::coordinate (current-tx-context)))]
    point
    (let [response (await (resolve-head-response!))]
      (cond
        (error-value? response) response
        (not (::protocol/success? response)) (response-error response)
        :else (::protocol/coordinate response)))))

(defn- ^:async read-request-base! [request]
  (if-let [{::keys [database-name attachment]} (active-session)]
    (let [point (await (read-coordinate! request))]
      (if (error-value? point)
        point
        {::protocol/request-id (str (random-uuid))
         ::protocol/database-name database-name
         ::protocol/attachment attachment
         ::protocol/coordinate point}))
    (session-error "This process has no open database session." {})))

(defn- read-resource-options [request]
  (cond-> {}
    (::max-work request)
    (assoc :datahike.resource/max-work (::max-work request))
    (::max-results request)
    (assoc :datahike.resource/max-results (::max-results request))
    (::max-result-weight request)
    (assoc :datahike.resource/max-result-weight (::max-result-weight request))))

(defn ^{:async true :seon.fn/agent-facing? true} head-coordinate []
  (let [response (await (resolve-head-response!))]
    (cond
      (error-value? response) response
      (not (::protocol/success? response)) (response-error response)
      :else (::protocol/coordinate response))))

(defn ^:seon.fn/agent-facing? cas-assert [ref attr value]
  [:db.fn/cas ref attr value value])

;;; Writes

(defn- ^:async submit-transaction! [arg tx-data tx-meta]
  (if-let [{::keys [database-name]} (active-session)]
    (let [request
          (protocol/transaction-request
           (cond-> {::protocol/request-id (str (random-uuid))
                    ::protocol/database-name database-name
                    ::protocol/transaction-data
                    (internal/encode-edn-slot-values tx-data)}
             (seq tx-meta) (assoc ::protocol/transaction-meta tx-meta)
             (::expected-coordinate arg)
             (assoc ::protocol/expected-coordinate (::expected-coordinate arg))
             (contains? arg :seon.db.id/generated-candidates)
             (assoc ::protocol/generated-candidates
                    (:seon.db.id/generated-candidates arg))))
          response (await (send-request! request 120000))]
      (cond
        (error-value? response) {::ok? false ::error response}
        (not (::protocol/success? response))
        {::ok? false ::error (response-error response)}
        :else
        (let [point (::protocol/coordinate response)]
          (cond-> {::ok? true
                   ::coordinate point
                   ::tempids (::protocol/temporary-ids response)
                   ::tx (::db.coordinate/t point)
                   ::tx-count (count (::protocol/transaction-data response))
                   ::added (::protocol/datoms-added response)
                   ::retracted (::protocol/datoms-retracted response)}
            (seq (::protocol/generated-entity-ids response))
            (assoc :seon.db.id/eids (::protocol/generated-entity-ids response))
            (::protocol/recovered? response)
            (assoc :seon.db.id/recovered-commit? true)))))
    {::ok? false
     ::error (session-error "This process has no open database session." {})}))

(defn ^{:async true :seon.fn/agent-facing? true} transact!
  "Commit ordinary transaction data through the authoritative writer."
  {:malli/schema
   [:function
    [:=> [:catn [::request ::transact-request]] ::transact-response]
    [:=> [:catn [::tx-data ::tx-data]] ::transact-response]]}
  [& call-args]
  (try
    (let [arg (cond
                (and (= 1 (count call-args)) (map? (first call-args)))
                (first call-args)
                (and (= 1 (count call-args)) (vector? (first call-args)))
                {::tx-data (first call-args)}
                :else {::tx-data (vec call-args)})
          _ (internal/assert-invocation-shape! arg)
          tx-data (-> (::tx-data arg)
                      internal/coerce-identity-symbol-idents
                      internal/normalize-entity-ref-keys)
          opts (internal/merge-tx-context-into-opts (::opts arg))
          tx-meta (:tx-meta opts)
          attrs (into (internal/extract-tx-attrs tx-data) (keys tx-meta))]
      (internal/validate-attrs! attrs)
      (internal/validate-values! tx-data)
      (internal/validate-values! [tx-meta])
      (await (submit-transaction! arg tx-data tx-meta)))
    (catch :default exception
      (internal/commit-error-envelope exception))))

;;; Coordinate-pinned reads

(defn- ^:async query-result! [request]
  (let [base (await (read-request-base! request))]
    (if (error-value? base)
      base
      (let [wire-request
            (protocol/query-request
             (cond->
              (merge base
                     {::protocol/query-form (::query request)
                      ::protocol/arguments (vec (or (::args request) []))}
                     (read-resource-options request))
               (::history? request) (assoc ::protocol/history? true)))
            response (await (send-request! wire-request 30000))]
        (cond
          (error-value? response) response
          (not (::protocol/success? response)) (response-error response)
          :else (:datahike.query/result response))))))

(defn ^{:async true :seon.fn/agent-facing? true} query
  "Run one Datalog query against an explicit or current coordinate."
  {:malli/schema
   [:function
    [:=> [:catn [::request [:or ::query-request ::query-form]]] :any]
    [:=> [:catn [::query ::query-form] [::rest [:+ :any]]] :any]]}
  ([request]
   (await
    (query-result!
     (if (and (map? request) (contains? request ::query))
       request
       {::query request ::args []}))))
  ([query-form & inputs]
   (await (query-result! {::query query-form ::args (vec inputs)}))))

(defn ^{:async true :seon.fn/agent-facing? true} query-with-evidence [request]
  (let [base (await (read-request-base! request))]
    (if (error-value? base)
      base
      (let [wire-request
            (protocol/query-request
             (cond->
              (merge base
                     {::protocol/query-form (::query request)
                      ::protocol/arguments (vec (or (::args request) []))}
                     (read-resource-options request))
               (::history? request) (assoc ::protocol/history? true)))
            response (await (send-request! wire-request 30000))]
        (cond
          (error-value? response) response
          (not (::protocol/success? response)) (response-error response)
          :else
          {::coordinate (::protocol/coordinate response)
           :datahike.query/result (:datahike.query/result response)
           :datahike.query/attribute-dependencies
           (:datahike.query/attribute-dependencies response)
           :datahike.query/cache-evidence (:datahike.query/cache-evidence response)
           :datahike.query/resource-evidence
           (:datahike.query/resource-evidence response)})))))

(defn ^{:async true :seon.fn/agent-facing? true} pull
  "Pull one entity as ordinary data."
  {:malli/schema
   [:function
    [:=> [:cat ::pull-request] :any]
    [:=> [:catn [::selector [:vector :any]] [::eid :any]] :any]]}
  ([request]
   (let [base (await (read-request-base! request))]
     (if (error-value? base)
       base
       (let [response
             (await
              (send-request!
               (protocol/pull-request
                (merge base
                       {::protocol/selector (::pull-pattern request)
                        ::protocol/entity-id (::ref request)}
                       (read-resource-options request)))
               30000))]
         (cond
           (error-value? response) response
           (not (::protocol/success? response)) (response-error response)
           :else (::protocol/result response))))))
  ([selector entity-id]
   (await (pull {::pull-pattern selector ::ref entity-id}))))

(defn ^{:async true :seon.fn/agent-facing? true} pull-many [request]
  (let [base (await (read-request-base! request))]
    (if (error-value? base)
      base
      (let [response
            (await
             (send-request!
              (protocol/pull-many-request
               (merge base
                      {::protocol/selector (::pull-pattern request)
                       ::protocol/entity-ids (::refs request)}
                      (read-resource-options request)))
              30000))]
        (cond
          (error-value? response) response
          (not (::protocol/success? response)) (response-error response)
          :else (::protocol/result response))))))

(defn ^{:async true :seon.fn/agent-facing? true} installed-schema
  ([] (await (installed-schema {})))
  ([request]
   (let [base (await (read-request-base! request))]
     (if (error-value? base)
       base
       (let [response (await (send-request! (protocol/schema-request base) 15000))]
         (cond
           (error-value? response) response
           (not (::protocol/success? response)) (response-error response)
           :else (::protocol/schema response)))))))

(defn ^{:async true :seon.fn/agent-facing? true} execute-many [request]
  (let [base (await (read-request-base! request))]
    (if (error-value? base)
      base
      (let [response
            (await
             (send-request!
              (protocol/execute-many-request
               (cond-> (assoc base ::protocol/members (::members request))
                 (::max-result-weight request)
                 (assoc :datahike.resource/max-result-weight
                        (::max-result-weight request))))
              60000))]
        (cond
          (error-value? response) response
          (not (::protocol/success? response)) (response-error response)
          :else {::coordinate (::protocol/coordinate response)
                 ::results (::protocol/results response)})))))

(defn ^{:async true :seon.fn/agent-facing? true} index-page [request]
  (let [base (await (read-request-base! request))]
    (if (error-value? base)
      base
      (let [wire-request
            (protocol/index-page-request
             (cond-> (assoc base
                            ::protocol/index (::index request)
                            ::protocol/prefix (vec (or (::components request) []))
                            ::protocol/direction (::direction request)
                            ::protocol/limit (::index-limit request))
               (::history? request) (assoc ::protocol/history? true)
               (::cursor request) (assoc ::protocol/cursor (::cursor request))
               (::max-result-weight request)
               (assoc :datahike.resource/max-result-weight
                      (::max-result-weight request))))
            response (await (send-request! wire-request 30000))]
        (cond
          (error-value? response) response
          (not (::protocol/success? response)) (response-error response)
          :else
          (cond-> {::coordinate (::protocol/coordinate response)
                   ::datoms (::protocol/datoms response)
                   ::complete? (::protocol/complete? response)}
            (::protocol/cursor response)
            (assoc ::cursor (::protocol/cursor response))))))))

(defn ^:async knn-search! [request]
  (let [base (await (read-request-base! request))]
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
          :else (::protocol/hits response))))))

(schema/register! ::head-coordinate ::coordinate)
(schema/register! ::transaction-id :seon.db.protocol/transaction-id)
(schema/register!
 ::resolve-transaction-coordinate-request
 [:map {:closed true}
  [::head-coordinate ::head-coordinate]
  [::transaction-id ::transaction-id]])

(defn ^:async resolve-transaction-coordinate!
  [{::keys [head-coordinate transaction-id]}]
  (try
    (if-let [{::keys [database-name]} (active-session)]
      (let [response
            (await
             (send-request!
              (protocol/resolve-transaction-coordinate-request
               {::protocol/request-id (str (random-uuid))
                ::protocol/database-name database-name
                ::protocol/head-coordinate head-coordinate
                ::protocol/transaction-id transaction-id})
              30000))]
        (cond
          (error-value? response) response
          (not (::protocol/success? response)) (response-error response)
          :else (::protocol/coordinate response)))
      (session-error "This process has no open database session." {}))
    (catch :default exception (error/->map exception))))

;;; Transaction interests

(declare unlisten!)

(defn ^:async listen! [{::keys [handler key query datom-patterns]}]
  (if-let [{::keys [session database-name attachment] :as state}
           (active-session)]
    (let [request-id (if key (str key) (str (random-uuid)))
          prior (get-in state [::interest-handlers request-id])
          _ (when prior (await (unlisten! {::key request-id})))
          owner (js-obj)
          request
          (protocol/listen-request
           (cond-> {::protocol/request-id request-id
                    ::protocol/database-name database-name
                    ::protocol/attachment attachment}
             query (assoc ::protocol/query-form query)
             datom-patterns (assoc ::protocol/datom-patterns datom-patterns)))]
      (swap! !session assoc-in [::interest-handlers request-id]
             {::owner owner ::handler handler})
      (let [response (await (request-on-session! session request 15000))]
        (if (and (not (error-value? response)) (::protocol/success? response))
          {::key request-id ::coordinate (::protocol/coordinate response)}
          (do
            (swap! !session
                   (fn [current]
                     (if (identical?
                          owner
                          (get-in current
                                  [::interest-handlers request-id ::owner]))
                       (update current ::interest-handlers dissoc request-id)
                       current)))
            (if (error-value? response) response (response-error response))))))
    (session-error "This process has no open database session." {})))

(defn ^:async listen-sync! [request] (await (listen! request)))
(defn ^:async listen-async! [request] (await (listen! request)))

(defn ^:async unlisten! [{::keys [key]}]
  (let [request-id (str key)]
    (if-let [{::keys [session]} (active-session)]
      (if-let [entry (get-in @!session [::interest-handlers request-id])]
        (let [response
              (await
               (request-on-session!
                session
                (protocol/unlisten-request
                 {::protocol/request-id (str (random-uuid))
                  ::protocol/target-request-id request-id})
                15000))]
          (if (and (not (error-value? response)) (::protocol/success? response))
            (do
              (swap! !session
                     (fn [current]
                       (if (identical?
                            (::owner entry)
                            (get-in current
                                    [::interest-handlers request-id ::owner]))
                         (update current ::interest-handlers dissoc request-id)
                         current)))
              {::ok? true})
            (if (error-value? response) response (response-error response))))
        {::ok? true})
      {::ok? true})))

;;; Pure schema/transaction transforms

(defn malli->datahike-schema [attr-keys]
  (internal/malli->datahike-schema attr-keys))

(defn tx-meta-datahike-schema []
  (internal/tx-meta-datahike-schema))

(defn decode-edn-value [attr value]
  (if (and (string? value) (internal/edn-encoded-attr? attr))
    (reader/read-string value)
    value))
