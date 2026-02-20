(ns seon.agent.helpers
  "SQL helpers for agents. These use *ctx* implicitly for cleaner syntax.

   ## Usage

   These functions are automatically available in agent sessions.
   They read the database connection from `*ctx*`.

   ```clojure
   ;; Query - returns vector of maps with keyword keys
   (sql \"SELECT * FROM signals\")
   (sql \"SELECT * FROM signals WHERE symbol = ?\" \"AAPL\")
   (sql \"SELECT * FROM signals WHERE symbol = ? AND direction = ?\" \"AAPL\" \"long\")

   ;; Write - returns transaction result
   (sql! \"INSERT INTO signals (_id, symbol, direction) VALUES (?, ?, ?)\"
         \"sig-1\" \"AAPL\" \"long\")

   ;; Batch write - multiple rows in one transaction
   (sql-batch! \"INSERT INTO signals (_id, symbol) VALUES (?, ?)\"
               [\"sig-1\" \"AAPL\"]
               [\"sig-2\" \"TSLA\"])
   ```

   ## Table Creation

   Tables are created implicitly on first INSERT - no CREATE TABLE needed.

   ## Column Naming

   - Use `snake_case` for simple columns: `iv_rank`, `created_at`
   - Namespaced keywords use `$` separator: `:signal/symbol` -> `signal$symbol`"
  (:require [seon.orchestrator.nrepl :refer [*ctx*]]
            [seon.schema :as schema]))

;;; ---------------------------------------------------------------------------
;;; Schema Registration
;;; ---------------------------------------------------------------------------

(schema/register! ::query
                  [:string {:min 1 :description "SQL query string"}])

(schema/register! ::statement
                  [:string {:min 1 :description "SQL statement (INSERT/UPDATE/DELETE)"}])

(schema/register! ::params
                  [:* :any {:description "Query parameters"}])

(schema/register! ::param-row
                  [:vector :any {:description "Single row of parameters for batch insert"}])

(schema/register! ::result-row
                  [:map {:description "Single result row with keyword keys"}])

(schema/register! ::query-result
                  [:vector ::result-row {:description "Query results"}])

(schema/register! ::tx-result
                  [:map {:description "Transaction result"}])

;;; ---------------------------------------------------------------------------
;;; Private Helpers
;;; ---------------------------------------------------------------------------

(defn- get-db
  "Get database connection from *ctx*, throwing helpful error if not available."
  []
  (if-let [db (:seon.agent/db @*ctx*)]
    db
    (throw (ex-info "No database connection in *ctx*"
                    {:error :no-db-connection
                     :hint "Are you running in an agent session? *ctx* should have :seon.agent/db"}))))

;;; ---------------------------------------------------------------------------
;;; Public API
;;; ---------------------------------------------------------------------------

(defn sql
  "Execute a SQL query, returning results as a vector of maps.

   Uses the database connection from *ctx* automatically.

   Examples:
     (sql \"SELECT * FROM signals\")
     (sql \"SELECT * FROM signals WHERE symbol = ?\" \"AAPL\")
     (sql \"SELECT * FROM signals WHERE symbol = ? AND score > ?\" \"AAPL\" 0.8)"
  {:malli/schema [:=> [:cat ::query [:* :any]] ::query-result]}
  [query & _params]
  (throw (ex-info "SQL helpers not yet migrated to Datalevin"
                  {:query query})))

(defn sql!
  "Execute a SQL write statement (INSERT/UPDATE/DELETE).

   Uses the database connection from *ctx* automatically.
   Tables are created implicitly on first INSERT.

   Examples:
     (sql! \"INSERT INTO signals (_id, symbol) VALUES (?, ?)\" \"sig-1\" \"AAPL\")
     (sql! \"UPDATE signals SET direction = ? WHERE _id = ?\" \"short\" \"sig-1\")
     (sql! \"DELETE FROM signals WHERE _id = ?\" \"sig-1\")"
  {:malli/schema [:=> [:cat ::statement [:* :any]] ::tx-result]}
  [stmt & _params]
  (throw (ex-info "SQL helpers not yet migrated to Datalevin"
                  {:statement stmt})))

(defn sql-batch!
  "Execute a batch INSERT with multiple rows in one transaction.

   Uses the database connection from *ctx* automatically.

   Example:
     (sql-batch! \"INSERT INTO signals (_id, symbol, direction) VALUES (?, ?, ?)\"
                 [\"sig-1\" \"AAPL\" \"long\"]
                 [\"sig-2\" \"TSLA\" \"short\"]
                 [\"sig-3\" \"GOOG\" \"long\"])"
  {:malli/schema [:=> [:cat ::statement [:* ::param-row]] ::tx-result]}
  [stmt & _param-rows]
  (throw (ex-info "SQL helpers not yet migrated to Datalevin"
                  {:statement stmt})))

(comment
  ;; REPL testing (requires running agent session with *ctx* bound)

  ;; Query
  (sql "SELECT * FROM signals")
  (sql "SELECT * FROM signals WHERE symbol = ?" "AAPL")

  ;; Insert
  (sql! "INSERT INTO signals (_id, symbol, direction) VALUES (?, ?, ?)"
        "sig-1" "AAPL" "long")

  ;; Batch insert
  (sql-batch! "INSERT INTO signals (_id, symbol, direction) VALUES (?, ?, ?)"
              ["sig-1" "AAPL" "long"]
              ["sig-2" "TSLA" "short"])

  ;; Update
  (sql! "UPDATE signals SET direction = ? WHERE _id = ?" "short" "sig-1")

  ;; Delete
  (sql! "DELETE FROM signals WHERE _id = ?" "sig-1")

  nil)
