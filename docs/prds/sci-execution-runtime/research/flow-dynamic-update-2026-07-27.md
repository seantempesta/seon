---
type: research
status: complete
tags: [research, runtime, architecture]
---

# Dynamic updates to a running core.async Flow system

## Verdict

The owner hypothesis is half right.

- Re-executing a step function's `defn` updates a running proc on its next
  invocation **when the launcher was constructed with the Var**
  (`#'my-step`). Flow explicitly recommends this for hot code reload
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:165-181`).
- Re-executing a graph-definition form does **not** add a proc, change a
  connection, or replace a buffer in an existing graph. `create-flow` derives
  and closes over the complete proc descriptions, channel coordinates,
  connection map, and executors once
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:38-70`).
- Flow has lifecycle and diagnostic mutation, not topology mutation:
  start/stop, whole-graph and per-proc pause/resume, ping, and injection
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj:76-163`).
- A topology update is therefore an explicit Seon reconciliation:
  compare old and new graph-definition data, stop the affected graph, call
  `create-flow` with the new data, call `start`, then call `resume`. Flow starts
  every proc paused (`flow.clj:105-121`).

On this machine, a three-proc `create-flow → start → resume → ping-ready → stop`
API round trip had a 0.343 ms median and 1.024 ms p95 over 50 measured samples.
The low cost supports rebuilding topology instead of inventing mutable graph
machinery. The measurement is an API/readiness measurement, not an exit join:
Flow's `stop` does not await proc termination
(`flow/impl.clj:174-183`).

## Dependency ledger and experiment harness

| dependency or mechanism | selected source | evidence |
|---|---|---|
| core.async Flow | `org.clojure/core.async 1.10.874-alpha3` | `deps.edn:13-17`; vendored tag commit `dc35f3e0d7bc2eef502e77982f48641f025c8051` |
| Flow graph implementation | alpha3 `flow.clj`, `flow/impl.clj`, `flow/spi.clj` | `reference-code/core.async/src/main/clojure/clojure/core/async/` |
| Seon Flow testbed | `seon.flow` launchers over the real Flow API/SPI | `src/seon/flow.clj:1-16,231-249,332-406,441-505,583-713,752-1036` |
| Standing scenario harness | in-memory Datahike plus real `create-flow`, `start`, `resume`, and `inject` | `test/seon/flow/loop_test.clj:121-134,422-448,532-569` |

The repository pins the same alpha3 commit that was read:

```text
$ git -C reference-code/core.async rev-parse HEAD
dc35f3e0d7bc2eef502e77982f48641f025c8051
```

The two repeatable probes are project-local and intentionally ignored by Git:

- `tmp/flow-dynamics/control_and_reload.clj`
- `tmp/flow-dynamics/rebuild_and_durability.clj`

They run on the requested source/test basis:

```sh
clojure -M:test tmp/flow-dynamics/control_and_reload.clj
clojure -M:test tmp/flow-dynamics/rebuild_and_durability.clj
```

The standing testbed remained green after the probes:

```text
$ bin/test seon.flow.loop-test

Testing seon.flow.loop-test

