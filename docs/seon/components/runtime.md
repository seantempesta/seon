---
type: component
status: production
tags: [component, flow]
---
# Runtime Registry

> Central registry tracking every running namespace instance, flow handle, agent run, and topology snapshot.

## Purpose

The runtime registry answers three questions for the entire system:

1. **What's running?** — In-memory cache of all namespace instances with status, location, and identity.
2. **What was running before a crash?** — Datahike persistence allows `mark-crashed!` on startup to detect unclean shutdowns.
3. **Where is a namespace instance located?** — Tracks whether an instance is `:in-process` or `:external` (agent JVM), with session IDs and nREPL ports.

Beyond instance tracking, this namespace owns ID generation, agent run lifecycle recording, flow handle registration, and topology snapshotting.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.runtime` | `src/seon/runtime.clj` | Registry, ID gen, flow handles, agent runs, snapshots |

This is a single-file component — everything lives in `seon.runtime`. It is one of the most depended-on namespaces in the system.

## Public API Surface

### Instance Lifecycle

| Function | Schema | Description |
|----------|--------|-------------|
| `register!` | `::register-request => ::instance-response` | Register a namespace instance (cache + Datahike upsert) |
| `unregister!` | `::unregister-request => ::unregister-response` | Set status to `:stopped` with timestamp; returns nil if namespace not found (`::unregister-response` is `[:maybe ::instance-response]`) |
| `instance` | `::instance-request => [:maybe ::instance-response]` | Look up one instance by namespace string |
| `instances` | `::instances-request => ::instances-response` | List all registered instances |
| `running-sessions` | `::running-sessions-request => ::running-sessions-response` | All external + running instances (agent sessions) |
| `mark-crashed!` | `::mark-crashed-request => ::mark-crashed-response` | On startup: mark all previously-running as `:crashed` |
| `hydrate-cache!` | `::hydrate-cache-request => ::hydrate-cache-response` | Load Datahike state into in-memory cache |
| `cleanup-stale!` | `::cleanup-stale-request => ::cleanup-stale-response` | Retract legacy entities missing required fields |

### ID Generation

| Function | Schema | Description |
|----------|--------|-------------|
| `generate-id` | `::generate-id-request => ::generate-id-response` | 6-char base62 ID via `SecureRandom`, optional prefix (e.g. `"ses-a1Bx9z"`) |

Uses an in-memory `generated-ids` atom for collision checking. ~56 billion possible IDs (62^6). Cleared on namespace reload via `after-ns-reload`.

### Flow Handle Registry (in-memory only)

| Function | Description |
|----------|-------------|
| `register-flow!` | Store flow object + channels + label; also registers as runtime instance |
| `unregister-flow!` | Remove flow handle; also unregisters runtime instance |
| `get-flow` | Look up flow handle by keyword ID |
| `list-flows` | All registered flow handles |
| `clear-flows!` | Reset all handles (testing only) |

Flow objects are opaque and not serializable, so this registry is purely in-memory. The `flow-handles` atom maps `flow-id` keyword to `{:flow flow-obj :chans chans-map :label string :started-at Instant}`.

### Agent Run Tracking

| Function | Schema | Description |
|----------|--------|-------------|
| `start-agent-run!` | `::start-agent-run-request => ::start-agent-run-response` | Create `:seon.agent.run/*` entity with `:running` status, linked to runtime instance via ref |
| `complete-agent-run!` | `::complete-agent-run-request => ::complete-agent-run-response` | Update with final status, cost, turns, duration |
| `agent-runs` | `::agent-runs-request => ::agent-runs-response` | Query all runs, optionally filtered by namespace |

### Topology Snapshots

| Function | Schema | Description |
|----------|--------|-------------|
| `snapshot-topology!` | `::snapshot-request => ::snapshot-response` | Capture paused flow state via `flow/ping`, persist as `pr-str` to Datahike |
| `latest-snapshot` | (no `:malli/schema` metadata; `::latest-snapshot-request` schema registered) | Get most recent snapshot for a flow label; takes `{::label ...}` map |

### Testing Helpers

| Function | Schema | Description |
|----------|--------|-------------|
| `reset-registry!` | `::reset-registry-request => ::reset-registry-response` | Reset in-memory registry cache, generated IDs, flow handles, and merged schema cache; for testing only |

### Schema: `runtime-merged-schema`

The function `runtime-merged-schema` lazily merges four Datahike schemas into one:

- `runtime-schema` (runtime instances + agent runs + flow snapshots)
- `seon.graph.ingest/datahike-schema` (code graph)
- `seon.ctx/datahike-schema` (context instances)
- `seon.flow.trace/datahike-schema` (flow traces)

This merged schema is what the `:seon.runtime` database connection uses. It is cached after first computation.

## Dependencies

### Uses

- [[components/database]] — `db/transact!`, `db/query` against `:seon.runtime`
- [[components/schema-system]] — `schema/register!` for all attribute and request/response schemas
- `seon.db.schema` — `register-entity-schema!`, `malli-map->datahike-schema`
- `clojure.core.async.flow` — `flow/ping` for topology snapshots

### Used By (almost everything)

- [[components/system-lifecycle]] — `seon.system` calls `register!`, `unregister!`, `mark-crashed!`, `hydrate-cache!`, `register-flow!`, `unregister-flow!` during Integrant init/halt
- [[components/flow-topology]] — Registers infrastructure flow via `register-flow!`
- [[components/code-graph]] — Scanner registers itself as runtime instance
- [[components/agent-system]] — `start-agent-run!`, `complete-agent-run!`, `agent-runs`
- [[components/namespace-lifecycle]] — Registers namespace instances
- [[components/web-layer]] — Health API reads instance data
- [[components/dev-tools]] — `after-ns-reload` hook re-populates flow handles

## How Data Flows

```
register!() --+---> registry-cache atom (in-memory, fast reads)
              +---> persist-instance!() ---> db/transact! :seon.runtime (async future in prod, sync in direct-mode)

mark-crashed!() ---> db/query :seon.runtime (find :running) ---> db/transact! (set :crashed)

hydrate-cache!() ---> cleanup-stale!() ---> db/query :seon.runtime ---> reset! registry-cache

register-flow!() --+---> flow-handles atom (in-memory only, flow objects not serializable)
                   +---> register!() (Datahike persistence of flow-as-namespace-instance)

```

### Entity Schemas (3 Datahike entity types)

| Entity | Identity Attr | Key Fields |
|--------|--------------|------------|
| `seon.runtime/*` | `:seon.runtime/namespace` | status, location, session-id, nrepl-port, started-at, stopped-at |
| `seon.agent.run/*` | `:seon.agent.run/id` | runtime (ref), provider, status, started-at, cost-usd, num-turns |
| `seon.flow.snap/*` | `:seon.flow.snap/id` | label, created-at, reason, data (pr-str of flow state) |

## Design Decisions

1. **Dual storage (cache + Datahike)**: The in-memory `registry-cache` atom provides fast reads. Datahike provides crash recovery. Writes go to both — async in production (via `future`), synchronous in `*direct-mode*` (tests and Integrant init).

2. **Base62 IDs over UUIDs**: 6-char base62 (`a1Bx9z`) is human-readable in logs, session names, and UI. Collision-checked against in-memory set. The set resets on reload (negligible risk for 6-char space).

3. **Flow handles are in-memory only**: core.async flow objects are opaque Java objects — not serializable. The `flow-handles` atom is the only way to access them. `after-ns-reload` re-populates from Integrant state.

4. **`runtime-merged-schema` uses lazy require**: Avoids circular dependencies with `seon.graph.ingest`, `seon.ctx`, and `seon.flow.trace`. These namespaces are `require`d at first call, not at load time.

5. **`persist-instance!` uses `future` in production**: Non-blocking writes. In `*direct-mode*` (tests, init), writes are synchronous to avoid race conditions.

## Refactoring Opportunities

1. **`::flow` registered as `:any`**: The flow snapshot request schema uses `[:any]` for the flow object. This is a known exception (flow objects are opaque Java types), but worth noting as a deviation from the no-`:any` rule.

2. **`latest-snapshot` lacks `:malli/schema`**: Unlike every other public function, `latest-snapshot` has no schema metadata. Should be instrumented.

3. **`datahike->cache` key mapping is manual**: The translation between `:seon.runtime/*` and `::runtime/*` keys is a hand-maintained map. A more systematic approach (e.g., a shared key registry) could reduce drift risk.

4. **Agent run entity schema duplication**: `agent-run-entity-schema` and `::agent-run-entity` define similar shapes in different formats. Could be unified.
