(ns seon.server.broadcast
  "Unix-socket transaction fanout from the writer to every reader.

   The writer's `::raw-broadcast` Datahike listener calls `broadcast!` after
   each committed transaction. Every connected output stream receives the
   event; readers demultiplex the tagged stream by
   `:seon.store.wire/db-name`.

   The accept loop has its own thread. Datahike listeners may call
   `broadcast!` concurrently, so each subscriber's framed writes are locked."
  (:require [seon.server.codec :as codec])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel SocketChannel Channels]
           [java.io OutputStream]))

(set! *warn-on-reflection* true)

;; Socket subscribers: a flat set of `{::ch <SocketChannel> ::out <OutputStream>}`
;; entries. Each gets every event; the host demuxes the single tagged stream by
;; db-name. The CHANNEL is retained alongside the stream so a dropped (dead)
;; subscriber's underlying FD can be explicitly `.close`d — dropping only the
;; OutputStream reference leaks the SocketChannel FD until GC finalizes it,
;; which on a long-lived low-churn writer JVM may be never (measured: +1 FD per
;; ephemeral-cluster create/destroy cycle, the pub subscription never reclaimed).
(defonce ^:private socket-subscribers (atom #{}))

(defn- close-subscriber!
  "Close a dropped subscriber's SocketChannel (its FD). Idempotent + never
   throws — a broadcast write already failed on this peer, so the close is
   best-effort reclamation, not correctness."
  [{::keys [^SocketChannel ch]}]
  (when ch (try (.close ch) (catch Throwable _))))

(defn broadcast!
  "Fan one event out to every socket subscriber.

   A write failure drops and closes that subscriber. Socket delivery is
   db-agnostic; the consumer demultiplexes each event by its db-name."
  [event]
  (let [snap @socket-subscribers
        dead (volatile! [])]
    (doseq [{::keys [^OutputStream out] :as sub} snap]
      (try
        ;; Datahike may invoke listeners concurrently on writer threads.
        ;; A frame is two writes (length + Transit payload); without a
        ;; per-subscriber lock, concurrent broadcasts interleave those bytes
        ;; and the Node subscriber reads a valid length followed by fragments
        ;; of two payloads. That creates a reconnect/replay loop which pegs the
        ;; pod CPU and repeatedly re-renders every open feed.
        (locking out
          (codec/write-frame! out event))
        (catch Throwable _
          (vswap! dead conj sub))))
    (when (seq @dead)
      ;; Drop dead subscribers from the set AND close their channels — a bare
      ;; disj would leak the SocketChannel FD (see socket-subscribers docstring).
      (swap! socket-subscribers #(reduce disj % @dead))
      (run! close-subscriber! @dead)))
  nil)

(defn start-pub-server!
  "Bind path and accept socket subscribers forever. Each connected subscriber
   joins the flat socket set and receives every event. Returns the
   ServerSocketChannel so the caller can close it."
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
                           (swap! socket-subscribers conj {::ch ch ::out out}))
                         (recur))
                       (catch java.nio.channels.AsynchronousCloseException _ nil)
                       (catch Throwable t
                         (binding [*out* *err*]
                           (println "[pub] accept loop died:" (.getMessage t))))))
                   "wire-pub-accept")
      (.setDaemon true)
      (.start))
    server))
