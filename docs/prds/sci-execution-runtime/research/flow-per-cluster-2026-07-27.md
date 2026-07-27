---
type: research
status: complete
tags: [research, runtime, architecture]
---

# One core.async Flow graph per cluster

## Verdict

Use **one independent `core.async.flow` graph per cluster**, not one
process-global graph containing per-cluster procs. A Flow graph is the library's
unit of topology, channels, lifecycle, diagnostics, and observation; the
process executors are a separate concern and may be shared by every cluster
graph in the JVM
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:18-34`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:50-91`).

For clusters `a` and `b` in one JVM, the root Integrant system should own the
shared `:io` and bounded `:compute` executors, while nested system `a` owns Flow
graph `a` and nested system `b` owns Flow graph `b`. Halting nested system `a`
then stops only graph `a`; Flow itself never shuts down a supplied executor, so
the shared executors and graph `b` remain live
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-106`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:145-183`;
`reference-code/integrant/src/integrant/core.cljc:650-666`).

This extends rather than re-derives the adopted Path A and testbed results. The
prior API audit settled the real Flow graph/SPI and unmodified Flow Monitor
contract
(`docs/prds/sci-execution-runtime/research/flow-api-adoption-2026-07-26.md:9-35`),
and the testbed already proved that stopping graph A and even stopping A's
supplied compute executor leaves graph B running
(`docs/prds/sci-execution-runtime/research/flow-testbed-2026-07-26.md:214-250`;
`test/seon/flow_test.clj:1062-1087`). This document settles the B0/N3
granularity that follows from those results.

## Source coordinates

The selected source is the checked-out Flow alpha3 tag
`v1.10.874-alpha3`, commit
`dc35f3e0d7bc2eef502e77982f48641f025c8051`
(`docs/prds/sci-execution-runtime/research/flow-testbed-2026-07-26.md:142-161`).
All `flow.clj`, `flow/spi.clj`, and `flow/impl.clj` citations below refer to
`reference-code/core.async/src/main/clojure/clojure/core/async/`.

## What one Flow owns

### `create-flow` is construction, not execution

`create-flow` accepts one graph definition: `:procs`, `:conns`, and optional
`:mixed-exec`, `:io-exec`, and `:compute-exec`; its public contract explicitly
says the returned flow is not started
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-106`).
The implementation allocates one graph-local lock and channel-holder atom,
records the three optional executor references, calls `describe` for every proc,
validates every connection, and closes over the resulting proc descriptions,
port options, and connection map
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:38-70`).

Consequently, `create-flow` starts no proc loop, creates no graph channel, and
creates no executor pool; its marginal runtime state is the graph object,
lock/atom, derived topology maps, and closures
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:50-93`).
It does execute user-supplied `ProcLauncher/describe` during topology
preparation, so a throwing or effectful `describe` fails construction rather
than becoming a running-flow error
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:38-48`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:60-70`).

### `start` allocates the graph-local runtime

The first `start` of a graph allocates:

- one fixed-buffer-10 control channel and its `mult`;
- one `(sliding-buffer 100)` report channel and one
  `(sliding-buffer 100)` error channel;
- one channel per declared input;
- either no output channel, a direct reference to the sole consumer's input
  channel, or a separate output channel plus `mult` for fan-out/self-connection;
- one `(sliding-buffer 100)` broadcast-signal channel per proc; and
- one fixed-buffer-10 control tap per proc
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:94-141`;
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:149-172`).

Every proc launcher is then started with that graph's input, output, control,
error, and report channels plus a resolver for that graph's channels and
executors
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:145-167`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:72-86`).
An ordinary `flow/process` submits one long-lived proc loop to its selected
executor; a `:compute` proc runs that loop on `:io` and submits each transform
separately to `:compute`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-270`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:272-286`).

The cost of a started flow is therefore linear in its procs, declared ports,
fan-out connections, and buffered capacities. There is no fixed per-flow
executor-pool cost, but there is one long-lived proc activity plus control and
signal channels per proc, so “N flows are cheap” means N small graphs have
linear channel/proc-loop cost—not zero cost and not a claim about unmeasured
thousands of graphs
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:18-34`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:99-167`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:256-270`).

### Executors are not inherently per-flow

