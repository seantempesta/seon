(ns seon.db-session-test
  "Focused contract tests for the remote `seon.db` session facade."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.agent]
   [seon.agent.message]
   [seon.db :as db]
   [seon.db.coordinate :as coordinate]
   [seon.db.protocol :as protocol]
   [seon.db.transport.uds :as uds]))

(def ^:private database-name "session-test")
(def ^:private socket-path "tmp/session-test.sock")

(def ^:private coordinate-0
  {::coordinate/database-id #uuid "00000000-0000-0000-0000-000000000001"
   ::coordinate/branch :db
   ::coordinate/commit-id #uuid "00000000-0000-0000-0000-000000000002"
   ::coordinate/t 536870912})

(def ^:private coordinate-1
  (assoc coordinate-0
         ::coordinate/commit-id
         #uuid "00000000-0000-0000-0000-000000000003"
         ::coordinate/t 536870913))

(def ^:private coordinate-2
  (assoc coordinate-0
         ::coordinate/commit-id
         #uuid "00000000-0000-0000-0000-000000000004"
         ::coordinate/t 536870914))

(defn- query-response
  [request result]
  (protocol/success
   {::protocol/request-id (::protocol/request-id request)
    ::protocol/database-name database-name
    ::protocol/attachment (coordinate/attachment coordinate-0)
    ::protocol/coordinate (::protocol/coordinate request)
    :datahike.query/result result
    :datahike.query/attribute-dependencies #{}
    :datahike.query/cache-evidence {}
    :datahike.query/resource-evidence {}}))

(defn- response-for
  [request]
  (case (::protocol/operation request)
    :seon.db.protocol.operation/capabilities
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/capabilities {:seon.db.capability/query true}})

    :seon.db.protocol.operation/ensure-database
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/database-name database-name
      ::coordinate/coordinate coordinate-0
      ::protocol/backend :memory})

    :seon.db.protocol.operation/acquire-database
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/database-name database-name
      ::protocol/attachment (coordinate/attachment coordinate-0)
      ::protocol/coordinate coordinate-0
      ::protocol/acquired? true})

    :seon.db.protocol.operation/resolve-head
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/database-name database-name
      ::protocol/attachment (coordinate/attachment coordinate-1)
      ::protocol/coordinate coordinate-1})

    :seon.db.protocol.operation/query
    (query-response request [::query-result])

    :seon.db.protocol.operation/transact
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/coordinate coordinate-1
      ::protocol/previous-coordinate coordinate-0
      ::protocol/temporary-ids {"value" 42}
      ::protocol/transaction-data []
      ::protocol/datoms-added 3
      ::protocol/datoms-retracted 0})

    :seon.db.protocol.operation/execute-many
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/database-name database-name
      ::protocol/attachment (coordinate/attachment coordinate-0)
      ::protocol/coordinate (::protocol/coordinate request)
      ::protocol/results
      [(protocol/success
        {:datahike.query/result [::grouped-result]
         :datahike.query/attribute-dependencies #{}
         :datahike.query/cache-evidence {}
         :datahike.query/resource-evidence {}})]})

    :seon.db.protocol.operation/index-page
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/database-name database-name
      ::protocol/attachment (coordinate/attachment coordinate-0)
      ::protocol/coordinate (::protocol/coordinate request)
      ::protocol/datoms [{:seon.db/e 1 :seon.db/a :db/ident
                          :seon.db/v :example/value :seon.db/tx 1
                          :seon.db/added? true}]
      ::protocol/complete? true})

    :seon.db.protocol.operation/knn-search
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/database-name database-name
      ::protocol/attachment (coordinate/attachment coordinate-0)
      ::protocol/coordinate (::protocol/coordinate request)
      ::protocol/hits [{:seon.embed/eid 1 :seon.embed/distance 0.25}]})

    :seon.db.protocol.operation/listen
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/database-name database-name
      ::protocol/attachment (coordinate/attachment coordinate-0)
      ::protocol/coordinate coordinate-0
      ::protocol/listening? true})

    :seon.db.protocol.operation/unlisten
    (protocol/success
     {::protocol/request-id (::protocol/request-id request)
      ::protocol/target-request-id (::protocol/target-request-id request)
      ::protocol/listening? false})))

