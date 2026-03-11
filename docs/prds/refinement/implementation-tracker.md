# Implementation Tracker: Unified Runtime Architecture

**PRD:** `docs/prds/refinement/prd-flow-state.md`
**Branch:** `feature/refinement`
**Last updated:** 2026-02-22

---

## Phase Checklist

| Phase | Description | Status | Agent | Notes |
|-------|-------------|--------|-------|-------|
| 1 | Runtime registry + schema + unified ID | **Done** | seon-agent | `seon.runtime` created, `:seon/graph-db` component, 6-char hex IDs |
| 2 | Session integration | **Done** | seon-agent | Sessions dual-write to runtime registry on start/stop |
| 3 | Agent run entities | **Done** | seon-agent | `:seon.agent.run/*` entities with start/complete/query |
| 4 | DB consolidation | **Done** | task-agent | Renamed seon-graph→seon.runtime, killed orchestrator dual-write. AI session migration deferred. |
| 5 | Ctx unification | **Done** | task-agent | Deleted harness duplicate ctx persistence |
| 6 | Flow snapshots | **Done** | task-agent | pause→snapshot→stop in topology, snapshot schema in Datalevin |
| 7 | Startup hydration | **Done** | task-agent | hydrate-cache! populates registry from Datalevin on boot |
| 8 | Flow registry absorption | **Done** | task-agent | Deleted `seon.flow.registry`, flow handles in `seon.runtime` |
| 9 | Observatory UI (two-level lookup) | **Done** | orchestrator | Running agents enriched with runtime data, runtime instances table added |

**Deferred (future work, not blocking):**

- Phase 2.5: Instance messaging router (`::msg/from-id`/`::msg/to-id`)
- Phase 2.6: Schema-driven routing (`seon.runtime.router`)

---

## Phase 1 Implementation Summary (2026-02-21)

### Files Created

- `src/seon/runtime.clj` — unified runtime registry with 6-char hex ID generation
- `test/seon/runtime_test.clj` — 10 tests, 33 assertions

### Files Modified

- `src/seon/system.clj` — added `:seon/graph-db` component, register/unregister calls
- `resources/system.edn` — added `:seon/graph-db` with dependency wiring
- `src/seon/ctx.clj` — `generate-id` now delegates to `seon.runtime/generate-id`
- `src/seon/orchestrator/session.clj` — `generate-session-id` now delegates to runtime
- `test/seon/ctx_test.clj` — updated for 6-char IDs
- `test/seon/orchestrator/session_test.clj` — updated for 6-char IDs

### Key Changes

1. **`:seon/graph-db` Integrant component** — owns the seon-graph Datalevin connection with merged schema (graph + runtime)
2. **`seon.runtime/runtime-schema`** — Datalevin schema for `:seon.runtime/*` entities
3. **`seon.runtime/generate-id`** — canonical 6-char hex generator with collision checking
4. **`seon.runtime/register!` / `unregister!`** — in-memory cache + Datalevin persistence
5. **`seon.runtime/mark-crashed!`** — called on startup to detect dirty shutdown
6. **Integrant components registered** — datalevin-server, http-server, nrepl-server, schema-registry, code-scanner, graph-db

### Test Results

- 504 unit tests pass, 0 failures
- Runtime tests: 10 tests, 33 assertions

---

## Instance ID Research (2026-02-21)

### Finding: Four Duplicate ID Systems

The codebase has four ID generators, but only two unique algorithms:

1. `seon.ctx/generate-id` -- 4-char hex (2 random bytes)
2. `seon.orchestrator.session/generate-session-id` -- identical code to #1
3. `seon.ai/start-session!` -- `"ses-" + UUID` (separate AI conversation ID)
4. Claude SDK -- its own internal UUID

A single agent launch creates THREE IDs: 4-char hex (infra), AI session UUID, Claude SDK UUID.

### Decision: Unified 6-char Hex (Implemented)

- **Done:** `seon.runtime/generate-id` is the single canonical generator (6-char hex, 3 bytes)
- **Done:** `seon.ctx/generate-id` and `seon.orchestrator.session/generate-session-id` delegate to runtime
- **Done:** Collision check against in-memory `generated-ids` set atom
- AI session should use same hex ID (drop the `"ses-"` prefix UUID) - Phase 2

