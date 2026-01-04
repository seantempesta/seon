(ns seon.db.multi
  "XTDB multi-database management for namespace isolation.

  Provides functions for managing attached databases in a single XTDB node.
  Each namespace (e.g., seon.primer, seon.dev) gets its own isolated database.

  ## Design

  The primary 'xtdb' database is used by the orchestrator. Secondary databases
  are attached dynamically for each namespace that needs isolation.

  Database names use underscores (SQL compatible) while storage paths preserve
  the original dotted namespace format.

  | Namespace | Database Name | Storage Path |
  |-----------|--------------|--------------|
  | seon.primer | seon_primer | data/namespaces/seon.primer/ |
  | seon.dev | seon_dev | data/namespaces/seon.dev/ |

  ## Usage

  ```clojure
  ;; Attach a database for a namespace
  (attach-namespace-db! node 'seon.primer)

  ;; Get a connection to the namespace's database
  (with-open [conn (create-namespace-connection node 'seon.primer)]
    (xt/q conn \"SELECT * FROM sessions\"))

  ;; List all attached databases
  (list-attached-databases node)
  ;; => #{\"xtdb\" \"seon_primer\" \"seon_dev\"}
  ```

  ## Important Notes

  - ATTACH/DETACH only work from the primary 'xtdb' database
  - Cannot attach/detach within a transaction
  - Attached databases persist across restarts (stored in primary db's log)
  - Each database has independent transaction timeline"
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [taoensso.timbre :as log]
            [xtdb.api :as xt]
            [xtdb.util :as xt-util])
  (:import [org.postgresql.util PSQLException]))

;;; ---------------------------------------------------------------------------
;;; Naming Conventions
;;; ---------------------------------------------------------------------------

