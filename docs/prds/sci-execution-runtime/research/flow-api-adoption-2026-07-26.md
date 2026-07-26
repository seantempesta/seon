---
type: research
status: active
tags: [agent, runtime, architecture, research, flow]
---

# Flow API adoption: use the SPI, do not copy the implementation

## Decision

Recommend **Path A: genuine `core.async.flow` adoption with zero forked Flow
files**.

Use the real Flow graph and public API. Represent each Seon runtime owner with
either:

- an ordinary `flow/process` whose threaded state is deliberately ephemeral and
  whose latest `:ping-map-fn` derives its report from database facts; or
- a custom `flow.spi/ProcLauncher` when the proc's real input is a database
  interest, lease instant, or another non-Flow source.

The database remains the durable coordination medium. Flow channels are
process-local control, submission, backpressure, report, and error channels.
The fact that Flow's loop retains an ephemeral handle and status is an
implementation detail; the fact that Seon's run, receipt, and process state is
committed is not.

Path B is not an honest same-API implementation. The public contract explicitly
allows arbitrary state, predicates, and channel objects to round-trip through
the step function, and the graph definition promises core.async channel
buffering, transducers, mult fan-out, injection, and signal behavior. Replacing
those internals with database facts either breaks the contract or recreates the
original channels beside the database. The unchanged flow-monitor also reaches
Flow's datafied channel objects, making the implementation shape a de facto
integration contract.

## Dependency ledger and source-version warning

| dependency | selected source | evidence and consequence |
|---|---|---|
| Seon's declared core.async | `org.clojure/core.async 1.10.870-alpha2`, tag commit `1dbbca209ec05a86c4b5a6f39645411e4c8a53fd` | `deps.edn:137-141`; this is the contract Seon would execute today |
| vendored core.async | `b871f3519de6843a9f5ce66cf8d5c6cbe44d3222`, after `1.9.829-alpha2` | It **does contain Flow**, so it does not predate the API, but it is not the declared runtime source. Compared with `1.10.870-alpha2`, the Flow files differ by 30 lines, chiefly `ExecutorService` → `Executor` and `futurize` changes. Tag-qualified citations below therefore use the exact release object, not the older checkout. |
| latest published Flow alpha found | `1.10.874-alpha3`, tag commit `dc35f3e0d7bc2eef502e77982f48641f025c8051` | It lives on core.async's `dev-flow-alpha` line and adds `:ping-map-fn`, the exact seam for database-derived ping state. Core.async `master` removed Flow in `c63dfee2e16121ae17a2a34d5f1c6a7f3add3a45` on 2026-03-19, while the separate Flow alpha line continued and published alpha3. Adoption therefore requires an explicit Flow-alpha pin, not an assumption that ordinary core.async `master` carries it. |
| flow-monitor | vendored at `421d56c3e9049c0f2e7eecafd801376a7843444f` | Its own dependency is the older `1.9.808-alpha1` (`reference-code/core.async.flow-monitor/deps.edn:1-4`), but its calls still match alpha3. |
| Datahike | `caf526850084a9d5846ccd9ea34251fe411e0d6b` | Its `:self` writer already has a transaction-processing channel and a batched commit channel per connection (`reference-code/datahike/src/datahike/writer.cljc:85-91,94-200,202-269,286-306`). |
| SCI | `8fac6e88f32d53a5fd82ebe80640881e317b84fd` | `:interrupt-fn` runs at every interpreted function and loop body entrance (`reference-code/sci/doc/interrupt.md:6-8,50-52`); `interrupt!` is uncatchable by evaluated code (`reference-code/sci/src/sci/interrupt.cljc:32-42`). |
| Seon runtime target | seven base constructs | Database value, transaction, plan fold, guarded eval, capability door, corpus, and derived view are the complete set (`docs/prds/sci-execution-runtime/plan/README.md:123-163`). |

The exact alpha2 Flow source can be inspected without moving the submodule:

```sh
git -C reference-code/core.async show \
  1dbbca209ec05a86c4b5a6f39645411e4c8a53fd:src/main/clojure/clojure/core/async/flow.clj

```

