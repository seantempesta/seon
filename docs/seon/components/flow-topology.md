---
type: component
status: production
tags: [component, flow]
---
# Flow Topology

> core.async.flow-based routing backbone for all cross-boundary calls -- database writes, REPL eval, and inter-namespace function calls.

## Purpose

The flow topology is Seon's central nervous system. Every cross-boundary operation -- database writes, database reads, REPL eval on agent JVMs, and cross-namespace function calls -- routes through `topology/request!`. This gives a single observation point for all system activity: tracing, backpressure, error collection, and status monitoring.

Two separate flows exist:

1. **Infrastructure flow** -- shared services (DB writer, DB reader, REPL eval, reply router, sinks)
2. **Namespace flow** -- per-agent routing via [[components/harness]] processes, one per namespace

Both flows use the same reply-router pattern: caller registers a promise, injects a message, step-fn processes it and emits a reply, reply-router delivers the promise.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.flow.topology` | `src/seon/flow/topology.clj` | Infrastructure flow, namespace flow, `request!`, reply-router, cycle detection |
| `seon.flow.msg` | `src/seon/flow/msg.clj` | Message envelope schemas (request, reply, event) -- wire protocol |
| `seon.flow.status` | `src/seon/flow/status.clj` | Runtime status collection, throughput calculation, error drain |
| `seon.flow.trace` | `src/seon/flow/trace.clj` | Persist flow events to Datahike (`:seon.flow` db) for Observatory UI |
| `seon.flow.pool` | `src/seon/flow/pool.clj` | Pre-warmed JVM pool -- spawn, acquire, release, health check agent JVMs |
| `seon.flow.harness` | `src/seon/flow/harness.clj` | Per-namespace step-fn that routes requests to agent JVMs via TCP |
| `seon.flow.harness.channel` | `src/seon/flow/harness/channel.clj` | TCP channel management for harness-to-agent communication |
| `seon.flow.harness.proxy` | `src/seon/flow/harness/proxy.clj` | Proxy namespace generation for transparent cross-namespace calls on agent JVMs |
| `seon.flow.agent-runner` | `src/seon/flow/agent_runner.clj` | Agent JVM entry point — starts nREPL server (M-1: removed the legacy `--datalevin-uri` flag; agents no longer dial a separate database process) |

## Public API Surface

### topology (core routing)

| Function | Purpose |
|----------|---------|
| `request!` | **The universal cross-boundary call.** Blocking. Creates promise, injects into flow, derefs with timeout. Returns value or throws. |
| `build-infrastructure!` | Start the infrastructure flow (writer + reader + repl + reply-router + sinks). Called once at system boot. |
| `build-topology!` | Start a namespace flow with harness processes per namespace + reply router + sinks. Detects cycles at build time. |
| `stop-topology!` | Pause, snapshot state, stop flow, clean up promises, unregister from runtime. |
| `detect-cycles` | DFS cycle detection on namespace proxy dependency graph. |

### msg (wire protocol)

No functions -- pure schema definitions. Registers schemas for `::request`, `::reply`, `::event` envelopes plus all field types.

### status (observability)

| Function | Purpose |
|----------|---------|
| `start-error-drain!` | Background go-loop that accumulates errors from a flow's error-chan (sliding window of 100). |
| `stop-error-drain!` | Stop drain, clear error/count state for a flow. |
| `collect-flow-status` | Status snapshot for one flow: ping processes, compute throughput (msgs/sec from count deltas), gather errors. |
| `collect-status` | Snapshot all registered flows. Returns `{::flows ... ::alerts ...}`. |

### trace (persistence)

| Function | Purpose |
|----------|---------|
| `persist-event!` | Fire-and-forget write of a flow event to Datahike (`:seon.flow` db). |
| `events-for-session` | Query trace events for an agent session, newest first. |

### pool (JVM management)

| Function | Purpose |
|----------|---------|
| `create-pool!` | Spawn N agent JVMs concurrently in background. Cleans stale processes first. Non-blocking. |
| `acquire!` | Get a warm JVM, load namespace code. Non-blocking or with timeout. Auto-replenishes. |
| `acquire!!` | Blocking indefinite acquire (uses `LinkedBlockingQueue.take`). |
| `claim!` | Session-aware acquire: assigns session-id, injects `*ctx*`, triggers instrumentation. |
| `release!` | Reset namespace, return JVM to idle queue. |
| `release-session!` | Release by session-id. |
| `dispose!` | Kill JVM, spawn replacement. |
| `pool-status` | Current counts (total/idle/active/warming) + per-JVM details. |
| `shutdown!` | Kill all JVMs, stop health checker. |
| `nrepl-eval!` | Evaluate code on an agent JVM via nREPL. Also used by `repl-step`. |

## Dependencies

**Uses:**

- `clojure.core.async.flow` -- flow creation, process definition, inject, ping, start/stop/pause/resume
- `clojure.core.async` -- go-loops for cross-ns relay and error drains
- [[components/database]] (`seon.db`, `seon.db.datahike.conn-process`) -- per-db conn-process step-fns serialize reads/writes against the embedded Datahike store
- [[components/schema-system]] (`seon.schema`) -- schema registration
- `seon.runtime` -- flow registry (register/unregister/list/get flows), topology snapshots
- `nrepl.core` -- communication with agent JVMs
- `integrant.core` -- pool lifecycle component

**Used by:**

- `seon.system` -- Integrant wires `build-infrastructure!` and pool as components
- `seon.ns.lifecycle` -- uses `build-topology!` to start namespace flows
- `seon.orchestrator.session` -- uses pool `claim!`/`release-session!` for agent lifecycle
- `seon.db` -- uses `request!` (via `flow-request!`) for all DB operations outside direct mode
- Any code calling `db/transact!`, `db/query`, etc. -- implicitly routes through the infrastructure flow

## How Data Flows

### Infrastructure Flow (shared services)

```
                    +-------------+
  flow/inject ------>  :seon.flow/ |---- :seon.flow.out/reply ----+
  (DB write)        |    writer    |---- :seon.flow.out/error --+ |
                    +-------------+                             | |
                    +-------------+                             | |
  flow/inject ------>  :seon.flow/ |---- :seon.flow.out/reply --+ |
  (DB read)         |    reader    |---- :seon.flow.out/error --+ |
                    +-------------+                             | |
                    +-------------+                             | |
  flow/inject ------>  :seon.flow/ |---- :seon.flow.out/reply --+ |
  (REPL eval)       |     repl     |---- :seon.flow.out/error --+ |
                    +-------------+                             | |
                                                                | |
                    +-------------+                             | |
                    | :seon.flow/  |<-- :seon.flow.in/reply ----+ |
                    | reply-router |<-----------------------------+
                    +------+------+
                           | deliver(promise)
                           v
                    caller unblocks
                                        +--------------+
                    error channels -----> :seon.flow/   |
                                        |  error-sink   |
                                        +--------------+

