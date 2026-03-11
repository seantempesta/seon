# XTDB Multi-Database Research

**Date**: 2026-01-04
**XTDB Version**: 2.1.0 (submodule) / 2.1.0-rc0 (deps.edn - needs update)
**Purpose**: Document multi-database architecture for agent isolation

> **NOTE**: This is internal orchestrator infrastructure. Agents never see database
> names or SQL. They interact with a `*ctx*` atom; the system handles persistence.
> See `docs/prds/agent-isolation/prd.md` for the agent-facing design.

---

## Executive Summary

XTDB v2.1.0 introduces full multi-database support that perfectly fits our agent isolation architecture:

- **ATTACH DATABASE** creates secondary databases with separate log/storage
- **Cross-database queries** via `db.table` or `db.schema.table` syntax
- **Connection management** via `.database("name")` on connection builder
- **DETACH DATABASE** removes attached databases from the cluster
- **Constraints**: ATTACH/DETACH only from primary `xtdb` database connection

This enables our target architecture where each agent namespace (e.g., `seon.trading`) gets its own isolated database.

---

## Version Status

| Component | Current | Target | Action |
|-----------|---------|--------|--------|
| `reference-code/xtdb/` submodule | v2.1.0 | v2.1.0 | None needed |
| `deps.edn` xtdb-api | 2.1.0-rc0 | 2.1.0 | Update needed |
| `deps.edn` xtdb-core | 2.1.0-rc0 | 2.1.0 | Update needed |

The submodule is already at v2.1.0. The deps.edn versions should be updated from `2.1.0-rc0` to `2.1.0` for the stable release.

---

## ATTACH DATABASE Syntax

### Basic Local Storage

```sql
ATTACH DATABASE namespace_db WITH $$
  log: !Local
    path: 'data/namespaces/namespace_db/log'
  storage: !Local
    path: 'data/namespaces/namespace_db/storage'
$$

```

### For Agent Isolation (Clojure Example)

```clojure
(require '[xtdb.api :as xt])

;; From primary database connection, attach a namespace database
(defn attach-namespace-db! [node namespace-sym]
  (let [ns-name (str namespace-sym)  ; e.g., "seon.trading"
        ;; Convert dots to underscores for SQL compatibility
        db-name (clojure.string/replace ns-name "." "_")]
    (xt/execute-tx node
      [[(format "ATTACH DATABASE %s WITH $$
                   log: !Local
                     path: 'data/namespaces/%s/log'
                   storage: !Local
                     path: 'data/namespaces/%s/storage'
                 $$" db-name ns-name ns-name)]])))

;; Usage
(attach-namespace-db! node 'seon.trading)
;; Creates database "seon_trading" with storage at data/namespaces/seon.trading/

```

### Key Constraints

1. **Must run from primary `xtdb` database connection**
   - Cannot attach a database while connected to a secondary database
   - Error: "Can only attach databases when connected to the primary 'xtdb' database."

2. **Cannot run within a transaction**
   - ATTACH/DETACH are not transactional operations
   - Error: "Cannot attach a database in a transaction."

3. **Database names must be unique**
   - Error if database already exists: "Database already exists"

4. **Databases persist across restarts**
   - Attached databases are stored in the primary database's transaction log
   - On restart, they're automatically re-attached

---

## Getting a Connection to a Specific Database

### Clojure API

```clojure
;; Method 1: Using createConnectionBuilder (recommended)
(let [conn (-> (.createConnectionBuilder node)
               (.database "seon_trading")
               (.build))]
  (try
    ;; Use conn for queries/transactions on seon_trading database
    (xt/q conn "SELECT * FROM signals")
    (finally
      (.close conn))))

;; Method 2: Using xt/q with :database option
(xt/q node "SELECT * FROM signals" {:database :seon_trading})

;; Method 3: For execute-tx with :database option
(xt/execute-tx node
  [[:put-docs :signals {:xt/id "sig1" :symbol "AAPL"}]]
  {:database :seon_trading})

```

### Creating Agent Context

For agent isolation, we create a dedicated connection:

```clojure
(defn create-agent-db-connection
  "Create a database connection for an agent's namespace.
   Returns a Connection that should be closed when agent completes."
  [node namespace-sym]
  (let [db-name (-> namespace-sym str (clojure.string/replace "." "_"))]
    (-> (.createConnectionBuilder node)
        (.database db-name)
        (.build))))

;; In agent context
(def agent-ctx
  (atom
    {:seon.agent/namespace 'seon.trading
     :seon.agent/db (create-agent-db-connection node 'seon.trading)}))

```

---

## Cross-Database Queries

### Syntax Options

| Form | Meaning |
|------|---------|
| `table` | `public.table` in current database |
| `schema.table` | `schema.table` in current database |
| `database.table` | `public.table` in specified database |
| `database.schema.table` | `schema.table` in specified database |

### Examples

```sql
-- Connected to seon_trading, query from xtdb primary
SELECT * FROM xtdb.users

-- Join across databases
SELECT s.*, u.name
FROM signals s
JOIN xtdb.users u ON s.user_id = u._id

-- Fully qualified to avoid ambiguity
SELECT * FROM seon_trading.public.signals

-- Union across databases
SELECT symbol FROM signals
UNION ALL
SELECT symbol FROM seon_health.watchlist

```

### Gotcha: Ambiguous References

If a database named `public` exists, `public.table` becomes ambiguous:
- Could mean: `public` schema in current database
- Or: `table` in `public` database

XTDB will raise: "Ambiguous table reference: public.table"

