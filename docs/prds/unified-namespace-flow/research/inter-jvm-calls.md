---
type: research
status: active
tags: [research, flow, prd]
---

# Inter-JVM Call Pattern ("REPL Layer")

## Overview

Seon makes function calls transparent across JVM process boundaries. An agent running in a separate JVM calls `(nutrition/metabolic-rate)` with normal Clojure syntax. Under the hood, the call serializes via Nippy over TCP, routes through the orchestrator's core.async.flow topology, executes in the target namespace's JVM, and returns the result. The agent never sees wire protocol, channels, or flow infrastructure.

This document traces the exact mechanics for both call directions.

---

## Key Components

| File | Namespace | Role |
|------|-----------|------|
| `src/seon/flow/topology.clj` | `seon.flow.topology` | Orchestrator-side: `request!` entry point, reply-router, topology builder, cross-ns relay |
| `src/seon/flow/harness.clj` | `seon.flow.harness` | Orchestrator-side: per-namespace flow step function, backpressure, JVM lifecycle |
| `src/seon/flow/harness/bridge.clj` | `seon.flow.harness.bridge` | Agent-side: `execute-local`, `remote-call!`, bridge step function |
| `src/seon/flow/harness/proxy.clj` | `seon.flow.harness.proxy` | Agent-side: transparent proxy namespace creation |
| `src/seon/flow/harness/channel.clj` | `seon.flow.harness.channel` | Wire: TCP server/client with length-prefixed Nippy serialization |
| `src/seon/flow/msg.clj` | `seon.flow.msg` | Envelope schemas for all wire messages |
| `src/seon/flow/pool.clj` | `seon.flow.pool` | Pre-warmed JVM pool (acquire, release, health checks) |

---

## Direction 1: Orchestrator Calls Agent JVM

This is the "forward" direction. The orchestrator (or any code in the core Seon JVM) calls a function that lives in a separate agent JVM.

### Concrete Example

```clojure
;; In orchestrator JVM:
(topology/request! {::topology/flow      flow-obj
                    ::topology/target-ns  "seon.health.nutrition"
                    ::topology/fn         "seon.health.nutrition/metabolic-rate"
                    ::topology/args       []
                    ::topology/timeout-ms 5000})
;; => 1800

```

### Step-by-Step Trace

