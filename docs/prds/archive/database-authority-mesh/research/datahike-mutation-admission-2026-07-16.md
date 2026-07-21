---
type: research
status: completed
tags: [research, prd, database, flow]
---

# Datahike mutation admission — 2026-07-16

## Decision

Bound mutation requests in Seon's one authority dispatcher before calling
`d/transact`. Keep Datahike's existing writer on every connection as the only
transaction ordering, batching, commit, publication, and shutdown owner.

The mutation work class uses the same ordinary database round robin as reads,
but has its own active, queued-job, per-database, and queued-byte limits. At
most one mutation for a database enters `d/transact`; mutations for different
database connections may run concurrently up to the process limit. This is a
small admission policy around the existing mechanism, not a second writer,
transaction log, receipt system, or batching layer.

Configure each Datahike self writer's transaction and commit queues to 32 as a
defense-in-depth bound. Under normal Seon execution they remain near one item:
the authority waits for `d/transact` through commit while retaining the
per-database active position. Datahike's internal bound protects direct library
calls and later mistakes; it is not the user-visible admission boundary.

## Exact source path

### Datahike owns one ordered writer per connection

`reference-code/datahike/src/datahike/writer.cljc:42-55` puts every writer
invocation into that connection's transaction channel. The processing loop
applies synchronous transaction functions to one threaded immutable `old` DB
at lines 95-183. It stages reports in order into the commit channel. The commit
loop drains every currently ready report as one batch at lines 198-239, commits
the last resulting DB once, resets the connection once, then resolves every
request report with the same durable commit ID.

Consequences:

- one request does not imply one commit ID;
- Seon must not recreate ordering or batching outside Datahike;
- different Datahike connections have different channels and commit loops, so
  independent databases already write concurrently; and
- the useful outer bound is work admitted to those connection writers, not a
  global serial transaction worker.

Datahike's defaults are 120,000 transaction entries and 120,000 commit entries
per connection at `writer.cljc:78,280-300`. `-dispatch!` uses non-blocking
`put!` at lines 46-55, so a caller may create a promise and park an accepted put
instead of receiving overload. These generic defaults are incompatible with
many databases on modest hardware.

Datahike release already has the right final fence. The final connection
release closes writer admission synchronously, joins its processing and commit
loops, closes secondary indexes, and only then releases the store at
`reference-code/datahike/src/datahike/connector.cljc:477-528`. Seon must drain
its outer accepted mutations before entering that final Datahike release.

### Seon's current call is serialized too late

`src/seon/db/writer.clj:887-945` currently performs receipt recovery, coordinate
checking, coercion, tempid discovery, receipt construction, `d/transact`,
response construction, and ambiguous-result recovery under
`(locking connection ...)`. This correctly prevents two request threads from
interleaving same-connection preconditions, but it admits an unbounded platform
thread per socket connection before the lock. The current request transport
starts one thread per accepted Unix socket and invokes the handler synchronously
at `src/seon/db/transport/uds.clj:162-230`.

Thus a burst for one database retains request maps, stacks, socket workers, and
responses outside Datahike even though only one request can progress. The
Datahike queues are usually not the first saturation point because the Seon
connection lock spans the blocking `d/transact` call. Moving the bound before
that lock removes the thread pile-up while preserving the proven transaction
body unchanged.

The existing durable receipt is sufficient. `transact-once!` checks the
request ID against the current DB before constructing a transaction, writes
the request ID and request hash in transaction metadata, and retries recovery
after any ambiguous `d/transact` failure. No outer mutable completion record is
needed.

## Selected admission contract

1. Validate framing and protocol shape before admission. Resolve the database
   name to its exact current attachment and generation.
2. Account the decoded request's retained bytes and submit the ordinary request
   map to the mutation work class. Do not retain a connection or DB value in a
   queued job.
3. Reject before acceptance when the global job bound, per-database job bound,
   global byte bound, per-database byte bound, or attachment fence is full.
   Return the existing overload/error envelope with the same request ID.
4. Ordinary round robin chooses a database. Only one job for that database may
   become active. Resolve its connection and generation again immediately
   before execution; a mismatch is a definite stale-attachment failure.
5. Run the existing `transact-once!` body unchanged through durable commit and
   response materialization. The active mutation position is held through the
   `d/transact` result. Do not submit a nested future and do not release the
   position during storage wait in the first implementation.
6. Release byte/job accounting in `finally`, then select another ready
   database.

The dispatcher needs no mutation-specific routing names beyond the existing
work class, database name, attachment, request ID, and request. Its state is
immutable ready queues plus counts, as in the current `seon.db.executor`.

### Acceptance, disconnect, receipts, and cancellation

Acceptance means the authority mutation queue retained the validated request.
After acceptance, a client disconnect does **not** cancel or remove it. The
authority executes it and the durable receipt decides the outcome. A retry with
the same request ID and same logical transaction recovers the committed result;
reuse with different data remains a conflict. This is the only honest rule
because disconnect can occur after commit but before response delivery.

A queue-full response before acceptance is a definite rejection. A disconnect
before the client reads that response is still ambiguous from the client's
perspective, so retrying the same request ID remains correct: either no receipt
exists and it can be admitted, or the receipt recovers the prior commit.

The existing `cancel` operation must never claim rollback for a mutation. It
may report that the request is queued or running for observability, but accepted
mutations continue. Reads remain detachable because they do not create durable
effects; mutations do not share that cancellation contract.

### Release and shutdown

