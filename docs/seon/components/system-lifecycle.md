---
type: component
status: production
tags: [component, flow]
---
# System Lifecycle

> Integrant-based two-phase startup, graceful shutdown, health monitoring, and Aero configuration loading.

## Purpose

Manages the full lifecycle of the Seon system: configuration loading, two-phase component initialization, health checking, readiness gating, graceful shutdown, and the `(go)` / `(halt)` / `(reset)` REPL workflow. The design ensures that even if database or flow components fail, the developer always has an nREPL and HTTP server to debug with.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.core` | `src/seon/core.clj` | Entry point, two-phase init, shutdown hook, `-main` |
| `seon.system` | `src/seon/system.clj` | All `ig/init-key` and `ig/halt-key!` methods |
| `seon.system.config` | `src/seon/system/config.clj` | Malli schemas for component configs, `validate`, `describe` |
| `seon.config` | `src/seon/config.clj` | Aero config loading, `system-config` |
| `seon.health` | `src/seon/health.clj` | Health checks, readiness gate, post-start observation |

## Public API Surface

### `seon.config`

| Function | Description |
|----------|-------------|
| `system-config` | Load `system.edn` via Aero with profile (`:dev`, `:test`, `:prod`). Registers `#ig/ref` and `#ig/refset` as Aero readers. |

### `seon.core`

| Function | Description |
|----------|-------------|
| `start-app` | Two-phase Integrant init. Phase 1: core services. Phase 2: `ig/resume` full config. Stores result in `integrant.repl.state/system`. |
| `stop-app` | Call profile `:stop` hook, then `ig-repl/halt`. |
| `-main` | CLI entry: configure logging, preflight port checks, `start-app`, install shutdown hook, block. |

### `seon.health`

| Function | Schema | Description |
|----------|--------|-------------|
| `check` | `::check-request => ::check-response` | Full health check: all services, resources, startup phase |
| `readiness-gate` | (returns map) | Post-Phase-2 operational checks: Datalevin query, flow responsive, runtime persisted |
| `set-startup-phase!` | (atom reset) | Update startup phase (`:phase-1`, `:phase-2`, `:ready`, `:degraded`) |
| `start-post-start-observation!` | (side effect) | Schedule re-checks at 30s and 60s after startup |
| `log-startup-summary!` | (side effect) | Log clean service table with ports and modes |
| `cleanup-orphaned-resources!` | `::cleanup-orphaned-resources-request => response` | Kill stale pool JVM processes |

### `seon.system.config`

| Function | Description |
|----------|-------------|
| `validate` | Check component config against Malli schema. Returns nil or humanized errors. |
| `describe` | Return schema info for a component key. For agent introspection. |

## Components (Integrant init order)

The full system has 15 components. Integrant resolves init order from `#ig/ref` dependencies in `system.edn`.

| Component Key | Depends On | Suspend/Resume | Description |
|---------------|-----------|----------------|-------------|
| `:seon.schema/registry` | — | Survives reset | Load all Malli schemas |
| `:seon.db.schema/consistency-check` | registry | Survives reset | Validate persisted schemas (no `:any`, no `[:maybe X]`) |
| `:seon.dev/nrepl` | — | Survives reset | nREPL server on :7888 |
| `:seon.dev/instrumentation` | — | Survives reset | Malli function instrumentation |
| `:seon.web.server/http-server` | — | — | HTTP on :8080 |
| `:seon.web/tailwind` | — | — | Tailwind CSS watcher (dev only) |
| `:seon.ai.claude/sdk` | — | — | Claude CLI path config |
| `:seon.db.datalevin/server` | — | — | Start/adopt Datalevin on :8898 |
| `:seon.db.datalevin/connections` | server | — | Connection manager (caching, lazy DB creation) |
| `:seon.flow/infrastructure` | connections | Always re-init | Infrastructure flow (writer + reply-router) |
| `:seon/runtime-db` | connections, infrastructure | Survives reset | Runtime DB conn, `mark-crashed!`, `hydrate-cache!` |
| `:seon.graph/scanner` | runtime-db | Always re-init | Background code scan (~3s, with circuit breaker) |
| `:seon.flow/pool` | server | — | Pre-warmed agent JVM pool |
| `:seon.orchestrator/sessions` | connections, pool | Survives reset | Session storage init |
| `:seon.web/caddy` | http-server | — | Caddy reverse proxy on :3030 (dev only) |

