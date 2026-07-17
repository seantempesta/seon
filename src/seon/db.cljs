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
(schema/register! ::error :map)
(schema/register! ::coordinate :seon.db.coordinate/coordinate)
(schema/register! ::query-form [:or [:vector :any] :map :string])
(schema/register! ::query :any)
(schema/register! ::args [:vector :any])
(schema/register! ::selector [:vector :any])
(schema/register! ::eid :any)
(schema/register! ::eids [:vector :any])
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
(schema/register! ::backend :seon.db.protocol/backend)
(schema/register! ::database-path :seon.db.protocol/database-path)
(schema/register! ::capabilities :seon.db.protocol/capabilities)
(schema/register! ::session :seon.db.transport.uds/session)
(schema/register! ::databases [:map-of ::database-name :seon.db/db])
(schema/register! ::thunk 'fn?)
(schema/register! ::tx-context :map)

(schema/register!
 ::transact-request
 [:map {:closed true}
  [::tx-data ::tx-data]
  [::db {:optional true} :seon.db/db]
  [::expected-db {:optional true} :seon.db/expected-db]
  [::tx-meta {:optional true} ::tx-meta]
  [::opts {:optional true} ::opts]
  [:seon.db.id/generated-candidates {:optional true}
   :seon.db.id/generated-candidates]])
(schema/register!
 ::transact-response
 [:map {:closed true}
  [:db-before :db-before]
  [:db-after :db-after]
  [:tx-data :tx-data]
  [:tempids :tempids]
  [:tx-meta :tx-meta]
  [:seon.db.id/eids {:optional true} :seon.db.id/eids]
  [:seon.db.id/recovered-commit?
   {:optional true} :seon.db.id/recovered-commit?]])
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
  [::selector ::selector]
  [::eid ::eid]
  [::db {:optional true} :seon.db/db]
  [::max-work {:optional true} ::max-work]
  [::max-results {:optional true} ::max-results]
  [::max-result-weight {:optional true} ::max-result-weight]])
(schema/register!
 ::pull-many-request
 [:map {:closed true}
  [::selector ::selector]
  [::eids ::eids]
  [::db {:optional true} :seon.db/db]
  [::max-work {:optional true} ::max-work]
  [::max-results {:optional true} ::max-results]
  [::max-result-weight {:optional true} ::max-result-weight]])
(schema/register!
 ::entity-request
 [:map {:closed true}
  [::eid ::eid]
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
  [::attachment {:optional true} :seon.db.coordinate/attachment]])
(schema/register!
 ::open-session-response
  [:map {:closed true}
  [::database-name ::database-name]
  [::db :seon.db/db]
  [::capabilities ::capabilities]])

;;; Process session

(defonce ^:private !session (atom nil))

(defn- session-error [message data]
  {:seon.error/message message
   :seon.error/kind :core-bug
   :seon.error/data data})

(defn- transport-failure-data [value]
  (get-in value [:seon.error/data :seon.error/ex-data]))

(defn- recoverable-transaction-delivery? [value]
  (let [failure-data (transport-failure-data value)
        transport-failure (::uds/failure failure-data)]
    (or (and (contains? #{:seon.db.transport.uds.failure/closed
                          :seon.db.transport.uds.failure/write
                          :seon.db.transport.uds.failure/timeout}
                        transport-failure)
             (not (::uds/closed-by-owner? failure-data)))
        (and (false? (::protocol/success? value))
             (or (true? (::protocol/canceled? value))
                 (and (= protocol/request-conflict-error
                         (::protocol/error-kind value))
                      (true? (::protocol/running? value))))))))

