# Agent Isolation Architecture

**Status**: Research & Design
**Last Updated**: 2026-01-04

## Vision

Transform Seon into an **orchestrator platform** where:
- The main Seon server manages agent lifecycles and metadata
- Each agent works on an assigned **namespace** with its own isolated nREPL and persisted state
- Namespace is THE unique identifier (e.g., `seon.trading`)
- One agent per namespace at a time (shared infrastructure constraint)
- **Agents just use a `ctx` atom** - persistence, validation, and time-travel happen automatically
- All agent work flows through PRs for proper git integration

## Problem Statement

Current subagent architecture shares everything:
- Same XTDB database (agents can corrupt each other's test data)
- Same working directory (file conflicts)
- Same nREPL session (blocking evals, namespace pollution)
- No visibility into what each agent is doing

## Goals

1. **Namespace-scoped agents** - Each agent owns a namespace (e.g., `seon.trading`)
2. **Isolated state** - Agent changes don't affect main or other agents
3. **Concurrent REPLs** - One agent's long eval doesn't block others
4. **ctx-first design** - Agents just `swap!` an atom; system handles persistence
5. **Automatic time-travel** - Full history of ctx changes via XTDB bitemporality
6. **Progressive complexity** - Start simple, escape to SQL when needed
7. **Orchestrator visibility** - Track all agents, their state, history

---

## Architecture: Shared JVM, Separate nREPLs

```
┌─────────────────────────────────────────────────────────────────┐
│                 Seon Orchestrator (single JVM)                  │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    XTDB Node (shared)                     │  │
│  │     Attached databases per namespace (internal detail)    │  │
│  │           Agent never sees database names                 │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐                │
│  │ nREPL :7888 │ │ nREPL :7889 │ │ nREPL :7890 │  ...           │
│  │(orchestrator)│ │seon.trading │ │ seon.health │                │
│  │             │ │   + *ctx*   │ │   + *ctx*   │                │
│  └─────────────┘ └─────────────┘ └─────────────┘                │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              HTTP Server :8080 (shared)                   │  │
│  │         Routes by X-Namespace header or path              │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

File System:
seon/                              # Main repo (orchestrator view)
├── data/xtdb/                     # Orchestrator DB
├── data/namespaces/               # Per-namespace storage (internal)
│   ├── seon.trading/
│   └── seon.health/
│
../seon-trading/                   # Git worktree for seon.trading
└── src/seon/trading/              # Agent works here
```

### Why This Architecture

| Concern | Solution |
|---------|----------|
| **Concurrent evals** | Separate nREPL per namespace (different threads) |
| **Data isolation** | Separate XTDB database per namespace (agent never sees this) |
| **Memory efficiency** | Shared JVM, shared XTDB node (~200-500MB per namespace) |
| **Simplicity** | Agent just uses `ctx` atom; persistence is automatic |
| **Code isolation** | Git worktrees per namespace |

### Constraints

- **One agent per namespace**: Shared code means no concurrent file edits
- **Shared heap**: One OOM crashes all (rare but possible)
- **Same code version**: All namespaces run same Clojure/XTDB version

---

## Core Concept: The Agent Context (ctx)

**The ctx atom IS the agent's entire world.** Agents don't know about databases, SQL, or persistence. They just `swap!` and `deref` like any Clojure atom.

```clojure
;; Agent's view: just an atom
(swap! *ctx* assoc :current-signal {:symbol "AAPL" :direction :long})
(swap! *ctx* update :signals conj {:symbol "TSLA" :iv-rank 0.85})

;; Read state
(:signals @*ctx*)
```

**Behind the scenes, the system automatically:**
1. Validates state changes against Malli schemas
2. Persists every change to XTDB
3. Preserves full history (time-travel for free)

### What's in ctx?

```clojure
;; The *ctx* dynamic var is bound per-nREPL session
;; Agent can put ANYTHING in here - it's their scratchpad

@*ctx*
;; => {:current-signal {:symbol "AAPL" :direction :long}
;;     :signals [{:symbol "TSLA" :iv-rank 0.85}]
;;     :notes "investigating volatility spike"
;;     ... anything the agent wants ...}
```

The system also provides some reserved keys (prefixed with `:seon.agent/`):

```clojure
{:seon.agent/namespace   'seon.trading      ; Read-only identity
 :seon.agent/db          <xtdb-connection>  ; Escape hatch to SQL (Level 3)
 :seon.agent/render-fn   (fn [hiccup] ...)  ; Push UI updates
 :seon.agent/worktree    "/path/to/worktree"}
```

Most agents ignore `:seon.agent/db`. It's there for when they need SQL performance (see Progressive Complexity below).

### Agent Workflow Example

```clojure
;; Agent code (evaluated in seon.trading namespace via nREPL :7889)

;; Store some state - automatically persisted!
(swap! *ctx* assoc :active-signals
  [{:symbol "AAPL" :direction :long :iv-rank 0.92}
   {:symbol "TSLA" :direction :short :iv-rank 0.78}])

;; Render to web UI
((:seon.agent/render-fn @*ctx*)
  [:div#signals-panel
   [:h2 "Active Signals"]
   (for [s (:active-signals @*ctx*)]
     [:div.signal (:symbol s) " - " (:direction s)])])

;; Later: what was my state 10 minutes ago?
;; (via helper function, if agent learns about it)
(ctx/at *ctx* #inst "2026-01-04T10:00:00")
```

### Progressive Complexity: The Escape Hatch

Agents start with just the ctx atom. When they need more power, they can learn:

| Level | Agent Knowledge | How It Works |
|-------|-----------------|--------------|
| **1. ctx atom** | Just `swap!` and `deref` | Full state snapshot persisted |
| **2. Time travel** | `ctx/at`, `ctx/history` | Query historical states |
| **3. SQL tables** | Use `:seon.agent/db` + invoke `xtdb-queries` skill | Create tables, indexed queries |

**Level 1 is the default.** Most agents never need more.

**Level 3 example** (after learning from skill):
```clojure
;; Agent decides they need a proper signals table for performance
(let [db (:seon.agent/db @*ctx*)]
  ;; Create table and insert
  (xt/execute-tx db [[:put-docs :signals {:xt/id "sig1" :symbol "AAPL"}]])
  ;; Query with SQL
  (xt/q db "SELECT * FROM signals WHERE symbol = ?" ["AAPL"]))
```

---

## Persisted Context: Design Sketch

> **NOTE**: This is pseudocode illustrating the concept. The implementing agent must
> properly research atom watchers, XTDB persistence patterns, validation timing,
> and performance implications. Don't assume this design is correct.

### Conceptual Implementation

```clojure
;; PSEUDOCODE - needs proper research and implementation

(defn make-persisted-ctx
  "Create a ctx atom that auto-persists to XTDB with validation.

   Questions for implementer:
   - Persist on every swap! or debounce?
   - Validate before persist (fail fast) or async?
   - How to handle validation failures?
   - What's the performance impact?"
  [db namespace-sym schema]
  (let [ctx (atom {}
              :validator (fn [state]
                           ;; Malli validation
                           (m/validate schema state)))]
    (add-watch ctx ::persist
      (fn [_ _ old new]
        (when (not= old new)
          ;; Persist to XTDB - exact mechanism TBD
          ;; Could be full snapshot or diff-based
          (persist-ctx-state! db namespace-sym new))))
    ctx))

(defn ctx-at
  "Retrieve ctx state at a specific point in time.
   Leverages XTDB's bitemporal queries."
  [db namespace-sym instant]
  ;; Query historical state - implementation TBD
  ...)

(defn ctx-history
  "Get timeline of ctx changes.
   Useful for debugging: 'what did the agent do?'"
  [db namespace-sym]
  ;; Query all valid-time versions - implementation TBD
  ...)
```

### Open Design Questions

1. **Persistence granularity** - Every `swap!`? Debounced? Per "turn"?
2. **Storage format** - Full state snapshot vs incremental diffs?
3. **Schema evolution** - What happens when ctx structure changes?
4. **Validation errors** - Throw? Log and skip? Rollback?
5. **Reserved keys** - How to protect `:seon.agent/*` keys from agent modification?
6. **Performance** - Acceptable latency for persisted swap!?

### RESEARCH COMPLETE

See `docs/prds/agent-isolation/research/nrepl-multi-server.md` for full details.

#### 1. How to inject ctx?

**RESOLVED**: Use custom nREPL middleware that injects `*ctx*` dynamic var into sessions.

```clojure
(def ^:dynamic *ctx* nil)

(defn make-context-middleware [ctx-atom target-ns]
  (fn wrap-context [handler]
    (fn [{:keys [session] :as msg}]
      (when (and session (not (contains? @session #'*ctx*)))
        (swap! session assoc
               #'*ns* (find-ns target-ns)
               #'*ctx* ctx-atom))
      (handler msg))))
```

The middleware runs after `session` (to access the session atom) and before `eval` (to set context).

#### 2. Multiple nREPLs in one JVM?

**RESOLVED: YES** - nREPL fully supports multiple servers.

Each call to `nrepl.server/start-server` creates an independent server with:
- Its own `ServerSocket` (unique port)
- Its own `open-transports` atom (connection tracking)
- Its own `handler` (can be customized per server)

The global `sessions` atom is keyed by UUID, so sessions across servers don't conflict.

**Proof**: nREPL's own test suite runs two servers simultaneously (`test-ack` in `core_test.clj`).

#### 3. Error handling?

**RESOLVED**:
- Port conflicts throw `java.net.BindException`
- Use `:port 0` to auto-assign an available port
- Server implements `java.io.Closeable` for clean shutdown

#### 4. Namespace binding at startup?

**RESOLVED**: Set `#'*ns*` in the session atom via middleware:

```clojure
(swap! session assoc #'*ns* (find-ns 'seon.trading))
```

### REMAINING RESEARCH NEEDED

1. **Does render-fn work with Datastar SSE?**
   - Need to scope SSE sessions per namespace
   - How does agent trigger re-renders?

---

## Orchestrator Database Schema

The primary `xtdb` database tracks namespaces and agents:

```clojure
;; Namespace registration
{:xt/id :seon.trading
 :namespace/name 'seon.trading
 :namespace/status :active           ; :active, :locked, :archived
 :namespace/db-attached? true
 :namespace/nrepl-port 7889
 :namespace/current-agent nil}       ; or session-id if occupied

;; Agent session (when active)
{:xt/id :agent-session/trading-20260104-abc123
 :agent/session-id "trading-20260104-abc123"
 :agent/namespace 'seon.trading
 :agent/worktree-path "../seon-trading"
 :agent/branch "agent/trading/20260104-abc123"
 :agent/started-at #inst "2026-01-04T..."
 :agent/status :running}             ; :running, :completed, :failed
```

---

## Implementation Phases

### Phase 1: XTDB Multi-Database (internal infrastructure) - COMPLETE

**Completed**: 2026-01-04. See `docs/prds/xtdb-sql-migration/prd.md` for full details.

- [x] Single XTDB node with attached databases (replaces 3 separate nodes)
- [x] `seon.db.multi` namespace with attach/detach/connection APIs
- [x] Naming: `seon.trading` → database `seon_trading` → path `data/namespaces/seon.trading/`
- [x] 16 tests in `test/seon/db/multi_test.clj`

**Current state**: System running with `xtdb`, `seon_primer`, `seon_dev` databases.

### Phase 2: Multiple nREPL Servers - COMPLETE

**Completed**: 2026-01-04

- [x] Research: Can nREPL start multiple servers in one JVM? **YES - CONFIRMED**
- [x] Research: How to bind namespace at startup? **Via custom middleware**
- [x] Research: Error handling and lifecycle? **Clean API, java.io.Closeable**
- [x] Implement `start-namespace-nrepl!` function
- [x] Implement port allocation (7889, 7890, ...)
- [x] Custom middleware for `*ctx*` and `*ns*` injection
- [x] Add Integrant component `:seon.orchestrator/namespace-nrepls`
- [x] Fix ctx injection tests - middleware descriptor now uses var reference

**Implementation Summary:**

Created `src/seon/orchestrator/nrepl.clj` with:
- `*ctx*` dynamic var for agent context (contains `:seon.agent/namespace`, `:seon.agent/db`, etc.)
- Thread-safe port allocation (7889-7999 range) with `allocate-port!` and `release-port!`
- `start-namespace-nrepl!` - Starts nREPL with custom middleware that injects `*ctx*` and `*ns*`
- `stop-namespace-nrepl!` - Clean shutdown with port release
- `list-namespace-servers` - Query running servers
- Custom middleware that creates namespaces with `clojure.core` referred

**Integrant Component:**
- `:seon.orchestrator/namespace-nrepls` in `system.clj`
- Survives `(reset)` via `suspend-key!`/`resume-key`
- Creates db connections for each namespace if node provided

**Key Fix (2026-01-04):**
The ctx injection tests were flaky because the middleware descriptor used `"session"` (a string operation name) instead of `#'nrepl.middleware.session/session` (a var reference). The nREPL middleware linearization only recognizes operation names that appear in a middleware's `:handles` map. Since no middleware "handles" an operation called "session", the dependency wasn't recognized and our middleware ran BEFORE the session middleware, seeing session IDs as strings instead of atoms.

**Tests:** 17 tests, 54 assertions in `test/seon/orchestrator/nrepl_test.clj`

### Phase 3: Persisted Context (ctx atom)
> **This is the key innovation.** Agent just uses an atom; we handle persistence.

#### Phase 3a: Research - COMPLETE

**Completed**: 2026-01-04. See `docs/prds/agent-isolation/research/persisted-ctx.md` for full details.

**Summary of Findings**:

1. **Non-blocking persistence**: Use `add-watch` + Clojure `agent` with `send-off`
   - Adds only ~660 ns overhead to `swap!` (97ns -> 757ns)
   - Agent queues writes sequentially, never blocks caller
   - With debouncing (50-100ms), 100 rapid updates collapse to 1 persist

2. **Storage format**: Full snapshots (not diffs)
   - Typical ctx is 5-20KB - trivial for XTDB
   - Diffs add complexity without proportional benefit given debouncing
   - XTDB's bitemporality handles versioning natively

3. **XTDB patterns**:
   - Store snapshots in `ctx_snapshots` table with `namespace`, `state` (EDN), `created_at`
   - Time travel via `FOR SYSTEM_TIME AS OF ?` queries
   - History via `FOR ALL SYSTEM_TIME` queries
   - ~1ms per temporal query

4. **Validation**: Synchronous before persist queue
   - Malli validation is fast (~1.6 us with compiled validator)
   - Log and skip invalid states (don't block swap!)

5. **Benchmarks established**:
   - Plain atom: 97 ns/op
   - Persisted atom (with debounce): ~2,300 ns/op (24x baseline but still instant)
   - XTDB execute-tx: 7.1 ms/op (why we need async)
   - XTDB temporal query: 1.0 ms/op

**Recommended Architecture**:
```
swap! → add-watch → validate (sync) → debounce timer → agent → XTDB
                    (~1.6 us)        (50-100ms)      (async)  (~7ms)
```

#### Phase 3b: Implementation

**Design Decisions** (confirmed 2026-01-04):

1. **Debounce window**: 1 second (for time-travel/backup, not recording every change)

2. **Validation rules** (STRICT - reject invalid updates):
   - Value must be a map
   - ALL keys must be fully namespaced (`:seon.trading/signals`, not `:signals`)
   - Each key must have a Malli spec registered in `seon.db.schema/registry`
   - The value for each key must validate against its spec
   - Reserved `:seon.agent/*` keys are immutable after creation

3. **Validation failure behavior**:
   - **Reject the `swap!`** - invalid data never enters the atom
   - Return clear error message explaining exactly what failed and how to fix it
   - Agent sees the error immediately (not async)

4. **Recovery on startup**: Load latest persisted state from XTDB

5. **Time travel semantics**:
   - `at` is **read-only** - queries historical state, doesn't modify atom
   - `restore!` sets atom to historical state **without triggering persist**
   - Uses `::restoring` metadata flag to skip the watch during restore
   - History is **never deleted** - only agent `swap!` creates new snapshots
   - Next `swap!` after restore persists normally (from the restored state)

**Implementation Tasks**:

- [ ] Create `seon.agent.ctx` namespace
- [ ] Register all schemas following CONVENTIONS.md pattern
- [ ] Implement `make-persisted-ctx` with:
  - Strict key validation (namespaced keys only)
  - Per-key Malli spec lookup and validation
  - Clear error messages on validation failure
  - 1s debounced persistence via agent
  - Reserved key protection (`:seon.agent/*` immutable)
  - `::restoring` metadata flag check to skip persist
- [ ] Implement `at` for read-only time-travel queries
- [ ] Implement `history` for debugging (list all snapshots)
- [ ] Implement `restore!` that:
  - Sets `::restoring` metadata flag
  - Resets atom to historical state
  - Clears flag (no persist triggered)
- [ ] Implement startup recovery (load latest on nREPL start)
- [ ] Create XTDB table `ctx_snapshots` with proper schema
- [ ] Comprehensive tests (see test list below)
- [ ] Performance tests confirming non-blocking behavior

**Error Message Examples** (agent-friendly):

```clojure
;; Non-namespaced key
(swap! *ctx* assoc :signals [...])
;; => ExceptionInfo: Invalid ctx key :signals
;;    All keys must be fully namespaced (e.g., :seon.trading/signals)
;;    To fix: Use (swap! *ctx* assoc :seon.trading/signals [...])

;; Missing spec
(swap! *ctx* assoc :seon.trading/foo "bar")
;; => ExceptionInfo: No spec registered for key :seon.trading/foo
;;    Register a Malli spec in seon.db.schema/registry first.
;;    To fix: Add [:seon.trading/foo <schema>] to the schema registry

;; Spec validation failure
(swap! *ctx* assoc :seon.trading/signals "not-a-vector")
;; => ExceptionInfo: Value for :seon.trading/signals failed validation
;;    Expected: [:vector :seon.trading/signal]
;;    Got: "not-a-vector"
;;    To fix: Provide a vector of signal maps

;; Reserved key modification
(swap! *ctx* assoc :seon.agent/namespace 'something-else)
;; => ExceptionInfo: Cannot modify reserved key :seon.agent/namespace
;;    Reserved :seon.agent/* keys are set by the system and immutable.
```

**API Surface** (following CONVENTIONS.md - map in, map out):

```clojure
(ns seon.agent.ctx
  (:require [seon.db.schema :as schema]))

;;; Schema Registration

(schema/register! ::db
                  [:any {:description "XTDB database connection"}])

(schema/register! ::namespace
                  [:symbol {:description "Agent namespace symbol"}])

(schema/register! ::debounce-ms
                  [:int {:min 100 :max 60000
                         :description "Debounce window in milliseconds"}])

(schema/register! ::instant
                  [:inst {:description "Point in time for time-travel"}])

(schema/register! ::atom
                  [:any {:description "The persisted ctx atom"}])

(schema/register! ::state
                  [:map {:description "Ctx state (namespaced keys only)"}])

;;; Request/Response Schemas

(schema/register! ::make-request
                  [:map
                   [::db ::db]
                   [::namespace ::namespace]
                   [::debounce-ms {:optional true} ::debounce-ms]])

(schema/register! ::make-response
                  [:map
                   [::atom ::atom]
                   [::flush! [:fn {:description "Force immediate persist"}]]
                   [::close! [:fn {:description "Cleanup resources"}]]])

(schema/register! ::at-request
                  [:map
                   [::db ::db]
                   [::namespace ::namespace]
                   [::instant ::instant]])

(schema/register! ::at-response
                  [:map
                   [::state ::state]
                   [::system-time ::instant]])

(schema/register! ::history-request
                  [:map
                   [::db ::db]
                   [::namespace ::namespace]])

(schema/register! ::history-response
                  [:map
                   [::snapshots [:vector [:map
                                          [::state ::state]
                                          [::system-time ::instant]]]]])

(schema/register! ::restore-request
                  [:map
                   [::atom ::atom]
                   [::db ::db]
                   [::namespace ::namespace]
                   [::instant ::instant]])

(schema/register! ::restore-response
                  [:map
                   [::state ::state]
                   [::restored-from ::instant]])

(schema/register! ::load-latest-request
                  [:map
                   [::db ::db]
                   [::namespace ::namespace]])

(schema/register! ::load-latest-response
                  [:map
                   [::state [:maybe ::state]]
                   [::system-time [:maybe ::instant]]])

;;; Public API

(defn make-persisted-ctx
  "Create a persisted context atom for an agent.

   Request keys:
     ::db          - Required. XTDB database connection
     ::namespace   - Required. Agent namespace symbol
     ::debounce-ms - Optional. Debounce window (default: 1000)

   Response keys:
     ::atom   - The persisted ctx atom
     ::flush! - Force immediate persist (for shutdown)
     ::close! - Cleanup resources

   Example:
     (make-persisted-ctx {::db conn ::namespace 'seon.trading})
     ;; From outside namespace:
     (ctx/make-persisted-ctx {::ctx/db conn ::ctx/namespace 'seon.trading})"
  {:malli/schema [:=> [:cat ::make-request] ::make-response]}
  [{::keys [db namespace debounce-ms]}]
  ...)

(defn at
  "Get ctx state at a specific point in time (read-only time-travel).

   Request keys:
     ::db        - Required. XTDB database connection
     ::namespace - Required. Agent namespace symbol
     ::instant   - Required. Point in time

   Response keys:
     ::state       - The ctx state at that time
     ::system-time - Actual XTDB system time of the snapshot

   Example:
     (at {::db conn ::namespace 'seon.trading ::instant #inst \"2026-01-04T10:00\"})"
  {:malli/schema [:=> [:cat ::at-request] ::at-response]}
  [{::keys [db namespace instant]}]
  ...)

(defn history
  "Get all historical ctx snapshots for a namespace.

   Request keys:
     ::db        - Required. XTDB database connection
     ::namespace - Required. Agent namespace symbol

   Response keys:
     ::snapshots - Vector of {::state, ::system-time} maps

   Example:
     (history {::db conn ::namespace 'seon.trading})"
  {:malli/schema [:=> [:cat ::history-request] ::history-response]}
  [{::keys [db namespace]}]
  ...)

(defn restore!
  "Restore ctx to a historical state WITHOUT triggering persistence.
   The restored state is already in XTDB history.

   Request keys:
     ::atom      - Required. The persisted ctx atom
     ::db        - Required. XTDB database connection
     ::namespace - Required. Agent namespace symbol
     ::instant   - Required. Point in time to restore to

   Response keys:
     ::state         - The restored state
     ::restored-from - The instant restored from

   Example:
     (restore! {::atom *ctx* ::db conn ::namespace 'seon.trading
                ::instant #inst \"2026-01-04T10:00\"})"
  {:malli/schema [:=> [:cat ::restore-request] ::restore-response]}
  [{::keys [atom db namespace instant]}]
  ...)

(defn load-latest
  "Load the most recent persisted state for a namespace.
   Used for recovery on startup.

   Request keys:
     ::db        - Required. XTDB database connection
     ::namespace - Required. Agent namespace symbol

   Response keys:
     ::state       - The latest state (nil if none)
     ::system-time - When it was persisted (nil if none)

   Example:
     (load-latest {::db conn ::namespace 'seon.trading})"
  {:malli/schema [:=> [:cat ::load-latest-request] ::load-latest-response]}
  [{::keys [db namespace]}]
  ...)
```

**Test Cases**:

- [ ] Valid swap! persists after debounce
- [ ] Invalid swap! rejected with clear error
- [ ] Non-namespaced key rejected
- [ ] Missing spec rejected
- [ ] Spec validation failure rejected
- [ ] Time travel returns correct historical state
- [ ] Restore does NOT create new snapshot
- [ ] Swap after restore DOES persist
- [ ] Startup loads latest state
- [ ] Reserved keys cannot be modified
- [ ] Generative tests with schema-generated inputs

### Phase 4: Agent Context Injection
- [x] Inject `*ctx*` dynamic var via nREPL middleware (done in Phase 2)
- [x] Populate reserved keys (namespace, db, render-fn, worktree) (done in Phase 2)
- [ ] Test agent can swap! and see persistence (requires Phase 3)
- [ ] Test time-travel works (requires Phase 3)

### Phase 5: Web Routing
- [ ] Route by `X-Namespace` header
- [ ] Scope SSE sessions per namespace
- [ ] Agent render-fn delivers to correct session

### Phase 6: Git Worktree Integration
- [ ] Create worktree when namespace agent starts
- [ ] Branch naming: `agent/{namespace}/{date}`
- [ ] Worktree cleanup/archival
- [x] **RESEARCH COMPLETE**: Worktree code reloading into shared JVM
  - clj-reload can load from non-classpath directories via `Compiler/load`
  - One active worktree at a time (global `*config*` state)
  - Agents must work on non-overlapping namespaces
  - See `docs/prds/agent-isolation/research/worktree-reloading.md`
- [ ] Implement `activate-agent!` and `deactivate-agent!` functions
- [ ] Implement `reload-agent-namespaces!` for targeted reload

### Phase 7: Agent Lifecycle
- [ ] `start-namespace-agent!` - preps everything
- [ ] `stop-namespace-agent!` - cleanup
- [ ] Lock namespace while agent active

---

## Open Questions

### Resolved
1. ~~**ctx injection mechanism**~~ - Dynamic var `*ctx*` via custom middleware
2. ~~**nREPL multi-server**~~ - Fully supported, see research doc
3. ~~**Worktree sync**~~ - clj-reload can load from worktree dirs, one agent at a time

### Open
4. **Persisted ctx implementation** - See design questions in "Persisted Context" section
5. **Datastar SSE scoping** - How to isolate SSE per namespace?
6. **Agent POST handling** - How does agent handle form submissions?
7. **Schema for agent state** - Open schema? Per-namespace? Evolvable?

---

## Related Research

- `docs/prds/agent-isolation/research/nrepl-multi-server.md` - **nREPL multi-server research (COMPLETE)**
- `docs/prds/agent-isolation/research/worktree-reloading.md` - **Worktree code reloading research (COMPLETE)**
- `docs/prds/agent-isolation/research/complete-isolation.md` - Full JVM isolation research
- `reference-code/nrepl/` - nREPL source code (git submodule)
- `reference-code/clj-reload/` - clj-reload source code (git submodule)
- `reference-code/xtdb/docs/src/content/docs/about/dbs-in-xtdb.md` - XTDB multi-database
- `reference-code/xtdb/src/test/clojure/xtdb/sql/multi_db_test.clj` - Multi-DB tests