```
topology/request!
  │
  ├─ 1. Generate UUID request-id
  ├─ 2. Create promise
  ├─ 3. Register promise: (swap! pending-promises assoc request-id promise)
  ├─ 4. Build request envelope:
  │       {::msg/id         <uuid>
  │        ::msg/version    1
  │        ::msg/type       :request
  │        ::msg/from-ns    "orchestrator"
  │        ::msg/to-ns      "seon.health.nutrition"
  │        ::msg/fn         "seon.health.nutrition/metabolic-rate"
  │        ::msg/args       []
  │        ::msg/created-at <instant>}
  │
  ├─ 5. Derive process ID: (keyword "ns" target-ns)
  │     => :ns/seon.health.nutrition
  │
  ├─ 6. Inject into flow:
  │     (flow/inject flow [:ns/seon.health.nutrition :seon.flow.in/request] [request])
  │
  │     ┌─────────────────────────────────────────────────────────────┐
  │     │  core.async.flow topology                                   │
  │     │                                                             │
  │     │  :ns/seon.health.nutrition (harness/namespace-step)         │
  │     │    receives on :seon.flow.in/request                        │
  │     │    ├─ Check backpressure: pending < queue-cap?              │
  │     │    │   NO  → return overload reply immediately              │
  │     │    │   YES → increment pending, forward to JVM:             │
  │     │    │         emit on :seon.flow.out/jvm-request             │
  │     │    │                                                        │
  │     │    │  :seon.flow.out/jvm-request is wired to a TCP          │
  │     │    │  channel (::channel/out-ch of the TCP server)          │
  │     └────┼────────────────────────────────────────────────────────┘
  │          │
  │          ▼
  │     ┌─────────────────────────────────────────────────────────────┐
  │     │  TCP Wire (channel.clj)                                     │
  │     │                                                             │
  │     │  Writer thread takes msg from out-ch:                       │
  │     │    1. nippy/fast-freeze msg → byte[]                        │
  │     │    2. Write 4-byte big-endian length prefix                 │
  │     │    3. Write byte[]                                          │
  │     │    4. Flush                                                 │
  │     │                                                             │
  │     │  ─── TCP socket (localhost) ───                              │
  │     │                                                             │
  │     │  Reader thread on agent side:                               │
  │     │    1. Read 4-byte length                                    │
  │     │    2. Read that many bytes                                  │
  │     │    3. nippy/fast-thaw → msg                                 │
  │     │    4. Put onto in-ch                                        │
  │     └─────────────────────────────────────────────────────────────┘
  │          │
  │          ▼
  │     ┌─────────────────────────────────────────────────────────────┐
  │     │  Agent JVM (bridge loop, injected via nREPL at startup)     │
  │     │                                                             │
  │     │  (loop []                                                   │
  │     │    (when-let [request (async/<!! in-ch)]                    │
  │     │      (let [reply (bridge/execute-local request              │
  │     │                    {::bridge/namespace "seon.health.nutrition"})]│
  │     │        (async/>!! out-ch reply))                            │
  │     │      (recur)))                                              │
  │     │                                                             │
  │     │  execute-local:                                             │
  │     │    1. (requiring-resolve (symbol fn-name))                  │
  │     │       → finds the var                                       │
  │     │    2. (apply the-var args)                                  │
  │     │       → calls the function                                  │
  │     │    3. Time the execution (nanoTime)                         │
  │     │    4. Verify result is Nippy-serializable:                  │
  │     │       (nippy/fast-thaw (nippy/fast-freeze result))          │
  │     │    5. Build reply envelope:                                 │
  │     │       {::msg/id          <same uuid>                        │
  │     │        ::msg/version     1                                  │
  │     │        ::msg/type        :reply                             │
  │     │        ::msg/status      :ok                                │
  │     │        ::msg/value       1800                               │
  │     │        ::msg/from-ns     "seon.health.nutrition"            │
  │     │        ::msg/duration-ms <elapsed>}                         │
  │     └─────────────────────────────────────────────────────────────┘
  │          │
  │          ▼
  │     TCP wire (reverse direction, same format)
  │          │
  │          ▼
  │     ┌─────────────────────────────────────────────────────────────┐
  │     │  Orchestrator: harness step receives on                     │
  │     │  :seon.flow.in/jvm-reply                                    │
  │     │    ├─ Decrement pending count                               │
  │     │    ├─ Emit reply on :seon.flow.out/reply                    │
  │     │    ├─ Emit event on :seon.flow.out/event                    │
  │     │    └─ If error: also emit on :seon.flow.out/error           │
  │     │                                                             │
  │     │  :seon.flow.out/reply is connected to reply-router-step     │
  │     │                                                             │
  │     │  reply-router-step:                                         │
  │     │    1. Extract ::msg/id from reply                           │
  │     │    2. Look up promise in pending-promises atom               │
  │     │    3. (deliver promise reply)                                │
  │     │    4. Remove from pending-promises                          │
  │     └─────────────────────────────────────────────────────────────┘
  │
  ├─ 7. (deref promise timeout-ms ::timed-out)
  │     Blocks calling thread until reply-router delivers
  │
  ├─ 8. Check reply status:
  │     :ok      → return (::msg/value reply)  ;; => 1800
  │     :error   → throw ex-info with error details
  │     :timeout → throw ex-info (deref returned ::timed-out)
  │     :overload → throw ex-info
  │
  └─ 9. Clean up promise from atom on any exception path

```

### Key Design Decisions

- **Promise-before-inject**: The promise is registered in the atom BEFORE `flow/inject`. This prevents a race where the reply arrives before the promise exists.
- **Global atom for promises**: `topology/pending-promises` is a `defonce` atom shared between `request!` (registers) and `reply-router-step` (delivers). This is necessary because `flow/inject` is the mechanism for sending, but promises must exist before injection.
- **Backpressure via pending count**: Each harness step tracks how many requests are in-flight. When `pending >= queue-cap`, new requests get an immediate overload reply without touching the TCP channel.
- **Nippy serialization check**: `execute-local` does a round-trip `fast-thaw(fast-freeze(result))` after every call to catch non-serializable results early, before they hit the wire.

---

## Direction 2: Agent JVM Calls Other Namespace (Reverse Channel)

This is the "reverse" direction. An agent in `seon.health.lifting`'s JVM calls a function in `seon.health.nutrition` (which runs in a different JVM or the orchestrator). The agent code uses normal Clojure syntax via proxy namespaces.

### Concrete Example