In citations such as
`reference-code/core.async@1dbbca2:src/main/.../flow.clj:76-106`, the suffix is
the line in that exact Git object. The checked-out older file has the same
public function line numbers, but its `impl.clj` is two lines shorter.

## Public API and data contracts

### Flow namespace signatures

A same-API implementation must preserve all fifteen public functions and their
return behavior:

| function | signature | contract |
|---|---|---|
| `create-flow` | `[config]` | Returns an unstarted graph (`flow.clj:76-106`). |
| `start` | `[g]` | Starts every proc paused; returns `{:report-chan ch :error-chan ch}` and may add `:already-running true` (`flow.clj:108-121`; `impl.clj:94-101,172-173`). |
| `stop` | `[g]` | Sends stop, closes report/error channels, permits a later restart (`flow.clj:123-126`; `impl.clj:174-183`). |
| `pause` | `[g]` | Asynchronously sends `::flow/pause` to all procs (`flow.clj:128-130`; `impl.clj:184`). |
| `resume` | `[g]` | Asynchronously sends `::flow/resume` to all procs (`flow.clj:132-134`; `impl.clj:185`). |
| `ping` | `[g & {:keys [timeout-ms] :or {timeout-ms 1000}}]` | Returns `pid -> ping-map` only for replies before the timeout (`flow.clj:136-140`; `impl.clj:76-86,186`). |
| `pause-proc` | `[g pid]` | Sends pause to one proc (`flow.clj:142-144`). |
| `resume-proc` | `[g pid]` | Sends resume to one proc (`flow.clj:146-148`). |
| `ping-proc` | `[g pid & {:keys [timeout-ms] :or {timeout-ms 1000}}]` | Returns one ping map or nil on timeout (`flow.clj:150-153`; `impl.clj:84-86,189`). |
| `inject` | `[g [pid io-id :as coord] msgs]` | Returns a future; the future completes after every message has been put on the addressed input/output channel, or after a broadcast cast (`flow.clj:155-161`; `impl.clj:190-197`). |
| `process` | `[step-fn]`, `[step-fn opts]` | Returns a `ProcLauncher`; opts are `:workload` and `:compute-timeout-ms` (`flow.clj:163-284`). |
| `map->step` | `[{:keys [describe init transition transform]}]` | Requires describe and transform and returns the four-arity step function (`flow.clj:286-304`). |
| `lift*->step` | `[f]` | One `:in`, one `:out`; `f` returns zero or more non-nil messages (`flow.clj:306-316`). |
| `lift1->step` | `[f]` | One `:in`, one `:out`; a nil result means no output (`flow.clj:318-327`). |
| `futurize` | `[f & {:keys [exec] :or {exec :mixed}}]` | Returns a function that immediately returns a `Future`; `exec` is `:mixed`, `:io`, `:compute`, or an `Executor` in alpha2+ (`flow.clj:329-341`; `impl.clj:29-36`). |

The line references in this table are against the exact alpha2 objects:
`reference-code/core.async@1dbbca2:src/main/clojure/clojure/core/async/flow.clj`
and `.../flow/impl.clj`.

The public wrappers dispatch through the internal
`clojure.core.async.flow.impl.graph/Graph` protocol. A replacement intended to
work with the existing wrappers or monitor must also implement:

| Graph method | signature |
|---|---|
| `start` | `[g]` |
| `stop` | `[g]` |
| `pause` | `[g]` |
| `resume` | `[g]` |
| `ping` | `[g timeout-ms]` |
| `pause-proc` | `[g pid]` |
| `resume-proc` | `[g pid]` |
| `ping-proc` | `[g pid timeout-ms]` |
| `command-proc` | `[g pid cmd-id more-kvs]` |
| `inject` | `[g [pid io-id] msgs]` |

These signatures are at
`reference-code/core.async@1dbbca2:src/main/clojure/clojure/core/async/flow/impl/graph.clj:11-28`.
There is a live alpha defect in that protocol surface: `command-proc` has no
public Flow wrapper and the built-in graph does not implement it. Calling it on
an alpha3 graph produced `AbstractMethodError` in this lane. A compatible Seon
integration should use only the nine methods exercised by the public wrappers;
Path B must not mistake the protocol declaration for a working extra feature.

