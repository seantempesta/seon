---
type: reference
status: active
tags: [reference, flow]
---

# core.async Flow foundation

`clojure.core.async.flow` is Seon's runtime foundation. Fresh Seon runs agents
inside the cluster JVM: every agent owns a Flow graph created from the one
blueprint in `seon.cluster.agent/graph-definition`. A few shared plumbing
graphs own work submission, fault commitment, agent arming, and web rendering.
There is no external-agent process topology, Integrant resource graph,
dispatcher, or central scheduler.

## Per-agent graph

Each agent graph has two `:io` procs:

- `::mailbox` forwards a payload-free episode signal immediately, so graph
  control remains responsive during a model call.
- `::turn` pins a database value, derives that agent's next work, runs the
  surviving turn function, and self-wakes through the same mailbox when facts
  show more work.

The connection between them uses `(sliding-buffer 1)`. A wake means only
"derive now"; all durable triggers are database facts, so coalescing wakes
cannot lose work. `seon.cluster.agent/arm!` stamps, starts, resumes, routes, and
primes the graph. `disarm!` stops the graph and joins its published completion
before removing the route.

## Shared Flow mechanisms

`seon.flow` implements Flow's `flow.spi/ProcLauncher` boundary and supplies
var-backed procs so redefining a step function changes live behavior. Proc
workloads are explicit:

- `:compute` is bounded CPU work and must not block.
- `:io` may block on transport and must not compute.
- `:mixed` is the fail-closed default for unresolved work and is deliberately
  refused for the agent graph's known procs.

The process root owns the executors shared by the graphs. `seon.flow/submit!!`
is the bounded compute-submission owner used by agent evaluation.

Core faults use Flow's error and report channels. `seon.flow` fans those
channels to monitoring and to one fault-committer graph; durable error facts,
not channel contents, survive a crash. Agent mistakes remain flat error values
and never enter the Flow fault path.

## Transport law

Channels carry only in-flight values whose loss is free: values are derivable
from database facts or superseded by a newer complete value. Buffer choice
states the loss semantics. Durable messages, runs, receipts, attempts, and
faults are database facts. No recovery decision depends on a channel.

## Sources checked

- `src/seon/cluster/agent.clj` — the one per-agent graph blueprint and
  lifecycle.
- `src/seon/flow.clj` — proc launcher, workload owners, fault fan-out, and
  shared graph mechanics.
- `src/seon/render/web.clj` — the cluster render graph and sliding-buffer tabs.
- `reference-code/core.async/src/main/clojure/clojure/core/async/flow.clj`,
  `flow/impl.clj`, and `flow/spi.clj` — dependency contracts.
- `deps.edn` — `org.clojure/core.async` `1.10.874-alpha3`.
