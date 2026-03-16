---
type: component
status: production
tags: [component, flow]
---
# Context System

> Per-instance state management via atom + watches, with debounced persistence, SSE push, and Malli validation.

## Purpose

The context system gives each namespace instance a managed stateful atom. When namespace code runs (e.g. a trading dashboard), it gets a ctx atom that automatically persists changes to Datalevin, pushes SSE updates to connected browsers, and validates state transitions via Malli. It replaced four prior state systems with a single unified API.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.ctx` | `src/seon/ctx.clj` | Registry, atom creation, watches, persistence, SSE push, validation |
| `seon.ctx.history` | `src/seon/ctx/history.clj` | Pure delta/diff utilities for undo/redo (no IO, no atoms) |

## Public API Surface

### seon.ctx

| Function | Purpose |
|----------|---------|
| `create!` | Create a managed ctx atom with persistence/SSE/validation watches. Returns the atom. |
| `get-atom` | Look up atom by instance-id |
| `get-value` | Deref the atom for an instance |
| `get-entry` | Raw registry entry (exposes internals -- `::atom`, `::render-fn`, `::clients`) |
| `update!` | `swap!` wrapper with function + args |
| `destroy!` | Remove instance, clean up watches/scheduler/clients |
| `register-client!` / `unregister-client!` | Track SSE client channels per instance |
| `clients` / `client-count` | Query connected clients |
| `force-push!` | Manually trigger render + SSE push |
| `set-render-fn!` | Update render function post-creation |
| `instances-for-namespace` / `clients-for-namespace` / `client-count-for-namespace` | Namespace-level queries |
| `persist!` / `load!` | Manual persistence to/from Datalevin |
| `list-instances` | List all active instances |

### seon.ctx.history

| Function | Purpose |
|----------|---------|
| `map-diff` | Compute delta between two maps (added/retracted keys) |
| `apply-delta` | Apply a delta forward to produce next state |
| `reverse-delta` | Swap added/retracted for undo |
| `empty-delta?` | Check if delta represents no change |

## Dependencies

**Uses:**

- [[components/schema-system]] (`seon.schema`) -- schema registration, validation
- [[components/database]] (`seon.db`) -- persistence via `transact!` and `query`
- `seon.db.schema` -- entity schema registration and Malli-to-Datalevin bridge
- `seon.runtime` -- ID generation
- `org.httpkit.server` -- SSE channel management (soft dep via `requiring-resolve`)
- `seon.web.sse` -- broadcast refresh (soft dep)
- `seon.web.reactive.transform` -- hiccup transformation for Datastar SSE format
- `dev.onionpancakes.chassis.core` -- hiccup to HTML string

**Used by:**

- `seon.ns.lifecycle` -- creates instances, resolves by namespace
- `seon.ns.routes` -- reads `::render-fn` and `::atom` from registry entries for page rendering
- `seon.orchestrator.session` -- creates ctx with reserved-keys for agent isolation
- `seon.web.browser` -- queries `clients-for-namespace` for push targeting

## How Data Flows

1. **Creation**: `seon.ns.lifecycle` calls `ctx/create!` with config (db-name, namespace, initial value, options). Returns an atom.
2. **State changes**: Namespace code calls `ctx/update!` (or swaps the atom directly). Three watches fire:
   - **Persistence watch** (`::persist`): Cancels any pending persist, schedules a new one via `ScheduledExecutorService` with configurable debounce (default 100ms). The scheduled task calls `do-persist!` which filters non-serializable values, serializes to EDN, and transacts to Datalevin.
   - **SSE broadcast watch** (`::sse-push`): Calls `seon.web.sse/refresh-all!` to notify all connected browsers.
   - **Client-targeted watch** (`::client-push`): Calls the registered `render-fn` with current state, transforms hiccup via `seon.web.reactive.transform`, converts to HTML, formats as Datastar SSE `patch-elements` event, and pushes to each tracked client channel. Dead channels are cleaned up on failure.
3. **Loading**: On instance creation, `load!` can restore persisted state from Datalevin via Datalog query.
4. **Destruction**: `destroy!` closes client channels, cancels pending persists, shuts down the scheduler, removes watches, and removes from registry.

**Planned: Flow-first model** ([[prds/unified-namespace-flow/design]]). All three watches above will be replaced by flow outputs. The atom becomes a read cache; flow step state is source of truth. Persistence debouncing uses `sliding-buffer 1` on the channel to the writer step — writer I/O provides natural backpressure. SSE push uses `async/mult` on a flow out-port. Only one watch remains: `::flow-sync` injects into flow when external code changes the atom. See [[prds/unified-namespace-flow/research/ctx-flow-sync]].

### Validation Modes

Two validation approaches, both using atom `:validator`:

- **Per-key validation** (`::validate? true`): Every key must be namespaced, have a registered Malli schema, and pass validation. Reserved keys (`seon.agent/*`, `seon.ns/*`) cannot be added, modified, or removed.
- **Whole-state validation** (`::ctx-schema`): A single Malli schema validates the entire state map on every `swap!`.

When both are specified, both run: the validator calls `validate-state` (per-key) AND the schema validator. They are not mutually exclusive despite the intent — `::ctx-schema` takes precedence in ordering but doesn't suppress per-key checks.

### Registry

An in-memory `defonce` atom mapping `instance-id` (6-char hex string) to entry maps containing: `::atom`, `::db-name`, `::namespace`, `::persist?`, `::sse-push?`, `::track-clients?`, `::validate?`, `::reserved-keys`, `::clients` (atom of channel set), `::render-fn`, `::created-at`, `::scheduler` (ScheduledExecutorService), `::scheduled-task` (atom of ScheduledFuture).

### Serialization Filtering

Before persisting, `filter-serializable` round-trips each value through `pr-str` then `edn/read-string`. Non-serializable values (atoms, functions, connections) are silently stripped. This prevents persistence failures but means the persisted state is a subset of the live state.

## Design Decisions

- **Atom + watches over explicit event bus**: Simple Clojure idiom. Each concern (persist, SSE, client push) is a separate watch -- easy to add/remove.
- **Debounced persistence**: Rapid state changes (e.g. real-time data) collapse into a single DB write after the debounce window. Uses `ScheduledExecutorService` per instance for precise timing.
- **`bound-fn` in scheduler**: The persistence watch uses `bound-fn` to convey dynamic bindings (notably `db/*direct-mode*` in tests) to the scheduler thread.
- **Reserved keys for agent isolation**: Agent sessions inject read-only keys (`seon.agent/namespace`, `seon.agent/db`, `seon.ns/session-id`) that namespace code cannot modify. Enforced by the validator.
- **`requiring-resolve` for soft deps**: SSE, http-kit, and Chassis are resolved at call time, not at load time. Ctx can be loaded without the web layer.
- **EDN for persistence**: State serialized as a single EDN string in `:seon.ctx/data`. Simple but loses type information for some values and limits queryability.

## Refactoring Opportunities

- **`get-entry` exposes internals**: Returns the raw registry map. Consumers (`seon.ns.routes`) are coupled to `::atom`, `::render-fn`, `::clients`. A curated return map would decouple them (noted as P3 in source).
- **`::initial-value` and `::render-fn` typed as `:any`**: These resist schema validation. `::render-fn` could be `[:=> [:cat :map] :any]` at minimum.
- **No generative tests**: Most functions have `:malli/schema` metadata, making them candidates for property-based testing.
- **EDN string persistence is opaque**: State stored as a single string means you can't query individual ctx fields in Datalevin. A structured entity per ctx key would enable richer queries.
- **One ScheduledExecutorService per instance**: Could share a single pool across all instances to reduce thread count.
