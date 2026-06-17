---
type: component
status: production
tags: [component, flow]
---
# Context System

> Per-instance state management via atom + watches, with debounced persistence, SSE push, and Malli validation.

`[JVM track — paused]` The atom/watch system described below belongs to the paused JVM main-app track. The active CLJS pod has its own `seon.ctx` (the agent prompt composer) — see the "CLJS pod sibling" section near the end.

## Purpose

The context system gives each namespace instance a managed stateful atom. When namespace code runs (e.g. a trading dashboard), it gets a ctx atom that automatically persists changes to Datahike, pushes SSE updates to connected browsers, and validates state transitions via Malli. It replaced four prior state systems with a single unified API.

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
| `persist!` / `load!` | Manual persistence to/from Datahike |
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
- `seon.db.schema` -- entity schema registration and Malli-to-Datahike bridge
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
   - **Persistence watch** (`::persist`): Cancels any pending persist, schedules a new one via `ScheduledExecutorService` with configurable debounce (default 100ms). The scheduled task calls `do-persist!` which filters non-serializable values, serializes to EDN, and transacts to Datahike.
   - **SSE broadcast watch** (`::sse-push`): Calls `seon.web.sse/refresh-all!` to notify all connected browsers.
   - **Client-targeted watch** (`::client-push`): Calls the registered `render-fn` with current state, transforms hiccup via `seon.web.reactive.transform`, converts to HTML, formats as Datastar SSE `patch-elements` event, and pushes to each tracked client channel. Dead channels are cleaned up on failure.
3. **Loading**: On instance creation, `load!` can restore persisted state from Datahike via Datalog query.
4. **Destruction**: `destroy!` closes client channels, cancels pending persists, shuts down the scheduler, removes watches, and removes from registry.

See [[components/planned-ctx-flow-first]] for the planned flow-first model that would replace these watches with flow outputs.

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

## CLJS pod sibling — `seon.ctx` (`src/seon/ctx.cljs`)

The CLJS pod lane has its own `seon.ctx`: the agent prompt composer (`substrate-default-ctx` section layout + `assemble-context`), unrelated to the JVM atom/watch system above. Additions of 2026-06-11:

- **`:findings` section (priority 48)** — `seon.agent.findings/findings-section` (`src/seon/agent/findings.cljs`) renders every user-domain kind's stored rows IN FULL (kb-row content, not just attr names) into the prompt; cross-agent by design, derived per render, vanishes when the store holds none. The inspector's findings pane reads the same `user-domain-kinds` derivation — see [[components/web-inspector]].
- **Todo standing teaching + `:open-todos` render** — the `<system>` standing teachings now instruct: mint one todo per step via `seon.agent.todo/add!` BEFORE starting and `complete!` each id as steps land. The `:open-todos` section (priority 45, `seon.agent.todo/open-todos-section`) renders each open item as `<id> [<age>] <title>` plus the `complete!` call hint, so ids are always actionable from the prompt; empty = the section vanishes (done-signal).
- **`:seon.ai/config` row + provider selection** — `src/seon/ai.cljs`: one singleton row (identity `:seon.ai/id` = `"config"`) carries provider/model/temperature/max-tokens/thinking/timeout-ms as DATA; env-owned across boots (`SEON_AI_*`, same contract as [[components/web-brand]]), read per call via `seon.ai/current` (no cached atom). Provider selection (`seon.ai/provider`): env > config row > `:deepseek`. The two adapters that consume this row are `seon.ai.openai-compat` (`src/seon/ai/openai_compat.cljs` — serves the `:deepseek` + `:openai-compat` providers, renamed from `seon.ai.deepseek` 2026-06-16) and `seon.ai.anthropic` (the `:anthropic` provider). As of 2026-06-16 both use the official Node SDKs (`openai`, `@anthropic-ai/sdk`) instead of hand-rolled `js/fetch`; new config/opts fields `:seon.ai/tools`/`:seon.ai/tool-choice` (function-calling passthrough, default off) and `:seon.ai/extra-body` (generic request-field merge) ride the same per-call read. Provider metadata is preserved on every response (`:seon.ai/usage`, `:seon.ai/provider-fields`) and persisted per-turn (`:seon.agent.turn/llm-usage` + `:seon.agent.turn/llm-meta`).

## Refactoring Opportunities

- **`get-entry` exposes internals**: Returns the raw registry map. Consumers (`seon.ns.routes`) are coupled to `::atom`, `::render-fn`, `::clients`. A curated return map would decouple them (noted as P3 in source).
- **`::initial-value` and `::render-fn` typed as `:any`**: These resist schema validation. `::render-fn` could be `[:=> [:cat :map] :any]` at minimum.
- **No generative tests**: Most functions have `:malli/schema` metadata, making them candidates for property-based testing.
- **EDN string persistence is opaque**: State stored as a single string means you can't query individual ctx fields in Datahike. A structured entity per ctx key would enable richer queries.
- **One ScheduledExecutorService per instance**: Could share a single pool across all instances to reduce thread count.
