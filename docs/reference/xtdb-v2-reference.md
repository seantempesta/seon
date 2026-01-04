# XTDB v2 SQL Reference Guide

**Version**: 2.1.0 | **Last Updated**: 2026-01-04

---

## Quick Start

```clojure
(require '[xtdb.api :as xt])

;; Query with SQL
(xt/q node "SELECT ticker, strike, iv FROM option_quotes WHERE ticker = 'AAPL'")

;; Parameterized SQL
(xt/q node ["SELECT * FROM users WHERE _id = ?" user-id])

;; Insert data
(xt/execute-tx node
  [["INSERT INTO option_quotes (_id, ticker, strike, iv) VALUES (?, ?, ?, ?)"
    "AAPL230616C00150000" "AAPL" 150.0 0.25]])
```

---

## Table of Contents

1. [SQL Queries](#sql-queries) - **READ THIS FIRST**
2. [Multi-Database](#multi-database) - Agent isolation architecture
3. [Transactions](#transactions) - INSERT, DELETE, ERASE
4. [Temporal Queries](#temporal-queries) - Valid-time and system-time
5. [Node Setup](#node-setup)
6. [Data Types](#data-types)
7. [Quick Reference](#quick-reference)

---

## SQL Queries

XTDB v2 uses SQL as the primary query language. XTQL is also supported but SQL is more stable and better documented.

### Basic Queries

```clojure
;; Select all columns
(xt/q node "SELECT * FROM users")

;; Select specific columns
(xt/q node "SELECT name, email FROM users")

;; Filter with WHERE
(xt/q node "SELECT * FROM users WHERE status = 'active'")

;; Parameterized queries (ALWAYS use for user input)
(xt/q node ["SELECT * FROM users WHERE _id = ?" user-id])
(xt/q node ["SELECT * FROM option_quotes WHERE ticker = ? AND strike > ?" "AAPL" 150.0])
```

### Column Name Mapping

XTDB maps namespaced keywords to SQL column names:

| Clojure Keyword | SQL Column |
|-----------------|------------|
| `:xt/id` | `_id` |
| `:xt/valid-from` | `_valid_from` |
| `:xt/valid-to` | `_valid_to` |
| `:fn/namespace` | `fn$namespace` |
| `:asset/ticker` | `asset$ticker` |

### Aggregation

```clojure
;; Count
(xt/q node "SELECT COUNT(*) as cnt FROM users")

;; Group by with aggregation
(xt/q node "SELECT ticker, SUM(volume) as total_volume
            FROM option_quotes
            GROUP BY ticker")

;; Multiple aggregations
(xt/q node "SELECT ticker,
                   AVG(iv) as avg_iv,
                   MAX(volume) as max_volume
            FROM option_quotes
            GROUP BY ticker")
```

### Ordering and Limiting

```clojure
;; Order ascending
(xt/q node "SELECT * FROM users ORDER BY created_at")

;; Order descending
(xt/q node "SELECT * FROM users ORDER BY created_at DESC")

;; Limit results
(xt/q node "SELECT * FROM users ORDER BY created_at DESC LIMIT 10")

;; Offset for pagination
(xt/q node "SELECT * FROM users ORDER BY created_at LIMIT 10 OFFSET 20")
```

### Joins

```clojure
;; Inner join
(xt/q node "SELECT o.*, u.name
            FROM orders o
            JOIN users u ON o.user_id = u._id")

;; Left join
(xt/q node "SELECT u.*, COUNT(o._id) as order_count
            FROM users u
            LEFT JOIN orders o ON u._id = o.user_id
            GROUP BY u._id, u.name")
```

---

## Multi-Database

XTDB v2.1.0 supports multiple databases within a single node. This enables agent isolation where each namespace gets its own database.

### Architecture

```
Seon JVM
└── XTDB Node (shared)
    ├── "xtdb" database (primary - orchestrator)
    ├── "seon_trading" database (attached)
    ├── "seon_health" database (attached)
    └── ... (per namespace)
```

### Attach a Database

```sql
-- Run from primary 'xtdb' connection only
ATTACH DATABASE seon_trading WITH $$
  log: !Local
    path: 'data/namespaces/seon.trading/log'
  storage: !Local
    path: 'data/namespaces/seon.trading/storage'
$$
```

```clojure
;; From Clojure
(xt/execute-tx node
  [["ATTACH DATABASE seon_trading WITH $$
      log: !Local
        path: 'data/namespaces/seon.trading/log'
      storage: !Local
        path: 'data/namespaces/seon.trading/storage'
    $$"]])
```

### Connect to Specific Database

```clojure
;; Create a connection to a specific database
(let [conn (-> (.createConnectionBuilder node)
               (.database "seon_trading")
               (.build))]
  (try
    (xt/q conn "SELECT * FROM signals")
    (finally
      (.close conn))))

;; Or use :database option
(xt/q node "SELECT * FROM signals" {:database :seon_trading})
```

### Cross-Database Queries

```sql
-- Query from another database using qualified names
SELECT * FROM xtdb.users

-- Join across databases
SELECT s.*, u.name
FROM signals s
JOIN xtdb.users u ON s.user_id = u._id

-- Fully qualified
SELECT * FROM seon_trading.public.signals
```

### Table Reference Syntax

| Form | Meaning |
|------|---------|
| `table` | `public.table` in current database |
| `schema.table` | `schema.table` in current database |
| `database.table` | `public.table` in specified database |
| `database.schema.table` | `schema.table` in specified database |

### Detach a Database

```sql
-- Run from primary 'xtdb' connection only
DETACH DATABASE seon_trading
```

**Note**: Storage files remain on disk after detach.

### Constraints

1. ATTACH/DETACH only from primary `xtdb` database connection
2. Cannot run ATTACH/DETACH within a transaction
3. Database names must be unique
4. Cannot detach the primary `xtdb` database

---

## Transactions

### Insert (SQL)

```clojure
;; Single insert
(xt/execute-tx node
  [["INSERT INTO users (_id, name, email) VALUES (?, ?, ?)"
    "user-1" "Alice" "alice@example.com"]])

;; Batch insert (multiple rows)
(xt/execute-tx node
  [[:sql "INSERT INTO users (_id, name) VALUES (?, ?)"
    ["user-1" "Alice"]
    ["user-2" "Bob"]
    ["user-3" "Carol"]]])
```

### Insert (put-docs)

```clojure
;; Single document
(xt/execute-tx node
  [[:put-docs :users {:xt/id "user-1" :name "Alice" :email "alice@example.com"}]])

;; Multiple documents
(xt/execute-tx node
  [[:put-docs :users
    {:xt/id "user-1" :name "Alice"}
    {:xt/id "user-2" :name "Bob"}
    {:xt/id "user-3" :name "Carol"}]])

;; With valid-time
(xt/execute-tx node
  [[:put-docs :users
    {:xt/id "user-1"
     :name "Alice"
     :xt/valid-from #inst "2025-01-01"}]])
```

### Update

```clojure
;; SQL UPDATE
(xt/execute-tx node
  [["UPDATE users SET name = ? WHERE _id = ?" "Alicia" "user-1"]])

;; put-docs overwrites
(xt/execute-tx node
  [[:put-docs :users {:xt/id "user-1" :name "Alicia" :email "alicia@example.com"}]])
```

### Delete

```clojure
;; SQL DELETE
(xt/execute-tx node
  [["DELETE FROM users WHERE _id = ?" "user-1"]])

;; delete-docs
(xt/execute-tx node
  [[:delete-docs :users "user-1"]])

;; Delete for time range
(xt/execute-tx node
  [[:delete-docs :users "user-1"
    {:valid-from #inst "2025-01-01"
     :valid-to #inst "2025-06-01"}]])
```

### Erase (GDPR - Permanent)

```clojure
;; WARNING: Irreversibly removes all history
(xt/execute-tx node
  [["ERASE FROM users WHERE _id = ?" "user-1"]])

;; Or
(xt/execute-tx node
  [[:erase-docs :users "user-1"]])
```

### Transaction Options

```clojure
(xt/execute-tx node
  [[:put-docs :users {:xt/id "user-1" :name "Alice"}]]
  {:system-time #inst "2025-01-01"  ; Override system time
   :default-tz "America/New_York"   ; Override timezone
   :metadata {:correlation-id "abc123"}  ; Transaction metadata (v2.1+)
   :database :seon_trading})        ; Target specific database
```

---

## Temporal Queries

XTDB maintains two timelines:
- **Valid Time**: When the fact is true in the real world
- **System Time**: When XTDB recorded it

### Query at Valid Time

```sql
-- What was true on 2025-06-01?
SELECT * FROM users
FOR VALID_TIME AS OF TIMESTAMP '2025-06-01T00:00:00Z'

-- What was true between two dates?
SELECT * FROM users
FOR VALID_TIME FROM TIMESTAMP '2025-01-01' TO TIMESTAMP '2025-06-01'
```

```clojure
(xt/q node
  "SELECT * FROM users FOR VALID_TIME AS OF TIMESTAMP '2025-06-01T00:00:00Z'")
```

### Query All History

```sql
-- All valid-time history
SELECT _id, name, _valid_from, _valid_to
FROM users
FOR ALL VALID_TIME

-- Full bitemporal history
SELECT _id, name, _valid_from, _valid_to, _system_from, _system_to
FROM users
FOR ALL VALID_TIME
FOR ALL SYSTEM_TIME
```

### Query at System Time

```sql
-- What did we know on 2025-06-01?
SELECT * FROM users
FOR SYSTEM_TIME AS OF TIMESTAMP '2025-06-01T00:00:00Z'
```

### Common Patterns

```clojure
;; Events since a timestamp
(defn events-since [node table since-instant]
  (xt/q node
    [(format "SELECT * FROM %s WHERE _valid_from > ? ORDER BY _valid_from" table)
     since-instant]))

;; Most recent event time
(defn last-event-time [node table]
  (-> (xt/q node
        (format "SELECT _valid_from FROM %s ORDER BY _valid_from DESC LIMIT 1" table))
      first
      :xt/valid-from))

;; Count events in window
(xt/q node
  ["SELECT COUNT(*) as cnt FROM edit_events WHERE _valid_from > ?" cutoff-instant])
```

---

## Node Setup

### In-Memory (Development)

```clojure
(require '[xtdb.node :as xtn])

(def node (xtn/start-node))
;; Data lost when closed
```

### Persistent (Production)

```clojure
(def node (xtn/start-node
            {:log [:local {:path "data/xtdb/log"}]
             :storage [:local {:path "data/xtdb/storage"}]}))
```

### JVM Requirements

Requires JDK 21+ with flags:
```
--add-opens=java.base/java.nio=ALL-UNNAMED
--add-opens=java.base/sun.nio.ch=ALL-UNNAMED
--add-opens=java.base/java.lang=ALL-UNNAMED
--enable-native-access=ALL-UNNAMED
-Dio.netty.tryReflectionSetAccessible=true
```

---

## Data Types

| Clojure | SQL | Notes |
|---------|-----|-------|
| String | VARCHAR | |
| Long/Integer | BIGINT/INT | |
| Double | DOUBLE | |
| BigDecimal | NUMERIC | |
| Boolean | BOOLEAN | |
| Instant/Date | TIMESTAMP | Use #inst |
| UUID | UUID | |
| Keyword | VARCHAR | Stored as string |
| Vector | ARRAY | |
| Map | OBJECT | Nested docs supported |

---

## Quick Reference

### Query Patterns

| Pattern | SQL |
|---------|-----|
| Select all | `SELECT * FROM table` |
| Select columns | `SELECT col1, col2 FROM table` |
| Filter | `SELECT * FROM table WHERE col = ?` |
| Order asc | `ORDER BY col` |
| Order desc | `ORDER BY col DESC` |
| Limit | `LIMIT 10` |
| Offset | `LIMIT 10 OFFSET 20` |
| Count | `SELECT COUNT(*) FROM table` |
| Aggregate | `SELECT col, SUM(x) FROM table GROUP BY col` |
| Join | `JOIN other ON table.id = other.table_id` |
| Valid-time | `FOR VALID_TIME AS OF TIMESTAMP '...'` |
| All history | `FOR ALL VALID_TIME` |

### Transaction Patterns

| Operation | Syntax |
|-----------|--------|
| Insert (SQL) | `["INSERT INTO t (_id, col) VALUES (?, ?)" id val]` |
| Insert (put-docs) | `[:put-docs :table {:xt/id id :col val}]` |
| Update | `["UPDATE t SET col = ? WHERE _id = ?" val id]` |
| Delete (SQL) | `["DELETE FROM t WHERE _id = ?" id]` |
| Delete (put-docs) | `[:delete-docs :table id]` |
| Erase | `["ERASE FROM t WHERE _id = ?" id]` |

### Multi-Database

| Operation | Syntax |
|-----------|--------|
| Attach | `ATTACH DATABASE name WITH $$ log: ... storage: ... $$` |
| Detach | `DETACH DATABASE name` |
| Cross-db query | `SELECT * FROM other_db.table` |
| Connection | `(.database (.createConnectionBuilder node) "db")` |
| Query option | `(xt/q node "..." {:database :db_name})` |

---

## Files

- `src/seon/db/node.clj` - Query utilities
- `src/seon/db/queries.clj` - Domain queries
- `reference-code/xtdb/` - XTDB v2.1.0 source code
- `docs/prds/xtdb-sql-migration/research/multi-database.md` - Multi-DB research
