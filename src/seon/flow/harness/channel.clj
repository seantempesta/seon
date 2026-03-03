(ns seon.flow.harness.channel
  "Bidirectional TCP <-> core.async channel adapter.

   Length-prefixed EDN over TCP. Each message is:
   - 4-byte big-endian length prefix
   - UTF-8 encoded EDN bytes

   Two entry points:
   - `start-server!` - listen on a port, accept one client
   - `connect!` - connect to a server

   Both return {::in-ch ::out-ch ::close!} for bidirectional communication."
  (:require [clojure.core.async :as a]
            [seon.flow.msg :as msg]
            [seon.schema :as schema])
  (:import [java.io DataInputStream DataOutputStream EOFException]
           [java.net ServerSocket Socket InetAddress]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::port [:int {:min 0 :max 65535
                                :description "TCP port (0 for random assignment)"}])
(schema/register! ::host [:string {:min 1 :description "Hostname to connect to"}])

(defn- read-message!
  "Read one length-prefixed EDN message from input stream.
   Returns parsed EDN or nil on EOF/error."
  [^DataInputStream dis]
  (let [len (.readInt dis)
        buf (byte-array len)]
    (.readFully dis buf)
    (msg/read-edn (String. buf "UTF-8"))))

(defn- write-message!
  "Write one length-prefixed EDN message to output stream."
  [^DataOutputStream dos msg]
  (let [^bytes bs (.getBytes (pr-str msg) "UTF-8")]
    (.writeInt dos (alength bs))
    (.write dos bs)
    (.flush dos)))

(defn- start-reader-thread!
  "Read messages from socket and put onto channel. Closes channel on EOF."
  [^Socket socket ch label]
  (let [dis (DataInputStream. (.getInputStream socket))]
    (doto (Thread.
           (fn []
             (try
               (loop []
                 (when-not (.isClosed socket)
                   (let [msg (read-message! dis)]
                     (when (a/>!! ch msg)
                       (recur)))))
               (catch EOFException _)
               (catch java.io.IOException _)
               (finally
                 (a/close! ch))))
           (str "channel-reader-" label))
      (.setDaemon true)
      (.start))))

(defn- start-writer-thread!
  "Take messages from channel and write to socket. Stops when channel closes."
  [^Socket socket ch label]
  (let [dos (DataOutputStream. (.getOutputStream socket))]
    (doto (Thread.
           (fn []
             (try
               (loop []
                 (when-let [msg (a/<!! ch)]
                   (write-message! dos msg)
                   (recur)))
               (catch java.io.IOException _)
               (catch Exception _)))
           (str "channel-writer-" label))
      (.setDaemon true)
      (.start))))

(defn- wire-socket!
  "Wire a connected socket to in/out channels. Returns {::in-ch ::out-ch ::close!}."
  [^Socket socket label]
  (let [in-ch  (a/chan 32)
        out-ch (a/chan 32)]
    (start-reader-thread! socket in-ch label)
    (start-writer-thread! socket out-ch label)
    {::in-ch  in-ch
     ::out-ch out-ch
     ::close! (fn []
                (a/close! out-ch)
                (a/close! in-ch)
                (when-not (.isClosed socket)
                  (.close socket)))}))

(defn start-server!
  "Start TCP server. Returns immediately, accepts one client in background.

   Channels become usable once a client connects. Messages sent to ::out-ch
   before a client connects will buffer (up to 32).

   Request keys:
     ::port - Port to listen on (0 for random)

   Returns map with:
     ::server  - ServerSocket instance
     ::port    - Actual port (useful when 0 was requested)
     ::in-ch   - Channel of messages received from client
     ::out-ch  - Channel to send messages to client
     ::close!  - No-arg fn that cleans up everything

   Note: No :malli/schema - returns channels and sockets (runtime objects)."
  [{::keys [port]}]
  (let [port    (or port 0)
        ss      (ServerSocket. (int port) 1 (InetAddress/getLoopbackAddress))
        actual  (.getLocalPort ss)
        in-ch   (a/chan 32)
        out-ch  (a/chan 32)
        closed? (atom false)]
    ;; Accept thread: wait for client, then wire reader/writer
    (doto (Thread.
           (fn []
             (try
               (let [client (.accept ss)]
                 (when-not @closed?
                   (start-reader-thread! client in-ch (str "server-" actual))
                   (start-writer-thread! client out-ch (str "server-" actual))))
               (catch java.io.IOException _
                 (a/close! in-ch))))
           (str "channel-accept-" actual))
      (.setDaemon true)
      (.start))
    {::server ss
     ::port   actual
     ::in-ch  in-ch
     ::out-ch out-ch
     ::close! (fn []
                (reset! closed? true)
                (a/close! out-ch)
                (a/close! in-ch)
                (when-not (.isClosed ss)
                  (.close ss)))}))

(defn connect!
  "Connect to TCP server.

   Request keys:
     ::host - Hostname (default \"localhost\")
     ::port - Port to connect to

   Returns map with:
     ::in-ch  - Channel of messages received from server
     ::out-ch - Channel to send messages to server
     ::close! - No-arg fn that cleans up everything"
  [{::keys [host port]}]
  (let [host   (or host "localhost")
        socket (Socket. ^String host (int port))]
    (wire-socket! socket (str "client-" port))))
