(ns seon.db.datalevin.conn
  "Connection manager for Datalevin server.

   Manages client connections to the Datalevin server, providing:
   - Connection caching per namespace
   - Lazy database creation on first connection
   - TTL-based cleanup of idle connections
   - Schema application on connect

   ## Configuration

   ```clojure
   {:seon.db.datalevin/connections
    {:server #ig/ref :seon.db.datalevin/server
     :ttl-ms 300000  ; Connection TTL (5 minutes default)
     :cleanup-interval-ms 60000}}  ; Cleanup check interval (1 minute)
   ```

   ## Usage

   ```clojure
   (require '[seon.db.datalevin.conn :as conn])

   ;; Get any database connection by keyword identity
   (conn/get-conn! {::conn/manager mgr ::conn/db :seon.ai})
   (conn/get-conn! {::conn/manager mgr ::conn/db :seon.runtime ::conn/schema merged-schema})
   (conn/get-conn! {::conn/manager mgr ::conn/db :seon.trading})

   ;; Close a connection
   (conn/close-conn! {::conn/manager mgr ::conn/db :seon.trading})
   ```

   ## Database Tiers

   | Keyword       | Contents                        |
   |---------------|---------------------------------|
   | :seon.ai      | AI sessions + messages          |
   | :seon.flow    | Flow traces + snapshots         |
   | :seon.runtime | Code graph + instance registry  |
   | :seon.{ns}    | Per-namespace agent context      |

   Database names are converted to kebab-case by Datalevin server."
  (:require [integrant.core :as ig]
            [seon.schema :as schema]
            [taoensso.timbre :as log])
  (:import [java.time Instant]
           [java.util.concurrent ScheduledExecutorService Executors TimeUnit]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::port
                  [:int {:min 1 :max 65535 :description "Server port"}])

(schema/register! ::host
                  [:string {:description "Server hostname"}])

(schema/register! ::username
                  [:string {:min 1 :description "Server username"}])

(schema/register! ::password
                  [:string {:min 1 :description "Server password"}])

(schema/register! ::ttl-ms
                  [:int {:min 0 :description "Connection TTL in milliseconds"}])

(schema/register! ::cleanup-interval-ms
                  [:int {:min 1000 :description "Cleanup interval in milliseconds"}])

(schema/register! ::connection
                  [:any {:description "Datalevin connection (atom wrapping DB)"}])

(schema/register! ::manager
                  [:map {:description "Connection manager state"}
                   [::port ::port]
                   [::host {:optional true} ::host]
                   [::username {:optional true} ::username]
                   [::password {:optional true} ::password]
                   [::ttl-ms ::ttl-ms]
                   [::connections [:any {:description "Atom of namespace->connection-entry"}]]
                   [::scheduler {:optional true} [:any {:description "ScheduledExecutorService"}]]])

;; Connection entry stored in the cache
(schema/register! ::connection-entry
                  [:map
                   [::connection ::connection]
                   [::last-accessed [:any {:description "Instant of last access"}]]])

;; Request/Response schemas for public API
;; Note: These functions involve runtime resources (connections, atoms) that
;; cannot be generated for property testing. We define schemas for documentation
;; but omit :malli/schema metadata since the manager contains non-generatable atoms.

(schema/register! ::db
                  [:keyword {:description "Database identity keyword, e.g. :seon.ai, :seon.runtime"}])

(schema/register! ::schema
                  [:any {:description "Optional Datalevin schema to apply on connection"}])

(schema/register! ::get-conn-request
                  [:map
                   [::manager ::manager]
                   [::db ::db]
                   [::schema {:optional true} ::schema]])

(schema/register! ::close-conn-request
                  [:map
                   [::manager ::manager]
                   [::db ::db]])

(schema/register! ::close-all-connections-request
                  [:map
                   [::manager ::manager]])

(schema/register! ::connection-stats-request
                  [:map
                   [::manager ::manager]])

(schema/register! ::total-connections
                  [:int {:min 0 :description "Number of cached connections"}])

(schema/register! ::databases
                  [:vector :keyword])

(schema/register! ::connection-stats-response
                  [:map
                   [::total-connections ::total-connections]
                   [::databases ::databases]])

;;; ---------------------------------------------------------------------------
;;; URI Construction
;;; ---------------------------------------------------------------------------

(defn- build-uri
  "Build a Datalevin connection URI.

   URI format: dtlv://username:password@host:port/db-name"
  [{::keys [host port username password]} db-name]
  (let [host (or host "127.0.0.1")
        username (or username "datalevin")
        password (or password "datalevin")]
    (format "dtlv://%s:%s@%s:%d/%s" username password host port db-name)))


;;; ---------------------------------------------------------------------------
;;; Connection Management (Internal)
;;; ---------------------------------------------------------------------------

(defn- get-datalevin-conn
  "Get a Datalevin connection, creating the database if needed.

   Uses d/get-conn which automatically creates the database on the server
   if it doesn't exist."
  [manager db-name schema]
  (require 'datalevin.core)
  (let [get-conn (resolve 'datalevin.core/get-conn)
        uri (build-uri manager db-name)]
    (log/debug "Connecting to Datalevin database" {:db-name db-name :uri (str "dtlv://...@" (::host manager "127.0.0.1") ":" (::port manager) "/" db-name)})
    (if schema
      (get-conn uri schema)
      (get-conn uri))))

(defn- close-datalevin-conn
  "Close a Datalevin connection."
  [conn]
  (when conn
    (require 'datalevin.core)
    (let [close-fn (resolve 'datalevin.core/close)]
      (try
        (close-fn conn)
        (catch Exception e
          (log/warn "Error closing Datalevin connection" {:error (.getMessage e)}))))))

(defn- connection-closed?
  "Check if a Datalevin connection is closed.
   Conservative approach: only return true if we're certain the conn is closed.
   Returns false if we can't determine status (assumes conn is usable)."
  [conn]
  (when conn
    (try
      ;; Require the correct namespace (datalevin.conn, not datalevin.core)
      (require 'datalevin.conn)
      (if-let [closed-fn (resolve 'datalevin.conn/closed?)]
        (closed-fn conn)
        ;; Can't resolve the function - assume conn is usable
        false)
      (catch ClassNotFoundException _
        ;; Namespace not loaded yet - assume conn is usable
        false)
      (catch Exception e
        ;; Log but don't assume closed - let actual transact fail if conn is bad
        (log/debug "Error checking connection status, assuming usable"
                   {:error (.getMessage e)})
        false))))

(defn- touch-connection!
  "Update the last-accessed time for a connection entry."
  [connections ns-key]
  (swap! connections update ns-key
         (fn [entry]
           (when entry
             (assoc entry ::last-accessed (Instant/now))))))

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

(defn- get-or-create-connection!
  "Get an existing connection or create a new one.

   Thread-safe: uses swap! with compare-and-set semantics.
   On connection error, marks entry as stale and throws."
  [manager ns-key db-name schema]
  (let [connections (::connections manager)]
    ;; First, try to get existing connection
    (if-let [entry (get @connections ns-key)]
      (if (connection-closed? (::connection entry))
        ;; Connection is closed, need to create new one
        (try
          (let [new-conn (get-datalevin-conn manager db-name schema)]
            (swap! connections assoc ns-key
                   {::connection new-conn
                    ::last-accessed (Instant/now)})
            new-conn)
          (catch Exception e
            (when (connection-error? e)
              (log/warn "Server unreachable while reconnecting" {:db db-name :error (.getMessage e)})
              (swap! connections dissoc ns-key))
            (throw e)))
        ;; Connection is still valid, touch and return it
        (do
          (touch-connection! connections ns-key)
          (::connection entry)))
      ;; No existing connection, create new one
      (try
        (let [new-conn (get-datalevin-conn manager db-name schema)]
          (swap! connections assoc ns-key
                 {::connection new-conn
                  ::last-accessed (Instant/now)})
          new-conn)
        (catch Exception e
          (when (connection-error? e)
            (log/warn "Server unreachable during initial connection" {:db db-name :error (.getMessage e)}))
          (throw e))))))

;;; ---------------------------------------------------------------------------
;;; TTL Cleanup
;;; ---------------------------------------------------------------------------

(defn- expired?
  "Check if a connection entry has expired based on TTL."
  [entry ttl-ms]
  (let [last-accessed (::last-accessed entry)
        now (Instant/now)
        age-ms (.toEpochMilli (.minusMillis now (.toEpochMilli last-accessed)))]
    ;; Calculate age properly
    (let [accessed-epoch (.toEpochMilli last-accessed)
          now-epoch (.toEpochMilli now)
          age (- now-epoch accessed-epoch)]
      (> age ttl-ms))))

(defn- cleanup-expired-connections!
  "Close and remove connections that have exceeded their TTL.

   Called periodically by the scheduler."
  [{::keys [connections ttl-ms] :as _manager}]
  (let [entries @connections
        expired-keys (filter (fn [[_ entry]] (expired? entry ttl-ms)) entries)]
    (doseq [[ns-key entry] expired-keys]
      (log/debug "Closing expired connection" {:namespace ns-key})
      (close-datalevin-conn (::connection entry))
      (swap! connections dissoc ns-key))))

(defn- start-cleanup-scheduler!
  "Start the background cleanup scheduler."
  [{::keys [cleanup-interval-ms] :as manager}]
  (let [scheduler (Executors/newSingleThreadScheduledExecutor)
        cleanup-task #(try
                        (cleanup-expired-connections! manager)
                        (catch Exception e
                          (log/error "Error in connection cleanup" {:error (.getMessage e)})))]
    (.scheduleAtFixedRate scheduler
                          cleanup-task
                          cleanup-interval-ms
                          cleanup-interval-ms
                          TimeUnit/MILLISECONDS)
    scheduler))

(defn- stop-cleanup-scheduler!
  "Stop the cleanup scheduler."
  [^ScheduledExecutorService scheduler]
  (when scheduler
    (.shutdown scheduler)
    (try
      (.awaitTermination scheduler 5 TimeUnit/SECONDS)
      (catch InterruptedException _
        (.shutdownNow scheduler)))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;;
;;; Note: These functions omit :malli/schema metadata intentionally.
;;; The manager contains runtime objects (atoms, schedulers, connections)
;;; that cannot be generated for property testing. See CONVENTIONS.md
;;; "When NOT to Use :malli/schema" section.
;;; ---------------------------------------------------------------------------

(defn get-conn!
  "Get connection to any Datalevin database by keyword identity.

   Uses ::db keyword to identify the database. The keyword's name becomes
   the Datalevin db-name (dots normalized to hyphens by Datalevin server).

   Request keys:
     ::manager - Required. Connection manager instance (runtime object)
     ::db      - Required. Database keyword, e.g. :seon.ai, :seon.runtime
     ::schema  - Optional. Datalevin schema map for attribute definitions

   Returns:
     Datalevin connection to the specified database.

   Note: No :malli/schema - manager contains non-generatable runtime objects.

   Examples:
     (get-conn! {::conn/manager mgr ::conn/db :seon.ai})
     (get-conn! {::conn/manager mgr ::conn/db :seon.runtime ::conn/schema merged-schema})
     (get-conn! {::conn/manager mgr ::conn/db :seon.trading})"
  [{::keys [manager db schema]}]
  (get-or-create-connection! manager db (name db) schema))

(defn close-conn!
  "Explicitly close a connection by database keyword.

   Removes the connection from the cache and closes it.

   Request keys:
     ::manager - Required. Connection manager instance
     ::db      - Required. Database keyword

   Returns:
     true if connection was closed, false if not found."
  [{::keys [manager db]}]
  (let [connections (::connections manager)]
    (if-let [entry (get @connections db)]
      (do
        (close-datalevin-conn (::connection entry))
        (swap! connections dissoc db)
        true)
      false)))

(defn close-all-connections!
  "Close all cached connections.

   Used during system shutdown.

   Request keys:
     ::manager - Required. Connection manager instance

   Returns:
     Number of connections closed."
  [{::keys [manager]}]
  (let [connections (::connections manager)
        entries @connections
        count (count entries)]
    (doseq [[ns-key entry] entries]
      (log/debug "Closing connection" {:namespace ns-key})
      (close-datalevin-conn (::connection entry)))
    (reset! connections {})
    count))

(defn connection-stats
  "Get statistics about cached connections.

   Request keys:
     ::manager - Required. Connection manager instance

   Returns:
     Map with connection statistics:
       ::total-connections - Number of cached connections
       ::databases         - List of connected database keywords"
  [{::keys [manager]}]
  (let [connections @(::connections manager)]
    {::total-connections (count connections)
     ::databases (vec (keys connections))}))

(defn reconnect!
  "Force reconnection for a database.

   Closes the cached connection if any, then creates a fresh one.
   Use when a connection is known to be stale or broken.

   Request keys:
     ::manager - Required. Connection manager instance
     ::db      - Required. Database keyword
     ::schema  - Optional. Datalevin schema to apply

   Returns:
     New Datalevin connection."
  [{::keys [manager db schema]}]
  (let [connections (::connections manager)]
    ;; Close existing connection if cached
    (when-let [entry (get @connections db)]
      (close-datalevin-conn (::connection entry))
      (swap! connections dissoc db))
    ;; Create fresh connection
    (get-or-create-connection! manager db (name db) schema)))

(defn health
  "Get health status of the connection manager.

   Returns a map describing the connection state:
     ::status - :connected, :disconnected, or :degraded
     ::total-connections - Number of cached connections
     ::server-reachable? - Whether the server port accepts TCP connections

   Request keys:
     ::manager - Required. Connection manager instance"
  [{::keys [manager]}]
  (let [connections @(::connections manager)
        port (::port manager)
        reachable? (try
                     (with-open [socket (java.net.Socket.)]
                       (.connect socket
                                 (java.net.InetSocketAddress. "127.0.0.1" (int port))
                                 1000)
                       true)
                     (catch Exception _ false))
        total (count connections)
        status (cond
                 (and reachable? (pos? total)) :connected
                 reachable? :connected
                 (pos? total) :degraded
                 :else :disconnected)]
    {::status status
     ::total-connections total
     ::server-reachable? reachable?}))

;;; ---------------------------------------------------------------------------
;;; Integrant Component
;;; ---------------------------------------------------------------------------

(defmethod ig/init-key :seon.db.datalevin/connections
  [_ {:keys [server ttl-ms cleanup-interval-ms]
      :or {ttl-ms 300000           ; 5 minutes default
           cleanup-interval-ms 60000}}]  ; 1 minute default
  (let [{:keys [port]} server
        manager {::port port
                 ::host "127.0.0.1"
                 ::username "datalevin"
                 ::password "datalevin"
                 ::ttl-ms ttl-ms
                 ::cleanup-interval-ms cleanup-interval-ms
                 ::connections (atom {})}
        scheduler (start-cleanup-scheduler! manager)]
    (log/info "Started connection manager" {:port port :ttl-ms ttl-ms})
    (assoc manager ::scheduler scheduler)))

(defmethod ig/halt-key! :seon.db.datalevin/connections
  [_ manager]
  (log/info "Stopping connection manager...")
  (stop-cleanup-scheduler! (::scheduler manager))
  (let [closed (close-all-connections! {::manager manager})]
    (log/info "Connection manager stopped" {:connections-closed closed})))

;; Suspend/resume to survive (reset) without dropping connections
(defmethod ig/suspend-key! :seon.db.datalevin/connections [_ state] state)

(defmethod ig/resume-key :seon.db.datalevin/connections
  [key opts old-opts old-state]
  (if (and (= (:ttl-ms opts) (:ttl-ms old-opts))
           (= (get-in opts [:server :port]) (get-in old-opts [:server :port])))
    old-state
    (do (ig/halt-key! key old-state)
        (ig/init-key key opts))))

;;; ---------------------------------------------------------------------------
;;; REPL Helpers
;;; ---------------------------------------------------------------------------

(comment
  ;; Manual testing (requires running server)
  (require '[integrant.repl.state :as state])

  ;; Get connection manager from system
  (def mgr (:seon.db.datalevin/connections state/system))

  ;; Get AI connection
  (def ai-conn (get-conn! {::manager mgr ::db :seon.ai}))
  ai-conn

  ;; Get runtime connection
  (def rt-conn (get-conn! {::manager mgr ::db :seon.runtime}))
  rt-conn

  ;; Get namespace connection
  (def trading-conn (get-conn! {::manager mgr ::db :seon.trading}))
  trading-conn

  ;; Check stats
  (connection-stats {::manager mgr})

  ;; Test transact
  (require '[datalevin.core :as d])
  (d/transact! ai-conn [{:test/name "hello"}])
  (d/q '[:find ?name :where [?e :test/name ?name]] @ai-conn)

  ;; Close a connection
  (close-conn! {::manager mgr ::db :seon.trading})

  ;; Close all
  (close-all-connections! {::manager mgr})

  nil)
