---
type: decision
status: active
date: 2026-07-26
tags: [decision, architecture, agent, runtime, web]
---

# ADR-009: One cluster JVM per store and one portable capability seam

## Context

Datahike ships only a `:self` writer, so each store has exactly one writer
process. A cluster already owns one store, process directory, and port set; it
is the isolation boundary. Separating agent eval from its database would add a
wire to every read and write without creating another legal writer for that
store. Guarded evaluation and component supervision provide failure
containment without splitting rendering from its database.

## Decision

The supervised topology has two process kinds:

- the cluster JVM performs transactions, emits the committed feed, and owns
  every agent, guarded render, program-graph acquisition, Flow graph, and
  HTTP/Datastar SSE service in that store;
- disposable leaf runtimes run packages and selected workers.

The static browser is a client, not a supervised runtime process. It submits
actions and applies Datastar morphs.

Reads are pointers into immutable database values and writes are function
calls to the co-located transaction owner. Agent-authored evals and renders use
the one `seon.sci.eval/evaluate` path on `:compute` platform threads under the
one `:interrupt-fn`; blocking work uses `:io`. Supervision, bounded evals, and
Integrant component restart protect the cluster JVM. Claims,
`:seon.agent.run/process`, epochs, turn phases, and receipts—not process
memory—define authority and recovery. Scale by adding clusters, never by
adding processes to one store.

Store open takes one `flock` assertion before Datahike is opened. A second
cluster JVM for the same store refuses loudly. This is the one fenced exception
where coordination precedes the database, because it prevents two database
writers from existing.

Every agent-facing `my.*` tool call enters the one guarded
`seon.effect/request!` function carrying the request identity. Every
capability family has one portable `.cljc` core and one native leaf per active
tier. Entry expressions alone bridge synchronous and asynchronous ceremony.
Cross-runtime behavior uses the same source or the same compiled artifact.

Cutover strengthens the existing owner in place. Once consumers use the run
loop, renderer, or capability core, the superseded path is deleted.
Compatibility shims, hand-mirrored wrappers, and dual-maintained mechanisms are
not part of the architecture.

## Consequences

- One writer process per store structurally means one cluster JVM per cluster.
- Render failure is bounded by guarded evaluation and component restart.
- Cluster-JVM replacement resumes from database facts without cold-boot
  repair.
- JavaScript-only dependencies remain contained in disposable leaf runtimes.
- A platform port adds a leaf, not a parallel public API or control loop.

## Related

- [[architecture]] — complete topology and data flow.
- [[agent-runtime]] — claims, phases, receipts, and `:interrupt-fn`.
- [[ui]] — in-process render Flow and SSE delivery.
- [[toolkit]] — portable capability families and effect classes.
- [[laws]] — same-source and one-mechanism laws.