### Graph definition

The accepted graph is:

```clojure
{:procs
 {pid
  {:proc proc-launcher
   :args {param value}
   :chan-opts
   {io-id {:buf-or-n buffer-or-size
           :xform transducer}}}}
 :conns
 [[[from-pid out-id] [to-pid in-id]] ...]
 :mixed-exec executor
 :io-exec executor
 :compute-exec executor}

```

The executor keys are optional. `:procs` and `:conns` are the required shape
documented by `create-flow`; each `:chan-opts` entry has core.async `chan`
semantics and defaults to a fixed buffer of ten
(`flow.clj:76-105`; `impl.clj:50-69,101-110`). A one-to-one connection can use
the input channel directly. Multiple consumers or a same-proc connection cause
Flow to create an output channel plus `mult` and `tap`, so every consumer gets
every message (`flow.clj:90-95`; `impl.clj:111-123,136-141`).

The proc set and topology are fixed when `create-flow` runs:
`prep-proc` calls `describe`, validates all coordinates, and closes over
`pdescs`, `inopts`, `outopts`, and `conn-map` (`impl.clj:38-69`). There is no
public add/remove-proc operation. Dynamic agent entities therefore must not be
silently presented as dynamic nodes in one Flow graph. Honest choices are a
long-lived cluster driver proc that schedules database-derived runs, or
separate small flows reconciled from durable run facts. Adding hidden dynamic
nodes would already be a different graph-def contract.

### Step function and report shape

The standard process step function is exactly:

```clojure
()                          ;; describe -> description
(args-with-::flow/pid)      ;; init -> initial-state
(state transition)          ;; transition -> state'
(state input-id message)    ;; transform -> [state' output-map]

```

The description may contain `:params`, `:ins`, `:outs`, `:signal-select`, and
`:workload` (`flow.clj:163-205`). Alpha3 adds `:ping-map-fn`, a
`state -> ping-map` function whose result is placed at `::flow/state`
(`reference-code/core.async@dc35f3e:src/main/clojure/clojure/core/async/flow.clj:136-141,181-195`;
`.../flow/impl.clj:243-278`). This is not cosmetic: it lets the state retain
only an ephemeral database handle while ping returns facts read at call time.

Init, transition, and transform state is otherwise an unrestricted Clojure
value. It may contain:

- `::flow/in-ports` and `::flow/out-ports`, whose values are actual core.async
  channels added to the proc's I/O set (`flow.clj:206-221`;
  `impl.clj:263-266`);
- `::flow/input-filter`, a predicate that changes the next read set
  (`flow.clj:223-227`; `impl.clj:288-295`); and
- any application value threaded to the next transition or transform
  (`flow.clj:229-256`; `impl.clj:271-321`).

The built-in ping reply is:

```clojure
#::flow{:pid pid
        :status :paused-or-running
        :state ping-state
        :count completed-transform-count
        :ins {input-id datafied-channel}
        :outs {output-id datafied-channel}}

```

Alpha2 emits a recursively datafied state
(`reference-code/core.async@1dbbca2:.../flow/impl.clj:270-278`).
Alpha3 leaves the `:ping-map-fn` result as returned while still datafying the
other fields (`reference-code/core.async@dc35f3e:.../flow/impl.clj:270-280`).

### Report and error channels

`start` creates the report and error channels with sliding buffers of 100
(`impl.clj:99-102`). Therefore they are bounded diagnostic streams: a slow
consumer loses older reports/errors in favor of newer ones. They are not a
durable error ledger.

The public docstring says ping replies appear on `:report-chan`
(`flow.clj:108-120`), but the implementation no longer does that. `ping`
creates a private reply channel, puts it in the control command, collects
replies, and returns them synchronously (`impl.clj:76-86`). The public report
channel receives explicit `::flow/report` outputs from a transform
(`flow.clj:240-248`; `impl.clj:160-162,219-241`). The implementation is the
truth for compatibility; a same-API replacement must preserve returned ping
maps and explicit report output, not revive the stale docstring behavior.

