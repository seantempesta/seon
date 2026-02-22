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

   ;; Get master database connection (always exists)
   (conn/get-master-conn! {::conn/manager manager})

   ;; Get namespace connection (creates DB if needed)
   (conn/get-namespace-conn! {::conn/manager manager
                              ::conn/namespace 'seon.trading})

   ;; Explicitly close a namespace connection
   (conn/close-namespace-conn! {::conn/manager manager
                                ::conn/namespace 'seon.trading})
   ```

   ## Database Naming

   - Master database: `seon` (for orchestrator data)
   - Namespace databases: `seon.{namespace}` (e.g., `seon.trading`)

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

(schema/register! ::namespace
                  [:or :symbol :string])

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

(schema/register! ::get-master-conn-request
                  [:map
                   [::manager ::manager]])

(schema/register! ::schema
                  [:any {:description "Optional Datalevin schema to apply on connection"}])

(schema/register! ::get-namespace-conn-request
                  [:map
                   [::manager ::manager]
                   [::namespace ::namespace]
                   [::schema {:optional true} ::schema]])

(schema/register! ::close-namespace-conn-request
                  [:map
                   [::manager ::manager]
                   [::namespace ::namespace]])

(schema/register! ::close-all-connections-request
                  [:map
                   [::manager ::manager]])

(schema/register! ::connection-stats-request
                  [:map
                   [::manager ::manager]])

(schema/register! ::total-connections
                  [:int {:min 0 :description "Number of cached connections"}])

(schema/register! ::namespaces
                  [:vector :keyword])

(schema/register! ::master-connected?
                  [:boolean {:description "Whether master DB is connected"}])

(schema/register! ::connection-stats-response
                  [:map
                   [::total-connections ::total-connections]
                   [::namespaces ::namespaces]
                   [::master-connected? ::master-connected?]])

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

(defn- namespace->db-name
  "Convert a namespace to a database name.

   'seon.trading -> \"seon.trading\"
   \"seon.trading\" -> \"seon.trading\""
  [ns]
  (if (symbol? ns)
    (str ns)
    ns))

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
  "Check if a Datalevin connection is closed."
  [conn]
  (when conn
    (require 'datalevin.core)
    (let [closed? (resolve 'datalevin.conn/closed?)]
      (try
        (closed? conn)
        (catch Exception _
          true)))))

(defn- touch-connection!
  "Update the last-accessed time for a connection entry."
  [connections ns-key]
  (swap! connections update ns-key
         (fn [entry]
           (when entry
             (assoc entry ::last-accessed (Instant/now))))))

(defn- get-or-create-connection!
  "Get an existing connection or create a new one.

   Thread-safe: uses swap! with compare-and-set semantics."
  [manager ns-key db-name schema]
  (let [connections (::connections manager)]
    ;; First, try to get existing connection
    (if-let [entry (get @connections ns-key)]
      (if (connection-closed? (::connection entry))
        ;; Connection is closed, need to create new one
        (let [new-conn (get-datalevin-conn manager db-name schema)]
          (swap! connections assoc ns-key
                 {::connection new-conn
                  ::last-accessed (Instant/now)})
          new-conn)
        ;; Connection is still valid, touch and return it
        (do
          (touch-connection! connections ns-key)
          (::connection entry)))
      ;; No existing connection, create new one
      (let [new-conn (get-datalevin-conn manager db-name schema)]
        (swap! connections assoc ns-key
               {::connection new-conn
                ::last-accessed (Instant/now)})
        new-conn))))

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

(def ^:private master-db-name "seon")

(defn get-master-conn!
  "Get connection to the master control database.

   The master database (`seon`) stores orchestrator data:
   - Session registry
   - AI messages (Observatory)
   - Cross-namespace queries

   Request keys:
     ::manager - Required. Connection manager instance (runtime object)

   Returns:
     Datalevin connection to the master database.

   Note: No :malli/schema - manager contains non-generatable runtime objects.

   Example:
     (get-master-conn! {::conn/manager manager})"
  [{::keys [manager]}]
  (get-or-create-connection! manager ::master master-db-name nil))

(defn get-namespace-conn!
  "Get connection to a namespace-specific database.

   Creates the database lazily on first connection. Each namespace
   gets its own isolated database for domain-specific data.

   Request keys:
     ::manager   - Required. Connection manager instance
     ::namespace - Required. Namespace symbol or string
     ::schema    - Optional. Datalevin schema to apply on connection

   Returns:
     Datalevin connection to the namespace database.

   Example:
     (get-namespace-conn! {::manager manager
                           ::namespace 'seon.trading})
     (get-namespace-conn! {::manager manager
                           ::namespace 'seon.trading
                           ::schema my-schema})"
  [{::keys [manager namespace schema]}]
  (let [ns-str (namespace->db-name namespace)
        ns-key (keyword ns-str)]
    (get-or-create-connection! manager ns-key ns-str schema)))

(defn close-namespace-conn!
  "Explicitly close a namespace connection.

   Removes the connection from the cache and closes it.
   Use when you know a namespace won't be accessed for a while.

   Request keys:
     ::manager   - Required. Connection manager instance
     ::namespace - Required. Namespace symbol or string

   Returns:
     true if connection was closed, false if not found.

   Example:
     (close-namespace-conn! {::manager manager
                             ::namespace 'seon.trading})"
  [{::keys [manager namespace]}]
  (let [ns-str (namespace->db-name namespace)
        ns-key (keyword ns-str)
        connections (::connections manager)]
    (if-let [entry (get @connections ns-key)]
      (do
        (close-datalevin-conn (::connection entry))
        (swap! connections dissoc ns-key)
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
       ::namespaces        - List of connected namespaces
       ::master-connected? - Whether master DB is connected"
  [{::keys [manager]}]
  (let [connections @(::connections manager)
        ns-keys (keys connections)]
    {::total-connections (count connections)
     ::namespaces (remove #{::master} ns-keys)
     ::master-connected? (contains? connections ::master)}))

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

  ;; Get master connection
  (def master-conn (get-master-conn! {::manager mgr}))
  master-conn

  ;; Get namespace connection
  (def trading-conn (get-namespace-conn! {::manager mgr
                                           ::namespace 'seon.trading}))
  trading-conn

  ;; Check stats
  (connection-stats {::manager mgr})

  ;; Test transact
  (require '[datalevin.core :as d])
  (d/transact! master-conn [{:test/name "hello"}])
  (d/q '[:find ?name :where [?e :test/name ?name]] @master-conn)

  ;; Close namespace connection
  (close-namespace-conn! {::manager mgr ::namespace 'seon.trading})

  ;; Close all
  (close-all-connections! {::manager mgr})

  nil)
