(ns seon.db.writer-interest-test
  "Physical-session selective committed-report delivery tests."
  (:require [clojure.test :refer [deftest is]]
            [datahike.api :as d]
            [datahike.committed-report :as committed-report]
            [seon.db.branch :as branch]
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

(defn- database-value
  [database-name native]
  {:db-name database-name
   :store-id (:datahike.value/connection-id
              (d/committed-value-identity native))
   :t (:max-tx native)
   :as-of nil
   :since nil
   :history false
   :datahike/commit-id (d/commit-id native)})

(defn- acquire!
  ([channel database-name label]
   (acquire! channel database-name label true))
  ([channel database-name label database-advanced?]
   (call! channel
          (protocol/acquire-database-request
           {::protocol/request-id (str label "/acquire")
            ::protocol/database-name database-name
            ::protocol/database-advanced? database-advanced?}))))

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
  [channel database request-id transaction-data]
  (call! channel
         (protocol/transaction-request
          {::protocol/request-id request-id
           :seon.db/db database
           ::protocol/transaction-data transaction-data})))

(defn- fake-transport-connection
  [sent closed]
  (let [connection
        {::writer/connection-lock (Object.)
         ::writer/closed? (atom false)
         ::writer/closing? (AtomicBoolean. false)
         ::writer/acquisitions (atom #{})
         ::writer/database-advanced-acquisitions (atom #{})
         ::writer/interests (atom {})
         ::writer/close! #(deliver closed true)
         ::writer/send!
         (fn [message]
           (swap! sent conj message)
           {::uds/send-status uds/send-accepted})}]
    connection))

(deftest database-advanced-delivery-requires-explicit-acquisition
  (let [sent (atom [])
        closed (promise)
        connection (fake-transport-connection sent closed)
        acquisition ["proof" [:proof :branch]]
        event {::protocol/event protocol/database-advanced-event}]
    (swap! (::writer/acquisitions connection) conj acquisition)
    (is (nil? (@#'writer/send-database-event!
               connection (first acquisition) (second acquisition) event)))
    (is (empty? @sent))
    (swap! (::writer/database-advanced-acquisitions connection)
           conj acquisition)
    (is (= uds/send-accepted
           (@#'writer/send-database-event!
            connection (first acquisition) (second acquisition) event)))
    (is (= [event] @sent))))

(deftest an-acquisition-can-decline-database-advanced-events
  (let [database-name (str "writer-no-database-advanced-" (random-uuid))
        request-path (socket-path "no-database-advanced")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/selected-processors 3
          ::writer/request-socket-path request-path})
        ^SocketChannel quiet (uds/connect! request-path)
        ^SocketChannel writer-channel (uds/connect! request-path)]
    (try
      (let [quiet-acquired (acquire! quiet database-name "quiet" false)
            writer-acquired (acquire! writer-channel database-name "writer")
            pending (future
                      (try
                        (read-next quiet)
                        (catch java.nio.channels.AsynchronousCloseException _
                          ::socket-closed)))
            transaction
            (transact!
             writer-channel (:seon.db/db writer-acquired) "writer/schema"
             [{:db/ident :quiet-proof/value
               :db/valueType :db.type/string
               :db/cardinality :db.cardinality/one}])]
        (is (= (:seon.db/db quiet-acquired) (:db-before transaction)))
        (is (= ::no-database-event
               (deref pending 250 ::no-database-event)))
        (.close quiet)
        (is (= ::socket-closed
               (deref pending 3000 ::read-did-not-finish))))
      (finally
        (try (.close quiet) (catch Throwable _))
        (try (.close writer-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(defn- interest-connection
  [server request-id]
  (some (fn [session]
          (let [connection @(::uds/owner session)]
            (when (and (map? connection)
                       (contains? @(::writer/interests connection) request-id))
              connection)))
        (vals @(::uds/connections (::writer/request-server server)))))

(deftest repeated-listen-id-replaces-the-original-interest
  (let [database-name (str "writer-interest-duplicate-" (random-uuid))
        request-path (socket-path "duplicate-request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/selected-processors 3
          ::writer/request-socket-path request-path})
        ^SocketChannel listener (uds/connect! request-path)
        ^SocketChannel writer-channel (uds/connect! request-path)
        request-id "duplicate/listen"]
    (try
      (let [acquired (acquire! listener database-name "duplicate/listener")
            writer-acquired (acquire! writer-channel database-name "duplicate/writer")
            schema-report
            (transact!
             writer-channel (:seon.db/db writer-acquired) "duplicate/schema"
             [{:db/ident :duplicate/original
               :db/valueType :db.type/string
               :db/cardinality :db.cardinality/one}
              {:db/ident :duplicate/replacement
               :db/valueType :db.type/string
               :db/cardinality :db.cardinality/one}])
            original
            (call! listener
                   (protocol/listen-request
                    {::protocol/request-id request-id
                     :seon.db/db (:db-after schema-report)
                     ::protocol/datom-patterns
                     [{:seon.db/a :duplicate/original}]}))
            connection (interest-connection server request-id)
            replacement
            (call! listener
                   (protocol/listen-request
                    {::protocol/request-id request-id
                     :seon.db/db (:db-after schema-report)
                     ::protocol/datom-patterns
                     [{:seon.db/a :duplicate/replacement}]}))]
        (is (::protocol/listening? original) (pr-str original))
        (is (= (:db-after schema-report) (:db-after original)))
        (is (= (:seon.db/db acquired)
               (:db-before schema-report)))
        (is (some? connection))
        (is (::protocol/listening? replacement) (pr-str replacement))
        (is (= [{:seon.db/a :duplicate/replacement}]
               (get-in @(::writer/interests connection)
                       [request-id ::writer/patterns])))
        (is (= #{:duplicate/replacement}
               (set (keys
                     (get-in @(::writer/interest-state (::writer/runtime server))
                             [::writer/by-scope
                              (get-in @(::writer/interests connection)
                                      [request-id ::writer/scope])
                              ::writer/by-attribute])))))

        (let [original-transaction
              (transact! writer-channel (:db-after schema-report)
                         "duplicate/write-original"
                         [{:duplicate/original "replaced"}])
              replacement-transaction
              (transact! writer-channel (:db-after original-transaction)
                         "duplicate/write-replacement"
                         [{:duplicate/replacement "still-live"}])
              events (repeatedly 3
                                 #(deref (future (read-next listener))
                                         5000 ::timed-out))
              event (some #(when (= protocol/datoms-event
                                     (::protocol/event %)) %)
                          events)]
          (is (= 2 (count (filter #(= protocol/database-advanced-event
                                      (::protocol/event %))
                                  events))))
          (is (= protocol/datoms-event (::protocol/event event)))
          (is (protocol/valid-response? event))
          (is (= (get-in event [:db-after :store-id])
                 (get-in event [:db-before :store-id])))
          (is (= request-id (::protocol/request-id event)))
          (is (= :duplicate/replacement
                 (some (fn [[_entity attribute]]
                         (when (= :duplicate/replacement attribute) attribute))
                       (:tx-data event))))
          (is (= (:db-after replacement-transaction) (:db-after event)))
          (is (= (:tx-data replacement-transaction) (:tx-data event))))

        (let [unlisten
              (call! listener
                     (protocol/unlisten-request
                      {::protocol/request-id "duplicate/unlisten"
                       ::protocol/target-request-id request-id}))]
          (is (false? (::protocol/listening? unlisten)))
          (is (empty? @(::writer/interests connection)))
          (is (= {::writer/by-scope {} ::writer/by-source {}}
                 @(::writer/interest-state (::writer/runtime server)))))
        (.close listener)
        (is (wait-until! #(deref (::writer/closed? connection)))
            "disconnect completes with no retained interest state")
        (is (= {::writer/by-scope {} ::writer/by-source {}}
               @(::writer/interest-state (::writer/runtime server)))))
      (finally
        (try (.close listener) (catch Throwable _))
        (try (.close writer-channel) (catch Throwable _))
        (writer/stop! server)
        (.delete (File. request-path))
        nil))))

(deftest physical-connections-receive-only-matching-committed-datoms
  (let [database-name (str "writer-interest-" (random-uuid))
        request-path (socket-path "request")
        server
        (writer/start!
         {::writer/dependencies (dependencies)
          ::writer/database-name database-name
          ::writer/backend :memory
          ::writer/selected-processors 3
          ::writer/request-socket-path request-path})
        ^SocketChannel a (uds/connect! request-path)
        ^SocketChannel b (uds/connect! request-path)
        ^SocketChannel writer-channel (uds/connect! request-path)]
    (try
      (let [acquired-a (acquire! a database-name "interest/a")
            acquired-b (acquire! b database-name "interest/b")
            writer-acquired (acquire! writer-channel database-name "interest/writer")
            schema-report
            (transact!
             writer-channel (:seon.db/db writer-acquired) "interest/schema"
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
                     :seon.db/db (:db-after schema-report)
                     :datahike.read/dependency-plan
                     {:datahike.query.dependency/sources
                      [{:datahike.query.source/symbol '$
                        :datahike.query.source/argument-position 0
                        :datahike.query.source/attributes #{:interest/a}}]}}))
            listen-b
            (call! b
                   (protocol/listen-request
                    {::protocol/request-id listen-b-id
                     :seon.db/db (:db-after schema-report)
                     ::protocol/datom-patterns [{:seon.db/a :interest/b}]}))
            pending-b (future (read-next b))
            transaction-a
            (transact! writer-channel (:db-after schema-report) "interest/write-a"
                       [{:interest/a "only-a"}])
            events-a [(deref (future (read-next a)) 5000 ::timed-out)
                      (deref (future (read-next a)) 5000 ::timed-out)]
            event-a (some #(when (= protocol/datoms-event
                                    (::protocol/event %)) %)
                          events-a)
            advanced-b (deref pending-b 5000 ::timed-out)]
        (when-not (and (::protocol/listening? listen-a)
                       (::protocol/listening? listen-b))
          (throw (ex-info "listen setup failed" {:a listen-a :b listen-b})))
        (is (::protocol/listening? listen-a) (pr-str listen-a))
        (is (::protocol/listening? listen-b) (pr-str listen-b))
        (is (= (:db-after schema-report) (:db-after listen-a)))
        (is (= (:db-after listen-a) (:db-after listen-b)))
        (is (= (:seon.db/db acquired-a) (:seon.db/db acquired-b)))
        (is (= #{:interest/a}
               (get-in @(::writer/interests (interest-connection server
                                                                 listen-a-id))
                       [listen-a-id ::writer/dependencies])))
        (is (= protocol/datoms-event (::protocol/event event-a)))
        (is (= listen-a-id (::protocol/request-id event-a)))
        (is (some #(= :interest/a (nth % 1)) (:tx-data event-a)))
        (is (= (:db-after transaction-a) (:db-after event-a)))
        (is (= (:tx-data transaction-a) (:tx-data event-a)))
        (is (= protocol/database-advanced-event
               (::protocol/event advanced-b)))
        (is (= (:db-after transaction-a) (:db-after advanced-b)))

        (let [transaction-b
              (transact! writer-channel (:db-after transaction-a)
                         "interest/write-b" [{:interest/b "only-b"}])
              events-b [(deref (future (read-next b)) 5000 ::timed-out)
                        (deref (future (read-next b)) 5000 ::timed-out)]
              event-b (some #(when (= protocol/datoms-event
                                      (::protocol/event %)) %)
                            events-b)]
          (is (= protocol/datoms-event (::protocol/event event-b)))
          (is (= listen-b-id (::protocol/request-id event-b)))
          (is (some #(= :interest/b (nth % 1)) (:tx-data event-b)))
          (is (= (:db-after transaction-b) (:db-after event-b))))

        (let [unlisten
              (call! a
                     (protocol/unlisten-request
                      {::protocol/request-id "interest/unlisten-a"
                       ::protocol/target-request-id listen-a-id}))]
          (is (false? (::protocol/listening? unlisten)))
          (let [transaction
                (transact! writer-channel (:db-after transaction-a)
                           "interest/write-a-again"
                           [{:interest/a "after-ack"}])
                advanced (deref (future (read-next a)) 5000 ::timed-out)]
            (is (= protocol/database-advanced-event
                   (::protocol/event advanced)))
            (is (= (:db-after transaction) (:db-after advanced))))
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
        nil))))

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
              connection-id (branch/connection-id (branch/head db-value))
              scope {::executor/database-name "gap"
                     ::branch/connection-id connection-id
                     ::executor/connection-id
                     (:datahike.value/connection-id identity)
                     ::executor/generation (:datahike.value/generation identity)}
              request
              (protocol/listen-request
               {::protocol/request-id "gap/listen"
                :seon.db/db (database-value "gap" db-value)
                ::protocol/datom-patterns [{:seon.db/a :gap/value}]})]
          (locking (::writer/interest-lock runtime)
            (#'writer/install-interest-locked!
             runtime transport-connection request connection "gap" connection-id))
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
                       :db-after
                       (database-value "gap" (d/db connection))}]
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
             :db-after
             {:db-name "pressure"
              :t 1
              :as-of nil
              :since nil
              :history false
              :datahike/commit-id (random-uuid)}})))
    (is (true? (deref closed 1000 false)))
    (is (.get ^AtomicBoolean (::writer/closing? connection)))))