Database release first fences that exact attachment/generation against new
mutation admission, then drains every already accepted queued and active
mutation for it. Only after the outer count reaches zero may registry release
call Datahike's existing final release. This matches Datahike's own rule that
accepted writes finish or fail before store release.

Authority shutdown closes new mutation admission globally, drains accepted
mutations, then releases connections. A fatal process or storage failure may
prevent responses, but it cannot be repaired by an in-memory queue protocol;
the existing durable request receipt and same-ID retry are the recovery seam.

## Memory bounds

Job counts alone do not bound memory. The current transport accepts a 16 MiB
Transit frame. Eight decoded requests for one database can therefore represent
at least 128 MiB of wire payload before Clojure object overhead; the decoded
maps, strings, vectors, and boxed values may retain substantially more. The
Datahike default of 240,000 combined queue slots has no meaningful byte bound.

Account the encoded payload length from the frame as the conservative retained
request size before discarding the input bytes. This is available without a
second serialization pass. If later measurement shows decoded expansion is a
problem, charge `max(frame-bytes, measured-shallow-size)`; do not walk the
transaction twice on every request merely to estimate it.

Use the protocol's planned 4 MiB hard frame maximum and semantic paging/import
operations for larger work. Initial mutation limits are:

| Available processors | Active across process | Ready across process | Ready per database | Ready bytes across process | Ready bytes per database | Datahike transaction / commit queue |
|---:|---:|---:|---:|---:|---:|---:|
| 2 | 1 | 8 | 8 | 8 MiB | 8 MiB | 32 / 32 |
| 4 | 2 | 8 | 8 | 16 MiB | 8 MiB | 32 / 32 |
| 8 | 4 | 16 | 8 | 32 MiB | 8 MiB | 32 / 32 |

`Ready` excludes active work. Active requests are separately bounded by the
small process count and the 4 MiB frame maximum, so worst-case retained
mutation request payload is bounded approximately by `ready bytes + 4 MiB *
active`, before decoded-object overhead: 12, 24, and 48 MiB at 2, 4, and 8
processors. The per-database byte bound allows two maximum-size requests; job
count matters for ordinary small transactions.

These are measurement defaults. Do not increase them because a benchmark can
generate a deeper queue: queue depth converts latency into heap retention and
cannot raise one connection's commit throughput.

## Fairness and resilience

Round robin is needed only when more databases are ready than the global active
limit. It is not used inside one Datahike connection. A database with eight
queued writes receives one active position, then the dispatcher rotates to the
next ready database. With capacity remaining, another independent database
starts immediately. This prevents one hot cluster from occupying every active
mutation position while preserving each database's Datahike order.

Constant-time capability, health, cancellation observation, and lifecycle
fencing bypass the mutation queue. Release's drain wait does not occupy a CPU
worker. Slow storage for database A consumes one mutation position but not all
positions on 4/8-core hosts; database B can commit through its own connection.

Holding an active position through storage wait is intentionally conservative.
Splitting transaction computation from commit wait would require a new
Datahike admission/completion seam and could allow many computed DB values to
accumulate in its commit queues. Only pursue it if measurement shows storage
wait leaves material safe parallelism unused after independent databases are
already concurrent.

## Shortest falsifiers and graduation proof

The implementation is wrong if any of these fail:

- **Same database order:** block the first commit, submit three writes, and
  prove their transaction facts and completions retain admission order.
- **Independent progress:** block database A's commit, submit a write to B,
  and prove B commits before A unblocks when active capacity is at least two.
- **Fair saturation:** continuously refill A while B and C each queue one;
  prove B and C start within one database rotation.
- **Bounds:** submit maximum-size and many small requests; prove ready jobs and
  bytes never exceed both global and per-database limits and no socket-worker
  pile-up exists behind a connection lock.
- **Definite overload:** fill admission without executing, submit one more,
  and prove an existing error envelope returns without calling `d/transact`.
- **Disconnect ambiguity:** disconnect after acceptance, reconnect, retry the
  same request ID, and prove exactly one logical transaction plus recovered
  response. Reuse with changed data must conflict.
- **No mutation rollback claim:** cancel queued and running mutations and prove
  they still commit while cancellation evidence never reports canceled.
- **Release fence:** begin release with queued and active writes; prove new
  admission fails, accepted writes settle, Datahike release begins last, and no
  connection/index/store reference survives.
- **Fatal writer failure:** force commit failure, prove every accepted request
  settles as failure, new writer admission closes, and restart plus same-ID
  retry recovers any durable winner.
- **Batch semantics:** admit several same-database writes and prove Datahike may
  return one commit ID for several reports; no Seon assertion assumes distinct
  commits.
- **Density:** run 2/4/8-core configurations with many idle databases and hot
  databases; record p50/p95/p99 admission-to-commit latency, commits/s, queued
  jobs/bytes, heap after GC, allocation rate, Datahike batch sizes, storage
  wait, and control-request latency.

## Implementation boundary

Strengthen `seon.db.executor` in place into the already selected shared host
dispatcher. Add mutation class limits and per-database active limit one; route
`handle-transact` through it. Pass `:writer {:backend :self
:transaction-queue-size 32 :commit-queue-size 32}` when registry constructs
each Datahike config. Do not add a mutation executor namespace, a second
receipt, a transaction broker, or a global writer.

The persistent multiplexed Unix transport should call an asynchronous
completion-driven `handle-request!`; it must not allocate one platform thread
per blocked mutation. This admission contract remains valid during that atomic
transport replacement and is the required backpressure seam for it.
