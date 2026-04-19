---
type: component
status: production
tags: [component, agent]
---
# Harness

> Orchestrator-side flow process that routes cross-namespace function calls to isolated agent JVMs via TCP.

## Purpose

The harness is the orchestrator's proxy for a single namespace. It sits inside the [[components/flow-topology]] as a step function and acts as a bridge between the orchestrator's core.async flow and an agent JVM running that namespace's code. When namespace A calls a function in namespace B, the request flows through B's harness, which serializes it over TCP to B's agent JVM, waits for the reply, and routes it back.

The harness also enforces backpressure: each instance tracks pending requests against a configurable queue cap and immediately returns `:overload` errors when saturated.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.flow.harness` | `src/seon/flow/harness.clj` | Step function, JVM lifecycle, backpressure |
| `seon.flow.harness.channel` | `src/seon/flow/harness/channel.clj` | Length-prefixed Nippy over TCP adapter |
| `seon.flow.harness.bridge` | `src/seon/flow/harness/bridge.clj` | Agent-JVM-side execution and reverse channel |
| `seon.flow.harness.proxy` | `src/seon/flow/harness/proxy.clj` | Proxy namespace generation for transparent cross-namespace calls |

## Public API Surface

### `seon.flow.harness`

- **`namespace-step`** — 4-arity core.async.flow step function (describe/init/transition/transform). The main process loop.
- **`start-namespace-jvm!`** — Acquires a JVM from the pool, starts a TCP bridge, injects bridge code via nREPL. Returns in-ports/out-ports map for `namespace-step`.
- **`stop-namespace-jvm!`** — Closes TCP, releases JVM back to pool.

### `seon.flow.harness.channel`

- **`start-server!`** — Opens a TCP `ServerSocket` on loopback, accepts one client. Returns `{::server ::port ::in-ch ::out-ch ::close!}`.
- **`connect!`** — Connects to a TCP server. Returns `{::in-ch ::out-ch ::close!}`.

### `seon.flow.harness.bridge`

- **`bridge-step`** — 4-arity step function that runs inside the agent JVM. Receives requests, calls `execute-local`, returns replies.
- **`execute-local`** — Resolves a fully-qualified function name via `requiring-resolve`, calls it, wraps result in a reply envelope. Validates Nippy round-trip on the result.
- **`remote-call!`** — Blocking reverse-channel call. Agent code calls this to invoke functions in other namespaces via the orchestrator. Uses a promise map (`pending-remote-promises`) keyed by request ID.

## Dependencies

### Uses

- [[components/flow-topology]] (pool) — `pool/acquire!`, `pool/release!`, `pool/nrepl-eval!` for JVM lifecycle
- `seon.flow.msg` — `::msg/*` envelope keys for all request/reply/event messages
- `seon.flow.trace` — `trace/persist-event!` for observability (forward, error, overload, ok events)
- [[components/schema-system]] — `schema/register!` for attribute registration
- Nippy — `fast-freeze`/`fast-thaw` for TCP serialization
- `core.async` — channels for in-port/out-port wiring

### Used By

- [[components/flow-topology]] — harness instances are registered as flow processes in the topology
- Orchestrator — launches harnesses via `start-namespace-jvm!` when spinning up agents

## How Data Flows

```
Namespace A (caller)
    |
    v request msg on :seon.flow.in/request
+----------------------------------+
|  namespace-step (orchestrator)   |
|  +- pending < cap? --> forward   |
|  |   (increment pending)         |
|  +- pending >= cap? --> :overload|
+----------+------- ---------------+
           | :seon.flow.out/jvm-request
           v
    TCP (Nippy, length-prefixed)
           |
           v
+----------------------------------+
|  bridge loop (agent JVM)         |
|  +- read from ::channel/in-ch    |
|  +- execute-local (resolve + call)|
|  +- Nippy round-trip validation  |
|  +- write reply to ::channel/out-ch|
+----------+------- ---------------+
           |
           v TCP back to orchestrator
    :seon.flow.in/jvm-reply
           |
           v
    namespace-step (decrement pending, route reply)
           |
           v :seon.flow.out/reply
    Reply router -> deliver promise to caller

```

### Reverse Channel (cross-namespace from agent side)

When agent code needs to call a function in another namespace, `remote-call!` sends a request back through the TCP channel to the orchestrator, which routes it to the target harness. The reply flows back and is delivered to the waiting promise via `bridge-step`'s `:seon.flow.in/reply` handler.

### `seon.flow.harness.proxy`

- **`proxy-fn`** — Creates a single proxy function that routes calls through the reverse channel. Takes `{::request-ch ::from-ns ::target-ns ::fn-name ::fn-meta}`, returns a blocking function.
- **Namespace generation** — Creates proxy namespaces in the agent JVM so agent code uses normal `(require '[seon.foo :as foo])` / `(foo/bar ...)` syntax. Calls are transparently routed via `bridge/remote-call!` to the orchestrator, which dispatches to the target namespace's harness.

## Design Decisions

### 4-Arity Step Function Pattern

All flow processes use the same 4-arity protocol:

1. **Describe** (0-arity) — declares ins, outs, params, workload type
2. **Init** (1-arity) — receives params, returns initial state. Supports external `::in-ports`/`::out-ports` injection for testing.
3. **Transition** (2-arity) — handles `:stop`, `:pause`, `:resume` lifecycle events
4. **Transform** (3-arity) — pure-ish state machine. `[state input-id msg] -> [new-state output-map]`

### Backpressure via Pending Count

Rather than relying on channel buffer semantics, the harness tracks an explicit `::pending` counter against `::queue-cap` (default 32). This gives immediate, typed overload responses rather than blocking the caller.

### Length-Prefixed Nippy over TCP

The channel adapter uses a simple wire protocol: 4-byte big-endian length prefix + Nippy bytes (`fast-freeze`/`fast-thaw`, no header). Channel buffers are 32 deep. Reader and writer run on daemon threads per socket.

### Bridge Code Injection via nREPL

`start-namespace-jvm!` sends two nREPL evals to the agent JVM:

1. Require the channel and bridge namespaces (they're on the classpath)
2. Connect back to the orchestrator's TCP server and start a request loop in a `future`

This avoids needing custom class loading — the agent JVM is a standard Clojure process with `src/` on its classpath.

### Nippy Round-Trip Validation

`execute-local` does a `fast-thaw(fast-freeze(result))` check before returning. If the result isn't serializable, it returns a `:serialization` error rather than letting the TCP channel fail silently.

## Refactoring Opportunities

1. **Bridge code injection is string-based** — The nREPL eval in `start-namespace-jvm!` constructs Clojure code as a string with string concatenation. This is fragile. Could use `pr-str` on quoted forms consistently (the first eval already does this, but the second doesn't).

2. **Single-client TCP server** — `start-server!` accepts exactly one client. The accept thread, reader thread, and writer thread are raw Java threads. If the architecture ever needs reconnection or multiple clients, this needs rework.

3. **Global mutable state in bridge** — `pending-remote-promises` is a `defonce` atom at the module level. This means only one bridge can run per JVM. Adequate for the current 1:1 harness-to-JVM model, but would need scoping for multi-namespace JVMs.

4. **Trace persistence in futures** — `namespace-step` fires `(future (trace/persist-event! ...))` for every request/reply. These futures are fire-and-forget with no error handling.

5. **No reconnection logic** — If the TCP connection drops, the harness has no way to reconnect. The entire JVM must be torn down and re-acquired.
