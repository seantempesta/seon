# PRD: Datalevin Database Platform

**Status:** Implementation Ready
**Priority:** High
**Branch:** feature/datalevin-migration

---

## Vision

Build a rock-solid database platform where:
- **Orchestrator manages all database connections** - single point of control
- **Each namespace gets its own isolated database** - performance isolation
- **Agents receive connections via `*ctx*`** - simple, consistent interface
- **Malli schemas define the data contracts** - shared across all databases

---

## Architecture

### Single Server, Multiple Databases

```
┌─────────────────────────────────────────────────────────────┐
│                    Seon System (Integrant)                  │
│                                                             │
│  ┌───────────────────────────────────────────────────────┐  │
│  │            :seon/datalevin-server                     │  │
│  │                                                       │  │
│  │  • Runs inside orchestrator JVM                       │  │
│  │  • Port 8898 (configurable)                           │  │
│  │  • Root: data/datalevin/                              │  │
│  │                                                       │  │
│  │  Databases:                                           │  │
│  │    /seon/           ← Master (always exists)          │  │
│  │    /seon.trading/   ← Created lazily on first request │  │
│  │    /seon.health/    ← Created lazily on first request │  │
│  └───────────────────────────────────────────────────────┘  │
│                            │                                │
│  ┌─────────────────────────┼─────────────────────────────┐  │
│  │    :seon/connection-manager                           │  │
│  │                                                       │  │
│  │  • Manages client connections to server               │  │
│  │  • Caches connections per namespace                   │  │
│  │  • Applies compiled Malli schemas on connect          │  │
│  │  • Provides connections to agents via *ctx*           │  │
│  └───────────────────────────────────────────────────────┘  │
│                            │                                │
│  ┌─────────────────────────┼─────────────────────────────┐  │
│  │           Orchestrator (client)                       │  │
│  │                                                       │  │
│  │  • Uses master DB (/seon/) for:                       │  │
│  │    - Session registry                                 │  │
│  │    - AI messages (Observatory)                        │  │
│  │    - Cross-namespace queries                          │  │
│  └───────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────┘
                             │
        Agent processes connect as clients
                             │
         ┌───────────────────┼───────────────────┐
         ▼                   ▼                   ▼
     Agent a1b2          Agent c3d4          Agent e5f6
     (JVM 2)             (JVM 3)             (JVM 4)

     *ctx* contains:     *ctx* contains:     *ctx* contains:
     :seon.ns/conn →     :seon.ns/conn →     :seon.ns/conn →
       seon.trading        seon.trading        seon.health
```

### Key Design Decisions

| Decision | Rationale |
|----------|-----------|
| **Server mode, not embedded** | Multiple agent JVMs need to share namespace DBs |
| **Single server process** | Managed by Integrant like all Seon components |
| **Lazy DB creation** | Don't waste resources on unused namespaces |
| **Connection caching** | Don't reconnect on every request |
| **Schema on connect** | All DBs have full schema, enabling data flow |

### Resource Efficiency

- **Datalevin server is lightweight** - ~50MB memory baseline
- **LMDB is memory-mapped** - OS manages actual memory usage
- **Lazy DB creation** - Namespace DBs only exist when needed
- **Connection pooling** - Reuse connections, don't thrash

---

## Data Separation

### Master Database (`/seon/`)

Orchestrator's view of the system:

| Entity | Purpose |
|--------|---------|
| `session/*` | Agent session registry (id, namespace, status, ports) |
| `ai.session/*` | AI session metadata (cost, tokens, duration) |
| `ai.message/*` | All agent messages (for Observatory, replay) |

### Namespace Databases (`/seon.{namespace}/`)

Domain-specific data, isolated per namespace:

| Entity | Purpose |
|--------|---------|
| `ctx.version/*` | Agent `*ctx*` snapshots (keyed by session-id) |
| Domain entities | Whatever the namespace defines (quotes, signals, etc.) |

### Why This Separation?

1. **Performance isolation** - Trading queries (millions of records) don't affect health namespace
2. **Namespace ownership** - Each namespace owns its domain data
3. **Shared visibility** - Orchestrator sees all sessions/messages for Observatory
4. **Resource control** - Can kill namespace DB without affecting others

---

## Agent Context (`*ctx*`)