Ran 4 tests containing 31 assertions.
0 failures, 0 errors.
```

## 1. Runtime mutation supplied by Flow

### The complete public control surface

| call | what the implementation does |
|---|---|
| `start` | Under a graph lock, returns the existing report/error channels plus `:already-running true` if already running. Otherwise it creates fresh control, report, error, input, output, cast, and mult channels; starts every proc from the captured proc descriptions; records the running-channel map; and returns report/error channels. Procs start paused (`flow/impl.clj:93-173`; `flow/spi.clj:24-34`). |
| `stop` | Sends `::flow/stop` to all procs, closes the report and error channels, clears the graph's running-channel reference, and returns `true`; a stopped graph can be started again from its original captured definition. It does not join proc threads (`flow.clj:123-126`; `flow/impl.clj:174-183`). |
| `pause` / `resume` | Blocking-put a control command addressed to `::flow/all` on the shared control channel. Each proc's control tap receives the command; the standard proc changes logical status and calls the transition arity when status changes. The call confirms command enqueue, not completion by every proc (`flow/impl.clj:70-75,184-185,199-217,281-300`). |
| `pause-proc` / `resume-proc` | Put the same commands with one pid as `::flow/to`; every proc sees the multicast control value, but only the addressed proc changes status (`flow.clj:144-150`; `flow/impl.clj:187-189,199-217`). |
| `ping` | Creates a private reply channel, sends `::flow/ping` to all procs, and collects a `pid → ping` map until every expected response arrives or the timeout fires. Missing/blocked procs are omitted (`flow.clj:136-142`; `flow/impl.clj:76-86,186,270-280`). |
| `ping-proc` | Uses the same mechanism for one pid and returns its ping map or nil after timeout (`flow.clj:152-155`; `flow/impl.clj:76-86,189`). |
| `inject` | Resolves an existing input/output coordinate, or a broadcast signal coordinate `[::flow/cast signal-id]`, then schedules blocking puts on the `:io` executor. It returns a `Future` that completes after those puts complete (`flow.clj:157-163`; `flow/impl.clj:190-197`). |

Pause does not clear a proc's input buffers. The standard loop reads only the
control channel while paused, then includes inputs again after resume
(`flow/impl.clj:281-305`). The probe put one message while `:alpha` was paused:
its count stayed at one, and the queued message ran after `resume-proc`.
Broadcast injection reached both procs.

```text
$ clojure -M:test tmp/flow-dynamics/control_and_reload.clj
=== control surface ===
start: {:started-paused {...alpha {:status :paused, :count 0},
                         ...beta  {:status :paused, :count 0}},
        :second-start {... :already-running true}}
resume-proc + inject: {...alpha {:status :running, :count 1},
                       ...beta  {:status :paused, :count 0}}
pause-proc leaves input queued: {...alpha {:status :paused, :count 1},
                                  ...beta  {:status :paused, :count 0}}
resume-proc drains queued input: {...message .../queued-while-paused}
inject cast: {...report-count 2,
              :reports [{...input .../tick, :message [.../broadcast]}
                        {...input .../tick, :message [.../broadcast]}]}
pause all: {...alpha {:status :paused, :count 3},
            ...beta  {:status :paused, :count 1}}
resume all: {...alpha {:status :running, :count 3},
             ...beta  {:status :running, :count 1}}
stop: true
restart same graph creates fresh proc state:
  {...alpha {:status :paused, :count 0},
   ...beta  {:status :paused, :count 0}}
```

### A running graph cannot gain a proc

`create-flow` calls `describe` for every configured launcher and constructs
`pdescs`, input/output option maps, and `conn-map` before returning the graph
(`flow/impl.clj:38-69`). `start` later constructs channels and starts only
those closed-over `pdescs` (`flow/impl.clj:99-171`). Neither the public API
(`flow.clj:76-163`) nor `ProcLauncher`'s two-method SPI
(`flow/spi.clj:11-22,60-86`) contains add/remove/reconnect.

The probe created a one-proc graph, evaluated new definition data containing a
second proc, then tried the new coordinate against the old graph. The existing
graph still exposed only `:alpha`; only a new `create-flow` saw `:beta`.

```text
=== fixed topology ===
existing graph data: (.../alpha)
inject newly-added definition coordinate into existing graph:
  {...exception-class clojure.lang.ExceptionInfo,
   :message "can't resolve channel with io-id"}
existing graph ping keys: #{.../alpha}
new graph ping keys: #{.../alpha .../beta}
```

The exact answer is therefore: **Flow cannot add a proc to a running graph.**
Stopping and starting the same graph restarts its original topology. Applying
updated topology requires a new graph value from `create-flow`.

## 2. Re-executing a step function

Flow's documentation states that a Var holding the step function is preferred
because it enables hot code reload (`flow.clj:165-181`). The implementation
explains the boundary:

- `proc` invokes the supplied step once at launcher construction to capture
  the description, including ins, outs, signal selection, workload, and
  `:ping-map-fn` (`flow/impl.clj:243-255`).
- At proc start it retains the supplied `step` value as the transform, or
  closes over it in the compute wrapper, and invokes it for initialization
  (`flow/impl.clj:256-263`).
- Every message invokes that retained callable
  (`flow/impl.clj:301-305`). A plain function value remains that function
  object. A Var remains a Var, so invocation observes its current root.

The probe started two live graphs before re-executing `reload-step`'s `defn`.
One launcher received the plain value `reload-step`; the other received
`#'reload-step`.

