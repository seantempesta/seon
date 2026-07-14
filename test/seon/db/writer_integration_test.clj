(ns seon.db.writer-integration-test
  "End-to-end canonical writer request and publication tests."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [seon.db.coordinate :as coordinate]
            [seon.db.protocol :as protocol]
            [seon.db.registry :as registry]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer])
  (:import [java.io File]
           [java.nio.channels Channels SocketChannel]))

(defn- socket-path
  [label]
  (str "/tmp/seon-writer-" label "-" (random-uuid) ".sock"))

(defn- dependencies
  []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/transaction-transform (fn [_db-value transaction-data]
                                    transaction-data)
   ::writer/knn-search (fn [_db-value _request] {:seon.embed/hits []})})

(defn- wait-for-subscriber!
  [publisher]
  (let [deadline (+ (System/currentTimeMillis) 2000)]
    (loop []
      (cond
        (seq @(::uds/subscribers publisher)) true
        (> (System/currentTimeMillis) deadline)
        (throw (ex-info "publisher did not accept its subscriber" {}))
        :else (do (Thread/sleep 10) (recur))))))

(defn- call!
  [channel request]
  (uds/call! {::uds/channel channel ::uds/message request}))

(deftest canonical-writes-route-commit-and-publish-exactly-once
  (let [database-name (str "writer-integration-" (random-uuid))
        request-path (socket-path "request")
        publish-path (socket-path "publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        ^SocketChannel request-channel (uds/connect! request-path)
        ^SocketChannel publish-channel (uds/connect! publish-path)]
    (try
      (wait-for-subscriber! (::writer/publisher server))
      (let [publish-input (Channels/newInputStream publish-channel)
            ping-response
            (call! request-channel (protocol/ping-request))
            ensure-response
            (call! request-channel
                   (protocol/ensure-database-request
                    {::protocol/database-name database-name
                     ::protocol/backend :memory}))
            initial-coordinate (::coordinate/coordinate ensure-response)
            invalid-response
            (call! request-channel
                   {::protocol/operation protocol/transact-operation
                    ::protocol/request-id "missing-route"
                    ::protocol/transaction-data []})
            ensure-after-invalid
            (call! request-channel
                   (protocol/ensure-database-request
                    {::protocol/database-name database-name
                     ::protocol/backend :memory}))
            unknown-response
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name "not-open"
                     ::protocol/request-id "unknown-route"
                     ::protocol/transaction-data []}))
            schema-request
            (protocol/transaction-request
             {::protocol/database-name database-name
              ::protocol/request-id "writer/schema"
              ::protocol/transaction-data
              [{:db/ident :writer.person/id
                :db/valueType :db.type/string
                :db/cardinality :db.cardinality/one
                :db/unique :db.unique/identity}
               {:db/ident :writer.person/status
                :db/valueType :db.type/keyword
                :db/cardinality :db.cardinality/one}
               {:db/ident :writer.person/score
                :db/valueType :db.type/double
                :db/cardinality :db.cardinality/one}]})
            schema-response (call! request-channel schema-request)
            entity-request
            (protocol/transaction-request
             {::protocol/database-name database-name
              ::protocol/request-id "writer/entity"
              ::protocol/transaction-data
              [{:db/id "person-temp"
                :writer.person/id "alice"
                :writer.person/status :writer.status/ready
                :writer.person/score 1}]})
            entity-response (call! request-channel entity-request)
            schema-event (uds/read-frame publish-input)
            entity-event (uds/read-frame publish-input)
            recovered-response (call! request-channel entity-request)
            unexpected-event (future (uds/read-frame publish-input))
            no-event-sentinel ::no-event
            observed-after-retry (deref unexpected-event 250 no-event-sentinel)
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            stored
            (d/pull (d/db connection) '[*]
                    [:writer.person/id "alice"])]
        (is (= {::protocol/success? true ::protocol/pong? true}
               ping-response))
        (is (every? protocol/valid-response?
                    [ensure-response invalid-response ensure-after-invalid
                     unknown-response schema-response entity-response
                     recovered-response]))
        (is (coordinate/same-attachment?
             (coordinate/resolved (d/db connection))
             (::coordinate/coordinate ensure-response)))
        (is (= initial-coordinate
               (::coordinate/coordinate ensure-after-invalid))
            "an invalid request and an idempotent ensure write nothing")
        (is (= protocol/protocol-error
               (::protocol/error-kind invalid-response)))
        (is (= protocol/not-found-error
               (::protocol/error-kind unknown-response))
            "a named unknown database cannot fall back to the open database")
        (is (true? (::protocol/success? schema-response)))
        (is (true? (::protocol/success? entity-response)))
        (is (pos-int?
             (get (::protocol/temporary-ids entity-response) "person-temp")))
        (is (= [(::protocol/basis-t schema-response)
                (::protocol/basis-t entity-response)]
               (mapv ::protocol/basis-t [schema-event entity-event])))
        (is (= ["writer/schema" "writer/entity"]
               (mapv ::protocol/request-id [schema-event entity-event])))
        (is (every? #(= protocol/transaction-event (::protocol/event %))
                    [schema-event entity-event]))
        (is (every?
             empty?
             (for [message [schema-response entity-response
                            schema-event entity-event]]
               (filter protocol/reserved-attributes
                       (map second (::protocol/transaction-data message)))))
            "public response and event datoms omit receipt implementation")
        (is (every?
             empty?
             (for [message [schema-response entity-response
                            schema-event entity-event]]
               (filter protocol/reserved-attributes
                       (keys (or (::protocol/transaction-meta message) {})))))
            "public response and event metadata omit receipt implementation")
        (is (= (count (::protocol/transaction-data entity-response))
               (+ (::protocol/datoms-added entity-response)
                  (::protocol/datoms-retracted entity-response))))
        (is (true? (::protocol/recovered? recovered-response)))
        (is (= no-event-sentinel observed-after-retry)
            "a recovered delivery emits no duplicate transaction event")
        (is (= :writer.status/ready (:writer.person/status stored)))
        (is (instance? Double (:writer.person/score stored)))
        (is (= 1.0 (:writer.person/score stored))))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (try (.close publish-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest expected-basis-is-enforced-inside-the-serialized-writer
  (let [database-name (str "writer-fence-" (random-uuid))
        request-path (socket-path "fence-request")
        publish-path (socket-path "fence-publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        ^SocketChannel request-channel (uds/connect! request-path)]
    (try
      (let [opened
            (call! request-channel
                   (protocol/ensure-database-request
                    {::protocol/database-name database-name
                     ::protocol/backend :memory}))
            schema
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/schema"
                     ::protocol/transaction-data
                     [{:db/ident :writer.fence/id
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one
                       :db/unique :db.unique/identity}
                      {:db/ident :writer.fence/value
                       :db/valueType :db.type/string
                       :db/cardinality :db.cardinality/one}]}))
            frozen (::protocol/basis-t schema)
            accepted
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/accepted"
                     ::protocol/expected-basis-t frozen
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "accepted"}]}))
            committed (::protocol/basis-t accepted)
            rejected
            (call! request-channel
                   (protocol/transaction-request
                    {::protocol/database-name database-name
                     ::protocol/request-id "fence/rejected"
                     ::protocol/expected-basis-t frozen
                     ::protocol/transaction-data
                     [{:writer.fence/id "one"
                       :writer.fence/value "must-not-land"}]}))
            connection
            (::registry/conn
             (registry/lookup-connection
              {::registry/database-name (keyword database-name)}))
            stored (d/pull (d/db connection) '[*]
                           [:writer.fence/id "one"])]
        (is (true? (::protocol/success? opened)))
        (is (true? (::protocol/success? accepted)))
        (is (protocol/valid-response? rejected))
        (is (= protocol/stale-basis-error
               (::protocol/error-kind rejected)))
        (is (= frozen (::protocol/expected-basis-t rejected)))
        (is (= committed (::protocol/current-basis-t rejected)))
        (is (= committed (:max-tx (d/db connection)))
            "the rejected request creates no receipt or transaction")
        (is (= "accepted" (:writer.fence/value stored))
            "none of the stale request lands"))
      (finally
        (try (.close request-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))
