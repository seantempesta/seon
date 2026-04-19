---
type: research
status: completed
tags: [research, archive, database]
---

# XTDB SQL Syntax & Patterns Investigation

**Status**: Not Started
**Goal**: Document XTDB's SQL dialect for domain code standardization

---

## Context

Domain code (trading, health, finance) will use SQL for LLM accessibility. This document captures the SQL patterns and conventions.

## Research Tasks

### 1. Column Naming

- [ ] How are namespaced keywords stored? (`asset/ticker` → `asset_ticker`?)
- [ ] Test: Insert with `{:asset/ticker "AAPL"}`, query with SQL
- [ ] Document the exact mapping

### 2. Parameterized Queries

- [ ] Positional parameters (`?`)
- [ ] Vector syntax `["SELECT ... WHERE x = ?" value]`
- [ ] Type handling for each Clojure type

### 3. Temporal Syntax (for reference - domains won't use this)

- [ ] `FOR VALID_TIME`
- [ ] `FOR SYSTEM_TIME`
- [ ] `FOR ALL VALID_TIME`

### 4. Aggregations

- [ ] `GROUP BY` syntax
- [ ] Available functions: `COUNT`, `SUM`, `AVG`, `MIN`, `MAX`
- [ ] `HAVING` clause

### 5. Type Handling

- [ ] Keywords (`:call`, `:put`) - stored as strings?
- [ ] Instants / timestamps
- [ ] UUIDs
- [ ] Vectors / arrays
- [ ] Maps / nested objects

---

## Test Queries

```clojure
(require '[seon.db.node :as node])

(def db (xtdb-node))

;; What columns exist?
(node/sql-query db "SELECT * FROM option_greeks LIMIT 1")

;; Parameterized
(node/sql-query db ["SELECT * FROM option_greeks WHERE asset_ticker = ? LIMIT 5" "AAPL"])

;; Aggregation
(node/sql-query db
  "SELECT asset_ticker, COUNT(*) as cnt
   FROM option_greeks
   GROUP BY asset_ticker
   ORDER BY cnt DESC")

```

---

## Findings (Updated 2025-12-29)

### Column Naming Convention

**IMPORTANT**: XTDB uses `$` (not `_`) for namespaced keywords:

| Clojure | SQL Column |
|---------|------------|
| `:fn/namespace` | `fn$namespace` |
| `:fn/first-seen` | `fn$first_seen` |
| `:edit/file` | `edit$file` |
| `:xt/id` | `_id` |
| `:xt/valid-from` | `_valid_from` |
| `:xt/valid-to` | `_valid_to` |

### Parameterized Query Syntax

```clojure
;; Positional parameters work
(xt/q node ["SELECT * FROM users WHERE _id = ?" :user/alice])

;; Keywords work as parameters for _id
(xt/q node ["SELECT * FROM function WHERE _id = ?" :seon.ai.gemini/ask])

;; Instants work for temporal filtering
(xt/q node ["SELECT * FROM edit_event WHERE _valid_from > ?" cutoff-instant])

```

### Filtering on Namespaced Columns

Filtering on columns like `fn$namespace` in SQL is complex. Simpler approach:

```clojure
;; Fetch all, filter in Clojure
(->> (xt/q node "SELECT * FROM function")
     (filter #(= :seon.foo (:fn/namespace %))))

```

### Type Mapping

| Clojure Type | SQL Type | Notes |
|--------------|----------|-------|
| String | VARCHAR | |
| Long | BIGINT | |
| Double | DOUBLE | |
| Keyword | VARCHAR | Stored/returned as keyword |
| Instant | TIMESTAMP | Use `#inst` or `Instant` |
| UUID | UUID | |
| Set | ARRAY | |
| Map | OBJECT | Nested docs |

### XTQL vs SQL Status

**As of 2025-12-29**: XTQL partially works via `node/query` but has edge cases that fail.
SQL via `xt/q` works reliably. Since XTQL compiles to SQL anyway, prefer SQL directly.

---

## SQL Style Guide (Draft)

*(To be refined based on findings)*

```sql
-- Naming: snake_case for columns
SELECT asset_ticker, quote_iv, greeks_delta
FROM option_greeks
WHERE asset_ticker = ?
  AND greeks_delta >= ?
ORDER BY quote_iv DESC
LIMIT 100;

-- Aggregations
SELECT asset_ticker,
       COUNT(*) as record_count,
       AVG(quote_iv) as avg_iv
FROM option_greeks
GROUP BY asset_ticker
HAVING COUNT(*) > 100;

```
