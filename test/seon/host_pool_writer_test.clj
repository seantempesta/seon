(ns seon.host-pool-writer-test
  "Retained host writer-pool concurrency, deadlines, and recovery."
  (:require [clojure.test :refer [deftest is]]
            [seon.db.host :as db.host]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer-test-support :as writer-test]
            [seon.db.writer :as writer])
  (:import [java.io File]
           [java.nio.channels SocketChannel]
           [java.util.concurrent CountDownLatch TimeUnit]))

(defn- socket-path [label]
  (let [directory (File. "tmp")]
    (.mkdirs directory)
    (.getAbsolutePath
     (File. directory
            (str "host-pool-" label "-" (random-uuid) ".sock")))))

(defn- dependencies []
  {::writer/database-initializer (fn [_connection _database-name] nil)
   ::writer/embedding-enabled? false
   ::writer/embedding-entity-ids (fn [_db-value] [])
   ::writer/embedding-inputs-for-eids (fn [_db-value _entity-ids] [])
   ::writer/embedding-assertions (fn [_inputs] [])
   ::writer/revalidate-embedding-assertions (fn [_db-value _assertions] [])
   ::writer/query-vec (fn [_] {:seon.embed/vector [0.0]})
   ::writer/knn (fn [_db-value _vector _k _eids] [])})