All components derive from `:seon/component` via `resources/integrant/hierarchy.edn`, enabling a single `ig/assert-key` multimethod that validates configs against Malli schemas in `seon.system.config/schemas`.

## Two-Phase Startup

```
-main
  |
  +-- logging/configure!
  +-- preflight-port-checks! (7888, 8080 — NOT 8898, Datalevin auto-adopted)
  |     +-- If Seon already running: print status, exit 0
  |
  +-- start-app
        |
        +-- Phase 1 (set-startup-phase! :phase-1)
        |   ig/init with only phase-1-keys:
        |     :seon.schema/registry
        |     :seon.dev/nrepl
        |     :seon.web.server/http-server
        |     :seon.web/tailwind
        |     :seon.ai.claude/sdk
        |   -> Developer now has REPL + HTTP
        |
        +-- Phase 2 (set-startup-phase! :phase-2)
        |   ig/resume full-config from phase-1-system
        |   -> Reuses Phase 1 components, starts DB, flow, scanner, pool, etc.
        |   -> If Phase 2 throws: keep Phase 1 running, set :degraded
        |
        +-- Readiness Gate
        |   health/readiness-gate checks:
        |     :datalevin-query — Can execute a Datalevin query?
        |     :flow-responsive — Can route through infrastructure flow?
        |     :runtime-persisted — Are runtime instances registered?
        |   -> All pass: set-startup-phase! :ready
        |   -> Any fail: set-startup-phase! :degraded
        |
        +-- Post-Start Observation
        |   Scheduled re-checks at 30s and 60s
        |   Logs WARN if degradation detected
        |
        +-- Shutdown Hook
              db/pause-writer!
              lifecycle/backup-all-instances!
              stop-app
              shutdown-agents
```

## How `(go)`, `(halt)`, `(reset)` Work

These are `integrant.repl` functions that operate on `integrant.repl.state/system`:

- **`(go)`** — Calls `prep` (our `cfg-fn` via `ig-repl/set-prep!`) then `ig/init`. Equivalent to `start-app` but without two-phase logic.
- **`(halt)`** — Calls `ig/halt-key!` on all running components in reverse dependency order.
- **`(reset)`** — Calls `ig/suspend-key!` on each component, then `ig/resume` with fresh config. Components that implement `suspend-key!` survive (nREPL, runtime-db, schema registry, instrumentation). Components that don't are halted and re-initialized.

The key insight: `seon.core/start-app` stores the system in `integrant.repl.state/system` (not a separate atom), so `(reset)` works regardless of whether the system was started via `-main` or REPL.

## Suspend/Resume Semantics

Components that **survive `(reset)`** (return old state from `suspend-key!`):

- `:seon.dev/nrepl` — Critical: losing REPL connection during reset breaks workflow
- `:seon.schema/registry` — Pure value, no side effects to clean up
- `:seon.db.schema/consistency-check` — Pure check result
- `:seon/runtime-db` — Connection manager handles staleness internally
- `:seon.orchestrator/sessions` — Re-wires pool atom on resume
- `:seon.dev/instrumentation` — Persists across reloads

Components that **always re-init on resume**:

- `:seon.flow/infrastructure` — Flow objects are immutable; new code means new processes
- `:seon.graph/scanner` — Cheap (~3s background), ensures fresh connections

## Health Checks

The health system has three tiers:

### 1. Port Connectivity (always checked)

| Service | Port | Notes |
|---------|------|-------|
| Datalevin | 8898 | Reports mode (adopted/started), PID, connection manager health |
| nREPL | 7888 | Reads `.nrepl-port` file |
| HTTP | 8080 | Always `:started` mode |
| Caddy | 3030 | Reports mode (adopted/started) |
| Tailwind | — | Process alive check |

