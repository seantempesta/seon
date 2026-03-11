---
type: research
status: completed
tags: [research, archive, database]
---

# XTDB v2 Multi-Database Architecture Research

**Date**: 2025-12-13
**XTDB Version**: v2.1.0+
**Status**: Research Complete

---

## Executive Summary

XTDB v2.1 introduces **native multi-database support** via the `ATTACH DATABASE` command, enabling queries across independent databases/nodes. This capability is essential for Seon's multi-domain architecture (trading, health, finance, etc.).

### Key Findings

1. **Multi-DB queries ARE possible** in XTDB v2.1+
2. **SQL is required** for cross-database queries (not pure XTQL)
3. **Syntax**: Use `db_name.table_name` notation in SQL queries
4. **Consistency**: XTDB provides snapshot isolation across attached databases
5. **Architecture**: Seon should use **separate nodes per domain** + SQL for cross-domain queries

---

## XTDB v2.1 Multi-Database Capabilities

### ATTACH DATABASE Command

XTDB v2.1 allows one node to attach and query across multiple independent databases:

```sql
-- Attach a secondary database
ATTACH DATABASE trading_data WITH $$
  log: !Kafka
    cluster: 'prod-kafka'
    topic: 'xtdb.trading'
  storage: !S3
    bucket: 'trading-bucket'
    path: 'data'
$$

-- Attach health database
ATTACH DATABASE health_data WITH $$
  log: !Kafka
    cluster: 'prod-kafka'
    topic: 'xtdb.health'
  storage: !S3
    bucket: 'health-bucket'
    path: 'data'
$$

```

### Cross-Database Joins

Use `database_name.table_name` syntax in SQL queries:

```sql
-- Join trading data with health metrics
SELECT
  t.timestamp,
  t.ticker,
  t.pnl,
  h.stress_level,
  h.hrv
FROM trading_data.trades t
JOIN health_data.metrics h
  ON DATE(t.timestamp) = DATE(h.timestamp)
WHERE t.timestamp > CURRENT_DATE - INTERVAL 'P7D'
ORDER BY t.timestamp DESC

```

### Consistency Guarantees

**Critical advantage**: XTDB's immutable architecture provides **snapshot isolation** across attached databases without locks or performance penalties:

- Each query operates on a consistent snapshot across ALL attached databases
- No "tearing" of results (unlike traditional mutable SQL databases)
- `SHOW SNAPSHOT_TOKEN` generates a serializable token for read-your-writes consistency
- Tokens can be reused across nodes for distributed consistency

```sql
-- Get snapshot token for consistent reads
SHOW SNAPSHOT_TOKEN;

-- Use token in another transaction
BEGIN READ ONLY WITH (SNAPSHOT_TOKEN = 'abc123...');

```

---

## Recommended Architecture for Seon

### Option 1: Separate Nodes + SQL Joins (RECOMMENDED)

**Architecture**:
- Each domain (trading, health, finance) has its own XTDB node
- Functions take a `db` argument (the domain-specific node)
- Cross-domain queries use SQL with ATTACH DATABASE
- The `domain-db` function in `seon.core` provides domain isolation

**Advantages**:
- Clean separation of concerns
- Independent scaling per domain
- XTQL for single-domain queries (fast, native)
- SQL only when cross-domain joins are needed
- Aligns with XTDB's data-mesh architecture

**Implementation**:

```clojure
;; Query within a single domain (XTQL - native)
(defn get-recent-trades
  "Get recent trades for a ticker (single-domain query)."
  [db ticker]
  (node/query db
    (xt/template
      (-> (from :trades [{:ticker ~ticker} timestamp pnl])
          (order-by {:val timestamp :dir :desc})
          (limit 20)))))

;; Cross-domain query (SQL with ATTACH)
(defn get-trades-with-health
  "Join trading and health data (cross-domain query).

  NOTE: Requires ATTACH DATABASE health_data to be set up."
  [trading-db days]
  (node/sql-query trading-db
    (str "SELECT t.timestamp, t.ticker, t.pnl, h.stress_level, h.hrv "
         "FROM trades t "
         "JOIN health_data.metrics h ON DATE(t.timestamp) = DATE(h.timestamp) "
         "WHERE t.timestamp > CURRENT_DATE - INTERVAL 'P" days "D' "
         "ORDER BY t.timestamp DESC")))

```

**Domain Registry Pattern** (already implemented in `seon.core`):

```clojure
;; Register domains at startup
(register-domain! :trading trading-node {:description "Options trading"})
(register-domain! :health health-node {:description "Apple Health"})

;; Functions just take db, caller provides domain context
(defn analyze-trade [db trade-id]
  ;; Works with any domain's db
  (node/entity db :trades trade-id))

;; Caller determines which domain
(analyze-trade (domain-db :trading) "TRADE-123")  ; Trading domain
(analyze-trade (domain-db :health) "METRIC-456")  ; Health domain

```

