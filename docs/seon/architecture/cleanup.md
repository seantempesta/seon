---
type: architecture
status: actionable
---
# Cleanup: Problems Inventory

> Each item describes what's wrong and where. No suggested fixes — decide solutions separately.

## Dead Code

Files and functions with no callers in the codebase.

- **`web/namespace.clj` + `ui/viewer.clj`** — No callers, no tests. Fully replaced by `ns/routes`. Two dead files still in `src/`.
  - Severity: cleanup

- **`agent/helpers.clj`** — Every function throws "not yet migrated." Zero callers anywhere.
  - Severity: cleanup

- **`hook_test_scratch.clj` + `dev/hook_test_ns.clj`** — Scratch files that landed in `src/` instead of `tmp/`.
  - Severity: cleanup

- **`render/example.clj`** — No callers, no tests. Likely a prototype that was never wired in.
  - Severity: cleanup

- **`repl/graduate.clj`** — No callers. The graduation concept was never connected to anything.
  - Severity: cleanup

- **`web/sse.clj send!`** — Explicitly marked deprecated in its docstring. Still has callers using it.
  - Severity: cleanup

## Duplication

Same logic implemented in multiple places.

- **`get-conn` for `:seon.runtime`** is implemented identically in 3 places: `render.clj:56`, `ns/routes.clj:122`, `db.clj:167`. Each has its own private copy doing the same thing.
  - Severity: friction

- **`connection-error?`** is defined in both `db.clj:328` and `conn.clj:194`. The `conn.clj` version is public; the `db.clj` version is a private duplicate.
  - Severity: cleanup

- **`parse-form-body`** is implemented identically in `web/handlers.clj:47` and `ns/routes.clj:649`.
  - Severity: cleanup

- **`::db-name` schema** is registered 14 times across the codebase. Every file that touches the DB registers its own copy of `[:enum :seon :seon.runtime :seon.ai :seon.flow]`.
  - Severity: friction — schema changes require updating 14 files

- **`::namespace` schema** is registered 20+ times across the codebase. Same pattern as `::db-name`.
  - Severity: friction — schema changes require updating 20+ files

- **clj-kondo analysis** is wrapped independently in 3 namespaces: `graph/extract`, `graph/analyzer`, and `dev/analysis`. Each has its own integration with the same tool.
  - Severity: friction

## Overlap

Multiple systems solving the same problem.

### Three rendering systems
1. **`seon.render`** — Datalevin-backed key-shape discovery, specificity algorithm. Production system.
2. **`seon.ns.view`** — Multimethod-based rendering for namespace views. Separate discovery mechanism.
3. **`seon.ui.viewer`** — Dead (no callers, listed above).

Two live systems exist for the same job, using different dispatch mechanisms (specificity vs multimethods).
- Severity: architectural

### Three AI context builders
1. `render/code.clj` — code-focused context
2. `repl/context.clj` — REPL context wrapper
3. `graph/context.clj` — graph-query-based context

Three namespaces each build AI context text differently with no shared interface.
- Severity: friction

### Three SSE push mechanisms
1. **ctx watch-based push** (`ctx.clj:285-355`) — Bespoke SSE formatting, own client tracking, fires on atom watch. Tightly coupled to ctx atoms.
2. **render-handler poll** (`web/sse.clj`) — Content-hash deduplication. Used by the web layer.
3. **flow-based SSE** (`web/sse/flow.clj`) — Aggregates code change events. Flow-native but only handles one event type.

Three systems push data to browsers through different paths with different semantics.
- Severity: architectural

### Three status badge implementations
1. `web/html/status-badge` — generic HTML helper
2. `web/components/status-dot` — design-system component (dot + text)
3. `web/agents/agent-status-badge` — agent-specific variant

The same visual element is implemented three times with slightly different APIs.
- Severity: cleanup

## Naming Conflicts

Same name used for different concepts.

- **"context"** means 4 different things: `ctx.clj` (namespace state atoms), `graph/context.clj` (AI text generation), `dev/context.clj` (edit tracking), `repl/context.clj` (REPL wrapper). A developer reading "context" cannot know which system is meant without checking the namespace.
  - Severity: friction

- **"health"** means 2 different things: `seon.health` (system health checks — is Datalevin up?) vs `seon.health.*` (health domain — workouts, body metrics). The system namespace and the domain namespace collide.
  - Severity: friction

- **"status"** means 3 different things: system health (healthy/degraded), runtime instance state (running/stopped/crashed), flow process state (running/stopped/error). All use the word "status" with no qualifier.
  - Severity: friction

## Coupling Issues

Surprising dependencies that cross architectural boundaries.

- **`render.clj` reaches into `db.datalevin.conn` directly** — bypasses the `seon.db` API that everything else uses. Render has a direct dependency on the database implementation layer.
  - Severity: architectural

- **`graph.ingest` depends on `seon.render`** — The ingest pipeline (which scans code) has a dependency on the rendering system. Ingest should not need to know about rendering.
  - Severity: friction

- **`ns/routes.clj` uses `web/reactive.*` directly** — Namespace views are tightly coupled to the reactive system implementation rather than going through the standard SSE push path.
  - Severity: friction

- **3 circular dependencies broken by `requiring-resolve`** — Three pairs of namespaces depend on each other and use `requiring-resolve` to avoid load-order failures. This is fragile under code reload and indicates architectural boundaries that are drawn in the wrong place.
  - Severity: architectural