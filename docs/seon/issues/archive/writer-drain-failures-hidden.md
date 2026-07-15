---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow, architecture]
---

# Surface database writer drain failures

## Problem

The JVM request server could return from close while a request handler still
executed. Registry release discarded a Datahike shutdown failure, removed the
database identity, and let writer/server shutdown report success. Native restore
cannot prove exclusive storage access across either behavior.

## Evidence

Before the current working-tree correction,
`seon.db.transport.uds/close-request-server!` closed the listening socket and
connection channels but retained or joined no connection threads.
`seon.db.registry/release-database!` wrapped `datahike.api/release` in an empty
catch, unconditionally removed the entry, and returned `::released? true`.

An executable probe replaced `datahike.api/release` with a deterministic
exception. The registry returned `#:seon.db.registry{:released? true}` and no
longer listed the database. Maintained Datahike at selected SHA
`6f90b339768b1a02066dce3b6fcc93a200758fcc` instead defines release as the
writer admission/drain boundary and throws when writer, secondary-index, or
store shutdown is incomplete.

The implemented correction gates each decoded UDS request under the existing
server lifecycle, joins every admitted handler, retains failed registry
identity plus its error, blocks deletion, and projects failure through writer
and server stop responses. Closure awaits the integrating commit. The focused
transport/registry/writer/server gate passes 17
tests/87 assertions, and the complete writer checkpoint passes 62/360. The
fixing commit is recorded in this note's history.

## Owner

`seon.db.transport.uds` owns request admission and connection resources.
`seon.db.registry` owns Datahike connection identity and release.
`seon.db.writer` and `seon.db.server` own the composed shutdown result. These
existing owners must remain one lifecycle path.

## Acceptance

- Closing the request server rejects a request not admitted before close,
  preserves a response already admitted, and returns only after every admitted
  handler thread exits.
- Datahike release success removes the registry entry; failure retains its
  database identity and exact error and can never become success on retry in
  the same process.
- Database deletion never runs after an unproved release.
- Writer and server stop return `stopped? false` with every failed database
  identity, and the process shutdown hook emits that failure.
- Focused transport, registry, writer integration, and server tests plus the
  complete writer checkpoint pass.
