---
type: issue
status: open
severity: blocker
tags: [issue, agent, flow, architecture]
---

# Arbitrary eval allocation lacks hard process memory containment

## Problem

Datahike query/pull execution and retained eval values now have synchronous
budgets, but arbitrary JavaScript or dependency code can still allocate between
runtime checkpoints. The CLJS sandbox catches model mistakes; it is not a
security or hard memory boundary. A pathological predicate or direct JS form
can therefore exhaust the pod process even though it cannot exhaust the sole
writer through the bounded database API.

## Evidence

The 2026-07-14 memory-safety implementation adds work, result-node, and shallow-
weight budgets inside maintained Datahike and bounded structural admission for
`result/<id>`. Those mechanisms deliberately avoid claiming that they can
preempt arbitrary synchronous code. The live default pod recovered from 100
budget-exhausted queries, and a 300 KB retained result became a compact
descriptor, but neither probe is a process-level adversarial allocation test.

`seon.worker-eval` is already a separate diffusion-oracle JSON-line evaluator
using `vm.runInThisContext`; it does not have the pod's analyzer state, prior
agent definitions, instrumentation, or database context and is not an
application-eval containment boundary. `reference-code/piscina` demonstrates
abort, termination, cleanup, queue, and respawn patterns but is not a selected
dependency. Node worker `resourceLimits` bound isolate heaps, not necessarily
process RSS or external/native allocation, so worker thread versus child process
must be selected from measured hostile-allocation evidence rather than assumed.

The 2026-07-15 exact-source decision is now recorded in
[[docs/prds/agent-runtime-correctness/research/process-death-containment-audit-2026-07-15]].
Node `v26.4.0` explicitly excludes `ArrayBuffer`/external data from worker
`resourceLimits` and allows a global OOM to abort the process, so worker threads
are rejected. The selected contract is a pod-owned, non-multiplexed disposable
Node child with parent-owned receipts, capabilities, TERM/KILL/reap, and
reconstruction. A child alone is not the hard numeric ceiling: Darwin rejected
`RLIMIT_AS` in the audit, so hostile-memory graduation requires a measured
per-child OS/container limit rather than V8 old-space flags or RSS polling.

The retained synthetic-gate audit at
[[docs/prds/agent-runtime-correctness/research/synthetic-disposable-child-hostile-gate-2026-07-15]]
adds measured preflight evidence. Five host Node `26.4.0` children with a 24
MiB old-space cap all ended by `SIGABRT` at roughly 78–79 MiB sampled peak RSS,
showing that the heap flag is not a total-memory bound. A non-root
`seon:slice1` container with a 96 MiB memory/swap ceiling allocated external
buffers while heap use stayed near 4 MiB; Docker recorded exit 137 and
`OOMKilled=true` after cgroup use approached the configured ceiling. Ten
TERM-refusing host children were reaped by KILL/`close`, with measured
KILL-to-close maxima under 1.5 ms in this isolated preflight.

The same audit exposes a concrete artifact gap: host/audited Node is `26.4.0`,
but `seon:slice1` contains Node `22.23.1` at an executable path absent from
`PATH`. Node 22 `--permission` denied filesystem, child-process, and worker
probes but allowed a network listener, whereas Node 26 denied it. The packaged
runtime therefore cannot claim Node 26 permission behavior; Docker
`network=none` is its only measured network boundary. The issue stays open
because these disposable probes did not run through a live pod, writer,
durable receipt, parent capability, or dead-parent subtree inverse.

## Owner

The agent runtime's process-isolation and restart boundary, coordinated with
the one operator. Do not add a second evaluator, move arbitrary eval into the
writer, or describe SCI as a security boundary.

## Acceptance

- A bounded worker/process contract contains arbitrary synchronous eval memory
  and CPU failure without losing or wedging the writer, supervisor, or cluster.
- The selected boundary is measured against JavaScript heap,
  ArrayBuffer/external memory, native/dependency allocation, fatal worker
  failure, cancellation latency, cleanup, and analyzer reconstruction. Use a
  child process with Node/OS limits if worker threads fail any hard-containment
  criterion.
- The agent loop records a structured failure and can continue or restart from
  database state.
- The default live cluster proves recovery from a killed or exhausted worker.
- Inspect uses the same runtime contract rather than a bespoke drive path.
- The child has no database connection, writer/feed, actor selector, or durable
  state. Parent-side capability requests stamp actor/provenance and fence writes
  from the committed task receipt and immutable database coordinate.
