---
name: xtdb-queries
description: "XTDB database query patterns. Use when writing queries, debugging empty results, editing queries.clj or node.clj, working with SQL queries, or accessing database data. Use when you see xt/q, xt/execute-tx, seon.db namespace, multi-database operations, or when queries return unexpected results."
---

# XTDB SQL Query Patterns

## Critical Rules

1. **Use SQL** - XTDB v2 uses SQL as the primary query language
2. **Parameterize queries** - Always use `["SELECT ... WHERE col = ?" val]` for dynamic values
3. **Column mapping** - `:xt/id` becomes `_id`, `:xt/valid-from` becomes `_valid_from`

## Basic Queries

```clojure
(require '[xtdb.api :as xt])

;; Simple query
(xt/q node "SELECT * FROM users WHERE status = 'active'")

;; Parameterized (ALWAYS use for dynamic values)
(xt/q node ["SELECT * FROM users WHERE _id = ?" user-id])

;; Multiple parameters
(xt/q node ["SELECT * FROM options WHERE ticker = ? AND strike > ?" "AAPL" 150.0])
```

## Common Operations

```clojure
;; Aggregation
(xt/q node "SELECT ticker, SUM(volume) as total FROM trades GROUP BY ticker")

;; Order and limit
(xt/q node "SELECT * FROM users ORDER BY created_at DESC LIMIT 10")

;; Join
(xt/q node "SELECT o.*, u.name FROM orders o JOIN users u ON o.user_id = u._id")
```

## Transactions

```clojure
;; SQL INSERT
(xt/execute-tx node
  [["INSERT INTO users (_id, name) VALUES (?, ?)" "user-1" "Alice"]])

;; put-docs (Clojure-native)
(xt/execute-tx node
  [[:put-docs :users {:xt/id "user-1" :name "Alice"}]])

;; Batch insert
(xt/execute-tx node
  [[:sql "INSERT INTO users (_id, name) VALUES (?, ?)"
    ["user-1" "Alice"]
    ["user-2" "Bob"]]])

;; Delete
(xt/execute-tx node
  [["DELETE FROM users WHERE _id = ?" "user-1"]])
```

## Temporal Queries

```clojure
;; Query at specific valid-time
(xt/q node "SELECT * FROM users FOR VALID_TIME AS OF TIMESTAMP '2025-01-01T00:00:00Z'")

;; All history
(xt/q node "SELECT _id, name, _valid_from, _valid_to FROM users FOR ALL VALID_TIME")

;; Events since timestamp
(xt/q node ["SELECT * FROM events WHERE _valid_from > ? ORDER BY _valid_from" cutoff])
```

## Multi-Database (v2.1.0+)

For agent isolation, each namespace gets its own database.

```clojure
;; Attach a database (from primary 'xtdb' connection only)
(xt/execute-tx node
  [["ATTACH DATABASE seon_trading WITH $$
      log: !Local
        path: 'data/namespaces/seon.trading/log'
      storage: !Local
        path: 'data/namespaces/seon.trading/storage'
    $$"]])

;; Connect to specific database
(let [conn (-> (.createConnectionBuilder node)
               (.database "seon_trading")
               (.build))]
  (try
    (xt/q conn "SELECT * FROM signals")
    (finally
      (.close conn))))

;; Or use :database option
(xt/q node "SELECT * FROM signals" {:database :seon_trading})

;; Cross-database query
(xt/q node "SELECT * FROM seon_trading.signals s JOIN xtdb.users u ON s.user_id = u._id")

;; Detach
(xt/execute-tx node [["DETACH DATABASE seon_trading"]])
```

### Table Reference Syntax

| Form | Meaning |
|------|---------|
| `table` | `public.table` in current database |
| `database.table` | `public.table` in specified database |
| `database.schema.table` | `schema.table` in specified database |

## Column Name Mapping

| Clojure Keyword | SQL Column |
|-----------------|------------|
| `:xt/id` | `_id` |
| `:xt/valid-from` | `_valid_from` |
| `:xt/valid-to` | `_valid_to` |
| `:fn/namespace` | `fn$namespace` |
| `:asset/ticker` | `asset$ticker` |

## Key Files

| File | Purpose |
|------|---------|
| `src/seon/db/node.clj` | Query utilities |
| `src/seon/db/queries.clj` | Domain queries |
| `docs/reference/xtdb-v2-reference.md` | Full SQL reference |
| `docs/prds/xtdb-sql-migration/research/multi-database.md` | Multi-DB research |
| `reference-code/xtdb/` | XTDB v2.1.0 source code |

## For More Details

See `docs/reference/xtdb-v2-reference.md` for complete SQL reference, temporal queries, and multi-database patterns.