The graph stores only executor references supplied in its config. Resolver
lookup returns a supplied executor when present and otherwise calls
core.async's `executor-for`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:52-57`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:145-148`).
`executor-for` is memoized by workload for the JVM, so absent overrides all
flows use the same default `:mixed`, `:io`, and `:compute` executor objects
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:91-111`).

Flow never constructs a pool merely because a graph was created, and
`flow/stop` never closes or shuts down any executor
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:50-57`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174-183`).
Seon can therefore pass the same root-owned bounded `:compute` executor and
root-owned `:io` executor into every per-cluster graph without merging their
lifecycle or channels
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-103`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:145-162`).

The default `:compute` executor is a cached thread pool, not Seon's required
bounded platform executor, while the default `:io` executor starts a virtual
thread per task when the JVM supports it
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-96`;
`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:98-111`).
The root system should therefore supply the bounded `:compute` executor
explicitly; sharing Flow's defaults is source behavior, but it is not sufficient
capacity policy for the nucleus
(`docs/seon/architecture/architecture.md:371-386`;
`docs/prds/sci-execution-runtime/plan/README.md:718-726`).

## Lifecycle and reset behavior

### Start, pause, and resume

The standard proc loop begins in `:paused`, and `start` returns the graph's
report/error channels after all proc launchers have been called
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:149-172`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:270-286`).
This permits cluster startup to attach the error/report fan-out and Flow Monitor
view before calling `resume`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:108-121`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:184-189`).

Whole-flow `pause` and `resume` broadcast one addressed command to all procs;
`pause-proc` and `resume-proc` send the same command to one pid
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:71-86`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:184-189`).
The standard loop gives control priority at its next channel selection and
during output delivery, but neither graph-level nor proc-level pause preempts a
currently executing transform
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:219-241`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:281-316`).

Pause preserves the same channel objects and buffered contents because it only
changes proc status; resuming allows the proc to continue reading the existing
inputs
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:199-217`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:281-300`).
The existing testbed observed this exact boundary: pause took effect after the
active transform and resume preserved all queued ordinals
(`docs/prds/sci-execution-runtime/research/flow-testbed-2026-07-26.md:214-250`).

### Stop is graph-local command delivery, not a join

`stop` locks only that graph, broadcasts `::flow/stop` on that graph's control
channel, closes only that graph's error/report channels, resets only that
graph's channel-holder atom to `nil`, and returns
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:70-75`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174-183`).
All of those values are closed over by one `create-flow` invocation, so stopping
graph `a` has no path to graph `b`'s lock, channel atom, control channel, or
diagnostic channels
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:50-93`).

The `ProcLauncher/start` return value is explicitly ignored, and graph `stop`
does not retain or join a proc thread
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:72-86`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:149-183`).
The SPI instead requires each launched proc to notice `::flow/stop`, clean up
its own resources, and exit ordinarily
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:24-58`).
Any B0 reset contract that must know a run-loop interest, render registration,
or other resource has actually stopped therefore needs a proc-owned completion
event; `flow/stop` returning `true` is not that evidence
(`docs/prds/sci-execution-runtime/research/flow-testbed-2026-07-26.md:91-125`;
`src/seon/flow.clj:962-1020`).

### In-flight channel contents on stop

