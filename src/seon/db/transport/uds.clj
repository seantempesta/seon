(ns seon.db.transport.uds
  "Unix-domain-socket delivery for database protocol maps.

   This namespace owns bytes, length framing, sockets, and subscriber
   resources only. It never dispatches a database operation or manufactures a
   semantic response. The same Transit map can later ride another transport
   without forking `seon.db.protocol`."
  (:require [cognitect.transit :as transit]
            [seon.db.protocol :as protocol]
            [seon.schema :as schema])
  (:import [java.io ByteArrayInputStream ByteArrayOutputStream
            DataInputStream DataOutputStream InputStream OutputStream]
           [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio ByteBuffer]
           [java.nio.channels Channels SelectionKey Selector ServerSocketChannel
            SocketChannel]
           [java.util ArrayDeque]
           [java.util.concurrent ArrayBlockingQueue BlockingQueue
            ConcurrentLinkedQueue RejectedExecutionException ThreadFactory
            ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit]
           [java.util.concurrent.atomic AtomicBoolean]))

(set! *warn-on-reflection* true)

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
 [:map {:closed true} [::close! ::close!] [::send! ::send!]])
(schema/register! ::shutdown-timeout-ms [:int {:min 1}])
(schema/register! ::maximum-input-bytes [:int {:min 1}])
(schema/register! ::maximum-response-slots [:int {:min 1}])
(schema/register! ::maximum-session-response-slots [:int {:min 1}])
(schema/register! ::maximum-output-bytes [:int {:min 1}])
(schema/register! ::maximum-session-output-bytes [:int {:min 1}])
(schema/register! ::maximum-connections [:int {:min 1}])
(schema/register! ::graceful? :boolean)
(schema/register! ::forced-connections [:int {:min 0}])
(schema/register! ::selector-stopped? :boolean)
(schema/register! ::workers-stopped? :boolean)
(schema/register! ::cleanup-stopped? :boolean)
(schema/register! ::channel 'some?)
(schema/register! ::output-stream 'some?)
(schema/register! ::subscribers 'some?)
(schema/register! ::connections 'some?)
(schema/register! ::selector 'some?)
(schema/register! ::workers 'some?)
(schema/register! ::closed? 'some?)
(schema/register! ::queue 'some?)
(schema/register! ::worker 'some?)
(schema/register! ::nil-result 'nil?)
(schema/register!
 ::publisher
 [:map
  [::channel ::channel]
  [::subscribers ::subscribers]
  [::closed? ::closed?]])
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
  [::maximum-connections {:optional true} ::maximum-connections]])
(schema/register!
 ::call-input
 [:map [::channel ::channel] [::message ::message]])
(schema/register!
 ::publish-input
 [:map [::publisher ::publisher] [::message ::message]])

(def ^:private maximum-frame-bytes protocol/maximum-frame-bytes)
(def ^:private subscriber-queue-capacity 16)
(def ^:private codec-worker-queue-capacity 256)
(def ^:private default-shutdown-timeout-ms 5000)
(def ^:private default-maximum-input-bytes (* 32 1024 1024))
(def ^:private default-maximum-response-slots 256)
(def ^:private default-maximum-session-response-slots 64)
(def ^:private default-maximum-output-bytes (* 256 1024 1024))
(def ^:private default-maximum-session-output-bytes (* 128 1024 1024))
(def ^:private default-maximum-connections 256)
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
        reader (transit/reader in :json)]
    (transit/read reader)))

(defn write-frame!
  "Write and flush one bounded length-prefixed Transit map."
  {:malli/schema [:=> [:catn [::output-stream ::output-stream]
                            [::message ::message]] ::nil-result]}
  [^OutputStream output message]
  (let [^bytes payload (encode message)
        length (alength payload)
        framed (DataOutputStream. output)]
    (when (> length maximum-frame-bytes)
      (throw (ex-info "Database protocol frame is too large."
                      {::frame-bytes length
                       ::maximum-frame-bytes maximum-frame-bytes})))
    (.writeInt framed length)
    (.write framed payload 0 length)
    (.flush framed)
    nil))

(defn- message-frame
  "Encode one complete length-prefixed frame into a fresh buffer."
  ^ByteBuffer [message]
  (let [^bytes payload (encode message)
        length (alength payload)]
    (when (> length maximum-frame-bytes)
      (throw (ex-info "Database protocol frame is too large."
                      {::frame-bytes length
                       ::maximum-frame-bytes maximum-frame-bytes})))
    (doto (ByteBuffer/allocate (+ Integer/BYTES length))
      (.putInt length)
      (.put payload)
      (.flip))))

(defn read-frame
  "Read one length-prefixed Transit map, or nil at EOF."
  {:malli/schema [:=> [:catn [:seon.db.transport.uds/input-stream :any]]
                  :any]}
  [^InputStream input]
  (let [framed (DataInputStream. input)
        length (try (.readInt framed)
                    (catch java.io.EOFException _ nil))]
    (when length
      (when (or (neg? length) (> length maximum-frame-bytes))
        (throw (ex-info "Database protocol frame length is invalid."
                        {::frame-bytes length
                         ::maximum-frame-bytes maximum-frame-bytes})))
      (let [payload (byte-array length)]
        (.readFully framed payload 0 length)
        (decode payload)))))

(defn connect!
  "Open a Unix-domain SocketChannel. The caller owns it."
  {:malli/schema [:=> [:catn [::socket-path ::socket-path]] ::channel]}
  ^SocketChannel [^String socket-path]
  (let [address (UnixDomainSocketAddress/of socket-path)
        channel (SocketChannel/open StandardProtocolFamily/UNIX)]
    (.connect channel address)
    channel))

(defn call!
  "Send one request and synchronously read one response on an open channel."
  {:malli/schema [:=> [:catn [::call-input ::call-input]] ::message]}
  [{::keys [channel message]}]
  (let [input (Channels/newInputStream ^SocketChannel channel)
        output (Channels/newOutputStream ^SocketChannel channel)]
    (write-frame! output message)
    (read-frame input)))

(defn- codec-workers
  "Create the bounded off-selector codec and admission executor."
  ^ThreadPoolExecutor []
  (let [thread-number (atom 0)
        thread-factory
        (reify ThreadFactory
          (newThread [_ runnable]
            (doto (Thread. ^Runnable runnable
                           (str "database-request-codec-"
                                (swap! thread-number inc)))
              (.setDaemon true))))
        worker-count (max 2 (min 8 (.availableProcessors
                                    (Runtime/getRuntime))))]
    (ThreadPoolExecutor.
     worker-count worker-count 0 TimeUnit/MILLISECONDS
     (ArrayBlockingQueue. codec-worker-queue-capacity)
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
  [^Selector selector ^ConcurrentLinkedQueue commands command]
  (.offer commands command)
  (.wakeup selector)
  nil)

(defn- key-interests!
  [session add remove]
  (when-let [^SelectionKey key @(::key session)]
    (when (.isValid key)
      (.interestOps key
                    (bit-and (bit-or (.interestOps key) add)
                             (bit-not remove))))))

(defn- release-input-reservation!
  [reservation]
  (when (and reservation
             (.compareAndSet ^AtomicBoolean (::released? reservation)
                             false true))
    (let [authority-input-bytes (::authority-input-bytes reservation)]
      (locking authority-input-bytes
        (swap! authority-input-bytes - (::frame-bytes reservation)))))
  nil)

(defn- reserve-input!
  [session frame-bytes]
  (let [authority-input-bytes (::authority-input-bytes session)
        maximum-input-bytes (::maximum-input-bytes session)]
    (locking authority-input-bytes
      (let [next-bytes (+ @authority-input-bytes frame-bytes)]
        (when (<= next-bytes maximum-input-bytes)
          (let [reservation {::frame-bytes frame-bytes
                             ::authority-input-bytes authority-input-bytes
                             ::released? (AtomicBoolean. false)}]
            (reset! authority-input-bytes next-bytes)
            (reset! (::input-reservation session) reservation)
            reservation))))))

(defn- release-response-slot!
  [session slot]
  (when (and slot
             (not (.get ^AtomicBoolean (::encoding? slot)))
             (.compareAndSet ^AtomicBoolean (::released? slot) false true))
    (let [authority-output-bytes (::authority-output-bytes session)
          output-reservation (::output-reservation slot)]
      (locking authority-output-bytes
        (let [reserved @output-reservation]
          (when (pos? reserved)
            (swap! authority-output-bytes - reserved)
            (swap! (::session-output-bytes session) - reserved)
            (reset! output-reservation 0)))))
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
        (let [slot {::released? (AtomicBoolean. false)
                    ::encoding? (AtomicBoolean. false)
                    ::output-reservation (atom 0)}]
          (swap! session-slots conj slot)
          (swap! authority-count inc)
          (swap! (::outstanding session) inc)
          {::send-status send-accepted ::response-slot slot})))))

(defn- reserve-response-slot!
  [session]
  (::response-slot (reserve-response-slot-result! session)))

(defn- reserve-output-allowance-result!
  [session slot]
  (let [authority-output-bytes (::authority-output-bytes session)
        allowance (* 2 (+ Integer/BYTES maximum-frame-bytes))]
    (locking authority-output-bytes
      (let [authority-next (+ @authority-output-bytes allowance)
            session-next (+ @(::session-output-bytes session) allowance)]
        (cond
          (.get ^AtomicBoolean (::released? slot))
          {::send-status send-closed}

          (> session-next (::maximum-session-output-bytes session))
          {::send-status send-session-full}

          (> authority-next (::maximum-output-bytes session))
          {::send-status send-authority-full}

          :else
          (do
            (reset! authority-output-bytes authority-next)
            (reset! (::session-output-bytes session) session-next)
            (reset! (::output-reservation slot) allowance)
            {::send-status send-accepted}))))))

(defn- shrink-output-reservation!
  [session slot exact-bytes]
  (let [authority-output-bytes (::authority-output-bytes session)]
    (locking authority-output-bytes
      (let [reserved @(::output-reservation slot)
            released (- reserved exact-bytes)]
        (when (pos? released)
          (swap! authority-output-bytes - released)
          (swap! (::session-output-bytes session) - released)
          (reset! (::output-reservation slot) exact-bytes)))))
  slot)

(defn- remove-session!
  [connections session]
  (swap! connections dissoc (::channel session))
  (try
    (.wakeup ^Selector (::selector session))
    (catch Throwable _))
  nil)

(defn- remove-finished-session!
  [connections session]
  (when (and (.get ^AtomicBoolean (::closed? session))
             (.get ^AtomicBoolean (::cleanup-complete? session))
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
                     (.set ^AtomicBoolean (::cleanup-complete? session) true)
                     (remove-finished-session! connections session))))
      (catch RejectedExecutionException throwable
        ;; Retain the session and acquisition if the supposedly unreachable
        ;; capacity invariant fails. Shutdown evidence must then remain false.
        (reset! (::close-notified? session) false)
        (binding [*out* *err*]
          (println "[database-request] bounded connection cleanup rejected:"
                   (.getMessage throwable)))))))

(defn- finish-session-close!
  [connections close-connection! session]
  (if (= ::unopened @(::owner session))
    (when-not (.get ^AtomicBoolean (::opening? session))
      (.set ^AtomicBoolean (::cleanup-complete? session) true)
      (remove-finished-session! connections session))
    (notify-connection-close! connections close-connection! session))
  nil)

(declare abandon-pending-encodes!)

(defn- close-session!
  [connections workers close-connection! session]
  (when (.compareAndSet ^AtomicBoolean (::closed? session) false true)
    (when-let [^SelectionKey key @(::key session)]
      (.cancel key))
    (try (.close ^SocketChannel (::channel session)) (catch Throwable _))
    (release-input-reservation! @(::input-reservation session))
    (reset! (::input-reservation session) nil)
    (locking (::send-lock session)
      (abandon-pending-encodes! session send-closed))
    (run! #(release-response-slot! session %)
          (vec @(::response-slots session)))
    (.clear ^ArrayDeque (::outputs session))
    (reset! (::queued-output-bytes session) 0))
  (when (.get ^AtomicBoolean (::closed? session))
    (finish-session-close! connections close-connection! session)
    (remove-finished-session! connections session))
  nil)

(defn- drained-session?
  [session]
  (and (zero? @(::outstanding session))
       (not @(::decoding? session))
       (.isEmpty ^ArrayDeque (::outputs session))))

(defn- close-drained-session!
  [connections workers close-connection! shutting-down? session]
  (when (and @shutting-down? (drained-session? session))
    (close-session! connections workers close-connection! session)))

(defn- accept-encoded-response!
  [connections workers close-connection! shutting-down? session frame slot]
  (if (.get ^AtomicBoolean (::closed? session))
    (do
      (release-response-slot! session slot)
      (remove-finished-session! connections session)
      false)
    (do
      (swap! (::queued-output-bytes session) + (.remaining ^ByteBuffer frame))
      (.addLast ^ArrayDeque (::outputs session)
                {::frame frame ::response-slot slot})
      (key-interests! session SelectionKey/OP_WRITE 0)))
  (close-drained-session! connections workers close-connection!
                          shutting-down? session))

(defn- complete-send!
  [pending status]
  (when-let [completion (::send-completion pending)]
    (deliver completion status))
  nil)

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
  (let [encoding-active? ^AtomicBoolean (::encoding-active? session)]
    (if (.compareAndSet encoding-active? false true)
      (try
        (.execute workers
                  ^Runnable
                  #(encode-session! connections workers close-connection!
                                    shutting-down? session))
        true
        (catch RejectedExecutionException _
          (.set encoding-active? false)
          false))
      true)))