### Decision: Instance-Addressed Messaging

- Add `::msg/from-id` and `::msg/to-id` to `seon.flow.msg` envelope schema
- Build message router in `seon.runtime` that generalizes the bridge pattern
- Reuse `seon.flow.harness.bridge` promise-per-request-id for reply correlation
- New Phase 2.5 for this work (between session integration and agent run entities)

### Key Files for ID Consolidation

| File | Current ID Generation | Action |
|------|----------------------|--------|
| `src/seon/ctx.clj:133-138` | `generate-id` (4-char hex) | Delete, delegate to `seon.runtime` |
| `src/seon/orchestrator/session.clj:179-184` | `generate-session-id` (identical) | Delete, delegate to `seon.runtime` |
| `src/seon/ai.clj` (via `start-session!`) | `"ses-" + UUID` | Change to use 4-char hex |
| `src/seon/flow/msg.clj` | N/A (uses caller-provided `::msg/id` UUID) | Add `::msg/from-id`, `::msg/to-id` |

---

## Key Decisions Made

1. **Namespace string as identity** -- `:seon.runtime/namespace` is the unique key. No synthetic IDs. Matches code graph join key.

2. **Single entity type for all instances** -- No separate flow/session/agent entity types. A namespace instance IS the thing, regardless of location. Agent runs are separate (one-to-many).

3. **Additive, not rewrite** -- Existing systems keep working. Runtime registry is layered on top. Consumers migrate one phase at a time.

4. **In-memory cache pattern** -- Atoms remain for fast reads. Datalevin is truth. On startup, hydrate atoms from Datalevin. On change, update atom AND schedule Datalevin write (debounced).

5. **Major events only** -- Not tracking intermediate flow data. Atom changes, lifecycle events, snapshots. No event sourcing.

---

## Schema-Driven Routing Research (2026-02-21)

### REPL Findings

**Graph data available:**

- 660 specs indexed in the graph DB, 213 with `:seon.spec/contains-keys`
- 66 functions have `:seon.fn/input-spec` refs linking to their input schema
- Functions across 15 namespaces are linked (seon.ai.claude, seon.ai.agent, seon.dev.*, seon.health, etc.)

**Key-based routing works:**

```clojure
;; Given data keys, find functions that accept data with those keys.
;; Two-phase: Datalog finds candidates by key overlap, Clojure filters for subset match.
;; Example: data with {:seon.ai.agent/session-id "a1b2"} routes to:
;;   seon.ai.agent/get-agent (specificity 1)
;;   seon.ai.agent/tail (specificity 1)
```

**Performance:** 2.3ms to validate data against 44 candidate Malli schemas. Datalog key-lookup is sub-millisecond.

**Open map problem:** Malli maps are open by default. `m/validate` against `[:map]` (no required keys) matches anything. Key-based routing via `:seon.spec/contains-keys` is more precise and should be primary. Malli validation is secondary (value-level precision).

**Coverage:** Only 66/800+ functions have input-spec links. These are functions with `:malli/schema` metadata. As schema coverage grows, routing coverage grows automatically.

### Phase 2.6 Implementation Plan

1. Create `src/seon/runtime/router.clj`:
   - `build-routing-table` -- Datalog query + Clojure filter, returns cached routing table
   - `route` -- given data map, find best matching function + running instance
   - `claim!` / `release!` -- instance-level value claims in Datalevin
   - Routing table cache with invalidation on graph/registry changes

2. Add claim schema to Datalevin:
   - `:seon.route.claim/instance-id` (string, identity)
   - `:seon.route.claim/pattern` (string, EDN blob of key-value constraints)

3. Convention functions discovery:
   - At routing table build time, check each namespace for `route-preference` and `route-priority` vars
   - Store results in the routing table cache

4. Integration with `runtime/send!` (Phase 2.5):
   - When `::msg/to-ns` is omitted, use router to find destination from data shape
   - When provided, skip routing (explicit addressing)

### Malli Capabilities Discovered

