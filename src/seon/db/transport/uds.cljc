(ns seon.db.transport.uds
  "Unix-domain-socket delivery for database protocol maps.

   This namespace owns bytes, length framing, sockets, and subscriber
   resources only. It never dispatches a database operation or manufactures a
   semantic response. The same Transit map can later ride another transport
   without forking `seon.db.protocol`."
  (:require [cognitect.transit :as transit]
            [seon.db.protocol :as protocol]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            DataInputStream DataOutputStream InputStream OutputStream]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels Channels ServerSocketChannel SocketChannel
            #?@(:bb [] :clj [Selector SelectionKey])]
           [java.util ArrayDeque]
           [java.util.concurrent ArrayBlockingQueue
            LinkedBlockingQueue ThreadFactory
            ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit]
           [java.util.concurrent.atomic AtomicReference]))

(set! *warn-on-reflection* true)

;; Babashka's SCI allowlist omits Selector and SelectionKey. Their public
;; operation bits and the branch-hinted interop wrappers below keep this one
;; transport owner loadable by the operator while the JVM server runs the
;; selector hot loop without reflection.
(def ^:private op-read 1)
(def ^:private op-write 4)
(def ^:private op-accept 16)

(defn- selector-wakeup!
  [selector]
  #?(:bb (.wakeup selector)
     :clj (.wakeup ^Selector selector)))

(defn- selector-selected-keys
  [selector]
  #?(:bb (.selectedKeys selector)
     :clj (.selectedKeys ^Selector selector)))

(defn- selector-open?
  [selector]
  #?(:bb (.isOpen selector)
     :clj (.isOpen ^Selector selector)))

(defn- selector-close!
  [selector]
  #?(:bb (.close selector)
     :clj (.close ^Selector selector)))

(defn- key-valid?
  [selection-key]
  #?(:bb (.isValid selection-key)
     :clj (.isValid ^SelectionKey selection-key)))

(defn- key-acceptable?
  [selection-key]
  #?(:bb (.isAcceptable selection-key)
     :clj (.isAcceptable ^SelectionKey selection-key)))

(defn- key-readable?
  [selection-key]
  #?(:bb (.isReadable selection-key)
     :clj (.isReadable ^SelectionKey selection-key)))

(defn- key-writable?
  [selection-key]
  #?(:bb (.isWritable selection-key)
     :clj (.isWritable ^SelectionKey selection-key)))

(defn- key-attachment
  [selection-key]
  #?(:bb (.attachment selection-key)
     :clj (.attachment ^SelectionKey selection-key)))

(defn- key-cancel!
  [selection-key]
  #?(:bb (.cancel selection-key)
     :clj (.cancel ^SelectionKey selection-key)))

(defn- key-interest-ops
  [selection-key]
  #?(:bb (.interestOps selection-key)
     :clj (.interestOps ^SelectionKey selection-key)))

(defn- key-interest-ops!
  [selection-key ops]
  #?(:bb (.interestOps selection-key ops)
     :clj (.interestOps ^SelectionKey selection-key (int ops))))

