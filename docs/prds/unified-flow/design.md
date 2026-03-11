---
type: prd
status: draft
tags: [prd, database, flow, architecture]
---
# Unified Flow System Design

## 1. Core Insight: Rich Hickey's Flow Is Already the Right Abstraction

After reading the flow source code thoroughly, the key realization is that flow provides exactly five primitives. Everything else is composition.

### The Five Primitives

```
PRIMITIVE        WHAT IT IS                              API
─────────────────────────────────────────────────────────────────────
Process          A thread running a step-fn loop         flow/process
Connection       Wire one output to another's input      :conns in config
Flow             A graph of processes + connections       flow/create-flow
Inject           Put data into any point from outside     flow/inject
Ping             Observe any process's state from outside flow/ping
```

That's it. Everything in Seon -- namespace isolation, database writes, error handling, observability -- is a composition of these five things.

### How a Process Works (from the source)

A process is a loop running on a thread. The loop:

1. When **paused**: blocks on the control channel waiting for resume/stop/ping
2. When **running**: `alts!!` across ALL input channels + control + cast channels (priority on control)
3. On message: calls `(transform state input-id msg)` -> `[state' {out-id [msgs]}]`
4. Sends each output message to the corresponding output channel (interleaving control checks)
5. On exception in transform: reports to `::flow/error` channel, continues running
6. On `::flow/stop`: falls out of the loop, thread exits

Key detail from the impl: **a process reads from ALL its inputs simultaneously via `alts!!`**. There is no "one input channel" -- a process can have N inputs and the process loop multiplexes them. The `input-id` argument to transform tells you which input the message came from.

### How Connections Work (from the source)

```clojure
:conns [
  [[:proc-a :out]  [:proc-b :in]]     ; 1:1 direct (no intermediate channel)
  [[:proc-a :out]  [:proc-c :in]]     ; now :out is mult'd to both b and c
]
```

When an output connects to exactly one input, flow optimizes: no intermediate channel, the output channel IS the input channel. When an output connects to multiple inputs, flow creates a `core.async/mult` automatically.

Unconnected outputs get `nil` channels -- writes are silently dropped.

### How Errors Flow (from the source)

There is ONE `error-chan` per flow, created by `start`. Every process gets it as `(outs ::flow/error)`. When transform throws, the process catches the exception and puts a map on error-chan:

```clojure
{::flow/pid pid, ::flow/status status, ::flow/state state,
 ::flow/count count, ::flow/cid cid, ::flow/msg msg,
 ::flow/op :step, ::flow/ex ex}
```

The process then **continues running**. Errors do not stop processes. This is critical -- a bad message does not take down the system.

There is also ONE `report-chan` per flow. Any process can write to `::flow/report` for observability events. And `ping` responses arrive on report-chan too.

### Signals (Broadcast)

Flow has a broadcast mechanism: `flow/inject` to `[::flow/cast signal-id]` sends to ALL processes that declared interest via `:signal-select` in their describe. This is how you'd broadcast "time to backup" or "config changed" to all processes without explicit wiring.

---

## 2. How the Namespace Step Actually Works

### The Current Implementation

Reading `harness.clj`, `namespace-step` is a standard flow step-fn with:

```
INPUTS                          OUTPUTS
─────────────────────────────── ───────────────────────────────
:seon.flow.in/request           :seon.flow.out/reply
(from topology inject)          (to reply-router)

:seon.flow.in/jvm-reply         :seon.flow.out/jvm-request
(in-port from TCP)              (out-port to TCP)

                                :seon.flow.out/error
                                (to error-sink)

                                :seon.flow.out/event
                                (to event-sink)
```

The step-fn dispatches on `input-id`:

- `:seon.flow.in/request` -> check queue cap -> forward to `:seon.flow.out/jvm-request` (TCP to agent JVM)
- `:seon.flow.in/jvm-reply` -> update pending count -> emit on `:seon.flow.out/reply`

### Answering the User's Questions

**"The one step function transparently injected at runtime into each namespace allows it to handle all public functions in that namespace right?"**

Yes. The namespace-step does not know or care which function is being called. It receives a message envelope with `::msg/fn` (the fully-qualified function name) and `::msg/args`, and forwards the entire envelope to the agent JVM. The agent JVM's bridge calls `execute-local` which resolves the var by name and applies the args. The step is a transparent router -- all functions in that namespace go through the same process, the same input channel, the same step-fn.

**"Does that mean we only have one input and one output channel per namespace?"**