### 2. Operational Checks (only after Phase 2)

| Check | What It Does |
|-------|-------------|
| `:datalevin-query` | Execute a real Datalevin query |
| `:flow-responsive` | Route a probe query through infrastructure flow |
| `:runtime-persisted` | Verify runtime instances exist in memory |

### 3. Resource Counts

Agents running, pool JVMs, active sessions.

### Status Determination

- **`:healthy`** — All checks pass, phase is `:ready`
- **`:degraded`** — Non-critical failures, or startup phase is `:degraded`
- **`:unhealthy`** — Critical checks fail (datalevin, datalevin-query, flow-responsive)

## Configuration Loading (Aero)

`seon.config/system-config` loads `resources/system.edn` via Aero:

- **Profiles**: `:dev`, `:test`, `:prod` — controls which services are enabled
- **Aero readers**: `#or` (env var with fallback), `#env` (env var), `#profile` (profile-conditional)
- **Integrant readers**: `#ig/ref` and `#ig/refset` registered as custom Aero readers
- **Hierarchy**: `resources/integrant/hierarchy.edn` derives all component keys from `:seon/component`

Component config validation uses Malli schemas in `seon.system.config/schemas`, triggered by the `ig/assert-key :seon/component` multimethod before any `init-key`.

## Dependencies

### Uses

- [[components/runtime]] — `mark-crashed!`, `hydrate-cache!`, `register!`, `unregister!`, `register-flow!`, `unregister-flow!`, `runtime-merged-schema`
- [[components/database]] — `db/transact!`, `db/query`, `db/*direct-mode*`, `db/*conn-manager*`, `db/pause-writer!`
- [[components/schema-system]] — `schema/registered-schemas`, schema validation at boot
- [[components/flow-topology]] — `topology/build-infrastructure!` for infrastructure flow init
- [[components/namespace-lifecycle]] — `lifecycle/backup-all-instances!` during shutdown
- [[components/code-graph]] — Scanner component extracts + ingests code graph
- [[components/agent-system]] — Agent and pool health checks

### Used By

- Everything — this is the boot sequence. All components are initialized here.
- [[components/dev-tools]] — `(user/reset)` delegates to `integrant.repl/reset` which uses the system stored here.

## Design Decisions

1. **Two-phase startup**: Phase 1 gives the developer a working REPL immediately. If Datalevin is down or the flow fails, you can still connect and debug. This is critical for REPL-driven development.

2. **`integrant.repl.state/system` as single source**: No separate system atom. Both `-main` and REPL `(go)` store state in the same var. This is the "real Kit pattern" — `(reset)` works regardless of how the system was started.

3. **Port conflict detection with actionable errors**: Pre-flight checks provide PID and kill command in the error message. Datalevin port (8898) is NOT checked because the server component auto-detects and adopts existing instances.

4. **Readiness gate separates "started" from "operational"**: Components can be initialized but not actually working (e.g., Datalevin accepting TCP but failing queries). The readiness gate catches this.

5. **Post-start observation**: Background re-checks at 30s and 60s catch delayed degradation (e.g., connection pool exhaustion, flow stalls).

6. **`binding` conveyance during init**: Several `ig/init-key` methods bind `db/*direct-mode*` and `db/*conn-manager*` because Integrant system is nil during init, so normal flow-based DB access doesn't work. Clojure's `future` conveys these bindings automatically.

## Refactoring Opportunities

1. **`check` function has `::check-result` with `:any` in details**: The health check result schema uses `[:map-of :keyword :any]` for details. Could be tightened per-check.

2. **`check-resources` reaches into session internals**: Directly derefs `session-registry` and `agent-pool` atoms via `ns-resolve`. Fragile if those namespaces change.

3. **Phase 1 keys are hardcoded**: The `phase-1-keys` vector in `seon.core` duplicates knowledge about which components are DB-independent. Could be derived from the dependency graph.

4. **Missing env.clj**: The `seon.env` namespace with `:init`, `:start`, `:stop` hooks is referenced but the file wasn't found at the expected location. Profile hooks may be vestigial.
