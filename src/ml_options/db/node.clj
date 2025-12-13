(ns ml-options.db.node
  "XTDB v2 node management and query utilities.

  XTDB v2 API differences from v1:
  - No 'database value' concept - queries execute directly on node
  - No xt/entity or xt/entity-history - use SQL/XTQL queries instead
  - Temporal queries use query options or SQL temporal clauses
  - execute-tx is synchronous (no await needed)

  XTQL Execution:
  - Use `xtql-query` for direct XTQL execution on in-process nodes
  - This bypasses the JDBC/SQL wrapper used by xt/q
  - Returns results as Clojure maps with kebab-case keywords"
  (:require [xtdb.api :as xt]
            [xtdb.protocols :as xtp]
            [xtdb.serde :as serde])
  (:import [java.util.stream Stream]))

;;; ---------------------------------------------------------------------------
;;; Query Execution
;;; ---------------------------------------------------------------------------

(defn- stream->vec
  "Convert a Java Stream to a Clojure vector."
  [^Stream stream]
  (try
    (vec (.toList stream))
    (finally
      (.close stream))))

(defn xtql-query
  "Execute an XTQL query directly on an in-process node.

  This uses the native XTQL execution path (xtp/open-xtql-query),
  bypassing the JDBC/SQL wrapper that xt/q uses. This is the recommended
  approach for XTQL queries on in-process nodes.

  Args:
    node - XTDB in-process node
    query-form - XTQL expression (e.g., (from :table [col1 col2]))
    opts - Optional map with:
           :args - Query arguments as a vector
           :current-time - Valid-time point for temporal queries
           :snapshot-time - System-time point for temporal queries
           :key-fn - Keyword transformation (default: :kebab-case-keyword)

  Returns:
    Query results as a vector of maps"
  ([node query-form]
   (xtql-query node query-form {}))
  ([node query-form opts]
   (let [{:keys [args current-time snapshot-time key-fn]
          :or {key-fn :kebab-case-keyword}} opts
         query-opts (cond-> {:key-fn (serde/read-key-fn key-fn)}
                      args (assoc :args args)
                      current-time (assoc :current-time current-time)
                      snapshot-time (assoc :snapshot-time snapshot-time))]
     (-> (xtp/open-xtql-query node query-form query-opts)
         stream->vec))))

(defn sql-query
  "Execute a SQL query on the node.

  Args:
    node - XTDB node
    sql - SQL string or [sql & args] vector
    opts - Optional map with temporal options

  Returns:
    Query results as a vector of maps"
  ([node sql]
   (sql-query node sql {}))
  ([node sql opts]
   (let [{:keys [current-time snapshot-time key-fn]
          :or {key-fn :kebab-case-keyword}} opts
         query-opts (cond-> {:key-fn (serde/read-key-fn key-fn)}
                      current-time (assoc :current-time current-time)
                      snapshot-time (assoc :snapshot-time snapshot-time))]
     (-> (xtp/open-sql-query node sql query-opts)
         stream->vec))))

(defn query
  "Execute a query against the XTDB node.

  Routes to xtql-query for XTQL expressions (sequences) and
  sql-query for SQL strings. Uses the direct protocol methods
  for proper XTQL support.

  Args:
    node - XTDB node
    query-form - SQL string or XTQL expression
    opts - Optional map with:
           :args - Query arguments (positional for SQL)
           :current-time - Valid-time point for temporal queries
           :snapshot-time - System-time point for temporal queries

  Returns:
    Query results as a vector of maps"
  ([node query-form]
   (query node query-form {}))
  ([node query-form opts]
   (cond
     ;; XTQL expression - use direct protocol method
     (seq? query-form)
     (xtql-query node query-form opts)

     ;; SQL string - use direct protocol method
     (string? query-form)
     (sql-query node query-form opts)

     ;; SQL with args [sql-string & args]
     (and (vector? query-form) (string? (first query-form)))
     (sql-query node (first query-form)
                (assoc opts :args (vec (rest query-form))))

     :else
     (throw (ex-info "Unknown query type"
                     {:query query-form :type (type query-form)})))))

(defn entity
  "Retrieve an entity by ID from a table.

  Args:
    node - XTDB node
    table - Table keyword (e.g., :option-quotes)
    id - Entity ID
    opts - Optional map with :current-time, :snapshot-time

  Returns:
    Entity map or nil"
  ([node table id]
   (entity node table id {}))
  ([node table id opts]
   ;; Convert kebab-case table name to snake_case for SQL
   (let [table-name (clojure.string/replace (name table) "-" "_")
         sql (str "SELECT * FROM " table-name " WHERE _id = '" id "'")]
     (first (sql-query node sql opts)))))

(defn entity-history
  "Get the complete history of an entity across time.

  Uses SQL for simplicity since XTQL temporal queries require explicit column binding.

  Args:
    node - XTDB node
    table - Table keyword
    id - Entity ID
    opts - Options map:
           :for-valid-time - :all-time or specific range
           :for-system-time - :all-time or specific range

  Returns:
    Sequence of historical versions"
  ([node table id]
   (entity-history node table id {:for-valid-time :all-time
                                  :for-system-time :all-time}))
  ([node table id opts]
   ;; Use SQL with FOR ALL VALID_TIME for temporal queries
   (let [table-name (clojure.string/replace (name table) "-" "_")
         sql (str "SELECT * FROM " table-name
                  " FOR ALL VALID_TIME"
                  " WHERE _id = '" id "'")]
     (sql-query node sql opts))))

;;; ---------------------------------------------------------------------------
;;; Transaction Submission
;;; ---------------------------------------------------------------------------

(defn execute-tx!
  "Execute a transaction synchronously.

  Transaction ops:
    [:put-docs :table {:xt/id \"id\" ...}]
    [:delete-docs :table \"id\"]
    [:sql \"INSERT INTO ...\"]

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
;;; Node Management
;;; ---------------------------------------------------------------------------

(defn status
  "Get the status of the XTDB node.

  Returns:
    Status map with node information including metrics"
  [node]
  (xt/status node))