(defn namespace->db-name
  "Convert a Clojure namespace to a SQL-compatible database name.

  Replaces dots with underscores since SQL uses dots for db.schema.table syntax.

  Examples:
    (namespace->db-name 'seon.primer) => \"seon_primer\"
    (namespace->db-name 'seon.dev)    => \"seon_dev\"
    (namespace->db-name \"seon.foo\") => \"seon_foo\""
  [ns-or-name]
  (-> (if (symbol? ns-or-name)
        (str ns-or-name)
        ns-or-name)
      (str/replace "." "_")))

(defn db-name->namespace
  "Convert a database name back to a namespace symbol.

  Examples:
    (db-name->namespace \"seon_primer\") => seon.primer
    (db-name->namespace \"seon_dev\")    => seon.dev"
  [db-name]
  (-> db-name
      (str/replace "_" ".")
      symbol))

(defn namespace->storage-path
  "Get the storage path for a namespace's database.

  Storage paths use the original dotted namespace format for clarity.

  Examples:
    (namespace->storage-path 'seon.primer)
    ;; => \"data/namespaces/seon.primer\""
  [ns-sym]
  (str "data/namespaces/" ns-sym))

;;; ---------------------------------------------------------------------------
;;; Database Queries
;;; ---------------------------------------------------------------------------

(defn list-attached-databases
  "List all databases attached to the XTDB node.

  Returns a set of database names including the primary 'xtdb' database.

  Args:
    node - XTDB node instance

  Returns:
    Set of database name strings

  Example:
    (list-attached-databases node)
    ;; => #{\"xtdb\" \"seon_primer\" \"seon_dev\"}"
  [node]
  ;; Access db-catalog via xtdb.util/component which handles the node structure
  (if-let [db-catalog (xt-util/component node :xtdb/db-catalog)]
    (.getDatabaseNames db-catalog)
    ;; Fallback: if we can't access the catalog, return at least xtdb
    #{"xtdb"}))

(defn db-attached?
  "Check if a database is attached to the node.

  Args:
    node - XTDB node instance
    db-name - Database name string

  Returns:
    Boolean"
  [node db-name]
  (contains? (list-attached-databases node) db-name))

(defn namespace-db-attached?
  "Check if a namespace's database is attached.

  Args:
    node - XTDB node instance
    ns-sym - Namespace symbol

  Returns:
    Boolean"
  [node ns-sym]
  (db-attached? node (namespace->db-name ns-sym)))

;;; ---------------------------------------------------------------------------
;;; Database Lifecycle
;;; ---------------------------------------------------------------------------

(defn attach-namespace-db!
  "Attach a database for a namespace to the XTDB node.

  Creates a new database with isolated log and storage at:
    data/namespaces/{namespace}/log
    data/namespaces/{namespace}/storage

  If the database already exists (attached from a previous session),
  this is a no-op and returns nil.

  Args:
    node - XTDB node instance (must be connected to primary 'xtdb' database)
    ns-sym - Namespace symbol (e.g., 'seon.primer)

  Returns:
    JDBC result or nil if already attached

  Example:
    (attach-namespace-db! node 'seon.primer)
    ;; Creates database 'seon_primer' with storage at data/namespaces/seon.primer/"
  [node ns-sym]
  (let [db-name (namespace->db-name ns-sym)
        storage-path (namespace->storage-path ns-sym)]
    (if (db-attached? node db-name)
      (do
        (log/debug "Database already attached" {:db-name db-name})
        nil)
      ;; Try to attach, but handle race condition where database was restored
      ;; from log between our check and the ATTACH command
      (try
        (log/info "Attaching namespace database" {:namespace ns-sym :db-name db-name})
        ;; ATTACH DATABASE is NOT a transaction - must use jdbc/execute! directly
        ;; See: reference-code/xtdb/src/test/clojure/xtdb/sql/multi_db_test.clj
        (jdbc/execute! node [(format "ATTACH DATABASE %s WITH $$
  log: !Local
    path: '%s/log'
  storage: !Local
    path: '%s/storage'
$$" db-name storage-path storage-path)])
        (catch org.postgresql.util.PSQLException e
          ;; Handle "Database already exists" gracefully - this can happen
          ;; during restarts when the database is restored from the log
          (if (re-find #"Database already exists" (.getMessage e))
            (do
              (log/debug "Database restored from log" {:db-name db-name})
              nil)
            (throw e)))))))

(defn detach-namespace-db!
  "Detach a namespace's database from the XTDB node.

  The database is removed from the cluster but storage files remain on disk.
  Any existing connections to the database become invalid.

  Args:
    node - XTDB node instance
    ns-sym - Namespace symbol

  Returns:
    JDBC result or nil if not attached

  Note: Cannot detach the primary 'xtdb' database."
  [node ns-sym]
  (let [db-name (namespace->db-name ns-sym)]
    (if-not (db-attached? node db-name)
      (do
        (log/debug "Database not attached" {:db-name db-name})
        nil)
      (do
        (log/info "Detaching namespace database" {:namespace ns-sym :db-name db-name})
        ;; DETACH DATABASE is NOT a transaction - must use jdbc/execute! directly
        ;; See: reference-code/xtdb/src/test/clojure/xtdb/sql/multi_db_test.clj
        (jdbc/execute! node [(format "DETACH DATABASE %s" db-name)])))))

(defn ensure-namespace-db!
  "Ensure a namespace's database exists, creating it if necessary.

  Idempotent - safe to call multiple times.

  Args:
    node - XTDB node instance
    ns-sym - Namespace symbol

  Returns:
    :attached if newly attached, :exists if already attached"
  [node ns-sym]
  (if (namespace-db-attached? node ns-sym)
    :exists
    (do
      (attach-namespace-db! node ns-sym)
      :attached)))

;;; ---------------------------------------------------------------------------
;;; Connection Management
;;; ---------------------------------------------------------------------------

(defn create-namespace-connection
  "Create a database connection for a namespace.

  Returns a Connection that should be closed when done.
  Use with-open or try-finally to ensure cleanup.

  Args:
    node - XTDB node instance
    ns-sym - Namespace symbol

  Returns:
    java.sql.Connection to the namespace's database

  Example:
    (with-open [conn (create-namespace-connection node 'seon.primer)]
      (xt/q conn \"SELECT * FROM sessions\"))"
  [node ns-sym]
  (let [db-name (namespace->db-name ns-sym)]
    (-> (.createConnectionBuilder node)
        (.database db-name)
        (.build))))

(defn with-namespace-db
  "Execute a function with a connection to a namespace's database.

  Ensures the database is attached and cleans up the connection.

  Args:
    node - XTDB node instance
    ns-sym - Namespace symbol
    f - Function that takes a connection and returns a result

  Returns:
    Result of calling f with the connection

  Example:
    (with-namespace-db node 'seon.primer
      (fn [conn]
        (xt/q conn \"SELECT * FROM sessions\")))"
  [node ns-sym f]
  (ensure-namespace-db! node ns-sym)
  (with-open [conn (create-namespace-connection node ns-sym)]
    (f conn)))

;;; ---------------------------------------------------------------------------
;;; Batch Operations
;;; ---------------------------------------------------------------------------

(defn attach-all-namespace-dbs!
  "Attach databases for multiple namespaces.

  Args:
    node - XTDB node instance
    namespaces - Collection of namespace symbols

  Returns:
    Map of namespace -> :attached or :exists"
  [node namespaces]
  (into {}
        (for [ns-sym namespaces]
          [ns-sym (ensure-namespace-db! node ns-sym)])))

(defn list-namespace-databases
  "List attached databases, returning namespace info.

  Filters out the primary 'xtdb' database and returns namespace metadata.

  Args:
    node - XTDB node instance

  Returns:
    Sequence of maps with :namespace and :db-name

  Example:
    (list-namespace-databases node)
    ;; => [{:namespace seon.primer :db-name \"seon_primer\"}
    ;;     {:namespace seon.dev :db-name \"seon_dev\"}]"
  [node]
  (->> (list-attached-databases node)
       (remove #(= "xtdb" %))
       (map (fn [db-name]
              {:namespace (db-name->namespace db-name)
               :db-name db-name}))))

;;; ---------------------------------------------------------------------------
;;; Integration with seon.db.node
;;; ---------------------------------------------------------------------------

(defn q
  "Execute a SQL query on a namespace's database.

  Convenience wrapper that combines connection creation and query execution.
  For multiple queries, prefer with-namespace-db for efficiency.

  Args:
    node - XTDB node instance
    ns-sym - Namespace symbol
    sql - SQL query string
    params - Optional query parameters

  Returns:
    Query results

  Example:
    (q node 'seon.primer \"SELECT * FROM sessions WHERE _id = ?\" [\"sess-1\"])"
  ([node ns-sym sql]
   (q node ns-sym sql []))
  ([node ns-sym sql params]
   (with-namespace-db node ns-sym
     (fn [conn]
       (vec (xt/q conn (into [sql] params) {:key-fn :kebab-case-keyword}))))))

(defn execute-tx!
  "Execute a transaction on a namespace's database.

  Args:
    node - XTDB node instance
    ns-sym - Namespace symbol
    tx-ops - Transaction operations

  Returns:
    Transaction result

  Example:
    (execute-tx! node 'seon.primer
      [[:put-docs :sessions {:xt/id \"s1\" :user \"seon\"}]])"
  [node ns-sym tx-ops]
  (with-namespace-db node ns-sym
    (fn [conn]
      (xt/execute-tx conn tx-ops))))

(comment
  ;; REPL exploration

  ;; Naming conventions
  (namespace->db-name 'seon.primer)     ; => "seon_primer"
  (db-name->namespace "seon_primer")    ; => seon.primer
  (namespace->storage-path 'seon.dev)   ; => "data/namespaces/seon.dev"

  ;; With a running system
  (require '[seon.db.multi :as multi])
  (def node (user/xtdb-node))

  ;; List databases
  (multi/list-attached-databases node)

  ;; Attach a namespace database
  (multi/attach-namespace-db! node 'seon.primer)

  ;; Check if attached
  (multi/namespace-db-attached? node 'seon.primer)

  ;; Query the namespace database
  (multi/q node 'seon.primer "SELECT * FROM primer_sessions")

  ;; Execute transactions
  (multi/execute-tx! node 'seon.primer
                     [[:put-docs :test {:xt/id "t1" :value 42}]])

  ;; Use with-namespace-db for multiple operations
  (multi/with-namespace-db node 'seon.primer
    (fn [conn]
      (xt/execute-tx conn [[:put-docs :foo {:xt/id "f1" :x 1}]])
      (xt/q conn "SELECT * FROM foo")))

  nil)
