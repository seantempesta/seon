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

A later reactive slow/fast-socket pressure probe submitted 300 independent
transactions concurrently. The 256-entry pod request window correctly refused
the excess with `:seon.db.transport.uds.failure/busy`, but
`seon.db/submit-transaction!` did not classify that transient admission result
with its existing exact-request delivery recovery. The ordinary overload then
escaped as hundreds of core faults, filled the bounded pending-error buffer,
and degraded the disposable pod before socket pressure could be measured.

The first source-admitted retry probe on 2026-07-19 narrowed the remaining
boundary. Two hundred sixty concurrent transactions did enter the exact-request
retry loop, but `seon.db.transport.uds/request!` was a public instrumented async
function. Its expected rejected `:busy` Promise was therefore recorded as a
core fault before `seon.db` could convert and retry it. The named pod became
not-ready and undiscoverable while the transaction backlog drained. Capacity
admission now resolves that one expected transport condition as failure data;
`seon.db` translates the same fields into its existing error value below the
instrumentation boundary, and the transaction owner retries it unchanged.

The rebuilt disposable-cluster retry then completed 260 concurrent
transactions in 25,123 ms with zero errors. Pod, watcher, and writer remained
ready, the web UI returned HTTP 200, and the pod log contained no `:busy`,
capacity, or core-fault marker. This closes local request-window correctness;
the remaining acceptance items concern sustained agent/feed fanout and the
separate slow-consumer SSE proof.

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
discard timed-out request IDs or add retries. The transaction owner already
retries one frozen idempotent request through ambiguous delivery failures; it
also waits through transient local `:busy` admission with the same bounded
exponential delay instead of reconnecting or exposing a core fault.

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
  and proves that one more request receives the existing busy failure as data,
  without rejecting through public async instrumentation.
- A transaction that meets transient busy admission retries the byte-identical
  request on the same session and commits once capacity returns.
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
