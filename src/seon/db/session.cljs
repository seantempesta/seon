(ns seon.db.session
  "Bun session and ambient-context leaf for the portable database core."
  (:require
   [seon.config :as config]
   [seon.db :as-alias db]
   [seon.db.branch :as db.branch]
   [seon.db.fiber :as fiber]
   [seon.db.protocol :as protocol]
   [seon.db.transport.uds :as uds]
   [seon.error :as error]
   [seon.log :as seon-log]))

(defonce ^:private !session (atom nil))

(defn mint-id
  "Mint one process-local operation or transport identity."
  []
  (str (random-uuid)))

(defn session-error
  "Return one flat database session error."
  [message data]
  {:seon.error/message message
   :seon.error/kind :core-bug
   :seon.error/data data})

(defn request-error
  "Return one flat invalid-request error."
  [message data]
  {:seon.error/message message
   :seon.error/kind :user-input
   :seon.error/data data})

(defn error-value?
  "True when `value` is a flat public error value."
  [value]
  (and (map? value) (string? (:seon.error/message value))))

(defn current-tx-context
  "Return the current fiber-local transaction context."
  [] (fiber/current-tx-context))
(defn current-agent-id
  "Return the current fiber-local agent id."
  [] (fiber/current-agent-id))
(defn with-read-evidence
  "Run `f` in a fresh fiber-local read-evidence scope."
  [f] (fiber/run-with-read-evidence f))
(defn record-read-evidence!
  "Record read evidence in the current fiber-local scope."
  [evidence] (fiber/record-read-evidence! evidence))
(defn with-agent
  "Run `f` in one fiber-local agent scope."
  [agent-id f] (fiber/run-with-agent agent-id f))
(defn without-agent
  "Run `f` without an inherited fiber-local agent scope."
  [f] (fiber/run-without-agent f))
(defn with-tx-context
  "Run `f` with transaction context merged into the current scope."
  [context f] (fiber/run-with-tx-context context f))
(defn install-configuration-context!
  "Install resolved configuration for descendant asynchronous work."
  [configuration]
  (fiber/enter-tx-context! {:seon.config/configuration configuration}))

(defn on-commit!
  "Notify the current fiber's optional transaction observer."
  [report]
  (when-let [observe (:seon.db/on-commit! (current-tx-context))]
    (observe report))
  nil)

(defn resource-options
  "Return effective writer resource limits for a read request."
  [policy request]
  (let [configuration (:seon.config/configuration (current-tx-context))
        inherited (case policy
                    :query (if configuration
                             (config/database-query-policy configuration)
                             config/default-database-query-policy)
                    :pull (if configuration
                            (config/database-pull-policy configuration)
                            config/default-database-pull-policy))
        [max-work max-results max-result-weight]
        (case policy
          :query [(:seon.config.database.query/max-work inherited)
                  (:seon.config.database.query/max-results inherited)
                  (:seon.config.database.query/max-result-weight inherited)]
          :pull [(:seon.config.database.pull/max-work inherited)
                 (:seon.config.database.pull/max-results inherited)
                 (:seon.config.database.pull/max-result-weight inherited)])]
    {:datahike.resource/max-work (or (::db/max-work request) max-work)
     :datahike.resource/max-results (or (::db/max-results request) max-results)
     :datahike.resource/max-result-weight
     (or (::db/max-result-weight request) max-result-weight)}))

(defn- newer-database [current candidate]
  (cond
    (nil? current) candidate
    (> (:t candidate) (:t current)) candidate
    (< (:t candidate) (:t current)) current
    (= (:datahike/commit-id candidate) (:datahike/commit-id current)) current
    :else
    (throw (ex-info "One database transaction has conflicting commit identities."
                    {:seon.error/kind :core-bug
                     :seon.db/current-db current
                     :seon.db/candidate-db candidate}))))

(defn- cache-database-state [state database]
  (if (and state (protocol/database-value? database))
    (update-in state [::databases (:db-name database)] newer-database database)
    state))

(defn cache-database!
  "Cache a newer immutable database descriptor for its database name."
  [database]
  (swap! !session cache-database-state database)
  database)

(defn current-branch-head
  "Return the cached database's current branch head, when available."
  []
  (let [state @!session
        database (get-in state [::databases (::database-name state)])]
    (when (protocol/database-value? database)
      (db.branch/head-from-database-value database))))