- `m/validate` works for runtime shape matching against stored schema definitions (parsed from EDN strings)
- Schema definitions stored in graph reference other registered schemas by keyword -- parsing requires the live `seon.schema` registry
- `m/schema` parses stored definition strings; 64/66 parse successfully (2 failures due to edge cases)
- No built-in subtype/supertype checking in Malli, but not needed -- key-based routing + validation is sufficient
- Schema registry introspection via `seon.schema/schemas-in-namespace` and `seon.schema/registered-schemas` works

---

## Open Questions

- [ ] Should `seon.runtime` be an Integrant component itself, or a stateless namespace that takes a conn parameter? (Leaning toward component -- it needs the graph DB conn.)
- [ ] How to handle the transition period where both `seon.flow.registry` and `seon.runtime` exist? (Phase 7 deletes registry, but intermediate phases need both.)
- [ ] Should in-process namespace instances have ctx atoms? System components (Datalevin server, HTTP server) don't currently have ctx. Maybe only register them without ctx.
- [ ] The `seon.orchestrator.session` module has its own Datalevin namespace DB (`seon.orchestrator`). When migrating to master DB, do we need to migrate old data? (Probably not -- session data is ephemeral.)
- [ ] Schema-driven routing: should the router validate values (Malli) or just check key presence (Datalog)? Key-only is faster and avoids open-map false positives. Value validation adds precision but costs ~2ms per route lookup. Leaning toward key-only with optional value validation for ambiguous cases.
- [ ] Schema-driven routing: how to handle functions without `:malli/schema` metadata (currently 90%+ of functions)? Options: (a) require schemas for routable functions, (b) fall back to explicit `::msg/to-ns` addressing, (c) gradually add schemas. Leaning toward (b) + (c) -- routing is opt-in via schemas.
- [ ] Instance claims: should claims be Datalevin entities or in-memory? Datalevin survives restarts but adds write overhead. Claims change rarely so Datalevin seems right.

---

## Tips and Gotchas

### Datalevin

- **Schema merge at connection time** -- The graph DB connection is created in `system.clj` code-scanner component using `seon.graph.ingest/datalevin-schema`. New runtime schema must be merged here. The conn creation uses `d/get-conn graph-uri datalevin-schema`.
- **No `:db/isComponent true`** -- Don't use component refs for runtime -> agent-run. We want agent runs to survive if the runtime entity is retracted.
- **Nil values silently dropped** -- If `:seon.runtime/session-id` is nil (in-process instance), just don't include it in the transaction map. Don't write `{:seon.runtime/session-id nil}`.
- **`:db.type/keyword` for enums** -- Status and location are keywords. Datalevin indexes them for efficient equality checks.
- **Upsert via unique identity** -- Writing `{:seon.runtime/namespace "seon.web.server" :seon.runtime/status :running}` upserts because `:seon.runtime/namespace` has `:db.unique/identity`.

### core.async.flow

- **`flow/pause` and `flow/stop` are async** -- They send a command and return immediately. The transition happens on the process thread.
- **`flow/ping` is the sync barrier** -- After pause, call ping to block until the transition completes. Pattern: `pause -> ping -> (do work) -> resume`.
- **`flow/ping` returns process state** -- Map of pid -> `{::flow/pid, ::flow/state, ::flow/status}`. The `::flow/state` is the step-fn's state. This is what gets serialized for snapshots.
- **Catch `Throwable` not `Exception`** in transition hooks -- LMDB throws `Error`, not `Exception`.

### Existing Code Patterns

- **`seon.ctx` debounced persistence** -- Uses a `ScheduledExecutorService` per instance with 100ms debounce. The runtime registry should use the same pattern or piggyback on ctx watches.
- **`seon.flow.registry` is a defonce atom** -- Can't just require and replace. Need to either keep the atom as a cache or use `alter-var-root` during migration.
- **`seon.orchestrator.session/session-registry`** stores opaque handles (atoms, pool refs). Only the serializable subset goes to Datalevin.
- **`seon.ai.agent/agent-registry`** stores process handles, channels, atoms. Same issue -- persist metadata, not handles.

### Testing