(defn- error-value? [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn- db-value? [value]
  (protocol/database-value? value))

(defn- newer-database
  "Keep the newest descriptor and reject an impossible split history."
  [current candidate]
  (cond
    (nil? current) candidate
    (> (:t candidate) (:t current)) candidate
    (< (:t candidate) (:t current)) current
    (= (:datahike/commit-id candidate) (:datahike/commit-id current)) current
    :else
    (throw
     (ex-info "One database transaction has conflicting commit identities."
              {:seon.error/kind :core-bug
               :seon.db/current-db current
               :seon.db/candidate-db candidate}))))

(defn- cache-database [state database]
  (if (and state (db-value? database))
    (update-in state [::databases (:db-name database)]
               newer-database database)
    state))

(defn- response-error [response]
  (when-let [database (:seon.db/current-db response)]
    (swap! !session cache-database database))
  {:seon.error/message (::protocol/error response)
   :seon.error/kind (or (:seon.error/kind response) :core-bug)
   :seon.error/data
   (cond-> {::protocol/error-kind (::protocol/error-kind response)
            ::protocol/request-id (::protocol/request-id response)}
     (:seon.db/expected-db response)
     (assoc :seon.db/expected-db (:seon.db/expected-db response))
     (:seon.db/current-db response)
     (assoc :seon.db/current-db (:seon.db/current-db response))
     (::protocol/expected-coordinate response)
     (assoc ::protocol/expected-coordinate
            (::protocol/expected-coordinate response))
     (::protocol/current-coordinate response)
     (assoc ::protocol/current-coordinate
            (::protocol/current-coordinate response))
     (::protocol/generated-candidate response)
     (assoc ::protocol/generated-candidate
            (::protocol/generated-candidate response))
     (contains? response ::protocol/canceled?)
     (assoc ::protocol/canceled? (::protocol/canceled? response))
     (contains? response ::protocol/running?)
     (assoc ::protocol/running? (::protocol/running? response)))})

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
  (when-let [database (:db-after event)]
    (swap! !session cache-database database))
  (when-let [handler (get-in @!session
                             [::interest-handlers
                              (::protocol/request-id event) ::handler])]
    (try
      (let [value (if (= protocol/datoms-event (::protocol/event event))
                    (select-keys event
                                 [:db-before :db-after :tx-data
                                  :tempids :tx-meta])
                    event)
            result (handler value)]
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
   ::db (get-in state [::databases (::database-name state)])
   ::capabilities (::capabilities state)})

(defn- stable-session-selection [selection]
  (dissoc selection ::initialization))

(defn- ^:async ensure-and-acquire!
  [session selection initialization]
  (let [{::keys [database-name backend database-path attachment]} selection
        ensure-response
        (await
         (request-on-session!
          session
          (protocol/ensure-database-request
           (cond-> {::protocol/request-id (str (random-uuid))
                    ::protocol/database-name database-name
                    ::protocol/backend backend}
             database-path (assoc ::protocol/database-path database-path)
             attachment (assoc ::db.coordinate/attachment attachment)
             initialization (assoc ::initialization initialization)))
          15000))
        _ (when-not (::protocol/success? ensure-response)
            (throw (ex-info "Opening the database failed." ensure-response)))
        acquire-response
        (await
         (request-on-session!
          session
          (protocol/acquire-database-request
           {::protocol/request-id (str (random-uuid))
            ::protocol/database-name database-name})
          15000))]
    (when-not (::protocol/success? acquire-response)
      (throw (ex-info "Acquiring the database failed." acquire-response)))
    acquire-response))

(defn- ^:async refresh-selection! [state initialization]
  (let [{::keys [owner session selection]} state]
    (try
      (let [acquired (await (ensure-and-acquire! session selection initialization))
            accepted (atom nil)]
        (swap! !session
               (fn [current]
                 (if (and (identical? owner (::owner current))
                          (identical? session (::session current)))
                   (let [next (-> current
                                  (dissoc ::opening ::opening-initialization)
                                  (cache-database (::db acquired)))]
                     (reset! accepted next)
                     next)
                   current)))
        (if-let [current @accepted]
          (session-result current)
          (throw
           (ex-info "Database session closed while refreshing."
                    {::selection selection :seon.error/kind :core-bug}))))
      (catch :default exception
        (swap! !session
               (fn [current]
                 (if (and (identical? owner (::owner current))
                          (identical? session (::session current)))
                   (dissoc current ::opening ::opening-initialization)
                   current)))
        (throw exception)))))

(defn- claim-opening! [initialization claim-state start!]
  (let [claimed? (atom false)
        resolve-opening (atom nil)
        reject-opening (atom nil)
        opening
        (js/Promise.
         (fn [resolve reject]
           (reset! resolve-opening resolve)
           (reset! reject-opening reject)))]
    (swap! !session
           (fn [current]
             (if-let [claimed-state (claim-state current)]
               (do
                 (reset! claimed? true)
                 (assoc claimed-state
                        ::opening opening
                        ::opening-initialization initialization))
               current)))
    (when @claimed?
      (-> (start!)
          (.then (fn [result] (@resolve-opening result)))
          (.catch (fn [exception] (@reject-opening exception))))
      opening)))

(defn- claim-refresh! [state initialization]
  (let [{::keys [owner session]} state]
    (claim-opening!
     initialization
     (fn [current]
       (when (and (identical? owner (::owner current))
                  (identical? session (::session current))
                  (nil? (::opening current)))
         current))
     #(refresh-selection! state initialization))))

(declare connect-selection!)

(defn- claim-connect! [current selection initialization]
  (let [owner (or (::owner current) (js-obj))]
    (claim-opening!
     initialization
     (fn [latest]
       (when (and (or (nil? latest)
                      (identical? owner (::owner latest)))
                  (nil? (::opening latest)))
         (assoc (or latest {})
                ::owner owner
                ::selection selection
                ::interest-handlers
                (or (::interest-handlers latest) {}))))
     #(connect-selection! selection initialization owner))))

(defn- ^:async connect-selection! [selection initialization owner]
  (let [{::keys [socket-path database-name]} selection
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
                          (if (and (identical? owner (::owner current))
                                   (identical? @opened (::session current)))
                            (dissoc current
                                    ::session ::opening ::opening-initialization
                                    ::database-name
                                    ::capabilities ::databases)
                            current))))}))
            _ (reset! opened session)
            _ (swap! !session
                     (fn [current]
                       (if (identical? owner (::owner current))
                         (assoc current ::session session ::databases {})
                         current)))
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
            acquire-response
            (await (ensure-and-acquire! session selection initialization))
            _ (doseq [[request-id entry] (::interest-handlers @!session)]
                (when (and (identical? owner (::owner @!session))
                           (identical?
                            (::owner entry)
                            (get-in @!session
                                    [::interest-handlers request-id ::owner])))
                  (let [response
                        (await
                         (request-on-session!
                          session
                          (assoc (::request entry) ::db (::db acquire-response))
                          15000))]
                    (when (or (error-value? response)
                              (not (::protocol/success? response)))
                      (throw
                       (ex-info "Restoring a database listener failed."
                                response)))
                    (if (identical?
                         (::owner entry)
                         (get-in @!session
                                 [::interest-handlers request-id ::owner]))
                      (session-event!
                       {::protocol/event protocol/resynchronization-event
                        ::protocol/request-id request-id
                        :db-after (::db acquire-response)})
                      (await
                       (request-on-session!
                        session
                        (protocol/unlisten-request
                         {::protocol/request-id (str (random-uuid))
                          ::protocol/target-request-id request-id})
                        15000))))))
            state (-> @!session
                      (assoc ::owner owner
                             ::selection selection
                             ::session session
                             ::database-name database-name
                             ::capabilities
                             (::protocol/capabilities capabilities-response))
                      (dissoc ::opening ::opening-initialization)
                      (cache-database (::db acquire-response)))]
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
                 (if (identical? owner (::owner current))
                   (when-not (true? (aget owner "closed"))
                     (select-keys current
                                  [::owner ::selection ::interest-handlers]))
                   current)))
        (throw exception)))))