(defn response-error
  "Flatten one unsuccessful protocol response and advance its current db."
  [response]
  (when-let [database (:seon.db/current-db response)] (cache-database! database))
  {:seon.error/message (::protocol/error response)
   :seon.error/kind (or (:seon.error/kind response) :core-bug)
   :seon.error/data
   (cond-> {::protocol/error-kind (::protocol/error-kind response)
            ::protocol/request-id (::protocol/request-id response)}
     (:seon.db/expected-db response)
     (assoc :seon.db/expected-db (:seon.db/expected-db response))
     (:seon.db/current-db response)
     (assoc :seon.db/current-db (:seon.db/current-db response))
     (::protocol/generated-candidate response)
     (assoc ::protocol/generated-candidate (::protocol/generated-candidate response))
     (contains? response ::protocol/canceled?)
     (assoc ::protocol/canceled? (::protocol/canceled? response))
     (contains? response ::protocol/running?)
     (assoc ::protocol/running? (::protocol/running? response)))})

(defn- request-on-session! [session request timeout-ms]
  (if-not (protocol/valid-request? request)
    (js/Promise.resolve
     (request-error "The database request is invalid."
                    {::protocol/request-id (::protocol/request-id request)
                     ::protocol/error (protocol/explain-request request)}))
    (-> (uds/request! {::uds/session session ::uds/message request
                       ::uds/timeout-ms timeout-ms})
        (.then
         (fn [response]
           (cond
             (= :seon.db.transport.uds.failure/busy (::uds/failure response))
             (session-error
              (or (::uds/message response)
                  "The database session has no request capacity.")
              {::protocol/request-id (::protocol/request-id request)
               :seon.error/ex-data response})
             (and (protocol/valid-response? response)
                  (= (::protocol/request-id request)
                     (::protocol/request-id response))) response
             :else
             (session-error "The database authority returned an invalid response."
                            {::protocol/request-id (::protocol/request-id request)
                             ::protocol/error
                             (protocol/explain-response response)}))))
        (.catch
         (fn [exception]
           (session-error
            (or (.-message exception) "The database session failed.")
            (cond-> {::protocol/request-id (::protocol/request-id request)}
              (ex-data exception)
              (assoc :seon.error/ex-data (ex-data exception)))))))))

(defn- session-event! [event]
  (when-let [database (:db-after event)] (cache-database! database))
  (when-let [handler (get-in @!session
                             [::interest-handlers
                              (::protocol/request-id event) ::handler])]
    (try
      (let [value (if (= protocol/datoms-event (::protocol/event event))
                    (select-keys event
                                 [:db-before :db-after :tx-data :tempids :tx-meta])
                    event)
            result (handler value)]
        (when (instance? js/Promise result)
          (.catch result
                  (fn [exception]
                    (seon-log/warn!
                     {:seon.log/source ::listen!
                      :seon.log/message
                      (str "handler async-rejected: "
                           (error/->message exception))})))))
      (catch :default exception
        (seon-log/warn! {:seon.log/source ::listen!
                         :seon.log/message
                         (str "handler threw: " (error/->message exception))})))))

(defn- active-session []
  (let [state @!session]
    (when (and (::session state) (string? (::database-name state))
               (uds/connected? (::session state)))
      state)))

(defn- session-result [state]
  {::db/database-name (::database-name state)
   ::db/db (get-in state [::databases (::database-name state)])
   ::db/capabilities (::capabilities state)})

(defn- stable-selection [selection] (dissoc selection ::db/initialization))

(defn- ^:async ensure-and-acquire! [session selection initialization]
  (let [database-name (::db/database-name selection)
        backend (::db/backend selection)
        database-path (::db/database-path selection)
        connection-id (::db.branch/connection-id selection)
        database-advanced? (::db/database-advanced? selection)
        ensured
        (await (request-on-session!
                session
                (protocol/ensure-database-request
                 (cond-> {::protocol/request-id (mint-id)
                          ::protocol/database-name database-name
                          ::protocol/backend backend}
                   database-path (assoc ::protocol/database-path database-path)
                   connection-id (assoc ::db.branch/connection-id connection-id)
                   initialization (assoc ::db/initialization initialization)))
                15000))
        _ (when-not (::protocol/success? ensured)
            (throw (ex-info "Opening the database failed." ensured)))
        acquired
        (await (request-on-session!
                session
                (protocol/acquire-database-request
                 {::protocol/request-id (mint-id)
                  ::protocol/database-name database-name
                  ::protocol/database-advanced? (not (false? database-advanced?))})
                15000))]
    (when-not (::protocol/success? acquired)
      (throw (ex-info "Acquiring the database failed." acquired)))
    acquired))

