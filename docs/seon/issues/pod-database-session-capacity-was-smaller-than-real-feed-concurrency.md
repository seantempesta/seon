---
type: issue
status: open
tags: [database, issue, pod, web]
---

# Pod database session capacity was smaller than real feed concurrency

## Evidence

A real agent run with five independent Datastar feeds filled the CLJS
database session's 16 pending-request entries. Reads have a 30-second deadline,
and correctly retain their request ID after timeout until the JVM finishes or
cancels the physical database work. While those entries remained owned, every
new database call failed immediately with
`:seon.db.transport.uds.failure/busy`.

The JVM UDS transport already bounds its codec worker queue at 256 entries.
The CLJS session limit of 16 was an unrelated hard-coded assumption and was too
small for the application's supported feed and agent concurrency.

The source-admitted three-agent Inspect run on 2026-07-19 exposed the deeper
fanout defect. At `07:02:05.315Z` the writer closed execution child PID 41916's
database session with
`:seon.db.transport.uds.send/session-full`. The child was busy for a 47-second
turn and did not observe the closed Bun socket until its next write at
`07:02:24.919Z`; Bun's maintained `Socket.write` contract says `-1` means the
socket is closed or shutting down, not ordinary backpressure. The child
persisted a core fault and exited, then the automatic recovery transaction
failed. Sibling agents and the pod remained alive.

The writer currently adds every connection that performs database work to
`::acquisitions`, then `deliver-database-advanced!` sends every committed
database value to every acquired connection. An execution child therefore
receives every sibling transaction even though it owns no listener and its
turn intentionally works from an immutable database value. Sixty-four pending
event-response slots close the session while the child is doing synchronous
CLJS work. Raising the cap would only postpone the same broadcast failure and
retain more database values.

## Expected owner

`seon.db.transport.uds` owns one bounded per-session pending-request map. Its
capacity permits ordinary concurrent feeds and agent work while still bounding
memory and preserving request identity until physical completion. It does not
discard timed-out request IDs or add retries.

The database protocol separately owns delivery of
`:seon.db.protocol.event/database-advanced`. The pod opts into those updates so
its current database cache and reactive listeners advance. A short-lived
execution child opts out: it acquires one database value at startup, receives
`db-after` from its own transactions, and starts a fresh process for later
work. Listener registration already returns a resynchronization database value,
so an opted-in listener never relies on replaying an unbounded sequence of
intermediate database-advanced events.

## Acceptance

- The transport test fills the selected capacity with timed-out physical work
  and proves that one more request receives the existing busy error.
- A sustained real-agent run with five feeds does not exhaust session capacity.
- Pending requests retire after the JVM completes or cancels physical work;
  capacity does not leak across the recovery proof.
- Three concurrent execution children can remain busy while sibling
  transactions exceed 64 commits without receiving database-advanced events,
  exhausting response slots, or closing their UDS sessions.
- The pod continues to receive database-advanced events and cache the newest
  database value; explicit listeners continue to receive matching datoms and a
  resynchronization value after reconnect.
- The maintained Bun contract treats write `0` as backpressure and `-1` as a
  closed socket; no retry writes through a terminal session.