In the current design: **one flow-managed input** (`:seon.flow.in/request`) plus **one in-port** (`:seon.flow.in/jvm-reply` from TCP). And four outputs. But from the caller's perspective, yes -- you inject a request into the one input, and replies come out the one reply output.

However, flow supports N inputs and N outputs per process. We could have multiple inputs if needed (e.g., separate priority channels). The step-fn's transform knows which input the message came from via the `input-id` argument.

**"What about error reporting?"**

Three error paths:

1. **Transform throws**: flow catches it, puts on `error-chan`. Process continues. Caller's promise times out.
2. **Agent JVM returns error reply**: step-fn emits on `:seon.flow.out/error` AND `:seon.flow.out/reply`. Reply-router delivers error to caller's promise. Caller gets the error.
3. **Overload (queue full)**: step-fn returns error reply immediately without forwarding. Same path as #2.

Missing path: if `flow/inject` fails (flow not running), `request!` catches the exception and throws directly.

### Channel Topology for a Single Namespace

```
                    ┌─────────────────────────────────────┐
                    │         namespace-step               │
  flow/inject ─────>  :seon.flow.in/request               │
                    │         │                            │
                    │         ├─[queue full]──> :seon.flow.out/reply ───> reply-router
                    │         │                            │
                    │         └─[forward]───> :seon.flow.out/jvm-request ─── TCP ──> agent JVM
                    │                                      │
  TCP <── agent ────>  :seon.flow.in/jvm-reply             │
                    │         │                            │
                    │         ├──────────────> :seon.flow.out/reply ───> reply-router
                    │         ├──[if error]──> :seon.flow.out/error ───> error-sink
                    │         └──────────────> :seon.flow.out/event ───> event-sink
                    └─────────────────────────────────────┘
```

---

## 3. The Writer as a Namespace Step

### Single Writer Process for ALL Databases

Per user feedback: one writer process handles writes to ALL databases. Not one-per-DB. This is simpler (one process to manage), and internally the writer can parallelize per-DB if needed (since different LMDB environments have independent write locks).

The writer is just another step-fn in the topology:

```clojure
(defn writer-step
  ;; describe
  ([]
   {:ins {:in/tx "Write requests (any database)"}
    :outs {:out/reply "Write results"}
    :params {:conns "Map of db-name -> Datalevin connection"}
    :workload :io})

  ;; init
  ([{:keys [conns]}]
   {:conns conns
    :total-writes 0
    :total-errors 0})

  ;; transition
  ([state transition] ...)

  ;; transform
  ([state input-id msg]
   ;; msg contains ::msg/fn "seon.db/transact!"
   ;;              ::msg/args [db-name tx-data tx-meta]
   ;; Dispatch to d/transact! on the right connection
   ...))
```

It sits in the topology like any other process:

```
                      TOPOLOGY
  ┌────────────────────────────────────────────────────┐
  │                                                    │
  │  :ns/seon.health.lifting  ──reply──┐               │
  │  :ns/seon.health.nutrition ──reply──┤               │
  │  :seon.flow/writer ─────────reply──┤               │
  │                                    v               │
  │                          :seon.flow/reply-router   │
  │                                                    │
  │  (event-sink, error-sink also wired)               │
  └────────────────────────────────────────────────────┘
```

Callers use the same `topology/request!` to reach the writer as they use for any namespace call. The writer process ID is `:seon.flow/writer`. A write request looks like:

```clojure
(topology/request!
  {::topology/flow flow
   ::topology/target-ns "seon.flow/writer"  ; or use the pid directly
   ::topology/fn "seon.db/transact!"
   ::topology/args [:seon.health [{:workout/type :squat}]]})
```

From an agent JVM, `seon.db/transact!` is a proxy function that sends this through the reverse channel.

### Killable/Restartable

Because the writer is a process in the flow:

- `flow/pause-proc` pauses it (for backups)
- `flow/resume-proc` resumes it
- `flow/stop` + `flow/start` restarts the whole flow (including writer)
- For writer-in-separate-JVM (Phase 4): kill the JVM, acquire a new one, replay pending writes

### Internal Per-DB Parallelism (Optional)

The writer step-fn receives requests serially (one at a time through transform). For Phase 1, this is fine -- writes are serialized. If throughput matters later, the writer could:

1. Use `:workload :compute` so transform runs on a thread pool
2. Internally dispatch to per-DB `d/transact!` calls that can run in parallel
3. Or split into per-DB writer processes (more topology, but flow handles the wiring)

For now: serial is correct and simple. Datalevin server serializes writes anyway.

---

## 4. The Primitive Composition