(defn- fail-session-encoding!
  [connections workers close-connection! session pending]
  (.set ^AtomicBoolean (::encoding? (::response-slot pending)) false)
  (release-response-slot! session (::response-slot pending))
  (complete-send! pending send-encode-failed)
  (locking (::send-lock session)
    (.set ^AtomicBoolean (::closing? session) true)
    (abandon-pending-encodes! session send-closed))
  (.set ^AtomicBoolean (::encoding-active? session) false)
  (enqueue-selector!
   (::selector session) (::commands session)
   #(close-session! connections workers close-connection! session)))

(defn- encode-session!
  [connections workers close-connection! shutting-down? session]
  (loop []
    (let [pending
          (locking (::send-lock session)
            (.pollFirst ^ArrayDeque (::pending-encodes session)))]
      (if pending
        (let [slot (::response-slot pending)]
          (.set ^AtomicBoolean (::encoding? slot) true)
          (let [encoded
                (try
                  {::frame (message-frame (::message pending))}
                  (catch Throwable throwable
                    {::encode-error throwable}))]
            (if-let [^ByteBuffer frame (::frame encoded)]
              (do
                (shrink-output-reservation! session slot (.remaining frame))
                (enqueue-selector!
                 (::selector session) (::commands session)
                 #(do
                    (.set ^AtomicBoolean (::encoding? slot) false)
                    (accept-encoded-response!
                     connections workers close-connection! shutting-down?
                     session frame slot)))
                (complete-send! pending send-accepted)
                (recur))
              (fail-session-encoding!
               connections workers close-connection! session pending))))
        (locking (::send-lock session)
          (.set ^AtomicBoolean (::encoding-active? session) false)
          ;; Admission appends only under this lock, so clearing the flag while
          ;; the queue is empty cannot strand a later message.
          nil)))))

(defn- close-session-after-admission-failure!
  [connections workers close-connection! session]
  (.set ^AtomicBoolean (::closing? session) true)
  (enqueue-selector!
   (::selector session) (::commands session)
   #(close-session! connections workers close-connection! session)))

(defn- admit-response!
  [connections workers close-connection! shutting-down? session message slot
   completion close-on-failure?]
  (let [allowance (reserve-output-allowance-result! session slot)
        status (::send-status allowance)]
    (if-not (= send-accepted status)
      (do
        (release-response-slot! session slot)
        (when close-on-failure?
          (close-session-after-admission-failure!
           connections workers close-connection! session))
        {::send-status status})
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
            {::send-status send-authority-full}))))))

