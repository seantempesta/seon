---
type: decision
status: active
date: 2026-07-14
tags: [decision, architecture, database, cljs]
---

# ADR-008: Data-only Transit database protocol

## Context

The local cluster has a JVM Datahike writer and a CLJS pod replica. Reads remain
local to the pod's immutable database value; writes, administration, heavy
database calls, committed transaction frames, and bounded replay cross the
process boundary. Transport details may differ locally and remotely without
changing database semantics.

## Decision

`seon.db.protocol` is the one semantic protocol. Messages are pure, fully
namespaced data encoded with Transit. Request and response envelopes name their
operation, request identity, database attachment/coordinate, and typed result or
error. Transport adapters carry those envelopes and do not reinterpret them.

The JVM server is the sole durable writer. The pod never sends closures or raw
database handles; transaction functions such as CAS cross only in their
data form. Successful writes publish committed transaction frames, and a
replica repairs a gap through bounded ordered replay from a verified attachment
coordinate. Durable request receipts provide same-request recovery without a
second write path.

## Consequences

- Unix-domain sockets are the local transport, not the protocol definition.
- A future remote adapter reuses the same envelopes and state machine.
- Nippy is not a wire contract; any use inside Konserve remains private storage
  encoding.
- The writer does not persist query subscriptions, changed-row summaries, or a
  second invalidation bus.
- Remote bootstrap, cancellation, reconnect, backpressure, and state-transfer
  algorithms require their own PRD and proof.

## Related

- [[architecture]] — the two-process topology.
- [[data-model]] — transaction provenance and complete coordinates.
- [[agent-runtime]] — CAS fences and lifecycle transitions.
- [[observability]] — replay and forensic coordinates.