Flow does not drain or migrate input/output contents during stop, and it closes
only report/error channels before forgetting the runtime channel map
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174-183`).
A proc already in a transform can finish before it next observes control, while
a proc blocked while sending output gives control priority and may abandon the
remaining output sequence after observing stop
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:219-241`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:295-322`).

A later `start` allocates a new control channel, new diagnostic channels, new
port channels, new signal channels, and new proc loops; old queued values are
not copied into the new runtime
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:94-172`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:17-22`).
Flow channel values must therefore remain disposable wake/submission/render
values, with cluster reset rebuilding work from database facts rather than
expecting channel drain or replay
(`docs/seon/architecture/architecture.md:300-320`;
`docs/seon/architecture/architecture.md:381-386`).

## The proc contract

### Four step-function arities

`flow/process` adapts one four-arity step function: `()` describes the proc,
`(args)` initializes state, `(state transition)` handles
`::flow/resume`/`::flow/pause`/`::flow/stop`, and
`(state input-id message)` returns `[new-state output-map]`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:165-175`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:211-261`).
The graph calls `describe` while preparing static topology, calls `init` once
per start, and threads the returned state through later transitions and
transforms
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:38-69`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-271`).

Graph-declared `:ins` are descriptions from which Flow creates and resolves
channels. By contrast, `::flow/in-ports` and `::flow/out-ports` are actual
channel objects returned in initial state; Flow adds them to that proc's local
read/write sets, but they are not graph-visible or resolver-visible ports
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:197-226`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:263-269`).
That is the source-supported seam for a run-loop proc to select a
database-interest wake channel and for a fault-committer proc to read a
pre-created fault tap while still obeying Flow control
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:219-232`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:24-58`).

`pause-proc` and `resume-proc` are therefore operational controls over one
long-lived proc, not a way to destroy and recreate one cluster's subgraph
inside a global graph
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:144-155`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:184-189`).
The proc and connection inventory is fixed in the values closed over by
`create-flow`; the public API exposes no add/remove-proc operation
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-106`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:38-70`).

### Throwing step functions

A throwing `describe` fails `create-flow`, and a throwing `init` fails
`start`; the latter causes Flow to broadcast stop to already launched procs and
rethrow synchronously
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:38-48`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:149-167`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:256-265`).
Those startup failures are not ordinary messages on the running flow's
`error-chan`
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:149-167`).

After startup, a throwing transform is caught, reported to `::flow/error` with
pid, status, the pre-step state, count, input id/message, operation, and
exception, and the loop continues with the old state, old count, and old input
set
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:301-316`).
A transition or wider proc-loop exception is caught by the outer handler,
reported with pid/status/pre-step state/count, and likewise resumes from those
pre-step values
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:281-320`).

This recovery is only local in-memory rollback: a database transaction
committed before a later throw remains committed, so Seon's transaction fences
and receipts—not the proc's retained state—remain authoritative
(`docs/prds/sci-execution-runtime/research/flow-api-adoption-2026-07-26.md:280-331`).
Agent failures remain returned flat values and never throw into these handlers;
only an escaping core exception belongs on Flow's error path
(`docs/prds/sci-execution-runtime/plan/README.md:178-196`;
`docs/prds/sci-execution-runtime/research/flow-testbed-2026-07-26.md:239-251`).

A custom `ProcLauncher` must implement the same two logical statuses,
control-priority channel operations, addressed lifecycle commands, error
reporting, and attempt-to-continue rule itself
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:24-58`).
The custom launcher is the right N3 shape when the proc must select Flow control
and a database-interest channel, because the launcher may create a fresh proc
loop on every start while retaining no launched-process handle itself
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:11-22`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:72-95`).

## Error, report, and Flow Monitor topology

### Diagnostic channels are per-flow and lossy

Each successful first `start` creates fresh report/error channels inside that
graph's channel atom; a repeated `start` while running returns those same
channels with `:already-running true`, while a start after stop creates new
ones
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:94-102`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:168-183`).
They are therefore per-flow, not process-global
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:50-57`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:87-102`).

Both use `sliding-buffer 100`; at capacity, `SlidingBuffer.add!*` removes the
oldest buffered value before adding the new one
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:99-102`;
`reference-code/core.async/src/main/clojure/clojure/core/async/impl/buffers.clj:60-81`).
Overflow is silent transport loss, so neither channel is a durable fault record
(`docs/prds/sci-execution-runtime/plan/README.md:178-188`;
`docs/prds/sci-execution-runtime/research/flow-testbed-2026-07-26.md:91-125`).

### Flow Monitor observes one graph

Flow Monitor's server state contains one `:flow`; it pings that graph, datafies
that graph, extracts that graph's report/error channel objects, and consumes
those two channels directly
(`reference-code/core.async.flow-monitor/src/clojure/core/async/flow_monitor.clj:70-98`;
`reference-code/core.async.flow-monitor/src/clojure/core/async/flow_monitor.clj:121-156`).
Passing the source channels directly would make the monitor compete with the
fault committer and application report consumer, so Seon's existing testbed
correctly places one `mult` owner on each source and gives Flow Monitor a
datafiable proxy graph whose channel metadata points at independent taps
(`src/seon/flow.clj:605-680`;
`docs/prds/sci-execution-runtime/research/flow-testbed-2026-07-26.md:91-125`).

With one graph per cluster, cluster `a` naturally has its own error/report
mults, bounded fault tap, monitor taps, and Flow Monitor graph view, while
cluster `b` has independent instances of the same topology
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:87-102`;
`src/seon/flow.clj:629-680`).
The fault tap's counted overflow path remains necessary because Flow's source
sliding buffer and core.async's released dropping buffer provide no durable
drop callback
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/buffers.clj:38-58`;
`src/seon/flow.clj:537-565`;
`docs/prds/sci-execution-runtime/research/flow-testbed-2026-07-26.md:91-125`).

## Why not one global graph?

| Concern | One flow per cluster | One global flow with per-cluster procs | Source |
|---|---|---|---|
| Cluster reset | `flow/stop` targets exactly the cluster-owned graph-local lock and channels. | Flow has no stop-subgraph or remove-proc operation; proc pause does not tear down or recreate that cluster's channels. | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:123-155`; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174-189` |
| Static topology | Each cluster's long-lived owners are closed over once and recreated with that cluster. | Adding, removing, or rebuilding one cluster requires replacing the whole graph definition and therefore every cluster's runtime channels. | `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:38-70,94-172` |
| Diagnostics | Each cluster gets its own report/error transport, fault tap, and monitor view. | One pair of diagnostic channels merges all clusters before Seon can apply the cluster reset/ops boundary. | `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:87-102`; `src/seon/flow.clj:605-680` |
| Executors | Graphs can share the same explicitly supplied root executor objects. | A global graph buys no additional executor sharing because executor resolution is already independent of graph identity. | `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:145-148`; `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:98-111` |
| Integrant halt | One nested system contains one graph and its cluster resources; root-owned executors are outside that halt. | Flat cross-cluster refs/refsets participate in one dependency graph and make reverse-order halt traverse a shared topology. | `reference-code/integrant/src/integrant/core.cljc:84-103,177-215,650-666` |

A global graph can address `pause-proc` to the `a` procs, but that is an
operational throttle, not B0 reset isolation: their channels, taps, proc
launchers, and graph definition remain members of the same running graph
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:144-155`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:38-70`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:184-189`).
Because executors can already be shared independently, the global graph has no
capacity-sharing advantage to compensate for that lifecycle coupling
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:101-103`;
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:145-148`;
`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:98-111`).

