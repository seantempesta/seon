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

The first interpreter attempted to compose `seek-datoms`, `rseek-datoms`, raw
prefix equality, and a five-field cursor in Seon. Four executable falsifiers
rejected that seam:

- retractions encode a negative transaction field internally, while the public
  transaction ID is positive;
- one transaction may retract and add the same E/A/V, so `added?` participates
  in temporal order even though public seek accepts only four components;
- Transit reconstructs byte arrays and ordinary Clojure equality compares
  those arrays by identity; and
- Datahike resolves lookup refs and ref values before index comparison, so raw
  request components do not equal the resulting numeric datom components.

Bounded paging therefore belongs in the owned Datahike fork. Its eager
`index-page` capability accepts index, zero to four native components,
forward/reverse direction, a one-to-200 limit, optional retained-weight bound,
and an exact five-field Datahike cursor. The database argument itself selects
current versus history. Datahike resolves components and resumes strictly after
the cursor with its native current/temporal comparator, touches only the seek
path plus cursor verification and `limit + 1` rows, and rejects an absent,
tampered, or outside-prefix cursor as structured data.

Seon chooses the pinned immutable value, calls that operation, and converts the
returned Datoms once to plain `:seon.db/e`, `:seon.db/a`, `:seon.db/v`,
`:seon.db/tx`, and `:seon.db/added?` maps. The wire cursor adds only coordinate,
index, direction, and history; it does not duplicate the prefix or Datahike's
comparison rules. No lazy sequence or Datom crosses the wire.

## Selective interests

The listen request ID is the interest identity. Events use that same request ID;
unlisten has its own request ID and targets the original. Ownership is the exact
physical connection plus that request ID.

Two filters cover current consumers:

- a query form whose conservative attribute dependencies are derived by the
  authority, either `:all` or a set; and
- small ORed datom patterns with required attribute and optional entity, value,
  and added fields.

One generation-fenced committed-report source serves each active database
generation. A Datahike listener beside it would duplicate the same committed
transaction delivery. Datahike now exposes one bounded process-wide blocking
readiness queue; it creates no thread, callback, Future, sleep, or per-database
poller. Reverse indexes from existing committed scope and changed attribute to
interested connection/request pairs make a normal commit proportional to
transaction datoms plus matches, not all children. Exact patterns let one
addressed agent message wake one child. The event is only a coordinate and the
matching plain datoms; the actual result remains a coordinate-pinned grouped
read.

The readiness thread only hands a source to the existing fair authority
executor. A bounded Datahike batch is one serialized delivery job for that
database; different databases filter and deliver in parallel. Completion
requeues a still-ready source at the global tail. Rejected admission returns
the source without consuming reports. This avoids both a hot database
monopolizing readiness and a new Seon queue/fairness owner.

On committed-report overflow, selective filtering is no longer provably
complete. The authority abandons the retained prefix, opens a replacement
before reading the new head, and emits one resynchronization event at that
coordinate to every interest that existed at the cut. This exceptional gap is
the only database-wide wakeup. Later listeners start from the replacement
coordinate and do not receive the old resynchronization.

Native session send is admission, not synchronous encoding on the readiness or
delivery worker. Each physical session drains one ordered bounded encode path;
different sessions encode in parallel. Its host result distinguishes closed,
session-full, authority-full, and encode failure. Per-session pressure or an
encoding failure may close only that session; authority-wide pressure cannot be
misreported as a slow client.

## Ordering laws

- Registration opens the source before reading and returning its coordinate.
  A racing commit is reflected in that coordinate or appears later from the
  ordered report source; no event precedes the acknowledgement.
- Unlisten removes the interest and queues its acknowledgement under the same
  ordering; no event follows that acknowledgement.
- Disconnect removes its interests before releasing its acquisitions.
- Delivery jobs serialize per database but run in parallel across databases.
- Shutdown drains physical cleanup, proves the interest indexes empty,
  interrupts the one blocking readiness thread, and only then releases
  Datahike connections.
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