(defn- with-writer-session
  [label pool-default-overrides body]
  (let [database-name (str "host-pool-" (random-uuid))
        request-path (socket-path label)
        defaults (merge (var-get #'db.host/defaults)
                        pool-default-overrides)]
    (with-redefs-fn
      {#'db.host/defaults defaults}
      (fn []
        (let [server (writer-test/start! {::writer/dependencies (dependencies)
                                     ::writer/database-name database-name
                                     ::writer/backend :memory
                                     ::writer/selected-processors 3
                                     ::writer/request-socket-path request-path})
              session (db.host/writer-session
                       {::db.host/writer-socket-path request-path
                        ::db.host/database-name database-name
                        ::db.host/backend :memory})]
          (try
            (body server session)
            (finally
              (db.host/close-session! session)
              (writer/stop! server)
              (.delete (File. ^String request-path)))))))))

(defn- writer-call! [session request]
  ((var-get #'db.host/call!) session request))

(defn- query-request [head request-id]
  (protocol/query-request
   {::protocol/request-id request-id
    :seon.db/db head
    ::protocol/query-form '[:find ?e :where [?e :db/txInstant]]
    ::protocol/arguments []}))

(defn- await-latch! [^CountDownLatch latch]
  (.await latch 5 TimeUnit/SECONDS))

(defn- pool-members [session]
  (vals (::db.host/members @(::db.host/pool-state session))))

(deftest call-surfaces-transport-owned-eof-data
  (with-writer-session
    "typed-eof"
    {::db.host/pool-size 1}
    (fn [_server session]
      (let [database-session
            (uds/open-session! (::db.host/writer-socket-path session))]
        (try
        (with-redefs [uds/read-frame (fn
                                      ([_input] nil)
                                      ([_input _maximum-frame-bytes] nil))]
          (let [request-id "pool/typed-eof"
                failure
                (try
                  (uds/call! {::uds/session database-session
                              ::uds/message
                              (protocol/ping-request
                               {::protocol/request-id request-id})})
                  nil
                  (catch clojure.lang.ExceptionInfo exception
                    (ex-data exception)))]
            (is (= {::uds/eof true ::protocol/request-id request-id}
                   failure))))
          (finally
            (uds/close-session! database-session)))))))

(deftest a-slow-call-does-not-hold-the-other-member
  (with-writer-session
    "concurrency"
    {::db.host/pool-size 2 ::db.host/call-deadline-ms 5000}
    (fn [_server session]
      (let [head (db.host/resolve-db! session)
            slow-request (query-request head "pool/concurrency-slow")
            quick-request (query-request head "pool/concurrency-quick")
            slow-entered (CountDownLatch. 1)
            release-slow (CountDownLatch. 1)
            execute-read! (var-get #'writer/execute-read!)]
        (with-redefs-fn
          {#'writer/execute-read!
           (fn [runtime work]
             (when (= "pool/concurrency-slow"
                      (get-in work [::writer/request ::protocol/request-id]))
               (.countDown slow-entered)
               (.await release-slow))
             (execute-read! runtime work))}
          (fn []
            (let [slow (future (writer-call! session slow-request))]
              (try
                (is (await-latch! slow-entered))
                (let [quick (future (writer-call! session quick-request))]
                  (is (::protocol/success? (deref quick 5000 {})))
                  (is (not (realized? slow))
                      "the quick call completes while the slow member is leased"))
                (finally
                  (.countDown release-slow)
                  (is (::protocol/success? (deref slow 5000 {}))))))))))))

(deftest a-deadlined-member-is-evicted-and-the-session-recovers
  (with-writer-session
    "deadline"
    {::db.host/pool-size 1 ::db.host/call-deadline-ms 50}
    (fn [_server session]
      (let [head (db.host/resolve-db! session)
            victim (first (pool-members session))
            entered (CountDownLatch. 1)
            release-slow (CountDownLatch. 1)
            execute-read! (var-get #'writer/execute-read!)]
        (with-redefs-fn
          {#'writer/execute-read!
           (fn [runtime work]
             (when (= "pool/deadline"
                      (get-in work [::writer/request ::protocol/request-id]))
               (.countDown entered)
               (.await release-slow))
             (execute-read! runtime work))}
          (fn []
            (let [call (future
                         (writer-call! session
                                       (query-request head "pool/deadline")))]
              (try
                (is (await-latch! entered))
                (let [outcome (deref call 5000 {})]
                  (is (:seon.error/message outcome))
                  (is (= :call-deadline
                         (get-in outcome [:seon.error/data
                                          ::db.host/pool-reason])))
                  (is (not (.isOpen ^SocketChannel
                                    (::uds/channel (::db.host/session victim)))))
                  (let [replacement (first (pool-members session))]
                    (is (some? replacement))
                    (is (not= (::db.host/member-id victim)
                              (::db.host/member-id replacement)))))
                (finally
                  (.countDown release-slow))))))
      (let [next-head (db.host/resolve-db! session)
            replacement (first (pool-members session))]
        (is (map? next-head))
        (is (not (:seon.error/message next-head)))
        (is (not= (::db.host/member-id victim)
                  (::db.host/member-id replacement))))))))

(def ^:private note-schema-transaction
  [{:seon.schema/key :seon.schema/key
    :seon.schema/form "[:keyword {:seon.db/identity true}]"}
   {:seon.schema/key :seon.schema/form
    :seon.schema/form ":string"}
   {:seon.schema/key ::note
    :seon.schema/form "[:string {:min 1}]"}])

(deftest a-lost-write-response-recovers-with-the-same-request-id
  (with-writer-session
    "write-retry"
    {::db.host/pool-size 2 ::db.host/call-deadline-ms 5000}
    (fn [_server session]
      (let [head (db.host/resolve-db! session)
            seed (writer-call!
                  session
                  (protocol/transaction-request
                   {::protocol/request-id "pool/seed"
                    :seon.db/db head
                    ::protocol/transaction-data note-schema-transaction}))
            target-id "pool/lost-write-response"
            request
            (protocol/transaction-request
             {::protocol/request-id target-id
              :seon.db/db (:db-after seed)
              ::protocol/transaction-data [{::note "once"}]})
            original-call uds/call!
            attempts (atom [])
            first-response (atom nil)
            lose-once? (atom true)]
        (is (::protocol/success? seed))
        (with-redefs [uds/call!
                      (fn [{::uds/keys [session message] :as input}]
                        (when (= protocol/transact-operation
                                 (::protocol/operation message))
                          (swap! attempts conj
                                 [::protocol/request-id
                                  (::protocol/request-id message)
                                  ::session session]))
                        (let [response (original-call input)]
                          (if (and (= target-id (::protocol/request-id message))
                                   (compare-and-set! lose-once? true false))
                            (do (reset! first-response response)
                                (uds/close-session! session)
                                (throw (ex-info "simulated lost acknowledgement"
                                                {:test/lost-response true})))
                            response)))]
          (let [recovered (writer-call! session request)
                transaction-attempts
                (filter #(= target-id (second %)) @attempts)]
            (is (::protocol/success? recovered))
            (is (true? (::protocol/recovered? recovered)))
            (is (= (get-in @first-response [:db-after :t])
                   (get-in recovered [:db-after :t])))
            (is (= #{target-id} (into #{} (map second) transaction-attempts)))
            (is (= 2 (count (into #{} (map #(nth % 3)) transaction-attempts))))))))))

(deftest an-active-same-id-duplicate-waits-for-the-original-commit
  (with-writer-session
    "active-conflict"
    {::db.host/pool-size 2
     ::db.host/call-deadline-ms 100
     ::db.host/request-conflict-backoff-ms 5}
    (fn [_server session]
      (let [head (db.host/resolve-db! session)
            seed (writer-call!
                  session
                  (protocol/transaction-request
                   {::protocol/request-id "pool/conflict-seed"
                    :seon.db/db head
                    ::protocol/transaction-data note-schema-transaction}))
            target-id "pool/active-conflict"
            request (protocol/transaction-request
                     {::protocol/request-id target-id
                      :seon.db/db (:db-after seed)
                      ::protocol/transaction-data [{::note "conflict"}]})
            finish-transaction! (var-get #'writer/finish-transaction!)
            original-call uds/call!
            finish-entered (CountDownLatch. 1)
            release-finish (CountDownLatch. 1)
            duplicate-observed (CountDownLatch. 1)
            committed-response (atom nil)
            request-ids (atom [])]
        (is (::protocol/success? seed))
        (with-redefs-fn
          {#'writer/finish-transaction!
           (fn [prepared result]
             (let [response (finish-transaction! prepared result)]
               (when (= target-id (::writer/request-id prepared))
                 (reset! committed-response response)
                 (.countDown finish-entered)
                 (.await release-finish))
               response))
           #'uds/call!
           (fn [{::uds/keys [message] :as input}]
             (when (= target-id (::protocol/request-id message))
               (swap! request-ids conj (::protocol/request-id message))
               (when (= 2 (count @request-ids))
                 (.countDown duplicate-observed)
                 (.countDown release-finish)))
             (original-call input))}
          (fn []
            (let [call (future (writer-call! session request))]
              (try
                (is (await-latch! finish-entered))
                (is (await-latch! duplicate-observed))
                (let [duplicate (deref call 5000 {})]
                  (is (::protocol/success? duplicate))
                  (is (= (get-in @committed-response [:db-after :t])
                         (get-in duplicate [:db-after :t])))
                  (is (= #{target-id} (set @request-ids)))
                  (is (= 2 (count @request-ids))))
                (finally
                  (.countDown release-finish))))))))))

(deftest an-exhausted-pool-returns-a-bounded-steering-error
  (with-writer-session
    "exhaustion"
    {::db.host/pool-size 2
     ::db.host/pool-wait-timeout-ms 50
     ::db.host/call-deadline-ms 5000}
    (fn [_server session]
      (let [head (db.host/resolve-db! session)
            entered (CountDownLatch. 2)
            release-slow (CountDownLatch. 1)
            execute-read! (var-get #'writer/execute-read!)]
        (with-redefs-fn
          {#'writer/execute-read!
           (fn [runtime work]
             (when (.startsWith
                    ^String (get-in work [::writer/request
                                          ::protocol/request-id] "")
                                "pool/exhaustion-")
               (.countDown entered)
               (.await release-slow))
             (execute-read! runtime work))}
          (fn []
            (let [first-call (future (writer-call!
                                      session
                                      (query-request head "pool/exhaustion-1")))
                  second-call (future (writer-call!
                                       session
                                       (query-request head "pool/exhaustion-2")))]
              (try
                (is (await-latch! entered))
                (let [outcome (writer-call!
                               session
                               (query-request head "pool/over-wait"))
                      data (get-in outcome [:seon.error/data])]
                  (is (:seon.error/message outcome))
                  (is (= :pool-exhausted (::db.host/pool-reason data)))
                  (is (= 2 (get-in data [::db.host/pool
                                         ::db.host/in-flight-members]))))
                (finally
                  (.countDown release-slow)
                  (is (::protocol/success? (deref first-call 5000 {})))
                  (is (::protocol/success? (deref second-call 5000 {}))))))))))))

(deftest a-local-oversize-is-not-retried-or-evicted
  (with-writer-session
    "local-oversize"
    {::db.host/pool-size 1 ::db.host/call-deadline-ms 5000}
    (fn [_server session]
      (db.host/resolve-db! session)
      (let [request-id "pool/local-oversize"
            failure (protocol/frame-too-large-failure
                     {::protocol/request-id request-id
                      ::protocol/maximum-frame-bytes 65536})
            member-before (first (pool-members session))
            attempts (atom 0)]
        (with-redefs [uds/call!
                      (fn [_]
                        (swap! attempts inc)
                        (throw (ex-info (::protocol/error failure) failure)))]
          (is (= failure
                 (writer-call!
                  session
                  (protocol/ping-request
                   {::protocol/request-id request-id}))))
          (is (= 1 @attempts))
          (is (identical? (::db.host/session member-before)
                          (::db.host/session (first (pool-members session))))))))))

(deftest close-session-closes-every-member-and-a-fresh-session-admits
  (with-writer-session
    "close"
    {::db.host/pool-size 2 ::db.host/call-deadline-ms 5000}
    (fn [_server session]
      (let [head (db.host/resolve-db! session)
            entered (CountDownLatch. 2)
            release-slow (CountDownLatch. 1)
            execute-read! (var-get #'writer/execute-read!)]
        (with-redefs-fn
          {#'writer/execute-read!
           (fn [runtime work]
             (when (.startsWith
                    ^String (get-in work [::writer/request
                                          ::protocol/request-id] "")
                                "pool/close-")
               (.countDown entered)
               (.await release-slow))
             (execute-read! runtime work))}
          (fn []
            (let [calls [(future (writer-call! session
                                               (query-request head "pool/close-1")))
                         (future (writer-call! session
                                              (query-request head "pool/close-2")))]]
              (is (await-latch! entered))
              (.countDown release-slow)
              (run! #(is (::protocol/success? (deref % 5000 {}))) calls))))
        (let [members (vec (pool-members session))]
          (is (= 2 (count members)))
          (db.host/close-session! session)
          (is (empty? (pool-members session)))
          (is (every? #(not (.isOpen ^SocketChannel
                                     (::uds/channel (::db.host/session %))))
                      members)))
        (let [fresh (db.host/writer-session
                     {::db.host/writer-socket-path
                      (::db.host/writer-socket-path session)
                      ::db.host/database-name (::db.host/database-name session)
                      ::db.host/backend :memory})]
          (try
            (let [deadline (+ (System/currentTimeMillis) 5000)
                  admitted
                  (loop []
                    (let [head (db.host/resolve-db! fresh)]
                      (if (or (not (:seon.error/message head))
                              (>= (System/currentTimeMillis) deadline))
                        head
                        (do (Thread/sleep 10) (recur)))))]
              (is (not (:seon.error/message admitted))))
            (finally
              (db.host/close-session! fresh))))))))
