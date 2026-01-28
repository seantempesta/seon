# PRD: XTDB to Datalevin Migration

**Status:** Research Complete → Ready for Implementation
**Priority:** High
**Branch:** feature/datalevin-migration

---

## Goals

1. **Replace XTDB with Datalevin** - Migrate from XTDB v2 (SQL-based) to Datalevin (Datalog-based) for better query ergonomics and performance
2. **Embedded database** - Use Datalevin's embedded mode for direct LMDB access without network overhead
3. **Lower resource footprint** - Reduce memory usage and startup time
4. **Better query composition** - Datalog queries compose naturally vs SQL string building
5. **Unified Malli schemas** - Single source of truth for validation AND database schema

---

## Problem Statement

XTDB v2 has friction points in our use case:

- **SQL is awkward in Clojure** - Building SQL strings, escaping column names (e.g., `option$strike`), handling namespaced keywords is verbose
- **Query composition is hard** - SQL doesn't compose well; we end up with string concatenation or complex query builders
- **Memory usage** - XTDB's Arrow-based storage and query engine has significant memory overhead
- **Startup time** - XTDB node takes several seconds to start

**What we lose:** Explicit bitemporality (valid-time + system-time queries). Research confirmed:
- We rarely use system-time queries in practice
- All 7 temporal use cases can be handled with explicit timestamp columns
- Git-based backups of data directory provide disaster recovery

**What we gain:**
- Datalog queries that compose naturally
- Implicit joins via entity references
- Lower memory footprint (LMDB is memory-mapped, not in-heap)
- Faster startup (no JVM warm-up of query engine)
- Simpler embedded usage (no ports, no server process)
- **Malli → Datalevin schema compilation** - define once, use everywhere

---

## Resources

| Resource | Purpose |
|----------|---------|
| `reference-code/datalevin/` | Datalevin v0.10.3 source (2026-01-27) |
| `reference-code/malli/` | Malli source - schema transformation patterns |
| `reference-code/malli-datomic/` | Reference for Malli→Datomic bridges |
| `reference-code/spectomic/` | Type inference patterns for schema generation |
| `src/seon/schema.clj` | Current Malli registry pattern |

---

## Research Phase ✅ COMPLETE

### Phase 0: External Research ✅
- [x] **Datalevin API patterns** → `research/api-patterns.md`
- [x] **Schema design** → `research/schema-design.md`
- [x] **Embedded mode confirmed** - Works perfectly, tested in REPL
- [x] **Multi-database** - Each agent gets own LMDB directory
- [x] **Malli integration** → `research/malli-integration.md`

### Phase 1: Internal Code Audit ✅
- [x] **Catalog all XTDB usage** → `research/xtdb-audit.md` (22 files)
- [x] **Identify temporal queries** → 7 files use temporal features
- [x] **Document data shapes** → 11 entity types documented
- [x] **Temporal strategy** → `research/temporal-strategy.md`

### Key Findings

**No true bitemporality needed.** All temporal features map to:
- **Option A (explicit timestamps)** for facts (trading data, events)
- **Option B (append-only snapshots)** for contexts that need point-in-time queries

**Single abstraction point.** All production code goes through `seon.db.node`. This makes the swap feasible - we just change the implementation behind the interface.

**AI domain is simplest.** Good proof-of-concept: `ai_sessions` + `ai_messages` with clear schemas.

---

## Solution Design

### Core Architecture: Malli → Datalevin Schema Compiler

