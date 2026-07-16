(ns seon.db-session-test
  "Focused contract tests for the remote `seon.db` session facade."
  (:require
   [cljs.test :refer [async deftest is testing]]
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
