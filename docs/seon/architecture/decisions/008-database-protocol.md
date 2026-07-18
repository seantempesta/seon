---
type: decision
status: active
date: 2026-07-14
tags: [decision, architecture, database, cljs]
---

# ADR-008: Data-only Transit database protocol

## Context

One database authority owns each database's ordered writes, immutable indexed
values, shared query computation, and committed-report source. The first
authority implementation is the JVM/Datahike service and may host many isolated
cluster databases concurrently. Bun agent children and the Bun web host are
clients: they issue operations over explicit immutable database values and never reconstruct a
Datahike database, index, cache, or transaction feed. Transport details may
differ locally and remotely without changing database semantics.

## Decision

`seon.db.protocol` is the one semantic protocol. Messages are eager ordinary
data encoded with Transit. Request and response values name their operation,
one request identity, database value, and typed result or error.
Transport owners carry those values and do not reinterpret them.
Protocol version 7 preserves the existing `:seon.error/kind` on failed outer
and member responses alongside the protocol's operation-level error kind. A
client can therefore keep user-input and core-bug classification without
parsing an error string or learning JVM exception types.

The authority is the sole durable writer and indexed-read owner. Clients never
send closures or database handles; transaction functions such as CAS cross only
in their data form. One persistent multiplexed session carries independently
correlated requests, responses, cancellation, and selective database
interests. Reads require an exact database value, and `execute-many` resolves one
immutable database value for independent members. Its required outer result
bound is accepted in member position order, while member work may finish in
parallel; exact encoded frame bytes remain a separate delivery fence.
Successful writes wake only matching interests with committed ordinary data; a
gap reacquires the current database value, never transaction replay into
a replica. Durable request
receipts provide same-request mutation recovery without a second write path.

Datahike owns connection/index lifetime, exact committed-value identity,
completed query caching, identical-query single-flight, and native read
semantics. Seon owns protocol validation, session acquisition, fair
multi-database admission, paging, delivery bytes, and errors-as-values. Native
Datahike, Bun socket, stream, process, Future, and Promise values remain inside
their host owners.

## Consequences

- Unix-domain sockets are the local transport, not the protocol definition.
- A future remote or non-JVM authority conforms to the same data fixtures and
  state machine.
- Nippy is not a wire contract; any use inside Konserve remains private storage
  encoding.
- There is no client-side Datahike replica, global transaction broadcast,
  replay cursor, database broker, or duplicate query cache/listener.
- Interests are connection-owned and ephemeral; the database does not persist
  active subscriptions, changed-row summaries, or a second invalidation bus.
- Backpressure is bounded independently at database admission, encoding, and
  each session's exact retained output bytes. A slow client loses only its own
  session.

## Related

- [[architecture]] — the authority, Bun host, and isolated-child topology.
- [[data-model]] — transaction provenance and database values.
- [[agent-runtime]] — CAS fences and lifecycle transitions.
- [[observability]] — replay and forensic database values.