```

Built by `build-infrastructure!`. Six processes. Writer uses connection manager for multi-DB writes. Reader uses connection manager for multi-DB reads. REPL step calls `pool/nrepl-eval!`.

### Namespace Flow (per-agent routing)

```
  request!(target-ns="seon.health.lifting")
       |
       v flow/inject [:ns/seon.health.lifting :seon.flow.in/request]
  +--------------------+
  | :ns/seon.health.   |---- :seon.flow.out/reply ----> reply-router --> deliver(promise)
  |     lifting        |---- :seon.flow.out/error ----> error-sink
  |  (harness step)    |---- :seon.flow.out/event ----> event-sink
  +--------------------+
       | forwards via TCP to agent JVM
       v
  agent JVM on port 790X

```

Built by `build-topology!`. One harness process per namespace, plus shared reply-router, event-sink, error-sink. Harness manages backpressure (queue capacity) and traces events.

### The `request!` Pattern

1. Caller creates a `promise` and a UUID request-id
2. Promise registered in global `pending-promises` atom (before injection)
3. Message injected into target process's `:seon.flow.in/request` input
4. Step-fn processes request, emits reply on `:seon.flow.out/reply`
5. Reply-router receives reply, looks up promise by `::msg/id`, delivers it
6. Caller's `deref` unblocks with timeout (default 10s)
7. On `:ok` status, returns `::msg/value`. On error/timeout/overload, throws `ex-info`.

### Cross-JVM Relay

For agent JVMs that need to call functions in other namespaces, `start-cross-ns-relay!` creates a go-loop that:

1. Reads requests from `reverse-request-ch` (sent by agent proxy functions)
2. Calls `request!` to route through the flow (on a separate thread via `async/thread`)
3. Sends the reply back on `reverse-reply-ch`

### Message Envelope (msg.clj)

All messages use the same envelope format with namespaced keys under `seon.flow.msg`:

**Request**: `::id` (UUID), `::version` (always 1), `::type` (:request), `::from-ns`, `::to-ns`, `::fn` (fully qualified string), `::args` (vector), `::created-at` (Instant). Optional: `::trace-id`, `::timeout-ms`, `::reply-required?`, `::payload`.

**Reply**: `::id` (matches request), `::version`, `::type` (:reply), `::status` (:ok/:error/:timeout/:overload), `::from-ns`, `::duration-ms`. On success: `::value`. On error: `::error-type`, `::error-class`, `::error-message`, `::error-data`.

**Event**: `::id`, `::version`, `::type` (:event), `::event-kind` (:start/:ok/:error/:overload/:timeout/:pause/:resume/:stop), `::from-ns`, `::created-at`. Optional: `::payload`.

Dynamic fields (`::args`, `::value`, `::payload`) use `:seon.flow/dynamic` type instead of `:any` -- validated at message boundaries against the target function's `:malli/schema`.

Wire format is length-prefixed Nippy (`fast-freeze`/`fast-thaw`) for inter-JVM TCP.

## Design Decisions

- **Global `pending-promises` atom**: Shared between `request!` (registers) and `reply-router-step` (delivers). Global because promises must be registered before `flow/inject`, but the router is inside the flow. Works because UUIDs are unique.
- **Sync barrier via `flow/ping`**: Both `build-infrastructure!` and `build-topology!` call `flow/ping` with 5s timeout after `flow/resume`. This prevents race conditions where `flow/inject` arrives before step-fn threads have started their loops.
- **Cycle detection at build time**: `build-topology!` runs DFS on the namespace proxy dependency graph before creating any flow processes. Throws immediately on circular dependencies.
- **Topology snapshot on stop**: `stop-topology!` pauses the flow, takes a state snapshot via `runtime/snapshot-topology!`, then stops. Preserves process state for debugging.
- **Promise cleanup on shutdown**: Both `stop-topology!` and `before-ns-unload` deliver timeout errors to all pending promises, preventing caller threads from blocking forever.
- **LinkedBlockingQueue for pool**: Natural thread-safety -- `poll` prevents two threads from grabbing the same JVM. `take` provides blocking semantics for `acquire!!`.
- **Auto-replenishment**: Every `acquire!` triggers background `replenish-pool!` to maintain target idle count. Rate-limited to 6 respawns/minute to prevent port exhaustion.
- **Grace period for health checks**: New JVMs get 60s before health checks start, allowing post-ready setup (namespace loading and instrumentation) to complete.
- **Stale process cleanup on pool creation**: Scans full port range 7900-7999, kills any bound processes via `lsof` + `kill -9`. Prevents port conflicts from previous crashes.

## Channel Buffer Configuration

Flow connections support `chan-opts` with `:buf-or-n` and `:xform` per input/output. Default is `{:buf-or-n 10}`. Key patterns:

- **`sliding-buffer N`** — drop oldest when full. Use for "latest state wins" (ctx persistence, UI updates). Natural backpressure debouncing: fast producer + slow consumer = automatic batching.
- **`dropping-buffer N`** — drop newest when full. Use for observability (keep recent history for reconnection replay).
- **Unbuffered (size 1)** — synchronous handoff. Use for critical updates where delivery confirmation matters.
- **`sliding-buffer 1`** on persist channels — proven pattern for debouncing writes. Writer I/O provides natural backpressure; rapid changes coalesce to latest state. See [[prds/unified-namespace-flow/research/ctx-flow-sync]].

Buffer decisions are topology-level configuration, not code-level. See `flow/create-flow` `:chan-opts` parameter. Currently not exposed in `build-topology!` signature — see refactoring opportunities below.

## Refactoring Opportunities

- **`pending-promises` is global mutable state**: Works but makes testing harder and prevents running multiple independent topologies. Could be scoped to the flow instance.
- **`repl-step` in topology.clj**: The REPL step-fn is defined in topology.clj alongside infrastructure concerns. Could live in its own namespace for clarity.
- **Pool `claim!` does too much**: Acquires JVM, sets up namespace, injects `*ctx*` via nREPL string eval, triggers instrumentation. The `*ctx*` injection is particularly fragile (EDN round-trip, string-based `intern`). Could be split into acquire + configure phases.
- **`status.clj` uses `:any` in `::recent` errors schema**: `[:vector :any]` in `::flow-status` errors. Should be typed to the error envelope shape.
- **No `:malli/schema` on status functions**: Noted in source -- opaque flow objects and channels resist generation. Could use `:any` with description at minimum, or factor out the queryable parts.
- **Pool health check polls via nREPL eval**: Each health check opens an nREPL connection and evals `:ok`. With many JVMs this creates connection churn. A lightweight TCP ping would be cheaper.
- **Channel buffer configuration not exposed**: `build-topology!` and `build-infrastructure!` hardcode default buffers. Exposing `chan-opts` in the topology config would enable performance tuning without code changes.
