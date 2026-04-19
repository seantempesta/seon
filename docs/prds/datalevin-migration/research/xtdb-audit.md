---
type: research
status: draft
tags: [prd, research, database]
---
# XTDB Usage Audit

**Phase 1: Internal Code Audit**

This document catalogs all XTDB usage in the Seon codebase to inform the Datalevin migration strategy.

## Summary Table

| File | XTDB Functions Used | Entity Types | Temporal? |
|------|---------------------|--------------|-----------|
| `src/seon/db/node.clj` | `xt/q`, `xt/execute-tx` | Generic (any table) | Yes |
| `src/seon/db/queries.clj` | `xt/q` (via node/q) | `option_greeks`, `ingestion_state` | Yes |
| `src/seon/db/multi.clj` | `xt/execute-tx`, `xt/q`, `.submitTx` | Database metadata | No |
| `src/seon/db/transactions.clj` | `xt/execute-tx` | Generic (any table) | No |
| `src/seon/trading/bulk_load.clj` | `xt/execute-tx` (via node/execute-tx!) | `option_greeks`, `bulk_load_status` | Yes |
| `src/seon/trading/ingestion_state.clj` | node/q, node/execute-tx!, node/entity | `ingestion_state` | No |
| `src/seon/trading/signals.clj` | node/q | `option_greeks` | Yes |
| `src/seon/trading/analysis.clj` | node/q | `option_greeks` | No |
| `src/seon/trading/ingest.clj` | node/execute-tx!, node/q | `option_greeks`, `ingestion_state` | No |
| `src/seon/ai.clj` | `xt/execute-tx`, `xt/q` | `ai_sessions`, `ai_messages` | No |
| `src/seon/ai/agent.clj` | Uses seon.ai functions | `ai_sessions`, `ai_messages` | No |
| `src/seon/ai/agent/log.clj` | `xt/execute-tx` | `ai_messages` | No |
| `src/seon/ai/claude.clj` | Uses seon.ai functions | `ai_sessions`, `ai_messages` | No |
| `src/seon/agent/ctx.clj` | `xt/execute-tx`, `xt/q` | `ctx_snapshots` | Yes |
| `src/seon/agent/helpers.clj` | node/q | Generic queries | No |
| `src/seon/orchestrator/session.clj` | `xt/execute-tx`, `xt/q` | `sessions` | No |
| `src/seon/primer/ctx.clj` | node/execute-tx!, node/entity | `primer_sessions` | Yes |
| `src/seon/dev/context.clj` | node/execute-tx!, node/sql-query | `edit_event`, `review_event`, `todo_event` | Yes |
| `src/seon/web/agents.clj` | db/q (via seon.db.node) | `ai_sessions`, `ai_messages` | No |
| `src/seon/web/stats.clj` | node/q | `option_greeks` | No |
| `src/seon/health.clj` | `xt/q` | Health check queries | No |
| `src/seon/system.clj` | `xt/start-node` | System initialization | No |

---

## Detailed Audit by File

### Core Database Layer (`src/seon/db/`)

#### `src/seon/db/node.clj`

**Purpose:** Core XTDB wrapper providing the primary database interface.

**XTDB Operations:**

- `xt/q` - SQL query execution (primary query function)
- `xt/execute-tx` - Synchronous transaction execution

**Functions:**

- `q` - Execute SQL queries with parameter binding
- `query` - Legacy query router (throws on XTQL, routes SQL)
- `sql-query` - Direct SQL string execution
- `entity` - Retrieve single document by ID
- `entity-history` - Retrieve all versions of a document
- `execute-tx!` - Execute transactions synchronously
- `put!` - Insert/update documents
- `delete!` - Delete documents

**Entity Types:** Generic - works with any table

**Temporal Queries:** **YES**

- `entity` supports `{:current-time instant}` option for point-in-time queries
- `entity-history` uses `FOR ALL VALID_TIME` to get all versions

```clojure
;; Example temporal query from entity-history
(str "SELECT * FROM " table-name " FOR ALL VALID_TIME WHERE _id = ?")

```

---

#### `src/seon/db/queries.clj`

**Purpose:** Domain-specific query functions for trading data.

**XTDB Operations:** Uses `node/q` for all queries

**Functions:**

