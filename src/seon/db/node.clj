(ns seon.db.node
  "XTDB node management and SQL query wrappers.

  XTDB v2.1.0 uses SQL as the primary query language for stability and performance.
  All queries use parameterized SQL to prevent injection.

  ## Query Execution

  Use `q` for SQL queries:
    (q node \"SELECT * FROM users WHERE name = ?\" [\"Alice\"])
    (q node \"SELECT * FROM users\")  ; no params

  Use `entity` for single entity lookup:
    (entity node :users \"user-123\")

  ## Temporal Queries

  All query functions accept an opts map for temporal control:
    {:current-time #inst \"2024-01-15\"}  ; as of valid-time
    {:snapshot-time #inst \"2024-01-10\"} ; as of system-time

  ## Migration Note (v2.1.0)

  The legacy `query` and `xtql-query` functions have been deprecated.
  All code should now use `q` with SQL syntax.

  XTDB v2 API differences from v1:
  - No 'database value' concept - queries execute directly on node
  - No xt/entity or xt/entity-history - use SQL queries instead
  - Temporal queries use query options or SQL temporal clauses
  - execute-tx is synchronous (no await needed)"
  (:require [xtdb.api :as xt]))

;;; ---------------------------------------------------------------------------
;;; SQL Query Execution
;;; ---------------------------------------------------------------------------

(defn q
  "Execute a SQL query against XTDB.

  Supports multiple calling conventions:
  1. (q node \"SELECT * FROM table\")
  2. (q node \"SELECT * FROM table WHERE col = ?\" [val])
  3. (q node \"SELECT * FROM table WHERE col = ?\" [val] opts)
  4. (q node [\"SELECT * FROM table WHERE col = ?\" val])
  5. (q node [\"SELECT * FROM table WHERE col = ?\" val] opts)

  Query can be:
  - A SQL string with optional params vector
  - A vector of [sql-string & params]

  Opts map (optional):
  - :key-fn - Result key transformation (default :kebab-case-keyword)
  - :current-time - Valid-time for temporal query
  - :snapshot-time - System-time for point-in-time query

  Returns:
    Vector of result maps"
  ([node query-or-sql]
   (q node query-or-sql nil nil))
  ([node query-or-sql params-or-opts]
   (if (map? params-or-opts)
     ;; (q node query opts)
     (q node query-or-sql nil params-or-opts)
     ;; (q node sql params)
     (q node query-or-sql params-or-opts nil)))
  ([node query-or-sql params opts]
   (let [;; Normalize to [sql & params] format
         [sql & sql-params] (if (vector? query-or-sql)
                              query-or-sql
                              (into [query-or-sql] (or params [])))
         query-vec (into [sql] sql-params)
         ;; Build options
         query-opts (cond-> {:key-fn :kebab-case-keyword}
                      (:current-time opts) (assoc :current-time (:current-time opts))
                      (:snapshot-time opts) (assoc :snapshot-time (:snapshot-time opts))
                      (:key-fn opts) (assoc :key-fn (:key-fn opts)))]
     (vec (xt/q node query-vec query-opts)))))

;;; ---------------------------------------------------------------------------
;;; Entity Lookup
;;; ---------------------------------------------------------------------------

(defn entity
  "Get a single entity by ID from a table.

  Args:
    node - XTDB node
    table - Table keyword (e.g., :users, :option-greeks)
    id - Entity ID
    opts - Optional map with :current-time, :snapshot-time

  Returns:
    Entity map or nil if not found"
  ([node table id]
   (entity node table id {}))
  ([node table id opts]
   (let [;; Convert kebab-case to snake_case for SQL table name
         table-name (clojure.string/replace (name table) "-" "_")
         query-opts (cond-> {:key-fn :kebab-case-keyword}
                      (:current-time opts) (assoc :current-time (:current-time opts))
                      (:snapshot-time opts) (assoc :snapshot-time (:snapshot-time opts)))]
     (first (xt/q node
                  [(str "SELECT * FROM " table-name " WHERE _id = ?") id]
                  query-opts)))))

(defn entity-history
  "Get the complete history of an entity across time.

  Uses SQL FOR ALL VALID_TIME to retrieve all temporal versions.

  Args:
    node - XTDB node
    table - Table keyword
    id - Entity ID
    opts - Options map (currently ignored, queries all time)

  Returns:
    Sequence of historical versions"
  ([node table id]
   (entity-history node table id {}))
  ([node table id opts]
   (let [table-name (clojure.string/replace (name table) "-" "_")
         sql (str "SELECT * FROM " table-name " FOR ALL VALID_TIME WHERE _id = ?")]
     (vec (xt/q node [sql id] {:key-fn :kebab-case-keyword})))))

;;; ---------------------------------------------------------------------------
;;; DEPRECATED: Legacy Query Functions
;;; ---------------------------------------------------------------------------

(defn xtql-query
  "DEPRECATED: Execute an XTQL query on the node.

  XTQL is no longer supported. Please migrate to SQL:

    ;; Old XTQL:
    (xtql-query node '(from :users [name email]))

    ;; New SQL:
    (q node \"SELECT name, email FROM users\")

  This function will throw an error if called."
  ([node query-form]
   (xtql-query node query-form {}))
  ([node query-form opts]
   (throw (ex-info "XTQL queries are no longer supported. Please use SQL syntax."
                   {:query query-form
                    :hint "Use (q node \"SELECT ...\") instead"}))))

(defn sql-query
  "DEPRECATED: Use `q` instead.

  This is an alias for `q` for backward compatibility."
  ([node sql]
   (q node sql))
  ([node sql opts]
   (q node sql nil opts)))

(defn query
  "DEPRECATED: Execute a query against the XTDB node.

  This function previously supported both XTQL and SQL. XTQL is no longer
  supported. Please migrate to `q` with SQL syntax:

    ;; Old XTQL (no longer supported):
    (query node '(from :users [name email]))

    ;; New SQL:
    (q node \"SELECT name, email FROM users\")

  For backward compatibility, SQL queries still work but log a deprecation notice."
  ([node query-form]
   (query node query-form {}))
  ([node query-form opts]
   (cond
     ;; XTQL expression (quoted form) - no longer supported
     (seq? query-form)
     (throw (ex-info "XTQL queries are no longer supported. Please use SQL syntax."
                     {:query query-form
                      :hint "Use (q node \"SELECT ...\") instead of (query node '(from :table [...]))"}))

     ;; SQL string - route to q
     (string? query-form)
     (q node query-form nil opts)

     ;; SQL with args [sql-string & args]
     (and (vector? query-form) (string? (first query-form)))
     (q node query-form nil opts)

     :else
     (throw (ex-info "Unknown query type"
                     {:query query-form :type (type query-form)})))))

;;; ---------------------------------------------------------------------------
;;; Transaction Helpers
;;; ---------------------------------------------------------------------------

(defn put!
  "Put documents into XTDB.

  Args:
    node - XTDB node
    table - Table keyword (e.g., :users)
    docs - Single doc or vector of docs

  Each doc must have :xt/id. Optional :xt/valid-from for temporal control.

  Returns:
    Transaction result"
  [node table docs]
  (let [docs-vec (if (vector? docs) docs [docs])]
    (xt/execute-tx node
                   [(into [:put-docs table] docs-vec)])))

(defn delete!
  "Delete documents from XTDB by ID.

  Args:
    node - XTDB node
    table - Table keyword
    ids - Single ID or vector of IDs

  Returns:
    Transaction result"
  [node table ids]
  (let [ids-vec (if (vector? ids) ids [ids])]
    (xt/execute-tx node
                   (mapv (fn [id] [:delete-docs table id]) ids-vec))))

(defn execute-tx!
  "Execute a transaction synchronously.

  Transaction ops:
    [:put-docs :table {:xt/id \"id\" ...}]
    [:delete-docs :table \"id\"]
    [:erase-docs :table \"id\"]

  Args:
    node - XTDB node
    tx-ops - Vector of transaction operations

  Returns:
    Transaction result with :tx-id and :system-time"
  [node tx-ops]
  (xt/execute-tx node tx-ops))

(defn submit-tx!
  "Submit a transaction asynchronously.

  Use execute-tx! for synchronous execution (preferred in most cases).

  Args:
    node - XTDB node
    tx-ops - Vector of transaction operations

  Returns:
    Transaction result"
  [node tx-ops]
  (xt/submit-tx node tx-ops))

;;; ---------------------------------------------------------------------------
;;; Node Status
;;; ---------------------------------------------------------------------------

(defn status
  "Get the status of the XTDB node.

  Returns:
    Status map with node information including metrics"
  [node]
  (xt/status node))
