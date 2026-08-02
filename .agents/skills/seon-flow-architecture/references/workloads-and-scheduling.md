# Workloads and scheduling

Read this when choosing a proc workload, changing executor ownership, or
reasoning about blocking and evaluation admission.

## Contents

- [The dependency contract](#the-dependency-contract)
- [Current executor ownership](#current-executor-ownership)
- [Parking, occupation, and pinning](#parking-occupation-and-pinning)
- [The bounded submission owner](#the-bounded-submission-owner)
- [Measured scheduling probes](#measured-scheduling-probes)
- [What a capability boundary changes](#what-a-capability-boundary-changes)
- [Decision checklist](#decision-checklist)

## The dependency contract

Read core.async's implementation before assigning a workload:

- `:io` runs the proc loop and transform on the graph's I/O executor.
- `:compute` keeps the loop on the I/O executor, submits the whole transform
  to the compute executor, and waits for its `Future`.
- `:mixed` runs the proc loop and transform inline on the mixed executor.
- A missing workload defaults to `:mixed`.

Those are implementation facts at
`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-323`.
The `:compute` hop does not discover I/O inside a transform and does not divide
one transform into CPU and blocking segments.

Core.async's default dispatch executors are:

| workload | current core.async default |
|---|---|
| `:io` | virtual thread per task when supported, cached platform fallback |
| `:compute` | cached platform pool |
| `:mixed` | cached platform pool |

Verify the constructors at
`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-96`.
These defaults are not bounded by the machine's core count.

Seon's `var-process` accepts only `:io` or `:compute` and rejects a missing or
`:mixed` declaration at construction (`src/seon/flow.clj:83-115`). It also
requires a Var so re-evaluating the referenced `defn` changes behavior without
rebuilding the graph.

## Current executor ownership

Do not flatten four distinct executor roles into “the executor”:

| owner | construction | current consumers | source |
|---|---|---|---|
| core.async default/root-held `:io` | core.async memoized virtual-per-task when available, cached-platform fallback | every current production graph's `:io` proc loop, including work launcher, cluster, agent, and fault graphs | `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:82-105`; `src/seon/cluster.clj:158-182,1079-1096`; `src/seon/cluster/agent.clj:337-390`; `src/seon/flow.clj:381-423,626-666` |
| process-root `:compute` | Seon fixed platform pool, size `availableProcessors` | work-launcher graph `:compute` transforms | `src/seon/cluster.clj:158-182`; `src/seon/flow.clj:381-423` |
| core.async default `:compute` | core.async memoized cached platform pool | any graph with a `:compute` proc and no `:compute-exec` override | `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:91-105`; `reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:145-148` |
| work task executor | Seon virtual-thread-per-task | submitted SCI evaluation tasks | `src/seon/flow.clj:135-137,199-229,401-430`; `src/seon/cluster/loop.cljc:509-525` |

Core.async constructs and memoizes the default executors at
`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-105`;
flow's resolver uses them exactly when a graph omits the matching override
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:50-56,145-148`).
The process-root pair holds that same dependency-owned `:io` object beside
Seon's fixed `:compute` pool (`src/seon/cluster.clj:158-182`). The work-launcher
graph supplies only the root `:compute` override, so its custom proc loop also
resolves core.async's default `:io` (`src/seon/flow.clj:285-305,381-423`).
Per-agent graphs omit executor overrides at
`src/seon/cluster/agent.clj:337-390`; the cluster and fault graphs do the same
at `src/seon/cluster.clj:1079-1096` and `src/seon/flow.clj:626-666`.

The work-launcher task executor is deliberately separate from the graph's
I/O executor. Launcher construction creates a virtual-per-task executor at
`src/seon/flow.clj:135-137,401-430`. `execute-work!` submits each task there
(`src/seon/flow.clj:199-229`).

## Parking, occupation, and pinning

Use these terms precisely:

- A virtual thread that blocks in a supported operation normally parks and
  releases its carrier.
- A platform thread occupied by the long-lived `:mixed` proc loop is
  unavailable to other work. That is occupation, not Loom carrier pinning.
- A virtual thread pins its carrier only in runtime-specific critical cases.
  The July 29 probe found a sleeping `synchronized` section pinned on JDK 21
  but not on JDK 26.0.1. Native and other critical sections can still pin.

The probes and JDK conditions are recorded in
`docs/prds/sci-execution-runtime/research/workload-scheduling-truth-2026-07-29.md`.
Never generalize “parking is free” into “all blocking is free”: parked tasks
still consume application-level admission and retain their live state.

## The bounded submission owner

`seon.flow/submit!!` is the one public submission operation
(`src/seon/flow.clj:469-497`). The production turn path uses it at
`src/seon/cluster/loop.cljc:509-525`; do not evaluate inline on the turn proc.

The launcher owns:

- a fixed submission buffer;
- an active-count gate bounded by configured parallelism;
- a virtual-per-task task executor;
- task completion routed back to the launcher; and
- capacity observation through a compute proc.

Inspect `src/seon/flow.clj:304-467` before changing admission. The current
active count spans each evaluation's complete lifetime. If an eval parks
inside a host call, its logical slot remains occupied even though the virtual
thread may have released its carrier.

That distinction is why the current design is bounded and observable but does
not yet recover CPU capacity from parked capability I/O.

## Measured scheduling probes

The source-grounded probe used:

- compute parallelism `C = 18`;
- `M = 72` tasks;
- each task blocked for `L = 100 ms`; and
- JDK 26.0.1 on the recorded host.

The fixed platform pool and virtual threads holding a lifetime semaphore both
ran in four waves; releasing the CPU permit for the blocking segment matched
the one-wave unbounded virtual-thread case. Read the probe output and
interpretation in
`docs/prds/sci-execution-runtime/research/workload-scheduling-truth-2026-07-29.md`.
The result does not say “virtual threads make work faster.” It shows that a
lifetime-wide logical gate preserves the same bottleneck as fixed workers when
most admitted work is parked. Releasing the CPU permit at an explicit blocking
boundary recovered the expected overlap in that probe.

The flow-mechanics probe ran each section in a fresh JVM on an 18-core Mac,
JDK 26, `-Xmx512m`; its idle section used one-proc graphs sharing the default
executors. It measured about 8.5 KB and one virtual thread per parked proc,
8.3 MB for 1,000 graphs, and 21.6 ms to start those graphs. Use the full
method and caveats in
`docs/prds/sci-execution-runtime/research/flow-mechanics-2026-07-28.md`; do not
turn those host measurements into universal constants.

## What a capability boundary changes

There is no current effect/capability door in fresh `src/`. The fresh SCI host
bindings are pure `my.run` and `my.message` surfaces, and workload reachability
over `:seon.fn/calls` is not implemented. Treat both as target work.

When a genuine capability boundary lands, it creates the first honest place to
separate CPU admission from blocking transport:

1. Enter interpreted computation under bounded CPU admission.
2. At the capability request, publish an addressable request and release CPU
   admission.
3. Run blocking transport as `:io`.
4. Reacquire CPU admission before resuming interpreted computation.

This is the target examined by the CPU-segment probe, not current behavior.
Do not fake it by annotating an entire mixed chain `:io`, adding another
executor, or inspecting function names. Placement is intended to derive from
program-graph call edges and leaf workload facts; that reachability owner is
still **[TARGET]**.

The only built metadata step is parse-time lifting of explicit
`:seon.workload` metadata into `:seon.fn/workload`
(`src/seon/sci/reader.cljc:198-245`).

## Decision checklist

Before selecting a workload:

1. Read the step function and every blocking dependency it calls.
2. Choose `:compute` only when the whole transform is bounded CPU work.
3. Choose `:io` when the transform may park and its computation is small.
4. Split a materially mixed chain at a real capability boundary; do not expect
   core.async to split it.
5. Keep `:mixed` impossible in Seon graph definitions.
6. Name which executor the graph actually receives.
7. State whether a bound controls threads, tasks, or logical lifetimes.
8. Re-run a measured probe when changing any of those owners.
