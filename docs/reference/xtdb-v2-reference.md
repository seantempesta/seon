# XTDB v2 Reference Guide

**Version**: 2.1.0-rc0 | **Last Updated**: 2025-11-29

---

## Quick Start

```clojure
(require '[ml-options.db.node :as node]
         '[xtdb.api :as xt])

;; Query with XTQL (use node/query, NOT xt/q)
(node/query my-node '(from :option-quotes [ticker strike iv]))

;; With dynamic values
(let [ticker "AAPL"]
  (node/query my-node
    (xt/template (from :option-quotes [{:ticker ~ticker} strike iv]))))

;; Insert data
(xt/execute-tx my-node
  [[:put-docs :option-quotes {:xt/id "opt1" :ticker "AAPL" :strike 150.0}]])
```

---

## Table of Contents

1. [XTQL Queries](#xtql-queries) - **READ THIS FIRST**
2. [XTQL Gotchas](#xtql-gotchas) - Common pitfalls
3. [Transactions](#transactions)
4. [Temporal Queries](#temporal-queries)
5. [Node Setup](#node-setup)
6. [Data Types](#data-types)
7. [Quick Reference](#quick-reference)

---

## CRITICAL: SQL Queries vs XTQL

**As of 2025-12-29**: In this project, **use SQL queries directly** rather than XTQL.

XTQL queries via `xt/q` or `node/xtql-query` currently fail with:
```
No implementation of method: :plan-query of protocol: #'xtdb.xtql.plan/PlanQuery found for class: xtdb.xtql.From
```

### What Works

```clojure
;; SQL queries work perfectly
(xt/q node "SELECT * FROM users WHERE name = 'Alice'")

;; Parameterized SQL queries
(xt/q node ["SELECT * FROM users WHERE _id = ?" user-id])

;; Keywords work as parameters for _id
(xt/q node ["SELECT * FROM function WHERE _id = ?" :seon.ai.gemini/ask])
```

### What Doesn't Work

```clojure
;; XTQL queries fail - DO NOT USE
(xt/q node '(from :users [name email]))
(node/xtql-query node '(from :users [name email]))
```

### Column Name Mapping

XTDB maps namespaced keywords to SQL column names:
- `:fn/namespace` → `fn$namespace` in SQL
- `:fn/first-seen` → `fn$first_seen` in SQL
- `:xt/id` → `_id` in SQL
- `:xt/valid-from` → `_valid_from` in SQL

**Filtering on namespaced columns**: The SQL syntax for filtering on columns like `fn$namespace` is complex. The simpler approach is to:
1. Fetch all rows: `SELECT * FROM table`
2. Filter in Clojure: `(filter #(= value (:col %)) results)`

### Temporal Queries in SQL

```clojure
;; Filter by valid-time (parameterized)
(xt/q node ["SELECT * FROM edit_event WHERE _valid_from > ? ORDER BY _valid_from" cutoff-instant])

;; Query at a specific point in time
(xt/q node "SELECT * FROM users FOR VALID_TIME AS OF TIMESTAMP '2025-01-01T00:00:00Z'")
```

---

## XTQL Queries (DEPRECATED - USE SQL INSTEAD)

### Why node/query, Not xt/q?

The public `xt/q` wraps XTQL in SQL strings and sends via JDBC. Our `ml-options.db.node/query` uses `xtp/open-xtql-query` directly for native execution without serialization overhead.

**NOTE**: As documented above, XTQL is currently broken. Use SQL instead.

```clojure
;; CORRECT - use SQL
(xt/q my-node "SELECT * FROM users")

;; BROKEN - XTQL doesn't work
(node/query my-node '(from :users [name email]))
```

### Basic Queries

```clojure
;; Select columns (MUST list explicitly - [*] returns empty maps!)
(node/query node '(from :option-quotes [ticker strike iv delta gamma]))

;; Pipeline with operators
(node/query node
  '(-> (from :option-quotes [ticker strike iv])
       (where (> iv 0.3))
       (order-by strike)
       (limit 10)))
```

### Dynamic Values with xt/template

Use `xt/template` to inject Clojure values into queries:

```clojure
(let [ticker "AAPL"
      min-strike 140.0]
  (node/query node
    (xt/template
      (-> (from :option-quotes [{:ticker ~ticker} strike iv delta])
          (where (>= strike ~min-strike))))))
```

### Filtering

**Inline binding** (equality) - in the `from` clause:
```clojure
;; Filter where ticker = "AAPL"
(from :option-quotes [{:ticker "AAPL"} strike iv])

;; Multiple equality filters
(from :option-quotes [{:ticker "AAPL" :option_type :call} strike iv])
```

**Where clause** (comparisons):
```clojure
(-> (from :option-quotes [{:ticker "AAPL"} strike gamma])
    (where (>= gamma 0.05)
           (<= gamma 0.15)))
```

### Order By

```clojure
;; Ascending (default)
(order-by strike)

;; Descending
(order-by {:val strike :dir :desc})

;; Multiple columns
(order-by expiry {:val strike :dir :desc})
```

### Aggregation

```clojure
;; Group by strike, sum open interest
(-> (from :option-quotes [strike open_interest])
    (aggregate {:total_oi (sum open_interest)} strike))

;; Multiple aggregations
(-> (from :option-quotes [ticker volume open_interest])
    (aggregate {:vol (sum volume) :oi (sum open_interest)} ticker))

;; No group-by (aggregate all)
(-> (from :option-quotes [volume])
    (aggregate {:total (sum volume)}))
```

**Available**: `sum`, `avg`, `count`, `min`, `max`, `array-agg`

### Computed Columns

```clojure
(-> (from :option-quotes [strike bid ask])
    (with {:mid (/ (+ bid ask) 2)
           :spread (- ask bid)}))
```

### Joins (Unify)

```clojure
(unify (from :option-quotes [{:xt/id opt-id} ticker strike iv])
       (from :underlying [{:ticker ticker} spot]))
;; 'ticker' unifies the two tables
```

---

## XTQL Gotchas

### 1. [*] Returns Empty Maps
```clojure
;; WRONG - returns [{}]
(from :table [*])

;; CORRECT - list columns explicitly
(from :table [xt/id ticker strike iv delta gamma])
```

### 2. order-by Takes Symbol, NOT Vector
```clojure
;; WRONG
(order-by [strike :asc])

;; CORRECT
(order-by strike)
(order-by {:val strike :dir :desc})
```

### 3. aggregate Group-By is Symbol After Map
```clojure
;; WRONG
(aggregate {:total (sum vol)} [ticker])

;; CORRECT - group-by column is bare symbol after the map
(aggregate {:total (sum vol)} ticker)
```

### 4. Use xt/template for Dynamic Values
```clojure
;; WRONG - variable not in scope
(from :table [{:ticker ticker-var} strike])

;; CORRECT
(xt/template (from :table [{:ticker ~ticker-var} strike]))
```

### 5. Inline Binding for Equality Only
```clojure
;; Equality - use inline binding
(from :table [{:status "active"} name])

;; Range - use where clause
(-> (from :table [price name])
    (where (> price 100)))
```

---

## Transactions

### Insert (put-docs)

```clojure
(xt/execute-tx node
  [[:put-docs :option-quotes
    {:xt/id "AAPL230616C00150000"
     :ticker "AAPL"
     :strike 150.0
     :option_type :call
     :iv 0.25}]])

;; With valid-time (backdate)
(xt/execute-tx node
  [[:put-docs :option-quotes
    {:xt/id "opt1"
     :ticker "AAPL"
     :xt/valid-from #inst "2023-06-01"}]])
```

### Delete

```clojure
;; Delete from now onward
(xt/execute-tx node [[:delete-docs :option-quotes "opt1"]])

;; Delete for time range
(xt/execute-tx node
  [[:delete-docs :option-quotes "opt1"
    {:valid-from #inst "2023-01-01"
     :valid-to #inst "2023-06-01"}]])
```

### Erase (Permanent - GDPR)

```clojure
;; WARNING: Irreversibly removes all history
(xt/execute-tx node [[:erase-docs :users "user-id"]])
```

### Batch Insert

```clojure
(xt/execute-tx node
  [[:put-docs :option-quotes
    {:xt/id "opt1" :ticker "AAPL" :strike 150.0}
    {:xt/id "opt2" :ticker "AAPL" :strike 155.0}
    {:xt/id "opt3" :ticker "AAPL" :strike 160.0}]])
```

---

## Temporal Queries

XTDB maintains two timelines:
- **Valid Time** (`current-time`): When the fact is true in the real world
- **System Time** (`snapshot-time`): When XTDB recorded it

### Query at Valid Time

```clojure
;; What was true on 2023-06-01?
(node/query node
  '(from :option-quotes [ticker strike iv])
  {:current-time #inst "2023-06-01"})
```

### Query at System Time

```clojure
;; What did we know on 2023-06-01?
(node/query node
  '(from :option-quotes [ticker strike iv])
  {:snapshot-time #inst "2023-06-01"})
```

### Bitemporal Query

```clojure
;; What did we know on Dec 31 about June 1?
(node/query node
  '(from :option-quotes [ticker strike iv])
  {:current-time #inst "2023-06-01"
   :snapshot-time #inst "2023-12-31"})
```

### Query All History

```clojure
;; All valid-time history
(node/query node
  '(from :option-quotes {:for-valid-time :all-time}
         [xt/id ticker strike xt/valid-from xt/valid-to]))

;; Full bitemporal history
(node/query node
  '(from :option-quotes {:for-valid-time :all-time
                         :for-system-time :all-time}
         [xt/id ticker xt/valid-from xt/system-from]))
```

---

## Node Setup

### In-Memory (Development)

```clojure
(require '[xtdb.node :as xtn])

(def node (xtn/start-node))
;; Data lost when closed
```

### Persistent

```clojure
(def node (xtn/start-node
            {:log-dir "xtdb-data/log"
             :storage-dir "xtdb-data/storage"}))
```

### JVM Requirements

Requires JDK 21+ with flags:
```
--add-opens=java.base/java.nio=ALL-UNNAMED
--enabled-native-access=ALL-UNNAMED
-Dio.netty.tryReflectionSetAccessible=true
```

---

## Data Types

| Clojure | XTDB | Notes |
|---------|------|-------|
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

| Pattern | XTQL |
|---------|------|
| Select columns | `(from :t [col1 col2])` |
| Equality filter | `(from :t [{:field val} col])` |
| Dynamic value | `(xt/template (from :t [{:f ~var}]))` |
| Range filter | `(-> (from :t [c]) (where (> c 10)))` |
| Order asc | `(order-by col)` |
| Order desc | `(order-by {:val col :dir :desc})` |
| Aggregate | `(aggregate {:sum (sum c)} group-col)` |
| Computed | `(with {:new (+ a b)})` |
| Limit | `(limit 10)` |
| Valid-time | `{:current-time #inst "..."}` |
| System-time | `{:snapshot-time #inst "..."}` |
| All history | `(from :t {:for-valid-time :all-time} [...])` |

### Transaction Patterns

| Operation | Syntax |
|-----------|--------|
| Insert | `[:put-docs :table {:xt/id "id" ...}]` |
| Insert w/ time | `[:put-docs :table {:xt/id "id" :xt/valid-from #inst "..."}]` |
| Delete | `[:delete-docs :table "id"]` |
| Erase | `[:erase-docs :table "id"]` |

### Common Queries

```clojure
;; Find by ID
(first (node/query node (xt/template (from :table [{:xt/id ~id}]))))

;; ATM options (within 5% of spot)
(let [lower (* spot 0.95) upper (* spot 1.05)]
  (node/query node
    (xt/template
      (-> (from :option-quotes [{:ticker ~ticker} strike iv delta])
          (where (>= strike ~lower) (<= strike ~upper))))))

;; Aggregate OI by strike
(node/query node
  (xt/template
    (-> (from :option-quotes [{:ticker ~ticker} strike open_interest])
        (aggregate {:total_oi (sum open_interest)} strike)
        (order-by strike))))
```

---

## Bulk Loading and Data Management

See [XTDB Bulk Loading Guide](xtdb-bulk-loading.md) for comprehensive documentation on:
- Export/import procedures
- Compaction strategies
- Performance monitoring
- Troubleshooting
- Backup/restore procedures

## Files

- `src/ml_options/db/node.clj` - Query wrapper (use this)
- `src/ml_options/db/queries.clj` - Domain queries
- `src/ml_options/data/bulk_load.clj` - Bulk load CLI with resilience
- `reference-code/xtdb/` - XTDB v2.1.0-rc0 source