**Solution**: Use fully qualified `database.schema.table` syntax.

---

## DETACH DATABASE

```sql
DETACH DATABASE seon_trading

```

### Constraints

1. **Cannot detach primary database**: Error "Cannot detach the primary 'xtdb' database"
2. **Cannot detach in transaction**: Same as ATTACH
3. **Must be connected to primary**: Same as ATTACH

### Effect

- Database is removed from the cluster
- Storage files remain on disk (not deleted)
- Any connections to that database become invalid
- Error on re-connection: "database 'db_name' does not exist"

---

## Memory Implications

From the agent isolation notes, estimated memory per namespace database:

| Component | Memory |
|-----------|--------|
| Per namespace XTDB DB (attached) | ~100-200MB |
| Buffer pool, indexer state | Varies by data size |

**Key insight**: Databases share the JVM heap and XTDB node infrastructure. Memory efficiency is much better than running separate XTDB nodes.

The v2.1.0 architecture explicitly decouples:
- **Clusters (compute)**: JVM, query engine, buffer pool
- **Databases (storage)**: Log + object store per database

This means attaching a database primarily adds:
- Log consumption thread
- Index state for that database's tables
- Cache entries as data is queried

---

## Temporal Queries with Multi-Database

Temporal queries work the same within a database:

```sql
-- Query at valid time
SELECT * FROM seon_trading.signals
FOR VALID_TIME AS OF TIMESTAMP '2025-01-01T00:00:00Z'

-- Query all history
SELECT * FROM seon_trading.signals
FOR ALL VALID_TIME

-- Cross-database with temporal
SELECT s.*, u.name
FROM seon_trading.signals FOR ALL VALID_TIME s
JOIN xtdb.users u ON s.user_id = u._id

```

**Important**: Each database has its own transaction log, so:
- `xt.txs` table is per-database
- `AWAIT_TOKEN` / snapshot tokens are per-database
- Cross-database queries may see slightly different "now" per database

---

## Implementation Recommendations

### 1. Database Naming Convention

Use underscores instead of dots for SQL compatibility:
- Namespace: `seon.trading`
- Database name: `seon_trading`
- Storage path: `data/namespaces/seon.trading/`

### 2. Orchestrator Responsibilities

The orchestrator (connected to primary `xtdb` database) should:

1. **Register namespace databases** in `xtdb` database:

   ```clojure
   {:xt/id :namespace/seon.trading
    :namespace/name 'seon.trading
    :namespace/db-name "seon_trading"
    :namespace/status :active}

   ```

2. **Attach on first use**:

   ```clojure
   (defn ensure-namespace-db! [node namespace-sym]
     (let [db-name (namespace->db-name namespace-sym)]
       (when-not (db-attached? node db-name)
         (attach-namespace-db! node namespace-sym))))

   ```

3. **Create agent connections**:

   ```clojure
   (defn start-namespace-agent! [node namespace-sym]
     (ensure-namespace-db! node namespace-sym)
     (let [conn (create-agent-db-connection node namespace-sym)]
       {:seon.agent/db conn
        :seon.agent/namespace namespace-sym
        ...}))

   ```

### 3. Connection Lifecycle

```clojure
;; Agent receives a connection to its database
(let [{:seon.agent/keys [db]} @agent-ctx]
  ;; All queries go to agent's isolated database
  (xt/q db "SELECT * FROM signals"))

;; On agent completion, close the connection
(.close (:seon.agent/db @agent-ctx))

```

### 4. Cross-Namespace Data Access

For read-only access to other namespaces:

```clojure
;; Agent can read from other databases using qualified names
(xt/q agent-db
  "SELECT * FROM xtdb.users WHERE _id = ?"
  [user-id])

;; But cannot write to other databases (connection is to their namespace)

```

---

## Open Questions (Resolved)

### Q: How do we create/attach a database for a namespace like `seon.trading`?

**A**: Use `ATTACH DATABASE` with local log/storage paths. Convert dots to underscores for SQL compatibility.

### Q: How do we get a connection to a specific database?

**A**: Use `(.createConnectionBuilder node) (.database "db_name") (.build)` to get a `java.sql.Connection` to that database. Or use `:database` option with `xt/q` and `xt/execute-tx`.

### Q: What's the SQL syntax for cross-database queries?

**A**: Use `database.table` or `database.schema.table` syntax. Unqualified tables refer to the connected database.

### Q: What's the memory overhead per attached database?

**A**: Estimated 100-200MB per attached database, much more efficient than separate XTDB nodes (~1.5GB each).

### Q: How do temporal queries work with multi-database?

**A**: Same temporal SQL syntax works. Each database has its own transaction timeline, so cross-database queries may see slightly different "now" per database.

---

## Remaining Work

1. **Update deps.edn** from `2.1.0-rc0` to `2.1.0`
2. **Implement** `seon.db.multi/attach-namespace-db!`
3. **Implement** `seon.db.multi/create-namespace-connection`
4. **Update** `seon.system` to manage namespace databases
5. **Test** cross-database queries in practice

---

## References

- [XTDB v2.1.0 Release Notes](https://github.com/xtdb/xtdb/releases/tag/v2.1.0)
- [XTDB Multi-Database Docs](https://docs.xtdb.com/about/dbs-in-xtdb.html)
- `reference-code/xtdb/src/test/clojure/xtdb/sql/multi_db_test.clj` - Test examples
- `reference-code/xtdb/docs/src/content/docs/about/dbs-in-xtdb.md` - Source docs
