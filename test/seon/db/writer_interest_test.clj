(ns seon.db.writer-interest-test
  "Physical-session selective committed-report delivery tests."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.committed-report :as committed-report]
            [seon.db.coordinate :as coordinate]
            [seon.db.executor :as executor]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer])
  (:import [java.io File]
           [java.nio.channels Channels SocketChannel]
           [java.util.concurrent.atomic AtomicBoolean]))

(defn- socket-path
  [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory (str "seon-interest-" label "-" (random-uuid) ".sock")))))

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _limit _entity-ids] [])})

(defn- call!
  [channel request]
  (uds/call! {::uds/channel channel ::uds/message request}))

(defn- acquire!
  [channel database-name label]
  (let [head
        (call! channel
               (protocol/resolve-head-request
                {::protocol/request-id (str label "/head")
                 ::protocol/database-name database-name}))]
    (call! channel
           (protocol/acquire-database-request
            {::protocol/request-id (str label "/acquire")
             ::protocol/database-name database-name
             ::protocol/attachment (::protocol/attachment head)}))))

(defn- read-next
  [channel]
  (uds/read-frame (Channels/newInputStream ^SocketChannel channel)))

(defn- wait-until!
  [predicate]
  (let [deadline (+ (System/currentTimeMillis) 3000)]
    (loop []
      (cond
        (predicate) true
        (> (System/currentTimeMillis) deadline) false
        :else (do (Thread/sleep 5) (recur))))))

(defn- transact!
  [channel database-name request-id transaction-data]
  (call! channel
         (protocol/transaction-request
          {::protocol/request-id request-id
           ::protocol/database-name database-name
           ::protocol/transaction-data transaction-data})))

(defn- fake-transport-connection
  [sent closed]
  (let [connection
        {::writer/connection-lock (Object.)
         ::writer/closed? (atom false)
         ::writer/closing? (AtomicBoolean. false)
         ::writer/acquisitions (atom #{})
         ::writer/interests (atom {})
         ::writer/close! #(deliver closed true)
         ::writer/send!
         (fn [message]
           (swap! sent conj message)
           {::uds/send-status uds/send-accepted})}]
    connection))

(deftest physical-connections-receive-only-matching-committed-datoms
  (let [database-name (str "writer-interest-" (random-uuid))
        request-path (socket-path "request")
        publish-path (socket-path "publish")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/selected-processors 3
          ::writer/request-socket-path request-path
          ::writer/publish-socket-path publish-path})
        ^SocketChannel a (uds/connect! request-path)
        ^SocketChannel b (uds/connect! request-path)
        ^SocketChannel writer-channel (uds/connect! request-path)]
    (try
      (let [acquired-a (acquire! a database-name "interest/a")
            acquired-b (acquire! b database-name "interest/b")
            _ (acquire! writer-channel database-name "interest/writer")
            _
            (transact!
             writer-channel database-name "interest/schema"
             [{:db/ident :interest/a
               :db/valueType :db.type/string
               :db/cardinality :db.cardinality/one}
              {:db/ident :interest/b
               :db/valueType :db.type/string
               :db/cardinality :db.cardinality/one}])
            listen-a-id "interest/listen-a"
            listen-b-id "interest/listen-b"
            listen-a
            (call! a
                   (protocol/listen-request
                    {::protocol/request-id listen-a-id
                     ::protocol/database-name database-name
                     ::protocol/attachment (::protocol/attachment acquired-a)
                     ::protocol/query-form
                     '[:find ?value :where [_ :interest/a ?value]]}))
            listen-b
            (call! b
                   (protocol/listen-request
                    {::protocol/request-id listen-b-id
                     ::protocol/database-name database-name
                     ::protocol/attachment (::protocol/attachment acquired-b)
                     ::protocol/datom-patterns [{:seon.db/a :interest/b}]}))
            pending-b (future (read-next b))
            transaction-a
            (transact! writer-channel database-name "interest/write-a"
                       [{:interest/a "only-a"}])
            event-a (deref (future (read-next a)) 5000 ::timed-out)]
        (when-not (and (::protocol/listening? listen-a)
                       (::protocol/listening? listen-b))
          (throw (ex-info "listen setup failed" {:a listen-a :b listen-b})))
        (is (::protocol/listening? listen-a) (pr-str listen-a))
        (is (::protocol/listening? listen-b) (pr-str listen-b))
        (is (= (::protocol/coordinate listen-a)
               (::protocol/coordinate listen-b)))
        (is (= protocol/datoms-event (::protocol/event event-a)))
        (is (= listen-a-id (::protocol/request-id event-a)))
        (is (= :interest/a (get-in event-a [::protocol/datoms 0 :seon.db/a])))
        (is (= (::protocol/coordinate transaction-a)
               (::protocol/coordinate event-a)))
        (is (= ::still-waiting (deref pending-b 100 ::still-waiting))
            "an unrelated attribute does not wake the sibling session")

        (transact! writer-channel database-name "interest/write-b"
                   [{:interest/b "only-b"}])
        (let [event-b (deref pending-b 5000 ::timed-out)]
          (is (= protocol/datoms-event (::protocol/event event-b)))
          (is (= listen-b-id (::protocol/request-id event-b)))
          (is (= :interest/b
                 (get-in event-b [::protocol/datoms 0 :seon.db/a]))))

        (let [unlisten
              (call! a
                     (protocol/unlisten-request
                      {::protocol/request-id "interest/unlisten-a"
                       ::protocol/target-request-id listen-a-id}))
              after-ack (future (read-next a))]
          (is (false? (::protocol/listening? unlisten)))
          (transact! writer-channel database-name "interest/write-a-again"
                     [{:interest/a "after-ack"}])
          (is (= ::still-waiting (deref after-ack 150 ::still-waiting))
              "no event follows the unlisten acknowledgement")
          (.close a))
        (.close b)
        (is (wait-until!
             #(empty? (::writer/by-scope
                       @(::writer/interest-state (::writer/runtime server)))))
            "disconnect removes the final active interest and source"))
      (finally
        (try (.close a) (catch Throwable _))
        (try (.close b) (catch Throwable _))
        (try (.close writer-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        (.delete (File. publish-path))))))

(deftest a-report-gap-replaces-the-source-and-addresses-one-resynchronization
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :read}
        sent (atom [])
        closed (promise)
        transport-connection (fake-transport-connection sent closed)
        runtime {::writer/interest-lock (Object.)
                 ::writer/interest-state
                 (atom {::writer/by-scope {} ::writer/by-source {}})}]
    (d/create-database configuration)
    (let [connection (d/connect configuration)]
      (try
        (let [db-value (d/db connection)
              identity (d/committed-value-identity db-value)
              attachment (coordinate/attachment (coordinate/resolved db-value))
              scope {::executor/database-name "gap"
                     ::coordinate/attachment attachment
                     ::executor/connection-id
                     (:datahike.value/connection-id identity)
                     ::executor/generation (:datahike.value/generation identity)}
              request
              (protocol/listen-request
               {::protocol/request-id "gap/listen"
                ::protocol/database-name "gap"
                ::protocol/attachment attachment
                ::protocol/datom-patterns [{:seon.db/a :gap/value}]})]
          (locking (::writer/interest-lock runtime)
            (#'writer/install-interest-locked!
             runtime transport-connection request connection "gap" attachment))
          (let [source
                (get-in @(::writer/interest-state runtime)
                        [::writer/by-scope scope ::writer/source])]
            (dotimes [index 257]
              (committed-report/offer! source
                                       (::executor/generation scope)
                                       {:sequence index}))
            (#'writer/execute-delivery!
             runtime {::writer/scope scope ::writer/source source})
            (let [replacement
                  (get-in @(::writer/interest-state runtime)
                          [::writer/by-scope scope ::writer/source])]
              (is (not (identical? source replacement)))
              (is (= [{::protocol/event protocol/resynchronization-event
                       ::protocol/request-id "gap/listen"
                       ::protocol/coordinate
                       (coordinate/resolved (d/db connection))}]
                     @sent))
              (is (= :datahike.committed-report.status/closed
                     (:datahike.committed-report/status
                      (committed-report/evidence source))))
              (committed-report/close! replacement false))))
        (finally
          (d/release connection)
          (d/delete-database configuration))))))