```text
=== plain fn versus Var ===
before defn re-execution:
  {...plain {...version .../v1, :message .../before},
   ...var   {...version .../v1, :message .../before}}
after defn re-execution:
  {...plain {...version .../v1, :message .../after},
   ...var   {...version .../v2, :message .../after}}
```

Thus a Var-backed transform body edit needs no pause or restart: the next
message uses the new Var root and retains the proc's current state. Three
limits matter:

1. A plain function value, including the anonymous function returned by
   `flow/map->step`, does not become hot-reloadable merely because the `defn`
   that once constructed it is re-executed. A top-level helper Var called by
   that function may still reload normally, but the captured function body and
   captured arguments do not change.
2. The description was already captured when `flow/process` made the launcher.
   Editing inputs, outputs, signal selection, workload, or `:ping-map-fn`
   requires a newly constructed launcher and graph
   (`flow/impl.clj:243-255`).
3. Initialization runs only when a proc starts. A new init body cannot reshape
   already-running state. Even a transform-only edit is safe without rebuild
   only if it accepts the existing state shape.

## 3. Stop and recreate

Each `start` creates fresh graph channels from the captured descriptions and
connection map (`flow/impl.clj:99-141`). `stop` clears the graph's reference to
that channel set (`flow/impl.clj:174-183`). Channel contents are not copied
into a subsequent start.

The Datahike probe committed one durable pending job, injected one wake into a
paused graph, and confirmed the old channel buffer held one value. It then
stopped the graph and created a new one over the same in-memory database
connection. The new channel was empty and the new proc count was zero; the
database job was still pending. Querying that pending fact and injecting a new
wake processed it exactly once and committed `:done`.

```text
$ clojure -M:test tmp/flow-dynamics/rebuild_and_durability.clj
=== disposable channels, durable database facts ===
old graph before stop:
  {...queued-wake .../old-wake,
   ...ping {...status :paused, ...count 0,
            ...buffer {:type FixedBuffer, :count 1, :capacity 4}},
   ...database-status .../pending}
after stop:
  {...database-status .../pending, ...pending-job-ids #{"job-1"}}
new graph before re-drive:
  {...ping {...status :running, ...count 0,
            ...buffer {:type FixedBuffer, :count 0, :capacity 4}},
   ...database-status .../pending}
after database-derived re-drive:
  {...rederived-job-ids #{"job-1"},
   ...report {...event .../database-rederived,
              ...pending-before #{"job-1"}},
   ...database-status .../done,
   ...ping {...status :running, ...count 1}}
```

This proves the intended division, not magical preservation: no wake moved
between graphs; the durable fact survived, and the new wake was derived from
that fact. Flow state and channel values are disposable. Database facts and
effect/run receipts must make re-driving safe.

### Rebuild timing

The same probe warmed five rounds, then measured 50 three-proc rounds on
OpenJDK 26.0.1 with 18 available processors. Every sample called `ping` after
resume and required replies from all three procs, so “ready” means the new
procs were live and responsive. Times are milliseconds:

```text
=== three-proc graph timing ===
{...java-version "26.0.1",
 ...available-processors 18,
 ...sample-count 50,
 ...summary
 {...create-ms     {...median-ms 0.025709, :p95-ms 0.064125},
  ...start-ms      {...median-ms 0.051541, :p95-ms 0.121833},
  ...resume-ms     {...median-ms 0.009042, :p95-ms 0.031625},
  ...ping-ready-ms {...median-ms 0.214334, :p95-ms 0.751917},
  ...stop-ms       {...median-ms 0.012000, :p95-ms 0.041292},
  ...round-trip-ms {...median-ms 0.342833, :p95-ms 1.023500}}}
```