### Option 2: Single Node with Multiple Tables

**Architecture**:
- One XTDB node with tables prefixed by domain (`:trading/trades`, `:health/metrics`)
- No ATTACH DATABASE needed
- XTQL for all queries

**Advantages**:
- Simpler setup (one node)
- Pure XTQL (no SQL needed)

**Disadvantages**:
- Poor separation of concerns
- Single point of failure
- Cannot scale domains independently
- Violates data-mesh principles
- Harder to manage different data lifecycles (trading = real-time, health = batch)

**Verdict**: Not recommended for Seon's use case.

---

## DSL Executor Component Analysis

### Current State

- Component: `:seon/dsl-executor` in `resources/system.edn` and `src/seon/system.clj`
- Implementation: `src/seon/trading/executor.clj` (moved from `dsl/` directory)
- Status: Component exists but **is not used anywhere** in actual code

### Usage Check

Searched entire codebase for references to `:seon/dsl-executor`:

```bash
# Results:
# - src/seon/system.clj (component definition)
# - resources/system.edn (component config)
# - docs/*.md (historical documentation)
# - ZERO usage in actual application code

```

### Decision: KEEP BUT UPDATE

**Rationale**:
1. The executor provides valuable DSL evaluation capabilities for LLM-generated trading rules
2. It's part of the reasoning agent architecture (future feature)
3. The component is harmless (minimal overhead)
4. The implementation has already been moved to correct namespace (`seon.trading.executor`)

**Action Required**:
- Component definition is already correct (references `seon.trading.executor` namespace)
- No changes needed to component itself
- Component will be used when reasoning agent is implemented

**No changes required** - component is correctly configured and ready for future use.

---

## Code Examples

### Test Case: Multi-Database Query Demo

Here's a test demonstrating cross-database queries (conceptual - requires ATTACH DATABASE setup):

```clojure
(ns seon.db.multi-db-test
  "Test cross-database queries using ATTACH DATABASE.

  NOTE: This test is conceptual. Full implementation requires:
  1. Support for ATTACH DATABASE in test fixtures
  2. Multiple test nodes or mock attached databases
  3. SQL query execution for cross-DB joins"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [seon.db.node :as node]
            [seon.test-utils :refer [with-test-node *test-node*]]
            [xtdb.api :as xt]))

;; This is a CONCEPTUAL test showing the pattern
;; Actual implementation would require ATTACH DATABASE setup

(deftest cross-database-query-concept-test
  (testing "Cross-database query pattern using SQL"
    ;; Setup: In production, you would have:
    ;; 1. Trading node (primary)
    ;; 2. Health node (attached as 'health_data')

    ;; Insert trading data
    (node/execute-tx! *test-node*
      [[:put-docs :trades
        {:xt/id "TRADE-1"
         :ticker "AAPL"
         :timestamp #inst "2025-12-13T10:00:00Z"
         :pnl 150.0}]])

    ;; In a real scenario with attached health database:
    ;;
    ;; (node/sql-query *test-node*
    ;;   "SELECT t.ticker, t.pnl, h.stress_level
    ;;    FROM trades t
    ;;    JOIN health_data.metrics h
    ;;      ON DATE(t.timestamp) = DATE(h.timestamp)
    ;;    WHERE t.ticker = 'AAPL'")

    ;; For now, demonstrate single-database query
    (let [results (node/sql-query *test-node*
                    "SELECT ticker, pnl FROM trades WHERE ticker = 'AAPL'")]
      (is (= 1 (count results)))
      (is (= "AAPL" (:ticker (first results))))
      (is (= 150.0 (:pnl (first results)))))))

(comment
  ;; Production usage pattern:

  ;; 1. Start primary node (trading)
  (def trading-node (xtn/start-node {...}))

  ;; 2. Attach health database
  (node/sql-query trading-node
    "ATTACH DATABASE health_data WITH $$
       log: !Kafka {...}
       storage: !S3 {...}
     $$")

  ;; 3. Query across databases
  (node/sql-query trading-node
    "SELECT t.timestamp, t.pnl, h.stress_level
     FROM trades t
     JOIN health_data.metrics h ON DATE(t.timestamp) = DATE(h.timestamp)
     WHERE t.timestamp > CURRENT_DATE - INTERVAL 'P7D'
     ORDER BY t.timestamp DESC")
  )

```

### Single-Domain Query Pattern (Current)