Create `seon.schema.datalevin` that transforms Malli schemas to Datalevin format (same pattern as Malli's JSON Schema transformer):

```clojure
;; Define schema ONCE in Malli with :datalevin/* properties
(schema/register!
  ::session-id [:string {:datalevin/unique :db.unique/identity}])

;; Generate Datalevin schema automatically
(def datalevin-schema
  (dl/entity-schema :ai.session Session))
;; => {:ai.session/id {:db/valueType :db.type/string
;;                     :db/unique :db.unique/identity}}
```

### Agent Session Architecture

When an agent starts, it gets:
1. **Own REPL** - Isolated nREPL on unique port
2. **Own Datalevin instance** - Separate LMDB directory
3. **Shared schema preloaded** - Core entities (sessions, messages) ready
4. **Malli schemas in namespace** - Define data structures that match DB

```
data/datalevin/
├── orchestrator/     # Main database
├── seon_trading/     # Agent's isolated DB
├── seon_health/      # Another agent
└── ...
```

### Type Mapping: Malli → Datalevin

| Malli Type | Datalevin Type |
|------------|----------------|
| `:string` | `:db.type/string` |
| `:int` | `:db.type/long` |
| `:double` | `:db.type/double` |
| `:boolean` | `:db.type/boolean` |
| `:uuid` | `:db.type/uuid` |
| `:keyword` | `:db.type/keyword` |
| `inst?` | `:db.type/instant` |
| `:vector` | `:db.cardinality/many` |
| `:enum` | `:db.type/keyword` |

### Temporal Strategy

| Use Case | Current | Datalevin Approach |
|----------|---------|-------------------|
| Agent ctx recovery | `FOR SYSTEM_TIME` | Append-only snapshots |
| IV time series | `FOR ALL VALID_TIME` | Explicit `:quote/recorded-at` |
| Backtesting lockdown | `:current-time` option | Filter by timestamp |
| Historical inserts | `:xt/valid-from` | Explicit `:quote/recorded-at` |

---

## Implementation Phases

### Phase 1: Schema Compiler
- [ ] Create `seon.schema.datalevin` namespace
- [ ] Implement primitive type transformations
- [ ] Handle `:datalevin/*` properties
- [ ] Test with AI entity schemas

### Phase 2: Connection Layer
- [ ] Create `seon.db.datalevin` namespace
- [ ] Integrant component for connection lifecycle
- [ ] Factory for per-agent database directories
- [ ] Basic query/transact wrappers

### Phase 3: AI Domain Migration
- [ ] Migrate `ai_sessions` entity
- [ ] Migrate `ai_messages` entity
- [ ] Update `seon.ai` to use Datalevin
- [ ] Property tests with Malli generators

### Phase 4: Agent Integration
- [ ] Update agent session startup
- [ ] Preload shared schema on agent REPL start
- [ ] Test agent isolation
- [ ] Performance benchmarks

### Phase 5: Full Migration
- [ ] Migrate remaining domains
- [ ] Create `/datalevin-queries` skill
- [ ] Remove XTDB dependency
- [ ] Update documentation

---

## Constraints

- Must not break existing functionality during migration
- Must maintain agent isolation (separate DBs per namespace)
- Must be REPL-friendly (hot reload, interactive development)
- Must include property tests for all migrated functionality
- Malli schemas are single source of truth

---

## Success Criteria

1. **Schema compiler works** - Malli schemas generate valid Datalevin schemas
2. **Property tests pass** - Generated entities round-trip through DB
3. **AI domain migrated** - Sessions and messages on Datalevin
4. **Agent isolation works** - Each agent has isolated database
5. **Performance improved** - Lower memory, faster startup

---

## Deliverables

### Research Phase ✅
- [x] `research/api-patterns.md` - Datalevin API guide
- [x] `research/schema-design.md` - Entity schemas
- [x] `research/xtdb-audit.md` - Current usage catalog
- [x] `research/temporal-strategy.md` - Time travel alternatives
- [x] `research/malli-integration.md` - Schema unification approach

### Implementation Phase
- [ ] `src/seon/schema/datalevin.clj` - Malli→Datalevin compiler
- [ ] `src/seon/db/datalevin.clj` - Core wrapper
- [ ] `src/seon/db/datalevin/factory.clj` - Per-agent DB creation
- [ ] `.claude/skills/datalevin-queries.md` - Agent skill
- [ ] Property tests for entity roundtrips