(defn- with-fake-authority
  "Run `body` while the public UDS seam speaks valid protocol data."
  [body]
  (let [original-connect! uds/connect!
        original-request! uds/request!
        original-connected? uds/connected?
        original-close! uds/close!
        requests (atom [])
        connection-options (atom nil)
        connected? (atom true)
        session {::fake-session true}]
    (reset! @#'db/!session nil)
    (set! uds/connect!
          (fn [options]
            (reset! connection-options options)
            (js/Promise.resolve session)))
    (set! uds/request!
          (fn [{::uds/keys [message]}]
            (swap! requests conj message)
            (js/Promise.resolve (response-for message))))
    (set! uds/connected? (fn [_] @connected?))
    (set! uds/close!
          (fn [_]
            (let [was-connected? @connected?]
              (reset! connected? false)
              was-connected?)))
    (-> (js/Promise.resolve
         (body {::requests requests
                ::connection-options connection-options
                ::connected? connected?}))
        (.finally
         (fn []
           (db/close-session!)
           (reset! @#'db/!session nil)
           (set! uds/connect! original-connect!)
           (set! uds/request! original-request!)
           (set! uds/connected? original-connected?)
           (set! uds/close! original-close!))))))

(defn- open!
  []
  (db/open-session! {::db/socket-path socket-path
                     ::db/database-name database-name
                     ::db/backend :memory}))

(defn- schema-response
  [request coordinate installed]
  (protocol/success
   {::protocol/request-id (::protocol/request-id request)
    ::protocol/database-name database-name
    ::protocol/attachment (coordinate/attachment coordinate)
    ::protocol/coordinate coordinate
    ::protocol/schema installed}))

(defn- transaction-response
  [request before after]
  (protocol/success
   {::protocol/request-id (::protocol/request-id request)
    ::protocol/coordinate after
    ::protocol/previous-coordinate before
    ::protocol/temporary-ids {}
    ::protocol/transaction-data (::protocol/transaction-data request)
    ::protocol/datoms-added
    (count (::protocol/transaction-data request))
    ::protocol/datoms-retracted 0}))

(deftest open-negotiates-capabilities-before-database-acquisition
  (async done
    (-> (with-fake-authority
          (fn [{::keys [requests connection-options]}]
            (-> (open!)
                (.then
                 (fn [opened]
                   (is (= socket-path (::uds/socket-path @connection-options)))
                   (is (= database-name (::db/database-name opened)))
                   (is (= (coordinate/attachment coordinate-0)
                          (::db/attachment opened)))
                   (is (= coordinate-0 (::db/coordinate opened)))
                   (is (= [:seon.db.protocol.operation/capabilities
                           :seon.db.protocol.operation/ensure-database
                           :seon.db.protocol.operation/acquire-database]
                          (mapv ::protocol/operation @requests))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "session handshake rejected: " error))
                  (done))))))

(deftest provenance-genesis-and-preconditions-use-one-session
  (async done
    (-> (with-fake-authority
          (fn [{::keys [requests]}]
            (-> (open!)
                (.then
                 (fn [_]
                   (reset! requests [])
                   (let [current (atom coordinate-0)
                         installed (atom {})
                         facts (atom #{})
                         transaction-coordinates
                         (atom [coordinate-1 coordinate-2])]
                     (set! uds/request!
                           (fn [{::uds/keys [message]}]
                             (swap! requests conj message)
                             (js/Promise.resolve
                              (case (::protocol/operation message)
                                :seon.db.protocol.operation/resolve-head
                                (protocol/success
                                 {::protocol/request-id
                                  (::protocol/request-id message)
                                  ::protocol/database-name database-name
                                  ::protocol/attachment
                                  (coordinate/attachment @current)
                                  ::protocol/coordinate @current})

                                :seon.db.protocol.operation/schema
                                (schema-response message @current @installed)

                                :seon.db.protocol.operation/query
                                (query-response message @facts)

                                :seon.db.protocol.operation/execute-many
                                (let [required
                                      [:seon.agent/id :seon.user/id
                                       :seon.db/user :seon.db/process
                                       :seon.db.process/id
                                       :seon.schema/key :seon.schema/form]
                                      query-ready?
                                      (every? #(contains? @installed %)
                                              required)]
                                  (protocol/success
                                   {::protocol/request-id
                                    (::protocol/request-id message)
                                    ::protocol/database-name database-name
                                    ::protocol/attachment
                                    (coordinate/attachment @current)
                                    ::protocol/coordinate @current
                                    ::protocol/results
                                    (cond->
                                     [(protocol/success
                                       {::protocol/schema @installed})]
                                      (= 2
                                         (count (::protocol/members message)))
                                      (conj
                                       (if query-ready?
                                         (protocol/success
                                          {:datahike.query/result @facts
                                           :datahike.query/attribute-dependencies
                                           #{}
                                           :datahike.query/cache-evidence {}
                                           :datahike.query/resource-evidence {}})
                                         (protocol/failure
                                          {::protocol/error-kind
                                           protocol/database-error
                                           ::protocol/error
                                           "query attributes are not installed"}))))}))

                                :seon.db.protocol.operation/transact
                                (let [before @current
                                      after (first @transaction-coordinates)
                                      tx-data
                                      (::protocol/transaction-data message)]
                                  (swap! transaction-coordinates subvec 1)
                                  (reset! current after)
                                  (doseq [row tx-data]
                                    (when-let [attr (:seon.schema/key row)]
                                      (swap! installed assoc attr {})
                                      (swap! facts conj
                                             [:seon.schema/key attr]))
                                    (when-let [id (:seon.agent/id row)]
                                      (swap! facts conj
                                             [:seon.agent/id id]))
                                    (when-let [id (:seon.user/id row)]
                                      (swap! facts conj
                                             [:seon.user/id id]))
                                    (when-let [id
                                               (:seon.db.process/id row)]
                                      (swap! facts conj
                                             [:seon.db.process/id id])))
                                  (transaction-response message before after))))))
                     (-> (db/ensure-provenance! {})
                         (.then
                          (fn [fresh]
                            (is (= :fresh-genesis
                                   (::db/provenance-action fresh)))
                            (is (= (::coordinate/t coordinate-1)
                                   (::db/genesis-tx fresh)))
                            (is (= (::coordinate/t coordinate-2)
                                   (::db/human-tx fresh)))
                            (let [transactions
                                  (filterv
                                   #(= protocol/transact-operation
                                       (::protocol/operation %))
                                   @requests)
                                  genesis (first transactions)
                                  human (second transactions)
                                  genesis-data
                                  (::protocol/transaction-data genesis)]
                              (is (= 2 (count transactions)))
                              (is (nil?
                                   (::protocol/transaction-meta genesis))
                                  "genesis is deliberately unattributed")
                              (is (every? map? genesis-data))
                              (is (some :seon.schema/key genesis-data))
                              (is (not-any?
                                   (fn [row]
                                     (some #(= "db" (namespace %))
                                           (keys row)))
                                   genesis-data)
                                  "the client sends canonical forms, not raw Datahike declarations")
                              (is (= [{:seon.user/id "user"}]
                                     (::protocol/transaction-data human)))
                              (is (= {::db/user [:seon.agent/id "root"]
                                      ::db/process
                                      [:seon.db.process/id
                                       :seon.db.process/boot]}
                                     (::protocol/transaction-meta human))))
                            (db/ensure-provenance! {})))
                         (.then
                          (fn [converged]
                            (is (= :converged
                                   (::db/provenance-action converged)))
                            (is (= 2
                                   (count
                                    (filter
                                     #(= protocol/transact-operation
                                         (::protocol/operation %))
                                     @requests)))
                                "a converged ensure emits no transaction")
                            (db/assert-preconditions!
                             {::db/coordinate coordinate-2})))
                         (.then
                         (fn [evidence]
                            (is (true? (::db/preconditions? evidence)))
                            (is (= coordinate-2 (::db/coordinate evidence)))
                            (is (contains? (::db/installed-schema evidence)
                                           ::db/user))
                            (is (contains? (::db/installed-schema evidence)
                                           ::db/process))
                            (reset! requests [])
                            (db/assert-preconditions! {})))
                         (.then
                          (fn [evidence]
                            (is (= coordinate-2 (::db/coordinate evidence)))
                            (is (= [protocol/resolve-head-operation
                                    protocol/schema-operation]
                                   (mapv ::protocol/operation @requests)))
                                "one head resolution returns exact schema evidence")))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "session provenance rejected: " error))
                  (done))))))

(deftest canonical-schema-admission-is-one-remote-transaction
  (async done
    (-> (with-fake-authority
          (fn [{::keys [requests]}]
            (-> (open!)
                (.then
                 (fn [_]
                   (reset! requests [])
                   (set! uds/request!
                         (fn [{::uds/keys [message]}]
                           (swap! requests conj message)
                           (js/Promise.resolve
                            (transaction-response
                             message coordinate-0 coordinate-1))))
                   (db/with-tx-context
                     {::db/user [:seon.agent/id "root"]
                      ::db/process
                      [:seon.db.process/id :seon.db.process/boot]}
                     (fn []
                       (db/transact!
                        {::db/tx-data
                         [{:seon.schema/key :seon.test/title
                           :seon.schema/form ":string"}]})))))
                (.then
                 (fn [_]
                   (let [request (first @requests)
                         tx-data (::protocol/transaction-data request)]
                     (is (= [protocol/transact-operation]
                            (mapv ::protocol/operation @requests)))
                     (is (seq tx-data))
                     (is (every? :seon.schema/key tx-data))
                     (is (every? :seon.schema/form tx-data))
                     (is (not-any?
                          (fn [row]
                            (some #(= "db" (namespace %)) (keys row)))
                          tx-data))
                     (is (= {::db/user [:seon.agent/id "root"]
                             ::db/process
                             [:seon.db.process/id :seon.db.process/boot]}
                            (::protocol/transaction-meta request)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "runtime schema admission rejected: " error))
                  (done))))))

(deftest concurrent-same-selection-open-shares-the-complete-handshake
  (async done
    (-> (with-fake-authority
          (fn [{::keys [requests]}]
            (let [connect-count (atom 0)
                  resolve-connect (atom nil)
                  opening
                  (js/Promise.
                   (fn [resolve _reject]
                     (reset! resolve-connect resolve)))]
              (set! uds/connect!
                    (fn [_]
                      (swap! connect-count inc)
                      opening))
              (let [first-open (open!)
                    second-open (open!)]
                (@resolve-connect {::fake-session true})
                (-> (js/Promise.all #js [first-open second-open])
                    (.then
                     (fn [opened]
                       (is (= 1 @connect-count))
                       (is (= (aget opened 0) (aget opened 1)))
                       (is (= [:seon.db.protocol.operation/capabilities
                               :seon.db.protocol.operation/ensure-database
                               :seon.db.protocol.operation/acquire-database]
                              (mapv ::protocol/operation @requests))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "shared session opening rejected: " error))
                  (done))))))

(deftest reads-use-explicit-then-scoped-then-resolved-coordinates
  (async done
    (-> (with-fake-authority
          (fn [{::keys [requests]}]
            (-> (open!)
                (.then
                 (fn [_]
                   (reset! requests [])
                   (db/query {::db/query '[:find ?e :where [?e :db/ident]]
                              ::db/coordinate coordinate-0})))
                (.then
                 (fn [result]
                   (is (= [::query-result] result))
                   (is (= [:seon.db.protocol.operation/query]
                          (mapv ::protocol/operation @requests)))
                   (is (= coordinate-0
                          (::protocol/coordinate (first @requests))))
                   (reset! requests [])
                   (db/query {::db/query '[:find ?e :where [?e :db/ident]]
                              ::db/history? true
                              ::db/coordinate coordinate-0})))
                (.then
                 (fn [_]
                   (is (true? (::protocol/history? (first @requests))))
                   (is (= coordinate-0
                          (::protocol/coordinate (first @requests))))
                   (reset! requests [])
                   (db/with-tx-context
                     {::db/coordinate coordinate-0}
                     (fn []
                       (db/query {::db/query
                                  '[:find ?e :where [?e :db/ident]]})))))
                (.then
                 (fn [_]
                   (is (= [:seon.db.protocol.operation/query]
                          (mapv ::protocol/operation @requests)))
                   (is (= coordinate-0
                          (::protocol/coordinate (first @requests))))
                   (reset! requests [])
                   (db/query {::db/query
                              '[:find ?e :where [?e :db/ident]]})))
                (.then
                 (fn [_]
                   (is (= [:seon.db.protocol.operation/resolve-head
                           :seon.db.protocol.operation/query]
                          (mapv ::protocol/operation @requests)))
                   (is (= coordinate-1
                          (::protocol/coordinate (second @requests)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "coordinate precedence rejected: " error))
                  (done))))))

(deftest malformed-and-failed-responses-remain-ordinary-error-data
  (async done
    (-> (with-fake-authority
          (fn [_]
            (-> (open!)
                (.then
                 (fn [_]
                   (set! uds/request!
                         (fn [{::uds/keys [message]}]
                           (js/Promise.resolve
                            {::protocol/success? true
                             ::protocol/request-id
                             (str (::protocol/request-id message) "-wrong")})))
                   (db/query {::db/query '[:find ?e :where [?e :db/ident]]
                              ::db/coordinate coordinate-0})))
                (.then
                 (fn [error]
                   (is (= :core-bug (:seon.error/kind error)))
                   (is (string? (:seon.error/message error)))
                   (is (map? (:seon.error/data error)))
                   (set! uds/request!
                         (fn [{::uds/keys [message]}]
                           (js/Promise.resolve
                            (protocol/failure
                             {::protocol/error-kind protocol/database-error
                              ::protocol/error "The query is invalid."
                              :seon.error/kind :user-input
                              ::protocol/body
                              {::protocol/request-id
                               (::protocol/request-id message)}}))))
                   (db/query {::db/query '[:find ?e :where [?e :db/ident]]
                              ::db/coordinate coordinate-0})))
                (.then
                 (fn [error]
                   (is (= :user-input (:seon.error/kind error)))
                   (is (= "The query is invalid."
                          (:seon.error/message error)))
                   (is (= protocol/database-error
                          (get-in error [:seon.error/data
                                         ::protocol/error-kind]))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "ordinary error contract rejected: " error))
                  (done))))))

(deftest interests-dispatch-by-request-id-and-close-rejects-new-work
  (async done
    (-> (with-fake-authority
          (fn [{::keys [requests connection-options connected?]}]
            (let [events (atom [])]
              (-> (open!)
                  (.then
                   (fn [_]
                     (reset! requests [])
                     (db/listen!
                      {::db/key :routes
                       ::db/query '[:find ?route :where
                                    [?route :seon.route/pattern]]
                       ::db/handler #(swap! events conj %)})))
                  (.then
                   (fn [listening]
                     (let [request-id (::db/key listening)
                           on-event (::uds/on-event! @connection-options)]
                       (is (= ":routes" request-id))
                       (on-event {::protocol/event protocol/resynchronization-event
                                  ::protocol/request-id request-id
                                  ::protocol/coordinate coordinate-1})
                   (is (= [coordinate-1]
                              (mapv ::protocol/coordinate @events)))
                       ;; A listener mistake must not escape into the native
                       ;; session callback, whose contract closes the whole
                       ;; physical session on an owner callback failure.
                       (swap! @#'db/!session assoc-in
                              [::db/interest-handlers request-id ::db/handler]
                              (fn [_] (throw (js/Error. "listener failed"))))
                       (is (nil? (on-event
                                  {::protocol/event
                                   protocol/resynchronization-event
                                   ::protocol/request-id request-id
                                   ::protocol/coordinate coordinate-1})))
                       (is (true? (db/attached?)))
                       (db/unlisten! {::db/key request-id}))))
                  (.then
                   (fn [result]
                     (is (= {::db/ok? true} result))
                     (is (= [:seon.db.protocol.operation/listen
                             :seon.db.protocol.operation/unlisten]
                            (mapv ::protocol/operation @requests)))
                     (is (true? (db/close-session!)))
                     (is (false? @connected?))
                     (db/query {::db/query
                                '[:find ?e :where [?e :db/ident]]
                                ::db/coordinate coordinate-0})))
                  (.then
                   (fn [error]
                     (is (= :core-bug (:seon.error/kind error)))
                     (is (= "This process has no open database session."
                            (:seon.error/message error)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "interest lifecycle rejected: " error))
                  (done))))))

(deftest transaction-keeps-the-compact-application-envelope
  (async done
    (-> (with-fake-authority
          (fn [{::keys [requests]}]
            (-> (open!)
                (.then
                 (fn [_]
                   (reset! requests [])
                   (db/transact!
                    {:seon.db/tx-data
                     [{:db/ident :example/value
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}]})))
                (.then
                 (fn [result]
                   (is (true? (::db/ok? result)))
                   (is (= coordinate-1 (::db/coordinate result)))
                   (is (= 536870913 (::db/tx result)))
                   (is (= 3 (::db/added result)))
                   (is (= :seon.db.protocol.operation/transact
                          (::protocol/operation (first @requests)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "transaction facade rejected: " error))
                  (done))))))

(deftest grouped-index-and-knn-reads-stay-coordinate-pinned
  (async done
    (-> (with-fake-authority
          (fn [_]
            (let [member {::protocol/operation protocol/query-operation
                          ::protocol/query-form
                          '[:find ?e :where [?e :db/ident]]
                          ::protocol/arguments []}]
              (-> (open!)
                  (.then
                   (fn [_]
                     (db/execute-many
                      {::db/members [member]
                       ::db/coordinate coordinate-0})))
                  (.then
                   (fn [result]
                     (is (= coordinate-0 (::db/coordinate result)))
                     (is (= [::grouped-result]
                            (get-in result [::db/results 0
                                            :datahike.query/result])))
                     (db/index-page
                      {::db/index :eavt
                       ::db/direction :forward
                       ::db/index-limit 10
                       ::db/coordinate coordinate-0})))
                  (.then
                   (fn [page]
                     (is (true? (::db/complete? page)))
                     (is (= :db/ident (get-in page [::db/datoms 0
                                                    :seon.db/a])))
                     (db/knn-search!
                      {::protocol/query "related"
                       ::protocol/limit 3
                       ::db/coordinate coordinate-0})))
                  (.then
                   (fn [hits]
                     (is (= [{:seon.embed/eid 1
                              :seon.embed/distance 0.25}]
                            hits))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "coarse read facade rejected: " error))
                  (done))))))
