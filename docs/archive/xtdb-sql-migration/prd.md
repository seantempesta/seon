> **Status: ARCHIVED** — Complete — SQL migration done

> **Status: ARCHIVED** — Complete — SQL migration done

# XTDB SQL Migration & Multi-Database Architecture

**Status**: COMPLETE (All Phases Done)
**Last Updated**: 2026-01-04
**Priority**: High (blocks agent isolation work) - UNBLOCKED

---

## Quick Reference (for agents)

### Current System State

Single XTDB node with 3 attached databases:
- `xtdb` - Primary orchestrator database
- `seon_primer` - Primer sessions (was separate node)
- `seon_dev` - Dev hook events (was separate node)

Verify with: `clj-nrepl-eval -p 7888 "(status)"`

### Key Files

| File | Purpose |
|------|---------|
| `src/seon/db/multi.clj` | Multi-database API (attach, detach, connections) |
| `src/seon/db/node.clj` | SQL query wrapper (`node/q`) |
| `src/seon/system.clj` | Integrant components |
| `resources/system.edn` | System configuration |
| `test/seon/db/multi_test.clj` | Multi-db tests (16 tests) |

### Multi-Database API

```clojure
(require '[seon.db.multi :as multi])

;; Naming conversions
(multi/namespace->db-name 'seon.trading)  ; => "seon_trading"
(multi/db-name->namespace "seon_trading") ; => seon.trading

;; Attach a database (idempotent)
(multi/ensure-namespace-db! node 'seon.trading)

;; Get connection to namespace database
(with-open [conn (multi/create-namespace-connection node 'seon.trading)]
  (xt/q conn "SELECT * FROM signals"))

;; Convenience wrappers
(multi/q node 'seon.trading "SELECT * FROM signals")
(multi/execute-tx! node 'seon.trading [[:put-docs :signals {...}]])

;; List attached databases
(multi/list-attached-databases node)  ; => #{"xtdb" "seon_primer" "seon_dev"}
```

### Critical Gotchas

