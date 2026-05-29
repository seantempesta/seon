(ns seon.server.broadcast
  "Pub-socket fanout. The writer thread calls (broadcast! event) after every
   committed transact. We hold a set of OutputStreams for connected subscribers;
   on write failure we drop the subscriber.

   Multi-threaded: subscriber accept loop runs on its own thread; broadcast! is
   called from the writer-thread."
  (:require [seon.server.codec :as codec])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel SocketChannel Channels]
           [java.io OutputStream]))

(set! *warn-on-reflection* true)

(defonce ^:private subscribers (atom #{}))

(defn broadcast! [event]
  (let [snap @subscribers
        dead (volatile! [])]
    (doseq [^OutputStream out snap]
      (try
        (codec/write-frame! out event)
        (catch Throwable _
          (vswap! dead conj out))))
    (when (seq @dead)
      (swap! subscribers #(reduce disj % @dead)))))

(defn start-pub-server!
  "Bind path and accept subscribers forever. Returns the ServerSocketChannel
   so the caller can close it."
  [^String path]
  (let [_ (try (.. (java.io.File. path) delete) (catch Throwable _))
        addr (UnixDomainSocketAddress/of path)
        server (ServerSocketChannel/open StandardProtocolFamily/UNIX)]
    (.bind server addr)
    (doto (Thread. ^Runnable
                   (fn []
                     (try
                       (loop []
                         (let [^SocketChannel ch (.accept server)
                               out (Channels/newOutputStream ch)]
                           (swap! subscribers conj out))
                         (recur))
                       (catch java.nio.channels.AsynchronousCloseException _ nil)
                       (catch Throwable t
                         (binding [*out* *err*]
                           (println "[pub] accept loop died:" (.getMessage t))))))
                   "wire-pub-accept")
      (.setDaemon true)
      (.start))
    server))
