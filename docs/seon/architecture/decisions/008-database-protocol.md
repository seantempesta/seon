---
type: decision
status: active
date: 2026-07-14
tags: [decision, architecture, database, runtime]
---

# ADR-008: Data-only Transit database protocol

## Context

One cluster JVM owns each store's ordered transactions, committed-report
source, and agent evals. The web-render JVM reads a process-local immutable
replica and forwards writes to that cluster JVM. Disposable leaf runtimes are
clients of capability seams but own no durable database state. Transport
details may differ locally and remotely without changing database semantics.

## Decision

`seon.db.protocol` is the one semantic protocol. Messages are eager ordinary
data encoded with Transit. Request and response values name their operation,
one request identity, database value, and typed result or error.
Transport owners carry those values and do not reinterpret them.
Protocol version 7 preserves the existing `:seon.error/kind` on failed outer
and member responses alongside the protocol's operation-level error kind. A
client can therefore keep user-input and core-bug classification without
parsing an error string or learning JVM exception types.

The writer is the sole durable mutation owner. Clients never send closures or
database handles; transaction functions such as CAS cross only in their data
form. One persistent session carries independently correlated mutation
requests, responses, cancellation, committed changes, and selective interests.
Successful writes advance replicas with committed ordinary data. A gap
reacquires a complete current database value before delivery resumes. Durable
request receipts provide same-request mutation recovery without a second write
path.

Datahike owns replica connection/index lifetime, exact committed-value identity,
query caching, and native read semantics. Seon owns protocol validation,
session acquisition, mutation admission, replica catch-up, paging, delivery
bytes, and errors-as-values. Native Datahike, socket, stream, process, Future,
and Promise values remain inside their host owners.

## Consequences

- Unix-domain sockets are the local transport, not the protocol definition.
- A future remote or non-JVM authority conforms to the same data fixtures and
  state machine.
- Nippy is not a wire contract; any use inside Konserve remains private storage
  encoding.
- Each reader process has one database replica owner; there is no second
  application cache, database broker, or duplicate invalidation bus.
- Interests are connection-owned and ephemeral; the database does not persist
  active subscriptions, changed-row summaries, or a second invalidation bus.
- Backpressure is bounded independently at database admission, encoding, and
  each session's exact retained output bytes. A slow client loses only its own
  session.

## Related

- [[architecture]] — cluster JVM, web-render, leaf-runtime, and browser topology.
- [[data-model]] — transaction provenance and database values.
- [[agent-runtime]] — CAS fences and lifecycle transitions.
- [[observability]] — replay and forensic database values.