(defn ^:async open-session!
  "Open and acquire this process's one multiplexed database session."
  {:malli/schema [:=> [:cat ::open-session-request] ::open-session-response]}
  [request]
  (let [selection (stable-session-selection request)
        initialization (::initialization request)
        current @!session]
    (cond
      (and current (not= selection (::selection current)))
      (throw (ex-info "Another database session owns this process."
                      {::selection selection :seon.error/kind :core-bug}))

      (::opening current)
      (if (= initialization (::opening-initialization current))
        (await (::opening current))
        (do
          (await (::opening current))
          (await (open-session! request))))

      (active-session)
      (if initialization
        (if-let [opening (claim-refresh! current initialization)]
          (await opening)
          (await (open-session! request)))
        (session-result current))

      :else
      (if-let [opening (claim-connect! current selection initialization)]
        (await opening)
        (await (open-session! request))))))

(defn close-session!
  "Close this process's database session."
  {:malli/schema [:=> [:cat] :boolean]}
  []
  (let [closed (atom nil)]
    (swap! !session (fn [current] (reset! closed current) nil))
    (when-let [owner (::owner @closed)]
      (aset owner "closed" true))
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

;;; Fiber-local attribution and immutable database values

(defn current-tx-context
  "Return this async fiber's transaction context, if one is active."
  []
  (internal/current-tx-context))

(defn ^:seon.fn/agent-facing? current-agent-id
  "Return this async fiber's agent id, if one is active."
  {:malli/schema [:=> [:cat] [:maybe :string]]}
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

(defn- ^:async resolve-db! [database-name acquire?]
  (if-let [{current-name ::database-name databases ::databases}
           (active-session)]
    (let [name (or database-name current-name)]
      (if-let [database (get databases name)]
        database
        (let [request-id (str (random-uuid))
              request (if acquire?
                        (protocol/acquire-database-request
                         {::protocol/request-id request-id
                          ::protocol/database-name name})
                        (protocol/resolve-head-request
                         {::protocol/request-id request-id
                          ::protocol/database-name name}))
              response (await (send-request! request 15000))]
          (cond
            (error-value? response) response
            (not (::protocol/success? response)) (response-error response)
            :else
            (let [database (::db response)]
              (swap! !session cache-database database)
              database)))))
    (session-error "This process has no open database session." {})))

(defn- ^:async read-db! [request]
  (if-let [database (or (::db request) (::db (current-tx-context)))]
    database
    (await (resolve-db! (::database-name request) false))))

(defn- ^:async request-db! [request]
  (let [database (await (read-db! request))]
    (if (error-value? database)
      database
      {::protocol/request-id (or (::request-id request) (str (random-uuid)))
       ::db database})))

(defn- read-resource-options [request]
  (cond-> {}
    (::max-work request)
    (assoc :datahike.resource/max-work (::max-work request))
    (::max-results request)
    (assoc :datahike.resource/max-results (::max-results request))
    (::max-result-weight request)
    (assoc :datahike.resource/max-result-weight (::max-result-weight request))))

(defn ^{:async true :seon.fn/agent-facing? true} db
  "Return the latest immutable database value for this process's session."
  {:malli/schema
   [:function
    [:=> [:cat] :seon.db/db]
    [:=> [:cat [:map {:closed true}
                 [::database-name ::database-name]]] :seon.db/db]]}
  ([] (await (resolve-db! nil false)))
  ([request]
   (await (resolve-db! (::database-name request) true))))

(defn ^:seon.fn/agent-facing? as-of
  "Return a database value containing facts through `point`."
  {:malli/schema
   [:=> [:catn [::db :seon.db/db] [::point [:or :int :inst]]] :seon.db/db]}
  [database point]
  (assoc database :as-of point :since nil))

(defn ^:seon.fn/agent-facing? since
  "Return a database value containing facts added after `point`."
  {:malli/schema
   [:=> [:catn [::db :seon.db/db] [::point [:or :int :inst]]] :seon.db/db]}
  [database point]
  (assoc database :as-of nil :since point))

(defn ^:seon.fn/agent-facing? history
  "Return a database value containing assertions and retractions."
  {:malli/schema [:=> [:catn [::db :seon.db/db]] :seon.db/db]}
  [database]
  (assoc database :history true))

(defn ^:seon.fn/agent-facing? cas-assert [ref attr value]
  [:db.fn/cas ref attr value value])

;;; Writes

(defn- ^:async submit-transaction! [arg tx-data tx-meta]
  (let [database (await (read-db! arg))]
    (if (error-value? database)
      database
      (let [request
            (protocol/transaction-request
             (cond-> {::protocol/request-id
                      (or (::request-id arg) (str (random-uuid)))
                      ::db database
                      ::protocol/transaction-data
                      (internal/encode-edn-slot-values tx-data)}
               (::expected-db arg) (assoc ::expected-db (::expected-db arg))
               (seq tx-meta) (assoc ::protocol/transaction-meta tx-meta)
               (contains? arg :seon.db.id/generated-candidates)
               (assoc ::protocol/generated-candidates
                      (:seon.db.id/generated-candidates arg))))
            selection (::selection @!session)
            session-owner (::owner @!session)
            response
            (loop [delay-ms 1 ambiguous? false]
              (if (and session-owner (true? (aget session-owner "closed")))
                (session-error
                 "The database session was closed by its owner."
                 {::protocol/request-id (::protocol/request-id request)
                  :seon.error/ex-data {::uds/closed-by-owner? true}})
                (let [opened
                      (if (active-session)
                        true
                        (if selection
                          (await
                           (-> (open-session! selection)
                               (.then (constantly true))
                               (.catch
                                (fn [exception]
                                  (session-error
                                   (or (.-message exception)
                                       "The database session failed to reopen.")
                                   (cond->
                                    {::protocol/request-id
                                     (::protocol/request-id request)}
                                     (ex-data exception)
                                     (assoc :seon.error/ex-data
                                            (ex-data exception))))))))
                          false))
                      opening-error? (error-value? opened)
                      response
                      (if (error-value? opened)
                        opened
                        (await (send-request! request 120000)))]
                  (if (or (recoverable-transaction-delivery? response)
                          (and ambiguous? opening-error?))
                    (do
                      (await
                       (js/Promise.
                        (fn [resolve _reject]
                          (js/setTimeout resolve delay-ms))))
                      (recur (min 250 (* 2 delay-ms)) true))
                    response))))]
        (cond
          (error-value? response) response
          (not (::protocol/success? response)) (response-error response)
          :else
          (let [report (select-keys response
                                    [:db-before :db-after :tx-data
                                     :tempids :tx-meta])]
            (swap! !session cache-database (:db-after report))
            (cond-> report
              (seq (::protocol/generated-entity-ids response))
              (assoc :seon.db.id/eids (::protocol/generated-entity-ids response))
              (::protocol/recovered? response)
              (assoc :seon.db.id/recovered-commit? true))))))))

(defn ^{:async true :seon.fn/agent-facing? true} transact!
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
          tx-data (-> (::tx-data arg)
                      internal/coerce-identity-symbol-idents
                      internal/normalize-entity-ref-keys)
          opts (internal/merge-tx-context-into-opts
                (cond-> (::opts arg)
                  (::tx-meta arg) (assoc :tx-meta (::tx-meta arg))))
          tx-meta (:tx-meta opts)
          attrs (into (internal/extract-tx-attrs tx-data) (keys tx-meta))]
      (internal/validate-attrs! attrs)
      (internal/validate-values! tx-data)
      (internal/validate-values! [tx-meta])
      (await (submit-transaction! arg tx-data tx-meta)))
    (catch :default exception
      (let [value (error/->map exception)]
        (cond-> value
          (nil? (:seon.error/kind value))
          (assoc :seon.error/kind :core-bug))))))

