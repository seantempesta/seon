---
type: decision
status: superseded
date: 2026-07-26
tags: [decision, architecture, archive, agent, runtime, web]
---

# ADR-009: One cluster JVM per store and one portable capability seam

This decision is superseded. It selected one store and JVM per cluster,
Integrant component lifecycle, disposable JavaScript leaves, and claim-epoch
recovery. Those mechanisms were deleted.

The 2026-07-27 branch-per-cluster and CLJ-only rulings, the 2026-07-28
agents-are-flows and custody rulings, and rulings 2026-08-01 #27 and #29 for
one live program graph per cluster replaced it.

[[architecture/decisions/012-process-root-cluster-topology]] is the current
decision. One JVM may host several sovereign cluster branches over one fenced
process-root store and shared executors.

## Related

- [[architecture]] — complete target topology.
- [[agent-runtime]] — agent graphs and recovery.
- The `seon-flow-architecture`, `datahike`, and `repl` skills — current
  process, database, Flow, and SCI seams.