(deftest authority-pressure-closes-only-the-addressed-physical-session
  (let [closed (promise)
        owner (Object.)
        connection
        {::writer/connection-lock (Object.)
         ::writer/closed? (atom false)
         ::writer/closing? (AtomicBoolean. false)
         ::writer/interests
         (atom {"pressure/listen" {::writer/owner owner}})
         ::writer/close! #(deliver closed true)
         ::writer/send!
         (fn [_message]
           {::uds/send-status uds/send-authority-full})}]
    (is (= uds/send-authority-full
           (#'writer/send-interest-event!
            connection "pressure/listen" owner
            {::protocol/event protocol/resynchronization-event
             ::protocol/request-id "pressure/listen"
             ::protocol/coordinate
             {::coordinate/database-id (random-uuid)
              ::coordinate/branch :db
              ::coordinate/commit-id (random-uuid)
              ::coordinate/t 1}})))
    (is (true? (deref closed 1000 false)))
    (is (.get ^AtomicBoolean (::writer/closing? connection)))))

(deftest query-interests-require-a-usable-database-dependency
  (let [failure
        (try
          (#'writer/listen-interest
           (protocol/listen-request
            {::protocol/request-id "empty/listen"
             ::protocol/database-name "empty"
             ::protocol/attachment
             {::coordinate/database-id (random-uuid)
              ::coordinate/branch :db}
             ::protocol/query-form '[:find ?input :in ?input]})
           {::executor/database-name "empty"
            ::coordinate/attachment
            {::coordinate/database-id (random-uuid)
             ::coordinate/branch :db}
            ::executor/connection-id [(random-uuid) :db]
            ::executor/generation (random-uuid)})
          nil
          (catch clojure.lang.ExceptionInfo exception exception))]
    (is (instance? clojure.lang.ExceptionInfo failure))
    (is (re-find #"must depend on a database attribute"
                 (.getMessage ^Throwable failure)))))
