# Unified Agent Runtime: Session-ID as Universal Key

## Context

The refinement PRD (`docs/prds/refinement/prd.md`) already plans 4 tracks. This plan is a **refinement of Track 2** (Unify Context + Auto-Proxy) plus the pool/session unification that the PRD assumes but doesn't detail.

Currently there are 3 parallel runtime systems with no connection between them. We want ONE model:

**Session** = session-id + runtime + flow channels + ctx

- **Runtime** = a process with an nREPL for loading code (pool JVM today, cljs process tomorrow)
- **Flow channels** = TCP bridge for cross-ns data exchange (agent code calls functions normally, proxy rewrites route through flow)
- **nREPL** = how code gets loaded into the runtime (every pool JVM already has one)
- **Session-id** = 4-char hex, THE key for everything: logs, DB, runtime lookup, messages

## The Model

```
start-session!(namespace, opts) →
  1. Generate session-id (4-char hex)
  2. Claim runtime from pool (anonymous JVM gets session-id)
  3. Setup: create namespace, inject *ctx*, configure Datalevin
  4. Wire flow: start TCP bridge, register in topology
  5. Auto-proxy: analyze namespace deps, create proxies for cross-ns calls
  6. Register: session-id → {port, namespace, flow-channels, status} in master DB
  7. Return {session-id, nrepl-port}
```

MCP eval → look up session-id → Super REPL → nREPL eval on pool JVM.
Cross-ns call → proxy fn → flow channel → TCP → target JVM → execute → reply back.

Drivers (Claude, human, automation) just need the session-id to interact.

## Phase 1: Session Claims Pool JVM (this PR)

### 1.1 Pool: session-id tracking

**File: `src/seon/flow/pool.clj`**

- Add `::session-id` field to JVM maps in `::all-jvms`
- Add `claim!`: assigns session-id to an idle JVM, calls `setup-namespace!` + ctx injection, returns JVM handle
- Add `get-jvm-by-session`: session-id → JVM map (port, pid, etc.)
- `release!`: clears session-id, resets namespace, returns to idle queue
- Keep `::all-jvms` keyed by port (physical resource), add reverse index `::session->port`

### 1.2 Pool: `*ctx*` injection at claim time

**File: `src/seon/flow/pool.clj`**

Extend `setup-namespace!` or add to `claim!` flow — eval on pool JVM to intern `*ctx*`:
```clojure
(nrepl-eval! port
  (pr-str `(do (intern '~ns-sym '~'*ctx*
                 (atom {:seon.agent/namespace '~ns-sym
                        :seon.agent/session-id ~session-id
                        :seon.agent/started-at (java.util.Date.)}))
               (.setDynamic (resolve (symbol (str '~ns-sym) "*ctx*")) true)
               :ok)))
```

This replaces the middleware approach. The existing `make-persisted-ctx` from `seon.ctx` should be used to build the initial ctx value — reuse, don't duplicate.

### 1.3 Session: delegate to pool

**File: `src/seon/orchestrator/session.clj`**

- `start-agent-session!`: replace `nrepl/start-namespace-nrepl!` with `pool/claim!`
- `stop-agent-session!`: replace `nrepl/stop-namespace-nrepl!` with `pool/release!`
- Remove dependency on `seon.orchestrator.nrepl`
- Session still creates persisted ctx via `seon.ctx`, but hands it to pool for injection

### 1.4 System wiring

**Files: `resources/system.edn`, `src/seon/system.clj`**

- `:seon/orchestrator-sessions` depends on `:seon/agent-pool`
- Pass pool ref to session init

### 1.5 Delete `orchestrator/nrepl.clj`

**File: `src/seon/orchestrator/nrepl.clj`** — DELETE

Everything it does is replaced:
- Port allocation → pool handles
- `*ctx*` middleware → eval-based injection on pool JVM
- nREPL server lifecycle → pool JVM already has nREPL
- Port/server registries → pool's `::all-jvms` + `::session->port`

### 1.6 Verify downstream (no-change expected)

**`bin/mcp-server`**: calls `get-session-port` → nREPL eval. Pool JVMs speak same nREPL protocol. Should work.

**`src/seon/ai/claude.clj`**: delegates to `session/start-agent-session!`. Gets back `{session-id, nrepl-port}`. Doesn't care about runtime type.

**`src/seon/dev/hook.clj`**: uses session-id to find port. Same interface.

## Reusable Functions

| Function | File | Purpose |
|----------|------|---------|
| `pool/acquire!` + `activate-jvm!` | `flow/pool.clj` | Basis for `claim!` |
| `pool/release!` | `flow/pool.clj` | Reuse for session stop |
| `pool/nrepl-eval!` | `flow/pool.clj` | For ctx injection |
| `pool/setup-namespace!` | `flow/pool.clj` | Namespace creation on JVM |
| `session/generate-session-id` | `orchestrator/session.clj` | Keep as-is |
| `ctx/make-persisted-ctx` | `seon/ctx.clj` | Build ctx value to inject |
| `harness/start-namespace-jvm!` | `flow/harness.clj` | Future: wire TCP bridge at claim time |

## State After Phase 1

**Atoms: 5 → 3**
- ~~`orchestrator.nrepl/servers`~~ — deleted
- ~~`orchestrator.nrepl/port-registry`~~ — deleted
- `flow.pool/pool-state` — enhanced with session-id tracking
- `orchestrator.session/session-registry` — simplified
- `ai.agent/agent-registry` — unchanged

## Future Phases (align with refinement PRD tracks)

**Phase 2** (Track 2 auto-proxy): At `claim!` time, analyze namespace requires, auto-generate proxy namespaces for cross-ns deps so agent code calls functions normally.

**Phase 3** (Track 3 flow logging): Add trace-id to flow messages, persist to Datalevin, surface in Observatory.

**Phase 4** (DB naming): `agent-{session-id}` instead of `agent-{port}`. Datalevin URI configured at claim time, not spawn time.

**Phase 5** (runtime abstraction): The `claim!` interface is runtime-agnostic. A cljs pool would implement the same protocol: `claim!` → `{session-id, nrepl-port}`. The flow channels, ctx injection, and proxy wiring all work the same — just data over channels.

## Verification

1. `clojure -M:test -m kaocha.runner` — full suite, 0 failures
2. `user/launch-agent!!` — agent gets pool JVM, MCP eval works
3. `@*ctx*` in agent REPL returns session context
4. Pool JVM returns to idle on agent completion
5. Observatory shows agents correctly
6. No references to `orchestrator.nrepl` remain
