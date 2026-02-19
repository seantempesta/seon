# PRD: Datalevin Database Platform

**Status:** Phases 0-3 Mostly Complete (on `feature/super-repl` branch)
**Priority:** High
**Branch:** feature/super-repl (merged with datalevin-migration work)

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

---

## Unified `*ctx*` System

### Problem: Three Separate Context Systems

The codebase has three independent ctx/state management systems that overlap in purpose:

| System | File | Storage | Push/Notify | Keyed By | Persistence |
|--------|------|---------|-------------|----------|-------------|
| **Reactive Instance** | `seon.web.reactive.instance` | In-memory atom | SSE push via watcher | 4-char hex instance ID | None (ephemeral) |
| **Flow Harness** | `seon.flow.harness` | Datalevin via `persist-ctx!`/`load-ctx!` | None | Namespace string | Datalevin (EDN serialized) |
| **Primer Ctx** | `seon.primer.ctx` | In-memory atom + XTDB checkpoint | SSE refresh-all via watcher | Session ID string | XTDB (background auto-sync) |

All three filter non-serializable values, all three use atoms, and all three solve the same core problem: giving a running process isolated mutable state with persistence and notification.

### Unified Design

One system replaces all three. Key properties:

- **Per-instance, not per-namespace** -- multiple agents/UIs in the same namespace each get their own ctx
- **Keyed by instance ID** (4-char hex, same format as agent sessions)
- **Datalevin entity per instance:**
  ```clojure
  {:ctx/instance-id "a1b2"
   :ctx/namespace   "seon.trading"
   :ctx/data        "<EDN string>"
   :ctx/updated-at  #inst "2026-02-18T..."}
  ```
- **Shared `::conn` per namespace** -- all instances in the same namespace share one Datalevin DB connection (from connection manager)
- **Atom with composite watcher** that does both:
  1. Persist filtered EDN to Datalevin (proven pattern from `flow.harness/persist-ctx!`)
  2. Notify SSE clients (proven pattern from `reactive.instance/make-watch`)
- **Serialization filter** strips `::conn` and other non-EDN values (same `filter-serializable` pattern used by all three current systems)
- **Resume on create** -- if Datalevin has stored data for an instance ID, load it as initial value

### Coverage Matrix

| Capability | reactive.instance | flow.harness | primer.ctx | **Unified** |
|------------|:-:|:-:|:-:|:-:|
| In-memory atom | yes | no (persist only) | yes | yes |
| Datalevin persistence | no | yes | no (XTDB) | yes |
| SSE push on change | yes | no | yes (refresh-all) | yes |
| Per-instance isolation | yes | no (per-ns) | yes (per-session) | yes |
| Resume/reload | no | yes | yes | yes |
| Render function | yes | no | no | yes |
| Shared DB connection | n/a | n/a | n/a | yes (per-ns) |

### Note on Reactive Invalidation

The target architecture uses reactive invalidation (watcher-driven push) rather than polling. The initial implementation may use polling for some UI views; this is acceptable as a stepping stone. The watcher pattern from `reactive.instance` is the proven approach and should be carried forward.

---

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

### Master Database (`/seon/`) — Single Source of Truth

The master database is the **single source of truth** for all code metadata and system state. Everything that needs to understand the codebase — renderer resolution, agent context building, cross-namespace discovery — queries this database. How data gets in (startup scan, Super REPL eval, dev hook, git merge) is just plumbing.

Orchestrator's view of the system:

| Entity | Purpose |
|--------|---------|
| `session/*` | Agent session registry (id, namespace, status, ports) |
| `ai.session/*` | AI session metadata (cost, tokens, duration) |
| `ai.message/*` | All agent messages (for Observatory, replay) |
| `seon.fn/*` | Function entities from code index (specs, docs, render keys) |
| `seon.spec/*` | Malli spec entities (keys, definitions, contains-keys) |
| `seon.ns/*` | Namespace entities (requires, docs, file paths) |
| `seon.call/*` | Call graph edges (from-fn, to-fn) |

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

### Phase 1: Dual-Write AI Domain -- COMPLETE

**Goal:** Verify Datalevin works by writing to both DBs.

**Status:** COMPLETE. See "Stress Testing" section below for implementation details.

#### 1.1 Dual-Write Layer
- [x] Created `src/seon/ai/datalevin.clj` - parallel writes to Datalevin
- [x] Runtime toggle via `seon.ai.datalevin/enabled?` atom
- [x] Errors logged but non-fatal (fire-and-forget)

#### 1.2 AI Session/Message Dual-Write
- [x] Updated `seon.ai/start-session!`, `end-session!`, `add-message!`
- [x] Updated `seon.ai.claude/persist-message!`
- [x] Verification via `(dl/verify-sync)` and `(dl/stats)`

**Success Criteria:**
- [x] All AI data written to both XTDB and Datalevin
- [x] Existing tests still pass (read from XTDB)
- [x] Zero errors after extended use

---

### Phase 2: Read Migration

**Goal:** Switch reads to Datalevin, XTDB becomes backup.

#### 2.1 Query Migration