- `get-all-quotes` - Get current quotes
- `get-quotes-by-ticker` - Filter quotes by ticker
- `iv-time-series` - Implied volatility history with temporal queries
- `get-ingestion-state` / `set-ingestion-state!` - Track data ingestion

**Entity Types:** `option_greeks`, `ingestion_state`

**Temporal Queries:** **YES**

- `iv-time-series` uses `FOR ALL VALID_TIME` with `_valid_from` filtering

```clojure
;; From iv-time-series
["SELECT asset$ticker, quote$iv, greeks$delta, _valid_from, _valid_to
  FROM option_greeks FOR ALL VALID_TIME
  WHERE asset$ticker = ?
    AND _valid_to IS NULL
    AND _valid_from >= ?"
 ticker start-instant]

```

---

#### `src/seon/db/multi.clj`

**Purpose:** Multi-database management for namespace isolation.

**XTDB Operations:**

- `xt/execute-tx` - Create databases
- `xt/q` - List databases
- `.submitTx` - Raw connection transaction submission
- `.openConnection` - Create database connections

**Functions:**

- `ensure-namespace-db!` - Create/attach database for namespace
- `attach-namespace-db!` - Attach existing database
- `create-namespace-connection` - Get connection to namespace DB
- `list-namespace-dbs` - List all namespace databases

**Entity Types:** Database metadata only

**Temporal Queries:** **NO**

---

#### `src/seon/db/transactions.clj`

**Purpose:** Transaction builders for batch operations.

**XTDB Operations:**

- Uses `node/execute-tx!` for transaction execution

**Functions:**

- `make-put-tx` - Build put transaction
- `make-delete-tx` - Build delete transaction
- `batch-put!` - Batch insert with chunking
- `batch-delete!` - Batch delete with chunking

**Entity Types:** Generic

**Temporal Queries:** **NO**

---

### Trading Domain (`src/seon/trading/`)

#### `src/seon/trading/bulk_load.clj`

**Purpose:** Bulk loading historical options data.

**XTDB Operations:**

- `xt/execute-tx` (via node/execute-tx!)
- Uses `:put-docs` transaction operations

**Entity Types:** `option_greeks`, `bulk_load_status`

**Temporal Queries:** **YES** - Sets `xt/valid-from` on bulk loaded data

```clojure
;; Sets explicit valid-from for temporal history
:xt/valid-from valid-time

```

---

#### `src/seon/trading/ingestion_state.clj`

**Purpose:** Track data ingestion progress.

**XTDB Operations:**

- `node/q` - Query state
- `node/execute-tx!` - Update state
- `node/entity` - Get single entity

**Entity Types:** `ingestion_state`

**Temporal Queries:** **NO**

---

#### `src/seon/trading/signals.clj`

**Purpose:** Trading signal generation based on options data.

**XTDB Operations:**

- `node/q` - Query options data

**Functions:**

- `detect-volatility-anomalies` - Find IV anomalies with temporal range

**Entity Types:** `option_greeks`

**Temporal Queries:** **YES** - Filters by `_valid_from`

```clojure
["SELECT asset$ticker, quote$iv, greeks$delta, _valid_from
  FROM option_greeks
  WHERE _valid_from >= ?
  ORDER BY _valid_from DESC"
 since-instant]

```

---

#### `src/seon/trading/analysis.clj`

**Purpose:** Market analysis functions.

**XTDB Operations:**

- `node/q` - Query for analysis

**Entity Types:** `option_greeks`

**Temporal Queries:** **NO** - Only current state queries

---

#### `src/seon/trading/ingest.clj`

**Purpose:** Real-time data ingestion from CBOE.

**XTDB Operations:**

- `node/execute-tx!` - Insert new quotes
- `node/q` - Check existing data

**Entity Types:** `option_greeks`, `ingestion_state`

**Temporal Queries:** **NO** - Uses current time for valid-from

---

### AI/Agent System (`src/seon/ai/`, `src/seon/agent/`)

#### `src/seon/ai.clj`

**Purpose:** Core AI session and message persistence.

**XTDB Operations:**

- `xt/execute-tx` - Store sessions and messages
- `xt/q` - List sessions, query messages

**Functions:**

- `save-session!` - Persist session to XTDB
- `update-session!` - Update session status
- `save-message!` - Store AI message
- `list-sessions` - Get recent sessions
- `get-session-messages` - Get messages for session

**Entity Types:** `ai_sessions`, `ai_messages`

