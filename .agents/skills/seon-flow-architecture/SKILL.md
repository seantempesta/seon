---
name: seon-flow-architecture
description: "How Seon is architected on core.async.flow — procs, workloads, buffers, agent graphs, the boot tower, and how flow composes with the database and SCI. Load this whenever you are adding or changing a proc, graph, channel, or buffer; deciding :io vs :compute; wiring wakes, faults, or streaming; reasoning about what survives a crash; or wondering where a new runtime mechanism belongs. Also load it before designing ANY new runtime machinery in Seon, even if flow is not mentioned — the answer is usually a proc in an existing graph, and this skill says which one and why."
---

# Seon flow architecture

Seon's runtime is `core.async.flow` graphs in one JVM process; one JVM may host
several sovereign clusters (`src/seon/cluster.clj:924-940`). There is no
central loop, no dispatcher, no scheduler entity — that shape is banned by
owner ruling ("a JavaScript event loop inside Clojure"). If you are about to
write one, stop and read *The banned shapes* below.

This skill is the map. Sections marked **[TARGET]** describe designs that
are ruled and evidenced but **not built yet** — never write code that
assumes them without checking the tree first.

## Read the source, not your memory

Every source claim here points at its current owner; measured claims point at
their owning research note. This program has repeatedly been misled by
day-old prose, so verify with one live command (`rg`, `bin/test`) before
acting. The dependency's own source is vendored under
`reference-code/` precisely so you can check semantics rather than guess:

| question | read |
|---|---|
| what a proc/channel/buffer really does | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` + `flow/impl.clj` + `flow/spi.clj` |
| what `:io`/`:compute`/`:mixed` actually construct | `reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-96` |
| how the graph monitors itself | `reference-code/core.async.flow-monitor/` |
| transactions, listeners, query planning | `reference-code/datahike/` (our fork — internal calls are sanctioned) |
| the interrupt hook, contexts, forks | `reference-code/sci/doc/interrupt.md`, `reference-code/sci/src/sci/` |
| SSE writes and socket backpressure | `reference-code/http-kit/` (our fork — see *Streaming*) |

Deeper research, all with evidence, lives in
`docs/prds/sci-execution-runtime/research/`: `flow-mechanics-2026-07-28.md`,
`flow-inventory-2026-07-28.md`, `workload-classification-2026-07-28.md`,
`workload-scheduling-truth-2026-07-29.md`,
`agent-flow-render-falsification-2026-07-29.md`,
`render-pipeline-design-2026-07-29.md`. Cite them; don't re-derive them.

Read the progressive-disclosure references when the map is not enough:

- `references/workloads-and-scheduling.md` — executor ownership, parking,
  admission, and the measured scheduling probes.
- `references/agent-graphs.md` — the current two-proc blueprint, arming,
  custody, episode caps, and the proposed `::renders` proc.
- `references/wakes-and-faults.md` — listener constraints, selective interest,
  the old E/A/V design worth reusing, and fault fan-out.
- `references/render-delivery.md` — the current snapshot/delta renderer,
  target packages/keyframes, buffer laws, http-kit drain state, and frame
  budgets.
- `references/degraded-start.md` — tower-layer diagnosis, advertisements,
  scratch-JVM cleanup, stale code, and the in-memory fallback boundary.
- `references/decisions.md` — the rulings, rationale, and rejected
  predecessors.

## The tower — where a new mechanism belongs

Boot is layered, each layer reading only the one below and publishing its
own readiness (`src/seon/cluster.clj`):

1. **Process** — REPL opens at second zero; identity is
   `(cluster-name, pid, start-instant)`.
2. **Store** — one process-root Datahike store under a lifetime lock; sibling
   clusters use distinct branches and reuse the held root store.
3. **Facts** — the config manifest reconciles into database facts; runtime
   reads the database, never files or env vars.
4. **Flow** — the cluster graph and one graph per agent.

The full current order is store/fork/connection → schema accretion → recovery
→ config apply → root seed → work-launcher install → agent arm → web serve
(`src/seon/cluster.clj:843-922`). Before adding a mechanism, ask which layer
owns it. Most "new machinery" is a proc in an existing graph or a derivation
over facts — not a new subsystem.

The process root constructs a bounded platform `:compute` executor
(parallelism = available processors) and a cached platform `:io` executor
(`src/seon/cluster.clj:156-179`), but **only the work-launcher graph currently
receives that pair** (`src/seon/flow.clj:381-425`). Ordinary cluster and
agent graphs use core.async's defaults: virtual-per-task `:io` when the JDK
supports it, cached platform `:compute`, and cached platform `:mixed`
(`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:71-96`).
The work launcher separately owns a virtual-per-task executor for evaluation
tasks (`src/seon/flow.clj:402-432`).

## Degraded start and stale JVMs

When a scratch cluster fails partway up the tower, read
`references/degraded-start.md` before retrying. The short version:

- inspect the carried `:seon.boot/instance`; absent keys identify the last
  published layer (`src/seon/cluster.clj:845-924,926-1023`);
- inspect the advertisement and prove both the named cluster and any detached
  operator JVM are gone with `bin/seon status`; `bin/seon` and
  `bin/seon-fresh` now enter the same fresh operator
  (`bin/seon:4-7`; `bin/seon-fresh:5-8`);
- remember that `bin/seon start <name>` adds to an already-running JVM when
  one is advertised, so its Var roots may predate the source edit
  (`script/seon/fresh_operator.clj:495-522`); use a lane-owned operator root
  with no advertisements to force a fresh JVM from current source; and
- if boot is blocked by shared-tree churn but the subject is a pure
  transformation, fall back to a separate `clojure -M:dev` JVM with immutable
  in-memory inputs and make no live-tower claim.

## Building a proc

Use `seon.flow/var-process` (`src/seon/flow.clj:83-115`). Two things matter:

**Reference the step-fn as a var (`#'f`), never a value.** This is what
makes live update work: re-evaluating a `defn` against the running system
changes proc behavior immediately, zero restart. Topology changes (procs,
conns, buffers) instead rebuild the graph — stop → `create-flow` → start,
measured 0.343 ms median for a three-proc
create/start/resume/ping-ready/stop round trip in the documented JDK 26 probe
— which is safe only when every channel's contents are losable by construction
(`docs/prds/sci-execution-runtime/research/flow-dynamic-update-2026-07-27.md`).

