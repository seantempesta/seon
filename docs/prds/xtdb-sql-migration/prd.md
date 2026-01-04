# XTDB SQL Migration & Multi-Database Architecture

**Status**: Phase 2 Complete, Ready for Phase 3
**Last Updated**: 2026-01-04
**Priority**: High (blocks agent isolation work)

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

From the agent isolation PRD, agents receive context like:
```clojure
{:seon.agent/namespace     'seon.trading
 :seon.agent/db            <xtdb-connection-to-seon_trading-db>
 ...}
```

### Key Design Decisions (Resolved in Phase 1)

1. **Database creation**: Use `ATTACH DATABASE` with YAML config for log/storage paths
2. **Connection management**: Use `(.createConnectionBuilder node) (.database "name") (.build)`
3. **Cross-database queries**: Use `db.table` or `db.schema.table` syntax
4. **Naming convention**: Convert dots to underscores (e.g., `seon.trading` -> `seon_trading`)
5. **Storage paths**: `data/namespaces/{ns}/log` and `data/namespaces/{ns}/storage`

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

### Phase 3: Multi-Database Implementation

- [ ] Implement `seon.db.multi/attach-namespace-db!` function
- [ ] Implement `seon.db.multi/create-namespace-connection` function
- [ ] Design Integrant component for multi-db management
- [ ] Add namespace database registry to orchestrator DB
- [ ] Test cross-database queries

### Phase 4: Documentation Update - COMPLETE

- [x] Rewrite `docs/reference/xtdb-v2-reference.md` for SQL-only
- [x] Update `xtdb-queries` skill with SQL patterns
- [x] Add multi-database examples
- [x] Document temporal query patterns in SQL

## Success Criteria

1. ~~No XTQL code remains in codebase~~ **DONE** - All code uses SQL
2. ~~All queries use SQL syntax~~ **DONE** - 296 tests pass
3. Can create/attach separate database per namespace (Phase 3)
4. Can query across databases with `db.table` syntax (Phase 3)
5. ~~Documentation reflects current SQL-only reality~~ **DONE**
6. ~~Submodule is at latest stable XTDB release (v2.1.0)~~ **DONE**
7. ~~deps.edn uses stable v2.1.0 (not rc0)~~ **DONE**

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
