(ns seon.db-remote-contract-test
  "Selective public-facade contracts derived from Datahike's remote tests."
  (:require
   [cljs.test :refer [async deftest is testing]]
   [seon.db :as db]
   [seon.db.protocol :as protocol]
   [seon.db.transport.uds :as uds]
   [seon.instrument :as instrument]))

(def ^:private database-name "contract-default")
(def ^:private socket-path "tmp/db-remote-contract.sock")

(def ^:private database
  {:db-name database-name
   :t 536870913
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id #uuid "10000000-0000-0000-0000-000000000002"})

(defn- success
  [request body]
  (protocol/success (assoc body ::protocol/request-id
                           (::protocol/request-id request))))

(defn- response-for
  [request query-results]
  (let [name (or (::protocol/database-name request) database-name)
        current (assoc database :db-name name)]
    (case (::protocol/operation request)
      :seon.db.protocol.operation/capabilities
      (success request {::protocol/capabilities
                        {:seon.db.capability/query true}})

      :seon.db.protocol.operation/ensure-database
      (success request {::protocol/database-name name
                        ::db/db current
                        ::protocol/backend :memory})

      :seon.db.protocol.operation/acquire-database
      (success request {::protocol/database-name name
                        ::db/db current
                        ::protocol/acquired? true})

      :seon.db.protocol.operation/resolve-head
      (success request {::db/db current})

      :seon.db.protocol.operation/query
      (success request {:datahike.query/result
                        (get query-results (::protocol/query-form request))
                        :datahike.query/attribute-dependencies #{}
                        :datahike.query/cache-evidence {}
                        :datahike.query/resource-evidence {}})

      :seon.db.protocol.operation/pull
      (success request {::protocol/result {:example/id 1}})

      :seon.db.protocol.operation/pull-many
      (success request {::protocol/result [{:example/id 1} nil]})

      :seon.db.protocol.operation/execute-many
      (success request
               {::protocol/results
                (mapv (fn [_]
                        (protocol/success
                         {:datahike.query/result [::grouped-result]
                          :datahike.query/attribute-dependencies #{}
                          :datahike.query/cache-evidence {}
                          :datahike.query/resource-evidence {}}))
                      (::protocol/members request))})

      :seon.db.protocol.operation/index-page
      (success request
               {:datahike.index-page/datoms [[1 :example/id "one"
                                               536870913 true]]
                :datahike.index-page/complete? true})

      :seon.db.protocol.operation/transact
      (success request
               {:db-before (assoc current :t 536870912
                                  :datahike/commit-id
                                  #uuid "10000000-0000-0000-0000-000000000001")
                :db-after current
                :tx-data [[1 :example/id "one" 536870913 true]]
                :tempids {-1 1}
                :tx-meta {:seon.db/user [:seon.agent/id "root"]}})

      :seon.db.protocol.operation/listen
      (success request {:db-after current ::protocol/listening? true})

      :seon.db.protocol.operation/unlisten
      (success request {::protocol/target-request-id
                        (::protocol/target-request-id request)
                        ::protocol/listening? false})

      :seon.db.protocol.operation/cancel
      (success request {::protocol/target-request-id
                        (::protocol/target-request-id request)
                        ::protocol/canceled? true
                        ::protocol/running? false})

      :seon.db.protocol.operation/release-database
      (success request {::protocol/released? true})

      (success request {}))))

(defn- with-recording-authority
  [query-results body]
  (let [original-connect! uds/connect!
        original-request! uds/request!
        original-connected? uds/connected?
        original-close! uds/close!
        requests (atom [])
        connection-options (atom nil)
        connect-count (atom 0)
        connected? (atom true)
        session {::recording-session true}]
    (reset! @#'db/!session nil)
    (set! uds/connect!
          (fn [options]
            (swap! connect-count inc)
            (reset! connection-options options)
            (reset! connected? true)
            (js/Promise.resolve session)))
    (set! uds/request!
          (fn [{::uds/keys [message]}]
            (swap! requests conj message)
            (js/Promise.resolve (response-for message query-results))))
    (set! uds/connected? (fn [_] @connected?))
    (set! uds/close!
          (fn [_]
            (let [was-connected? @connected?]
              (reset! connected? false)
              was-connected?)))
    (-> (js/Promise.resolve
         (body {::requests requests
                ::connection-options connection-options
                ::connect-count connect-count
                ::connected? connected?
                ::session session}))
        (.finally
         (fn []
           (db/close-session!)
           (reset! @#'db/!session nil)
           (set! uds/connect! original-connect!)
           (set! uds/request! original-request!)
           (set! uds/connected? original-connected?)
           (set! uds/close! original-close!))))))

(defn- open! []
  (db/open-session! {::db/socket-path socket-path
                     ::db/database-name database-name
                     ::db/backend :memory}))

(def ^:private initialization
  {:seon.execution/artifact-digest
   "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
   :seon.db/program
   [{:seon.ns/name :seon.db
     :seon.ns/source "(ns seon.db)"}
    {:seon.fn/sym "seon.db/query"
     :seon.fn/ns [:seon.ns/name :seon.db]
     :seon.fn/source "(defn query [input] input)"}]
   :seon.db/initial-data [{:seon.user/id "user"}]})

(defn- operation-requests
  [requests operation]
  (filterv #(= operation (::protocol/operation %)) requests))

(defn- public-db-function
  "Return a public `seon.db` function without requiring an absent var."
  [function-name]
  (instrument/find-js-var 'seon.db function-name))

(deftest database-values-are-ordinary-immutable-maps
  (async done
    (-> (with-recording-authority
          {}
          (fn [_]
            (-> (open!)
                (.then
                 (fn [_]
                   (let [result (db/db)]
                     (is (instance? js/Promise result))
                     result)))
                (.then
                 (fn [current]
                   (is (= database current))
                   (let [earlier (db/as-of current 536870912)
                         later (db/since current 536870912)
                         all-datoms (db/history current)]
                     (is (= database current) "descriptor transforms are immutable")
                     (is (= 536870912 (:as-of earlier)))
                     (is (nil? (:since earlier)))
                     (is (= 536870912 (:since later)))
                     (is (nil? (:as-of later)))
                     (is (true? (:history all-datoms)))
                     (-> (db/db {::db/database-name "experiment-17"})
                         (.then
                          (fn [secondary]
                            (is (= "experiment-17" (:db-name secondary)))
                            (is (= (:datahike/commit-id database)
                                   (:datahike/commit-id secondary))))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "database-value contract rejected: " error))
                  (done))))))

(deftest initialization-is-forwarded-only-by-the-opening-host
  (async done
    (-> (with-recording-authority
          {}
          (fn [{::keys [requests]}]
            (-> (db/open-session!
                 {::db/socket-path socket-path
                  ::db/database-name database-name
                  ::db/backend :memory
                  ::db/initialization initialization})
                (.then
                 (fn [_]
                   (let [ensure-request
                         (first
                          (operation-requests
                           @requests protocol/ensure-database-operation))]
                     (is (= initialization
                            (::db/initialization ensure-request)))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "initialization forwarding rejected: " error))
                  (done))))))

(deftest transaction-redelivers-one-frozen-request-after-reconnect-failures
  (async done
    (-> (with-recording-authority
          {}
          (fn [{::keys [requests connection-options connect-count connected?
                        session]}]
            (-> (open!)
                (.then
                 (fn [_]
                   (let [reconnect-attempts (atom 0)
                         transaction-attempts (atom 0)
                         generated-candidates
                         [{:seon.db.id/key :example/generated
                           :seon.db.id/identity-attr :example/id
                           :seon.db.id/value "abcdefghijklmn"}]
                         tx-data [{:example/id "abcdefghijklmn"
                                   :example/value "frozen"}]
                         tx-meta {:seon.db/user [:seon.agent/id "root"]}]
                     (set! uds/connect!
                           (fn [options]
                             (swap! connect-count inc)
                             (reset! connection-options options)
                             (if (< (swap! reconnect-attempts inc) 3)
                               (js/Promise.reject
                                (ex-info
                                 "authority is restarting"
                                 {::uds/failure
                                  :seon.db.transport.uds.failure/closed}))
                               (do
                                 (reset! connected? true)
                                 (js/Promise.resolve session)))))
                     (set! uds/request!
                           (fn [{::uds/keys [message]}]
                             (swap! requests conj message)
                             (if (= protocol/transact-operation
                                    (::protocol/operation message))
                               (if (= 1 (swap! transaction-attempts inc))
                                 (do
                                   (reset! connected? false)
                                   ((::uds/on-close! @connection-options)
                                    (ex-info "socket closed" {}))
                                   (js/Promise.reject
                                    (ex-info
                                     "socket closed"
                                     {::uds/failure
                                      :seon.db.transport.uds.failure/closed})))
                                 (js/Promise.resolve
                                  (assoc (response-for message {})
                                         ::protocol/recovered? true)))
                               (js/Promise.resolve (response-for message {})))))
                     (-> ((deref #'db/submit-transaction!)
                          {::db/db database
                           ::db/expected-db database
                           :seon.db.id/generated-candidates
                           generated-candidates}
                          tx-data tx-meta)
                         (.then
                          (fn [report]
                            (let [transactions
                                  (operation-requests
                                   @requests protocol/transact-operation)]
                              (is (= 2 (count transactions)))
                              (is (= (first transactions) (second transactions))
                                  "recovery resends the exact immutable request")
                              (is (= generated-candidates
                                     (::protocol/generated-candidates
                                      (first transactions))))
                              (is (= tx-meta
                                     (::protocol/transaction-meta
                                      (first transactions))))
                              (is (= database
                                     (:seon.db/expected-db
                                      (first transactions))))
                              (is (= 4 @connect-count)
                                  "one open plus two failed and one successful reconnect")
                              (is (true?
                                   (:seon.db.id/recovered-commit? report)))))))))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "frozen transaction recovery failed: " error
                          "\n" (.-stack error)))
           (done))))))

(deftest owner-close-stops-transaction-recovery-during-reconnect
  (async done
    (-> (with-recording-authority
          {}
          (fn [{::keys [requests connection-options connect-count connected?]}]
            (-> (open!)
                (.then
                 (fn [_]
                   (set! uds/connect!
                         (fn [_options]
                           (swap! connect-count inc)
                           (js/Promise.
                            (fn [_resolve reject]
                              (js/setTimeout
                               #(reject
                                 (ex-info
                                  "authority is still unavailable"
                                  {::uds/failure
                                   :seon.db.transport.uds.failure/closed}))
                               20)))))
                   (set! uds/request!
                         (fn [{::uds/keys [message]}]
                           (swap! requests conj message)
                           (if (= protocol/transact-operation
                                  (::protocol/operation message))
                             (do
                               (reset! connected? false)
                               ((::uds/on-close! @connection-options)
                                (ex-info "socket closed" {}))
                               (js/Promise.reject
                                (ex-info
                                 "socket closed"
                                 {::uds/failure
                                  :seon.db.transport.uds.failure/closed})))
                             (js/Promise.resolve (response-for message {})))))
                   (let [result (db/transact! [{:db/ident :example/id}])]
                     (js/setTimeout db/close-session! 5)
                     result)))
                (.then
                 (fn [result]
                   (is (string? (:seon.error/message result)))
                   (is (= 2 @connect-count)
                       "owner close prevents another reconnect attempt"))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "owner-close recovery failed: " error
                          "\n" (.-stack error)))
           (done))))))

(deftest latest-database-value-is-a-monotonic-session-cache
  (async done
    (let [newest (assoc database
                        :t (inc (:t database))
                        :datahike/commit-id
                        #uuid "10000000-0000-0000-0000-000000000003")]
      (-> (with-recording-authority
            {}
            (fn [{::keys [requests connection-options]}]
              (-> (open!)
                  (.then
                   (fn [_]
                     (reset! requests [])
                     ((::uds/on-event! @connection-options)
                      {::protocol/event protocol/database-advanced-event
                       :db-after newest})
                     (db/db)))
                  (.then
                   (fn [current]
                     (is (= newest current))
                     (is (empty? @requests)
                         "reading the latest database never crosses the socket")
                     ((::uds/on-event! @connection-options)
                      {::protocol/event protocol/database-advanced-event
                       :db-after database})
                     (db/db)))
                  (.then
                   (fn [current]
                     (is (= newest current)
                         "an older delivery cannot move the session backwards"))))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "latest database cache rejected: " error))
                    (done)))))))

(deftest positional-and-namespaced-map-arities-form-the-same-requests
  (async done
    (let [query-form '[:find ?e :in $ ?minimum
                       :where [?e :example/rank ?rank]
                       [(>= ?rank ?minimum)]]]
      (-> (with-recording-authority
            {query-form #{[1]}}
            (fn [{::keys [requests]}]
              (-> (open!)
                  (.then (fn [_] (reset! requests [])
                           (db/query query-form database 4)))
                  (.then
                   (fn [_]
                     (let [positional (last (operation-requests
                                             @requests protocol/query-operation))]
                       (reset! requests [])
                       (-> (db/query {::db/query query-form
                                      ::db/args [database 4]})
                           (.then
                            (fn [_]
                              (let [mapped (last (operation-requests
                                                 @requests
                                                 protocol/query-operation))]
                                (is (= [database 4]
                                       (::protocol/arguments positional)))
                                (is (= (dissoc positional ::protocol/request-id)
                                       (dissoc mapped ::protocol/request-id))))))))))
                  (.then (fn [_] (db/pull database '[*] 1)))
                  (.then (fn [value] (is (= {:example/id 1} value))
                           (db/pull {::db/db database
                                     :seon.db/pull-pattern '[*]
                                     :seon.db/ref 1})))
                  (.then (fn [value] (is (= {:example/id 1} value))
                           (db/pull-many database '[*] [1 404])))
                  (.then (fn [value] (is (= [{:example/id 1} nil] value))
                           (db/pull-many {::db/db database
                                          :seon.db/pull-pattern '[*]
                                          :seon.db/refs [1 404]})))
                  (.then (fn [value] (is (= [{:example/id 1} nil] value))
                           (db/entity database 1)))
                  (.then (fn [value] (is (= {:example/id 1} value))
                           (db/entity {::db/db database :seon.db/ref 1})))
                  (.then (fn [value] (is (= {:example/id 1} value))
                           (db/index-page
                            database
                            {::db/index :eavt ::db/direction :forward
                             ::db/limit 2})))
                  (.then
                   (fn [page]
                     (is (= [[1 :example/id "one" 536870913 true]]
                            (:datahike.index-page/datoms page)))
                     (db/index-page
                      {::db/db database ::db/index :eavt
                       ::db/direction :forward ::db/limit 2})))
                  (.then
                   (fn [page]
                     (is (true? (:datahike.index-page/complete? page))))))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "public arity contract rejected: " error))
                    (done)))))))

(deftest query-preserves-source-argument-order-and-result-shapes
  (async done
    (let [relation-form '[:find ?e :where [?e :example/id]]
          scalar-form '[:find ?e . :where [?e :example/id]]
          collection-form '[:find [?e ...] :where [?e :example/id]]
          tuple-form '[:find [?e ?name] :where [?e :example/name ?name]]
          map-form '[:find ?e ?name :keys id name
                     :where [?e :example/name ?name]]
          multisource-form
          '[:find ?a ?b ?c :in $a ?ordinary $b $c
            :where [$a ?ea :example/id ?a]
                   [$b ?eb :example/id ?b]
                   [$c ?ec :example/id ?c]]
          db-b (assoc database :db-name "contract-b")
          db-c (assoc database :db-name "contract-c")
          descriptor-shaped-data
          {:db-name "ordinary" :t 1 :as-of nil :since nil :history false
           :datahike/commit-id
           #uuid "10000000-0000-0000-0000-000000000099"}
          inputs [database [descriptor-shaped-data] db-b db-c]
          expected {relation-form #{[1] [2]}
                    scalar-form 1
                    collection-form [1 2]
                    tuple-form [1 "one"]
                    map-form [{:id 1 :name "one"}]
                    multisource-form #{[1 2 3]}}]
      (-> (with-recording-authority
            expected
            (fn [{::keys [requests]}]
              (-> (open!)
                  (.then (fn [_] (reset! requests [])
                           (js/Promise.all
                            (into-array
                             (map db/query
                                  [relation-form scalar-form collection-form
                                   tuple-form map-form])))))
                  (.then
                   (fn [values]
                     (is (= [#{[1] [2]} 1 [1 2] [1 "one"]
                             [{:id 1 :name "one"}]]
                            (js->clj values)))
                     (reset! requests [])
                     (db/query {::db/query multisource-form ::db/args inputs})))
                  (.then
                   (fn [value]
                     (is (= #{[1 2 3]} value))
                     (is (= [protocol/query-operation]
                            (mapv ::protocol/operation @requests))
                         "explicit sources do not trigger an ambient head read")
                     (is (= inputs
                            (::protocol/arguments (first @requests)))
                         "only parsed top-level sources may be rehydrated"))))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "query shape/source contract rejected: " error))
                    (done)))))))

(deftest execute-many-resolves-and-attaches-one-database-value
  (async done
    (let [member {::protocol/operation protocol/query-operation
                  ::protocol/query-form
                  '[:find ?e :where [?e :example/id]]
                  ::protocol/arguments []}
          inherited (db/as-of database 536870912)
          other (assoc database :db-name "other")]
      (-> (with-recording-authority
            {}
            (fn [{::keys [requests]}]
              (-> (open!)
                  (.then
                   (fn [_]
                     (reset! requests [])
                     (db/execute-many
                      {::db/db database ::db/members [member member]})))
                  (.then
                   (fn [result]
                     (is (= #{::db/results} (set (keys result))))
                     (is (= 2 (count (::db/results result))))
                     (let [request (first
                                    (operation-requests
                                     @requests protocol/execute-many-operation))]
                       (is (= [database database]
                              (mapv ::db/db (::protocol/members request))))
                       (is (empty? (operation-requests
                                    @requests protocol/resolve-head-operation))))
                     (reset! requests [])
                     (db/with-tx-context
                      {::db/db inherited}
                      #(db/execute-many {::db/members [member]}))))
                  (.then
                   (fn [_]
                     (let [request (first
                                    (operation-requests
                                     @requests protocol/execute-many-operation))]
                       (is (= [inherited]
                              (mapv ::db/db (::protocol/members request)))))
                     (swap! @#'db/!session assoc ::db/databases {})
                     (reset! requests [])
                     (db/execute-many {::db/members [member member]})))
                  (.then
                   (fn [_]
                     (is (= [protocol/resolve-head-operation
                             protocol/execute-many-operation]
                            (mapv ::protocol/operation @requests))
                         "omission resolves the current database exactly once")
                     (let [request (second @requests)]
                       (is (= [database database]
                              (mapv ::db/db (::protocol/members request)))))
                     (reset! requests [])
                     (db/execute-many
                      {::db/members [(assoc member ::db/db database)
                                     (assoc member ::db/db other)]})))
                  (.then
                   (fn [result]
                     (is (= :core-bug (:seon.error/kind result)))
                     (is (= "execute-many requires one database value for every member."
                            (:seon.error/message result)))
                     (is (= [database other]
                            (get-in result
                                    [:seon.error/data
                                     ::db/member-databases])))
                     (is (empty? @requests)
                         "mixed member database values fail before transport"))))))
          (.then (fn [_] (done)))
          (.catch (fn [error]
                    (is false (str "execute-many database-value contract rejected: "
                                   error))
                    (done)))))))

(deftest all-authority-operations-return-promises-of-values
  (async done
    (-> (with-recording-authority
          {'[:find ?e :where [?e :example/id]] #{[1]}}
          (fn [{::keys [requests]}]
            (-> (open!)
                (.then
                 (fn [_]
                   (reset! requests [])
                   (let [query-result
                         (db/query '[:find ?e :where [?e :example/id]])
                         pull-result (db/pull '[*] 1)
                         transact-result
                         (db/transact! [{:db/ident :example/id}])]
                     (is (every? #(instance? js/Promise %)
                                 [query-result pull-result transact-result]))
                     (js/Promise.all #js [query-result pull-result
                                          transact-result]))))
                (.then
                 (fn [values]
                   (let [report (aget values 2)]
                     (is (= #{:db-before :db-after :tx-data :tempids :tx-meta}
                            (set (keys report))))
                     (is (= database (:db-after report)))
                     (is (= [[1 :example/id "one" 536870913 true]]
                            (:tx-data report))))
                   (db/transact! {::db/tx-data [{:db/ident :example/id}]
                                  ::db/tx-meta
                                  {:seon.db/user [:seon.agent/id "root"]}})))
                (.then
                 (fn [report]
                   (is (= #{:db-before :db-after :tx-data :tempids :tx-meta}
                          (set (keys report))))
                   (is (empty? (operation-requests
                                @requests protocol/resolve-head-operation))
                       "the cached latest database adds no read-before-call hop"))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "authority operation rejected instead of resolving: "
                                 error))
                  (done))))))

(deftest listener-registration-replacement-and-unlisten-are-session-owned
  (async done
    (-> (with-recording-authority
          {}
          (fn [{::keys [requests]}]
            (let [first-events (atom [])
                  replacement-events (atom [])]
              (-> (open!)
                  (.then (fn [_] (reset! requests [])
                           (db/listen! {::db/key :updates
                                        ::db/handler
                                        #(swap! first-events conj %)})))
                  (.then (fn [key] (is (= :updates key))
                           (is (= database
                                  (get-in @@#'db/!session
                                          [::db/databases database-name])))
                           (db/listen! :updates
                                       #(swap! replacement-events conj %))))
                  (.then
                   (fn [key]
                     (is (= :updates key))
                     (is (= [protocol/listen-operation protocol/listen-operation]
                            (mapv ::protocol/operation @requests))
                         "one session-owned callback map has no duplicate delivery")
                     (db/unlisten! {::db/key :updates})))
                  (.then
                   (fn [removed?]
                     (is (true? removed?))
                     (is (empty? @first-events))
                     (is (empty? @replacement-events))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "listener lifecycle contract rejected: " error))
                  (done))))))

(deftest listeners-restore-once-after-a-physical-session-reconnect
  (async done
    (-> (with-recording-authority
          {}
          (fn [{::keys [requests connection-options connected?]}]
            (let [events (atom [])]
              (-> (open!)
                  (.then
                   (fn [_]
                     (reset! requests [])
                     (db/listen! {::db/key :updates
                                  ::db/handler #(swap! events conj %)})))
                  (.then
                   (fn [_]
                     (let [closed-options @connection-options]
                       (reset! connected? false)
                       ((::uds/on-close! closed-options)
                        (ex-info "physical session closed" {}))
                       (open!))))
                  (.then
                   (fn [_]
                     (let [listen-requests
                           (operation-requests
                            @requests protocol/listen-operation)]
                       (is (= 2 (count listen-requests)))
                       (is (= (mapv ::protocol/request-id listen-requests)
                              [":updates" ":updates"])
                           "reconnect restores the one existing interest owner")
                       (is (= [{::protocol/event
                                protocol/resynchronization-event
                                ::protocol/request-id ":updates"
                                :db-after database}]
                              @events))
                       ((::uds/on-event! @connection-options)
                        {::protocol/event protocol/datoms-event
                         ::protocol/request-id ":updates"
                         :db-before (assoc database :t (dec (:t database)))
                         :db-after database
                         :tx-data [[1 :example/id "one" 536870913 true]]
                         :tempids {}
                         :tx-meta {}})
                       (is (= 2 (count @events))
                           "one physical event reaches the handler once")
                       (db/unlisten! :updates))))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "listener reconnect contract rejected: " error
                          "\n" (.-stack error)))
           (done))))))

(deftest unlisten-during-reconnect-prevents-physical-restoration
  (async done
    (-> (with-recording-authority
         {}
         (fn [{::keys [requests connection-options connected? session]}]
           (let [events (atom [])
                 reconnect (atom nil)
                 reconnect-started (atom nil)
                 started
                 (js/Promise.
                  (fn [resolve _reject]
                    (reset! reconnect-started resolve)))]
             (-> (open!)
                 (.then
                  (fn [_]
                    (reset! requests [])
                    (db/listen! {::db/key :updates
                                 ::db/handler #(swap! events conj %)})))
                 (.then
                  (fn [_]
                    (let [closed-options @connection-options]
                      (set! uds/connect!
                            (fn [options]
                              (reset! connection-options options)
                              (js/Promise.
                               (fn [resolve _reject]
                                 (reset! reconnect resolve)
                                 (@reconnect-started)))))
                      (reset! connected? false)
                      ((::uds/on-close! closed-options)
                       (ex-info "physical session closed" {}))
                      (let [opening (open!)]
                        (-> started
                            (.then (fn [_] (db/unlisten! :updates)))
                            (.then
                             (fn [removed?]
                               (is (nil?
                                    (get-in @@#'db/!session
                                            [::db/interest-handlers
                                             ":updates"])))
                               (reset! connected? true)
                               (@reconnect session)
                               (-> opening
                                   (.then (fn [_] removed?))))))))))
                 (.then
                  (fn [removed?]
                    (is (true? removed?))
                    (is (= 1
                           (count
                            (operation-requests
                             @requests protocol/listen-operation)))
                        "a removed handler is not restored on the new session")
                    (is (empty? @events))))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "unlisten during reconnect rejected: " error
                          "\n" (.-stack error)))
           (done))))))

(deftest owner-close-during-reconnect-prevents-listener-restoration
  (async done
    (-> (with-recording-authority
         {}
         (fn [{::keys [requests connection-options connected? session]}]
           (let [reconnect (atom nil)
                 reconnect-started (atom nil)
                 started
                 (js/Promise.
                  (fn [resolve _reject]
                    (reset! reconnect-started resolve)))]
             (-> (open!)
                 (.then
                  (fn [_]
                    (reset! requests [])
                    (db/listen! {::db/key :updates ::db/handler identity})))
                 (.then
                  (fn [_]
                    (let [closed-options @connection-options]
                      (set! uds/connect!
                            (fn [options]
                              (reset! connection-options options)
                              (js/Promise.
                               (fn [resolve _reject]
                                 (reset! reconnect resolve)
                                 (@reconnect-started)))))
                      (reset! connected? false)
                      ((::uds/on-close! closed-options)
                       (ex-info "physical session closed" {}))
                      (let [opening (open!)]
                        (-> started
                            (.then
                             (fn [_]
                               (is (false? (db/close-session!)))
                               (reset! connected? true)
                               (@reconnect session)
                               opening))
                            (.then
                             (fn [_]
                               (is false
                                   "owner-closed opening unexpectedly succeeded")))
                            (.catch
                             (fn [_]
                               (is (= 1
                                      (count
                                       (operation-requests
                                        @requests protocol/listen-operation)))
                                   "owner close never restores the listener"))))))))))))
        (.then (fn [_] (done)))
        (.catch
         (fn [error]
           (is false (str "owner-close listener contract rejected: " error
                          "\n" (.-stack error)))
           (done))))))

(deftest cancel-and-release-use-public-request-and-database-values
  (async done
    (-> (with-recording-authority
          {}
          (fn [{::keys [requests]}]
            (-> (open!)
                (.then
                 (fn [_]
                   (db/db {::db/database-name "experiment-17"})))
                (.then
                 (fn [secondary]
                   (is (= "experiment-17" (:db-name secondary)))
                   (let [cancel! (public-db-function 'cancel!)
                         release (public-db-function 'release)]
                     (is (fn? cancel!) "`seon.db/cancel!` is public")
                     (is (fn? release) "`seon.db/release` is public")
                     (when (and cancel! release)
                       (reset! requests [])
                       (-> (cancel! "query-17")
                           (.then
                            (fn [_]
                              (release secondary))))))))
                (.then
                 (fn [_]
                   (when (seq @requests)
                     (let [[cancel release] @requests]
                       (is (= "query-17" (::protocol/target-request-id cancel)))
                       (is (= "experiment-17"
                              (get-in release [::db/db :db-name]))))))))))
        (.then (fn [_] (done)))
        (.catch (fn [error]
                  (is false (str "cancel/release contract rejected: " error))
                  (done))))))