**Declare the workload explicitly.** `var-process` **refuses a missing or
`:mixed` workload at construction** (`src/seon/flow.clj:91-100`) because the
default `:mixed` execution path occupies one cached platform thread for the
proc's blocking loop and inline transform. This is a scaling cliff; call it
thread occupation, not Loom carrier pinning. Refusing at construction makes
the mistake unrepresentable rather than discovered under load.

```clojure
(seon.flow/var-process #'turn-step :io
                       {:seon.cluster.loop/cluster handle})
```

## Workloads: the measured truth

- **`:io`** — core.async's default is a virtual thread per task on a
  virtual-thread-capable JDK; the work-launcher graph overrides it with the
  process-root cached platform executor. May block; must not compute.
  Ordinary parking releases a carrier. The documented probe found a
  `synchronized` sleep pinned on JDK 21 but not JDK 26.0.1; native or critical
  sections can still pin (`workload-scheduling-truth-2026-07-29.md`).
- **`:compute`** — the whole transform is submitted to the graph's compute
  executor. It is bounded only where Seon supplies the process-root executor;
  core.async's default is cached and unbounded. It must never block.
- **`:mixed`** — **not a splitting scheduler.** It runs the proc's entire
  blocking loop and transform inline on one cached platform thread
  (`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj:243-323`).
  It is a fail-closed bucket for code the
  graph cannot resolve, and Seon refuses it outright.

`:compute` never identifies I/O *inside* a transform — the executor hop
moves the whole transform. So a chain that both computes and blocks must
be split at an explicit boundary, or run on `:io` and stay honest about
it. Classification is per-function and **derived** where possible: key
capability leaves carry `^{:seon.workload :io}` or `:compute` metadata lifted
at parse time into `:seon.fn/workload`
(`src/seon/sci/reader.cljc:198-245`). **[TARGET]** No current runtime derives
workload by reachability over `:seon.fn/calls`, including the planned
pure-implies-compute case. Until that owner exists, do not claim that
unannotated program-graph functions have a derived workload.

## Buffers: the loss semantics *are* the design

Pick the buffer by asking *what may be lost*, because the transport law
says anything recovery could need is a **database fact**, and anything in
flight rides channels **provided loss is free** — re-derivable from facts
or superseded by a newer complete value.

| buffer | meaning | example |
|---|---|---|
| `(sliding-buffer 1)` | latest-wins mailbox; a wake says only "look" and the woken pass derives everything from facts | agent episode conn (`src/seon/cluster/agent.clj:246-270`), armer/render/stream/page taps (`src/seon/cluster.clj:638-783`) |
| fixed | backpressure — the producer must wait | bounded work submission |
| counted-dropping | observation that must never block the producer | flow's error/report channels |

A design where channel loss breaks recovery is wrong by definition. If you
cannot name what makes a dropped item harmless, you have found a fact that
belongs in the database.

## Agent graphs

Every agent is its own flow graph, created with the agent from one
blueprint, parked between episodes (two `:io` procs; the measured baseline is
~8.5 KB and one virtual thread per parked proc) and kicked off by the messages
it receives (`src/seon/cluster/agent.clj:246-270`):

- **`::mailbox`** (`:io`) — total and instant: forwards a payload-free
  wake; it cannot recurse because the pass derives from facts.
- **`::turn`** (`:io`) — one episode pass per signal; claims the run,
  calls the model, evaluates, commits receipts.

Parallelism across agents is by construction. Runaway protection is one
per-agent dial (max consecutive runs per episode); **any race that could
loop agents forever is a design defect to dissolve, never a thing to
cap** — and nothing re-fires: a failed turn's only retry budget is the
episode's remaining turns.

