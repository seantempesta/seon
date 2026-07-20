---
type: issue
status: resolved
tags: [database, issue, pod, web]
severity: friction
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

The source-frozen five-feed fanout run at Seon `623f4650` found the remaining
pod-side form of the defect. Five gzip SSE feeds and five stable execution
children remained live while 65 separate root-purpose transactions committed
from basis transaction 536870925 through 536870997. Reactive work converged to
the newest database value with a global active/pending high-water mark of five,
but the writer logged eight
`:seon.db.transport.uds.send/session-full` closures. The pod then recorded
`Database authority ended the session` and native socket-write core faults;
automatic reconnect eventually hid the transport loss.

The maintained transport source establishes the cause. JVM `send!` reserves a
response slot for every server-initiated protocol event, even though the event
is one-way. The slot remains owned through encoding and socket output and is
released only after the complete frame is written. Reserving the 65th slot
returns `send-session-full`, and `send!` immediately schedules closure of that
session. `writer/send-interest-event!` also treats every non-accepted status as
terminal and closes the connection. The CLJS transport already coalesces
received events by interest, replacing repeated datom events with one
resynchronization event, but that bounded mechanism runs only after the JVM has
admitted and written each physical frame and cannot protect writer admission.

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

The correction must strengthen that existing event mechanism before
response-slot admission. A full event path is transient backpressure, not a
terminal socket condition. At most one newest pending event per interest (and
one newest database-advanced event per database) may be retained; repetition
conservatively becomes the protocol's existing resynchronization event.
Ordinary request responses retain their exact identity and current
response-slot semantics. There is no larger numeric cap and no second
committed-transaction truth queue.

The event caller is not Datahike's commit-serialization thread. A committed
report wakes `run-readiness!`, which submits `:delivery` work through
`seon.db.executor/try-submit!`; `execute-delivery!` and `deliver-report!` then
run on one of the bounded `seon-database-cpu-*` workers. Datahike transaction
execution is separately admitted as the executor's `:mutation` class and is
dispatched through its virtual-thread executor. Therefore the codec executor's
rejection fallback encodes the one already-bounded event on a delivery worker,
not on the transaction commit owner. It is work-conserving backpressure and
cannot serialize subsequent commits behind socket output.

## Acceptance

- The transport test fills the selected capacity with timed-out physical work
  and proves that one more request receives the existing busy failure as data,
  without rejecting through public async instrumentation.
- A transaction that meets transient busy admission retries the byte-identical
  request on the same session and commits once capacity returns.
- A sustained real-agent run with five feeds does not exhaust session capacity.
- With the per-session response-slot limit set below an event burst, one-way
  events remain bounded by event key, repetition becomes one newest
  resynchronization/database-advanced event, the session remains open, and an
  ordinary request response is admitted when capacity returns.
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
- The exact five-feed, five-child, 65-commit run has no writer session-pressure
  closure or pod database core fault, converges every feed to the newest basis
  transaction, keeps reactive active/pending ownership bounded, and closes with
  zero registrations, consumers, views, subscriptions, or execution children.
- Under the paused-read 10,000-commit stress, pending writer events remain
  bounded by installed interest and acquired-database keys rather than commit
  count, while ordinary query and transaction latency remains bounded and
  returns to its pre-pressure range after the reader resumes.

## Resolution

Seon `c75efad8` separates one-way event delivery from request-response slots.
UDS owns one opaque physical event from encoding through full-frame write,
always selecting response output first. The writer owns one in-flight event
and one newest pending value per semantic key; completion alone advances the
next event. Transient output pressure neither grows a per-commit queue nor
closes the session.

The rebuilt five-feed, five-child, 65-commit acceptance completed all 325
expected reactive evaluations and converged every feed without a session-full,
session-close, socket, or core-fault marker. Reactive and Datastar ownership
returned to zero after canonical close.

The opt-in real-SocketChannel regression committed at `2f42b339` then held one
listener unread through 10,000 transactions and 100 queries. Committed reports
were offered=delivered=10,000 with queued=0 and overflowed=0; semantic pending
high-water was one; transaction p95 was 2.708 ms and query p95 was 1.981 ms.
After the listener resumed, it received the exact newest basis transaction and
writer pending order, UDS event state/output, request connections, and writer
interests all returned to zero. The stress gate passed 14 tests / 10,326
assertions in 26.18 seconds, while its normal non-stress form passed 14 / 84.
This closes every acceptance item without capacity inflation, a second socket,
per-event acknowledgements, or another replay queue.
