(ns seon.db
  "Seon's database API. Drop-in replacement for datalevin.core.

   Agents use this instead of d/transact! directly.
   Reads pass through directly. Writes go through d/transact! directly
   for zero overhead, but each connection gets a lazily-created writer
   flow for coordination (pause/resume for backups, metrics via ping).

   API mirrors datalevin.core signatures exactly so agents
   don't need to learn new APIs. Positional args are intentional
   for drop-in compatibility — this is the one namespace where
   map-in/map-out does not apply."
  (:require [clojure.core.async.flow :as flow]
            [datalevin.core :as d]
            [malli.core :as m]
            [seon.db.datalevin.writer :as writer]
            [taoensso.timbre :as log])
  (:import [java.time Instant]))

;;; --- Schemas ---

(def WriteStats
  "Schema for the write statistics map."
  (m/schema
   [:map
    [:total-writes :int]
    [:last-write-at [:maybe :any]]
    [:by-caller [:map-of [:maybe :string] :int]]]))

;;; --- State ---

(defonce ^:private write-stats
  (atom {:total-writes 0
         :last-write-at nil
         :by-caller {}}))

;; Map of conn identity -> {:flow flow-obj :conn conn}
(defonce ^:private writers
  (atom {}))

;;; --- Internal ---

(defn- caller-ns
  "Best-effort extraction of the calling namespace from the stack."
  []
  (let [frames (.getStackTrace (Thread/currentThread))]
    (->> frames
         (map #(.getClassName ^StackTraceElement %))
         (filter #(and (.startsWith ^String % "seon.")
                       (not (.startsWith ^String % "seon.db"))))
         first)))

(defn- conn-id
  "Stable identity key for a Datalevin connection."
  [conn]
  (System/identityHashCode conn))

(defn- ensure-writer!
  "Lazily create a writer flow for conn if one doesn't exist yet.
   The writer flow provides pause/resume coordination and metrics.
   Normal writes bypass the flow channel for zero overhead."
  [conn]
  (let [id (conn-id conn)]
    (when-not (get @writers id)
      (let [fl (writer/create-writer-flow {::writer/conn conn
                                           ::writer/db-name (str "conn-" id)})]
        (swap! writers assoc id {:flow fl :conn conn})
        (log/debug "Created writer flow for conn" id)))
    nil))

(defn- track-write!
  "Update write stats after a successful transact."
  [caller tx-count]
  (swap! write-stats (fn [s]
                       (-> s
                           (update :total-writes inc)
                           (assoc :last-write-at (Instant/now))
                           (update-in [:by-caller caller] (fnil inc 0)))))
  (log/trace "transact!" {:caller caller :tx-count tx-count}))

;;; --- Public API (positional, mirrors datalevin.core) ---

(def ^:private default-timeout-ms
  "Default transaction timeout in milliseconds.
   Generous — protects against deadlocks, not slow queries."
  10000)

(defn transact!
  "Transact data into conn. Tracks caller and write count for observability.
   Lazily creates a writer flow for backup coordination.
   Wraps d/transact! in a future with timeout to prevent permanent deadlocks
   from abandoned write transactions (e.g. MCP eval timeout while holding lock)."
  {:malli/schema [:=> [:cat :any [:sequential :any]] :any]}
  ([conn tx-data]
   (transact! conn tx-data nil))
  ([conn tx-data tx-meta]
   (ensure-writer! conn)
   (let [caller (caller-ns)
         fut (future
               (if tx-meta
                 (d/transact! conn tx-data tx-meta)
                 (d/transact! conn tx-data)))
         result (deref fut default-timeout-ms ::timeout)]
     (when (= result ::timeout)
       (future-cancel fut)
       (log/error "Datalevin transaction timed out — possible deadlock"
                  {:caller caller :tx-count (count tx-data)
                   :timeout-ms default-timeout-ms})
       (throw (ex-info "Datalevin transaction timed out"
                       {:timeout-ms default-timeout-ms
                        :tx-count (count tx-data)
                        :caller caller})))
     (track-write! caller (count tx-data))
     result)))

(defn q
  "Query the database. Pass-through to datalevin.core/q."
  {:malli/schema [:=> [:cat :any [:* :any]] :any]}
  [query & inputs]
  (apply d/q query inputs))

(defn pull
  "Pull an entity by selector and eid. Pass-through to datalevin.core/pull."
  {:malli/schema [:=> [:cat :any :any :any] :any]}
  [db selector eid]
  (d/pull db selector eid))

(defn pull-many
  "Pull multiple entities. Pass-through to datalevin.core/pull-many."
  {:malli/schema [:=> [:cat :any :any [:sequential :any]] :any]}
  [db selector eids]
  (d/pull-many db selector eids))

(defn entity
  "Get an entity by eid. Pass-through to datalevin.core/entity."
  {:malli/schema [:=> [:cat :any :any] :any]}
  [db eid]
  (d/entity db eid))

;;; --- Writer Flow Coordination ---

(defn all-conns
  "Returns all connections that have active writer flows."
  []
  (mapv :conn (vals @writers)))

(defn pause-writes!
  "Pause the writer flow for conn, triggering d/sync via the transition hook.
   Blocks until the pause transition completes (flush is done).
   Use before backups to ensure all writes are flushed."
  [conn]
  (when-let [{:keys [flow]} (get @writers (conn-id conn))]
    (flow/pause flow)
    (flow/ping flow 5000)
    (log/info "Paused writer for conn" (conn-id conn))))

(defn resume-writes!
  "Resume the writer flow for conn after backup completes."
  [conn]
  (when-let [{:keys [flow]} (get @writers (conn-id conn))]
    (flow/resume flow)
    (log/info "Resumed writer for conn" (conn-id conn))))

(defn shutdown-writers!
  "Stop all writer flows safely. Uses pause -> ping -> stop to ensure
   the transition hook (flush) completes before stopping the flow.
   Call on system shutdown before closing Datalevin connections."
  []
  (doseq [[id {:keys [flow]}] @writers]
    (try
      (flow/pause flow)
      (flow/ping flow 5000)
      (flow/stop flow)
      (log/debug "Stopped writer flow" id)
      (catch Throwable e
        (log/warn e "Error stopping writer flow" id))))
  (reset! writers {})
  (log/info "All writer flows stopped"))

(defn writer-status
  "Returns status of writer flows. Nil values mean no writer for that conn."
  [conn]
  (when-let [{:keys [flow]} (get @writers (conn-id conn))]
    (flow/ping flow)))

(defn stats
  "Returns write statistics: total writes, last write time, writes by caller namespace."
  {:malli/schema [:=> [:cat] WriteStats]}
  []
  @write-stats)
