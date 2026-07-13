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
           [java.nio.channels Channels ServerSocketChannel SocketChannel]
           [java.util.concurrent ArrayBlockingQueue BlockingQueue]))

(set! *warn-on-reflection* true)

(schema/register! ::socket-path [:string {:min 1}])
(schema/register! ::message :map)
(schema/register! ::handler [:fn fn?])
(schema/register! ::channel [:fn some?])
(schema/register! ::output-stream [:fn some?])
(schema/register! ::subscribers [:fn some?])
(schema/register! ::connections [:fn some?])
(schema/register! ::closed? [:fn some?])
(schema/register! ::queue [:fn some?])
(schema/register! ::worker [:fn some?])
(schema/register! ::nil-result [:fn nil?])
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
  [::closed? ::closed?]])
(schema/register!
 ::request-server-input
 [:map [::socket-path ::socket-path] [::handler ::handler]])
(schema/register!
 ::call-input
 [:map [::channel ::channel] [::message ::message]])
(schema/register!
 ::publish-input
 [:map [::publisher ::publisher] [::message ::message]])

(def ^:private maximum-frame-bytes (* 16 1024 1024))
(def ^:private subscriber-queue-capacity 16)

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
  {:malli/schema [:=> [:cat ::call-input] ::message]}
  [{::keys [channel message]}]
  (let [input (Channels/newInputStream ^SocketChannel channel)
        output (Channels/newOutputStream ^SocketChannel channel)]
    (write-frame! output message)
    (read-frame input)))

(defn start-request-server!
  "Start a concurrent request server whose handler maps request to response."
  {:malli/schema [:=> [:cat ::request-server-input] ::request-server]}
  [{::keys [socket-path handler]}]
  (try (.delete (java.io.File. ^String socket-path)) (catch Throwable _))
  (let [^UnixDomainSocketAddress address
        (UnixDomainSocketAddress/of ^String socket-path)
        ^ServerSocketChannel server
        (ServerSocketChannel/open StandardProtocolFamily/UNIX)
        connections (atom #{})
        closed? (atom false)]
    (.bind server address)
    (doto
      (Thread.
       ^Runnable
       (fn []
         (try
           (loop []
             (let [channel (.accept server)
                   input (Channels/newInputStream channel)
                   output (Channels/newOutputStream channel)]
               (locking connections
                 (if @closed?
                   (try (.close ^SocketChannel channel) (catch Throwable _))
                   (do
                     (swap! connections conj channel)
                     (doto
                       (Thread.
                        ^Runnable
                        (fn []
                          (try
                            (loop []
                              (when-let [request (read-frame input)]
                                (write-frame! output (handler request))
                                (recur)))
                            (catch Throwable throwable
                              (when-not @closed?
                                (binding [*out* *err*]
                                  (println
                                   "[database-request] connection closed:"
                                   (.getMessage throwable)))))
                            (finally
                              (swap! connections disj channel)
                              (try (.close ^SocketChannel channel)
                                   (catch Throwable _)))))
                        "database-request-connection")
                       (.setDaemon true)
                       (.start))))))
             (recur))
           (catch java.nio.channels.AsynchronousCloseException _ nil)
           (catch Throwable throwable
             (binding [*out* *err*]
               (println "[database-request] accept loop stopped:"
                        (.getMessage throwable))))))
       "database-request-accept")
      (.setDaemon true)
      (.start))
    {::channel server ::connections connections ::closed? closed?}))

(defn close-request-server!
  "Close one request-server socket."
  {:malli/schema [:=> [:catn [::request-server ::request-server]]
                  ::nil-result]}
  [request-server]
  (let [connections (::connections request-server)
        closed? (::closed? request-server)]
    (locking connections
      (reset! closed? true)
      (try (.close ^ServerSocketChannel (::channel request-server))
           (catch Throwable _))
      (doseq [connection @connections]
        (try (.close ^SocketChannel connection) (catch Throwable _)))
      (reset! connections #{})))
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
             (catch java.nio.channels.AsynchronousCloseException _ nil)
             (catch Throwable throwable
               (when-not @closed?
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
           (catch java.nio.channels.AsynchronousCloseException _ nil)
           (catch Throwable throwable
             (binding [*out* *err*]
               (println "[database-publish] accept loop stopped:"
                        (.getMessage throwable))))))
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
