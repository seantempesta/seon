(ns seon.host-pool-writer-test
  "Retained host writer-pool concurrency, deadlines, and recovery."
  (:require [clojure.test :refer [deftest is]]
            [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds]
            [seon.db.writer :as writer]
            [seon.host.context :as context])
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
        defaults (merge (var-get #'context/writer-pool-defaults)
                        pool-default-overrides)]
    (with-redefs-fn
      {#'context/writer-pool-defaults defaults}
      (fn []
        (let [server (writer/start! {::writer/dependencies (dependencies)
                                     ::writer/database-name database-name
                                     ::writer/backend :memory
                                     ::writer/selected-processors 3
                                     ::writer/request-socket-path request-path})
              session (context/writer-session
                       {::context/writer-socket-path request-path
                        ::context/database-name database-name
                        ::context/backend :memory})]
          (try
            (body server session)
            (finally
              (context/close-session! session)
              (writer/stop! server)
              (.delete (File. ^String request-path)))))))))

(defn- writer-call! [session request]
  ((var-get #'context/writer-call!) session request))

(defn- query-request [head request-id]
  (protocol/query-request
   {::protocol/request-id request-id
    :seon.db/db head
    ::protocol/query-form '[:find ?e :where [?e :db/txInstant]]
    ::protocol/arguments []}))

(defn- await-latch! [^CountDownLatch latch]
  (.await latch 5 TimeUnit/SECONDS))

(defn- pool-members [session]
  (vals (::context/members @(::context/pool-state session))))

(deftest call-surfaces-transport-owned-eof-data
  (with-writer-session
    "typed-eof"
    {::context/pool-size 1}
    (fn [_server session]
      (with-open [channel (uds/connect!
                           (::context/writer-socket-path session))]
        (with-redefs [uds/read-frame (fn [_input] nil)]
          (let [request-id "pool/typed-eof"
                failure
                (try
                  (uds/call! {::uds/channel channel
                              ::uds/message
                              (protocol/ping-request
                               {::protocol/request-id request-id})})
                  nil
                  (catch clojure.lang.ExceptionInfo exception
                    (ex-data exception)))]
            (is (= {::uds/eof true ::protocol/request-id request-id}
                   failure))))))))

(deftest a-slow-call-does-not-hold-the-other-member
  (with-writer-session
    "concurrency"
    {::context/pool-size 2 ::context/call-deadline-ms 5000}
    (fn [_server session]
      (let [head (context/resolve-head! session)
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
    {::context/pool-size 1 ::context/call-deadline-ms 50}
    (fn [_server session]
      (let [head (context/resolve-head! session)
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
                  (is (:seon/error outcome))
                  (is (= :call-deadline
                         (get-in outcome [:seon/error :seon.error/data
                                          ::context/pool-reason])))
                  (is (not (.isOpen ^SocketChannel (::context/channel victim))))
                  (let [replacement (first (pool-members session))]
                    (is (some? replacement))
                    (is (not= (::context/member-id victim)
                              (::context/member-id replacement)))))
                (finally
                  (.countDown release-slow))))))
      (let [next-head (context/resolve-head! session)
            replacement (first (pool-members session))]
        (is (map? next-head))
        (is (not (:seon/error next-head)))
        (is (not= (::context/member-id victim)
                  (::context/member-id replacement))))))))

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
    {::context/pool-size 2 ::context/call-deadline-ms 5000}
    (fn [_server session]
      (let [head (context/resolve-head! session)
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
                      (fn [{::uds/keys [channel message] :as input}]
                        (when (= protocol/transact-operation
                                 (::protocol/operation message))
                          (swap! attempts conj
                                 [::protocol/request-id
                                  (::protocol/request-id message)
                                  ::channel channel]))
                        (let [response (original-call input)]
                          (if (and (= target-id (::protocol/request-id message))
                                   (compare-and-set! lose-once? true false))
                            (do (reset! first-response response)
                                (.close ^SocketChannel channel)
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

(deftest an-active-same-id-conflict-is-polled-until-durable-recovery
  (with-writer-session
    "active-conflict"
    {::context/pool-size 2
     ::context/call-deadline-ms 100
     ::context/request-conflict-backoff-ms 5}
    (fn [_server session]
      (let [head (context/resolve-head! session)
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
            conflict-observed (CountDownLatch. 1)
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
               (swap! request-ids conj (::protocol/request-id message)))
             (let [response (original-call input)]
               (when (and (= target-id (::protocol/request-id message))
                          (= protocol/request-conflict-error
                             (::protocol/error-kind response))
                          (true? (::protocol/running? response)))
                 (.countDown conflict-observed)
                 (.countDown release-finish))
               response))}
          (fn []
            (let [call (future (writer-call! session request))]
              (try
                (is (await-latch! finish-entered))
                (is (await-latch! conflict-observed))
                (let [recovered (deref call 5000 {})]
                  (is (::protocol/success? recovered))
                  (is (true? (::protocol/recovered? recovered)))
                  (is (= (get-in @committed-response [:db-after :t])
                         (get-in recovered [:db-after :t])))
                  (is (= #{target-id} (set @request-ids)))
                  (is (<= 3 (count @request-ids))))
                (finally
                  (.countDown release-finish))))))))))

(deftest an-exhausted-pool-returns-a-bounded-steering-error
  (with-writer-session
    "exhaustion"
    {::context/pool-size 2
     ::context/pool-wait-timeout-ms 50
     ::context/call-deadline-ms 5000}
    (fn [_server session]
      (let [head (context/resolve-head! session)
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
                      data (get-in outcome [:seon/error :seon.error/data])]
                  (is (:seon/error outcome))
                  (is (= :pool-exhausted (::context/pool-reason data)))
                  (is (= 2 (get-in data [::context/pool
                                         ::context/in-flight-members]))))
                (finally
                  (.countDown release-slow)
                  (is (::protocol/success? (deref first-call 5000 {})))
                  (is (::protocol/success? (deref second-call 5000 {}))))))))))))

(deftest close-session-closes-every-member-and-a-fresh-session-admits
  (with-writer-session
    "close"
    {::context/pool-size 2 ::context/call-deadline-ms 5000}
    (fn [_server session]
      (let [head (context/resolve-head! session)
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
          (context/close-session! session)
          (is (empty? (pool-members session)))
          (is (every? #(not (.isOpen ^SocketChannel (::context/channel %)))
                      members)))
        (let [fresh (context/writer-session
                     {::context/writer-socket-path
                      (::context/writer-socket-path session)
                      ::context/database-name (::context/database-name session)
                      ::context/backend :memory})]
          (try
            (let [deadline (+ (System/currentTimeMillis) 5000)
                  admitted
                  (loop []
                    (let [head (context/resolve-head! fresh)]
                      (if (or (not (:seon/error head))
                              (>= (System/currentTimeMillis) deadline))
                        head
                        (do (Thread/sleep 10) (recur)))))]
              (is (not (:seon/error admitted))))
            (finally
              (context/close-session! fresh))))))))
