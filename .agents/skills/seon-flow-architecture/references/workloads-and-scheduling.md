---
type: reference
status: active
tags: [reference, flow]
---

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
These defaults are not bounded by the machine's core count. `executor-for`
memoizes one executor per workload tag for the whole process (`:98-111`).

Seon's `var-process` accepts only `:io` or `:compute`, refuses a missing or
`:mixed` declaration, a non-Var step and arguments that name no
`:seon.env/environment`, all at construction (`src/seon/flow.clj:132-184`).
Requiring a Var means re-evaluating the referenced `defn` changes behavior
without rebuilding the graph.

## Current executor ownership

The process root holds one executor pair,
`seon.operator.runtime/root-executor-pair`
(`resources/seon/operator/runtime.clj:17-28`):

- `:io` is core.async's own memoized `(executor-for :io)`, virtual thread per
  task;
- `:compute` is `seon.flow/bounded-platform-executor`, a fixed platform pool of
  `availableProcessors` threads (`src/seon/flow.clj:186-190`).

Do not flatten the roles below into "the executor":

| graph or work | executor it receives | source |
|---|---|---|
| each agent graph | `:io-exec` = the handle's `:seon.flow/executor` | `src/seon/cluster/agent.clj:579-580` |
| the cluster graph (armer, render) | `:io-exec` = the same handle executor | `src/seon/cluster.clj:3305` |
| that handle executor | `seon.cluster/projection-executor`: root `:io`, binding the cluster's projection state around each task | `src/seon/cluster.clj:3265-3280`, assigned `:3328-3337` |
| the fault-committer graph | `:io-exec` = `seon.flow/projection-executor` over root `:io` | `src/seon/flow.clj:1123-1135`, `:1183-1189` |
| the work-launcher graph | `:compute-exec` = root `:compute`, and no `:io-exec` | `src/seon/flow.clj:616-654` |
| launcher compute and I/O submissions | root `:io` as the task executor | `src/seon/flow.clj:685-701`, dispatched `:530-556` |
| capability handlers | root `:io` | `src/seon/effect.clj:490-492` |

Flow's resolver uses core.async's defaults exactly when a graph omits the
matching override
(`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:50-56,145-148`).
The work-launcher graph therefore resolves its `:io` proc loops through that
fallback. The object is the same memoized executor the root holds, so the
escape is latent, but the projection binding the other graphs carry is absent
(`docs/seon/issues/flow-work-launcher-graph-omits-its-root-io-executor.md`).

The root `:compute` pool runs only the launcher graph's one `:compute` proc,
the capacity observer (`src/seon/flow.clj:217-239`). Submitted compute work
runs on root `:io` virtual threads; its bound is the launcher's admission
count, not a pool size. The pool is shared by every cluster in the JVM
(`docs/seon/issues/root-compute-executor-has-no-per-cluster-fairness.md`).

## Parking, occupation, and pinning

Use these terms precisely:

- A virtual thread that blocks in a supported operation normally parks and
  releases its carrier.
- A platform thread occupied by the long-lived `:mixed` proc loop is
  unavailable to other work. That is occupation, not Loom carrier pinning.
- A virtual thread pins its carrier only in runtime-specific critical cases.
  The July 29 probe found a blocking take inside `synchronized` pinned its
  carrier on JDK 21.0.11 but freed it on JDK 26.0.1. Native and other critical
  sections can still pin.

The probes and JDK conditions are recorded in the deleted research note,
readable with
`git show 215447c46^:docs/prds/sci-execution-runtime/research/workload-scheduling-truth-2026-07-29.md`
(lines 216-234). Never generalize "parking is free" into "all blocking is
free": parked tasks still consume application-level admission and retain
their live state.

## The bounded submission owner

The work launcher accepts two submission kinds: `seon.flow/submit!!` for
compute work awaited to a terminal value (`src/seon/flow.clj:836-904`) and
`seon.flow/submit!` for detached I/O work (`:771-835`). The turn path does not
enter either: the turn proc evaluates SCI inline (`evaluate-sources`,
`src/seon/turn.clj:4668`, called at `:4815` and `:4871`) and calls the model
inline (`ai/complete`, `:4525`). The one `submit!!` caller,
`submit-evaluation!!` (`src/seon/turn.clj:3315-3328`), has no caller. This
boundary is recorded in
`docs/seon/issues/turn-evaluations-bypass-work-submission.md`.