### What the user wants: few powerful primitives, composed

Here is the entire Seon flow architecture in terms of the five primitives:

```
PRIMITIVE        SEON USAGE
─────────────────────────────────────────────────────────────────────
Process          namespace-step (per agent namespace)
                 writer-step (single, all DBs)
                 reply-router-step (delivers promises)
                 event-sink-step (observability)
                 error-sink-step (error collection)

Connection       namespace reply -> reply-router
                 writer reply -> reply-router
                 namespace error -> error-sink
                 namespace event -> event-sink

Flow             One flow = the entire topology
                 create-flow + start + resume = running system

Inject           request! injects into target process input
                 External data enters through inject

Ping             REPL observability: (flow/ping topology)
                 Health checks: ping writer for pending count
                 Flow-monitor visualization
```

### The Composition Pattern

Every cross-boundary call follows one pattern:

```
1. Register promise in atom       (request!)
2. Inject message into flow       (flow/inject)
3. Process handles message        (step-fn transform)
4. Reply flows to reply-router    (connection)
5. Router delivers promise        (reply-router-step)
6. Caller derefs promise          (request!)
```

This is the ONLY pattern. Namespace calls, database writes, and (future) any other cross-process operation all use this exact sequence. The step-fn in step 3 is the only thing that varies.

### In-ports and Out-ports: Bridging External Systems

Flow's `::flow/in-ports` and `::flow/out-ports` (returned from init) let a process bring external channels into the flow. This is how TCP connections enter:

```
External world ──> in-port ──> process reads via alts!! ──> transform
                                                              │
transform output ──> out-port ──> External world
```

The namespace-step uses this for TCP to/from agent JVMs. The writer could use this for a future separate-JVM writer. The in-ports/out-ports are not visible to the flow's connection system -- they are private to the process. This is the "source/sink" pattern from the flow guide.

---

## 5. Error Handling Design

### Three Layers

```
LAYER 1: Flow Infrastructure Errors
  - Transform throws -> error-chan (automatic, process continues)
  - Channel xform errors -> error-chan (automatic)
  - These are bugs in step-fns

LAYER 2: Application Errors (in reply envelopes)
  - Function not found -> reply with status :error, error-type :not-found
  - Function throws -> reply with status :error, error-type :execution
  - Serialization failure -> reply with status :error, error-type :serialization
  - Queue full -> reply with status :overload
  - These flow through the normal reply path to the caller

LAYER 3: Timeout Errors
  - request! deref times out -> throws ex-info with :timeout
  - Caller decides what to do (retry, fail, log)
```

Layer 1 errors go to the flow's error-chan. The error-sink collects them. They are operational alerts.

Layer 2 errors go through the normal reply path. The caller gets a structured error and can handle it. The error-sink also gets a copy (namespace-step emits on both `:out/reply` and `:out/error`).

Layer 3 errors are caller-side. The promise is never delivered. `request!` cleans up the pending-promises atom.

### Error Observability

```clojure
;; See all recent errors
(flow/ping-proc topology :seon.flow/error-sink)
;; => {::flow/state {:recent-errors [...]} ...}

;; See flow-level errors (transform exceptions)
(async/poll! error-chan)

;; See writer stats
(flow/ping-proc topology :seon.flow/writer)
;; => {::flow/state {:total-writes 142 :total-errors 3 :pending 0} ...}
```

---

## 6. Current State Assessment

### What Exists and Works

| Component | Status | Notes |
|-----------|--------|-------|
| `flow/msg.clj` | Working | Wire format, tagged literals |
| `flow/topology.clj` | Working | `request!`, `build-topology!`, reply-router, cycle detection |
| `flow/harness.clj` | Working | namespace-step, JVM lifecycle |
| `flow/harness/bridge.clj` | Working | execute-local, remote-call! |
| `flow/harness/proxy.clj` | Working | transparent proxy namespace creation |
| `flow/harness/channel.clj` | Working | TCP <-> core.async |
| `flow/pool.clj` | Working | Pre-warmed JVM pool |
| `flow/status.clj` | Working | Status collection |
| `flow/trace.clj` | Working | Event persistence |
| `db/datalevin/conn.clj` | Working | Connection manager |
| `db.clj` reads | Working | query, pull, etc. |
| `db.clj` writes | Working | transact! via future+timeout (NOT through flow) |
| `db/datalevin/writer.clj` | Partial | Step-fn exists, standalone flow exists, not wired into topology |

### What Needs to Change

