# Agent Isolation Architecture

**Status**: Research & Design
**Last Updated**: 2026-01-04

## Vision

Transform Seon into an **orchestrator platform** where:
- The main Seon server manages agent lifecycles and metadata
- Each agent works on an assigned **namespace** with its own isolated database and nREPL
- Namespace is THE unique identifier (no artificial naming schemes)
- One agent per namespace at a time (shared infrastructure constraint)
- Agents receive an injected `ctx` atom with everything they need
- All agent work flows through PRs for proper git integration

## Problem Statement

Current subagent architecture shares everything:
- Same XTDB database (agents can corrupt each other's test data)
- Same working directory (file conflicts)
- Same nREPL session (blocking evals, namespace pollution)
- No visibility into what each agent is doing

## Goals

1. **Namespace-scoped agents** - Each agent owns a namespace (e.g., `seon.trading`)
2. **Isolated databases** - Agent changes don't affect main or other agents
3. **Concurrent REPLs** - One agent's long eval doesn't block others
4. **Injected context** - Agents receive a `ctx` atom with db, render-fn, etc.
5. **UI testing** - Agents can test web UI against their database
6. **Orchestrator visibility** - Track all agents, their state, databases

---

## Architecture: Shared JVM, Separate nREPLs

```
┌─────────────────────────────────────────────────────────────────┐
│                 Seon Orchestrator (single JVM)                  │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │                    XTDB Node (shared)                     │  │
│  │  ┌─────────┐ ┌─────────────┐ ┌─────────────┐              │  │
│  │  │  xtdb   │ │ seon.trading│ │ seon.health │  ...         │  │
│  │  │(primary)│ │  (attached) │ │  (attached) │              │  │
│  │  └─────────┘ └─────────────┘ └─────────────┘              │  │
│  └───────────────────────────────────────────────────────────┘  │
│                                                                 │
│  ┌─────────────┐ ┌─────────────┐ ┌─────────────┐                │
│  │ nREPL :7888 │ │ nREPL :7889 │ │ nREPL :7890 │  ...           │
│  │(orchestrator)│ │seon.trading │ │ seon.health │                │
│  │             │ │ (bound ns)  │ │ (bound ns)  │                │
│  └─────────────┘ └─────────────┘ └─────────────┘                │
│                                                                 │
│  ┌───────────────────────────────────────────────────────────┐  │
│  │              HTTP Server :8080 (shared)                   │  │
│  │         Routes by X-Namespace header or path              │  │
│  └───────────────────────────────────────────────────────────┘  │
└─────────────────────────────────────────────────────────────────┘

File System:
seon/                              # Main repo (orchestrator view)
├── data/xtdb/                     # Primary orchestrator DB
├── data/namespaces/
│   ├── seon.trading/              # Trading namespace DB
│   │   ├── log/
│   │   └── storage/
│   └── seon.health/               # Health namespace DB
│
../seon-trading/                   # Git worktree for seon.trading
└── src/seon/trading/              # Agent works here
```

### Why This Architecture

| Concern | Solution |
|---------|----------|
| **Concurrent evals** | Separate nREPL per namespace (different threads) |
| **Data isolation** | XTDB ATTACH DATABASE per namespace |
| **Memory efficiency** | Shared JVM, shared XTDB node (~200-500MB per namespace) |
| **Cross-ns queries** | Possible via `namespace.table` SQL syntax |
| **Code isolation** | Git worktrees per namespace |

### Constraints

- **One agent per namespace**: Shared code means no concurrent file edits
- **Shared heap**: One OOM crashes all (rare but possible)
- **Same code version**: All namespaces run same Clojure/XTDB version

---

## Core Concept: The Agent Context (ctx)

When an agent starts, it receives an atom containing everything it needs:

```clojure
(def agent-ctx
  (atom
    {;; Identity
     :seon.agent/namespace     'seon.trading
     :seon.agent/session-id    "trading-20260104-abc123"

     ;; Database (isolated per namespace)
     :seon.agent/db            <xtdb-connection-to-seon.trading-db>

     ;; Web UI (routes to agent's namespace)
     :seon.agent/render-fn     (fn [hiccup] ...)      ; Render HTML fragment
     :seon.agent/sse-push-fn   (fn [fragment] ...)    ; Push SSE update

     ;; File system
     :seon.agent/worktree-path "/Users/sean/src/seon-trading"

     ;; Metadata
     :seon.agent/started-at    #inst "2026-01-04T..."
     :seon.agent/nrepl-port    7889}))
```

### Agent Workflow Example

```clojure
;; Agent code (evaluated in seon.trading namespace via nREPL :7889)

(let [{:seon.agent/keys [db render-fn]} @ctx]
  ;; Query agent's isolated database
  (let [signals (xt/q db "SELECT * FROM signals WHERE active = true")]
    ;; Render to web UI - just provide hiccup
    (render-fn
      [:div#signals-panel
       [:h2 "Active Signals"]
       (for [s signals]
         [:div.signal (:symbol s) " - " (:direction s)])])))
```

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

### Phase 1: XTDB Multi-Database
- [ ] Test ATTACH DATABASE with namespace-derived names
- [ ] Create/attach database when namespace first used
- [ ] Verify cross-database queries work
- [ ] Add namespace registry to orchestrator DB

### Phase 2: Multiple nREPL Servers
- [x] Research: Can nREPL start multiple servers in one JVM? **YES - CONFIRMED**
- [x] Research: How to bind namespace at startup? **Via custom middleware**
- [x] Research: Error handling and lifecycle? **Clean API, java.io.Closeable**
- [ ] Implement `start-namespace-nrepl!` function
- [ ] Implement port allocation (7889, 7890, ...)
- [ ] Create `seon-nrepl-eval` that routes to correct port
- [ ] Add Integrant component for namespace nREPL servers

### Phase 3: Agent Context Injection
- [ ] Define ctx schema (Malli specs)
- [ ] Inject ctx when agent session starts
- [ ] Provide db connection, render-fn, sse-push-fn
- [ ] Test agent can query and render

### Phase 4: Web Routing
- [ ] Route by `X-Namespace` header
- [ ] Scope SSE sessions per namespace
- [ ] Agent render-fn delivers to correct session

### Phase 5: Git Worktree Integration
- [ ] Create worktree when namespace agent starts
- [ ] Branch naming: `agent/{namespace}/{date}`
- [ ] Worktree cleanup/archival

### Phase 6: Agent Lifecycle
- [ ] `start-namespace-agent!` - preps everything
- [ ] `stop-namespace-agent!` - cleanup
- [ ] Lock namespace while agent active

---

## Open Questions

1. ~~**ctx injection mechanism**~~ - **RESOLVED**: Dynamic var `*ctx*` via custom middleware
2. ~~**nREPL multi-server**~~ - **RESOLVED**: Fully supported, see research doc
3. **Datastar SSE scoping** - How to isolate SSE per namespace?
4. **Agent POST handling** - How does agent handle form submissions?
5. **Worktree sync** - When does agent's code get loaded into shared JVM?

---

## Related Research

- `docs/prds/agent-isolation/research/nrepl-multi-server.md` - **nREPL multi-server research (COMPLETE)**
- `docs/prds/agent-isolation/research/complete-isolation.md` - Full JVM isolation research
- `reference-code/nrepl/` - nREPL source code (git submodule)
- `reference-code/xtdb/docs/src/content/docs/about/dbs-in-xtdb.md` - XTDB multi-database
- `reference-code/xtdb/src/test/clojure/xtdb/sql/multi_db_test.clj` - Multi-DB tests