**Temporal Queries:** **NO** - Uses `_valid_from` for ordering only, not temporal queries

---

#### `src/seon/ai/agent.clj`

**Purpose:** Agent registry and lifecycle management.

**XTDB Operations:** Delegates to `seon.ai` functions

**Entity Types:** Same as `seon.ai`

**Temporal Queries:** **NO**

---

#### `src/seon/ai/agent/log.clj`

**Purpose:** Agent log file management.

**XTDB Operations:**

- `xt/execute-tx` - Store log messages (optional persistence)

**Entity Types:** `ai_messages`

**Temporal Queries:** **NO**

---

#### `src/seon/ai/claude.clj`

**Purpose:** Claude provider implementation.

**XTDB Operations:** Uses `seon.ai` functions for persistence

**Entity Types:** Same as `seon.ai`

**Temporal Queries:** **NO**

---

#### `src/seon/agent/ctx.clj`

**Purpose:** Persisted context atom with time-travel.

**XTDB Operations:**

- `xt/execute-tx` - Persist ctx snapshots
- `xt/q` - Load snapshots, time-travel queries

**Functions:**

- `make-persisted-ctx` - Create auto-persisting atom
- `at` - Time-travel query
- `history` - Get all historical snapshots
- `restore!` - Restore to historical state
- `load-latest` - Load most recent state

**Entity Types:** `ctx_snapshots`

**Temporal Queries:** **YES** - Heavy use of temporal features

```clojure
;; Point-in-time query
"SELECT state, _system_from FROM ctx_snapshots
 FOR SYSTEM_TIME AS OF ?
 WHERE namespace = ?
 ORDER BY _system_from DESC LIMIT 1"

;; Full history query
"SELECT _system_from, state FROM ctx_snapshots
 FOR ALL SYSTEM_TIME
 WHERE namespace = ?
 ORDER BY _system_from"

```

---

#### `src/seon/agent/helpers.clj`

**Purpose:** Helper functions for agents.

**XTDB Operations:**

- `node/q` - Generic queries

**Entity Types:** Various

**Temporal Queries:** **NO**

---

### Orchestrator (`src/seon/orchestrator/`)

#### `src/seon/orchestrator/session.clj`

**Purpose:** Agent session lifecycle management.

**XTDB Operations:**

- `xt/execute-tx` - Store/update sessions
- `xt/q` - List sessions, lookup

**Functions:**

- `start-agent-session!` - Create new session
- `stop-agent-session!` - Stop and cleanup
- `get-agent-session` - Lookup session
- `list-agent-sessions` - List active sessions
- `recover-sessions!` - Handle orphaned sessions

**Entity Types:** `sessions`

**Temporal Queries:** **NO** - Stores timestamps but doesn't query temporally

---

### Primer (`src/seon/primer/`)

#### `src/seon/primer/ctx.clj`

**Purpose:** Multi-session context management with XTDB persistence.

**XTDB Operations:**

- `node/execute-tx!` - Checkpoint sessions
- `node/entity` - Load session
- `node/entity-history` - Get session history

**Entity Types:** `primer_sessions`

**Temporal Queries:** **YES**

- Uses `entity-history` for temporal queries
- `load-at!` supports `{:current-time instant}` option

---

### Dev Hook System (`src/seon/dev/`)

#### `src/seon/dev/context.clj`

**Purpose:** Development hook context tracking.

**XTDB Operations:**

- `node/execute-tx!` - Record events
- `node/sql-query` - Query events

**Functions:**

- `record-edit!` - Track file edits
- `record-review!` - Track reviews
- `record-todos!` - Track agent todos
- `edits-since-last-review` - Query with temporal filtering
- `reviews-in-range` - Time-range queries

**Entity Types:** `edit_event`, `review_event`, `todo_event`

**Temporal Queries:** **YES** - Uses `_valid_from` extensively

```clojure
;; Uses _valid_from for ordering and filtering
"SELECT *, _valid_from FROM edit_event WHERE _valid_from > ? ORDER BY _valid_from"
"SELECT *, _valid_from FROM review_event WHERE _valid_from >= ? AND _valid_from <= ?"

```

---

### Web Layer (`src/seon/web/`)

#### `src/seon/web/agents.clj`

**Purpose:** Agent Observatory UI.

**XTDB Operations:**

- `db/q` - Query sessions and messages

**Functions:**