1. **Writer step-fn**: rewrite to handle ALL databases (currently per-DB), remove standalone flow
2. **`seon.db/transact!`**: route through topology instead of direct future+timeout
3. **`build-topology!`**: accept writer process alongside namespace processes
4. **Agent bootstrap**: create `seon.db` proxy during agent JVM setup

---

## 7. Implementation Phases

### Phase 1: Writer in Topology (2 hours, 4 files)

**Goal**: Single writer-step for all DBs, wired into topology. `seon.db/transact!` routes through it.

**Files**: `writer.clj`, `db.clj`, `topology.clj`, `writer_test.clj`

1. Rewrite `db-writer-step` to accept map of db-name -> conn, dispatch by db-name in the request
2. Delete `write-reply-step`, `create-writer-flow`, `inject-tx!`
3. `build-topology!` accepts `::writer` config alongside `::namespaces`
4. `transact!` calls `topology/request!` targeting `:seon.flow/writer`
5. Fallback: if no topology running (boot), direct `d/transact!` with future+timeout

**Verification**: Existing callers (`seon.ctx`, `seon.flow.trace`) still work. Writer tests pass.

### Phase 2: Agent Write Proxy (2 hours, 3 files)

**Goal**: Agents call `seon.db/transact!` in their JVM, transparently routed to writer.

**Files**: `harness.clj`, agent bootstrap file, integration test

1. During agent bootstrap, `proxy-ns!` creates `seon.db` proxy with `transact!`
2. Reads remain direct (local conn, MVCC safe)
3. Integration test: agent writes through proxy, data visible

### Phase 3: Full Proxy Bootstrap (2 hours, 4 files)

**Goal**: Agent JVMs auto-discover and proxy all registered namespaces.

1. Query knowledge graph for public functions per namespace
2. Auto-generate proxy metadata from graph
3. Agent gets all proxies at boot

### Phase 4: Separate-JVM Writer (3 hours, 4 files)

**Goal**: Writer in pool JVM, killable/restartable.

1. Writer JVM runs loop: TCP -> d/transact! -> TCP
2. Orchestrator writer-step becomes TCP forwarder (like namespace-step)
3. Pending write buffer for replay on recovery
4. Kill -> acquire new JVM -> replay pending

### Phase 5: Observability (1 hour, 3 files)

**Goal**: All flow activity visible through ping + observatory.

1. Writer state includes stats (total writes, errors, last write, pending)
2. `flow/ping` returns all process states
3. Health endpoint and observatory page show flow topology

---

## 8. The Flow Monitor

Rich Hickey's flow includes a separate project `core.async.flow-monitor` for visualization. It uses `datafy` (flow implements `Datafiable`) to get a static view and `ping` for live state. The monitor renders:

- Processes as nodes
- Channels as edges (with buffer state from ping)
- Process status (paused/running/stopped)
- Message counts per process
- Errors

We should integrate with or learn from this for the observatory page. The `datafy` output of our topology gives us the full graph structure. `ping` gives us live state. These are the same primitives the monitor uses.

---

## 9. Redundant Systems to Remove

Once the unified flow is the routing backbone, these standalone mechanisms become redundant:

| Current System | Replacement | Files |
|---------------|-------------|-------|
| `writer/create-writer-flow` (standalone per-conn flow) | Writer process in infrastructure flow | `db/datalevin/writer.clj` |
| `seon.db/writers` atom + `write-stats` atom | Writer step-fn state via `flow/ping` | `db.clj` |
| `seon.db/transact!` future+timeout | `topology/request!` → writer process | `db.clj` |
| `pause-writes!` / `resume-writes!` | `flow/pause-proc` / `flow/resume-proc` | `db.clj` |
| `pool/nrepl-eval!` direct calls | `topology/request!` → `:seon/repl` process | `repl/super.clj` |
| Various promise atoms per subsystem | Single `pending-promises` in topology | `topology.clj` |

**Rule**: No fallbacks. If the topology isn't running, throw. Fallback code paths are untested code paths.

---

## 10. Summary: The Architecture in One Paragraph

Seon uses Rich Hickey's core.async.flow as its backbone. A single flow topology contains one process per agent namespace (routing function calls to agent JVMs via TCP), one writer process (handling all database writes), and shared infrastructure processes (reply-router, error-sink, event-sink). Every cross-boundary operation -- namespace calls, database writes, future extensions -- uses the same pattern: register a promise, inject a message, step-fn handles it, reply flows to router, router delivers promise. The step-fn is the only thing that varies. Five primitives (process, connection, flow, inject, ping) compose to handle all cases. The writer is killable and restartable because it is just another process in the flow.
