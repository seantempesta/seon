(ns seon.db.host-interest-test
  "JVM host interest delivery and session-restoration regressions."
  (:require [clojure.test :refer [deftest is]]
            [seon.db.host :as db.host]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer-test-support :as writer-test]
            [seon.db.writer :as writer])
  (:import [java.io File]))

(defn- socket-path []
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory (str "host-interest-" (random-uuid) ".sock")))))

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _limit _entity-ids] [])})

(defn- wait-until!
  [timeout-ms predicate]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop []
      (cond
        (predicate) true
        (>= (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(defn- transact!
  [session database request-id transaction-data]
  (db.host/call!
   session
   (protocol/transaction-request
    {::protocol/request-id request-id
     :seon.db/db database
     ::protocol/transaction-data transaction-data})))

(deftest interest-delivery-resynchronizes-after-the-session-drops
  (let [database-name (str "host-interest-" (random-uuid))
        request-path (socket-path)
        server
        (writer-test/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/selected-processors 3
          ::writer/request-socket-path request-path})
        session
        (db.host/writer-session
         {::db.host/writer-socket-path request-path
          ::db.host/database-name database-name
          ::db.host/backend :memory
          ::db.host/pool-size 2
          ::db.host/interest-call-timeout-ms 3000
          ::db.host/interest-reconnect-backoff-ms 10})
        events (atom [])]
    (try
      (let [head (db.host/resolve-db! session)
            listener
            (db.host/listen!
             session
             {:seon.db/key :host-interest/proof
              :seon.db/handler #(swap! events conj %)})
            replacement
            (db.host/listen!
             session
             {:seon.db/key :host-interest/proof
              :seon.db/handler #(swap! events conj %)})
            schema-report
            (transact!
             session head "host-interest/schema"
             [{:db/ident :host-interest/value
               :db/valueType :db.type/string
               :db/cardinality :db.cardinality/one}])]
        (is (= :host-interest/proof listener))
        (is (= :host-interest/proof replacement))
        (is (::protocol/success? schema-report) (pr-str schema-report))
        (is (wait-until!
             3000
             #(some (fn [event]
                      (and (:tx-data event)
                           (= (:db-after schema-report) (:db-after event))))
                    @events)))
        (let [old-session
              (::db.host/session @(::db.host/interest-state session))]
          (uds/close-session! old-session))
        (is (wait-until!
             3000
             #(some (fn [event]
                      (= protocol/resynchronization-event
                         (::protocol/event event)))
                    @events)))
        (let [resynchronization
              (last
               (filter #(= protocol/resynchronization-event
                           (::protocol/event %))
                       @events))
              value-report
              (transact!
               session (:db-after resynchronization) "host-interest/value"
               [{:host-interest/value "delivered-after-restore"}])]
          (is (::protocol/success? value-report) (pr-str value-report))
          (is (wait-until!
               3000
               #(some (fn [event]
                        (= (:db-after value-report) (:db-after event)))
                      @events))))
        (is (true? (db.host/unlisten! session :host-interest/proof)))
        (is (= 0 (::db.host/listener-count
                  (db.host/interest-snapshot session)))))
      (finally
        (db.host/close-session! session)
        (writer/stop! server)
        (.delete (File. ^String request-path))))))
