---
type: orchestrator
status: active
tags: [orchestrator, prd, database, flow, agent]
---

# Database authority mesh — working context

This PRD owns research and implementation of one protocol-defined database
authority serving many cluster databases and many isolated Bun readers without
duplicating Datahike indexes or query work. Read [[roadmap]],
[[research/source-grounded-research-tasks-2026-07-15]], architecture, data-model,
library-grounding, and the selected Datahike/Konserve/Bun source before work.

Optimization is the product: minimize computation, retained memory, copies,
serialization, scheduler contention, and conceptual surface while preserving or
improving the interface. Prefer the closest existing dependency primitive over a
Seon wrapper. Datahike already owns immutable database values, result caching,
transaction-aware propagation, connection reference counting, keyed listeners,
and explicit pod database-value release. Strengthen those mechanisms in place;
do not invent snapshot, coordinate, lease, subscription, or cache synonyms.

The protocol is implementation- and transport-neutral. The JVM/Datahike service
is the first authority; a future Bun, Rust, cloud, or platform implementation may
conform to the same semantic fixtures. Exactly one authority owns one database
at a time. Agent-facing functions and durable facts remain namespaced data;
Datahike, Konserve, Bun, socket, Future, and stream values stay inside their
owners.

Research begins with the shortest falsifier and exact source lines. Record
before/after CPU, latency, memory, allocations, cache hits, copies, concurrency,
failure behavior, and interface deletions. Do not implement from this high-level
goal until the relevant task has a settled source-grounded contract.