```clojure
;; In agent JVM for seon.health.lifting:
(require '[seon.health.nutrition :as nutrition])
(nutrition/metabolic-rate)
;; => 1800

;; The agent doesn't know this is a remote call.
;; "seon.health.nutrition" is a proxy namespace with proxy vars.

```

### Proxy Namespace Setup

Before the agent can call remote functions, proxy namespaces must be created. This happens during JVM initialization (via nREPL eval from `harness/start-namespace-jvm!`).

```clojure
;; Executed in agent JVM during setup:
(proxy/proxy-ns!
  {::proxy/target-ns  "seon.health.nutrition"
   ::proxy/functions   {"metabolic-rate" {::proxy/doc "Return base metabolic rate."
                                          ::proxy/arglists '([])}
                         "daily-summary"  {::proxy/doc "Return today's macro totals."}}
   ::proxy/request-ch  reverse-request-ch   ;; channel back to orchestrator
   ::proxy/from-ns     "seon.health.lifting"})

```

`proxy-ns!` does the following:
1. `(create-ns 'seon.health.nutrition)` -- creates the namespace object
2. For each function entry, creates a closure via `proxy-fn`:
   - The closure captures `request-ch`, `from-ns`, `target-ns`, and the fully-qualified function name
   - When called, it invokes `bridge/remote-call!`
3. `(intern the-ns fn-sym pfn)` -- interns each proxy function into the namespace
4. Attaches metadata: `::proxy? true`, docstring prefixed with `[proxy]`, arglists if provided

After this, `(require '[seon.health.nutrition :as nutrition])` works normally. The namespace exists with real vars that happen to be proxy closures.

### Step-by-Step Trace

```
Agent code: (nutrition/metabolic-rate)
  │
  ├─ Clojure resolves var: seon.health.nutrition/metabolic-rate
  │   This is a proxy function created by proxy-ns!
  │
  ▼
proxy-fn closure (proxy.clj)
  │
  ├─ Calls bridge/remote-call! with:
  │   {::bridge/request-ch      reverse-request-ch
  │    ::bridge/remote-call-timeout-ms 10000
  │    ::msg/to-ns              "seon.health.nutrition"
  │    ::msg/fn                 "seon.health.nutrition/metabolic-rate"
  │    ::msg/args               []
  │    ::msg/from-ns            "seon.health.lifting"}
  │
  ▼
bridge/remote-call! (bridge.clj)
  │
  ├─ 1. Generate UUID request-id
  ├─ 2. Create promise
  ├─ 3. Register: (swap! pending-remote-promises assoc request-id promise)
  ├─ 4. Build request envelope (same ::msg schema)
  ├─ 5. Send on reverse-request-ch:
  │     (async/>!! request-ch request)
  │
  │     ┌─────────────────────────────────────────────────────────────┐
  │     │  TCP wire: agent JVM → orchestrator                         │
  │     │  (reverse-request-ch is wired to a TCP channel)             │
  │     └─────────────────────────────────────────────────────────────┘
  │          │
  │          ▼
  │     ┌─────────────────────────────────────────────────────────────┐
  │     │  Orchestrator: start-cross-ns-relay! (topology.clj)         │
  │     │                                                             │
  │     │  A go-loop reads from reverse-request-ch:                   │
  │     │    1. Extract target-ns, fn-name, args from request         │
  │     │    2. Spawn (async/thread ...) for blocking work:           │
  │     │       a. Call topology/request! with:                       │
  │     │          {::flow       topology-flow                        │
  │     │           ::target-ns  "seon.health.nutrition"              │
  │     │           ::fn         "seon.health.nutrition/metabolic-rate"│
  │     │           ::args       []                                   │
  │     │           ::timeout-ms 10000                                │
  │     │           ::from-ns    "seon.health.lifting"}               │
  │     │                                                             │
  │     │       This is a RECURSIVE call through the flow topology!   │
  │     │       It follows the exact same path as Direction 1:        │
  │     │       inject → harness step → TCP → target JVM bridge →    │
  │     │       execute → reply → reply-router → promise delivered    │
  │     │                                                             │
  │     │       b. Build reply from result (or catch exception):      │
  │     │          {::msg/id      <original request-id>               │
  │     │           ::msg/status  :ok                                 │
  │     │           ::msg/value   1800                                │
  │     │           ...}                                              │
  │     │                                                             │
  │     │       c. Send reply on reverse-reply-ch:                    │
  │     │          (async/>!! reverse-reply-ch reply)                 │
  │     └─────────────────────────────────────────────────────────────┘
  │          │
  │          ▼
  │     ┌─────────────────────────────────────────────────────────────┐
  │     │  TCP wire: orchestrator → agent JVM                         │
  │     │  (reverse-reply-ch is wired to the TCP channel back)        │
  │     └─────────────────────────────────────────────────────────────┘
  │          │
  │          ▼
  │     Agent JVM: reply arrives on bridge's in-port
  │     bridge-step transform handles :seon.flow.in/reply:
  │       1. Extract request-id from reply
  │       2. Look up in pending-remote-promises atom
  │       3. (deliver promise reply)
  │       4. Remove from atom
  │
  ├─ 6. (deref promise timeout-ms ::timed-out)
  │     Blocks the calling thread
  │
  ├─ 7. Check reply status:
  │     :ok → return (::msg/value reply)  ;; => 1800
  │     :error → throw ex-info
  │     ::timed-out → throw ex-info with :timeout
  │
  └─ 8. Clean up promise on exception

```

