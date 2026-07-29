---
name: seon-flow-architecture
description: "How Seon is architected on core.async.flow — procs, workloads, buffers, agent graphs, the boot tower, and how flow composes with the database and SCI. Load this whenever you are adding or changing a proc, graph, channel, or buffer; deciding :io vs :compute; wiring wakes, faults, or streaming; reasoning about what survives a crash; or wondering where a new runtime mechanism belongs. Also load it before designing ANY new runtime machinery in Seon, even if flow is not mentioned — the answer is usually a proc in an existing graph, and this skill says which one and why."
---

# Seon flow architecture

Seon's runtime is `core.async.flow` graphs in one JVM per cluster. There is
no central loop, no dispatcher, no scheduler entity — that shape is banned
by owner ruling ("a JavaScript event loop inside Clojure"). If you are
about to write one, stop and read *The banned shapes* below.

This skill is the map. Sections marked **[TARGET]** describe designs that
are ruled and evidenced but **not built yet** — never write code that
assumes them without checking the tree first.

## Read the source, not your memory

Every claim here carries a `file:line`. This program has repeatedly been
misled by day-old prose; verify with one live command (`rg`, `bin/test`)
before acting. The dependency's own source is vendored under
`reference-code/` precisely so you can check semantics rather than guess:

| question | read |
|---|---|
| what a proc/channel/buffer really does | `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj` + `flow/impl.clj` + `flow/spi.clj` |
| what `:io`/`:compute`/`:mixed` actually construct | `reference-code/core.async/.../impl/dispatch.clj:71-96` |
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

## The tower — where a new mechanism belongs

Boot is layered, each layer reading only the one below and publishing its
own readiness (`src/seon/cluster.clj`):

1. **Process** — REPL opens at second zero; identity is
   `(cluster-name, pid, start-instant)`.
2. **Store** — one Datahike store per cluster under a lifetime `flock`;
   a second opener refuses loudly.
3. **Facts** — the config manifest reconciles into database facts; runtime
   reads the database, never files or env vars.
4. **Flow** — the cluster graph and one graph per agent.

Ordering at start is `config apply → work launcher install → agent arm`.
Before adding a mechanism, ask which layer owns it. Most "new machinery"
is a proc in an existing graph or a derivation over facts — not a new
subsystem.

The process root owns **one bounded `:compute` executor (parallelism =
available processors, a computed hardware fact) and one `:io` executor**
shared by every graph (`src/seon/cluster.clj:158-166`). Note that
core.async itself defaults both `:compute` and `:mixed` to an *unbounded
cached platform pool* — the bound is Seon's, not the library's
(`dispatch.clj:71-96`; measured in `workload-scheduling-truth`).

## Building a proc

Use `seon.flow/var-process` (`src/seon/flow.clj:83`). Two things matter:

**Reference the step-fn as a var (`#'f`), never a value.** This is what
makes live update work: re-evaluating a `defn` against the running system
changes proc behavior immediately, zero restart. Topology changes (procs,
conns, buffers) instead rebuild the graph — stop → `create-flow` → start,
measured ~0.3 ms — which is safe because channel contents are losable by
construction.

**Declare the workload explicitly.** `var-process` **refuses a missing or
`:mixed` workload at construction** (`src/seon/flow.clj:88-89`) because
`:mixed` pins one platform thread per proc forever and is the one scaling
cliff. Refusing at construction means the mistake is unrepresentable
rather than discovered under load.

```clojure
(seon.flow/var-process #'turn-step :io
                       {:seon.cluster.loop/cluster handle})
```

## Workloads: the measured truth

- **`:io`** — a virtual thread per task. May block (model calls, database
  transactions, SSE writes); must not compute. Blocking parks for free;
  on our JDK `synchronized` no longer pins the carrier (probed: true on
  26.0.1, false on 21 — `workload-scheduling-truth-2026-07-29.md`).
- **`:compute`** — the bounded platform pool. Must never block: one
  blocked thread is a whole core of capacity gone.
- **`:mixed`** — **not a splitting scheduler.** It runs the proc's entire
  blocking loop and transform inline on one cached platform thread
  (`flow/impl.clj:243-323`). It is a fail-closed bucket for code the
  graph cannot resolve, and Seon refuses it outright.