SQL queries to port to Datalevin Datalog (~25 queries). All are SELECTs with no temporal features -- straightforward translation.

**`seon.ai` (5 queries):**
1. `get-session` -- `SELECT * FROM ai_sessions WHERE _id = ?`
2. `get-messages` -- `SELECT * FROM ai_messages WHERE session_id = ? ORDER BY timestamp`
3. `list-sessions` -- `SELECT * FROM ai_sessions [WHERE ns/status] ORDER BY started_at DESC LIMIT ?`
4. `session-stats` (sessions) -- `SELECT SUM(cost), COUNT(*) FROM ai_sessions`
5. `session-stats` (messages) -- `SELECT COUNT(*), SUM(tokens...) FROM ai_messages`

**`seon.ai.claude` (5 queries):**
6. `agent-result` -- `SELECT * FROM ai_sessions WHERE agent_session_id = ?`
7. `agent-result` (result msg) -- `SELECT content, subtype FROM ai_messages WHERE session_id = ? AND message_type = 'result'`
8. `agent-result` (turn count) -- `SELECT COUNT(*) FROM ai_messages WHERE session_id = ? AND role = 'assistant' AND message_type = 'assistant'`
9. `agent-messages` -- `SELECT * FROM ai_sessions WHERE agent_session_id = ?`
10. `agent-messages` (count) -- `SELECT COUNT(*) FROM ai_messages WHERE session_id = ?`
11. `agent-messages` (recent) -- `SELECT role, content, msg_type, tool_calls FROM ai_messages WHERE session_id = ? ORDER BY timestamp DESC LIMIT ?`

**`seon.web.agents` (6 queries):**
12. `find-ai-session-id` -- `SELECT _id FROM ai_sessions WHERE agent_session_id = ?`
13. `load-session-messages` -- `SELECT * FROM ai_messages WHERE session_id = ? ORDER BY timestamp`
14. `load-session-info` -- `SELECT status, started_at, ended_at, cost, context FROM ai_sessions WHERE _id = ?`
15. `load-context-tokens` -- `SELECT cache_creation_tokens FROM ai_messages WHERE session_id = ? AND message_type = 'result'`
16. `message-stats-by-session` -- `SELECT session_id, COUNT(*), MAX(timestamp) FROM ai_messages GROUP BY session_id`
17. `context-tokens-by-session` -- `SELECT session_id, cache_creation_tokens FROM ai_messages WHERE message_type = 'result'`

- [ ] Port all ~25 queries to Datalog
- [ ] Feature flag: `:db/read-from :datalevin`
- [ ] A/B query comparison logging

#### 2.2 Code Index Entities in Master DB
- [ ] Master DB schema includes `seon.fn/*`, `seon.spec/*`, `seon.ns/*`, `seon.call/*` entities
- [ ] Existing `seon.graph.*` entities (`:graph/*` keys) migrated to new key prefixes
- [ ] Scanner writes code index to master DB (see spec-driven-rendering PRD Phase 1)

#### 2.3 Switch Reads -- COMPLETE (Phase B2)
- [x] `seon.ai` reads from Datalevin (read-from atom flipped to :datalevin)
- [ ] Observatory reads from Datalevin (web/agents.clj still queries XTDB directly -- separate task)
- [x] Stop XTDB writes for AI domain (Datalevin primary, XTDB fallback when no DL connection)

**Success Criteria:**
- [x] AI domain fully on Datalevin (with XTDB fallback for tests/environments without DL)
- [ ] Code index entities stored in master DB
- [x] All tests pass (679 tests, 3130 assertions, 0 AI-related failures)
- [ ] Observatory works (needs verification with running server)

---

### Phase 3: Unified Agent Context System — MOSTLY COMPLETE

**Goal:** Replace all three ctx systems with unified per-instance ctx backed by Datalevin.

#### 3.1 Unified Ctx Module — COMPLETE
- [x] Created `src/seon/ctx.clj` (top-level, shared by all consumers)
- [x] Per-instance atom keyed by instance ID
- [x] Datalevin persistence with debounced writes
- [x] `create!` -- creates atom, attaches composite watcher, loads previous state from Datalevin if exists
- [x] `destroy!` -- removes watchers, cancels scheduled persist, cleans up registry entry

#### 3.2 Composite Watcher (persist + notify)

The atom gets a single watcher that does two things on every state change:

1. **Persist** -- filter non-EDN values, write to Datalevin as `{:ctx/instance-id :ctx/namespace :ctx/data :ctx/updated-at}`. Proven pattern from `seon.flow.harness/persist-ctx!`.
2. **Notify SSE clients** -- render and push to connected channels. Proven pattern from `seon.web.reactive.instance/make-watch`.

```clojure
(defn create-ctx!
  [{::keys [instance-id namespace conn initial-value]}]
  (let [previous (load-ctx conn instance-id)
        ctx-atom (atom (or previous initial-value {}))]
    (add-watch ctx-atom ::persist-and-notify
      (fn [_ _ old new]
        (when (not= old new)
          (persist-ctx! conn instance-id namespace new)
          (notify-sse-clients! instance-id new))))
    ctx-atom))
```