```clojure
;; Use XTQL for single-domain queries (recommended)
(defn get-high-iv-options
  "Get options with IV above threshold (single-domain query)."
  [db ticker min-iv]
  (node/query db
    (xt/template
      (-> (from :option-greeks
            [{:asset/ticker ~ticker} option/strike quote/iv greeks/delta])
          (where (>= quote/iv ~min-iv))
          (order-by {:val quote/iv :dir :desc})))))

;; Functions take db argument - caller provides domain context
(let [trading-db (domain-db :trading)]
  (get-high-iv-options trading-db "AAPL" 0.3))

```

---

## Implementation Roadmap

### Phase 1: Current Architecture (COMPLETED)

- [x] Separate domain nodes via `seon.core/domains` registry
- [x] `domain-db` function for domain isolation
- [x] Functions accept `db` parameter
- [x] XTQL for single-domain queries

### Phase 2: Cross-Domain Queries (FUTURE)

When cross-domain analytics are needed:

1. **Update system.edn**: Add ATTACH DATABASE configuration

   ```edn
   :seon/xtdb-node
   {:storage {:path "data/xtdb/trading"}
    :attached-databases
    [{:name "health_data"
      :log {:kafka {:cluster "..." :topic "..."}}
      :storage {:s3 {:bucket "..." :path "..."}}}]}

   ```

2. **Add ATTACH on startup**: Execute ATTACH DATABASE during node initialization

   ```clojure
   (defmethod ig/init-key :seon/xtdb-node [_ config]
     (let [node (xtn/start-node ...)
           attached (:attached-databases config)]
       (doseq [{:keys [name log storage]} attached]
         (attach-database! node name log storage))
       node))

   ```

3. **Create cross-domain query namespace**: `seon.db.cross-domain`

   ```clojure
   (ns seon.db.cross-domain
     "Cross-domain analytical queries using SQL ATTACH DATABASE.")

   (defn trades-with-health-metrics [trading-db days]
     (node/sql-query trading-db ...))

   ```

### Phase 3: Production Deployment (FUTURE)

- Set up Kafka topics per domain
- Configure S3/object storage per domain
- Implement snapshot token management for distributed consistency
- Add monitoring for cross-database query performance

---

## References

### Official Documentation

- [XTDB 2.1 ATTACH DATABASE Blog Post](https://xtdb.com/blog/attach-database)
- [XTDB v2.1.0 Release Notes](https://github.com/xtdb/xtdb/releases/tag/v2.1.0)
- [XTQL Queries Documentation](https://docs.xtdb.com/reference/main/xtql/queries.html)
- [XTDB SQL Queries Documentation](https://docs.xtdb.com/reference/main/sql/queries.html)
- [Launching XTDB v2 Announcement](https://xtdb.com/blog/launching-xtdb-v2)

### Key Concepts

- **Data Mesh Architecture**: Organize databases around business domains (orders, customers, products) while teams run their own compute clusters
- **Snapshot Isolation**: XTDB's immutable architecture provides consistent snapshots across attached databases without locks
- **Unify Operator**: XTQL's Datalog-based join mechanism (single-database only)
- **SQL + XTQL**: SQL required for cross-database queries; XTQL for single-database queries

---

## Conclusions

### Multi-Database Queries: SUPPORTED

XTDB v2.1+ fully supports cross-database queries via:
- `ATTACH DATABASE` command (SQL)
- `db_name.table_name` syntax in SQL joins
- Snapshot isolation for consistency
- No performance penalties from immutable architecture

### Recommended Pattern for Seon

1. **Single-domain queries**: Use XTQL (fast, native, already implemented)
2. **Cross-domain queries**: Use SQL with ATTACH DATABASE (when needed)
3. **Domain isolation**: Keep using `domain-db` pattern from `seon.core`
4. **Future-proof**: Architecture ready for cross-domain analytics when needed

### DSL Executor Component: KEEP AS-IS

- Component is correctly configured
- Implementation moved to `seon.trading.executor`
- Not currently used but ready for reasoning agent feature
- No changes required

---

## Changes Made

### Code Changes: NONE REQUIRED

- DSL executor component is already correctly configured
- Domain registry pattern already implemented in `seon.core`
- `seon.db.node` already supports both XTQL and SQL queries
- Architecture is already optimal for future multi-DB features

### Documentation Created

- This document: `docs/prds/seon-transform/research/xtdb-multi-db-architecture.md`

---

## Next Steps

1. **No immediate code changes needed** - current architecture is optimal
2. **When cross-domain queries are needed**:
   - Add ATTACH DATABASE configuration to `system.edn`
   - Create `seon.db.cross-domain` namespace for cross-domain queries
   - Use SQL queries with `db_name.table_name` syntax
3. **Continue using current patterns**:
   - XTQL for single-domain queries
   - Functions accept `db` parameter
   - `domain-db` for domain isolation