`:compute` never identifies I/O *inside* a transform — the executor hop
moves the whole transform. So a chain that both computes and blocks must
be split at an explicit boundary, or run on `:io` and stay honest about
it. Classification is per-function and **derived** where possible: key
capability leaves carry `^{:seon.workload :io}` metadata lifted at parse
time into `:seon.fn/workload` (`src/seon/sci/reader.cljc:231-241`); a
function the program graph proves pure counts as `:compute` implicitly.
**[TARGET]** Full reachability derivation over `:seon.fn/calls` waits on
the code-graph corpus; until then, unresolved code is treated as unknown
and kept off `:compute`.

## Buffers: the loss semantics *are* the design

Pick the buffer by asking *what may be lost*, because the transport law
says anything recovery could need is a **database fact**, and anything in
flight rides channels **provided loss is free** — re-derivable from facts
or superseded by a newer complete value.

| buffer | meaning | example |
|---|---|---|
| `(sliding-buffer 1)` | latest-wins mailbox; a wake says only "look" and the woken pass derives everything from facts | agent episode conn (`src/seon/cluster/agent.clj:268`), armer, render, stream channels (`src/seon/cluster.clj:678-679`) |
| fixed | backpressure — the producer must wait | bounded work submission |
| counted-dropping | observation that must never block the producer | flow's error/report channels |

A design where channel loss breaks recovery is wrong by definition. If you
cannot name what makes a dropped item harmless, you have found a fact that
belongs in the database.

## Agent graphs

Every agent is its own flow graph, created with the agent from one
blueprint, parked between episodes (~2 virtual threads, ~8.5 KB per
parked proc) and kicked off by the messages it receives
(`src/seon/cluster/agent.clj:245` `graph-definition`):

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
platform threads, one mechanism for both render kinds), contract not yet
authored. Production is per-agent; delivery stays per-cluster.

## Wakes, faults, and streaming

**Wakes are event-driven, never polled.** One `listen!` per cluster routes
committed transactions to interested mailboxes (`src/seon/cluster/wake.cljc`).
Two rules that cost real debugging to learn (documented at
`wake.cljc:6-25`): the listener **must never throw** (Datahike fires
listeners inside the transaction's critical path — a probe's 800 ms
listener stalled the triggering transaction), and a routed attribute
re-asserted with an identical value produces **zero** wakes, silently.

**Faults ride flow's error channel** into `fault-committer-proc`
(`src/seon/flow.clj:593-602`), which commits each as a durable fact with
provenance — so "who should fix this" is a query, not a router. Agent
*mistakes* are different: they never touch these channels, they become
flat `:seon.error` values the agent sees. One config dial decides dev
panic vs prod degrade; the recorder itself never panics ("the fire alarm
doesn't burn").

**Streaming**: partials ride channels; only the settled reply commits.
Delivery is measured (`render-pipeline-design-2026-07-29.md`): serialize
once and `mult` the bytes (1.17 ms p95 at 50 tabs vs up to 53 ms
per-tab), morph per block (1.2–1.5 ms for a 250-event block). Our
http-kit fork adds per-channel pending-byte state and a drain-or-close
completion so the `:io` writer **parks** on real backpressure — stock
`send!` reports channel openness, not socket drain, and grew to 12 MB on
a stalled tab (`httpkit-write-path-2026-07-29.md`).

## Evaluation and the guarded door

Untrusted agent code runs in SCI, one fork per evaluation, under one
`:interrupt-fn` with a time limit as the only limit — SCI counts nothing
and has no step concept (`reference-code/sci/doc/interrupt.md`). The
interrupt fires at every interpreted function-body entrance and throws an
uncatchable marker; the known ceiling is code stuck inside a *host* call,
where no interpreted entrance occurs. Evaluations go through the one
bounded submission owner (`seon.flow/submit!!`, `src/seon/flow.clj:481`)
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

- **Probe before you plan.** `mcp__seon_cljs__eval_clj` against a live
  cluster answers most design questions in one form. The REPL is the
  first design surface; the checked-in source is the durable authority.
- **Use scratch clusters.** Live proof belongs on your own named cluster;
  never reset or bounce someone else's. Clusters are sovereign — one
  store, one flock, no shared mutable state.
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