The intended error envelope contains at least `::flow/ex`
(`flow.clj:116-120`). Actual sites add context:

- a channel transducer failure sends
  `#::flow{:ex ex :pid pid :cid cid :xform xform}`
  (`impl.clj:103-110`);
- a transform failure sends pid, volatile status and pre-step state, count,
  input id, input message, `:op :step`, and exception, then continues from the
  old state (`impl.clj:300-319`);
- a wider proc-loop failure sends pid, status, state, count, and exception and
  also attempts to continue (`impl.clj:316-321`).

`start`-time launcher failures are different: Flow broadcasts stop and
rethrows synchronously (`impl.clj:149-167`). `stop` broadcasts stop and then
immediately closes report/error without joining proc threads
(`impl.clj:174-183`). A Seon proc must therefore commit core faults as facts
before best-effort reporting, and it cannot treat the Flow error channel as
the durable owner.

## What the SPI actually abstracts

`ProcLauncher` has only two protocol methods:

```clojure
(describe [launcher])
(start [launcher {:keys [pid args ins outs resolver]}])

```

The launcher must acquire no resources and retain no launched process; it must
be reusable, with each `start` creating a fresh proc
(`flow/spi.clj:11-22`). The launched proc must:

- start paused and expose only logical `:paused` and `:running` statuses;
- include the control channel with priority in every channel read/write
  `alts!!`;
- handle stop, pause, resume, and ping commands addressed to its pid or
  `::flow/all`;
- report errors on `::flow/error` and attempt to continue;
- avoid transmitting or closing graph-owned channels
  (`flow/spi.clj:24-58`).

`start` receives named input channels, output channels, and a `Resolver`.
`Resolver/get-write-chan` resolves `[pid io-id]`; `Resolver/get-exec` resolves
one of the three workload executors (`flow/spi.clj:60-95`). The actual
implementation also passes `:cast`, though the SPI doc does not promise it
(`impl.clj:149-162`), so a custom Seon launcher should not depend on that
undocumented key.

This is enough to put a database-backed proc behind unmodified Flow. The proc
loop can `alts!!` over Flow control and a process-local channel fed by a
database interest callback. Pause means stop claiming new durable work;
resume means resume that derivation; ping queries a current database value;
stop removes interests and releases process-local resources. Run ownership,
position, and completion remain the existing CAS/lease/receipt facts:

- claim and takeover are derived then committed with `:db.fn/cas`
  (`src/seon/agent/run/core.cljc:104-192`);
- release and finish are transactions
  (`src/seon/agent/run/core.cljc:194-221`);
- execution position is the first ordinal without a terminal receipt
  (`src/seon/eval/receipt.cljc:101-139`;
  `src/seon/agent/driver.clj:688-733`).

### Exact friction points

**The ordinary process retains state.** `flow/process` initializes once and
threads `[status state count read-ins]` forever
(`impl.clj:243-271,300-321`). A Seon step must return the same small ephemeral
runtime handle (or another disposable value) and read durable state from the
database. It must not pretend Flow has stopped retaining state.

**Alpha2 ping sees that retained state.** At Seon's declared alpha2 pin,
built-in ping datafies the loop's state. Alpha3's `:ping-map-fn` is the clean
fix. Without an upgrade, use a custom `ProcLauncher` whose ping branch queries
facts; do not fork `flow.impl`.

**Lifecycle is serialized on the proc loop.** Transition is called only when
the loop observes a control message (`impl.clj:199-217,282-299`). Pause/resume
cannot preempt a transform. They are scheduler-local commands; durable release
or reacquisition still needs Seon's CAS transactions.

**Stop has no completion handshake.** `ProcLauncher/start`'s return is ignored,
the graph retains no process handle, and graph stop closes diagnostics
immediately after broadcasting (`flow/spi.clj:72-86`;
`impl.clj:149-183`). Any resource whose shutdown must be observed needs its own
fact/completion and a cluster lifecycle owner outside Flow's `stop` return.