### Key Design Decisions

- **Two separate promise atoms**: The orchestrator uses `topology/pending-promises` for forward calls; the agent uses `bridge/pending-remote-promises` for reverse calls. They are in different JVMs.
- **Relay uses `async/thread`**: The relay go-loop dispatches `request!` (which is blocking) on a separate thread to avoid blocking the go-loop's thread pool.
- **Recursive routing**: A reverse call goes through the same `topology/request!` as a forward call. This means the full backpressure, timeout, and error handling applies uniformly.
- **Proxy namespaces shadow real ones**: In the agent JVM, `create-ns` creates a namespace that may not have the real source loaded. The proxy vars replace what would have been real function vars. This works because the agent JVM only needs to call functions, not define them.

---

## Wire Protocol

### TCP Channel (channel.clj)

Two entry points producing identical bidirectional `{::in-ch ::out-ch ::close!}` maps:

| Function | Role | Usage |
|----------|------|-------|
| `start-server!` | Listen on port, accept one client | Orchestrator side |
| `connect!` | Connect to server | Agent JVM side |

### Message Format

Every TCP message is:

```
┌──────────────────────────┬──────────────────────────────────┐
│  4 bytes (big-endian int) │  N bytes (Nippy fast-freeze)     │
│  = N (payload length)     │  = serialized Clojure value      │
└──────────────────────────┴──────────────────────────────────┘

```

Nippy `fast-freeze`/`fast-thaw` (headerless) handles all JVM types natively: UUIDs, Instants, byte arrays, nested collections, keywords, etc. No tagged literals or EDN coercion needed.

### Threading Model

Each TCP connection spawns two daemon threads:

- **Reader thread**: Blocks on `DataInputStream.readInt()` + `readFully()`, puts deserialized messages onto `in-ch`. Closes `in-ch` on EOF or IOException.
- **Writer thread**: Takes from `out-ch` via `async/<!!`, writes length-prefixed Nippy bytes. Stops when channel closes.

Channel buffer size is 32 for both `in-ch` and `out-ch`.

### Message Envelope (msg.clj)

All messages share the `::msg/id` (UUID) key. Two envelope types:

**Request:**

```clojure
{::msg/id         <uuid>           ;; echoed in reply
 ::msg/version    1
 ::msg/type       :request
 ::msg/from-ns    "seon.health.lifting"
 ::msg/to-ns      "seon.health.nutrition"
 ::msg/fn         "seon.health.nutrition/metabolic-rate"
 ::msg/args       []               ;; vector of arguments
 ::msg/created-at <instant>
 ;; optional:
 ::msg/trace-id   <uuid>           ;; for distributed tracing
 ::msg/payload    {}               ;; key-value metadata
 ::msg/timeout-ms 10000}

```

**Reply:**

```clojure
{::msg/id            <same uuid>   ;; matches request
 ::msg/version       1
 ::msg/type          :reply
 ::msg/status        :ok            ;; or :error, :timeout, :overload
 ::msg/value         1800           ;; present when :ok
 ::msg/from-ns       "seon.health.nutrition"
 ::msg/duration-ms   3
 ;; present on error:
 ::msg/error-type    :execution     ;; :not-found, :serialization, :timeout, :overload
 ::msg/error-class   "clojure.lang.ExceptionInfo"
 ::msg/error-message "boom"
 ::msg/error-data    {:code :x}}

```

