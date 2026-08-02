---
type: decision
status: superseded
date: 2026-07-14
tags: [decision, architecture, archive, database, runtime]
---

# ADR-008: Data-only Transit database protocol

This decision is superseded. It selected a versioned Transit protocol,
persistent remote sessions, database replicas, catch-up, and claim epochs for
database access across processes. Those mechanisms were deleted.

[[architecture/decisions/012-process-root-cluster-topology]] is the current
decision. It follows the 2026-07-27 branch-per-cluster ruling and the
2026-07-28 transport and custody rulings: cluster reads and writes are
co-located, durable state is database facts, and run custody is process
presence without an epoch.

## Related

- [[architecture]] — current topology.
- [[data-model]] — database values and transaction facts.
- [[agent-runtime]] — run custody and recovery.