(declare open-session! connect-selection!)

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
                                  (cache-database-state (::db/db acquired)))]
                     (reset! accepted next)
                     next)
                   current)))
        (if-let [current @accepted]
          (session-result current)
          (throw (ex-info "Database session closed while refreshing."
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
        opening (js/Promise.
                 (fn [resolve reject]
                   (reset! resolve-opening resolve)
                   (reset! reject-opening reject)))]
    (swap! !session
           (fn [current]
             (if-let [claimed-state (claim-state current)]
               (do (reset! claimed? true)
                   (assoc claimed-state ::opening opening
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

(defn- claim-connect! [current selection initialization]
  (let [owner (or (::owner current) (js-obj))]
    (claim-opening!
     initialization
     (fn [latest]
       (when (and (or (nil? latest) (identical? owner (::owner latest)))
                  (nil? (::opening latest)))
         (assoc (or latest {}) ::owner owner ::selection selection
                ::interest-handlers (or (::interest-handlers latest) {}))))
     #(connect-selection! selection initialization owner))))

(defn- ^:async connect-selection! [selection initialization owner]
  (let [opened (atom nil)]
    (try
      (let [session
            (await (uds/connect!
                    {::uds/socket-path (::db/socket-path selection)
                     ::uds/on-event! session-event!
                     ::uds/on-close!
                     (fn [_]
                       (swap! !session
                              (fn [current]
                                (if (and (identical? owner (::owner current))
                                         (identical? @opened (::session current)))
                                  (dissoc current ::session ::opening
                                          ::opening-initialization ::database-name
                                          ::capabilities ::databases)
                                  current))))}))
            _ (reset! opened session)
            _ (swap! !session #(if (identical? owner (::owner %))
                                 (assoc % ::session session ::databases {}) %))
            capabilities-response
            (await (request-on-session!
                    session
                    (protocol/capabilities-request
                     {::protocol/request-id (mint-id)}) 5000))
            _ (when-not (::protocol/success? capabilities-response)
                (throw (ex-info "Database capability negotiation failed."
                                capabilities-response)))
            capabilities (::protocol/capabilities capabilities-response)
            _ (when-not
                (and (= (::uds/version session) (::protocol/version capabilities))
                     (= (::uds/configured-maximum-frame-bytes session)
                        (::protocol/maximum-frame-bytes capabilities)))
                (throw (ex-info "Database capabilities disagree with session admission."
                                {::protocol/version (::protocol/version capabilities)
                                 ::protocol/maximum-frame-bytes
                                 (::protocol/maximum-frame-bytes capabilities)
                                 ::uds/version (::uds/version session)
                                 ::uds/configured-maximum-frame-bytes
                                 (::uds/configured-maximum-frame-bytes session)
                                 :seon.error/kind :configuration})))
            acquired (await (ensure-and-acquire! session selection initialization))
            _ (doseq [[request-id entry] (::interest-handlers @!session)]
                (when (and (identical? owner (::owner @!session))
                           (identical? (::owner entry)
                                       (get-in @!session
                                               [::interest-handlers request-id
                                                ::owner])))
                  (let [response
                        (await (request-on-session!
                                session
                                (assoc (::request entry) ::db/db (::db/db acquired))
                                15000))]
                    (when (or (error-value? response)
                              (not (::protocol/success? response)))
                      (throw (ex-info "Restoring a database listener failed."
                                      response)))
                    (if (identical? (::owner entry)
                                    (get-in @!session
                                            [::interest-handlers request-id
                                             ::owner]))
                      (session-event!
                       {::protocol/event protocol/resynchronization-event
                        ::protocol/request-id request-id
                        :db-after (::db/db acquired)})
                      (await (request-on-session!
                              session
                              (protocol/unlisten-request
                               {::protocol/request-id (mint-id)
                                ::protocol/target-request-id request-id})
                              15000))))))
            database-name (::db/database-name selection)
            state (-> @!session
                      (assoc ::owner owner ::selection selection ::session session
                             ::database-name database-name ::capabilities capabilities)
                      (dissoc ::opening ::opening-initialization)
                      (cache-database-state (::db/db acquired)))]
        (swap! !session #(if (identical? owner (::owner %)) state %))
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
                     (select-keys current [::owner ::selection ::interest-handlers]))
                   current)))
        (throw exception)))))

(defn ^:async open-session!
  "Open and acquire this process's one multiplexed database session."
  [request]
  (let [selection (stable-selection request)
        initialization (::db/initialization request)
        current @!session]
    (cond
      (and current (not= selection (::selection current)))
      (throw (ex-info "Another database session owns this process."
                      {::selection selection :seon.error/kind :core-bug}))
      (::opening current)
      (if (= initialization (::opening-initialization current))
        (await (::opening current))
        (do (await (::opening current))
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
  []
  (let [closed (atom nil)]
    (swap! !session (fn [current] (reset! closed current) nil))
    (when-let [owner (::owner @closed)] (aset owner "closed" true))
    (if-let [session (::session @closed)] (uds/close! session) false)))

(defn attached?
  "True when this process has one live database session."
  [] (some? (active-session)))

(defn- ^:async active-or-reconnect! []
  (if-let [state (active-session)]
    state
    (if-let [selection (::selection @!session)]
      (try
        (await (open-session! selection))
        (or (active-session)
            (session-error "The database session did not reopen." {}))
        (catch :default exception
          (session-error
           (or (.-message exception) "The database session failed to reopen.")
           (cond-> {} (ex-data exception)
             (assoc :seon.error/ex-data (ex-data exception))))))
      (session-error "This process has no open database session." {}))))

(defn ^:async call!
  "Deliver one validated protocol request, reconnecting the session if needed."
  ([request] (await (call! request 15000)))
  ([request timeout-ms]
   (let [state (await (active-or-reconnect!))]
     (if (error-value? state)
       (update state :seon.error/data assoc
               ::protocol/request-id (::protocol/request-id request))
       (await (request-on-session! (::session state) request timeout-ms))))))

(defn ^:async transaction-call!
  "Deliver one transaction request, retaining identity across ambiguous retries."
  [request recoverable?]
  (let [selection (::selection @!session)
        owner (::owner @!session)]
    (loop [delay-ms 1 ambiguous? false]
      (if (and owner (true? (aget owner "closed")))
        (session-error "The database session was closed by its owner."
                       {::protocol/request-id (::protocol/request-id request)
                        :seon.error/ex-data {::uds/closed-by-owner? true}})
        (let [opened (if (active-session)
                       true
                       (if selection
                         (await (-> (open-session! selection)
                                    (.then (constantly true))
                                    (.catch
                                     (fn [exception]
                                       (session-error
                                        (or (.-message exception)
                                            "The database session failed to reopen.")
                                        (cond-> {::protocol/request-id
                                                 (::protocol/request-id request)}
                                          (ex-data exception)
                                          (assoc :seon.error/ex-data
                                                 (ex-data exception))))))))
                         false))
              opening-error? (error-value? opened)
              response (if opening-error? opened (await (call! request 120000)))]
          (if (or (recoverable? response) (and ambiguous? opening-error?))
            (do (await (js/Promise. (fn [resolve _]
                                     (js/setTimeout resolve delay-ms))))
                (recur (min 250 (* 2 delay-ms)) true))
            response))))))

(defn ^:async resolve-database!
  "Resolve or acquire a named database, consulting the descriptor cache first."
  [database-name acquire?]
  (let [state (await (active-or-reconnect!))]
    (if (error-value? state)
      state
      (let [name (or database-name (::database-name state))]
        (if-let [database (get-in state [::databases name])]
          database
          (let [request (if acquire?
                          (protocol/acquire-database-request
                           {::protocol/request-id (mint-id)
                            ::protocol/database-name name})
                          (protocol/resolve-head-request
                           {::protocol/request-id (mint-id)
                            ::protocol/database-name name}))
                response (await (call! request 15000))]
            (cond
              (error-value? response) response
              (not (::protocol/success? response)) (response-error response)
              :else (cache-database! (::db/db response)))))))))

(defn ^:async read-database!
  "Return the request's explicit or ambient database, else resolve the head."
  [request]
  (if-let [database (or (::db/db request) (::db/db (current-tx-context)))]
    database
    (await (resolve-database! (::db/database-name request) false))))

(defn ^:async request-database!
  "Return protocol request identity plus the selected immutable database."
  [request]
  (let [database (await (read-database! request))]
    (if (error-value? database)
      database
      {::protocol/request-id (or (::db/request-id request) (mint-id))
       ::db/db database})))

(defn ^:async resolve-head-response!
  "Resolve the current session's latest branch head response."
  []
  (if-let [state (active-session)]
    (await (call! (protocol/resolve-head-request
                   {::protocol/request-id (mint-id)
                    ::protocol/database-name (::database-name state)}) 15000))
    (session-error "This process has no open database session." {})))

(defn- ^:async listen-request!
  [{:seon.db/keys [db handler key query dependency-plan read-evidence
                   datom-patterns]
    :as input}]
  (if-let [state (active-session)]
    (let [database (or db (await (read-database! input)))]
      (if (error-value? database)
        database
        (let [public-key (or key (mint-id))
              request-id (str public-key)
              owner (js-obj)
              request
              (protocol/listen-request
               (cond-> {::protocol/request-id request-id ::db/db database}
                 datom-patterns (assoc ::protocol/datom-patterns datom-patterns)
                 (and (not datom-patterns) read-evidence)
                 (assoc ::db/read-evidence read-evidence)
                 (and (not datom-patterns) dependency-plan)
                 (assoc :datahike.read/dependency-plan dependency-plan)
                 (and (not datom-patterns) (not read-evidence)
                      (not dependency-plan) query)
                 (assoc ::protocol/query-form query)
                 (and (not datom-patterns) (not read-evidence)
                      (not dependency-plan) (not query))
                 (assoc :datahike.read/dependency-plan :all)))]
          (swap! !session assoc-in [::interest-handlers request-id]
                 {::owner owner ::handler handler ::key public-key
                  ::request request})
          (let [response (await (request-on-session! (::session state)
                                                     request 15000))]
            (if (and (not (error-value? response))
                     (::protocol/success? response))
              (do (when-let [database (:db-after response)]
                    (cache-database! database))
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
   (await (listen-request! (if (map? input-or-handler)
                             input-or-handler
                             {::db/handler input-or-handler}))))
  ([key handler]
   (await (listen-request! {::db/key key ::db/handler handler})))
  ([database key handler]
   (await (listen-request! {::db/db database ::db/key key
                            ::db/handler handler}))))

(defn ^:async unlisten!
  "Remove a listener by key and report whether this session owned it."
  [input]
  (let [key (if (and (map? input) (contains? input ::db/key))
              (::db/key input) input)
        request-id (str key)
        removed (atom nil)]
    (swap! !session
           (fn [current]
             (if-let [entry (get-in current [::interest-handlers request-id])]
               (do (reset! removed entry)
                   (update current ::interest-handlers dissoc request-id))
               current)))
    (if-not @removed
      false
      (if-let [state (active-session)]
        (let [response
              (await (request-on-session!
                      (::session state)
                      (protocol/unlisten-request
                       {::protocol/request-id (mint-id)
                        ::protocol/target-request-id request-id}) 15000))]
          (cond (error-value? response) response
                (not (::protocol/success? response)) (response-error response)
                :else true))
        true))))

(defn ^:async cancel!
  "Request cancellation by an existing public request identity."
  [request-id]
  (let [response (await (call! (protocol/cancel-request
                                {::protocol/request-id (mint-id)
                                 ::protocol/target-request-id (str request-id)})
                               15000))]
    (cond (error-value? response) response
          (not (::protocol/success? response)) (response-error response)
          :else (::protocol/canceled? response))))

(defn ^:async release!
  "Release this session's acquisition of one database value."
  [database]
  (let [response (await (call! (protocol/release-database-request
                                {::protocol/request-id (mint-id) ::db/db database})
                               15000))]
    (cond
      (error-value? response) response
      (not (::protocol/success? response)) (response-error response)
      :else (let [released? (::protocol/released? response)]
              (when released?
                (swap! !session update ::databases dissoc (:db-name database)))
              released?))))

(defn ^:async resolve-transaction-branch-head!
  "Resolve the branch head containing one transaction."
  [containing-branch-head transaction-id]
  (if-let [state (active-session)]
    (let [response
          (await (call!
                  (protocol/resolve-transaction-branch-head-request
                   {::protocol/request-id (mint-id)
                    ::protocol/database-name (::database-name state)
                    ::protocol/containing-branch-head containing-branch-head
                    ::protocol/transaction-id transaction-id}) 30000))]
      (cond (error-value? response) response
            (not (::protocol/success? response)) (response-error response)
            :else (::protocol/branch-head response)))
    (session-error "This process has no open database session." {})))

(def leaf
  "The Bun database leaf bound into the portable `seon.db` core."
  {:seon.db.leaf/call! call!
   :seon.db.leaf/transaction-call! transaction-call!
   :seon.db.leaf/resolve-db! resolve-database!
   :seon.db.leaf/read-db! read-database!
   :seon.db.leaf/request-db! request-database!
   :seon.db.leaf/cache-db! cache-database!
   :seon.db.leaf/context {:seon.db.leaf/current-tx-context current-tx-context
                          :seon.db.leaf/current-agent-id current-agent-id
                          :seon.db.leaf/with-read-evidence with-read-evidence
                          :seon.db.leaf/record-read-evidence!
                          record-read-evidence!
                          :seon.db.leaf/with-agent with-agent
                          :seon.db.leaf/without-agent without-agent
                          :seon.db.leaf/with-tx-context with-tx-context
                          :seon.db.leaf/install-configuration-context!
                          install-configuration-context!}
   :seon.db.leaf/uuid mint-id
   :seon.db.leaf/resource-options resource-options
   :seon.db.leaf/on-commit! on-commit!})
