# Agent Isolation Architecture

**Status**: Phase 4d Complete, Phase 7 in progress
**Last Updated**: 2026-01-09

## Current Status

**Completed:**
- Phase 1: Multi-database XTDB (`seon.db.multi`)
- Phase 2: Multi-server nREPL (`seon.orchestrator.nrepl`)
- Phase 3b: Persisted context (`seon.agent.ctx`)
- Phase 4: Agent Session API (`seon.orchestrator.session`)
- Phase 4b: MCP Agent Eval Tool (`bin/mcp-server`)
- Phase 4c: Session Observability (`list_sessions` with uptime, activity tracking, eval counts)
- Phase 4d: Persistent nREPL Sessions (`clone` op for *1/*2/*3, `interrupt_eval` tool)
- Claude SDK research complete (see `research/` folder)
- Claude SDK Phase 1 complete (`seon.claude.sdk` - query/exec functions)

**In Progress:**
- Phase 7: Clojure Claude SDK implementation (see `docs/prds/clojure-claude-sdk/prd.md`)

**Remaining:**
- Phase 5: Web Routing (SSE scoping)
- Phase 6: Git Worktree Integration
- Phase 7: Full Agent Lifecycle

**Next:** Implement Clojure Claude SDK (enables Phase 7's `launch-agent!`)

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
;; Agent's view: just an atom (all keys must be namespaced)
(swap! *ctx* assoc :seon.trading/current-signal {:symbol "AAPL" :direction :long})
(swap! *ctx* update :seon.trading/signals conj {:symbol "TSLA" :iv-rank 0.85})

;; Read state
(:seon.trading/signals @*ctx*)
```

**Behind the scenes, the system automatically:**
1. Validates state changes against Malli schemas
2. Persists every change to XTDB
3. Preserves full history (time-travel for free)

### What's in ctx?

```clojure
;; The *ctx* dynamic var is bound per-nREPL session
;; Agent stores state with namespaced keys (auto-persisted)

@*ctx*
;; => {:seon.trading/current-signal {:symbol "AAPL" :direction :long}
;;     :seon.trading/signals [{:symbol "TSLA" :iv-rank 0.85}]
;;     :seon.trading/notes "investigating volatility spike"
;;     :seon.agent/namespace seon.trading   ; reserved, read-only
;;     :seon.agent/db <xtdb-connection>}    ; reserved, read-only
```

The system also provides some reserved keys (prefixed with `:seon.agent/`):

```clojure
{:seon.agent/namespace   'seon.trading      ; Read-only identity
 :seon.agent/db          <xtdb-connection>  ; Direct SQL access (Level 3)
 :seon.agent/render      (fn [hiccup] ...)  ; Push UI updates (Phase 5)
 :seon.agent/worktree    "/path/to/worktree"} ; Git worktree (Phase 6)
```

Most agents ignore `:seon.agent/db`. It's there for when they need SQL performance (see Progressive Complexity below).

### Agent Workflow Example

```clojure
;; Agent code (evaluated in seon.trading namespace via nREPL :7889)

;; Store state in ctx - automatically persisted!
(swap! *ctx* assoc :seon.trading/active-signals
  [{:symbol "AAPL" :direction :long :iv-rank 0.92}
   {:symbol "TSLA" :direction :short :iv-rank 0.78}])

;; Render to web UI
((:seon.agent/render @*ctx*)
  [:div#signals-panel
   [:h2 "Active Signals"]
   (for [s (:seon.trading/active-signals @*ctx*)]
     [:div.signal (:symbol s) " - " (:direction s)])])

;; Later: what was my state 10 minutes ago?
(ctx/at {::ctx/db (:seon.agent/db @*ctx*)
         ::ctx/namespace 'seon.trading
         ::ctx/instant #inst "2026-01-04T10:00:00"})
```

### Progressive Complexity: The Escape Hatch

Agents start with just the ctx atom. When they need more power, they can learn:

| Level | Agent Knowledge | How It Works |
|-------|-----------------|--------------|
| **1. ctx atom** | Just `swap!` and `deref` | Full state snapshot persisted |
| **2. Time travel** | `ctx/at`, `ctx/history` | Query historical states |
| **3. SQL tables** | `sql`, `sql!`, `sql-batch!` helpers | Create tables, indexed queries |

**Level 1 is the default.** Most agents never need more.

**Level 3: SQL Helpers** (available in `seon.agent.helpers`):

```clojure
;; The helpers use *ctx* implicitly - no need to extract :seon.agent/db

;; Query - returns vector of keyword maps
(sql "SELECT * FROM signals")
(sql "SELECT * FROM signals WHERE symbol = ?" "AAPL")
;; => [{:xt/id "sig-1" :symbol "AAPL" :direction "long" :iv_rank 0.92}]

;; Insert (table created implicitly on first INSERT)
(sql! "INSERT INTO signals (_id, symbol, direction, iv_rank) VALUES (?, ?, ?, ?)"
      "sig-1" "AAPL" "long" 0.92)

;; Batch insert - multiple rows in one transaction
(sql-batch! "INSERT INTO signals (_id, symbol, direction) VALUES (?, ?, ?)"
            ["sig-1" "AAPL" "long"]
            ["sig-2" "TSLA" "short"])

;; Update and delete
(sql! "UPDATE signals SET direction = ? WHERE _id = ?" "short" "sig-1")
(sql! "DELETE FROM signals WHERE _id = ?" "sig-1")
```

**SQL column naming**: Use `snake_case` for columns. Namespaced keywords use `$` separator:
`:signal/symbol` → `signal$symbol` in SQL.

**Alternative**: Use XTDB API directly via `:seon.agent/db` (see `xtdb-queries` skill).

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

- [x] Create `seon.agent.ctx` namespace
- [x] Register all schemas following CONVENTIONS.md pattern
- [x] Implement `make-persisted-ctx` with:
  - Strict key validation (namespaced keys only)
  - Per-key Malli spec lookup and validation
  - Clear error messages on validation failure
  - 1s debounced persistence via agent
  - Reserved key protection (`:seon.agent/*` immutable)
  - `::restoring` metadata flag check to skip persist
- [x] Implement `at` for read-only time-travel queries
- [x] Implement `history` for debugging (list all snapshots)
- [x] Implement `restore!` that:
  - Sets `::restoring` metadata flag
  - Resets atom to historical state
  - Clears flag (no persist triggered)
- [x] Implement startup recovery (load latest on nREPL start)
- [x] Create XTDB table `ctx_snapshots` with proper schema
- [x] Comprehensive tests (see test list below)
- [ ] Performance tests confirming non-blocking behavior

**Implementation Summary** (completed 2026-01-06):

Created `src/seon/agent/ctx.clj` with full persisted context implementation:

**Core Components:**
- `make-persisted-ctx` - Creates a validated, auto-persisting atom
- `at` - Read-only time-travel to query historical state
- `history` - Returns all historical snapshots for debugging
- `restore!` - Restores atom to historical state without triggering persist
- `load-latest` - Loads most recent state for startup recovery

**Key Implementation Details:**

1. **Validation**: Strict synchronous validation via atom `:validator`
   - Rejects non-namespaced keys with helpful error messages
   - Rejects keys without registered Malli specs
   - Validates values against their registered specs
   - Protects reserved `:seon.agent/*` keys from modification/removal

2. **Persistence**: Debounced async writes via `ScheduledThreadPoolExecutor` + Clojure agent
   - `add-watch` triggers on state changes
   - Debounce timer (default 1s) coalesces rapid updates
   - Agent handles async XTDB writes without blocking `swap!`
   - Reserved keys filtered out before serialization (contain non-EDN objects)

3. **Time Travel**: Leverages XTDB's `FOR SYSTEM_TIME AS OF` and `FOR ALL SYSTEM_TIME`
   - `at` queries state at specific instant
   - `history` returns chronological list of all snapshots
   - `restore!` uses `::restoring` metadata to skip persist watch

4. **Startup Recovery**: `make-persisted-ctx` automatically loads latest state
   - Merges persisted state with fresh reserved keys
   - Handles missing snapshots gracefully (starts with empty state)

**Tests:** 17 tests, 25 assertions in `test/seon/agent/ctx_test.clj`
- Validation rejection tests (non-namespaced, missing spec, spec failure, reserved keys)
- Persistence tests (debounce, flush)
- Time travel tests (at, history)
- Restore tests (no new snapshot, state restoration, swap after restore)
- Startup recovery tests

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

**Test Cases** (all passing - 17 tests, 25 assertions):

- [x] Valid swap! persists after debounce
- [x] Invalid swap! rejected with clear error
- [x] Non-namespaced key rejected
- [x] Missing spec rejected
- [x] Spec validation failure rejected
- [x] Time travel returns correct historical state
- [x] Restore does NOT create new snapshot
- [x] Swap after restore DOES persist
- [x] Startup loads latest state
- [x] Reserved keys cannot be modified
- [x] Reserved keys cannot be removed
- [x] Reserved keys present on creation
- [x] Flush immediately persists pending state
- [x] Load latest returns nil when no snapshots
- [x] Load latest returns most recent state
- [x] History returns all snapshots in order
- [x] Generative tests pass (via gen/fmap skip for db connections)

**Manual REPL Verification** (2026-01-06):

All features verified working in live REPL session:
- Valid `swap!` with namespaced keys works
- Non-namespaced key rejection with clear error message
- Missing spec rejection with helpful fix suggestion
- Spec validation failure shows expected vs got
- Reserved key protection prevents modification
- Time travel (`at`) returns correct historical state
- History shows chronological snapshots (count: 5 → 100 → 999)
- Restore does NOT create new snapshot (verified count unchanged)
- Swap after restore DOES persist (new snapshot created)

### Phase 4: Agent Session API - COMPLETE

**Completed**: 2026-01-06

**Goal**: Provide a simple, opaque session abstraction so agents don't need to know about XTDB, nREPL ports, or persistence mechanics.

#### Implementation Summary

Created `src/seon/orchestrator/session.clj` with the full session API:

**Public Functions** (following CONVENTIONS.md - map in, map out):

- `start-agent-session!` - Creates everything, returns session info
  - Generates 8-char hex session ID via `SecureRandom`
  - Auto-attaches XTDB database if not exists (`multi/ensure-namespace-db!`)
  - Creates namespace connection (`multi/create-namespace-connection`)
  - Creates persisted ctx (`ctx/make-persisted-ctx`) with automatic resume
  - Starts namespace nREPL with the persisted ctx atom injected
  - Stores session in XTDB orchestrator database
  - Returns `{::id, ::namespace, ::status, ::nrepl-port, ::started-at, ::db-name}`

- `stop-agent-session!` - Cleans up everything
  - Flushes pending ctx state via `flush!`
  - Stops namespace nREPL
  - Closes persisted ctx via `close!`
  - Closes namespace database connection
  - Updates session status in XTDB
  - Returns `{::id, ::status, ::stopped-at}`

- `list-agent-sessions` - Returns active sessions from in-memory registry
- `get-agent-session` - Returns single session info (checks registry then XTDB)
- `get-session-port` - Returns nREPL port for session ID (used by agent-eval)
- `recover-sessions!` - Marks orphaned "running" sessions as stopped on startup

**CLI Tool**: `bin/agent-eval`

Babashka script that provides opaque session-based evaluation:
```bash
# Usage: agent-eval <session-id> '<clojure-code>'
agent-eval acdb234f '(+ 1 2)'
agent-eval acdb234f '(swap! *ctx* assoc :seon.trading/signals [...])'
```

The script:
1. Validates session ID format (8 hex chars)
2. Queries orchestrator for session-id → port mapping via nREPL
3. Evaluates code on the agent's namespace nREPL
4. Returns result (stdout/stderr/value)
5. Handles errors gracefully (session not found, connection failed)

**Integration with Phase 2 (nREPL)**:

Modified `start-namespace-nrepl!` to accept optional `:ctx-atom` parameter:
- When provided, uses the external atom (persisted ctx) directly
- When not provided, creates a plain atom (backward compatible)
- Skips updating reserved keys on external atoms (they have protection)

**Tests**: 12 tests, 35 assertions in `test/seon/orchestrator/session_test.clj`

- Session ID format (8 hex chars)
- Session lifecycle (start, stop, cleanup)
- Session query functions (get, list, port)
- nREPL integration (connect, eval, *ctx* available, *ns* bound)
- Session resume (start, add data, stop, resume with data restored)
- Multiple sessions isolation (separate ports, separate ctx)
- Session recovery (marks orphaned sessions as stopped)

#### API Examples

**Orchestrator's view** (Clojure API):
```clojure
;; Start a fresh session
(start-agent-session! {::session/node xtdb-node ::session/namespace 'seon.trading})
;; => {::session/id "acdb234f"
;;     ::session/namespace 'seon.trading
;;     ::session/status :running
;;     ::session/nrepl-port 7889
;;     ::session/started-at #inst "..."
;;     ::session/db-name "seon_trading"}

;; Resume existing session (loads previous ctx state - default behavior)
(start-agent-session! {::session/node xtdb-node
                       ::session/namespace 'seon.trading
                       ::session/resume? true})

;; List active sessions
(list-agent-sessions {::session/node xtdb-node})
;; => [{::session/id "acdb234f" ::session/namespace 'seon.trading ...}]

;; Stop session (flushes ctx, stops nREPL)
(stop-agent-session! {::session/node xtdb-node ::session/id "acdb234f"})
```

**Agent's view** (CLI tool):
```bash
# Agent receives session-id from orchestrator, uses it for all evals
agent-eval acdb234f '(swap! *ctx* assoc :seon.trading/signals [...])'
agent-eval acdb234f '(:seon.trading/signals @*ctx*)'
agent-eval acdb234f '(search "XTDB temporal queries")'

# That's it. Agent doesn't know about:
# - nREPL ports
# - XTDB databases
# - Persistence mechanics
# - Connection management
```

#### What's Hidden from Agent

| Concern | Handled By |
|---------|------------|
| XTDB database creation | `start-agent-session!` auto-attaches if needed |
| nREPL port allocation | Session registry maps session-id → port |
| ctx persistence | `seon.agent.ctx` handles debounced writes |
| State recovery | `resume?` flag loads latest persisted state (default: true) |
| Cleanup | `stop-agent-session!` flushes and closes everything |

#### What Agent Gets

1. **Session ID** - Opaque 8-char hex identifier (e.g., "acdb234f")
2. **`*ctx*` atom** - Just works, persistence is transparent
3. **`*ns*`** - Bound to assigned namespace
4. **Helper functions** - `search`, `ask` for research

#### Agent Instructions Template

When launching an agent, orchestrator provides:

```
You have been assigned session ID: a1b2
Namespace: seon.trading

To evaluate Clojure code, use the eval tool:

  eval(session_id="a1b2", code="(your-code-here)")

Your context atom `*ctx*` is available. Use namespaced keys:

  eval(session_id="a1b2", code="(swap! *ctx* assoc :seon.trading/signals [...])")
  eval(session_id="a1b2", code="(:seon.trading/signals @*ctx*)")

Helper functions from user namespace (qualify with user/):

  eval(session_id="a1b2", code="(user/reload)")           ; Reload changed code
  eval(session_id="a1b2", code="(user/search \"query\")")  ; Web search via Gemini
  eval(session_id="a1b2", code="(user/status)")           ; System status

All state is automatically persisted. You don't need to save anything manually.
Each eval response includes the current namespace (;; ns: seon.trading).
```

### Phase 4b: MCP Agent Eval Tool - COMPLETE

**Completed**: 2026-01-08

Created `bin/mcp-server` (Babashka) that exposes an `agent_eval` MCP tool. Claude calls it directly with JSON parameters - no shell escaping issues.

See: [mcp-agent-eval.md](mcp-agent-eval.md) for full details.

- [x] Research MCP stdio protocol (JSON-RPC 2.0, line-delimited)
- [x] Implement MCP server in Babashka (~200 lines, fast startup)
- [x] Register `agent_eval` tool with session lookup
- [x] Test with Claude Code (verified all special chars work)
- [x] Configure via `claude mcp add` (creates `.mcp.json`)
- [x] Keep `bin/agent-eval` for manual debugging

### Phase 4c: Session Observability & Timeout Hardening

**Status**: Complete (2026-01-09)
**Priority**: High (discovered blocking issue in production)

**Problem Discovered (2026-01-09)**:

During testing of the Clojure Claude SDK, agent evals started hanging indefinitely. Investigation revealed:

1. **Root Cause**: Code in an nREPL session called `spawn-claude-code` directly and then used `slurp` to read the subprocess stdout
2. **Effect**: `slurp` blocks until EOF, but the Claude subprocess never terminates (interactive)
3. **Result**: nREPL session thread blocked for 13+ minutes, REPL appeared hung
4. **Fix Applied**: Made `spawn-claude-code` private in `seon.claude.sdk` with warning about blocking IO

**Architectural Insight**:

```
Subprocess stdout/stderr → blocking read (slurp) → nREPL thread blocked
                                                     ↓
                                           All evals to that session hang
```

The JVM thread dump showed:
```
"nREPL-ephemeral-session-6" - elapsed=783.55s
   java.lang.Thread.State: RUNNABLE
   at java.io.FileInputStream.readBytes(Native Method)
   - locked <...> (a java.lang.ProcessImpl$ProcessPipeInputStream)
   at clojure.core$slurp.invokeStatic(core.clj:7098)
```

**Immediate Fix** (completed):
- [x] Made `spawn-claude-code` private (`defn-`)
- [x] Added warning in docstring about never using blocking IO on streams
- [x] Public API (`query`/`exec`) properly uses futures and core.async channels

**Observability Requirements**:

When listing sessions via `list_sessions` MCP tool, now exposes:

| Field | Description |
|-------|-------------|
| `uptime_seconds` | Duration since session started |
| `last_activity_at` | When the last eval completed |
| `eval_count` | Total number of evals in this session |
| `current_eval` | Currently running eval info (code, started_at) |

**Implementation Tasks** (all complete):

- [x] Add `::last-activity-at` to session registry (updated on each eval)
- [x] Add `::current-eval` tracking `{::code ::started-at}` (set during eval, cleared after)
- [x] Add `::uptime` calculation in `list-agent-sessions` (via MCP server)
- [x] Add connection timeout to MCP server socket creation (5s timeout)
- [x] Track eval counts per session for debugging (`::eval-count`)
- [ ] Add optional health check (ping with 2s timeout) to `list_sessions` - deferred

**Files Modified**:
- `src/seon/orchestrator/session.clj` - Added activity tracking functions (`record-eval-start!`, `record-eval-complete!`)
- `bin/mcp-server` - Added `record-activity!` helper, enhanced `execute-list-sessions` with observability fields

**Testing** (all passing):
- [x] `activity-tracking-test` - Verify eval start/complete tracking
- [x] `activity-tracking-nonexistent-session-test` - Verify graceful handling of unknown sessions
- [x] `list-sessions-includes-observability-test` - Verify observability fields in list output
- [x] Connection timeout verified (5s in `connect-with-timeout`)

### Phase 4d: Persistent nREPL Sessions & Interrupt

**Status**: Complete (2026-01-09)

**Problem**: Each eval used an ephemeral nREPL session, meaning:
- `*1`, `*2`, `*3` didn't persist between evals
- No way to interrupt hung evals (nREPL's `interrupt` op needs a session ID)

**Solution**: Clone a persistent nREPL session on agent session creation.

**Implementation**:

1. **Session Cloning** (`bin/mcp-server`):
   - On `create_session`, send `{"op" "clone"}` to agent's nREPL
   - Store returned `new-session` UUID via `set-nrepl-session-id!`
   - Pass session ID with every eval for `*1`/`*2`/`*3` support

2. **Session Storage** (`src/seon/orchestrator/session.clj`):
   - Added `::nrepl-session-id` to session schema
   - Added `set-nrepl-session-id!` function
   - Updated `get-session-port` → `get-session-info` (returns port + session ID)

3. **Interrupt Tool** (`bin/mcp-server`):
   - New `interrupt_eval` MCP tool
   - Sends `{"op" "interrupt" "session" <nrepl-session-id>}` to agent's nREPL
   - Returns status: `["done" "interrupted"]` on success

**API Changes**:

```clojure
;; create_session now returns nrepl_session_id
{:session_id "a3a4" :nrepl_port 7889 :nrepl_session_id "405d22a3-..."}

;; list_sessions includes nrepl_session_id
[{:session_id "a3a4" :nrepl_session_id "405d22a3-..." ...}]

;; New interrupt_eval tool
(mcp__seon__interrupt_eval {:session_id "a3a4"})
;; => "Interrupt sent. Status: [\"done\" \"interrupted\"]"
```

**Verified Working**:
- [x] `*1` persists between evals
- [x] `interrupt_eval` kills hung `(Thread/sleep 30000)`
- [x] REPL responsive after interrupt
- [x] `nrepl_session_id` in create/list responses

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

### Phase 7: Full Agent Lifecycle (combines previous phases)

**Prerequisite:** Clojure Claude SDK (see `docs/prds/clojure-claude-sdk/prd.md`)

The SDK enables Seon to programmatically spawn and control Claude Code agents from the JVM using `clojure.java.process`. This is the foundation for `launch-agent!`.

- [ ] Implement Clojure Claude SDK (Phase 1-2 of SDK PRD)
  - [ ] `seon.claude.sdk/query` - spawn Claude Code, stream messages
  - [ ] `seon.claude.sdk/exec` - blocking convenience wrapper
  - [ ] Malli schemas per CONVENTIONS.md
- [ ] Integrate SDK with session API (Phase 3 of SDK PRD)
  - [ ] Auto-create nREPL session for launched agent
  - [ ] Pass session context via MCP (eval tool gets session_id)
  - [ ] Map Claude Code session to Seon session
- [ ] `launch-agent!` - orchestration function
  - [ ] Create Seon session (nREPL, ctx, db)
  - [ ] Spawn Claude Code with SDK
  - [ ] Configure MCP server with session_id
  - [ ] Assign worktree (Phase 6)
  - [ ] Provide agent instructions
- [ ] `terminate-agent!` - cleanup everything
  - [ ] Stop Claude Code process
  - [ ] Stop nREPL session
  - [ ] Archive/cleanup worktree
- [ ] Lock namespace while agent active
- [ ] Agent status dashboard in web UI

---

## Open Questions

### Resolved
1. ~~**ctx injection mechanism**~~ - Dynamic var `*ctx*` via custom middleware
2. ~~**nREPL multi-server**~~ - Fully supported, see research doc
3. ~~**Worktree sync**~~ - clj-reload can load from worktree dirs, one agent at a time
4. ~~**Persisted ctx implementation**~~ - Phase 3b complete with full test coverage
5. ~~**Session ID format**~~ - 8-char lowercase hex via `SecureRandom` (e.g., "acdb234f")

### Open
6. **Datastar SSE scoping** - How to isolate SSE per namespace?
7. **Agent POST handling** - How does agent handle form submissions?
8. **Schema for agent state** - Open schema? Per-namespace? Evolvable?

---

## Related Research

- `docs/prds/agent-isolation/research/nrepl-multi-server.md` - **nREPL multi-server research (COMPLETE)**
- `docs/prds/agent-isolation/research/worktree-reloading.md` - **Worktree code reloading research (COMPLETE)**
- `docs/prds/agent-isolation/research/sdk-architecture.md` - **Claude Agent SDK research (COMPLETE)**
- `docs/prds/agent-isolation/research/custom-subagent-investigation.md` - Markdown vs SDK agents
- `docs/prds/agent-isolation/research/complete-isolation.md` - Full JVM isolation research
- `reference-code/nrepl/` - nREPL source code (git submodule)
- `reference-code/clj-reload/` - clj-reload source code (git submodule)
- `reference-code/xtdb/docs/src/content/docs/about/dbs-in-xtdb.md` - XTDB multi-database
- `reference-code/xtdb/src/test/clojure/xtdb/sql/multi_db_test.clj` - Multi-DB tests