(defn- queue-response!
  [connections workers close-connection! shutting-down? session response slot]
  (locking (::send-lock session)
    (if (or (.get ^AtomicBoolean (::closing? session))
            (.get ^AtomicBoolean (::closed? session)))
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
               completed? (AtomicBoolean. false)
               complete!
               (fn [response]
                 (when (.compareAndSet completed? false true)
                   (queue-response! connections workers close-connection!
                                    shutting-down? session response
                                    response-slot)))]
           (handler @(::owner session) request
                    (::frame-bytes input-reservation) complete!))
         (catch Throwable _
           (enqueue-selector!
            (::selector session) (::commands session)
            #(close-session! connections workers close-connection! session)))
         (finally
           (release-input-reservation! input-reservation)
           (enqueue-selector!
            (::selector session) (::commands session)
            (fn []
              (reset! (::decoding? session) false)
              (if @shutting-down?
                (close-drained-session! connections workers close-connection!
                                        shutting-down? session)
                (key-interests! session SelectionKey/OP_READ 0))))))))
    (catch RejectedExecutionException _
      (close-session! connections workers close-connection! session)))
  nil)

(defn- read-session!
  [connections workers close-connection! shutting-down? handler session]
  (let [^SocketChannel channel (::channel session)]
    (loop []
      (let [^ByteBuffer target (or @(::payload session) (::header session))
            read-count (.read channel target)]
        (cond
          (neg? read-count)
          (close-session! connections workers close-connection! session)

          (zero? read-count)
          nil

          (.hasRemaining target)
          nil

          (nil? @(::payload session))
          (let [length (.getInt ^ByteBuffer (doto target .flip))]
            (.clear target)
            (if (or (not (pos? length)) (> length maximum-frame-bytes))
              (close-session! connections workers close-connection! session)
              (let [frame-bytes (+ Integer/BYTES length)
                    input-reservation (reserve-input! session frame-bytes)
                    response-slot (when input-reservation
                                    (reserve-response-slot! session))]
                (if (and input-reservation response-slot)
                  (do
                    (reset! (::current-response-slot session) response-slot)
                    (reset! (::payload session) (ByteBuffer/allocate length))
                    (recur))
                  (do
                    (release-input-reservation! input-reservation)
                    (reset! (::input-reservation session) nil)
                    (close-session! connections workers close-connection!
                                    session))))))

          :else
          (let [^ByteBuffer payload-buffer @(::payload session)
                payload (.array payload-buffer)
                input-reservation @(::input-reservation session)
                response-slot @(::current-response-slot session)]
            (reset! (::payload session) nil)
            (reset! (::input-reservation session) nil)
            (reset! (::current-response-slot session) nil)
            (reset! (::decoding? session) true)
            (key-interests! session 0 SelectionKey/OP_READ)
            (admit-payload! connections workers close-connection!
                            shutting-down? handler session payload
                            input-reservation response-slot)))))))