- **514 unit tests, 0 failures** as of 2026-02-21. Must stay green throughout.
- **Integration tests tagged `^:integration`** -- Run with `bin/test --all`. Skip with `bin/test`.
- **REPL-first** -- Use `(user/run-tests 'seon.runtime-test)` during development, not CLI.
- **Flow pool tests need running JVMs** -- `seon.flow.pool-integration-test` requires agent JVMs. Tag as integration.

---

## File Inventory

### Must Create

| File | Purpose | Phase |
|------|---------|-------|
| `src/seon/runtime.clj` | Unified runtime registry | 1 |
| `test/seon/runtime_test.clj` | Registry tests | 1 |

### Must Modify

| File | Changes | Phase |
|------|---------|-------|
| `src/seon/system.clj` | Add `register!` calls to init-key methods | 1 |
| `src/seon/graph/ingest.clj` | Merge runtime schema | 1 |
| `src/seon/orchestrator/session.clj` | Use runtime registry instead of own persistence | 2 |
| `src/seon/ai/claude.clj` | Write agent run entities | 3 |
| `src/seon/ai/agent.clj` | Agent registry becomes cache over Datalevin | 3 |
| `src/seon/flow/topology.clj` | Snapshot before stop | 4 |
| `src/seon/ctx.clj` | Add `:seon.ctx/runtime` ref | 5 |
| `src/seon/flow/harness.clj` | Use `seon.ctx/*` schema for persist-ctx! | 5 |
| `src/seon/core.clj` | Call hydrate on startup | 6 |
| `src/seon/flow/status.clj` | Use runtime queries | 7 |
| `src/seon/web/flows.clj` | Show runtime instances | 8 |
| `src/seon/web/agents.clj` | Show agent runs from Datalevin | 8 |

### Must Delete (Phase 7)

| File | Replaced By |
|------|-------------|
| `src/seon/flow/registry.clj` | `seon.runtime` queries |
| `test/seon/flow/registry_test.clj` | `seon.runtime-test` |

### Reference (read, don't modify)

| File | Why |
|------|-----|
| `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` | Flow API |
| `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj` | Flow internals (ping, pause, state) |
| `src/seon/flow/pool.clj` | Pool claim/release lifecycle |
| `src/seon/db.clj` | Write coordination pattern |
| `src/seon/db/datalevin/writer.clj` | Flow step-fn for writes |
| `resources/system.edn` | Integrant component graph |

---

## Research Notes

### How flow/ping Works (from impl.clj)

```
send-command ::flow/ping with reply-chan
  -> control channel
    -> each process receives ping command
      -> process replies with {::flow/pid, ::flow/state, ::flow/status}
    -> replies collected within timeout-ms
  -> returns map of pid -> state
```

The `::flow/state` in the ping response IS the step-fn's current state (the accumulator from init/transition/transform). This is the data we serialize for snapshots.

### How Integrant Dependencies Work (from system.edn)

```
datalevin-server (no deps)
  <- connection-manager
    <- code-scanner, primer-ctx, orchestrator-sessions
  <- agent-pool
    <- orchestrator-sessions
schema-registry (no deps)
nrepl-server (no deps)
http-server (no deps)
tailwind-watcher (no deps)
claude-code (no deps)
```

The runtime registry component should depend on `connection-manager` (for Datalevin access) and be depended on by `orchestrator-sessions` and `code-scanner`.

### Graph DB Connection

The code scanner creates its own connection to the `seon-graph` database:

```clojure
;; In system.clj, :seon/code-scanner init-key:
(let [graph-uri (build-uri-fn connection-manager "seon-graph")
      conn (get-conn graph-uri datalevin-schema)]
  ...)
```

The runtime registry needs to use this SAME connection (same DB). Options:

1. Make runtime registry a dep of code-scanner, share the conn
2. Make runtime registry create its own conn to `seon-graph` with merged schema
3. Create a `seon-graph` component that both depend on

Option 3 is cleanest. A new `:seon/graph-db` Integrant component that:

- Creates the `seon-graph` connection with merged schema (code graph + runtime)
- Is depended on by both code-scanner and runtime registry
- Passes the conn to both

This avoids the schema merge timing problem (who creates the conn first?).
