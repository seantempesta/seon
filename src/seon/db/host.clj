(ns seon.db.host
  "JVM platform leaf for portable `seon.db` operations.

   The leaf owns retained UDS connections, bounded blocking calls, recovery
   sleeps, UUID generation, and ambient invocation access. Portable request
   construction and response interpretation remain in `seon.db`."
  (:require [seon.db.protocol :as protocol]
            [seon.db.transport.uds :as uds])
  (:import [java.util.concurrent Callable ExecutorService Executors]
           [java.util.concurrent.locks Condition ReentrantLock]))

(set! *warn-on-reflection* true)

(def defaults
  "Hardware-derived defaults for one host database connection pool."
  {::pool-size (max 1 (dec (.availableProcessors (Runtime/getRuntime))))
   ::pool-wait-timeout-ms 1000
   ::call-deadline-ms 120000
   ::request-conflict-backoff-ms 10})

(defn recoverable-transaction-delivery?
  "True when ambiguous delivery may use the transaction receipt path."
  [request]
  (= protocol/transact-operation (::protocol/operation request)))

(defn- initial-pool-state
  []
  {::closed? false
   ::members {}
   ::available []
   ::opening 0})

(defn writer-session
  "Build one lazy retained host database connection pool."
  [{::keys [writer-socket-path database-name backend database-path
            current-agent-id-fn context-fn mint-op-id-fn]
    :as options}]
  (let [pool-size (or (::pool-size options) (::pool-size defaults))
        pool-lock (ReentrantLock.)]
    (cond-> {::writer-socket-path writer-socket-path
             ::database-name database-name
             ::pool-size pool-size
             ::pool-state (atom (initial-pool-state))
             ::pool-lock pool-lock
             ::pool-condition (.newCondition pool-lock)
             ::call-executor (Executors/newFixedThreadPool (int pool-size))
             ::current-agent-id-fn (or current-agent-id-fn (constantly nil))
             ::context-fn (or context-fn (constantly nil))
             ::mint-op-id-fn (or mint-op-id-fn #(str (random-uuid)))
             ::recoverable-transaction-delivery?
             (or (::recoverable-transaction-delivery? options)
                 recoverable-transaction-delivery?)}
      backend (assoc ::backend backend)
      database-path (assoc ::database-path database-path))))

(defn current-agent-id
  "Return the invocation's agent id from the injected host accessor."
  [{::keys [current-agent-id-fn]}]
  (current-agent-id-fn))

(defn invocation-context
  "Return ordinary invocation context from the injected host accessor."
  [{::keys [context-fn]}]
  (context-fn))

(defn mint-op-id
  "Mint one operation identity through the injected host service."
  [{::keys [mint-op-id-fn]}]
  (mint-op-id-fn))

(defn- with-pool-lock
  [{::keys [pool-lock]} f]
  (.lock ^ReentrantLock pool-lock)
  (try
    (f)
    (finally
      (.unlock ^ReentrantLock pool-lock))))

(defn- pool-snapshot
  [{::keys [pool-state pool-size] :as writer}]
  (with-pool-lock
    writer
    (fn []
      (let [{::keys [closed? members available opening]} @pool-state]
        {::closed? closed?
         ::pool-size pool-size
         ::live-members (count members)
         ::available-members (count available)
         ::in-flight-members (- (count members) (count available))
         ::opening-members opening}))))

(defn- pool-error
  ([writer reason message]
   (pool-error writer reason message {}))
  ([writer reason message data]
   {:seon.error/message message
    :seon.error/kind :agent
    :seon.error/data (merge {::pool-reason reason
                             ::pool (pool-snapshot writer)}
                            data)}))

(defn- error-value?
  [value]
  (string? (:seon.error/message value)))

(defn- protocol-error-value
  [response]
  (if (error-value? response)
    response
    {:seon.error/message
     (str "The database writer rejected the call: "
          (or (::protocol/error response) (::protocol/error-kind response)))
     :seon.error/kind :agent
     :seon.error/data
     (select-keys response [::protocol/error-kind
                            ::protocol/configuration-key
                            ::protocol/maximum-frame-bytes])}))

(defn- close-member-session!
  [session]
  (when session
    (try
      (uds/close-session! session)
      (catch Throwable _)))
  nil)

(defn- member-present?
  [state {::keys [member-id session]}]
  (identical? session (get-in state [::members member-id ::session])))

(defn- release-member!
  [{::keys [pool-state pool-condition] :as writer} member]
  (let [retain?
        (with-pool-lock
          writer
          (fn []
            (let [state @pool-state]
              (if (and (not (::closed? state))
                       (member-present? state member))
                (do
                  (swap! pool-state update ::available conj (::member-id member))
                  (.signal ^Condition pool-condition)
                  true)
                false))))]
    (when-not retain?
      (close-member-session! (::session member)))
    nil))

(defn- evict-member!
  [{::keys [pool-state pool-condition] :as writer} member]
  (with-pool-lock
    writer
    (fn []
      (when (member-present? @pool-state member)
        (swap! pool-state
               (fn [state]
                 (-> state
                     (update ::members dissoc (::member-id member))
                     (update ::available
                             (fn [member-ids]
                               (filterv #(not= (::member-id member) %)
                                        member-ids))))))
        (.signalAll ^Condition pool-condition))))
  (close-member-session! (::session member))
  nil)

(defn- handshake-request!
  [session request]
  (uds/call! {::uds/session session ::uds/message request}))

(defn- open-member!
  [{::keys [writer-socket-path database-name backend database-path]
    :as writer}]
  (let [session (atom nil)]
    (try
      (let [connected (uds/open-session! writer-socket-path)
            _ (reset! session connected)
            ensure-response
            (when backend
              (handshake-request!
               connected
               (protocol/ensure-database-request
                (cond-> {::protocol/request-id (str (random-uuid))
                         ::protocol/database-name database-name
                         ::protocol/backend backend}
                  database-path
                  (assoc ::protocol/database-path database-path)))))
            ensure-error
            (when (and ensure-response
                       (not (::protocol/success? ensure-response)))
              (protocol-error-value ensure-response))
            resolve-response
            (when-not ensure-error
              (handshake-request!
               connected
               (protocol/resolve-head-request
                {::protocol/request-id (str (random-uuid))
                 ::protocol/database-name database-name})))
            resolve-error
            (when (and resolve-response
                       (not (::protocol/success? resolve-response)))
              (protocol-error-value resolve-response))]
        (if-let [error (or ensure-error resolve-error)]
          (do
            (close-member-session! connected)
            error)
          {::member-id (random-uuid) ::session connected}))
      (catch Throwable throwable
        (close-member-session! @session)
        (if (= protocol/connection-capacity-error
               (::protocol/error-kind (ex-data throwable)))
          (pool-error writer :writer-capacity
                      "The database writer is at its connection capacity."
                      (select-keys
                       (ex-data throwable)
                       [::protocol/request-id ::protocol/error-kind
                        ::protocol/configuration-key
                        ::protocol/maximum-connections]))
          (pool-error writer :connect-failed
                      "The host could not open a database writer connection."
                      {::failure (str throwable)}))))))

(defn- finish-opening!
  [{::keys [pool-state pool-condition] :as writer} result]
  (let [installed?
        (with-pool-lock
          writer
          (fn []
            (swap! pool-state update ::opening dec)
            (let [closed? (::closed? @pool-state)]
              (when (and (not closed?) (not (error-value? result)))
                (swap! pool-state assoc-in
                       [::members (::member-id result)] result))
              (.signalAll ^Condition pool-condition)
              (and (not closed?) (not (error-value? result))))))]
    (cond
      (error-value? result) result
      installed? result
      :else
      (do
        (close-member-session! (::session result))
        (pool-error writer :session-closed
                    "The database writer session is closed.")))))

(defn- acquire-member!
  [{::keys [pool-state pool-condition pool-size] :as writer} wait-timeout-ms]
  (let [deadline (+ (System/nanoTime)
                    (.toNanos java.util.concurrent.TimeUnit/MILLISECONDS
                              (long wait-timeout-ms)))
        decision
        (with-pool-lock
          writer
          (fn []
            (loop []
              (let [{::keys [closed? members available opening]} @pool-state
                    remaining (- deadline (System/nanoTime))]
                (cond
                  closed?
                  (pool-error writer :session-closed
                              "The database writer session is closed.")

                  (seq available)
                  (let [member-id (peek available)
                        member (get members member-id)]
                    (swap! pool-state update ::available pop)
                    member)

                  (< (+ (count members) opening) pool-size)
                  (do
                    (swap! pool-state update ::opening inc)
                    ::open-member)

                  (not (pos? remaining))
                  (pool-error writer :pool-exhausted
                              "Every database writer connection is busy."
                              {::pool-wait-timeout-ms wait-timeout-ms})

                  :else
                  (let [interrupted?
                        (try
                          (.awaitNanos ^Condition pool-condition remaining)
                          false
                          (catch InterruptedException _ true))]
                    (if interrupted?
                      (do
                        (.interrupt (Thread/currentThread))
                        (pool-error
                         writer :interrupted
                         "Waiting for a database writer connection was interrupted."))
                      (recur))))))))]
    (if (= ::open-member decision)
      (finish-opening! writer (open-member! writer))
      decision)))

(defn- replace-member!
  [writer]
  (let [member (acquire-member!
                writer (::pool-wait-timeout-ms defaults))]
    (when-not (error-value? member)
      (release-member! writer member))
    member))

(defn close-session!
  "Close every retained connection and stop the call executor."
  [{::keys [pool-state pool-condition call-executor] :as writer}]
  (let [members
        (with-pool-lock
          writer
          (fn []
            (let [members (vals (::members @pool-state))]
              (swap! pool-state assoc
                     ::closed? true ::members {} ::available [])
              (.signalAll ^Condition pool-condition)
              members)))]
    (run! #(close-member-session! (::session %)) members)
    (.shutdownNow ^ExecutorService call-executor))
  nil)

(defn- throwable-cause
  [throwable]
  (or (.getCause ^Throwable throwable) throwable))

(defn- invoke-member!
  [{::keys [call-executor] :as writer} member request deadline-ms]
  (let [task (.submit ^ExecutorService call-executor
                      ^Callable
                      (fn []
                        (uds/call! {::uds/session (::session member)
                                    ::uds/message request})))
        timeout ::deadline]
    (try
      (let [response (deref task (long deadline-ms) timeout)]
        (if (= timeout response)
          (do
            (evict-member! writer member)
            {::call-outcome :deadline})
          (do
            (release-member! writer member)
            {::call-outcome :response ::response response})))
      (catch Throwable throwable
        (let [failure (throwable-cause throwable)
              data (ex-data failure)]
          (if (= protocol/frame-too-large-error
                 (::protocol/error-kind data))
            (do
              (release-member! writer member)
              {::call-outcome :response ::response data})
            (do
              (evict-member! writer member)
              {::call-outcome :failure ::failure failure})))))))

(defn- call-attempt!
  [writer request budget-ms]
  (let [started (System/nanoTime)
        wait-ms (min (long budget-ms)
                     (long (::pool-wait-timeout-ms defaults)))
        member (acquire-member! writer wait-ms)]
    (if (error-value? member)
      {::call-outcome :error ::response member}
      (let [spent-ms (.toMillis java.util.concurrent.TimeUnit/NANOSECONDS
                                (- (System/nanoTime) started))
            remaining (max 0 (- (long budget-ms) spent-ms))]
        (if (zero? remaining)
          (do
            (release-member! writer member)
            {::call-outcome :deadline})
          (invoke-member! writer member request remaining))))))

(defn- active-request-conflict?
  [response]
  (and (false? (::protocol/success? response))
       (= protocol/request-conflict-error (::protocol/error-kind response))
       (true? (::protocol/running? response))))

(defn- release-in-flight?
  [response]
  (= protocol/release-error
     (or (::protocol/error-kind response)
         (get-in response [:seon.error/data ::protocol/error-kind]))))

(defn- sleep-before-recovery-poll!
  [remaining]
  (let [backoff-ms
        (min remaining
             (long (::request-conflict-backoff-ms defaults)))]
    (try
      (Thread/sleep (long backoff-ms))
      true
      (catch InterruptedException _
        (.interrupt (Thread/currentThread))
        false))))

(defn- recovery-write!
  [writer request]
  (let [budget-ms (long (::call-deadline-ms defaults))
        deadline (+ (System/nanoTime)
                    (.toNanos java.util.concurrent.TimeUnit/MILLISECONDS
                              budget-ms))]
    (loop []
      (let [remaining (.toMillis java.util.concurrent.TimeUnit/NANOSECONDS
                                 (max 0 (- deadline (System/nanoTime))))]
        (if (zero? remaining)
          (pool-error writer :request-conflict-timeout
                      "The database writer is still settling the original write."
                      {::protocol/request-id (::protocol/request-id request)
                       ::protocol/error-kind protocol/request-conflict-error
                       ::protocol/running? true})
          (let [{::keys [call-outcome response failure]}
                (call-attempt! writer request remaining)]
            (case call-outcome
              :response
              (if (active-request-conflict? response)
                (if (sleep-before-recovery-poll! remaining)
                  (recur)
                  (pool-error writer :interrupted
                              "Database write recovery was interrupted."))
                response)

              :error
              (if (release-in-flight? response)
                (if (sleep-before-recovery-poll! remaining)
                  (recur)
                  (pool-error writer :interrupted
                              "Database write recovery was interrupted."))
                response)

              :deadline
              (pool-error writer :write-recovery-deadline
                          "The database write recovery reached its deadline."
                          {::protocol/request-id (::protocol/request-id request)})

              :failure
              (pool-error writer :write-recovery-failed
                          "The database write recovery connection failed."
                          {::protocol/request-id (::protocol/request-id request)
                           ::failure (str failure)}))))))))

(defn call!
  "Dispatch one bounded wire round-trip through the retained pool."
  ([writer request]
   (call! writer request (::call-deadline-ms defaults)))
  ([{::keys [recoverable-transaction-delivery?] :as writer}
    request timeout-ms]
  (let [recoverable? (boolean (recoverable-transaction-delivery? request))
        deadline-ms (long timeout-ms)
        {::keys [call-outcome response failure]}
        (call-attempt! writer request deadline-ms)]
    (case call-outcome
      :response response
      :error response
      :deadline
      (if recoverable?
        (recovery-write! writer request)
        (do
          (replace-member! writer)
          (pool-error writer :call-deadline
                      "The database writer call reached its deadline."
                      {::protocol/request-id (::protocol/request-id request)})))
      :failure
      (let [{retry-outcome ::call-outcome
             retry-response ::response
             retry-failure ::failure}
            (call-attempt! writer request deadline-ms)]
        (case retry-outcome
          :response
          (if (and recoverable? (active-request-conflict? retry-response))
            (recovery-write! writer request)
            retry-response)
          :error
          (if (and recoverable? (release-in-flight? retry-response))
            (recovery-write! writer request)
            retry-response)
          :deadline
          (if recoverable?
            (recovery-write! writer request)
            (do
              (replace-member! writer)
              (pool-error writer :call-deadline
                          "The database writer call reached its deadline."
                          {::protocol/request-id
                           (::protocol/request-id request)})))
          :failure
          (pool-error writer :connection-failed
                      "The database writer connection failed after reconnecting."
                      {::protocol/request-id (::protocol/request-id request)
                       ::failure (str (or retry-failure failure))})))))))

(defn ensure-db!
  "Ensure the configured database is retained by the writer."
  ([writer]
   (ensure-db! writer (::database-name writer)))
  ([{::keys [backend database-path] :as writer} database-name]
   (if backend
     (call!
      writer
      (protocol/ensure-database-request
       (cond-> {::protocol/request-id (str (random-uuid))
                ::protocol/database-name database-name
                ::protocol/backend backend}
         database-path (assoc ::protocol/database-path database-path))))
     {:seon.error/message
      "The host database leaf has no configured backend to ensure."
      :seon.error/kind :agent
      :seon.error/data {::database-name database-name}})))

(defn resolve-db!
  "Resolve a current database value, ensuring configured storage once."
  ([writer]
   (resolve-db! writer (::database-name writer) false))
  ([writer selection]
   (resolve-db! writer selection false))
  ([{::keys [backend] :as writer} database-name acquire?]
   (let [database-name (or database-name (::database-name writer))
         resolve-once
         (fn []
           (call!
            writer
            ((if acquire?
               protocol/acquire-database-request
               protocol/resolve-head-request)
             {::protocol/request-id (str (random-uuid))
              ::protocol/database-name database-name})
            15000))
         response (resolve-once)
         response (if (and backend
                           (not (::protocol/success? response))
                           (= :seon.db.protocol.error/not-found
                              (::protocol/error-kind response)))
                    (let [ensured (ensure-db! writer database-name)]
                      (if (or (error-value? ensured)
                              (false? (::protocol/success? ensured)))
                        ensured
                        (resolve-once)))
                    response)]
     (if (::protocol/success? response)
       (:seon.db/db response)
       (protocol-error-value response)))))

(defn remember-db!
  "Accept a database value without adding a second host-side cache."
  [_writer database]
  database)

(defn record-resource!
  "Accept resource evidence; durable accounting remains writer-owned."
  [_writer _evidence]
  nil)

(defn resource-options
  "Return explicit read limits without a second host configuration path."
  [_policy request]
  (cond-> {}
    (:seon.db/max-work request)
    (assoc :datahike.resource/max-work (:seon.db/max-work request))
    (:seon.db/max-results request)
    (assoc :datahike.resource/max-results (:seon.db/max-results request))
    (:seon.db/max-result-weight request)
    (assoc :datahike.resource/max-result-weight
           (:seon.db/max-result-weight request))))

(defn leaf
  "Bind the portable database core to one JVM writer session."
  [writer context-fn]
  (let [context (context-fn)
        resolve! #(resolve-db! writer %1 %2)
        read! (fn [request]
                (or (:seon.db/db request)
                    (:seon.db/db
                     ((or (:seon.db.leaf/current-tx-context context)
                          (constantly nil))))
                    (resolve! (:seon.db/database-name request) false)))
        request! (fn [request]
                   (let [database (read! request)]
                     (if (error-value? database)
                       database
                       {::protocol/request-id
                        (or (:seon.db/request-id request) (mint-op-id writer))
                        :seon.db/db database})))]
    {:seon.db.leaf/call! #(call! writer %1 %2)
     :seon.db.leaf/transaction-call!
     (fn [request _recoverable?]
       (call! writer request 120000))
     :seon.db.leaf/resolve-db! resolve!
     :seon.db.leaf/read-db! read!
     :seon.db.leaf/request-db! request!
     :seon.db.leaf/cache-db! #(remember-db! writer %)
     :seon.db.leaf/context context
     :seon.db.leaf/uuid #(mint-op-id writer)
     :seon.db.leaf/resource-options resource-options
     :seon.db.leaf/on-commit! (fn [_transaction-report] nil)}))