;;; Coordinate-pinned reads

(defn- explicit-query-source? [arguments]
  (some db-value? arguments))

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
                 (or (::request-id request) (str (random-uuid)))
                 ::protocol/query-form (::query request)
                 ::protocol/arguments arguments}
                (read-resource-options request))
         database (assoc ::db database))))))

(defn- ^:async query-response! [request]
  (let [wire-request (await (query-wire-request! request))]
    (if (error-value? wire-request)
      wire-request
      (let [response (await (send-request! wire-request 30000))]
        (if (or (error-value? response) (::protocol/success? response))
          response
          (response-error response))))))

(defn- ^:async query-result! [request]
  (let [response (await (query-response! request))]
    (if (or (error-value? response) (not (::protocol/success? response)))
      response
      (:datahike.query/result response))))

(defn ^{:async true :seon.fn/agent-facing? true} query
  "Run one Datalog query with source arguments in their declared positions."
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

(defn ^{:async true :seon.fn/agent-facing? true} query-with-evidence
  "Run a query and return its result plus Datahike cache/resource evidence."
  [request]
  (let [response (await (query-response! request))]
    (if (or (error-value? response) (not (::protocol/success? response)))
      response
      (select-keys response
                   [:datahike.query/result
                    :datahike.query/attribute-dependencies
                    :datahike.query/cache-evidence
                    :datahike.query/resource-evidence]))))

(defn ^{:async true :seon.fn/agent-facing? true} pull
  "Pull one entity as ordinary data."
  {:malli/schema
   [:function
    [:=> [:cat ::pull-request] :any]
    [:=> [:catn [::selector [:vector :any]] [::eid :any]] :any]]}
  ([request]
   (let [base (await (request-db! request))]
     (if (error-value? base)
       base
       (let [response
             (await
              (send-request!
               (protocol/pull-request
                (merge base
                       {::protocol/selector (::selector request)
                        ::protocol/entity-id (::eid request)}
                       (read-resource-options request)))
               30000))]
         (cond
           (error-value? response) response
           (not (::protocol/success? response)) (response-error response)
           :else (::protocol/result response))))))
  ([selector entity-id]
   (await (pull {::selector selector ::eid entity-id})))
  ([database selector entity-id]
   (await (pull {::db database
                 ::selector selector
                 ::eid entity-id}))))

(defn ^{:async true :seon.fn/agent-facing? true} pull-many
  "Pull several entities as eager ordinary maps in input order."
  ([request]
   (let [base (await (request-db! request))]
     (if (error-value? base)
       base
       (let [response
             (await
              (send-request!
               (protocol/pull-many-request
                (merge base
                       {::protocol/selector (::selector request)
                        ::protocol/entity-ids (::eids request)}
                       (read-resource-options request)))
               30000))]
         (cond
           (error-value? response) response
           (not (::protocol/success? response)) (response-error response)
           :else (::protocol/result response))))))
  ([selector entity-ids]
   (await (pull-many {::selector selector ::eids (vec entity-ids)})))
  ([database selector entity-ids]
   (await (pull-many {::db database
                      ::selector selector
                      ::eids (vec entity-ids)}))))

(defn ^{:async true :seon.fn/agent-facing? true} entity
  "Pull every attribute of one entity as eager ordinary data."
  ([request-or-entity-id]
   (if (and (map? request-or-entity-id)
            (contains? request-or-entity-id ::eid))
     (await (pull (assoc request-or-entity-id ::selector '[*])))
     (await (pull '[*] request-or-entity-id))))
  ([database entity-id]
   (await (pull database '[*] entity-id))))

(defn ^{:async true :seon.fn/agent-facing? true} installed-schema
  "Return Datahike's installed schema map for an explicit or current database."
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
           :else (::protocol/schema response)))))))

(defn ^{:async true :seon.fn/agent-facing? true} execute-many
  "Run bounded independent database operations at one database value."
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
                            (or (::request-id request) (str (random-uuid)))
                            ::protocol/members
                            (mapv #(assoc % ::db database) members)}
                     (::max-result-weight request)
                     (assoc :datahike.resource/max-result-weight
                            (::max-result-weight request))))
                  response (await (send-request! wire-request 60000))]
              (cond
                (error-value? response) response
                (not (::protocol/success? response)) (response-error response)
                :else {::results (::protocol/results response)}))))))
    (catch :default exception
      (let [value (error/->map exception)]
        (cond-> value
          (nil? (:seon.error/kind value))
          (assoc :seon.error/kind :core-bug))))))