(defn- write-session!
  [connections workers close-connection! shutting-down? session]
  (let [^SocketChannel channel (::channel session)
        ^ArrayDeque outputs (::outputs session)]
    (loop []
      (if-let [{::keys [frame response-slot]} (.peekFirst outputs)]
        (do
          (.write channel ^ByteBuffer frame)
          (when-not (.hasRemaining ^ByteBuffer frame)
            (.removeFirst outputs)
            (swap! (::queued-output-bytes session)
                   - (.limit ^ByteBuffer frame))
            (release-response-slot! session response-slot)
            (remove-finished-session! connections session)
            (recur)))
        (do
          (key-interests! session 0 SelectionKey/OP_WRITE)
          (close-drained-session! connections workers close-connection!
                                  shutting-down? session))))))

(defn- accept-session!
  [^ServerSocketChannel server ^Selector selector commands connections
   ^ThreadPoolExecutor workers
   close-connection! shutting-down? open-connection! server-capacity]
  (when-let [^SocketChannel channel (.accept server)]
    (if (>= (count @connections) (::maximum-connections server-capacity))
      (.close channel)
      (do
        (.configureBlocking channel false)
        (let [session-holder (atom nil)
              close!
              (fn []
                (when-let [session @session-holder]
                  (locking (::send-lock session)
                    (when (.compareAndSet ^AtomicBoolean (::closing? session)
                                          false true)
                      (enqueue-selector!
                       selector commands
                       #(close-session! connections workers close-connection!
                                        session))))))
              send!
              (fn [message]
                (if-let [session @session-holder]
                  (locking (::send-lock session)
                    (if (or (.get ^AtomicBoolean (::closing? session))
                            (.get ^AtomicBoolean (::closed? session))
                            @shutting-down?)
                      {::send-status send-closed}
                      (let [slot-result (reserve-response-slot-result! session)
                            status (::send-status slot-result)]
                        (if (= send-accepted status)
                          (let [completion (promise)
                                result
                                (admit-response!
                                 connections workers close-connection!
                                 shutting-down? session message
                                 (::response-slot slot-result) completion false)]
                            (when (= send-session-full (::send-status result))
                              (close-session-after-admission-failure!
                               connections workers close-connection! session))
                            result)
                          (do
                            (when (= send-session-full status)
                              (close-session-after-admission-failure!
                               connections workers close-connection! session))
                            {::send-status status})))))
                  {::send-status send-closed}))
              session
              {::channel channel
               ::selector selector
               ::commands commands
               ::key (atom nil)
               ::header (ByteBuffer/allocate Integer/BYTES)
               ::payload (atom nil)
               ::input-reservation (atom nil)
               ::authority-input-bytes (::authority-input-bytes server-capacity)
               ::maximum-input-bytes (::maximum-input-bytes server-capacity)
               ::response-slots (atom #{})
               ::current-response-slot (atom nil)
               ::authority-response-slot-count
               (::authority-response-slot-count server-capacity)
               ::maximum-response-slots
               (::maximum-response-slots server-capacity)
               ::maximum-session-response-slots
               (::maximum-session-response-slots server-capacity)
               ::authority-output-bytes
               (::authority-output-bytes server-capacity)
               ::session-output-bytes (atom 0)
               ::maximum-output-bytes (::maximum-output-bytes server-capacity)
               ::maximum-session-output-bytes
               (::maximum-session-output-bytes server-capacity)
               ::cleanup-workers (::cleanup-workers server-capacity)
               ::pending-encodes (ArrayDeque.)
               ::encoding-active? (AtomicBoolean. false)
               ::outputs (ArrayDeque.)
               ::queued-output-bytes (atom 0)
               ::decoding? (atom false)
               ::outstanding (atom 0)
               ::owner (atom ::unopened)
               ::opening? (AtomicBoolean. true)
               ::cleanup-complete? (AtomicBoolean. false)
               ::closing? (AtomicBoolean. false)
               ::closed? (AtomicBoolean. false)
               ::close-notified? (atom false)
               ::send-lock (Object.)
               ::close! close!}]
          (reset! session-holder session)
          (reset! (::key session) (.register channel selector 0 session))
          (swap! connections assoc channel session)
          (try
            (.execute
             workers
             ^Runnable
             (fn []
               (try
                 (let [owner (open-connection!
                              {::close! close! ::send! send!})]
                   (reset! (::owner session) owner)
                   (.set ^AtomicBoolean (::opening? session) false)
                   (if (.get ^AtomicBoolean (::closed? session))
                     (finish-session-close! connections close-connection!
                                            session)
                     (enqueue-selector!
                      selector commands
                      (fn []
                        (if (.get ^AtomicBoolean (::closed? session))
                          (finish-session-close! connections close-connection!
                                                 session)
                          (key-interests! session SelectionKey/OP_READ 0))))))
                 (catch Throwable _
                   (.set ^AtomicBoolean (::opening? session) false)
                   (enqueue-selector!
                    selector commands
                    #(close-session! connections workers close-connection!
                                     session))))))
            (catch RejectedExecutionException _
              (close-session! connections workers close-connection!
                              session))))))))