- `find-ai-session-id` - Lookup AI session
- `load-session-messages` - Get messages for display
- `completed-sessions` - List completed sessions

**Entity Types:** `ai_sessions`, `ai_messages`

**Temporal Queries:** **NO**

---

#### `src/seon/web/stats.clj`

**Purpose:** Trading statistics dashboard.

**XTDB Operations:**

- `node/q` - Query options data

**Entity Types:** `option_greeks`

**Temporal Queries:** **NO**

---

### System (`src/seon/`)

#### `src/seon/health.clj`

**Purpose:** Health checks and cleanup.

**XTDB Operations:**

- `xt/q` - Simple query for health check

**Entity Types:** None specific (uses simple ping query)

**Temporal Queries:** **NO**

---

#### `src/seon/system.clj`

**Purpose:** Integrant system configuration.

**XTDB Operations:**

- `xt/start-node` - Initialize XTDB node

**Entity Types:** N/A (initialization only)

**Temporal Queries:** **NO**

---

## Temporal Query Patterns Summary

### Files Using Temporal Features

| File | Temporal Pattern | Purpose |
|------|-----------------|---------|
| `db/node.clj` | `FOR ALL VALID_TIME` | Entity history retrieval |
| `db/queries.clj` | `FOR ALL VALID_TIME` + `_valid_from` filtering | IV time series |
| `trading/bulk_load.clj` | Sets `xt/valid-from` | Historical data loading |
| `trading/signals.clj` | `_valid_from` filtering | Anomaly detection window |
| `agent/ctx.clj` | `FOR SYSTEM_TIME AS OF`, `FOR ALL SYSTEM_TIME` | Time-travel, history |
| `primer/ctx.clj` | `entity-history`, `{:current-time}` | Session history |
| `dev/context.clj` | `_valid_from` ordering/filtering | Event timestamps |

### Temporal Query Types Used

1. **Point-in-time queries:**
   - `FOR VALID_TIME AS OF TIMESTAMP '...'`
   - `FOR SYSTEM_TIME AS OF ?`
   - `{:current-time instant}` option in entity lookup

2. **Full history queries:**
   - `FOR ALL VALID_TIME`
   - `FOR ALL SYSTEM_TIME`

3. **Time range filtering:**
   - `WHERE _valid_from > ?`
   - `WHERE _valid_from >= ? AND _valid_from <= ?`

4. **Explicit valid-time insertion:**
   - `:xt/valid-from` in document data

---

## Entity Types Summary

| Entity Type | Purpose | Used By |
|-------------|---------|---------|
| `option_greeks` | Options pricing data | trading/* |
| `ingestion_state` | Data ingestion tracking | trading/ingestion_state, ingest |
| `bulk_load_status` | Bulk load progress | trading/bulk_load |
| `ai_sessions` | AI agent sessions | ai.clj, ai/agent.clj, web/agents.clj |
| `ai_messages` | AI message history | ai.clj, ai/agent/log.clj, web/agents.clj |
| `ctx_snapshots` | Agent context snapshots | agent/ctx.clj |
| `primer_sessions` | Primer session state | primer/ctx.clj |
| `sessions` | Orchestrator sessions | orchestrator/session.clj |
| `edit_event` | File edit tracking | dev/context.clj |
| `review_event` | Code review tracking | dev/context.clj |
| `todo_event` | Agent todo tracking | dev/context.clj |

---

## Migration Considerations

### High-Impact Areas (Require Temporal Support)

1. **agent/ctx.clj** - Core time-travel functionality
2. **db/queries.clj** - IV time series analysis
3. **trading/signals.clj** - Temporal anomaly detection
4. **trading/bulk_load.clj** - Historical data with explicit valid-times

### Medium-Impact Areas (Use Timestamps)

1. **dev/context.clj** - Event ordering by `_valid_from`
2. **ai.clj** - Message ordering

### Low-Impact Areas (No Temporal)

1. **orchestrator/session.clj** - Simple CRUD
2. **trading/ingestion_state.clj** - Simple state tracking
3. **web/agents.clj** - Display queries

### Abstraction Layer Opportunity

All production code goes through `seon.db.node` wrapper. The migration can:

1. Keep the `node/q`, `node/execute-tx!` interface
2. Swap XTDB implementation for Datalevin underneath
3. Handle temporal → explicit column mapping at this layer