**Transform commit and channel output are not atomic.** Flow first calls
transform, accepts the returned state, and then sends outputs
(`impl.clj:300-310`). If a Seon transform commits facts and output delivery
later blocks or is interrupted, the fact is authoritative and the channel
message must be a reproducible wake hint. Never make an uncommitted output the
only record of work.

**Error recovery keeps the pre-step in-memory state.** A transform exception is
reported and the loop continues with `state`, not a partially returned state
(`impl.clj:311-319`). A transaction already committed before that exception
still exists. Seon's fencing and idempotent receipt identity, not Flow's local
rollback, must resolve re-entry.

**Input selection is state-driven.** `::flow/input-filter` is read from the
retained state before each `alts!!` (`impl.clj:288-295`). A database-derived
read set can be copied into ephemeral state on an interest wake, but Flow will
not query it by itself.

**Topology is static.** All procs and connections are closed over at
`create-flow` (`impl.clj:38-69`). Flow is honest for long-lived runtime owners
or one small flow per reconciled unit, not for a secretly dynamic graph.

**Buffer introspection is real channel introspection.** Core.async channel
`datafy` exposes pending put/take counts, close state, buffer type/count/capacity,
and retains the channel object in `:clojure.datafy/obj`
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/channels.clj:308-318`;
`.../impl/buffers.clj:111-124`). A database backlog is not that buffer and must
not be reported as though it were.

## Mapping the Seon owners

| owner | honest Flow placement |
|---|---|
| Datahike writer | Keep Datahike's two-stage core.async pipeline intact. It serially threads `db-before -> db-after`, feeds a batch commit loop, and returns one `LocalWriter` per connection (`reference-code/datahike/src/datahike/writer.cljc:94-200,202-269,286-306`). A Flow proc may own connection lifecycle and report/query its state; it must not copy or wrap each internal writer stage as a second pipeline. |
| run loop | Best fit for a custom `ProcLauncher`: Flow control plus a database-interest wake channel, with pending and running work queried from facts. Today's driver already wakes from a message interest, scans, claims via CAS, and resumes from receipts (`src/seon/agent/driver.clj:826-901`; `:688-733`). |
| eval launcher | Use a `:compute` Flow process only with Seon's supplied bounded platform executor. Each submission references an already committed running receipt; the channel is disposable scheduling state. |
| reactive registrations | A long-lived proc can own the process-local registration/timer maps while current database values and interests remain the source of recomputation (`src/seon/reactive.cljc:371-447,504-556,558-666`). Ping should expose bounded derived measurements, not the atom's implementation map. |
| web-render mailboxes | A web-render proc may own connections and latest-wins mailboxes. They are intentionally process-local presentation state: clear then offer newest (`src/seon/web/feed.clj:22-43`), with connection lifecycle at `:90-163`. They should not be relabeled as durable channel backlog. |

This proc granularity matters. An agent entity or claimed run is durable work,
not automatically one static Flow graph node. Flow should manage the runtime
owners that schedule those facts unless a separate single-proc flow per active
run proves useful. The web UI already derives per-agent visibility from facts;
it does not need a fake static channel topology.

## Flow-monitor compatibility

The monitor uses public Flow calls **and implementation-shaped datafy output**.

Its public calls are:

- `async-flow/ping` every second
  (`reference-code/core.async.flow-monitor/src/clojure/core/async/flow_monitor.clj:70-81`);
- `inject`, `resume-proc`, and `pause-proc` from websocket actions
  (`flow_monitor.clj:92-98`).

Its non-public assumptions are stronger:

- On connect it sends `(datafy flow)` to the browser
  (`flow_monitor.clj:83-91`).
- At startup it expects `[:chans :error]` and `[:chans :report]` in that map,
  then extracts the actual channels from their
  `:clojure.datafy/obj` metadata (`flow_monitor.clj:131-156`).
- It consumes those channels directly with `alts!!`
  (`flow_monitor.clj:121-129`).
- It expects `:conns` in the datafied graph
  (`.../flow_monitor_ui/global.cljs:154-167`), exact
  `::flow/status`, `::flow/count`, `::flow/ins`, `::flow/outs`, and
  `::flow/state` ping keys plus channel buffer counters
  (`.../routes/index/view.cljs:35-62`), and it drives pause/resume from the
  volatile `::flow/status` (`view.cljs:233-266`).

**Against Path A:** yes, unchanged. The graph remains the real Flow graph, so
its datafy shape and report/error channel metadata are original. A custom
launcher must send the built-in ping keys if the monitor is expected to render
state and meters; the SPI minimum of pid+status alone is insufficient for the
current UI. Database-only activity will correctly have no channel meter unless
the launcher declares a real input channel.

**Against Path B:** no, not merely because `seon.flow` has similarly named
functions. The monitor calls `clojure.core.async.flow/*` and expects an object
implementing the original `flow.impl.graph/Graph` protocol. It also expects the
original datafy map and live channel-object metadata. B works only if it
implements that original protocol and reproduces those internals, or if the
monitor is forked. Either choice expands B beyond “same public signatures.”

## Interrupt and platform-thread seam

Flow's threading model can host the eval seam, but only in the `:compute`
shape. **Yes: the eval step can own `:interrupt-fn` arming and permit
accounting inside its transform without a Flow fork.** The constraints below
make that safe; using `:io` or letting Flow's timeout fire first does not.

- `:mixed` and `:io` run transform on the proc-loop executor. `:io` is a
  virtual-thread-per-task executor when supported; `:compute` and `:mixed` are
  cached platform-thread pools
  (`reference-code/core.async@dc35f3e:src/main/clojure/clojure/core/async/impl/dispatch.clj:71-111`).
- For `:compute`, Flow runs the proc loop in `:io`, submits each transform to
  the supplied compute executor, and blocks on its `Future`
  (`flow.clj:267-281`; `impl.clj:258-263`). Supplying Seon's bounded platform
  executor through `:compute-exec` therefore puts the step function on the
  required platform thread.
- Seon can arm `:interrupt-fn`, create the SCI fork, evaluate, stop the timer,
  and release its permit in the transform's `try/finally`. The current owner
  does exactly those operations around a submitted platform-thread task
  (`src/seon/sci/eval.clj:116-173`), while the interrupt owner samples
  platform-thread allocation and arms the timer
  (`src/seon/sci/interrupt.clj:46-99`).

The friction is permit placement. Acquiring a semaphore *inside* the compute
transform can occupy a compute worker while waiting. Prefer making the bounded
compute executor's active count the concurrency authority and recording queue
wait at submission. If the explicit permit remains temporarily, the executor
size must not exceed the permit count so acquisition is immediate; otherwise
Flow's worker pool and Seon's permit become two competing bounds.

Flow's `:compute-timeout-ms` is not Seon's interrupt. It calls `Future.get` with
a timeout but never calls `cancel` (`impl.clj:258-260`). If that timeout fires
first, Flow reports an error while the SCI task may continue and retain its
permit. The Flow timeout must therefore be a louder, longer backstop than the
SCI time limit, with `:interrupt-fn` settling first. Pause/stop also cannot
preempt the blocked `.get`; only the SCI interrupt can settle the transform.
This is a configuration invariant, not a reason to fork `flow.impl`.

## Path B sizing and drift

At Seon's exact alpha2 pin, a complete source fork is **786 lines**:

| file | lines |
|---|---:|
| `flow.clj` | 341 |
| `flow/impl.clj` | 322 |
| `flow/spi.clj` | 95 |
| `flow/impl/graph.clj` | 28 |

At alpha3 it is **792 lines**: 346 + 323 + 95 + 28. Copying only the public
wrapper and `impl.clj` is not enough for a same-API namespace: SPI types,
Resolver behavior, Graph dispatch, and monitor compatibility are part of the
usable contract.

B would have to modify or replace:

- graph construction, the closed-over proc/connection inventory, channel
  creation, mults, casts, resolver, and injection;
- the proc loop's init/transition/transform state threading and input filter;
- control-priority I/O, pause/resume/ping/stop handling, error continuation,
  and compute dispatch;
- datafy output, actual report/error channels, channel metadata, and buffer
  metrics for flow-monitor; and
- restart behavior while retaining exact public return values and keyword
  namespaces.

But it cannot remove arbitrary in-memory step state and still honor the
published step contract. Persisting state is not general: it may contain
functions, predicates, channels, executor handles, and other process-local
objects. Retaining that state recreates `flow.impl`; serializing only Seon's
special state creates a narrower, incompatible API.

The drift is already measurable. From flow-monitor's `1.9.808-alpha1` through
`1.10.874-alpha3`, eight semantic Flow commits changed the four source files,
with 139 insertions and 78 deletions. The changes include broadcast signals,
`ExecutorService` → `Executor`, three `futurize` corrections, and
`:ping-map-fn`. Flow remains explicitly alpha (`flow.clj:11-16`), is released
from a separate branch after removal from `master`, and alpha3 has only 62
lines of direct Flow tests. A copy would require manual three-way review of
every alpha change plus a Seon-owned conformance suite for the API and the
monitor's undocumented datafy expectations.

## Executable falsifier run in this lane

A disposable probe used the published `1.10.874-alpha3` artifact and no Seon
source changes. It created a custom `ProcLauncher` with one Flow input and an
external fact holder, then exercised `start`, `resume-proc`, `inject`,
`ping-proc`, `datafy`, `stop`, graph recreation, and ping after recreation.

Observed:

```clojure
{:started-keys #{:report-chan :error-chan}
 :ping-before-restart
 #:clojure.core.async.flow{
   :pid :durable
   :status :running
   :state #:durable{:value 7}
   :count 1}
 :ping-after-restart
 #:clojure.core.async.flow{
   :pid :durable
   :status :paused
   :state #:durable{:value 7}
   :count 0}
 :monitor-shape
 {:top-keys #{:chans :execs :procs :conns}
  :chan-keys #{:report :ins :error :outs}
  :error-object? true
  :report-object? true}}

```

The proc-local status and count correctly reset; the external fact survived;
and the exact channel-object metadata the monitor extracts remained present.
This directly falsifies the claim that database-authoritative state requires a
`flow.impl` fork.

## First production-shaped falsifiable spike

Build one **single-proc Flow in a throwaway cluster**, without editing any Flow
namespace:

- The proc is the current run driver behind a custom `ProcLauncher`.
- Its `alts!!` set contains Flow control and one bounded wake channel fed by the
  existing database interest.
- `resume-proc` allows claims; `pause-proc` stops new claims and releases no
  already committed fact implicitly.
- `ping-proc` reads a fresh database value and returns queued/running/wedged
  projections from run and receipt facts.
- One injected message commits a durable test command, proving `inject` remains
  a scheduling ingress rather than the work ledger.
- The eval case uses `:compute-exec` backed by a bounded platform executor; an
  infinite SCI loop reaches the SCI time limit before Flow's longer compute
  backstop and leaves no permit or worker leak.
- Kill and recreate the Flow after the running receipt commits. The recreated
  proc must ping the same durable state and resume at the first missing terminal
  receipt.
- Start unmodified flow-monitor against the graph. Its pause/resume, injection,
  ping state, report stream, and error stream must work with no adapter and no
  `flow.impl` edit.

The spike fails Path A if any required behavior needs a patch to
`clojure.core.async.flow.impl`, if monitor compatibility needs a fork, if
pause/resume can publish around the run fence, or if Flow's compute timeout can
return while an eval still owns capacity. Otherwise A is closed and B should
be removed from consideration.

## Three strongest reasons

- **The intended seam already exists and was proven.** `ProcLauncher` owns
  nonstandard process loops, alpha3's `:ping-map-fn` derives report state, and
  the executable probe preserved facts across a fresh Flow without modifying
  the implementation.
- **B cannot keep the claimed contract while replacing the guts.** Arbitrary
  state, channel ports, predicates, xforms, mults, buffers, signals, and
  injection are observable Flow semantics. Removing them breaks the API;
  retaining them is a copy of Flow plus a second database mechanism.
- **A preserves the ecosystem contract; B inherits undocumented drift.**
  Unmodified flow-monitor works against the original Graph/datafy/channel
  shape. A pins and upgrades one alpha dependency. B owns 792 source lines,
  protocol compatibility, monitor internals, and every future alpha change.