1. **ATTACH/DETACH use `jdbc/execute!`** - NOT `xt/execute-tx` (they're DDL, not transactions)
2. **`:put-docs` doesn't work with connections** - Use SQL `INSERT INTO table RECORDS ?` instead
3. **Databases persist across restarts** - Handle "already exists" gracefully
4. **Cross-db queries**: Use `db_name.table` syntax (e.g., `seon_primer.sessions`)

---

## Context: Agent Isolation Architecture

**READ FIRST**: `docs/prds/agent-isolation/prd.md`

This work enables the agent isolation architecture where:
- Each agent namespace (e.g., `seon.trading`) gets its own isolated database
- The orchestrator manages multiple attached databases in a single XTDB node
- Agents receive a `ctx` atom with a connection to their namespace's database
- Cross-namespace queries are possible via `db.table` SQL syntax

The agent isolation PRD describes the full vision. This PRD focuses on the XTDB foundation needed to make it work.

## Problem Statement

1. **XTQL is broken** - `xt/q` with XTQL fails with `No implementation of method: :plan-query` errors
2. **Old docs reference XTQL** - Need to fully migrate to SQL-only patterns
3. **Multi-database support needed** - Agent isolation requires separate databases per namespace
4. **Version mismatch** - deps.edn uses v2.1.0-rc0, should use v2.1.0 stable
5. **Submodule is current** - `reference-code/xtdb/` is at v2.1.0

## Goals

1. **Verify/update XTDB version** - Ensure we're running v2.1.0+ with multi-database support
2. **Full SQL migration** - Remove all XTQL code paths, document SQL-only patterns
3. **Multi-database architecture** - Design for agent isolation (one DB per namespace)
4. **Update reference docs** - Rewrite `xtdb-v2-reference.md` for SQL-only
5. **Update skills** - Ensure `xtdb-queries` skill reflects SQL patterns

## Key Resources to Read

1. **Agent Isolation PRD**: `docs/prds/agent-isolation/prd.md` (understand the vision)
2. **XTDB v2.1.0 Release Notes**: https://github.com/xtdb/xtdb/releases/tag/v2.1.0
3. **Multi-Database Docs**: https://docs.xtdb.com/about/dbs-in-xtdb.html
4. **Reference Code**: `reference-code/xtdb/` (at v2.1.0)
5. **Current Implementation**: `src/seon/db/node.clj`
6. **Multi-DB Research**: `docs/prds/xtdb-sql-migration/research/multi-database.md`

## Architecture Requirements

### Current State (Single Database)
```
Seon JVM
└── XTDB Node
    └── "xtdb" database (primary)
```

### Target State (Multi-Database for Agent Isolation)
```
Seon JVM
└── XTDB Node (shared)
    ├── "xtdb" database (orchestrator - starts on boot)
    ├── "seon_trading" database (attached on demand)
    ├── "seon_health" database (attached on demand)
    └── ... (per namespace)
```

From the agent isolation PRD, agents receive a `*ctx*` atom with system-provided keys:

```clojure
{:seon.agent/namespace  'seon.trading
 :seon.agent/db         <xtdb-connection>  ; For escape hatch to SQL
 ...agent's own state...}
```

**Primary interface**: Agents just `swap!` and `deref` - persistence is automatic.
**Escape hatch**: When agents need SQL performance, they use `:seon.agent/db` directly.

### Key Design Decisions (Resolved in Phase 1)

1. **Database creation**: Use `ATTACH DATABASE` with YAML config for log/storage paths
2. **Connection management**: Use `(.createConnectionBuilder node) (.database "name") (.build)`
3. **Cross-database queries**: Use `db.table` or `db.schema.table` syntax (orchestrator only)
4. **Storage paths**: `data/namespaces/{ns}/log` and `data/namespaces/{ns}/storage`

### Database Naming (Internal Detail)

> **Agents never see database names.** This is purely orchestrator infrastructure.

SQL uses dots as hierarchy separators (`db.schema.table`), so database names can't contain dots.
The orchestrator converts namespace names internally:

| What | Example |
|------|---------|
| Namespace (Clojure) | `seon.trading` |
| Database name (SQL) | `seon_trading` |
| Storage path | `data/namespaces/seon.trading/` |

Agents interact with a `*ctx*` atom. The system handles persistence to their isolated database transparently. See `docs/prds/agent-isolation/prd.md` for the ctx-first design.

## Implementation Phases

### Phase 1: Research & Version Update - COMPLETE

- [x] Update `reference-code/xtdb/` submodule to v2.1.0 (already at v2.1.0)
- [x] Read v2.1.0 release notes thoroughly
- [x] Read multi-database documentation
- [x] Read agent isolation PRD for context
- [x] Document findings in `research/multi-database.md`
- [x] Verify current `deps.edn` XTDB version (v2.1.0-rc0, needs update to v2.1.0)

**Key Findings:**
- Submodule at v2.1.0, deps.edn at v2.1.0-rc0 - should update to stable v2.1.0
- Multi-DB fully supported via ATTACH DATABASE SQL command
- Cross-database queries work with `db.table` syntax
- Estimated ~100-200MB memory per attached database (efficient shared JVM)
- ATTACH/DETACH only work from primary `xtdb` database connection
- Temporal queries work the same per-database

### Phase 2: SQL Migration & Version Update - COMPLETE

- [x] Update deps.edn from `2.1.0-rc0` to `2.1.0`
- [x] Audit all XTQL usage in codebase
- [x] Convert remaining XTQL to SQL
- [x] Remove deprecated `node/query` XTQL wrapper
- [x] Test all queries work with SQL

**Implementation Summary:**

Files converted from XTQL to SQL:
- `src/seon/db/node.clj` - New `q` function for SQL, deprecated `query`/`xtql-query`
- `src/seon/db/queries.clj` - All option chain, greeks, IV surface queries now SQL
- `src/seon/trading/ingestion_state.clj` - State tracking queries converted
- `src/seon/trading/signals.clj` - IV rank and volatility primitives converted
- `src/seon/trading/analysis.clj` - Ticker analysis queries converted
- `src/seon/trading/bulk_load.clj` - Import status query converted
- `src/seon/web/stats.clj` - Dashboard stats queries converted

Test files updated:
- `test/seon/db/node_test.clj` - Tests for new SQL-based `q` function
- `test/seon/db/factory_test.clj` - Updated to use SQL
- `test/seon/trading/bulk_load_test.clj` - Updated mock redefs for `node/q`

**SQL Patterns Established:**
```clojure
;; Simple query
(node/q node "SELECT * FROM users")

;; Parameterized query
(node/q node "SELECT * FROM users WHERE name = ?" ["Alice"])

;; Vector format
(node/q node ["SELECT * FROM users WHERE name = ?" "Alice"])

;; With temporal options
(node/q node "SELECT * FROM users" [] {:current-time #inst "2024-01-15"})

;; Column naming: use $ for nested fields
;; asset/ticker -> asset$ticker in SQL, returns :asset/ticker with :kebab-case-keyword
```

**Deprecation Notes:**
- `node/query` - XTQL no longer supported, throws error for XTQL, routes SQL to `q`
- `node/xtql-query` - Throws error, fully deprecated
- `node/sql-query` - Alias for `q`, deprecated in favor of `q`

All 296 tests pass with 0 failures.

### Phase 3: Multi-Database Implementation - COMPLETE

**Completed**: 2026-01-04

#### Summary
Fixed the critical ATTACH DATABASE API bug and completed multi-database support.

#### Changes Made

1. **Fixed `seon.db.multi` namespace**:
   - Changed `attach-namespace-db!` to use `jdbc/execute!` instead of `xt/execute-tx`
   - Changed `detach-namespace-db!` to use `jdbc/execute!`
   - Fixed `list-attached-databases` to use `xtdb.util/component` for db-catalog access
   - Added graceful handling of "Database already exists" error during restarts
   - Added `PSQLException` import for error handling

2. **Fixed `seon.primer.ctx` namespace**:
   - Changed `checkpoint!` to use SQL `INSERT INTO ... RECORDS ?` syntax
   - The `:put-docs` transaction op doesn't work with XTDB 2.1.0 connections
   - SQL INSERT with map parameter provides the same upsert semantics

3. **Fixed `seon.system` namespace**:
   - Removed alias conflict in `require` statement

4. **Created comprehensive tests**:
   - `test/seon/db/multi_test.clj` with 16 tests covering:
     - Namespace naming conventions
     - Database attach/detach lifecycle
     - Connection management
     - Query and transaction execution
     - Database isolation
     - Cross-database queries
     - Batch operations

#### Key Technical Findings

1. **ATTACH/DETACH are NOT transactions**: They must use `jdbc/execute!` directly, not `xt/execute-tx`

2. **Connections work with `xt/q` and `xt/execute-tx`**: Both the XTDB API functions work with database connections, not just nodes

3. **`:put-docs` doesn't work with connections in XTDB 2.1.0**: Use SQL `INSERT INTO ... RECORDS ?` with a map parameter instead

4. **Databases persist across restarts**: Attached databases are stored in the primary node's log and restored on startup. Handle the race condition gracefully.

5. **Cross-database queries work**: Use `db_name.table` syntax (e.g., `seon_primer.sessions`)

#### Verification

- `(reset)` succeeds with no errors
- `(status)` shows healthy system with 3 databases: `xtdb`, `seon_primer`, `seon_dev`
- All 312 tests pass (1455 assertions, 0 failures)
- Dev hook and primer functionality work correctly

### Phase 4: Documentation Update - COMPLETE

- [x] Rewrite `docs/reference/xtdb-v2-reference.md` for SQL-only
- [x] Update `xtdb-queries` skill with SQL patterns
- [x] Add multi-database examples
- [x] Document temporal query patterns in SQL

## Success Criteria

1. ~~No XTQL code remains in codebase~~ **DONE** - All code uses SQL
2. ~~All queries use SQL syntax~~ **DONE** - 312 tests pass
3. ~~Can create/attach separate database per namespace~~ **DONE** - Phase 3 complete
4. ~~Can query across databases with `db.table` syntax~~ **DONE** - Verified in tests
5. ~~Documentation reflects current SQL-only reality~~ **DONE**
6. ~~Submodule is at latest stable XTDB release (v2.1.0)~~ **DONE**
7. ~~deps.edn uses stable v2.1.0 (not rc0)~~ **DONE**

**All success criteria met!**

## Open Questions (Resolved)

### Q: Does ATTACH DATABASE require the database to already exist?
**A**: No. ATTACH DATABASE creates the database with the specified log/storage paths. If paths exist, it reuses them; if not, it creates them.

### Q: What's the memory overhead per attached database?
**A**: Estimated 100-200MB per database. Much more efficient than separate JVMs (~1.5GB each). Shared JVM, shared query engine, separate log consumers and index state.

### Q: Can we have different storage configs per attached database?
**A**: Yes. Each ATTACH DATABASE specifies its own log and storage configuration independently.

### Q: How does temporal querying work across databases?
**A**: Same SQL syntax works. Each database has its own transaction timeline (`xt.txs` table). Cross-database queries may see slightly different "now" per database since they have independent logs.

### Q: Database naming with dots?
**A**: Use underscores in database names for SQL compatibility. Namespace `seon.trading` becomes database `seon_trading`. Storage paths can use the original dotted form.

## Related

- `docs/prds/agent-isolation/prd.md` - **READ THIS** - Depends on this work
- `docs/prds/agent-isolation/notes.md` - XTDB multi-database notes
- `docs/prds/xtdb-sql-migration/research/multi-database.md` - Detailed research findings
- `reference-code/xtdb/docs/src/content/docs/about/dbs-in-xtdb.md`
- `reference-code/xtdb/src/test/clojure/xtdb/sql/multi_db_test.clj`