Agents receive a `*ctx*` atom with:

```clojure
{:seon.ns/conn       <Datalevin connection to namespace DB>
 :seon.ns/session-id "a1b2"
 :seon.ns/namespace  "seon.trading"

 ;; Agent's working memory (EDN-serializable)
 :signals []
 :positions []
 :analysis nil}
```

### Two Storage Mechanisms

| Mechanism | Purpose | Characteristics |
|-----------|---------|-----------------|
| **`*ctx*` atom** | Working memory | Small, versioned, EDN-only, time-travel |
| **`:seon.ns/conn`** | Domain data | Large, indexed, queryable, structured |

### The Atom → Database Progression

```clojure
;; Stage 1: Prototyping - just use the atom
(swap! *ctx* update :signals conj new-signal)

;; Stage 2: Need queries - use the connection
(d/transact! (:seon.ns/conn @*ctx*)
  [{:signal/id (random-uuid) :signal/ticker "SPY" :signal/type :buy}])

(d/q '[:find ?ticker
       :where [?e :signal/type :buy] [?e :signal/ticker ?ticker]]
     @(:seon.ns/conn @*ctx*))
```

### Durable Atom: Duratom + Watchers

**Decision:** Use [duratom](https://github.com/jimpil/duratom) for persistence, add versioning via watchers.

**Why duratom:**
- Drop-in atom replacement (`swap!`, `reset!`, `@` all work)
- Custom serializers filter non-EDN keys (`:seon.ns/conn`)
- Sync or async commit modes
- Active maintenance

**Pattern:**
```clojure
(def ctx (dur/duratom :local-file
           :file-path "data/ctx/session-id.edn"
           :rw {:write (fn [p d] (spit p (pr-str (filter-serializable d))))}
           :init {}))

;; Add versioning via watcher
(add-watch ctx ::versioning
  (fn [_ _ old new]
    (swap! versions conj {:timestamp (Instant/now) :state new})))
```

See `research/durable-atoms.md` for full implementation.

---

## Schema System

### Malli as Source of Truth

```clojure
(ns seon.trading.schema
  (:require [seon.schema :as schema]))

;; Register with Datalevin properties
(schema/register! ::signal-id
  [:uuid {:datalevin/unique :db.unique/identity}])

(schema/register! ::ticker
  [:string {:datalevin/index true}])

(schema/register! ::signal-type
  [:enum :buy :sell :hold])

(schema/register! ::signal
  [:map
   [::signal-id]
   [::ticker]
   [::signal-type]
   [::recorded-at inst?]])
```

### Schema Compilation

```clojure
(require '[seon.schema.datalevin :as dl])

;; Compile all registered schemas
(dl/compile-schemas)
;; => {:seon.trading/signal-id {:db/valueType :db.type/uuid
;;                              :db/unique :db.unique/identity}
;;    :seon.trading/ticker {:db/valueType :db.type/string
;;                          :db/index true}
;;    ...}
```

### Schema Loading

When a namespace connection is created:
1. Load all registered Malli schemas
2. Compile to Datalevin format
3. Apply via `d/update-schema`

This means all DBs can store any registered entity type.

---

## Implementation Phases

### Phase 0: Database Platform Foundation

**Goal:** Rock-solid database infrastructure, no breaking changes.

#### 0.1 Datalevin Server Component
```clojure
;; resources/config.edn
{:seon/datalevin-server
 {:port 8898
  :root "data/datalevin"
  :opts {:validate-data? true}}}
```

- [ ] Create `src/seon/db/datalevin/server.clj`
- [ ] Integrant component: `:seon/datalevin-server`
- [ ] Start server on system start
- [ ] Graceful shutdown on system stop
- [ ] Health check integration

#### 0.2 Connection Manager
```clojure
;; API
(conn/get-master-conn!)           ;; → /seon/ connection
(conn/get-namespace-conn! 'seon.trading)  ;; → /seon.trading/ connection (lazy create)
(conn/close-namespace-conn! 'seon.trading) ;; → close and remove from cache
```

- [ ] Create `src/seon/db/datalevin/conn.clj`
- [ ] Integrant component: `:seon/connection-manager`
- [ ] Depends on `:seon/datalevin-server`
- [ ] Connection caching per namespace
- [ ] Lazy database creation
- [ ] Schema application on connect

#### 0.3 Schema Compiler
- [ ] Create `src/seon/schema/datalevin.clj`
- [ ] Multimethod for type transformation
- [ ] Handle `:datalevin/*` properties
- [ ] `compile-schemas` → full Datalevin schema map

#### 0.4 Health & Diagnostics
- [ ] `/api/health` includes Datalevin server status
- [ ] `/api/health/datalevin` detailed DB stats
- [ ] Connection count, DB sizes, query latency

**Success Criteria:**
- [ ] Server starts with system, stops cleanly
- [ ] `get-namespace-conn!` creates DB on first call
- [ ] Schema compiler produces valid Datalevin schemas
- [ ] Health checks pass
- [ ] Zero changes to existing XTDB code

---

### Phase 1: Dual-Write AI Domain

**Goal:** Verify Datalevin works by writing to both DBs.

#### 1.1 Dual-Write Layer
- [ ] Create `src/seon/db/dual.clj` - writes to both XTDB and Datalevin
- [ ] Feature flag in config: `:db/dual-write? true`
- [ ] Log any discrepancies between DBs

#### 1.2 AI Session/Message Dual-Write
- [ ] Update `seon.ai/save-session!`
- [ ] Update `seon.ai/save-message!`
- [ ] Verification queries compare both DBs

**Success Criteria:**
- [ ] All AI data in both XTDB and Datalevin
- [ ] Data matches exactly
- [ ] Existing tests still pass (read from XTDB)

---

### Phase 2: Read Migration

**Goal:** Switch reads to Datalevin, XTDB becomes backup.

#### 2.1 Query Migration
- [ ] Rewrite SQL queries as Datalog
- [ ] Feature flag: `:db/read-from :datalevin`
- [ ] A/B query comparison logging

#### 2.2 Switch Reads
- [ ] `seon.ai` reads from Datalevin
- [ ] Observatory reads from Datalevin
- [ ] Stop XTDB writes for AI domain

**Success Criteria:**
- [ ] AI domain fully on Datalevin
- [ ] All tests pass
- [ ] Observatory works

---

### Phase 3: Agent Context System

**Goal:** Implement `*ctx*` with `:seon.ns/conn` injection.

#### 3.1 Durable Atom (Duratom)
- [ ] Create `src/seon/agent/durable_ctx.clj`
- [ ] Integrate duratom with custom serializer for filtering
- [ ] Add watcher-based versioning with bounded history

#### 3.2 Orchestrator Context Factory
```clojure
(defn create-agent-ctx [{:keys [namespace session-id]}]
  (let [conn (conn/get-namespace-conn! namespace)
        previous (load-ctx-versions conn session-id)]
    (durable-atom
      {:initial {:seon.ns/conn conn
                 :seon.ns/session-id session-id
                 :seon.ns/namespace (str namespace)}
       :skip-keys #{:seon.ns/conn}
       :persist-to conn})))
```

- [ ] Create `src/seon/orchestrator/ctx.clj`
- [ ] Context injection on agent launch
- [ ] Session resume loads previous `*ctx*`

**Success Criteria:**
- [ ] Agents get `*ctx*` with working `:seon.ns/conn`
- [ ] `*ctx*` persists (minus non-EDN keys)
- [ ] Session resume works

---

### Phase 4: Domain Migration

**Goal:** Move remaining domains to Datalevin.

#### 4.1 Trading Domain
- [ ] Migrate `option_greeks` (millions of records)
- [ ] Performance verification
- [ ] Bulk loader updates

#### 4.2 Dev Hook Domain
- [ ] Migrate edit/review/todo events

#### 4.3 Orchestrator Domain
- [ ] Session registry to master DB

**Success Criteria:**
- [ ] All domains on Datalevin
- [ ] Performance equal or better than XTDB

---

### Phase 5: XTDB Removal

**Goal:** Clean removal of XTDB.

- [ ] Remove XTDB from `deps.edn`
- [ ] Remove XTDB Integrant components
- [ ] Update documentation
- [ ] Create `/datalevin-queries` skill
- [ ] Delete XTDB data (after backup)

**Success Criteria:**
- [ ] XTDB completely removed
- [ ] All tests pass
- [ ] Documentation updated

---

## File Structure

```
src/seon/
├── schema/
│   └── datalevin.clj        # Malli → Datalevin compiler
│
├── db/
│   ├── datalevin/
│   │   ├── server.clj       # Integrant server component
│   │   └── conn.clj         # Connection manager
│   ├── dual.clj             # Dual-write layer (temporary)
│   └── node.clj             # Existing XTDB wrapper (eventually remove)
│
├── agent/
│   └── durable_ctx.clj      # Duratom-based *ctx* with versioning
│
└── orchestrator/
    └── ctx.clj              # Agent *ctx* factory (injects conn)
```

---

## Configuration

```clojure
;; resources/config.edn
{:seon/datalevin-server
 {:port #long #or [#env PORT_DATALEVIN 8898]
  :root "data/datalevin"
  :opts {:validate-data? true
         :auto-entity-time? false}}

 :seon/connection-manager
 {:server #ig/ref :seon/datalevin-server
  :schema-ns 'seon.schema}

 ;; Feature flags for migration
 :db/dual-write? false
 :db/read-from :xtdb}  ;; :xtdb | :datalevin
```

---

## Success Criteria

| Criterion | Measurement |
|-----------|-------------|
| **Reliability** | Zero data loss during migration |
| **Performance** | Query latency ≤ XTDB |
| **Isolation** | Namespace DBs don't affect each other |
| **Resource efficiency** | Memory usage < XTDB |
| **Developer experience** | Simpler queries, better errors |

---

## Research Documents

| Document | Status | Purpose |
|----------|--------|---------|
| `research/api-patterns.md` | ✅ | Datalevin API guide |
| `research/schema-design.md` | ✅ | Entity schemas |
| `research/xtdb-audit.md` | ✅ | Current usage catalog |
| `research/temporal-strategy.md` | ✅ | Time travel alternatives |
| `research/malli-integration.md` | ✅ | Schema unification |
| `research/concurrent-access.md` | ✅ | Multi-process constraints |
| `research/multi-db-queries.md` | ✅ | Cross-DB query patterns |
| `research/durable-atoms.md` | ✅ | Duratom chosen for persistence |

---

## Risks & Mitigations

| Risk | Mitigation |
|------|------------|
| Data loss during migration | Dual-write phase, keep XTDB until Phase 5 |
| Performance regression | Benchmark each phase, feature flags to rollback |
| Agent disruption | Gradual rollout, existing code keeps working |
| Server process failure | Health checks, auto-restart via Integrant |

---

## Implementation Progress (Audit: 2026-02-10)

### ✅ Phase 0.1 & 0.2: Server & Connection Manager COMPLETE

Both server and connection manager are fully implemented:

| Component | File | Status |
|-----------|------|--------|
| Datalevin Server | `src/seon/db/datalevin/server.clj` | ✅ Complete |
| Connection Manager | `src/seon/db/datalevin/conn.clj` | ✅ Complete |
| Integrant Components | `src/seon/system.clj` | ✅ Integrated |

Features implemented:
- ✅ Server starts/stops with Integrant lifecycle
- ✅ TCP health check (`healthy?` function)
- ✅ Connection caching with TTL-based cleanup
- ✅ Lazy database creation via `get-namespace-conn!`
- ✅ Suspend/resume survives `(reset)`

### 🔲 Phase 0.3: Schema Compiler NOT STARTED

The Malli→Datalevin schema compiler (`src/seon/schema/datalevin.clj`) does not exist yet.

**Impact:** Connections currently use no schema. Schema-on-connect is not working.

### 🔲 Phase 0.4: Health Integration NOT STARTED

Datalevin is not integrated into `/api/health` endpoint.

---

## Current XTDB Dependencies (Complete Audit)

### Critical Files (29 files use XTDB directly or via wrappers)

| Category | Files | XTDB Functions | Temporal? |
|----------|-------|----------------|-----------|
| **Core DB Layer** | | | |
| `db/node.clj` | xt/q, xt/execute-tx | YES |
| `db/queries.clj` | node/q | YES |
| `db/multi.clj` | xt/execute-tx, xt/q, .submitTx | NO |
| **AI System** | | | |
| `ai.clj` | xt/execute-tx, xt/q | NO |
| `ai/agent.clj` | uses seon.ai functions | NO |
| `ai/agent/log.clj` | xt/execute-tx | NO |
| `ai/claude.clj` | uses seon.ai functions | NO |
| **Agent System** | | | |
| `agent/ctx.clj` | xt/execute-tx, xt/q | **YES - CRITICAL** |
| `agent/helpers.clj` | node/q | NO |
| **Trading Domain** | | | |
| `trading/bulk_load.clj` | xt/execute-tx | YES |
| `trading/ingestion_state.clj` | node/q, node/execute-tx! | NO |
| `trading/signals.clj` | node/q | YES |
| `trading/analysis.clj` | node/q | NO |
| `trading/ingest.clj` | node/execute-tx! | NO |
| **Orchestrator** | | | |
| `orchestrator/session.clj` | xt/execute-tx, xt/q | NO |
| **Primer** | | | |
| `primer/ctx.clj` | node/execute-tx!, node/entity | YES |
| **Dev Hook** | | | |
| `dev/context.clj` | node/execute-tx!, node/sql-query | YES |
| **Web Layer** | | | |
| `web/agents.clj` | db/q | NO |
| `web/stats.clj` | node/q | NO |
| `web/handlers.clj` | uses db functions | NO |
| `web/jobs.clj` | uses db functions | NO |
| **Infrastructure** | | | |
| `health.clj` | xt/q | NO |
| `system.clj` | xt/start-node | NO |
| `ns/view.clj` | uses db functions | NO |

### Temporal Query Patterns in Use

| Pattern | Files Using | Datalevin Strategy |
|---------|-------------|-------------------|
| `FOR ALL VALID_TIME` | db/node.clj, db/queries.clj | Append-only snapshots |
| `FOR SYSTEM_TIME AS OF` | agent/ctx.clj | Append-only snapshots |
| `_valid_from` filtering | dev/context.clj, trading/signals.clj | Explicit `:recorded-at` |
| `:xt/valid-from` on insert | trading/bulk_load.clj | Explicit `:quote/recorded-at` |
| `{:current-time instant}` | db/node.clj, primer/ctx.clj | Explicit filtering in query |

### Query Language Translation Required

XTDB uses SQL. Datalevin uses Datalog. All queries must be rewritten:

```clojure
;; XTDB (current)
(xt/q node ["SELECT * FROM ai_sessions WHERE status = ?" status])

;; Datalevin (target)
(d/q '[:find (pull ?e [*])
       :in $ ?status
       :where [?e :ai.session/status ?status]]
     @conn status)
```

**Estimated queries to rewrite:** 40-50 across the codebase.

---

## Migration Risks - Deep Analysis

### HIGH RISK: Query Language Translation Errors

**Issue:** SQL → Datalog translation is error-prone. Different semantics can cause subtle bugs.

**Specific Concerns:**
1. JOIN semantics differ (Datalevin is inner-join only)
2. NULL handling differs between SQL and Datalog
3. Aggregate functions have different syntax
4. ORDER BY behavior with ties

**Mitigation:**
- Create comprehensive query test suite BEFORE migration
- Add parallel query execution during dual-write (compare results)
- Log all query result differences for manual review

### HIGH RISK: Temporal Feature Loss

**Issue:** XTDB has built-in bitemporality. Datalevin has single-timeline history.

**Files at Risk:**
- `agent/ctx.clj` - Uses SYSTEM_TIME for debugging time-travel
- `primer/ctx.clj` - Uses `{:current-time instant}` for point-in-time
- `trading/bulk_load.clj` - Uses `:xt/valid-from` for historical data

**Mitigation:**
- Implement append-only snapshot pattern (already documented in temporal-strategy.md)
- Add explicit `:recorded-at` columns to all temporal entities
- Test time-travel queries extensively before switching

### MEDIUM RISK: Multi-Database Query Complexity

**Issue:** Cross-namespace queries require multiple database connections.

**Example:** Observatory needs to query all agent messages across namespaces.

**Mitigation:**
- Research verified: Multi-DB queries work (`research/multi-db-queries.md`)
- Performance tested: 13ms for 1003 results across DBs
- Pattern: Use `:in $db1 $db2 $db3` syntax

### MEDIUM RISK: Schema Drift

**Issue:** Malli schemas and Datalevin schemas could diverge.

**Mitigation:**
- Build schema compiler (Phase 0.3) FIRST
- Generate Datalevin schema from Malli at connection time
- Add schema validation tests

### LOW RISK: Connection Lifecycle

**Issue:** Agent JVMs connecting/disconnecting could stress connection manager.

**Mitigation:**
- Already implemented: TTL-based cleanup (5-minute default)
- Connection caching prevents thrashing
- Health checks detect stale connections

---

## Gaps Identified in Current Plan

### Gap 1: No Schema Compiler Implementation Path

**Problem:** Phase 0.3 says "Create `src/seon/schema/datalevin.clj`" but doesn't specify:
- Which Malli types map to which Datalevin types
- How to handle nested maps (EDN-encode vs flatten)
- How to handle optionality

**Resolution:** Add detailed type mapping table (see research/malli-integration.md)

### Gap 2: No Query Migration Guide

**Problem:** Plan says "Rewrite SQL queries as Datalog" but doesn't provide:
- Mapping from XTDB SQL patterns to Datalog patterns
- Common pitfalls to avoid
- Testing strategy for query equivalence

**Resolution:** Create `research/query-migration-guide.md` before Phase 2

### Gap 3: Observatory Cross-DB Query Pattern

**Problem:** Observatory needs to query ALL agent messages, but agents use separate DBs.

**Resolution:**
- Option A: Keep all messages in master DB (simpler)
- Option B: Aggregate queries across namespace DBs (already tested, works)

**Recommendation:** Option A - all AI messages stay in master DB, only domain data in namespace DBs.

### Gap 4: Test Migration Strategy

**Problem:** Existing tests use XTDB. No plan for migrating test fixtures.

**Resolution:**
- Add `seon.db.test-utils` that abstracts DB setup
- Tests should work against either XTDB or Datalevin
- Feature flag `:test/db-backend` determines which to use

### Gap 5: Data Migration Scripts

**Problem:** No scripts defined for migrating existing XTDB data to Datalevin.

**Resolution:** Add to Phase 4:
- Create `src/seon/db/migration.clj`
- One-time migration functions per entity type
- Verification queries after migration

---

## Validation Checklist

### Phase 0: Foundation

- [ ] Datalevin server starts with system
- [ ] Health check passes: `(dtlv-server/healthy? {::port 8898})`
- [ ] Connection to master DB works: `(get-master-conn!)`
- [ ] Namespace DB created lazily: `(get-namespace-conn! 'seon.trading)`
- [ ] Schema compiler generates valid Datalevin schemas
- [ ] TTL cleanup removes idle connections
- [ ] Server survives `(reset)` without losing connections

### Phase 1: Dual-Write

- [ ] All `ai_sessions` writes go to both XTDB and Datalevin
- [ ] All `ai_messages` writes go to both XTDB and Datalevin
- [ ] Data in both DBs matches exactly (verification query)
- [ ] No test failures
- [ ] Observatory still works (reading from XTDB)

### Phase 2: Read Migration

- [ ] All AI queries rewritten to Datalog
- [ ] Query results match XTDB results (logged comparison)
- [ ] Observatory works with Datalevin backend
- [ ] Session list loads correctly
- [ ] Message timeline displays correctly
- [ ] Token/cost stats are accurate
- [ ] No test failures

### Phase 3: Agent Context

- [ ] Agents receive `*ctx*` with working `:seon.ns/conn`
- [ ] `swap!` persists to namespace DB (filtered)
- [ ] Time-travel works: can query historical `*ctx*` states
- [ ] Session resume loads previous context
- [ ] Non-EDN keys (connections) are filtered from persistence

### Phase 4: Domain Migration

- [ ] Trading quotes migrated (count matches XTDB)
- [ ] Query performance ≤ XTDB latency
- [ ] Bulk loader works with Datalevin
- [ ] Dev hook events migrated
- [ ] Orchestrator sessions migrated

### Phase 5: XTDB Removal

- [ ] XTDB removed from `deps.edn`
- [ ] All Integrant XTDB components removed
- [ ] No references to `xt/*` in codebase
- [ ] All tests pass
- [ ] `/xtdb-queries` skill replaced with `/datalevin-queries`
- [ ] Documentation updated

---

## Recommended Execution Order

Based on the audit, here's the recommended priority:

1. **Phase 0.3: Schema Compiler** - Must complete before any data migration
2. **Phase 0.4: Health Integration** - Important for observability
3. **Phase 1: Dual-Write AI** - Low risk, high visibility
4. **Create Query Migration Guide** - Before Phase 2
5. **Phase 2: Read Migration** - Carefully with A/B testing
6. **Phase 3: Agent Context** - Enables namespace isolation vision
7. **Phase 4: Domain Migration** - Trading data is largest and riskiest
8. **Phase 5: XTDB Removal** - Only after everything verified

**Estimated Timeline:**
- Phases 0-2: Can be done incrementally with minimal disruption
- Phase 3: Requires careful orchestrator changes
- Phase 4: Bulk of the work, needs dedicated focus
- Phase 5: Final cleanup, low effort if earlier phases succeeded

---

## Stress Testing (Phase 1 - IMPLEMENTED)

### What's Implemented

A parallel dual-write layer that writes AI sessions and messages to both XTDB and Datalevin simultaneously. This allows stress testing Datalevin under real production load without risking data loss.

**Files:**
- `src/seon/ai/datalevin.clj` - Parallel storage namespace
- `src/seon/ai.clj` - Modified to call dual-write after XTDB writes
- `src/seon/ai/claude.clj` - Modified to dual-write Claude messages

### How It Works

1. All `start-session!`, `end-session!`, `add-message!` calls in `seon.ai` now also write to Datalevin
2. All `persist-message!` calls in `seon.ai.claude` also write to Datalevin
3. Writes are fire-and-forget: Datalevin errors are logged but don't affect the main flow
4. Can be toggled on/off at runtime

### Toggling Dual-Write

```clojure
(require '[seon.ai.datalevin :as dl])

;; Check current state
@dl/enabled?  ;; => true

;; Disable (if Datalevin has issues)
(dl/set-enabled! false)

;; Re-enable
(dl/set-enabled! true)
```

### Monitoring

```clojure
;; View write statistics
(dl/stats)
;; => {:write-count 94
;;     :error-count 0
;;     :last-write-at #inst "..."
;;     :session-writes 63
;;     :message-writes 17}

;; Count entities in Datalevin
(dl/count-entities)
;; => {:sessions 63 :messages 17}

;; Compare with XTDB (historical data won't match)
(dl/verify-sync)
;; => {:datalevin {:sessions 63 :messages 17}
;;     :xtdb {:sessions 161 :messages 10304}
;;     :in-sync? false}  ;; Expected: XTDB has historical data

;; Query recent sessions
(dl/query-sessions {:limit 5})

;; Query messages for a session
(dl/query-messages {:session-id "ses-abc123" :limit 20})
```

### What to Watch For

1. **Error count increasing** - Check `(:error-count (dl/stats))` regularly
2. **Write latency** - Monitor `:last-write-at` to ensure writes are happening
3. **Memory usage** - Watch for memory growth in Datalevin server
4. **Disk usage** - Check `data/datalevin/` directory size
5. **Server stability** - Does Datalevin survive `(reset)` and long-running sessions?

### Stress Test Procedures

**Light stress test (recommended first):**
1. Launch 2-3 agents simultaneously
2. Monitor `(dl/stats)` for error count
3. Run for 30+ minutes
4. Verify no crashes or hangs

**Heavy stress test:**
1. Launch 5+ agents simultaneously
2. Include agents that generate many messages (complex multi-file edits)
3. Run for several hours
4. Compare final counts: `(dl/verify-sync)`

**Crash recovery test:**
1. While agents are running, stop the server abruptly (`pkill -9`)
2. Restart with `./bin/run`
3. Verify Datalevin recovers without data loss
4. Check `(dl/count-entities)` matches expected values

### Success Criteria for Migration

Before proceeding to Phase 2 (switching reads to Datalevin):

- [ ] Zero errors after 24+ hours of normal use
- [ ] Server survives multiple `(reset)` cycles without issues
- [ ] No memory leaks observed
- [ ] Crash recovery works reliably
- [ ] Write latency acceptable (< 10ms per entity)