The important caveat is source-grounded: `stop` sends the stop command, closes
diagnostic channels, clears its running map, and returns without a join
(`flow/impl.clj:174-183`). Where simultaneous old/new activity could violate a
durable fence, Seon must wait on an observable custom-proc stopped event or
rely on the same durable operation identity and database fences that make a
process crash safe. A tuned sleep is not a lifecycle contract.

## 4. The precise Seon update pattern

The graph definition is ordinary data. Eventually Seon derives that data from
database facts; Flow consumes a concrete snapshot of it at `create-flow`.
Evaluating a changed definition does not itself mutate a graph. A Seon
reconciler must compare the old and new definitions and perform the required
action explicitly.

| change | required action |
|---|---|
| Transform body edit, launcher built with `flow/process #'step` | No restart. Re-execute the `defn`; the next invocation observes the new Var root. Preserve the existing state only when the new body accepts its shape. Proven by the Var transcript above and specified at `flow.clj:177-181`. |
| Transform body edit, launcher built with a plain fn or `flow/map->step` result | Rebuild the affected graph, unless the edit is only to a separately referenced helper Var. The plain-reference transcript stayed on v1. |
| Init body or init arguments | Restart the proc. For updated args, construct a new graph because args are captured in `pdescs` (`flow/impl.clj:38-48,150-162,256-264`). |
| Describe result, workload, signal selection, or ping projection | Construct a new launcher and graph. Description is captured by `flow/process` before graph construction (`flow/impl.clj:243-255`). |
| New or removed proc | Stop the affected graph, `create-flow` from updated data, `start`, then `resume`. No add/remove operation exists; the fixed-topology transcript proves it. |
| Changed connection | Rebuild. `conn-map` is derived once during `create-flow` and drives mult/direct channel wiring during `start` (`flow/impl.clj:63-69,113-141`). |
| Changed buffer size or transducer | Rebuild. Channel options are captured in the proc descriptions, and `start` creates channels from those captured options (`flow/impl.clj:38-48,58-61,101-124`). Stopping and starting the same graph would recreate the old sizes. |
| Changed executor | Rebuild. The executor map is captured by `create-flow`; the resolver closes over it (`flow/impl.clj:52-58,145-148`). |

“Execute the forms against the running system” therefore has two meanings:

- for Var-backed behavior, execute the new `defn` and do nothing else;
- for graph-definition data, execute the definition **and then explicitly
  reconcile it**. Flow does not watch Vars containing graph data, compute a
  diff, or restart affected procs.

The honest topology sequence is:

```clojure
(flow/stop old-graph)
(let [new-graph (flow/create-flow new-graph-definition)]
  (flow/start new-graph)  ; all procs are paused
  (flow/resume new-graph)
  new-graph)
```

If exit completion matters, insert an event-driven old-proc completion/fence
between `stop` and `create-flow`; `flow/stop` alone is not a join.

## 5. What `seon.flow` duplicates

### What already uses Flow properly

The ordinary Seon launchers use Flow's standard `process` and `map->step`
surface rather than rebuilding process loops:

- capacity observer (`src/seon/flow.clj:231-249`);
- fault committer (`src/seon/flow.clj:583-619`);
- planner and namespace owner (`src/seon/flow.clj:752-793`);
- source enumerator, indexer, and eval (`src/seon/flow.clj:795-929`);
- mailbox (`src/seon/flow.clj:931-946`).

Graph ownership also calls the real `create-flow`, `start`, `resume`, and
`stop` (`src/seon/flow.clj:441-483,645-713`). Shutting down the
Seon-created compute executor after `flow/stop` is not duplicated Flow
behavior: Flow accepts supplied executors but does not own or shut them down
(`flow/impl.clj:52-58,145-148,174-183`).

### Duplicated responsibilities to keep explicit

There are three deliberate wrappers/custom implementations, and two affect the
update contract:

1. **`work-launcher-proc` reimplements the ProcLauncher control loop.**
   It implements addressed stop/pause/resume/ping, count/status tracking,
   error/report delivery, and executor dispatch itself
   (`src/seon/flow.clj:251-406`). Those lifecycle parts duplicate the standard
   proc implementation at `flow/impl.clj:199-217,270-322`. Its distinct
   requirement is concurrent submissions up to `parallelism`; Flow's standard
   `:compute` process waits for each transform future before reading the next
   message (`flow.clj:278-284`; `flow/impl.clj:258-261,301-311`). That is a
   legitimate SPI reason, but the custom code must remain only this exceptional
   launcher, not become a second general process engine.
2. **`database-proc` reimplements the same control/ping/error loop.**
   `addressed?`, `ping-map`, `next-status`, and the custom channel loop repeat
   standard Flow responsibilities (`src/seon/flow.clj:948-1036` versus
   `flow/impl.clj:199-217,270-322`). Its distinct behavior is reading current
   database facts for ping and each wake, committing through supplied
   functions, and making the downstream wake an `offer!`
   (`src/seon/flow.clj:1011-1032`). Before B0 adopts it, this is the strongest
   simplification candidate: keep the custom SPI launcher only if current-fact
   ping plus nonblocking downstream delivery cannot be expressed through
   `flow/process` without changing the required semantics.
3. **`monitor-graph` delegates the entire Graph protocol.**
   It repeats every lifecycle/control method solely to replace the datafied
   report/error channels with independent monitor taps
   (`src/seon/flow.clj:621-643`). This is not another graph implementation, but
   it is a complete control-surface proxy. Its scope must remain the
   Flow-Monitor view.

`install-work-launcher!` is not rebuilding a feature Flow already has: Flow
has no topology reconciler. It is Seon's current replacement owner
(`src/seon/flow.clj:485-505`). However, it starts the new graph before swapping
and stopping the previous graph (`src/seon/flow.clj:488-497`). That creates a
brief two-graph overlap. B0 must either reverse that order and wait on the
required durable fence, or prove that shared durable operation identity makes
the overlap harmless. It must not accidentally treat `flow/stop` as a join.

Finally, the current ordinary launchers all pass anonymous
`flow/map->step` results to `flow/process`, not step Vars. The live plain-versus-
Var transcript therefore says that re-executing their constructor `defn`s does
not replace an already-running transform closure. Today those body/topology
edits require graph rebuild. If B0 wants body-only hot reload, its graph data
must carry Var-backed step launchers deliberately.

The source audit that located the two exceptional ProcLaunchers and the
ordinary `flow/process` users was:

```text
$ rg -n "flow/process|flow.spi/ProcLauncher" src/seon/flow.clj
236:  (flow/process
339:    flow.spi/ProcLauncher
592:  (flow/process
757:  (flow/process
778:  (flow/process
800:  (flow/process
844:  (flow/process
869:  (flow/process
936:  (flow/process
988:    flow.spi/ProcLauncher
```

## The update pattern

The B0 contract should encode these rules in plain language:

1. A graph definition is data. `create-flow` consumes one snapshot; a running
   graph never watches or mutates that data.
2. Put stable step Vars in graph definitions. Re-executing a Var-backed
   transform changes the next message with no restart. Do not promise this for
   captured plain functions, initialization, descriptions, or state-shape
   changes.
3. A new/removed proc, connection, buffer, transducer, args, workload, or
   executor means a new graph. Diff old and new definitions only to identify
   the affected graph; do not invent add-proc or reconnect operations.
4. Rebuild by stopping/fencing the old graph, calling `create-flow`, calling
   `start`, and calling `resume`. `start` alone leaves every proc paused.
5. Nothing in a Flow channel is durable or migrated. After rebuild, derive
   wakes and work again from database facts. Durable operation identities,
   receipts, and database fences make that re-drive safe.
6. `flow/stop` is a command, not a join. When overlap matters, observe proc
   completion or the durable fence; never sleep and assume exit.
7. Use `flow/process` for ordinary processes. A custom `ProcLauncher` may
   duplicate Flow's mandated lifecycle only when the standard process cannot
   express one measured requirement, and it must still use Flow's channels,
   resolver, executors, report channel, and error channel.
