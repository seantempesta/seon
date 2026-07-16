(ns seon.db.transport.uds
  "Unix-domain-socket delivery for database protocol maps.

   This namespace owns bytes, length framing, sockets, and subscriber
   resources only. It never dispatches a database operation or manufactures a
   semantic response. The same Transit map can later ride another transport
   without forking `seon.db.protocol`."
  (:require [cognitect.transit :as transit]
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
 ::request-server-input
 [:map
  [::socket-path ::socket-path]
  [::handler ::handler]
  [::open-connection! ::open-connection!]
  [::close-connection! ::close-connection!]])
(schema/register!
 ::call-input
 [:map [::channel ::channel] [::message ::message]])
(schema/register!
 ::publish-input
 [:map [::publisher ::publisher] [::message ::message]])

(def ^:private maximum-frame-bytes (* 16 1024 1024))
(def ^:private subscriber-queue-capacity 16)
(def ^:private codec-worker-queue-capacity 256)
(def ^:private maximum-queued-output-bytes (* 32 1024 1024))
(def ^:private maximum-queued-output-frames 64)
(def ^:private asynchronous-close-class
  (Class/forName "java.nio.channels.AsynchronousCloseException"))

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

(defn- notify-connection-close!
  [^ThreadPoolExecutor workers close-connection! session]
  (when (and (not= ::unopened @(::owner session))
             (compare-and-set! (::close-notified? session) false true))
    (try
      (.execute workers
                ^Runnable
                #(try
                   (close-connection! @(::owner session))
                   (catch Throwable throwable
                     (binding [*out* *err*]
                       (println "[database-request] connection close failed:"
                                (.getMessage throwable))))))
      (catch RejectedExecutionException _
        ;; Saturation must not move writer cleanup onto the selector. This
        ;; daemon exists only for the exceptional rejected-cleanup path; the
        ;; steady state has one bounded worker pool, not a thread per session.
        (doto
          (Thread.
           ^Runnable
           #(try
              (close-connection! @(::owner session))
              (catch Throwable throwable
                (binding [*out* *err*]
                  (println "[database-request] connection close failed:"
                           (.getMessage throwable)))))
           "database-request-rejected-close")
          (.setDaemon true)
          (.start))))))

(defn- close-session!
  [connections workers close-connection! session]
  (when (.compareAndSet ^AtomicBoolean (::closed? session) false true)
    (when-let [^SelectionKey key @(::key session)]
      (.cancel key))
    (try (.close ^SocketChannel (::channel session)) (catch Throwable _))
    (swap! connections dissoc (::channel session))
    (notify-connection-close! workers close-connection! session))
  nil)

(defn- drained-session?
  [session]
  (and (zero? @(::outstanding session))
       (not @(::decoding? session))
       (not @(::encoding? session))
       (.isEmpty ^ArrayDeque (::response-candidates session))
       (.isEmpty ^ArrayDeque (::outputs session))))

(defn- close-drained-session!
  [connections workers close-connection! shutting-down? session]
  (when (and @shutting-down? (drained-session? session))
    (close-session! connections workers close-connection! session)))

(declare start-next-encode!)

(defn- accept-encoded-response!
  [connections workers close-connection! shutting-down? session frame]
  (reset! (::encoding? session) false)
  (when-not (.get ^AtomicBoolean (::closed? session))
    (let [queued-bytes (+ @(::queued-output-bytes session)
                          (.remaining ^ByteBuffer frame))]
      (if (> queued-bytes maximum-queued-output-bytes)
        (close-session! connections workers close-connection! session)
        (do
          (reset! (::queued-output-bytes session) queued-bytes)
          (.addLast ^ArrayDeque (::outputs session) frame)
          (key-interests! session SelectionKey/OP_WRITE 0)
          (start-next-encode! connections workers close-connection!
                              shutting-down? session))))))

(defn- start-next-encode!
  [connections ^ThreadPoolExecutor workers close-connection! shutting-down?
   session]
  (when (and (not @(::encoding? session))
             (not (.get ^AtomicBoolean (::closed? session))))
    (when-let [response (.pollFirst ^ArrayDeque
                                    (::response-candidates session))]
      (reset! (::encoding? session) true)
      (try
        (.execute
         workers
         ^Runnable
         (fn []
           (try
             (let [frame (message-frame response)]
               (enqueue-selector!
                (::selector session) (::commands session)
                #(accept-encoded-response!
                  connections workers close-connection! shutting-down?
                  session frame)))
             (catch Throwable _
               (enqueue-selector!
                (::selector session) (::commands session)
                #(close-session! connections workers close-connection!
                                 session))))))
        (catch RejectedExecutionException _
          (close-session! connections workers close-connection! session))))))

(defn- queue-response!
  [connections workers close-connection! shutting-down? session response]
  (when-not (.get ^AtomicBoolean (::closed? session))
    (enqueue-selector!
     (::selector session) (::commands session)
     (fn []
       (when-not (.get ^AtomicBoolean (::closed? session))
         (if (>= (+ (.size ^ArrayDeque (::response-candidates session))
                    (.size ^ArrayDeque (::outputs session)))
                 maximum-queued-output-frames)
           (close-session! connections workers close-connection! session)
           (do
             (.addLast ^ArrayDeque (::response-candidates session) response)
             (start-next-encode! connections workers close-connection!
                                 shutting-down? session)))))))
  nil)

