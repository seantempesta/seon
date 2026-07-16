---
type: research
status: active
tags: [research, database, flow]
---

# Remote Datahike operation seams

## Question

Which Datahike concepts must cross the authority protocol so Bun consumers can
delete their local replica without recreating database internals or a broadcast
system?

## Selected operations

Use four existing concepts:

- `schema` returns `d/schema` at one captured coordinate;
- `index-page` materializes a bounded prefix from Datahike seek or reverse-seek;
- `listen` registers a physical-connection-owned interest; and
- `unlisten` removes the interest named by the original listen request ID.

History is not a database value or a fifth operation. `history?` is an option on
query and index-page, applied to the one captured immutable value. Schema and
index-page compose inside execute-many; listen and unlisten do not because they
change retained connection state.

This is grounded in maintained Datahike `api/specification.cljc`, `core.cljc`,
`db/interface.cljc`, `query.cljc`, `committed_report.cljc`, and `writer.cljc`,
plus Seon's existing coordinate-bound browser paging code.

## Index page

The request carries index, zero to four prefix components, direction, limit
between one and 200, optional history, and an ordinary cursor map. Results are
eager vectors of plain `:seon.db/e`, `:seon.db/a`, `:seon.db/v`, `:seon.db/tx`,
and `:seon.db/added?` maps. No lazy Datahike sequence or Datom crosses the wire.

Datahike seek continues after the requested prefix, so the authority applies a
prefix `take-while`. Resume includes the last complete datom in the cursor,
seeks inclusively from its four ordered components, and discards through the
exact five-field datom. The cursor also seals coordinate, history, index,
prefix, and direction, preventing reuse against another ordered view.

## Selective interests

The listen request ID is the interest identity. Events use that same request ID;
unlisten has its own request ID and targets the original. Ownership is the exact
physical connection plus that request ID.

Two filters cover current consumers:

- Datahike's existing conservative query attribute dependencies, either `:all`
  or a set; and
- small ORed datom patterns with required attribute and optional entity, value,
  and added fields.

One generation-fenced committed-report source serves each active database
generation. A Datahike listener beside it would duplicate the same committed
transaction delivery. Reverse indexes from changed attribute to interested
connection/request pairs make a normal commit proportional to transaction
datoms plus matches, not all children. Exact patterns let one addressed agent
message wake one child. The event is only a coordinate and the matching plain
datoms; the actual result remains a coordinate-pinned grouped read.

On committed-report overflow, selective filtering is no longer provably
complete. The authority emits one resynchronization event at the latest
coordinate to every remaining interest for that database. This exceptional gap
is the only database-wide wakeup. The terminal gapped source is then closed and
reopened for the same generation at that latest coordinate; it is not polled
forever.

## Ordering laws

- Registration stores the returned coordinate. A racing commit is reflected in
  that coordinate or appears later from the ordered report source.
- Unlisten removes the interest and queues its acknowledgement under the same
  ordering; no event follows that acknowledgement.
- Disconnect removes its interests before releasing its acquisitions.
- Final release removes the committed-report source before indexes and storage
  close.
- No database, connection, Datom, report, callback, stream, Future, or Promise
  enters protocol data.

## Required dependency changes

Mark Datahike's existing schema function with
`:capability-operation :datahike.operation/schema`. Return its already-computed
query attribute dependencies from `q-with-evidence`; Bun must not parse Datalog
again. Add one process-wide readiness handoff for committed-report sources that
transition from empty to nonempty or open to gapped, so Seon drains ready
sources without a polling thread per database.

## Proof plan

- Page mixed assertion/retraction history forward and backward with no duplicate
  or skipped datom.
- Race registration/unlisten with commits and prove the coordinate/event laws.
- With 1,000 interests, one addressed message produces one event rather than
  1,000 encodes.
- Overflow the report source and prove resynchronization rather than a falsely
  complete filtered stream.
- Disconnect one of two sibling sockets and prove only its interests disappear.
- Final release retains zero sources, interests, event frames, or
  connection references.