(defn- drain-commands!
  [^ConcurrentLinkedQueue commands]
  (loop []
    (when-let [command (.poll commands)]
      (command)
      (recur))))

(defn- begin-shutdown!
  [^ServerSocketChannel server connections workers close-connection!
   shutting-down?]
  (reset! shutting-down? true)
  (try (.close server) (catch Throwable _))
  (doseq [session (vals @connections)]
    (key-interests! session 0 SelectionKey/OP_READ)
    (close-drained-session! connections workers close-connection!
                            shutting-down? session)))

(defn- force-close-sessions!
  [connections workers close-connection!]
  (doseq [session (vals @connections)]
    (close-session! connections workers close-connection! session)))

(defn- process-selected!
  [^ServerSocketChannel server ^Selector selector commands connections workers
   close-connection! shutting-down? open-connection! handler server-capacity]
  (let [selected (.selectedKeys selector)
        iterator (.iterator selected)]
    (while (.hasNext iterator)
      (let [^SelectionKey key (.next iterator)]
        (.remove iterator)
        (when (.isValid key)
          (try
            (if (.isAcceptable key)
              (accept-session! server selector commands connections workers
                               close-connection! shutting-down? open-connection!
                               server-capacity)
              (let [session (.attachment key)]
                (when (.isReadable key)
                  (read-session! connections workers close-connection!
                                 shutting-down? handler session))
                (when (and (.isValid key) (.isWritable key))
                  (write-session! connections workers close-connection!
                                  shutting-down? session))))
            (catch Throwable _
              (when-let [session (.attachment key)]
                (close-session! connections workers close-connection!
                                session)))))))))

