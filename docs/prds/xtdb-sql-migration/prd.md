# XTDB SQL Migration & Multi-Database Architecture

**Status**: Phase 1 Complete, Ready for Phase 2
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

### Phase 2: SQL Migration & Version Update

- [ ] Update deps.edn from `2.1.0-rc0` to `2.1.0`
- [ ] Audit all XTQL usage in codebase
- [ ] Convert remaining XTQL to SQL
- [ ] Remove deprecated `node/query` XTQL wrapper
- [ ] Test all queries work with SQL

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

1. No XTQL code remains in codebase
2. All queries use SQL syntax
3. Can create/attach separate database per namespace
4. Can query across databases with `db.table` syntax
5. Documentation reflects current SQL-only reality
6. Submodule is at latest stable XTDB release (v2.1.0)
7. deps.edn uses stable v2.1.0 (not rc0)

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