(deftest one-exact-pattern-addresses-one-of-one-thousand-interests
  (let [configuration {:store {:backend :memory :id (random-uuid)}
                       :schema-flexibility :read}
        sent (atom [])]
    (d/create-database configuration)
    (let [connection (d/connect configuration)]
      (try
        (d/transact connection
                    [{:db/ident :fanout/value
                      :db/valueType :db.type/string
                      :db/cardinality :db.cardinality/one}])
        (let [report (d/transact connection [{:fanout/value "target"}])
              target-eid
              (some (fn [datom]
                      (when (= :fanout/value (.-a ^datahike.datom.Datom datom))
                        (.-e ^datahike.datom.Datom datom)))
                    (:tx-data report))
              identity (d/committed-value-identity (:db-after report))
              scope {::executor/database-name "fanout"
                     ::branch/connection-id
                     (branch/connection-id
                      (branch/head (:db-after report)))
                     ::executor/connection-id
                     (:datahike.value/connection-id identity)
                     ::executor/generation (:datahike.value/generation identity)}
              interests
              (mapv
               (fn [index]
                 (let [owner (Object.)
                       request-id (str "fanout/" index)
                       connection-sent (atom [])
                       transport
                       (fake-transport-connection connection-sent (promise))
                       interest
                       {::writer/owner owner
                        ::writer/scope scope
                        ::writer/dependencies nil
                        ::writer/patterns
                        [{:seon.db/a :fanout/value
                          :seon.db/e (if (zero? index)
                                       target-eid
                                       (+ target-eid index))}]}
                       reference [transport request-id owner]]
                   (swap! (::writer/interests transport) assoc request-id interest)
                   [reference interest connection-sent]))
               (range 1000))
              entry
              (reduce (fn [entry [reference interest _connection-sent]]
                        (#'writer/add-interest-to-entry
                         entry reference interest))
                      {::writer/scope scope
                       ::writer/interest-count 0
                       ::writer/all #{}
                       ::writer/by-attribute {}}
                      interests)
              runtime
              {::writer/interest-lock (Object.)
               ::writer/interest-state
               (atom {::writer/by-scope {scope entry}
                      ::writer/by-source {}})}]
          (#'writer/deliver-report! runtime scope report)
          (reset! sent
                  (into []
                        (mapcat (fn [[[_transport request-id _owner]
                                      _interest connection-sent]]
                                  (map (fn [event] [request-id event])
                                       @connection-sent)))
                        interests))
          (is (= 1 (count @sent)))
          (is (= "fanout/0" (ffirst @sent)))
          (is (= target-eid
                 (some (fn [[entity attribute]]
                         (when (= :fanout/value attribute) entity))
                       (get-in @sent [0 1 :tx-data])))))
        (finally
          (d/release connection)
          (d/delete-database configuration))))))

(deftest query-interests-require-a-usable-database-dependency
  (let [failure
        (try
          (#'writer/listen-interest
           (protocol/listen-request
            {::protocol/request-id "empty/listen"
             :seon.db/db
             {:db-name "empty"
              :store-id [(random-uuid) :db]
              :t 1
              :as-of nil
              :since nil
              :history false
              :datahike/commit-id (random-uuid)}
             ::protocol/query-form '[:find ?input :in ?input]})
           {::executor/database-name "empty"
            ::branch/connection-id [(random-uuid) :db]
            ::executor/connection-id [(random-uuid) :db]
            ::executor/generation (random-uuid)})
          nil
          (catch clojure.lang.ExceptionInfo exception exception))]
    (is (instance? clojure.lang.ExceptionInfo failure))
    (is (re-find #"must depend on a database attribute"
                 (.getMessage ^Throwable failure)))))

(deftest one-process-readiness-thread-routes-concurrent-runtime-sources
  (let [scope-a {::executor/database-name "runtime-a"
                 ::branch/connection-id [(random-uuid) :db]
                 ::executor/connection-id [(random-uuid) :db]
                 ::executor/generation (random-uuid)}
        scope-b {::executor/database-name "runtime-b"
                 ::branch/connection-id [(random-uuid) :db]
                 ::executor/connection-id [(random-uuid) :db]
                 ::executor/generation (random-uuid)}
        source-a (committed-report/open!
                  (::executor/connection-id scope-a)
                  (::executor/generation scope-a) 4)
        source-b (committed-report/open!
                  (::executor/connection-id scope-b)
                  (::executor/generation scope-b) 4)
        runtime-a {::writer/readiness-owner (Object.)
                   ::writer/interest-lock (Object.)
                   ::writer/interest-state
                   (atom {::writer/by-scope {}
                          ::writer/by-source {source-a scope-a}})
                   ::writer/executor :runtime-a}
        runtime-b {::writer/readiness-owner (Object.)
                   ::writer/interest-lock (Object.)
                   ::writer/interest-state
                   (atom {::writer/by-scope {}
                          ::writer/by-source {}})
                   ::writer/executor :runtime-b}
        submissions (atom [])]
    (try
      (with-redefs [executor/try-submit!
                    (fn [submission]
                      (swap! submissions conj
                             [(::executor/executor submission)
                              (::executor/scope submission)])
                      {::executor/accepted? true ::executor/joined? false})]
        (#'writer/register-readiness! runtime-a)
        (#'writer/register-readiness! runtime-b)
        (let [thread
              (::writer/readiness-thread @@#'writer/readiness-state)]
          (is (instance? Thread thread))
          (is (.isAlive ^Thread thread))
          (is (= 2 (count (::writer/readiness-runtimes
                           @@#'writer/readiness-state)))))
        (committed-report/offer! source-a (::executor/generation scope-a) :a)
        (committed-report/offer! source-b (::executor/generation scope-b) :b)
        (Thread/yield)
        (locking (::writer/interest-lock runtime-b)
          (swap! (::writer/interest-state runtime-b)
                 assoc-in [::writer/by-source source-b] scope-b))
        (is (wait-until!
             #(= #{[:runtime-a scope-a] [:runtime-b scope-b]}
                 (set @submissions)))
            "a source taken before owner publication is requeued, not lost"))
      (finally
        (#'writer/unregister-readiness! runtime-a)
        (#'writer/unregister-readiness! runtime-b)
        (committed-report/close! source-a false)
        (committed-report/close! source-b false)))
    (is (nil? (::writer/readiness-thread @@#'writer/readiness-state)))
    (is (empty? (::writer/readiness-runtimes @@#'writer/readiness-state)))))
