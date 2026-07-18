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
           [java.nio.channels Channels ServerSocketChannel SocketChannel]
           [java.util ArrayDeque]
           [java.util.concurrent ArrayBlockingQueue
            LinkedBlockingQueue ThreadFactory
            ThreadPoolExecutor ThreadPoolExecutor$AbortPolicy TimeUnit]
           [java.util.concurrent.atomic AtomicReference]))

(set! *warn-on-reflection* true)

;; Babashka's SCI allowlist omits Selector and SelectionKey. Their public
;; operation bits and reflective construction keep this one transport owner
;; loadable by the operator as well as the JVM server.
(def ^:private op-read 1)
(def ^:private op-write 4)
(def ^:private op-accept 16)

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
  [::maximum-connections {:optional true} ::maximum-connections]])
(schema/register!
 ::call-input
 [:map [::channel ::channel] [::message ::message]])

(def ^:private maximum-frame-bytes protocol/maximum-frame-bytes)
(def ^:private codec-worker-queue-capacity 256)
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
        output (Channels/newOutputStream ^SocketChannel channel)
        request-id (::protocol/request-id message)]
    (write-frame! output message)
    (loop []
      (let [response (read-frame input)]
        (if (::protocol/event response)
          (recur)
          (if (= request-id (::protocol/request-id response))
            response
            (throw
             (ex-info "Database response does not match the request."
                      {::protocol/request-id request-id
                       ::protocol/response response}))))))))

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
  [selector ^LinkedBlockingQueue commands command]
  (.offer commands command)
  (.wakeup selector)
  nil)

(defn- key-interests!
  [session add remove]
  (when-let [key @(::key session)]
    (when (.isValid key)
      (.interestOps key
                    (bit-and (bit-or (.interestOps key) add)
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

(defn- release-response-slot!
  [session slot]
  (when (and slot
             (not (.get ^AtomicReference (::encoding? slot)))
             (.compareAndSet ^AtomicReference (::released? slot) false true))
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
        (swap! (::outstanding session) dec)))
    (wake-paused-reads! session))
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
    (.wakeup (::selector session))
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

(declare abandon-pending-encodes!)

(defn- close-session!
  [connections workers close-connection! session]
  (when (.compareAndSet ^AtomicReference (::closed? session) false true)
    (swap! (::paused-read-sessions session) disj session)
    (reset! (::paused-frame-length session) nil)
    (when-let [key @(::key session)]
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
  (when (.get ^AtomicReference (::closed? session))
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
   #(close-session! connections workers close-connection! session)))

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
   #(close-session! connections workers close-connection! session)))

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
          (let [encoded
                (try
                  {::frame (message-frame (::message pending))}
                  (catch Throwable throwable
                    {::encode-error throwable}))]
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
   #(close-session! connections workers close-connection! session)))

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
                (key-interests! session op-read 0))))))))
    (catch Throwable throwable
      (if (rejected-execution? throwable)
        (do
          (log/error throwable "UDS request worker rejected admission")
          (close-session! connections workers close-connection! session))
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
                response-slot @(::current-response-slot session)]
            (reset! (::payload session) nil)
            (reset! (::input-reservation session) nil)
            (reset! (::current-response-slot session) nil)
            (reset! (::decoding? session) true)
            (key-interests! session 0 op-read)
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
          (key-interests! session 0 op-write)
          (close-drained-session! connections workers close-connection!
                                  shutting-down? session))))))

(defn- accept-session!
  [^ServerSocketChannel server selector commands connections
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
                    (when (.compareAndSet ^AtomicReference (::closing? session)
                                          false true)
                      (enqueue-selector!
                       selector commands
                       #(close-session! connections workers close-connection!
                                        session))))))
              send!
              (fn [message]
                (if-let [session @session-holder]
                  (locking (::send-lock session)
                    (if (or (.get ^AtomicReference (::closing? session))
                            (.get ^AtomicReference (::closed? session))
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
               ::paused-frame-length (atom nil)
               ::paused-read-sessions (::paused-read-sessions server-capacity)
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
               ::shutting-down? shutting-down?
               ::pending-encodes (ArrayDeque.)
               ::encoding-active? (AtomicReference. false)
               ::outputs (ArrayDeque.)
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
                   (.set ^AtomicReference (::opening? session) false)
                   (if (.get ^AtomicReference (::closed? session))
                     (finish-session-close! connections close-connection!
                                            session)
                     (enqueue-selector!
                      selector commands
                      (fn []
                        (if (.get ^AtomicReference (::closed? session))
                          (finish-session-close! connections close-connection!
                                                 session)
                          (key-interests! session op-read 0))))))
                 (catch Throwable _
                   (.set ^AtomicReference (::opening? session) false)
                   (enqueue-selector!
                    selector commands
                    #(close-session! connections workers close-connection!
                                     session))))))
            (catch Throwable throwable
              (if (rejected-execution? throwable)
                (close-session! connections workers close-connection! session)
                (throw throwable)))))))))

(defn- drain-commands!
  [^LinkedBlockingQueue commands]
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
    (key-interests! session 0 op-read)
    (close-drained-session! connections workers close-connection!
                            shutting-down? session)))

(defn- force-close-sessions!
  [connections workers close-connection!]
  (doseq [session (vals @connections)]
    (close-session! connections workers close-connection! session)))

(defn- process-selected!
  [^ServerSocketChannel server selector commands connections workers
   close-connection! shutting-down? open-connection! handler server-capacity]
  (let [selected (.selectedKeys selector)
        iterator (.iterator selected)]
    (while (.hasNext iterator)
      (let [key (.next iterator)]
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
        selector (.openSelector (.provider server))
        commands (LinkedBlockingQueue.)
        workers (codec-workers)
        cleanup-pool (cleanup-workers maximum-connections)
        connections (atom {})
        closed? (AtomicReference. false)
        shutting-down? (atom false)
        server-capacity
        {::authority-input-bytes (atom 0)
         ::paused-read-sessions (atom #{})
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
