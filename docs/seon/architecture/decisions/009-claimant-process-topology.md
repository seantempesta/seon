---
type: decision
status: active
date: 2026-07-23
tags: [decision, architecture, agent, runtime, web]
---

# ADR-009: Claimant process topology and one portable capability seam

## Context

The writer, renderer, and agent runtime have different failure and scaling
domains. Letting one process accumulate those jobs makes agent code a threat to
transaction authority and UI availability. Maintaining separate CLJS and JVM
drivers or mirrored host wrappers also creates two semantic systems whose
recovery behavior can drift.

## Decision

The supervised topology has five narrow roles:

- the writer JVM performs transactions and emits the committed feed only;
- the web-render JVM performs trusted pure database-value derivation and serves
  HTTP/Datastar SSE;
- claimant JVMs execute the one claim-native driver, guarded SCI work, and
  model I/O;
- the disposable Bun leaf host runs JavaScript packages and selected workers;
  and
- the static browser submits actions and applies Datastar morphs.

Agent-authored code never executes in the writer or web-render process.
Claimants use one virtual thread per held claim and scale by adding replaceable
processes. Claims, epochs, turn phases, and receipts—not process memory—define
authority and recovery.

Every capability family has one portable `.cljc` core and one native leaf per
active tier. Entry expressions alone bridge synchronous and asynchronous
ceremony. Cross-runtime behavior uses the same source or the same compiled
artifact.

Cutover strengthens the existing owner in place. Once consumers use the
portable driver, renderer, or capability core, the superseded path is deleted.
Compatibility shims, hand-mirrored wrappers, and dual-maintained mechanisms are
not part of the architecture.

## Consequences

- Writer throughput work cannot broaden the writer into an agent host.
- Renderer failure is isolated from claimants and the writer.
- Claimant replacement resumes from database facts without cold-boot repair.
- JavaScript-only dependencies remain contained in a disposable leaf.
- A platform port adds a leaf, not a parallel public API or control loop.

## Related

- [[architecture]] — complete topology and data flow.
- [[agent-runtime]] — claims, phases, receipts, and guarded evaluation.
- [[ui]] — independent web-render process.
- [[toolkit]] — portable capability families and effect classes.
- [[laws]] — same-source and one-mechanism laws.
