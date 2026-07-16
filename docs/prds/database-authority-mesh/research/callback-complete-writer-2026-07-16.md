---
type: research
status: active
tags: [research, database, architecture]
---

# Callback-complete writer execution

## Question

How can a single JVM selector admit many concurrent requests without retaining
one waiting Future, thread, or result promise per socket request?

## Dependency ledger

- Seon `seon.db.executor` and `seon.db.writer` at `c55fa564`.
- Datahike `d7ac886f`: `tools.cljc`, `writer.cljc`, query single-flight, and
  connection release.
- Core.async retained source: nonblocking `take!` callback semantics.

## Result

Replace the synchronous production entry point with one
`writer/handle-request!` function that returns after validation/admission and
invokes its completion function exactly once. One writer-owned active-request
map is sufficient for duplicate request-ID rejection, disconnect, cancellation,
execute-many progress, and final delivery; there is no second callback registry.

The executor receives one stable runtime completion function. Work remains
ordinary request/class/database/scope/identity data and never retains a socket,
per-job callback, Future, or Promise. Physical completion updates job and class
accounting under the executor lock, then invokes the stable completion function
outside the lock so execute-many can immediately admit replacement members.

Execute-many becomes an active-request state transition: resolve one database
value, admit up to the existing per-database bound, record each member by input
position as it completes, and admit the next member. Cancellation stops new
admission but final cleanup waits for physically running work before releasing
the shared database value. This deletes the coordinator waiter, result promises,
completed-job queue, and blocking member loop.

Datahike transaction completions already support callback consumption through
core.async without blocking a reader thread. That is a later mutation-worker
optimization, not a prerequisite for deleting transport waiters. Query
single-flight's per-caller promises remain internal semantic cancellation
owners and are not transport waiters.

## Required proof

- every completion/cancel/fence/continuation race delivers once and retains no
  request identity;
- completion may reenter admission without deadlock;
- 1,000 blocked requests do not add 1,000 threads/Future tasks;
- fast replies overtake a slow reply and correlate only by request ID;
- reverse-order execute-many completion stays bounded and returns input order;
- cancellation waits for physical cleanup before releasing the shared value;
- a stale worker cannot deliver into a reused request ID; and
- a throwing delivery function cannot leak executor accounting.