(schema/register! ::socket-path [:string {:min 1}])
(schema/register! ::message :map)
(schema/register! ::handler 'fn?)
(schema/register! ::open-connection! 'fn?)
(schema/register! ::close-connection! 'fn?)
(schema/register! ::close! 'fn?)
(schema/register! ::send! 'fn?)
(schema/register! ::send-status :qualified-keyword)
(schema/register! ::send-completion 'some?)
(schema/register!
 ::connection-control
 [:map {:closed true}
  [::close! ::close!]
  [::send! ::send!]])
(schema/register! ::shutdown-timeout-ms [:int {:min 1}])
(schema/register! ::maximum-input-bytes [:int {:min 1}])
(schema/register! ::maximum-response-slots [:int {:min 1}])
(schema/register! ::maximum-session-response-slots [:int {:min 1}])
(schema/register! ::maximum-output-bytes [:int {:min 1}])
(schema/register! ::maximum-session-output-bytes [:int {:min 1}])
(schema/register! ::maximum-connections [:int {:min 1}])
(schema/register! ::maximum-frame-bytes [:int {:min 1}])
(schema/register! ::codec-workers [:int {:min 1}])
(schema/register! ::codec-worker-queue-capacity [:int {:min 1}])
(schema/register! ::graceful? :boolean)
(schema/register! ::forced-connections [:int {:min 0}])
(schema/register! ::selector-stopped? :boolean)
(schema/register! ::workers-stopped? :boolean)
(schema/register! ::cleanup-stopped? :boolean)
(schema/register! ::channel 'some?)
(schema/register!
 ::session
 [:map {:closed true}
  [::channel ::channel]
  [::protocol/version ::protocol/version]
  [::protocol/configured-maximum-frame-bytes
   ::protocol/configured-maximum-frame-bytes]
  [::protocol/maximum-frame-bytes ::protocol/maximum-frame-bytes]])
(schema/register! ::output-stream 'some?)
(schema/register! ::connections 'some?)
(schema/register! ::selector 'some?)
(schema/register! ::workers 'some?)
(schema/register! ::closed? 'some?)
(schema/register! ::queue 'some?)
(schema/register! ::worker 'some?)
(schema/register! ::nil-result 'nil?)
(schema/register!
 ::request-server
 [:map
  [::channel ::channel]
  [::connections ::connections]
  [::worker ::worker]
  [::selector ::selector]
  [::workers ::workers]
  [::closed? ::closed?]])
(schema/register!
 ::shutdown-result
 [:map {:closed true}
  [::graceful? ::graceful?]
  [::forced-connections ::forced-connections]
  [::selector-stopped? ::selector-stopped?]
  [::workers-stopped? ::workers-stopped?]
  [::cleanup-stopped? ::cleanup-stopped?]])
(schema/register!
 ::request-server-input
 [:map
  [::socket-path ::socket-path]
  [::handler ::handler]
  [::open-connection! ::open-connection!]
  [::close-connection! ::close-connection!]
  [::shutdown-timeout-ms {:optional true} ::shutdown-timeout-ms]
  [::maximum-input-bytes {:optional true} ::maximum-input-bytes]
  [::maximum-response-slots {:optional true} ::maximum-response-slots]
  [::maximum-session-response-slots {:optional true}
   ::maximum-session-response-slots]
  [::maximum-output-bytes {:optional true} ::maximum-output-bytes]
  [::maximum-session-output-bytes {:optional true}
   ::maximum-session-output-bytes]
  [::maximum-frame-bytes {:optional true} ::maximum-frame-bytes]
  [::maximum-connections {:optional true} ::maximum-connections]
  [::codec-workers {:optional true} ::codec-workers]
  [::codec-worker-queue-capacity {:optional true}
   ::codec-worker-queue-capacity]])
(schema/register!
 ::call-input
 [:map {:closed true} [::session ::session] [::message ::message]])

(def ^:private default-codec-worker-queue-capacity 256)
(def ^:private default-shutdown-timeout-ms 5000)
(def ^:private default-maximum-input-bytes (* 32 1024 1024))
(def ^:private default-maximum-response-slots 256)
(def ^:private default-maximum-session-response-slots 64)
(def ^:private default-maximum-output-bytes (* 256 1024 1024))
(def ^:private default-maximum-session-output-bytes (* 128 1024 1024))
(def ^:private default-maximum-connections 256)
(def ^:private transit-read-handlers
  (transit/read-handler-map
   {"list" (transit/read-handler #(apply list %))}))
(def ^:private asynchronous-close-class
  (Class/forName "java.nio.channels.AsynchronousCloseException"))

(def send-accepted :seon.db.transport.uds.send/accepted)
(def send-closed :seon.db.transport.uds.send/closed)
(def send-session-full :seon.db.transport.uds.send/session-full)
(def send-authority-full :seon.db.transport.uds.send/authority-full)
(def send-encode-failed :seon.db.transport.uds.send/encode-failed)

(defn- asynchronous-close?
  [throwable]
  (.isInstance ^Class asynchronous-close-class throwable))

(defn- rejected-execution?
  [throwable]
  (= "java.util.concurrent.RejectedExecutionException"
     (.getName ^Class (class throwable))))

(defn encode
  "Encode one protocol map as Transit JSON bytes."
  {:malli/schema [:=> [:catn [::message ::message]] :any]}
  ^bytes [message]
  (let [out (ByteArrayOutputStream. 1024)
        writer (transit/writer out :json)]
    (transit/write writer message)
    (.toByteArray out)))

(defn decode
  "Decode one Transit JSON payload into a protocol map."
  {:malli/schema [:=> [:catn [:seon.db.transport.uds/payload :any]] ::message]}
  [^bytes payload]
  (let [in (ByteArrayInputStream. payload)
        reader (transit/reader in :json {:handlers transit-read-handlers})]
    (transit/read reader)))

(defn write-frame!
  "Write and flush one bounded length-prefixed Transit map."
  {:malli/schema [:function
                  [:=> [:catn [::output-stream ::output-stream]
                               [::message ::message]] ::nil-result]
                  [:=> [:catn [::output-stream ::output-stream]
                               [::message ::message]
                               [::maximum-frame-bytes ::maximum-frame-bytes]]
                   ::nil-result]]}
  ([output message]
   (write-frame! output message protocol/maximum-frame-bytes))
  ([^OutputStream output message maximum-frame-bytes]
   (let [^bytes payload (encode message)
         length (alength payload)
         framed (DataOutputStream. output)]
     (when (> length maximum-frame-bytes)
       (throw (ex-info "Database protocol frame is too large."
                       {::frame-bytes length
                        ::protocol/configuration-key
                        :seon.config.database.transport/maximum-frame-bytes
                        ::protocol/maximum-frame-bytes maximum-frame-bytes})))
     (.writeInt framed length)
     (.write framed payload 0 length)
     (.flush framed)
     nil)))

(defn- message-frame
  "Encode one complete length-prefixed frame into a fresh buffer."
  (^ByteBuffer [message]
   (message-frame message protocol/maximum-frame-bytes))
  (^ByteBuffer [message maximum-frame-bytes]
   (let [^bytes payload (encode message)
         length (alength payload)]
     (when (> length maximum-frame-bytes)
       (throw (ex-info "Database protocol frame is too large."
                       {::frame-bytes length
                        ::protocol/configuration-key
                        :seon.config.database.transport/maximum-frame-bytes
                        ::protocol/maximum-frame-bytes maximum-frame-bytes})))
     (doto (ByteBuffer/allocate (+ Integer/BYTES length))
       (.putInt length)
       (.put payload)
       (.flip)))))

(defn read-frame
  "Read one length-prefixed Transit map, or nil at EOF."
  {:malli/schema [:function
                  [:=> [:catn [:seon.db.transport.uds/input-stream :any]] :any]
                  [:=> [:catn [:seon.db.transport.uds/input-stream :any]
                               [::maximum-frame-bytes ::maximum-frame-bytes]]
                   :any]]}
  ([input]
   (read-frame input protocol/maximum-frame-bytes))
  ([^InputStream input maximum-frame-bytes]
   (let [framed (DataInputStream. input)
         length (try (.readInt framed)
                     (catch java.io.EOFException _ nil))]
     (when length
       (when (or (neg? length) (> length maximum-frame-bytes))
         (throw (ex-info "Database protocol frame length is invalid."
                         {::frame-bytes length
                          ::protocol/configuration-key
                          :seon.config.database.transport/maximum-frame-bytes
                          ::protocol/maximum-frame-bytes maximum-frame-bytes})))
       (let [payload (byte-array length)]
         (.readFully framed payload 0 length)
         (decode payload))))))

(defn connect!
  "Open a Unix-domain SocketChannel. The caller owns it."
  {:malli/schema [:=> [:catn [::socket-path ::socket-path]] ::channel]}
  ^SocketChannel [^String socket-path]
  (let [address (UnixDomainSocketAddress/of socket-path)
        channel (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect channel address)
    channel))

(defn- valid-opening-success?
  [response client-maximum-frame-bytes]
  (let [configured (::protocol/configured-maximum-frame-bytes response)
        agreed (::protocol/maximum-frame-bytes response)]
    (and (::protocol/success? response)
         (= protocol/session-open-request-id
            (::protocol/request-id response))
         (= protocol/current-version (::protocol/version response))
         (protocol/valid-response? response)
         (int? configured)
         (<= protocol/session-open-maximum-frame-bytes
             configured
             protocol/maximum-frame-bytes)
         (= agreed (min client-maximum-frame-bytes configured)))))

(defn open-session!
  "Open and admit one database session over a raw Unix channel."
  {:malli/schema [:=> [:catn [::socket-path ::socket-path]] ::session]}
  [socket-path]
  (let [channel (connect! socket-path)]
    (try
      (let [input (Channels/newInputStream channel)
            output (Channels/newOutputStream channel)
            opening
            (protocol/session-open-request
             {::protocol/maximum-frame-bytes protocol/maximum-frame-bytes})]
        (write-frame! output opening protocol/session-open-maximum-frame-bytes)
        (let [response
              (read-frame input protocol/session-open-maximum-frame-bytes)]
          (if (valid-opening-success?
               response (::protocol/maximum-frame-bytes opening))
            {::channel channel
             ::protocol/version (::protocol/version response)
             ::protocol/configured-maximum-frame-bytes
             (::protocol/configured-maximum-frame-bytes response)
             ::protocol/maximum-frame-bytes
             (::protocol/maximum-frame-bytes response)}
            (throw (ex-info (or (::protocol/error response)
                                "Database session opening failed.")
                            (or response {::eof true}))))))
      (catch Throwable throwable
        (try (.close channel) (catch Throwable _))
        (throw throwable)))))

(defn close-session!
  "Close one admitted database session."
  {:malli/schema [:=> [:catn [::session ::session]] ::nil-result]}
  [session]
  (.close ^SocketChannel (::channel session))
  nil)

(defn call!
  "Send one request and synchronously read one admitted-session response."
  {:malli/schema [:=> [:catn [::call-input ::call-input]] ::message]}
  [{::keys [session message]}]
  (let [channel (::channel session)
        maximum-frame-bytes (::protocol/maximum-frame-bytes session)
        input (Channels/newInputStream ^SocketChannel channel)
        output (Channels/newOutputStream ^SocketChannel channel)
        request-id (::protocol/request-id message)]
    (try
      (write-frame! output message maximum-frame-bytes)
      (catch clojure.lang.ExceptionInfo exception
        (if (::frame-bytes (ex-data exception))
          (throw
           (ex-info
            "Database protocol frame is too large."
            (protocol/frame-too-large-failure
             {::protocol/request-id request-id
              ::protocol/maximum-frame-bytes maximum-frame-bytes})))
          (throw exception))))
    (loop []
      (let [response (read-frame input maximum-frame-bytes)]
        (cond
          (nil? response)
          (throw
           (ex-info "Database transport reached EOF before a response."
                    {::eof true
                     ::protocol/request-id request-id}))

          (::protocol/event response)
          (recur)

          (= request-id (::protocol/request-id response))
          response

          :else
          (throw
           (ex-info "Database response does not match the request."
                    {::protocol/request-id request-id
                     ::protocol/response response})))))))

(defn- codec-workers
  "Create the bounded off-selector codec and admission executor."
  ^ThreadPoolExecutor [worker-count worker-queue-capacity]
  (let [thread-number (atom 0)
        thread-factory
        (reify ThreadFactory
          (newThread [_ runnable]
            (doto (Thread. ^Runnable runnable
                           (str "database-request-codec-"
                                (swap! thread-number inc)))
              (.setDaemon true))))
        worker-count (or worker-count
                         (max 2 (min 8 (.availableProcessors
                                       (Runtime/getRuntime)))))]
    (ThreadPoolExecutor.
     worker-count worker-count 0 TimeUnit/MILLISECONDS
     (ArrayBlockingQueue. worker-queue-capacity)
     thread-factory
     (ThreadPoolExecutor$AbortPolicy.))))

(defn- cleanup-workers
  "Create separately bounded connection cleanup capacity."
  ^ThreadPoolExecutor [maximum-connections]
  (let [thread-number (atom 0)
        thread-factory
        (reify ThreadFactory
          (newThread [_ runnable]
            (doto (Thread. ^Runnable runnable
                           (str "database-request-cleanup-"
                                (swap! thread-number inc)))
              (.setDaemon true))))]
    (ThreadPoolExecutor.
     2 2 0 TimeUnit/MILLISECONDS
     (ArrayBlockingQueue. maximum-connections)
     thread-factory
     (ThreadPoolExecutor$AbortPolicy.))))

(defn- enqueue-selector!
  [selector ^LinkedBlockingQueue commands command]
  (.offer commands command)
  (selector-wakeup! selector)
  nil)

(defn- key-interests!
  [session add remove]
  (when-let [key @(::key session)]
    (when (key-valid? key)
      (key-interest-ops! key
                         (bit-and (bit-or (key-interest-ops key) add)
                                  (bit-not remove))))))

(declare resume-paused-reads!)

(defn- wake-paused-reads!
  [session]
  (when-let [paused-sessions (::paused-read-sessions session)]
    (enqueue-selector!
     (::selector session) (::commands session)
     #(resume-paused-reads! paused-sessions)))
  nil)

(defn- release-input-reservation!
  ([reservation]
   (release-input-reservation! reservation true))
  ([reservation wake?]
   (when (and reservation
              (.compareAndSet ^AtomicReference (::released? reservation)
                              false true))
     (let [authority-input-bytes (::authority-input-bytes reservation)]
       (locking authority-input-bytes
         (swap! authority-input-bytes - (::frame-bytes reservation))))
     (when wake?
       (wake-paused-reads! (::session reservation))))
   nil))

(defn- reserve-input!
  [session frame-bytes]
  (let [authority-input-bytes (::authority-input-bytes session)
        maximum-input-bytes (::maximum-input-bytes session)]
    (locking authority-input-bytes
      (let [next-bytes (+ @authority-input-bytes frame-bytes)]
        (when (<= next-bytes maximum-input-bytes)
          (let [reservation {::frame-bytes frame-bytes
                             ::session session
                             ::authority-input-bytes authority-input-bytes
                             ::released? (AtomicReference. false)}]
            (reset! authority-input-bytes next-bytes)
            (reset! (::input-reservation session) reservation)
            reservation))))))

(declare wake-paused-events!)

(defn- release-output-reservation!
  [session reservation]
  (let [released?
        (and reservation
             (not (.get ^AtomicReference (::encoding? reservation)))
             (.compareAndSet ^AtomicReference (::released? reservation)
                             false true))]
    (when released?
      (let [authority-output-bytes (::authority-output-bytes session)
            output-reservation (::output-reservation reservation)]
        (locking authority-output-bytes
          (let [reserved @output-reservation]
            (when (pos? reserved)
              (swap! authority-output-bytes - reserved)
              (swap! (::session-output-bytes session) - reserved)
              (reset! output-reservation 0)))))
      (wake-paused-reads! session)
      (wake-paused-events! (::paused-event-sessions session)))
    released?))

(defn- release-response-slot!
  [session slot]
  (when (release-output-reservation! session slot)
    (let [authority-count (::authority-response-slot-count session)]
      (locking authority-count
        (swap! (::response-slots session) disj slot)
        (swap! authority-count dec)
        (swap! (::outstanding session) dec))))
  nil)

(defn- reserve-response-slot-result!
  [session]
  (let [session-slots (::response-slots session)
        authority-count (::authority-response-slot-count session)]
    (locking authority-count
      (cond
        (>= (count @session-slots)
            (::maximum-session-response-slots session))
        {::send-status send-session-full}

        (>= @authority-count (::maximum-response-slots session))
        {::send-status send-authority-full}

        :else
        (let [slot {::released? (AtomicReference. false)
                    ::encoding? (AtomicReference. false)
                    ::output-reservation (atom 0)}]
          (swap! session-slots conj slot)
          (swap! authority-count inc)
          (swap! (::outstanding session) inc)
          {::send-status send-accepted ::response-slot slot})))))

(defn- reserve-request-capacity!
  [session length]
  (let [frame-bytes (+ Integer/BYTES length)]
    (if-let [input-reservation (reserve-input! session frame-bytes)]
      (let [slot-result (reserve-response-slot-result! session)]
        (if (= send-accepted (::send-status slot-result))
          {::input-reservation input-reservation
           ::response-slot (::response-slot slot-result)}
          (do
            (release-input-reservation! input-reservation false)
            (reset! (::input-reservation session) nil)
            slot-result)))
      {::send-status ::input-full})))

(defn- pause-session-read!
  [session length]
  (reset! (::paused-frame-length session) length)
  (swap! (::paused-read-sessions session) conj session)
  (key-interests! session 0 op-read)
  nil)

(defn- resume-paused-session!
  [session]
  (when (and (not (.get ^AtomicReference (::closed? session)))
             (not @(::shutting-down? session)))
    (when-let [length @(::paused-frame-length session)]
      (let [capacity (reserve-request-capacity! session length)]
        (when-let [input-reservation (::input-reservation capacity)]
          (reset! (::current-response-slot session)
                  (::response-slot capacity))
          (reset! (::payload session) (ByteBuffer/allocate length))
          (reset! (::paused-frame-length session) nil)
          (swap! (::paused-read-sessions session) disj session)
          (key-interests! session op-read 0)))))
  nil)

(defn- resume-paused-reads!
  [paused-sessions]
  (run! resume-paused-session! (vec @paused-sessions))
  nil)

(defn- reserve-encoded-output-result!
  [session slot exact-bytes]
  (let [authority-output-bytes (::authority-output-bytes session)]
    (locking authority-output-bytes
      (let [authority-next (+ @authority-output-bytes exact-bytes)
            session-next (+ @(::session-output-bytes session) exact-bytes)]
        (cond
          (or (.get ^AtomicReference (::released? slot))
              (.get ^AtomicReference (::closed? session)))
          {::send-status send-closed}

          (> session-next (::maximum-session-output-bytes session))
          {::send-status send-session-full}

          (> authority-next (::maximum-output-bytes session))
          {::send-status send-authority-full}

          :else
          (do
            (reset! authority-output-bytes authority-next)
            (reset! (::session-output-bytes session) session-next)
            (reset! (::output-reservation slot) exact-bytes)
            {::send-status send-accepted}))))))

(defn- remove-session!
  [connections session]
  (swap! connections dissoc (::channel session))
  (try
    (selector-wakeup! (::selector session))
    (catch Throwable _))
  nil)

(defn- remove-finished-session!
  [connections session]
  (when (and (.get ^AtomicReference (::closed? session))
             (.get ^AtomicReference (::cleanup-complete? session))
             (empty? @(::response-slots session)))
    (remove-session! connections session))
  nil)

(defn- notify-connection-close!
  [connections close-connection! session]
  (when (and (not= ::unopened @(::owner session))
             (compare-and-set! (::close-notified? session) false true))
    (try
      (.execute ^ThreadPoolExecutor (::cleanup-workers session)
                ^Runnable
                #(try
                   (close-connection! @(::owner session))
                   (catch Throwable throwable
                     (binding [*out* *err*]
                       (println "[database-request] connection close failed:"
                                (.getMessage throwable))))
                   (finally
                     (.set ^AtomicReference (::cleanup-complete? session) true)
                     (remove-finished-session! connections session))))
      (catch Throwable throwable
        (if (rejected-execution? throwable)
          (do
            ;; Retain the session and acquisition if the supposedly unreachable
            ;; capacity invariant fails. Shutdown evidence must then remain false.
            (reset! (::close-notified? session) false)
            (binding [*out* *err*]
              (println "[database-request] bounded connection cleanup rejected:"
                       (.getMessage throwable))))
          (throw throwable))))))

(defn- finish-session-close!
  [connections close-connection! session]
  (if (= ::unopened @(::owner session))
    (when-not (.get ^AtomicReference (::opening? session))
      (.set ^AtomicReference (::cleanup-complete? session) true)
      (remove-finished-session! connections session))
    (notify-connection-close! connections close-connection! session))
  nil)

(declare abandon-pending-encodes! finish-event!)

(defn- close-server-session!
  [connections workers close-connection! session]
  (when (.compareAndSet ^AtomicReference (::closed? session) false true)
    (when (and (= ::unopened @(::owner session))
               (not= ::owner-opening @(::phase session)))
      (.set ^AtomicReference (::opening? session) false))
    (swap! (::paused-read-sessions session) disj session)
    (reset! (::paused-frame-length session) nil)
    (when-let [key @(::key session)]
      (key-cancel! key))
    (try (.close ^SocketChannel (::channel session)) (catch Throwable _))
    (release-input-reservation! @(::input-reservation session))
    (reset! (::input-reservation session) nil)
    (locking (::send-lock session)
      (abandon-pending-encodes! session send-closed))
    (when-let [pending (::pending @(::event-state session))]
      (finish-event! session pending send-closed))
    (run! #(release-response-slot! session %)
          (vec @(::response-slots session)))
    (.clear ^ArrayDeque (::outputs session))
    (reset! (::queued-output-bytes session) 0))
  (when (.get ^AtomicReference (::closed? session))
    (finish-session-close! connections close-connection! session)
    (remove-finished-session! connections session))
  nil)

(defn- drained-session?
  [session]
  (and (zero? @(::outstanding session))
       (not @(::decoding? session))
       (.isEmpty ^ArrayDeque (::outputs session))
       (nil? @(::event-state session))))

(defn- close-drained-session!
  [connections workers close-connection! shutting-down? session]
  (when (and @shutting-down? (drained-session? session))
    (close-server-session! connections workers close-connection! session)))

(defn- accept-encoded-response!
  [connections workers close-connection! shutting-down? session frame slot]
  (if (.get ^AtomicReference (::closed? session))
    (do
      (release-response-slot! session slot)
      (remove-finished-session! connections session)
      false)
    (do
      (swap! (::queued-output-bytes session) + (.remaining ^ByteBuffer frame))
      (.addLast ^ArrayDeque (::outputs session)
                {::frame frame ::response-slot slot})
      (key-interests! session op-write 0)))
  (close-drained-session! connections workers close-connection!
                          shutting-down? session))

(defn- complete-send!
  [pending status]
  (when-let [completion (::send-completion pending)]
    (deliver completion status))
  (when-let [callback (::send-callback pending)]
    (try
      (callback status)
      (catch Throwable throwable
        (log/error throwable "UDS event completion callback failed"))))
  nil)

(defn- event-output-reservation []
  {::released? (AtomicReference. false)
   ::encoding? (AtomicReference. false)
   ::output-reservation (atom 0)})

(defn- finish-event!
  [session pending status]
  (when (identical? pending (::pending @(::event-state session)))
    (swap! (::paused-event-sessions session) disj session)
    (reset! (::event-state session) nil)
    (release-output-reservation! session (::output pending))
    (complete-send! pending status))
  nil)

(defn- select-encoded-event!
  [connections workers close-connection! shutting-down? session pending frame]
  (when (identical? pending (::pending @(::event-state session)))
    (if (.get ^AtomicReference (::closed? session))
      (finish-event! session pending send-closed)
      (let [slot (::output pending)
            reservation (reserve-encoded-output-result!
                         session slot (.remaining ^ByteBuffer frame))
            status (::send-status reservation)]
        (if (= send-accepted status)
          (do
            (reset! (::event-state session)
                    {::phase ::output ::pending pending ::frame frame})
            (swap! (::queued-output-bytes session)
                   + (.remaining ^ByteBuffer frame))
            (key-interests! session op-write 0))
          (do
            (reset! (::event-state session)
                    {::phase ::blocked ::pending pending ::frame frame})
            (swap! (::paused-event-sessions session) conj session))))))
  (close-drained-session! connections workers close-connection!
                          shutting-down? session))

(defn- resume-blocked-event!
  [session]
  (when-let [{::keys [phase pending frame]} @(::event-state session)]
    (when (and (= ::blocked phase)
               (not (.get ^AtomicReference (::closed? session))))
      (let [slot (::output pending)
            reservation (reserve-encoded-output-result!
                         session slot (.remaining ^ByteBuffer frame))]
        (when (= send-accepted (::send-status reservation))
          (swap! (::paused-event-sessions session) disj session)
          (reset! (::event-state session)
                  {::phase ::output ::pending pending ::frame frame})
          (swap! (::queued-output-bytes session)
                 + (.remaining ^ByteBuffer frame))
          (key-interests! session op-write 0))))))

(defn- wake-paused-events!
  [paused-sessions]
  (doseq [session (vec @paused-sessions)]
    (enqueue-selector! (::selector session) (::commands session)
                       #(resume-blocked-event! session)))
  nil)

(defn- admit-event!
  [connections ^ThreadPoolExecutor workers close-connection! shutting-down?
   session message callback]
  (if (some? @(::event-state session))
    {::send-status send-session-full}
    (let [completion (promise)
          slot (event-output-reservation)
          pending {::message message
                   ::output slot
                   ::send-completion completion
                   ::send-callback callback}
          encode!
          (fn []
            (.set ^AtomicReference (::encoding? slot) true)
            (let [encoded
                  (try
                    {::frame
                     (message-frame message
                                    @(::maximum-frame-bytes-state session))}
                    (catch Throwable throwable
                      {::encode-error throwable}))]
              (.set ^AtomicReference (::encoding? slot) false)
              (if-let [^ByteBuffer frame (::frame encoded)]
                (enqueue-selector!
                 (::selector session) (::commands session)
                 #(select-encoded-event!
                   connections workers close-connection! shutting-down?
                   session pending frame))
                (do
                  (finish-event! session pending send-encode-failed)
                  (enqueue-selector!
                   (::selector session) (::commands session)
                   #(close-server-session! connections workers close-connection!
                                           session))))))]
      (reset! (::event-state session) {::phase ::encoding ::pending pending})
      (try
        (.execute workers ^Runnable encode!)
        {::send-status send-accepted ::send-completion completion}
        (catch Throwable throwable
          (if (rejected-execution? throwable)
            (do
              (encode!)
              {::send-status send-accepted ::send-completion completion})
            (do
              (finish-event! session pending send-encode-failed)
              {::send-status send-encode-failed})))))))

(defn- abandon-pending-encodes!
  [session status]
  (let [^ArrayDeque pending (::pending-encodes session)]
    (loop []
      (when-let [entry (.pollFirst pending)]
        (release-response-slot! session (::response-slot entry))
        (complete-send! entry status)
        (recur)))))

(declare encode-session!)

(defn- schedule-session-encoding!
  [connections ^ThreadPoolExecutor workers close-connection! shutting-down?
   session]
  (let [encoding-active? ^AtomicReference (::encoding-active? session)]
    (if (.compareAndSet encoding-active? false true)
      (try
        (.execute workers
                  ^Runnable
                  #(encode-session! connections workers close-connection!
                                    shutting-down? session))
        true
        (catch Throwable throwable
          (if (rejected-execution? throwable)
            (do (.set encoding-active? false) false)
            (throw throwable))))
      true)))

(defn- fail-session-encoding!
  [connections workers close-connection! session pending]
  (.set ^AtomicReference (::encoding? (::response-slot pending)) false)
  (release-response-slot! session (::response-slot pending))
  (complete-send! pending send-encode-failed)
  (locking (::send-lock session)
    (.set ^AtomicReference (::closing? session) true)
    (abandon-pending-encodes! session send-closed))
  (.set ^AtomicReference (::encoding-active? session) false)
  (enqueue-selector!
   (::selector session) (::commands session)
   #(close-server-session! connections workers close-connection! session)))

(defn- fail-session-output!
  [connections workers close-connection! session pending status]
  (.set ^AtomicReference (::encoding? (::response-slot pending)) false)
  (release-response-slot! session (::response-slot pending))
  (complete-send! pending status)
  (locking (::send-lock session)
    (.set ^AtomicReference (::closing? session) true)
    (abandon-pending-encodes! session send-closed))
  (.set ^AtomicReference (::encoding-active? session) false)
  (enqueue-selector!
   (::selector session) (::commands session)
   #(close-server-session! connections workers close-connection! session)))

(defn- take-pending-encode!
  [session]
  (locking (::send-lock session)
    (if-let [pending
             (.pollFirst ^ArrayDeque (::pending-encodes session))]
      pending
      (do
        ;; The empty observation and idle publication are one transition.
        ;; Admission takes this same lock before appending and scheduling.
        (.set ^AtomicReference (::encoding-active? session) false)
        nil))))

(defn- encode-session!
  [connections workers close-connection! shutting-down? session]
  (loop []
    (let [pending (take-pending-encode! session)]
      (if pending
        (let [slot (::response-slot pending)]
          (.set ^AtomicReference (::encoding? slot) true)
          (let [maximum-frame-bytes @(::maximum-frame-bytes-state session)
                encoded
                (try
                  {::frame (message-frame (::message pending)
                                          maximum-frame-bytes)}
                  (catch Throwable throwable
                    (if (::frame-bytes (ex-data throwable))
                      {::frame
                       (message-frame
                        (protocol/frame-too-large-failure
                         {::protocol/request-id
                          (or (::protocol/request-id (::message pending))
                              protocol/session-control-request-id)
                          ::protocol/maximum-frame-bytes maximum-frame-bytes})
                        maximum-frame-bytes)}
                      {::encode-error throwable})))]
            (if-let [^ByteBuffer frame (::frame encoded)]
              (let [reservation
                    (reserve-encoded-output-result!
                     session slot (.remaining frame))
                    status (::send-status reservation)]
                (if (= send-accepted status)
                  (do
                    (enqueue-selector!
                     (::selector session) (::commands session)
                     #(do
                        (.set ^AtomicReference (::encoding? slot) false)
                        (accept-encoded-response!
                         connections workers close-connection! shutting-down?
                         session frame slot)))
                    (complete-send! pending send-accepted)
                    (recur))
                  (fail-session-output!
                   connections workers close-connection! session pending
                   status)))
              (fail-session-encoding!
               connections workers close-connection! session pending))))
        nil))))

(defn- close-session-after-admission-failure!
  [connections workers close-connection! session]
  (.set ^AtomicReference (::closing? session) true)
  (enqueue-selector!
   (::selector session) (::commands session)
   #(close-server-session! connections workers close-connection! session)))

(defn- admit-response!
  [connections workers close-connection! shutting-down? session message slot
   completion close-on-failure?]
  (let [pending {::message message
                 ::response-slot slot
                 ::send-completion completion}
        ^ArrayDeque queue (::pending-encodes session)]
    (.addLast queue pending)
    (if (schedule-session-encoding!
         connections workers close-connection! shutting-down? session)
      {::send-status send-accepted ::send-completion completion}
      (do
        (.removeLastOccurrence queue pending)
        (release-response-slot! session slot)
        (when close-on-failure?
          (close-session-after-admission-failure!
           connections workers close-connection! session))
        {::send-status send-authority-full}))))

(defn- queue-response!
  [connections workers close-connection! shutting-down? session response slot]
  (locking (::send-lock session)
    (if (or (.get ^AtomicReference (::closing? session))
            (.get ^AtomicReference (::closed? session)))
      (do
        (release-response-slot! session slot)
        {::send-status send-closed})
      (admit-response! connections workers close-connection! shutting-down?
                       session response slot nil true))))

(defn- admit-payload!
  [connections ^ThreadPoolExecutor workers close-connection! shutting-down?
   handler session payload input-reservation response-slot]
  (try
    (.execute
     workers
     ^Runnable
     (fn []
       (try
         (let [request (decode payload)
               completed? (AtomicReference. false)
               complete!
               (fn [response]
                 (when (.compareAndSet completed? false true)
                   (queue-response! connections workers close-connection!
                                    shutting-down? session response
                                    response-slot)))]
           (handler @(::owner session) request
                    (::frame-bytes input-reservation) complete!))
         (catch Throwable throwable
           (log/error throwable
                      "UDS request decode or handler admission failed")
           (enqueue-selector!
            (::selector session) (::commands session)
            #(close-server-session! connections workers close-connection!
                                    session)))
         (finally
           (release-input-reservation! input-reservation)
           (enqueue-selector!
            (::selector session) (::commands session)
            (fn []
              (reset! (::decoding? session) false)
              (if @shutting-down?
                (close-drained-session! connections workers close-connection!
                                        shutting-down? session)
                (key-interests! session op-read 0))))))))
    (catch Throwable throwable
      (if (rejected-execution? throwable)
        (do
          (log/error throwable "UDS request worker rejected admission")
          (close-server-session! connections workers close-connection! session))
        (throw throwable))))
  nil)

(defn- queue-session-control!
  [session response outcome]
  (let [frame (message-frame response
                             protocol/session-open-maximum-frame-bytes)]
    (reset! (::phase session) ::opening-response)
    (.addLast ^ArrayDeque (::outputs session)
              {::frame frame ::opening-outcome outcome})
    (swap! (::queued-output-bytes session) + (.remaining frame))
    (key-interests! session op-write op-read))
  nil)

(defn- opening-response
  [session request]
  (cond
    (not= protocol/session-open-operation (::protocol/operation request))
    [(protocol/session-open-required-failure
      {::protocol/request-id
       (or (::protocol/request-id request)
           protocol/session-control-request-id)})
     ::close]

    (not= protocol/current-version (::protocol/version request))
    [(if (pos-int? (::protocol/version request))
       (protocol/incompatible-version-failure
        {::protocol/peer-version (::protocol/version request)})
       (protocol/failure
        {::protocol/error-kind protocol/protocol-error
         ::protocol/error "The session-open request is invalid."
         ::protocol/body
         {::protocol/request-id protocol/session-open-request-id}}))
     ::close]

    (not (protocol/valid-request? request))
    [(protocol/failure
      {::protocol/error-kind protocol/protocol-error
       ::protocol/error "The session-open request is invalid."
       ::protocol/body
       {::protocol/request-id protocol/session-open-request-id}})
     ::close]

    (not (and (int? (::protocol/maximum-frame-bytes request))
              (<= protocol/session-open-maximum-frame-bytes
                  (::protocol/maximum-frame-bytes request)
                  protocol/maximum-frame-bytes)))
    [(protocol/failure
      {::protocol/error-kind protocol/protocol-error
       ::protocol/error "The requested database frame ceiling is invalid."
       ::protocol/body
       {::protocol/request-id protocol/session-open-request-id}})
     ::close]

    :else
    (let [agreed (min (::protocol/maximum-frame-bytes request)
                      (::configured-maximum-frame-bytes session))]
      (reset! (::maximum-frame-bytes-state session) agreed)
      [(protocol/session-open-success
        {::protocol/configured-maximum-frame-bytes
         (::configured-maximum-frame-bytes session)
         ::protocol/maximum-frame-bytes agreed})
       ::admit])))

(defn- admit-opening-payload!
  [connections ^ThreadPoolExecutor workers close-connection! session payload]
  (try
    (.execute
     workers
     ^Runnable
     (fn []
       (try
         (let [[response outcome] (opening-response session (decode payload))]
           (enqueue-selector!
            (::selector session) (::commands session)
            #(queue-session-control! session response outcome)))
         (catch Throwable throwable
           (log/error throwable "UDS session-open decode failed")
           (enqueue-selector!
            (::selector session) (::commands session)
            #(do
               (.set ^AtomicReference (::opening? session) false)
               (close-server-session! connections workers close-connection!
                                      session)))))))
    (catch Throwable throwable
      (if (rejected-execution? throwable)
        (do
          (.set ^AtomicReference (::opening? session) false)
          (close-server-session! connections workers close-connection! session))
        (throw throwable))))
  nil)

(defn- begin-owner-opening!
  [connections ^ThreadPoolExecutor workers close-connection! session]
  (reset! (::phase session) ::owner-opening)
  (try
    (.execute
     workers
     ^Runnable
     (fn []
       (try
         (let [owner ((::open-connection! session)
                      (::connection-control session))]
           (reset! (::owner session) owner)
           (.set ^AtomicReference (::opening? session) false)
           (if (.get ^AtomicReference (::closed? session))
             (finish-session-close! connections close-connection! session)
             (enqueue-selector!
              (::selector session) (::commands session)
              #(if (.get ^AtomicReference (::closed? session))
                 (finish-session-close! connections close-connection! session)
                 (do
                   (reset! (::phase session) ::open)
                   (key-interests! session op-read 0))))))
         (catch Throwable _
           (.set ^AtomicReference (::opening? session) false)
           (enqueue-selector!
            (::selector session) (::commands session)
            #(close-server-session! connections workers close-connection!
                                    session))))))
    (catch Throwable throwable
      (if (rejected-execution? throwable)
        (do
          (.set ^AtomicReference (::opening? session) false)
          (close-server-session! connections workers close-connection! session))
        (throw throwable))))
  nil)

(defn- read-session!
  [connections workers close-connection! shutting-down? handler session]
  (let [^SocketChannel channel (::channel session)]
    (loop []
      (let [^ByteBuffer target (or @(::payload session) (::header session))
            read-count (.read channel target)]
        (cond
          (neg? read-count)
          (close-server-session! connections workers close-connection! session)

          (zero? read-count)
          nil

          (.hasRemaining target)
          nil

          (nil? @(::payload session))
          (let [length (.getInt ^ByteBuffer (doto target .flip))
                opening? (not= ::open @(::phase session))
                frame-ceiling (if opening?
                                protocol/session-open-maximum-frame-bytes
                                @(::maximum-frame-bytes-state session))]
            (.clear target)
            (cond
              (or (not (pos? length)) (> length frame-ceiling))
              (queue-session-control!
               session
               (protocol/frame-too-large-failure
                {::protocol/request-id protocol/session-control-request-id
                 ::protocol/maximum-frame-bytes frame-ceiling})
               ::close)

              opening?
              (do
                (reset! (::payload session) (ByteBuffer/allocate length))
                (recur))

              :else
              (let [capacity (reserve-request-capacity! session length)]
                (if-let [input-reservation (::input-reservation capacity)]
                  (do
                    (reset! (::current-response-slot session)
                            (::response-slot capacity))
                    (reset! (::payload session) (ByteBuffer/allocate length))
                    (recur))
                  (pause-session-read! session length)))))

          :else
          (let [^ByteBuffer payload-buffer @(::payload session)
                payload (.array payload-buffer)
                input-reservation @(::input-reservation session)
                response-slot @(::current-response-slot session)
                opening? (not= ::open @(::phase session))]
            (reset! (::payload session) nil)
            (reset! (::input-reservation session) nil)
            (reset! (::current-response-slot session) nil)
            (key-interests! session 0 op-read)
            (if opening?
              (admit-opening-payload! connections workers close-connection!
                                      session payload)
              (do
                (reset! (::decoding? session) true)
                (admit-payload! connections workers close-connection!
                                shutting-down? handler session payload
                                input-reservation response-slot)))))))))

(defn- write-session!
  [connections workers close-connection! shutting-down? session]
  (let [^SocketChannel channel (::channel session)
        ^ArrayDeque outputs (::outputs session)]
    (loop []
      (if-let [{::keys [frame response-slot pending opening-outcome] :as output}
               (or (.peekFirst outputs)
                   (when (= ::output (::phase @(::event-state session)))
                     @(::event-state session)))]
        (do
          (.write channel ^ByteBuffer frame)
          (when-not (.hasRemaining ^ByteBuffer frame)
            (if (identical? output (.peekFirst outputs))
              (.removeFirst outputs)
              nil)
            (swap! (::queued-output-bytes session)
                   - (.limit ^ByteBuffer frame))
            (cond
              opening-outcome
              (case opening-outcome
                ::admit
                (begin-owner-opening! connections workers close-connection!
                                      session)

                ::close
                (do
                  (.set ^AtomicReference (::opening? session) false)
                  (close-server-session! connections workers close-connection!
                                         session)))

              pending
              (do
                (finish-event! session pending send-accepted)
                (remove-finished-session! connections session)
                (recur))

              :else
              (do
                (release-response-slot! session response-slot)
                (remove-finished-session! connections session)
                (recur)))))
        (do
          (key-interests! session 0 op-write)
          (close-drained-session! connections workers close-connection!
                                  shutting-down? session))))))

(defn- finish-capacity-rejection!
  [selection-key attachment]
  (key-cancel! selection-key)
  (try (.close ^SocketChannel (::channel attachment)) (catch Throwable _))
  (reset! (::rejecting-session (::server-capacity attachment)) nil)
  (let [accept-key (::accept-key attachment)]
    (when (and (not @(::shutting-down? attachment))
               (key-valid? accept-key))
      (key-interest-ops! accept-key
                         (bit-or (key-interest-ops accept-key) op-accept))))
  nil)

(defn- write-capacity-rejection!
  [selection-key attachment]
  (let [^ByteBuffer frame (::frame attachment)]
    (.write ^SocketChannel (::channel attachment) frame)
    (when-not (.hasRemaining frame)
      (finish-capacity-rejection! selection-key attachment))))

(defn- reject-capacity!
  [^SocketChannel channel selector accept-key shutting-down? server-capacity]
  (.configureBlocking channel false)
  (let [frame
        (message-frame
         (protocol/connection-capacity-failure
          {::protocol/maximum-connections
           (::maximum-connections server-capacity)})
         protocol/session-open-maximum-frame-bytes)
        attachment
        {::rejection? true
         ::channel channel
         ::frame frame
         ::accept-key accept-key
         ::shutting-down? shutting-down?
         ::server-capacity server-capacity}]
    (key-interest-ops! accept-key
                       (bit-and (key-interest-ops accept-key)
                                (bit-not op-accept)))
    (reset! (::rejecting-session server-capacity) attachment)
    (.register channel selector op-write attachment))
  nil)

(defn- accept-session!
  [^ServerSocketChannel server selector accept-key commands connections
   ^ThreadPoolExecutor workers close-connection! shutting-down? open-connection!
   server-capacity]
  (when-let [^SocketChannel channel (.accept server)]
    (if (>= (count @connections) (::maximum-connections server-capacity))
      (reject-capacity! channel selector accept-key shutting-down?
                        server-capacity)
      (do
        (.configureBlocking channel false)
        (let [session-holder (atom nil)
              close!
              (fn []
                (when-let [session @session-holder]
                  (locking (::send-lock session)
                    (when (.compareAndSet ^AtomicReference (::closing? session)
                                          false true)
                      (enqueue-selector!
                       selector commands
                       #(close-server-session! connections workers
                                               close-connection! session))))))
              send-event!
              (fn [message callback]
                (if-let [session @session-holder]
                  (locking (::send-lock session)
                    (if (or (.get ^AtomicReference (::closing? session))
                            (.get ^AtomicReference (::closed? session))
                            @shutting-down?)
                      {::send-status send-closed}
                      (admit-event! connections workers close-connection!
                                    shutting-down? session message callback)))
                  {::send-status send-closed}))
              send!
              (fn
                ([message] (send-event! message nil))
                ([message callback] (send-event! message callback)))
              connection-control {::close! close! ::send! send!}
              session
              {::channel channel
               ::selector selector
               ::commands commands
               ::key (atom nil)
               ::phase (atom ::opening)
               ::header (ByteBuffer/allocate Integer/BYTES)
               ::payload (atom nil)
               ::paused-frame-length (atom nil)
               ::paused-read-sessions (::paused-read-sessions server-capacity)
               ::input-reservation (atom nil)
               ::authority-input-bytes (::authority-input-bytes server-capacity)
               ::maximum-input-bytes (::maximum-input-bytes server-capacity)
               ::response-slots (atom #{})
               ::current-response-slot (atom nil)
               ::authority-response-slot-count
               (::authority-response-slot-count server-capacity)
               ::maximum-response-slots (::maximum-response-slots server-capacity)
               ::maximum-session-response-slots
               (::maximum-session-response-slots server-capacity)
               ::authority-output-bytes (::authority-output-bytes server-capacity)
               ::session-output-bytes (atom 0)
               ::maximum-output-bytes (::maximum-output-bytes server-capacity)
               ::maximum-session-output-bytes
               (::maximum-session-output-bytes server-capacity)
               ::configured-maximum-frame-bytes
               (::maximum-frame-bytes server-capacity)
               ::maximum-frame-bytes-state
               (atom (::maximum-frame-bytes server-capacity))
               ::maximum-frame-bytes (::maximum-frame-bytes server-capacity)
               ::cleanup-workers (::cleanup-workers server-capacity)
               ::shutting-down? shutting-down?
               ::open-connection! open-connection!
               ::connection-control connection-control
               ::pending-encodes (ArrayDeque.)
               ::encoding-active? (AtomicReference. false)
               ::outputs (ArrayDeque.)
               ::event-state (atom nil)
               ::paused-event-sessions (::paused-event-sessions server-capacity)
               ::queued-output-bytes (atom 0)
               ::decoding? (atom false)
               ::outstanding (atom 0)
               ::owner (atom ::unopened)
               ::opening? (AtomicReference. true)
               ::cleanup-complete? (AtomicReference. false)
               ::closing? (AtomicReference. false)
               ::closed? (AtomicReference. false)
               ::close-notified? (atom false)
               ::send-lock (Object.)
               ::close! close!}]
          (reset! session-holder session)
          (reset! (::key session) (.register channel selector op-read session))
          (swap! connections assoc channel session))))))

(defn- drain-commands!
  [^LinkedBlockingQueue commands]
  (loop []
    (when-let [command (.poll commands)]
      (command)
      (recur))))

(defn- begin-shutdown!
  [^ServerSocketChannel server connections workers close-connection!
   shutting-down? rejecting-session]
  (reset! shutting-down? true)
  (try (.close server) (catch Throwable _))
  (when-let [rejection @rejecting-session]
    (try (.close ^SocketChannel (::channel rejection)) (catch Throwable _))
    (reset! rejecting-session nil))
  (doseq [session (vals @connections)]
    (key-interests! session 0 op-read)
    (close-drained-session! connections workers close-connection!
                            shutting-down? session)))

(defn- force-close-sessions!
  [connections workers close-connection!]
  (doseq [session (vals @connections)]
    (close-server-session! connections workers close-connection! session)))

(defn- process-selected!
  [^ServerSocketChannel server selector commands connections workers
   close-connection! shutting-down? open-connection! handler server-capacity]
  (let [^java.util.Set selected (selector-selected-keys selector)
        ^java.util.Iterator iterator (.iterator selected)]
    (while (.hasNext iterator)
      (let [key (.next iterator)]
        (.remove iterator)
        (when (key-valid? key)
          (try
            (if (key-acceptable? key)
              (accept-session! server selector key commands connections workers
                               close-connection! shutting-down? open-connection!
                               server-capacity)
              (let [session (key-attachment key)]
                (if (::rejection? session)
                  (when (key-writable? key)
                    (write-capacity-rejection! key session))
                  (do
                    (when (key-readable? key)
                      (read-session! connections workers close-connection!
                                     shutting-down? handler session))
                    (when (and (key-valid? key) (key-writable? key))
                      (write-session! connections workers close-connection!
                                      shutting-down? session))))))
            (catch Throwable _
              (when-let [session (key-attachment key)]
                (if (::rejection? session)
                  (finish-capacity-rejection! key session)
                  (close-server-session! connections workers close-connection!
                                         session))))))))))

(defn start-request-server!
  "Start one selector server with callback-complete request delivery."
  {:malli/schema [:=> [:cat ::request-server-input] ::request-server]}
  [{::keys [socket-path handler open-connection! close-connection!
            shutdown-timeout-ms maximum-input-bytes maximum-response-slots
            maximum-session-response-slots maximum-output-bytes
            maximum-session-output-bytes maximum-connections
            maximum-frame-bytes]
    codec-worker-count ::codec-workers
    worker-queue-capacity ::codec-worker-queue-capacity
    :or {shutdown-timeout-ms default-shutdown-timeout-ms
         maximum-input-bytes default-maximum-input-bytes
         maximum-response-slots default-maximum-response-slots
         maximum-session-response-slots
         default-maximum-session-response-slots
         maximum-output-bytes default-maximum-output-bytes
         maximum-session-output-bytes default-maximum-session-output-bytes
         maximum-connections default-maximum-connections
         maximum-frame-bytes protocol/maximum-frame-bytes
         worker-queue-capacity default-codec-worker-queue-capacity}}]
  (try (.delete (java.io.File. ^String socket-path)) (catch Throwable _))
  (let [^UnixDomainSocketAddress address
        (UnixDomainSocketAddress/of ^String socket-path)
        ^ServerSocketChannel server
        (ServerSocketChannel/open StandardProtocolFamily/UNIX)
        selector (.openSelector (.provider server))
        commands (LinkedBlockingQueue.)
        workers (codec-workers codec-worker-count worker-queue-capacity)
        cleanup-pool (cleanup-workers maximum-connections)
        connections (atom {})
        closed? (AtomicReference. false)
        shutting-down? (atom false)
        server-capacity
        {::authority-input-bytes (atom 0)
         ::paused-read-sessions (atom #{})
         ::paused-event-sessions (atom #{})
         ::maximum-input-bytes maximum-input-bytes
         ::authority-response-slot-count (atom 0)
         ::maximum-response-slots maximum-response-slots
         ::maximum-session-response-slots maximum-session-response-slots
         ::authority-output-bytes (atom 0)
         ::maximum-output-bytes maximum-output-bytes
         ::maximum-session-output-bytes maximum-session-output-bytes
         ::maximum-frame-bytes maximum-frame-bytes
         ::maximum-connections maximum-connections
         ::rejecting-session (atom nil)
         ::cleanup-workers cleanup-pool}]
    (try
      (.bind server address)
      (.configureBlocking server false)
      (.register server selector op-accept)
      (let [selector-worker
          (Thread.
           ^Runnable
           (fn []
             (try
               (loop []
                 (drain-commands! commands)
                 (when-not (and @shutting-down? (empty? @connections))
                   (.select selector)
                   (process-selected! server selector commands connections
                                      workers close-connection! shutting-down?
                                      open-connection! handler server-capacity)
                   (recur)))
               (catch Throwable throwable
                 (when-not (.get closed?)
                   (binding [*out* *err*]
                     (println "[database-request] selector stopped:"
                              (.getMessage throwable)))))
               (finally
                 (try (.close server) (catch Throwable _))
                 (doseq [session (vals @connections)]
                   (close-server-session! connections workers close-connection!
                                          session)))))
           "database-request-selector")]
      (.setDaemon selector-worker true)
      (.start selector-worker)
      {::channel server
       ::connections connections
       ::worker selector-worker
       ::selector selector
       ::commands commands
       ::workers workers
       ::cleanup-workers cleanup-pool
       ::forced-connections (atom 0)
       ::shutting-down? shutting-down?
       ::shutdown-timeout-ms shutdown-timeout-ms
       ::authority-input-bytes (::authority-input-bytes server-capacity)
       ::authority-response-slot-count
       (::authority-response-slot-count server-capacity)
       ::authority-output-bytes (::authority-output-bytes server-capacity)
       ::rejecting-session (::rejecting-session server-capacity)
       ::close-connection! close-connection!
       ::closed? closed?})
      (catch Throwable throwable
        (try (.close server) (catch Throwable _))
        (try (.close selector) (catch Throwable _))
        (.shutdownNow workers)
        (.shutdownNow cleanup-pool)
        (try (.delete (java.io.File. ^String socket-path)) (catch Throwable _))
        (throw throwable)))))

(defn close-request-server!
  "Stop admission, drain admitted responses, and close every connection."
  {:malli/schema [:=> [:catn [::request-server ::request-server]]
                  ::shutdown-result]}
  [request-server]
  (let [closed? ^AtomicReference (::closed? request-server)
        selector (::selector request-server)
        selector-worker ^Thread (::worker request-server)
        workers ^ThreadPoolExecutor (::workers request-server)
        cleanup-pool ^ThreadPoolExecutor (::cleanup-workers request-server)
        shutdown-timeout-ms (long (::shutdown-timeout-ms request-server))]
    (when (.compareAndSet closed? false true)
      (enqueue-selector!
       selector (::commands request-server)
       #(begin-shutdown!
         (::channel request-server)
         (::connections request-server)
         workers
         (::close-connection! request-server)
         (::shutting-down? request-server)
         (::rejecting-session request-server))))
    (.join selector-worker shutdown-timeout-ms)
    (when (.isAlive selector-worker)
      (reset! (::forced-connections request-server)
              (count @(::connections request-server)))
      (enqueue-selector!
       selector (::commands request-server)
       #(force-close-sessions!
         (::connections request-server) workers
         (::close-connection! request-server)))
      (.join selector-worker shutdown-timeout-ms))
    (when (.isAlive selector-worker)
      (selector-close! selector)
      (.interrupt selector-worker)
      (.join selector-worker shutdown-timeout-ms))
    (let [selector-stopped? (not (.isAlive selector-worker))]
      (when (and selector-stopped? (selector-open? selector))
        (selector-close! selector))
      (when selector-stopped?
        (.shutdown workers))
      (let [codec-stopped?
            (and selector-stopped?
                 (or (.awaitTermination workers shutdown-timeout-ms
                                        TimeUnit/MILLISECONDS)
                     (do
                       (.shutdownNow workers)
                       (.awaitTermination workers shutdown-timeout-ms
                                          TimeUnit/MILLISECONDS))))
            commands-drained?
            (and codec-stopped?
                 (try
                   (drain-commands! (::commands request-server))
                   (empty? (::commands request-server))
                   (catch Throwable _ false)))
            workers-stopped? (and codec-stopped? commands-drained?)]
        (when workers-stopped?
          (.shutdown cleanup-pool))
        (let [cleanup-stopped?
              (and workers-stopped?
                   (or (.awaitTermination cleanup-pool shutdown-timeout-ms
                                          TimeUnit/MILLISECONDS)
                       (do
                         (.shutdownNow cleanup-pool)
                         (.awaitTermination cleanup-pool shutdown-timeout-ms
                                            TimeUnit/MILLISECONDS))))
              forced-connections @(::forced-connections request-server)
              fully-stopped?
              (and selector-stopped? workers-stopped? cleanup-stopped?
                   (empty? @(::connections request-server)))]
          {::graceful? (and fully-stopped? (zero? forced-connections))
           ::forced-connections forced-connections
           ::selector-stopped? selector-stopped?
           ::workers-stopped? workers-stopped?
           ::cleanup-stopped? cleanup-stopped?})))))