The `::msg/args` and `::msg/value` fields use the `:seon.flow/dynamic` schema type -- a boundary type that defers validation to message boundaries rather than schema-time. This is necessary because these fields carry arbitrary function arguments and return values.

---

## JVM Lifecycle (harness.clj)

`start-namespace-jvm!` acquires a warm JVM from the pool, starts a TCP server, and bootstraps the agent side via nREPL:

1. **Acquire JVM** from `seon.flow.pool` (returns nREPL port + PID)
2. **Start TCP server** on port 0 (OS-assigned random port) in the orchestrator
3. **nREPL eval step 1**: Require bridge and channel namespaces in agent JVM
4. **nREPL eval step 2**: Connect agent JVM to orchestrator's TCP server, start bridge loop:

```clojure
;; Injected into agent JVM:
(def bridge-tcp
  (channel/connect! {::channel/host "localhost"
                     ::channel/port <bridge-port>}))
(def bridge-loop
  (future
    (loop []
      (when-let [request (async/<!! (::channel/in-ch bridge-tcp))]
        (let [reply (bridge/execute-local request
                      {::bridge/namespace "seon.health.nutrition"})]
          (async/>!! (::channel/out-ch bridge-tcp) reply))
        (recur)))))

```

5. **Return channels**: The TCP server's `in-ch` and `out-ch` become the harness step's `in-ports` and `out-ports` in the flow topology.

`stop-namespace-jvm!` closes TCP and releases JVM back to pool.

---

## Topology Wiring (topology.clj)

`build-topology!` creates the complete flow graph:

### Process Definitions

For each namespace in the config, one harness step process is created:

```
Process ID:  (keyword "ns" namespace-string)
             e.g., :ns/seon.health.nutrition

Step fn:     harness/namespace-step
Args:        {::harness/namespace "seon.health.nutrition"
              ::harness/queue-cap 32
              ;; in-ports/out-ports wired to TCP channels}

```

Plus three infrastructure processes:
- `:seon.flow/reply-router` -- delivers promises
- `:seon.flow/event-sink` -- accumulates observability events
- `:seon.flow/error-sink` -- accumulates error replies

### Connections

```
Each namespace step:
  :seon.flow.out/reply → :seon.flow/reply-router :seon.flow.in/reply
  :seon.flow.out/event → :seon.flow/event-sink :seon.flow.out/event
  :seon.flow.out/error → :seon.flow/error-sink :seon.flow.out/error

```

### Cross-Namespace Relays

For namespaces with reverse channels (`::reverse-request-ch` and `::reverse-reply-ch`), `start-cross-ns-relay!` spawns a go-loop that:

1. Reads requests from the agent's reverse-request channel
2. Calls `topology/request!` (which goes through the flow)
3. Sends the reply back on the agent's reverse-reply channel

This is what makes agent-to-agent calls work: the relay converts a reverse-channel request into a normal forward-direction `request!` call.

### Cycle Detection

Before building the topology, `detect-cycles` runs DFS on the namespace proxy graph. If namespace A proxies to B and B proxies to A, the build throws `::cycle-detected`. This prevents deadlocks where two agent JVMs would wait on each other through the relay.

---

## Ctx Injection: Current vs. Planned

### Current State

The harness/bridge system does NOT inject `::ctx`. Functions are called with their raw arguments. The integration tests use orchestrator-choreographed cross-namespace calls: the orchestrator explicitly fetches `metabolic-rate` from nutrition, then passes it as an argument to `calories-burned` in lifting.

### Planned (per design.md)

The unified dispatch layer will intercept calls at the proxy level. Before a function executes, the dispatch layer will:

1. Read the function's `:malli/schema` to detect `::ctx` in input keys
2. If present, fetch the namespace's ctx value and merge it into the input map
3. After execution, if `::ctx` is in output keys, apply the new ctx value
4. Strip `::ctx` from the result before returning to the caller

This means `(workout/total-volume {})` will transparently become `(workout/total-volume {::workout/ctx <current-ctx>})` at the dispatch boundary. The agent passes `{}` and gets back `{::workout/volume 1620.0}`. The ctx flow is invisible.

---

## Error Handling

Five error categories propagate cleanly through the wire:

| Error Type | Source | How |
|------------|--------|-----|
| `:not-found` | `execute-local` cannot resolve function symbol | `requiring-resolve` returns nil |
| `:execution` | Target function throws exception | Caught in `execute-local`, exception class/message/data preserved |
| `:serialization` | Return value fails Nippy round-trip | `fast-thaw(fast-freeze(result))` throws |
| `:timeout` | No reply within `timeout-ms` | `deref promise timeout-ms` returns sentinel |
| `:overload` | Target namespace queue at capacity | Harness step returns immediate reply, never touches TCP |