(defn ^{:async true :seon.fn/agent-facing? true} index-page
  "Return one eager bounded page in native Datahike index order."
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
           (select-keys response
                        [:datahike.index-page/datoms
                         :datahike.index-page/complete?
                         :datahike.index-page/cursor]))))))
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

(def ^:private all-datoms-query
  '[:find ?e :where [?e ?attribute ?value]])

(defn- ^:async listen-request! [{::keys [handler key query datom-patterns]
                                 :as input}]
  (if-let [{::keys [session]} (active-session)]
    (let [database (await (read-db! input))]
      (if (error-value? database)
        database
        (let [public-key (or key (str (random-uuid)))
              request-id (str public-key)
              owner (js-obj)
              request
              (protocol/listen-request
               (cond-> {::protocol/request-id request-id
                        ::db database}
                 datom-patterns
                 (assoc ::protocol/datom-patterns datom-patterns)
                 (not datom-patterns)
                 (assoc ::protocol/query-form (or query all-datoms-query))))]
          (swap! !session assoc-in [::interest-handlers request-id]
                 {::owner owner
                  ::handler handler
                  ::key public-key
                  ::request request})
          (let [response (await (request-on-session! session request 15000))]
            (if (and (not (error-value? response))
                     (::protocol/success? response))
              (do
                (swap! !session cache-database (:db-after response))
                public-key)
              (do
                (swap! !session
                       (fn [current]
                         (if (identical?
                              owner
                              (get-in current
                                      [::interest-handlers request-id ::owner]))
                           (update current ::interest-handlers dissoc request-id)
                           current)))
                (if (error-value? response)
                  response
                  (response-error response))))))))
    (session-error "This process has no open database session." {})))

(defn ^:async listen!
  "Register or atomically replace a session-owned transaction listener."
  ([input-or-handler]
   (await (listen-request!
           (if (map? input-or-handler)
             input-or-handler
             {::handler input-or-handler}))))
  ([key handler]
   (await (listen-request! {::key key ::handler handler})))
  ([database key handler]
   (await (listen-request! {::db database ::key key ::handler handler}))))

(defn ^:async unlisten!
  "Remove a listener by key and report whether this session owned it."
  [input]
  (let [key (if (and (map? input) (contains? input ::key)) (::key input) input)
        request-id (str key)
        removed (atom nil)]
    (swap! !session
           (fn [current]
             (if-let [entry
                      (get-in current [::interest-handlers request-id])]
               (do
                 (reset! removed entry)
                 (update current ::interest-handlers dissoc request-id))
               current)))
    (if-not @removed
      false
      (if-let [{::keys [session opening]} (active-session)]
        (if opening
          true
          (let [response
                (await
                 (request-on-session!
                  session
                  (protocol/unlisten-request
                   {::protocol/request-id (str (random-uuid))
                    ::protocol/target-request-id request-id})
                  15000))]
            (cond
              (error-value? response) response
              (not (::protocol/success? response)) (response-error response)
              :else true)))
        true))))

(defn ^:async cancel!
  "Request cancellation by the existing public request identity."
  [request-id]
  (let [response
        (await
         (send-request!
          (protocol/cancel-request
           {::protocol/request-id (str (random-uuid))
            ::protocol/target-request-id (str request-id)})
          15000))]
    (cond
      (error-value? response) response
      (not (::protocol/success? response)) (response-error response)
      :else (::protocol/canceled? response))))

(defn ^:async release
  "Release this session's acquisition of a named database value."
  [database]
  (let [response
        (await
         (send-request!
          (protocol/release-database-request
           {::protocol/request-id (str (random-uuid))
            ::db database})
          15000))]
    (cond
      (error-value? response) response
      (not (::protocol/success? response)) (response-error response)
      :else
      (let [released? (::protocol/released? response)]
        (when released?
          (swap! !session update ::databases dissoc (:db-name database)))
        released?))))

;;; Pure schema/transaction transforms

(defn malli->datahike-schema [attr-keys]
  (internal/malli->datahike-schema attr-keys))

(defn tx-meta-datahike-schema []
  (internal/tx-meta-datahike-schema))

(defn decode-edn-value
  "Decode one mixed-schema attribute value returned by the database."
  {:malli/schema [:=> [:cat :keyword :any] :any]}
  [attr value]
  (if (and (string? value) (internal/edn-encoded-attr? attr))
    (reader/read-string value)
    value))

(defn decode-edn-values
  "Decode every attribute value in one flat pulled entity map."
  {:malli/schema [:=> [:cat :map] :map]}
  [values]
  (reduce-kv
   (fn [decoded attribute value]
     (assoc decoded attribute (decode-edn-value attribute value)))
   {}
   values))
