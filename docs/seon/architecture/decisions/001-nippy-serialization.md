---
type: decision
status: superseded
date: 2026-03-05
tags: [decision, architecture, archive, database]
---

# ADR-001: Nippy for inter-JVM serialization

This decision is superseded. It governed the removed inter-JVM flow harness.
Nippy is not a Seon wire contract.

[[architecture/decisions/012-process-root-cluster-topology]] is the current
decision. It records the branch-per-cluster, co-located database topology that
replaced both this harness and ADR-008's remote database protocol. The
replacement follows the 2026-07-27 branch-per-cluster ruling and the
2026-07-28 transport ruling in the active program roadmap.