The launcher owns:

- a `RefusingBuffer` per submission kind that refuses at admission instead of
  blocking (`src/seon/flow.clj:304-342`);
- an active-count gate per kind, bounded by the configured concurrency
  (`with-submission-filter`, `:497-507`);
- the root `:io` task executor;
- task completion routed back to the launcher proc; and
- capacity observation through the `:compute` observer proc.

Inspect `src/seon/flow.clj:291-588` before changing admission. The active
count spans each submission's complete lifetime. If work parks inside a host
call, its logical slot remains occupied even though the virtual thread may
have released its carrier.

That distinction is why the current design is bounded and observable but does
not yet recover CPU capacity from parked capability I/O.

## Measured scheduling probes

The July 29 source-grounded probe used compute parallelism `C = 18`, `M = 72`
tasks each blocked for `L = 100 ms`, on JDK 26.0.1. The fixed platform pool and
virtual threads holding a lifetime semaphore both ran in four waves; releasing
the CPU permit for the blocking segment matched the one-wave unbounded
virtual-thread case. Read the probe output in the same deleted note
(`git show 215447c46^:docs/prds/sci-execution-runtime/research/workload-scheduling-truth-2026-07-29.md`,
lines 407-424).

The result does not say "virtual threads make work faster." It shows that a
lifetime-wide logical gate preserves the same bottleneck as fixed workers when
most admitted work is parked. Releasing the CPU permit at an explicit blocking
boundary recovered the expected overlap in that probe.

The July 28 flow-mechanics probe ran each section in a fresh JVM on an 18-core
Mac, JDK 26, `-Xmx512m`; its idle section used one-proc graphs sharing the
default executors. It measured about 8.5 KB and one virtual thread per parked
proc, 8.3 MB for 1,000 graphs, and 21.6 ms to start those graphs
(`git show 215447c46^:docs/prds/sci-execution-runtime/research/flow-mechanics-2026-07-28.md`,
lines 20-42). Do not turn those host measurements into universal constants.

## What a capability boundary changes

`seon.effect/request!` (`src/seon/effect.clj:941`) is the one system-side owner
for capability requests. It commits the request receipt before the protected
JVM handler runs on the process-root `:io` executor, bounds terminal data,
commits settlement once, and recovery interrupts an open receipt and never
dispatches it again (`src/seon/effect.clj:1-8`). Workload reachability over
`:seon.fn/calls` remains unimplemented.

The request owner is the honest place to separate CPU admission from blocking
transport:

1. Enter interpreted computation under bounded CPU admission.
2. At the capability request, publish an addressable request and release CPU
   admission.
3. Run blocking transport as `:io`.
4. Reacquire CPU admission before resuming interpreted computation.

The four-stage placement sequence remains **[TARGET]**; the request owner alone
does not implement CPU-permit release and reacquisition. Do not fake it by
annotating an entire mixed chain `:io`, adding another executor, or inspecting
function names. Placement is intended to derive from program-graph call edges
and leaf workload facts; that reachability owner is still **[TARGET]**.

The only built metadata step is parse-time lifting of explicit
`:seon.workload` metadata into `:seon.fn/workload`
(`src/seon/sci/reader.cljc:278-297`).

## Decision checklist

Before selecting a workload:

1. Read the step function and every blocking dependency it calls.
2. Choose `:compute` only when the whole transform is bounded CPU work.
3. Choose `:io` when the transform may park and its computation is small.
4. Split a materially mixed chain at a real capability boundary; do not expect
   core.async to split it.
5. Keep `:mixed` impossible in Seon graph definitions.
6. Name which executor the graph actually receives, including any omitted
   override that falls back to core.async's default.
7. State whether a bound controls threads, tasks, or logical lifetimes.
8. Re-run a measured probe when changing any of those owners.
