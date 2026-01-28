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