(defn- admit-payload!
  [connections ^ThreadPoolExecutor workers close-connection! shutting-down?
   handler session payload]
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
                                    shutting-down? session response)))]
           (handler @(::owner session) request complete!))
         (catch Throwable _
           (enqueue-selector!
            (::selector session) (::commands session)
            #(close-session! connections workers close-connection! session)))
         (finally
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
              (do
                (reset! (::payload session) (ByteBuffer/allocate length))
                (recur))))

          :else
          (let [^ByteBuffer payload-buffer @(::payload session)
                payload (.array payload-buffer)]
            (reset! (::payload session) nil)
            (reset! (::decoding? session) true)
            (swap! (::outstanding session) inc)
            (key-interests! session 0 SelectionKey/OP_READ)
            (admit-payload! connections workers close-connection!
                            shutting-down? handler session payload)))))))

(defn- write-session!
  [connections workers close-connection! shutting-down? session]
  (let [^SocketChannel channel (::channel session)
        ^ArrayDeque outputs (::outputs session)]
    (loop []
      (if-let [^ByteBuffer frame (.peekFirst outputs)]
        (do
          (.write channel frame)
          (when-not (.hasRemaining frame)
            (.removeFirst outputs)
            (swap! (::queued-output-bytes session) - (.limit frame))
            (swap! (::outstanding session) dec)
            (recur)))
        (do
          (key-interests! session 0 SelectionKey/OP_WRITE)
          (close-drained-session! connections workers close-connection!
                                  shutting-down? session))))))

(defn- accept-session!
  [^ServerSocketChannel server ^Selector selector commands connections
   ^ThreadPoolExecutor workers
   close-connection! shutting-down? open-connection!]
  (when-let [^SocketChannel channel (.accept server)]
    (.configureBlocking channel false)
    (let [session-holder (atom nil)
          close!
          (fn []
            (when-let [session @session-holder]
              (enqueue-selector!
               selector commands
               #(close-session! connections workers close-connection! session))))
          session
          {::channel channel
           ::selector selector
           ::commands commands
           ::key (atom nil)
           ::header (ByteBuffer/allocate Integer/BYTES)
           ::payload (atom nil)
           ::response-candidates (ArrayDeque.)
           ::encoding? (atom false)
           ::outputs (ArrayDeque.)
           ::queued-output-bytes (atom 0)
           ::decoding? (atom false)
           ::outstanding (atom 0)
           ::owner (atom ::unopened)
           ::closed? (AtomicBoolean. false)
           ::close-notified? (atom false)
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
             (let [owner (open-connection! close!)]
               (reset! (::owner session) owner)
               (if (.get ^AtomicBoolean (::closed? session))
                 (notify-connection-close! workers close-connection! session)
                 (enqueue-selector!
                  selector commands
                  (fn []
                    (if (.get ^AtomicBoolean (::closed? session))
                      (notify-connection-close! workers close-connection! session)
                      (key-interests! session SelectionKey/OP_READ 0))))))
             (catch Throwable _
               (enqueue-selector!
                selector commands
                #(close-session! connections workers close-connection! session))))))
        (catch RejectedExecutionException _
          (close-session! connections workers close-connection! session))))))

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

(defn- process-selected!
  [^ServerSocketChannel server ^Selector selector commands connections workers
   close-connection! shutting-down? open-connection! handler]
  (let [selected (.selectedKeys selector)
        iterator (.iterator selected)]
    (while (.hasNext iterator)
      (let [^SelectionKey key (.next iterator)]
        (.remove iterator)
        (when (.isValid key)
          (try
            (if (.isAcceptable key)
              (accept-session! server selector commands connections workers
                               close-connection! shutting-down? open-connection!)
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
  [{::keys [socket-path handler open-connection! close-connection!]}]
  (try (.delete (java.io.File. ^String socket-path)) (catch Throwable _))
  (let [^UnixDomainSocketAddress address
        (UnixDomainSocketAddress/of ^String socket-path)
        ^ServerSocketChannel server
        (ServerSocketChannel/open StandardProtocolFamily/UNIX)
        selector (Selector/open)
        commands (ConcurrentLinkedQueue.)
        workers (codec-workers)
        connections (atom {})
        closed? (AtomicBoolean. false)
        shutting-down? (atom false)]
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
                                      open-connection! handler)
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
       ::shutting-down? shutting-down?
       ::close-connection! close-connection!
       ::closed? closed?})))

(defn close-request-server!
  "Stop admission, drain admitted responses, and close every connection."
  {:malli/schema [:=> [:catn [::request-server ::request-server]]
                  ::nil-result]}
  [request-server]
  (let [closed? ^AtomicBoolean (::closed? request-server)
        selector ^Selector (::selector request-server)
        selector-worker ^Thread (::worker request-server)
        workers ^ThreadPoolExecutor (::workers request-server)]
    (when (.compareAndSet closed? false true)
      (enqueue-selector!
       selector (::commands request-server)
       #(begin-shutdown!
         (::channel request-server)
         (::connections request-server)
         workers
         (::close-connection! request-server)
         (::shutting-down? request-server))))
    (.join selector-worker)
    (.close selector)
    (.shutdown workers)
    (.awaitTermination workers 30 TimeUnit/SECONDS))
  nil)

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