**[TARGET] `::renders`** — a third proc owning every derived view of the
agent's world (html blocks *and* its own AI context pieces), memoized in
proc state with byte digests. Falsification passed
(`agent-flow-render-falsification-2026-07-29.md`: +8.9 KB/agent, zero new
platform threads, 12 registrations in one measured pass), but it also exposed
unresolved interest-narrowness and unbounded-memory seams. The proc and its
contract are not authored: the current graph definition contains only
`::mailbox` and `::turn` (`src/seon/cluster/agent.clj:246-270`). Production
delivery stays per-cluster.

## Wakes, faults, and streaming

**Wakes are event-driven, never polled.** One `listen!` per cluster routes
committed transactions to agent and render inputs
(`src/seon/cluster/wake.cljc:156-217`). Message/agent creation routing is
datom-selective; the current render input receives every transaction report.
Two rules that cost real debugging to learn (documented at
`src/seon/cluster/wake.cljc:6-63`): the listener **must never throw or park**
(Datahike invokes it before transaction delivery; an 800 ms listener made the
transaction take 804 ms), and re-asserting an identical value produces no
datom and therefore no routed wake.

**Faults ride flow's error channel** into `fault-committer-proc`
(`src/seon/flow.clj:593-602`), which commits each as a durable fact with
provenance — so "who should fix this" is a query, not a router. Agent
*mistakes* are different: they never touch these channels, they become
flat `:seon.error` values the agent sees. One config dial selects `:record` or
`:panic` (`resources/seon/schema/config.edn:7-8`). In current `:panic` mode the
cluster handler still commits the fault and prints it; it does not throw from
the recorder (`src/seon/cluster.clj:708-747`).

**Streaming**: partials ride channels; only the settled reply commits. The
current JVM renderer emits complete page snapshots, mults them, and computes
per-tab changed blocks (`src/seon/render/web.clj:229-285,530-608`).
**[TARGET]** Revisioned packages/keyframes would serialize shared bytes once.
The falsifier measured 0.872–1.171 ms p95 for once+mult at 50 tabs versus
31.783–42.479 ms p50 for per-tab serialization, and 1.2–1.5 ms p95 for a
250-event Chrome block morph
(`render-pipeline-design-2026-07-29.md`). Our
http-kit fork adds per-channel pending-byte state and a drain-or-close
completion so the `:io` writer **parks** on real backpressure — stock
`send!` reports channel openness, not socket drain
(`src/seon/render/web.clj:502-528`;
`reference-code/http-kit/src/org/httpkit/server.clj:321-326`;
`httpkit-write-path-2026-07-29.md`).

## Evaluation and the guarded door

Untrusted agent code runs in SCI, one fork per evaluation, under one
`:interrupt-fn` with a time limit as the only limit — SCI counts nothing
and has no step concept (`reference-code/sci/doc/interrupt.md`). The
interrupt fires at every interpreted function-body entrance and throws an
uncatchable marker; the known ceiling is code stuck inside a *host* call,
where no interpreted entrance occurs. Evaluations go through the one
bounded submission owner (`seon.flow/submit!!`,
`src/seon/flow.clj:469-497`; `src/seon/cluster/loop.cljc:218-234`)
rather than inline on a turn thread, so eval concurrency is bounded and
observable.

## The banned shapes

Each of these was tried, measured, or ruled against — reintroducing one
is a regression even if tests pass:

- **A central loop, dispatcher, scheduler, or active-set.** Agents are
  independent flows; the JVM's threads are cheap.
- **`:mixed`, or an unannotated proc.** Refused at construction.
- **Polling or `Thread/sleep` where an event exists.** Interfaces express
  their dependencies and publish their own readiness; a timeout may only
  guard genuinely unobservable external state, and its firing is itself a
  bug report.
- **A second mechanism** — `foo-v2`, a parallel registry, a compatibility
  path. Fix the one owner in place and delete the superseded path in the
  same change.
- **Storing what a query derives** — status fields, counters, cached
  projections. State is which facts exist.
- **Re-execution on recovery.** Nothing re-fires: reopen the store, mark
  dangling receipts interrupted, let the agent adapt.

## Working effectively

- **Probe before you plan.** Use the selected cluster's `eval_clj` operation;
  one live form answers most design questions. The REPL is the first design
  surface; checked-in source is the durable authority.
- **Use scratch clusters.** Live proof belongs on your own named cluster;
  never reset or bounce someone else's. Clusters have distinct database
  branches and graph state even when sibling clusters share the process-root
  store and executors (`src/seon/cluster.clj:924-940`).
- **Measure, never assert.** Every performance claim in this skill
  carries the conditions it was measured under, because this program has
  been misled twice by a number without its context.
- **When tests get awkward, suspect the design.** Fixture pain, polling,
  and exact-string assertions are design verdicts. Move the invariant to
  one choke point and keep one regression per class.
- **Read `reference-code/` before naming anything at an integration
  point.** Use the dependency's own vocabulary (`proc`, `step-fn`,
  `conns`, `:io`) — invented nouns drift and cause integration bugs.

## Related skills

`data-oriented-clojure` (write it the Seon way) → `data-modeling` (what
shape to register) → `datahike` (query/transact mechanics) →
`clojure-testing` (how to prove it) → `repl` (how the eval boundary reads
your forms).
