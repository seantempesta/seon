(ns seon.server.broadcast
  "Pub fanout, per-DB routed. The writer's `d/listen!` `::raw-broadcast`
   callback calls `(broadcast! event)` after every committed transact. Each
   event carries its committing conn's real `\"db-name\"` (no more hardcoded
   \"default\").

   Two delivery channels, both fed by one `broadcast!`:

   - **Socket subscribers** (via `start-pub-server!`): every connected
     OutputStream receives EVERY event — the single tagged stream the Rust
     host demuxes by `\"db-name\"` (design §7). Stays db-agnostic so the
     existing in-process test fixture (`start-pub-collector!`) sees all events.
   - **In-process per-DB subscribers** (via `subscribe!`): keyed by db-name.
     A subscriber registered for cluster A is invoked ONLY for events whose
     `\"db-name\"` is A — zero cross-bleed. This is the reactive engine's /
     in-JVM consumer's routing path.

   Multi-threaded: the socket accept loop runs on its own thread; `broadcast!`
   is called from the writer thread inside the `::raw-broadcast` listener."
  (:require [seon.server.codec :as codec])
  (:import [java.net StandardProtocolFamily UnixDomainSocketAddress]
           [java.nio.channels ServerSocketChannel SocketChannel Channels]
           [java.io OutputStream]))

(set! *warn-on-reflection* true)

;; Socket subscribers: a flat set of OutputStreams. Each gets every event;
;; the host demuxes the single tagged stream by db-name.
(defonce ^:private socket-subscribers (atom #{}))

;; In-process per-DB subscribers: {db-name -> {sub-id -> (fn [event])}}.
;; Routed: a subscriber only fires for its own db-name's events.
(defonce ^:private db-subscribers (atom {}))
(defonce ^:private sub-counter (atom 0))

;; ---------- In-process per-DB subscription API ----------

(defn subscribe!
  "Register an in-process subscriber for ONE db-name. `f` is invoked with the
   event map on every `broadcast!` whose event `\"db-name\"` equals `db-name`.
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
   subscriber registered for the event's `\"db-name\"`. Dead socket subscribers
   (write failure) are dropped. The event's `\"db-name\"` drives the per-DB
   routing; socket delivery is db-agnostic (host demuxes)."
  [event]
  ;; (a) socket subscribers — every one gets every (tagged) event.
  (let [snap @socket-subscribers
        dead (volatile! [])]
    (doseq [^OutputStream out snap]
      (try
        (codec/write-frame! out event)
        (catch Throwable _
          (vswap! dead conj out))))
    (when (seq @dead)
      (swap! socket-subscribers #(reduce disj % @dead))))
  ;; (b) in-process per-DB subscribers — only those keyed by this event's
  ;; db-name. A nil/absent db-name routes to no per-DB subscriber.
  (when-let [db-name (get event "db-name")]
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
                           (swap! socket-subscribers conj out))
                         (recur))
                       (catch java.nio.channels.AsynchronousCloseException _ nil)
                       (catch Throwable t
                         (binding [*out* *err*]
                           (println "[pub] accept loop died:" (.getMessage t))))))
                   "wire-pub-accept")
      (.setDaemon true)
      (.start))
    server))