All errors throw `ex-info` at the caller with structured data containing the error type, message, and (for `:execution`) the original exception class and `ex-data`.

---

## Test Coverage

### What IS Tested

| Test File | Coverage |
|-----------|----------|
| `harness_test.clj` | Harness step function: happy path forwarding, overload, error forwarding, pending count tracking, event emission, trace-id propagation, unknown input, transitions |
| `bridge_test.clj` | `execute-local`: happy path, not-found, execution error, serialization error, trace-id echo. `bridge-step`: describe/init/transition/transform arities |
| `channel_test.clj` | TCP roundtrip, message envelope roundtrip, close cleanup, type fidelity (byte arrays, floats, Instants, UUIDs, nested collections), message ordering |
| `topology_test.clj` | Reply router: deliver/unmatched. Full topology: request happy path, timeout, error reply, overload, multiple namespaces. Cycle detection: no cycles, direct A-B-A, indirect A-B-C-A, self-loop, build-time rejection |
| `integration_test.clj` | End-to-end with mock bridge loops: single-namespace calls, two-namespace topology, orchestrator-choreographed cross-ns calls, error isolation, overload isolation, full workflow session, transparent proxy calls, bidirectional proxy calls, proxy error propagation, proxy timeout |

### What is NOT Tested

- **Real JVM startup via pool**: `start-namespace-jvm!` is tested only via the pool integration test (`pool_integration_test.clj`), not through the full harness-to-bridge path with real TCP
- **Ctx injection through proxy calls**: No tests exist for the planned dispatch-layer ctx injection because it is not yet implemented
- **Concurrent proxy calls from multiple agent threads**: The tests are sequential; no test exercises multiple threads calling proxy functions simultaneously
- **Proxy namespace cleanup on JVM release**: When a JVM is released back to the pool, proxy namespaces from the previous session may linger
- **Large message handling**: No test sends messages larger than a few KB through the TCP channel
- **Connection loss recovery**: No test simulates TCP connection drops mid-conversation

### Test Architecture Note

The integration tests simulate agent JVMs using in-process go-loops with `bridge/execute-local`, not actual separate JVMs. This is a deliberate design choice: it tests the protocol and routing logic without the latency and complexity of real process management. The pool integration test (`pool_integration_test.clj`) tests actual JVM spawning separately.

---

## Infrastructure Flow

Separate from the namespace topology, `build-infrastructure!` creates a flow for cross-cutting concerns:

- `:seon.flow/writer` -- database writes (multi-DB via connection manager)
- `:seon.flow/reader` -- database reads
- `:seon.flow/repl` -- nREPL eval via pool
- `:seon.flow/reply-router` -- same reply-router pattern
- `:seon.flow/event-sink` and `:seon.flow/error-sink`

The infrastructure flow shares the same `pending-promises` atom as namespace topologies. This means `request!` can target infrastructure processes (like `:seon.flow/writer`) using `::pid` instead of `::target-ns`.

---

## Summary: What the Agent Sees vs. What Actually Happens

```
Agent writes:                     System does:
─────────────                     ────────────

(require '[seon.health.nutrition  proxy-ns! creates namespace with
  :as nutrition])                 proxy vars, no real code loaded

(nutrition/metabolic-rate)        proxy-fn closure →
                                  bridge/remote-call! →
                                  reverse-request-ch →
                                  TCP to orchestrator →
                                  start-cross-ns-relay! →
                                  topology/request! →
                                  flow/inject →
                                  harness step →
                                  TCP to nutrition JVM →
                                  execute-local →
                                  (requiring-resolve + apply) →
                                  TCP reply back →
                                  harness step →
                                  reply-router →
                                  TCP to lifting JVM →
                                  bridge delivers promise →
;; => 1800                        remote-call! returns value

```

The entire round-trip involves:
- 2 TCP hops for a forward call (orchestrator to target, target to orchestrator)
- 4 TCP hops for a reverse call (agent to orchestrator, orchestrator to target, target to orchestrator, orchestrator to agent)
- 1 core.async.flow topology traversal (inject + step + reply-router)
- 2 Nippy serialization round-trips per TCP hop (freeze on send, thaw on receive)
- Promise-based synchronization at both ends