(defn start-request-server!
  "Start one selector server with callback-complete request delivery."
  {:malli/schema [:=> [:cat ::request-server-input] ::request-server]}
  [{::keys [socket-path handler open-connection! close-connection!
            shutdown-timeout-ms maximum-input-bytes maximum-response-slots
            maximum-session-response-slots maximum-output-bytes
            maximum-session-output-bytes maximum-connections]
    :or {shutdown-timeout-ms default-shutdown-timeout-ms
         maximum-input-bytes default-maximum-input-bytes
         maximum-response-slots default-maximum-response-slots
         maximum-session-response-slots
         default-maximum-session-response-slots
         maximum-output-bytes default-maximum-output-bytes
         maximum-session-output-bytes default-maximum-session-output-bytes
         maximum-connections default-maximum-connections}}]
  (try (.delete (java.io.File. ^String socket-path)) (catch Throwable _))
  (let [^UnixDomainSocketAddress address
        (UnixDomainSocketAddress/of ^String socket-path)
        ^ServerSocketChannel server
        (ServerSocketChannel/open StandardProtocolFamily/UNIX)
        selector (Selector/open)
        commands (ConcurrentLinkedQueue.)
        workers (codec-workers)
        cleanup-pool (cleanup-workers maximum-connections)
        connections (atom {})
        closed? (AtomicBoolean. false)
        shutting-down? (atom false)
        server-capacity
        {::authority-input-bytes (atom 0)
         ::maximum-input-bytes maximum-input-bytes
         ::authority-response-slot-count (atom 0)
         ::maximum-response-slots maximum-response-slots
         ::maximum-session-response-slots maximum-session-response-slots
         ::authority-output-bytes (atom 0)
         ::maximum-output-bytes maximum-output-bytes
         ::maximum-session-output-bytes maximum-session-output-bytes
         ::maximum-connections maximum-connections
         ::cleanup-workers cleanup-pool}]
    (try
      (.bind server address)
      (.configureBlocking server false)
      (.register server selector SelectionKey/OP_ACCEPT)
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
                   (close-session! connections workers close-connection!
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
  (let [closed? ^AtomicBoolean (::closed? request-server)
        selector ^Selector (::selector request-server)
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
         (::shutting-down? request-server))))
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
      (.close selector)
      (.interrupt selector-worker)
      (.join selector-worker shutdown-timeout-ms))
    (let [selector-stopped? (not (.isAlive selector-worker))]
      (when (and selector-stopped? (.isOpen selector))
        (.close selector))
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

(defn- close-subscriber!
  [{::keys [channel worker]}]
  (when channel
    (try (.close ^SocketChannel channel) (catch Throwable _)))
  (when worker
    (.interrupt ^Thread worker)))

(defn- start-subscriber!
  [subscribers closed? ^SocketChannel channel]
  (.configureBlocking channel true)
  (let [queue (ArrayBlockingQueue. subscriber-queue-capacity)
        subscriber-holder (atom nil)
        worker
        (Thread.
         ^Runnable
         (fn []
           (try
             (loop []
               (let [^ByteBuffer frame (.take ^BlockingQueue queue)]
                 (loop []
                   (when (.hasRemaining frame)
                     (.write channel frame)
                     (recur)))
                 (recur)))
             (catch InterruptedException _ nil)
             (catch Throwable throwable
               (when-not (or @closed? (asynchronous-close? throwable))
                 (binding [*out* *err*]
                   (println "[database-publish] subscriber closed:"
                            (.getMessage throwable)))))
             (finally
               (when-let [subscriber @subscriber-holder]
                 (swap! subscribers disj subscriber))
               (try (.close channel) (catch Throwable _)))))
         "database-publish-subscriber")
        subscriber {::channel channel ::queue queue ::worker worker}]
    (reset! subscriber-holder subscriber)
    (locking subscribers
      (if @closed?
        (close-subscriber! subscriber)
        (do
          (swap! subscribers conj subscriber)
          (doto worker (.setDaemon true) (.start)))))))

(defn start-publisher!
  "Start a fanout socket and return its explicit publisher resource."
  {:malli/schema [:=> [:catn [::socket-path ::socket-path]] ::publisher]}
  [socket-path]
  (try (.delete (java.io.File. ^String socket-path)) (catch Throwable _))
  (let [^UnixDomainSocketAddress address
        (UnixDomainSocketAddress/of ^String socket-path)
        ^ServerSocketChannel server
        (ServerSocketChannel/open StandardProtocolFamily/UNIX)
        subscribers (atom #{})
        closed? (atom false)]
    (.bind server address)
    (doto
      (Thread.
       ^Runnable
       (fn []
         (try
           (loop []
             (start-subscriber! subscribers closed? (.accept server))
             (recur))
           (catch Throwable throwable
             (when-not (asynchronous-close? throwable)
               (binding [*out* *err*]
                 (println "[database-publish] accept loop stopped:"
                          (.getMessage throwable)))))))
       "database-publish-accept")
      (.setDaemon true)
      (.start))
    {::channel server ::subscribers subscribers ::closed? closed?}))

(defn publish!
  "Offer one event to each subscriber without blocking the writer.

   Each subscriber has one daemon writer and a fixed-capacity queue. The writer
   completes every accepted frame even when the socket accepts only a partial
   write. A subscriber that cannot keep up with the bounded queue is closed;
   its normal reconnect and transaction replay recover the exact missed range."
  {:malli/schema [:=> [:cat ::publish-input] ::nil-result]}
  [{::keys [publisher message]}]
  (let [subscribers (::subscribers publisher)
        snapshot (if @(::closed? publisher) #{} @subscribers)]
    (when (seq snapshot)
      (let [^ByteBuffer frame (message-frame message)
            dead
            (reduce
             (fn [failed {::keys [queue] :as subscriber}]
               (if (.offer ^BlockingQueue queue (.duplicate frame))
                 failed
                 (conj failed subscriber)))
             []
             snapshot)]
        (when (seq dead)
          (swap! subscribers #(reduce disj % dead))
          (run! close-subscriber! dead))))
    nil))

(defn close-publisher!
  "Close the publisher socket and every connected subscriber."
  {:malli/schema [:=> [:catn [::publisher ::publisher]] ::nil-result]}
  [publisher]
  (let [subscribers (::subscribers publisher)
        closed? (::closed? publisher)]
    (locking subscribers
      (reset! closed? true)
      (try (.close ^ServerSocketChannel (::channel publisher))
           (catch Throwable _))
      (run! close-subscriber! @subscribers)
      (reset! subscribers #{}))
    nil))