## B0 contract implications

- One `bin/seon` process starting clusters `a` and `b` creates **two Flow
  graphs**: graph `a` in nested Integrant system `a`, and graph `b` in nested
  Integrant system `b`; it does not create one process-global Flow graph
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:50-93`;
  `reference-code/integrant/src/integrant/core.cljc:650-666`).

- Each cluster graph contains long-lived cluster owners, not one proc per agent
  or run: at N3/N4 the minimum topology is one run-loop proc, one render proc,
  and one fault-committer proc. The run-loop uses a custom
  `flow.spi/ProcLauncher` when it must select the cluster's database-interest
  wake alongside Flow control; the render and fault committer may use ordinary
  `flow/process` launchers and external `::flow/in-ports`
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:11-58`;
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:219-261`;
  `docs/prds/sci-execution-runtime/plan/README.md:633-652`).

- Flow Monitor is **not a proc** in either graph. Each cluster owns one monitor
  attachment/view over its graph plus independent report/error taps; the source
  error channel has one `mult` fan-out owner feeding that cluster's bounded
  fault tap and monitor tap
  (`reference-code/core.async.flow-monitor/src/clojure/core/async/flow_monitor.clj:121-156`;
  `src/seon/flow.clj:605-680`).

- The process root creates **one shared bounded platform `:compute` executor**
  and **one shared `:io` executor** and passes the same objects to both graph
  definitions. Flow permits supplied executors per graph and otherwise shares
  JVM-memoized defaults; separate graphs do not imply separate pools
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-103`;
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:145-148`;
  `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:91-111`).

- A nested cluster halt calls `flow/stop` only on that cluster's graph, then
  awaits explicit stopped events from resource-owning custom procs before
  closing the cluster database connection or completing reset. It never shuts
  down the root-owned executors, and it never relies on `flow/stop` returning
  as proof that proc threads have exited
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:174-183`;
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:43-58`;
  `reference-code/core.async/src/main/clojure/clojure/core/async/flow/spi.clj:72-86`).

- Cluster-local Flow channels carry only disposable wakes, submissions,
  reports, errors, and render values. Reset may discard their buffered
  contents; the replacement cluster graph derives current work from cluster
  database facts and receipts
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:94-183`;
  `docs/seon/architecture/architecture.md:300-320`;
  `docs/seon/architecture/architecture.md:381-386`).

- Proc ids need only be unique within their cluster graph, while every
  process-level registration, monitor endpoint, stopped event, and fault fact
  carries the cluster name together with pid and process start instant. Flow's
  proc inventory is graph-local; B0 supplies the cluster-qualified process
  identity and path boundary
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:38-69`;
  `docs/prds/sci-execution-runtime/plan/README.md:532-555`).

- A flat root Integrant `refset` that connects all cluster flows to shared
  resources is an acceptance failure. Shared executors remain root-owned values
  passed into each nested system, while each nested system's dependency graph
  contains only that cluster's Flow, database, monitor, and runtime resources
  (`reference-code/integrant/src/integrant/core.cljc:84-103`;
  `reference-code/integrant/src/integrant/core.cljc:177-215`;
  `reference-code/integrant/src/integrant/core.cljc:650-666`).
