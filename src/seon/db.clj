(ns seon.db
  "Seon's database API. Drop-in replacement for datalevin.core.

   Agents use this instead of d/transact! directly.
   Reads pass through directly. Writes go through d/transact! directly
   for zero overhead, but each connection gets a lazily-created writer
   flow for coordination (pause/resume for backups, metrics via ping).

   Two API levels:
   - Raw pass-through: `q`, `pull`, `pull-many`, `entity` — take a db value
   - Named convenience: `query`, `pull-by-name`, `pull-many-by-name` — take
     a db-name keyword (`:seon`, `:seon.runtime`, or namespace string),
     resolve connection via conn manager, handle staleness with retry

   Positional args are intentional for drop-in compatibility — this is
   the one namespace where map-in/map-out does not apply."
  (:require [clojure.core.async.flow :as flow]
            [datalevin.core :as d]
            [malli.core :as m]
            [seon.db.datalevin.conn :as conn]
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

(defn- conn-closed?
  "Check if a Datalevin connection is closed. Returns true if closed or nil."
  [conn]
  (try
    (d/closed? conn)
    (catch Throwable _
      true)))

(defn- ensure-writer!
  "Lazily create a writer flow for conn if one doesn't exist yet.
   The writer flow provides pause/resume coordination and metrics.
   Normal writes bypass the flow channel for zero overhead.
   Refuses to create a flow for closed connections."
  [conn]
  (when (conn-closed? conn)
    (throw (ex-info "Cannot create writer for closed connection"
                    {:conn-id (conn-id conn)})))
  (let [id (conn-id conn)]
    (when-not (get @writers id)
      (let [fl (writer/create-writer-flow {::writer/conn conn
                                           ::writer/db-name (str "conn-" id)})]
        (swap! writers assoc id {:flow fl :conn conn})
        (log/info "Created writer flow" {:conn-id id})))
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

;;; --- Named Convenience API ---
;;;
;;; These functions resolve a db-name keyword to a connection via the
;;; conn manager, deref it to get a db value, and delegate to datalevin.
;;; On connection error, they reconnect and retry once.

(def ^:dynamic *conn-manager*
  "Dynamic var for overriding the connection manager in tests.
   When nil (default), resolves from Integrant system."
  nil)

(defn- get-conn-manager
  "Get the connection manager from *conn-manager* or the running Integrant system.
   Throws if neither is available."
  []
  (or *conn-manager*
      (if-let [sys-var (try @(requiring-resolve 'integrant.repl.state/system)
                            (catch Exception _ nil))]
        (or (:seon.db.datalevin/connections sys-var)
            (throw (ex-info "Connection manager not available — is the system running?"
                            {:component :seon.db.datalevin/connections})))
        (throw (ex-info "Integrant system not running" {})))))

(defn- db-name->conn-args
  "Convert a db-name keyword to [get-fn reconnect-namespace].
   get-fn takes a conn manager and returns a connection atom."
  [db-name]
  (case db-name
    :seon [#(conn/get-master-conn! {::conn/manager %})
           :seon.db.datalevin.conn/master]
    :seon.runtime [#(conn/get-runtime-conn! {::conn/manager %})
                   :seon.db.datalevin.conn/runtime]
    ;; Anything else is a namespace db name
    [(fn [mgr] (conn/get-namespace-conn! {::conn/manager mgr
                                           ::conn/namespace (name db-name)}))
     (name db-name)]))

(defn- resolve-db
  "Resolve a db-name keyword to a Datalevin db value.
   Gets connection from conn manager and derefs it."
  [db-name]
  (let [mgr (get-conn-manager)
        [get-fn _] (db-name->conn-args db-name)]
    @(get-fn mgr)))

(defn- connection-error?
  "Check if an exception indicates a connection/server error."
  [^Throwable e]
  (let [msg (str (.getMessage e) " " (some-> (.getCause e) (.getMessage)))]
    (boolean
     (or (re-find #"(?i)connection refused" msg)
         (re-find #"(?i)connection reset" msg)
         (re-find #"(?i)broken pipe" msg)
         (re-find #"(?i)closed" msg)
         (re-find #"(?i)not connected" msg)
         (re-find #"(?i)timeout" msg)
         (instance? java.net.ConnectException e)
         (instance? java.net.ConnectException (.getCause e))))))

(defn- with-retry
  "Execute f with a db resolved from db-name. On connection error,
   reconnect and retry once."
  [db-name f]
  (try
    (f (resolve-db db-name))
    (catch Exception e
      (if (connection-error? e)
        (do
          (log/warn "Connection error on" db-name "- reconnecting and retrying"
                    {:error (.getMessage e)})
          (let [mgr (get-conn-manager)
                [_ reconnect-ns] (db-name->conn-args db-name)]
            (conn/reconnect! {::conn/manager mgr ::conn/namespace reconnect-ns})
            (f (resolve-db db-name))))
        (throw e)))))

(defn query
  "Query a named database. Resolves connection by db-name, retries on staleness.

   db-name — :seon, :seon.runtime, or a namespace keyword like :seon.trading
   datalog-query — Datalog query (same as d/q first arg)
   inputs — additional query inputs (sources, rules, etc.)

   Example:
     (query :seon.runtime '[:find ?e ?n :where [?e :seon.fn/name ?n]])
     (query :seon '[:find ?e :where [?e :name \"test\"]])"
  {:malli/schema [:=> [:cat :keyword :any [:* :any]] :any]}
  [db-name datalog-query & inputs]
  (with-retry db-name
    (fn [db] (apply d/q datalog-query db inputs))))

(defn pull-by-name
  "Pull an entity from a named database by selector and eid.

   db-name — :seon, :seon.runtime, or a namespace keyword
   selector — pull pattern (e.g., '[*] or '[:name :age])
   eid — entity id or lookup ref

   Example:
     (pull-by-name :seon.runtime '[*] 1)"
  {:malli/schema [:=> [:cat :keyword :any :any] :any]}
  [db-name selector eid]
  (with-retry db-name
    (fn [db] (d/pull db selector eid))))

(defn pull-many-by-name
  "Pull multiple entities from a named database.

   db-name — :seon, :seon.runtime, or a namespace keyword
   selector — pull pattern
   eids — collection of entity ids or lookup refs

   Example:
     (pull-many-by-name :seon.runtime '[:seon.fn/name] [1 2 3])"
  {:malli/schema [:=> [:cat :keyword :any [:sequential :any]] :any]}
  [db-name selector eids]
  (with-retry db-name
    (fn [db] (d/pull-many db selector eids))))

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

(defn remove-writer!
  "Stop and remove the writer flow for a specific connection.
   Use when closing a connection that has a writer flow, to prevent
   orphaned flows from crashing on later shutdown.
   Safe to call even if no writer exists for conn."
  [conn]
  (let [id (conn-id conn)]
    (when-let [{:keys [flow]} (get @writers id)]
      (try
        (flow/pause flow)
        (flow/ping flow 5000)
        (flow/stop flow)
        (log/debug "Removed writer flow" {:conn-id id})
        (catch Throwable e
          (log/warn e "Error removing writer flow" {:conn-id id})))
      (swap! writers dissoc id))))

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
