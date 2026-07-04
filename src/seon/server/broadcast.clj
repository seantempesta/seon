(ns seon.server.broadcast
  "Pub fanout, per-DB routed. The writer's `d/listen!` `::raw-broadcast`
   callback calls `(broadcast! event)` after every committed transact. Each
   event carries its committing conn's real `:seon.store.wire/db-name` (no
   more hardcoded \"default\").

   Two delivery channels, both fed by one `broadcast!`:

   - **Socket subscribers** (via `start-pub-server!`): every connected
     OutputStream receives EVERY event — a single tagged stream a consumer
     demuxes by `:seon.store.wire/db-name`. Stays db-agnostic so the existing
     in-process test fixture (`start-pub-collector!`) sees all events.
   - **In-process per-DB subscribers** (via `subscribe!`): keyed by db-name.
     A subscriber registered for cluster A is invoked ONLY for events whose
     `:seon.store.wire/db-name` is A — zero cross-bleed. This is the reactive
     engine's / in-JVM consumer's routing path.

   Multi-threaded: the socket accept loop runs on its own thread; `broadcast!`
   is called from the writer thread inside the `::raw-broadcast` listener."
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

;; In-process per-DB subscribers: {db-name -> {sub-id -> (fn [event])}}.
;; Routed: a subscriber only fires for its own db-name's events.
(defonce ^:private db-subscribers (atom {}))
(defonce ^:private sub-counter (atom 0))

;; ---------- In-process per-DB subscription API ----------

(defn subscribe!
  "Register an in-process subscriber for ONE db-name. `f` is invoked with the
   event map on every `broadcast!` whose event `:seon.store.wire/db-name`
   equals `db-name`.
   Returns an opaque sub-id for `unsubscribe!`. A subscriber to cluster A never
   sees cluster B's events."
  [db-name f]
  (let [id (swap! sub-counter inc)]
    (swap! db-subscribers assoc-in [db-name id] f)
    id))

(defn unsubscribe!
  "Drop the per-DB subscriber registered under `db-name`/`sub-id`. Idempotent."
  [db-name sub-id]
  (swap! db-subscribers update db-name dissoc sub-id)
  nil)

(defn ^:no-doc db-subscriber-count
  "Test seam: number of in-process subscribers registered for `db-name`."
  [db-name]
  (count (get @db-subscribers db-name)))

(defn ^:no-doc reset-subscribers!
  "Test seam: drop all in-process per-DB subscribers (not the socket set)."
  []
  (reset! db-subscribers {}))

;; ---------- Fanout ----------

(defn broadcast!
  "Fan one event out to (a) every socket subscriber and (b) every in-process
   subscriber registered for the event's `:seon.store.wire/db-name`. Dead
   socket subscribers (write failure) are dropped. The event's
   `:seon.store.wire/db-name` drives the per-DB routing; socket delivery is
   db-agnostic (the consumer demuxes)."
  [event]
  ;; (a) socket subscribers — every one gets every (tagged) event.
  (let [snap @socket-subscribers
        dead (volatile! [])]
    (doseq [{::keys [^OutputStream out] :as sub} snap]
      (try
        (codec/write-frame! out event)
        (catch Throwable _
          (vswap! dead conj sub))))
    (when (seq @dead)
      ;; Drop dead subscribers from the set AND close their channels — a bare
      ;; disj would leak the SocketChannel FD (see socket-subscribers docstring).
      (swap! socket-subscribers #(reduce disj % @dead))
      (run! close-subscriber! @dead)))
  ;; (b) in-process per-DB subscribers — only those keyed by this event's
  ;; db-name. A nil/absent db-name routes to no per-DB subscriber.
  (when-let [db-name (:seon.store.wire/db-name event)]
    (doseq [[_ f] (get @db-subscribers db-name)]
      (try (f event) (catch Throwable _))))
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
