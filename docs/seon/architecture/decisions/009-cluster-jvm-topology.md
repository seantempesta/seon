---
type: decision
status: active
date: 2026-07-25
tags: [decision, architecture, agent, runtime, web]
---

# ADR-009: One cluster JVM per store and one portable capability seam

## Context

Datahike ships only a `:self` writer, so each store has exactly one writer
process. A cluster already owns one store, process directory, and port set; it
is the isolation boundary. Separating agent eval from its database would add a
wire to every read and write without creating another legal writer for that
store. The web UI still needs failure isolation from agent-authored code.

## Decision

The supervised topology has four narrow roles:

- the cluster JVM performs transactions, emits the committed feed, and runs
  every agent in that store;
- the web-render JVM performs trusted pure database-value derivation and serves
  HTTP/Datastar SSE;
- disposable leaf runtimes run packages and selected workers; and
- the static browser submits actions and applies Datastar morphs.

Agent-authored code executes only in the cluster JVM, never in the web-render
JVM. Reads are pointers into immutable database values and writes are function
calls to the co-located transaction owner. SCI runs on `:compute` platform
threads under the one `:interrupt-fn`; blocking work uses `:io` virtual
threads. Claims, `:seon.agent.run/process`, epochs, turn phases, and
receipts—not process memory—define authority and recovery. Scale by adding
clusters, never by adding processes to one store.

Every capability family has one portable `.cljc` core and one native leaf per
active tier. Entry expressions alone bridge synchronous and asynchronous
ceremony. Cross-runtime behavior uses the same source or the same compiled
artifact.

Cutover strengthens the existing owner in place. Once consumers use the
portable driver, renderer, or capability core, the superseded path is deleted.
Compatibility shims, hand-mirrored wrappers, and dual-maintained mechanisms are
not part of the architecture.

## Consequences

- One writer process per store structurally means one cluster JVM per cluster.
- Renderer failure is isolated from the cluster JVM.
- Cluster-JVM replacement resumes from database facts without cold-boot
  repair.
- JavaScript-only dependencies remain contained in disposable leaf runtimes.
- A platform port adds a leaf, not a parallel public API or control loop.

## Related

- [[architecture]] — complete topology and data flow.
- [[agent-runtime]] — claims, phases, receipts, and `:interrupt-fn`.
- [[ui]] — independent web-render process.
- [[toolkit]] — portable capability families and effect classes.
- [[laws]] — same-source and one-mechanism laws.