#### 3.3 Migration from Existing Systems
- [ ] Replace `seon.web.reactive.instance` usage with unified ctx (blocked on spec-driven render pipeline — see below)
- [x] `seon.primer.ctx` wraps `seon.ctx` with lazy connection manager
- [x] `seon.orchestrator.session` uses connection manager with lazy `get-namespace-conn!`

**Key Design Points:**
- Multiple agents/UIs in the same namespace each get their own ctx (per-instance, not per-namespace)
- All instances in the same namespace share one Datalevin connection (`::conn`)
- Instance ID is the primary key in Datalevin, not namespace
- Future goal: full reactive invalidation (watcher-driven push replaces all polling)

**Remaining migration — depends on spec-driven rendering:**
The old `seon.web.reactive.instance` has render-fn + client-tracking that the unified `seon.ctx` doesn't yet have. Rather than adding render-fn storage to `seon.ctx` (which would duplicate the old pattern), the plan is to resolve renderers from Datalevin via `seon.render/find-renderer` — turtles all the way down. See `docs/prds/spec-driven-rendering/prd.md` for the render resolution algorithm. Once the render pipeline is wired, `seon.ctx` gets client tracking only (not render-fn storage), and rendering is push-based from scanner invalidation.

**Success Criteria:**
- [x] Agents get `*ctx*` with Datalevin persistence
- [x] State changes trigger debounced persist + optional SSE push
- [x] Session resume loads previous context from Datalevin
- [x] Non-EDN keys filtered from persistence
- [ ] `seon.web.reactive.instance` replaced (depends on render pipeline)
- [ ] `seon.web.reactive.ctx` replaced (depends on render pipeline)

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
- [ ] Remove `seon.primer.ctx` (superseded by unified ctx in Phase 3)
- [ ] Remove `seon.web.reactive.instance` (superseded by unified ctx in Phase 3)
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

## Implementation Progress (Audit: 2026-02-19)

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
- ✅ `get-namespace-conn!` accepts optional `::schema` for per-namespace schemas

### 🔲 Phase 0.3: Schema Compiler NOT STARTED

The Malli→Datalevin schema compiler (`src/seon/schema/datalevin.clj`) does not exist yet.

**Impact:** Schemas are currently passed manually per-namespace via `::conn/schema` param. Graph schema is in `seon.graph.ingest/datalevin-schema`, ctx schema in `seon.ctx/datalevin-schema`, session schema in `seon.orchestrator.session/dl-schema`.

### 🔲 Phase 0.4: Health Integration NOT STARTED

Datalevin is not integrated into `/api/health` endpoint.

### ✅ Phase 1: Dual-Write AI Domain COMPLETE

See "Stress Testing" section below.

### ✅ Phase 2.3: AI Reads Switched to Datalevin COMPLETE

- `seon.ai` reads from Datalevin (read-from atom = `:datalevin`)
- XTDB writes stopped for AI domain
- Code index entities stored in master DB via `seon.graph.ingest`

### ✅ Phase 3: Unified Agent Context System COMPLETE

- `src/seon/ctx.clj` — Per-instance atoms with Datalevin persistence (debounced) and optional SSE push
- `src/seon/primer/ctx.clj` — Session wrapper over `seon.ctx` with lazy connection via connection manager
- `src/seon/orchestrator/session.clj` — Lazy connection via connection manager (no more resolve hacks)
- Both use Integrant keys (`:seon/primer-ctx`, `:seon/orchestrator-sessions`) with proper `#ig/ref` deps
- **NOT YET migrated**: `seon.web.reactive.instance` (20+ callers in `seon.ns.routes`) and `seon.web.reactive.ctx` (used by `seon.web.browser`). These need migration to `seon.ctx` — see spec-driven-rendering PRD for unified render pipeline plan.

### Remaining: Phase 4 (Domain Migration) and Phase 5 (XTDB Removal)

Trading domain still on XTDB. Dev hook events, orchestrator sessions on Datalevin. Observatory web views (`seon.web.agents`) still query XTDB for some data.

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

## Render Contract

**Superseded by:** Spec-Driven Rendering PRD (`docs/prds/spec-driven-rendering/prd.md`).

The render contract is no longer per-namespace `render` functions with `::format` dispatch. Instead, render functions are discovered automatically from the Datalevin code index via Malli `:malli/schema` metadata. Resolution picks the most specific input match by key count, with newest timestamp as tiebreaker. See the spec-driven-rendering PRD for full details.

---

## Related PRDs

- **Super REPL** (`docs/prds/super-repl/prd.md`) -- Runtime flow harness, pool JVMs, cross-namespace calls. Ctx persistence originated here.
- **Namespace UI** (`docs/prds/namespace-ui/`) -- Presentation layer, design system, reactive instance pattern.
- **Spec-Driven Rendering** (`docs/prds/spec-driven-rendering/prd.md`) -- Automatic render function discovery via code index. Replaces the per-namespace render contract. Code index entities (`seon.fn/*`, `seon.spec/*`, `seon.ns/*`, `seon.call/*`) live in the master DB.

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
