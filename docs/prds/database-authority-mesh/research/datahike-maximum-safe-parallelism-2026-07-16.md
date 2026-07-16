---
type: research
status: completed
tags: [research, prd, database, flow]
---

# Datahike maximum safe parallelism — 2026-07-16

## Decision

Parallelize at two existing Datahike seams:

- one independent Datahike writer per connection orders that database's
  mutations; writers for different connections run concurrently; and
- bounded authority workers run complete query, pull, temporal, index, and
  KNN operations concurrently over one captured immutable database value.

Do not put one JVM-wide lock or request queue in front of Datahike. Do not
parallelize the transaction function for one connection, clauses inside one
query, frames inside one pull, or datoms inside one ordinary secondary-index
transaction. Those are dependency-owned units whose internal order carries
database semantics or whose implementations already use invocation-local
transients.

The authority should resolve a requested commit once, retain that exact
database value while its work is admitted or running, and relinquish it only
after every operation has settled. Final connection release closes admission,
cancels cooperative queries, drains every user of the exact generation, shuts
down its writer, closes its secondary indexes, and then releases its Konserve
store. This is the smallest design that obtains parallel reads and independent
database writes without copying indexes or racing native resource close.

## Dependency ledger

- Datahike `092f5b0580c892c32b1dc65bf9acdbe37db90c4f`:
  `writer.cljc`, `writing.cljc`, `connector.cljc`, `connections.cljc`,
  `db.cljc`, `query.cljc`, `query/single_flight.cljc`, `pull_api.cljc`,
  `db/transaction.cljc`, and `index/secondary.cljc`.
- Persistent sorted set
  `e1a17bbe767c7801e67407c81f64efabfd2f1601`:
  Datahike's `index/persistent_set.cljc` adapter and
  `PersistentSortedSet.java`.
- Konserve `df6818d43ea3363a808cd051c0d68917f1b987a9`:
  store protocols, cache, and selected backend lifecycle.
- Proximum `9846d3e79e1aee48474bc876d3d563d7137209c6`:
  `hnsw.clj`, `vectors.clj`, `PersistentEdgeIndex.java`, `HnswInsert.java`,
  and Datahike's `src-secondary/.../proximum.clj` adapter.
- Existing mechanism reports:
  [[datahike-parallel-read-internals-2026-07-16]],
  [[execute-many-value-reuse-2026-07-16]], and
  [[single-jvm-host-capacity-2026-07-16]].

## What must remain ordered

### Mutations of one connection

`LocalWriter` owns one transaction queue and one commit queue per connection
(`writer.cljc:45-92`). Its processing loop threads `old` to `:db-after` in
queue order (`writer.cljc:100-183`). Its commit loop drains a batch, persists
the final database value, publishes the committed cache generation, resets the
connection atom once, and then resolves every transaction callback
(`writer.cljc:196-239`).

That is the database's write order. The authority may bound admission before
`dispatch!`, but it must not create multiple mutation workers for the same
connection or bypass Datahike's queue. A writer batch may give several accepted
requests one durable commit ID; authority request identity and transaction
receipts remain distinct from commit identity.

The default Datahike transaction and commit buffers are each 120,000 entries
(`writer.cljc:78-91`). They are containment hazards, not useful concurrency.
Bound mutation admission at the authority and configure the dependency queues
small enough that a failed or slow database cannot retain hundreds of thousands
of requests.

### One transaction's primary and secondary changes

The transaction function builds one successor immutable database. Secondary
updates are part of that construction. `ISecondaryIndex/-transact` must return
an updated persistent index; the optional transient protocol makes one index
mutable for the transaction batch and freezes it at the end
(`index/secondary.cljc:15-61`). Parallelizing datoms outside that protocol
would violate transient ownership and secondary ordering.

Proximum already performs its own graph construction parallelism when a batch
operation chooses it. Its HNSW bulk path uses `HnswInsert/insertBatch`
(`proximum/hnsw.clj:510-577`), whose current static `ForkJoinPool` defaults to
half of available processors (`HnswInsert.java:15-41`). The authority must
eventually supply and bound this dependency-owned parallelism; it must not wrap
construction in another pool and multiply runnable threads.

### Commit and publication

Index flush, durable branch-head advancement, cache propagation, connection
atom reset, committed-report offer, and callback delivery form one causal
sequence (`writer.cljc:218-239`; `writing.cljc:465-470`). They may proceed
independently for different database connections, but not be reordered within
one connection.

### Release and close

The final connection releaser atomically wins ownership in
`connections.cljc:11-35`. It closes query-cache/event admission before draining
the writer, closes secondary indexes only after accepted writes settle, and
releases the store last (`connector.cljc:483-538`). This order is load-bearing:
a queued mutation may still touch a secondary and both primary and secondary
indexes may still address the store.

No operation may use a database value concurrently with closing one of its
resource-owning secondary indexes. The current final connection release is
correct only for the live head after the authority has drained external reads.
Historical `commit-as-db` values currently restore their own Closeable
secondary owners without a matching public close operation
(`versioning.cljc:414-433`; `writing.cljc:182-245`). That gap is recorded in
[[historical-db-secondary-index-lifetime]] and must be fixed before historical
KNN graduates.

## What can run concurrently

### Independent database writers

Connection identity and reference counts are entries in one atom, but each
published connection owns its own writer (`connections.cljc:37-111`;
`connector.cljc:362-395`). There is no process-wide transaction loop. Database
A and database B can process and commit concurrently, subject only to actual
shared backend, CPU, and I/O capacity.

The authority therefore selects mutation work fairly by database only for
bounded admission. Once admitted, Datahike's per-connection writer owns its
order. A database whose writer fails must be marked unavailable and reconnected
without stopping admission to healthy database writers.

Two logical databases that address the same physical backend can still contend
inside that backend. Datahike deliberately shares write-hook ownership for a
physical store (`connections.cljc:59-73`), and a secondary adapter may impose a
native per-branch lock. Independence means no Datahike global gate, not infinite
storage bandwidth.

### Reads of one captured immutable database value

`DB` is an immutable record whose search and index-access methods delegate to
persistent indexes (`db.cljc:307-383`). Query evaluation is synchronous
caller-thread work (`query.cljc:4179-4246`). Pull uses an invocation-local frame
stack and invocation-local transients (`pull_api.cljc:12-214`); no pull state is
stored on the DB. Complete queries and pulls can therefore run concurrently on
the identical safely published database value.

The persistent-set adapter shallowly attaches immutable trees to a
connection-owned cached storage. Readers share tree nodes and the store cache;
that avoids index copies, but cold readers may contend on cache and backend I/O.
This is a capacity effect, not a correctness reason to serialize them.

Eight independent aggregate queries over one warmed 100,000-entity database
were previously probed on this exact dependency line. Sequential execution was
955.2 ms; eight futures over the identical DB completed in 174.6 ms, a 5.47x
wall-time improvement. The probe establishes useful operation-level
parallelism, not an eight-worker production default.

### Reads while the writer advances head

A reader that captures `@connection` owns an immutable old value. The writer
constructs and later publishes a distinct committed value with one `reset!`
(`writer.cljc:210-232`). Reads of the captured value do not need a read lock and
remain coordinate-consistent while the connection advances.

The authority must capture once. Re-dereferencing the connection at several
steps creates several valid but different values; it is not a data race, but it
breaks the caller's requested coordinate.

### Distinct and identical queries

Distinct query keys execute independently on their caller threads. Identical
cacheable misses already join Datahike's single-flight owner, and completed hits
use the global weighted result cache (`query.cljc:2409-2468,4179-4246`). Seon
must not add a second query cache or duplicate-work registry.

The result-cache atom is process-global. Heavy hot-hit traffic can cause atom
CAS retries across otherwise independent databases. Measure that contention
before sharding; do not infer that all queries need a global queue.

Resource-bounded queries (`max-work`, `max-results`, or `max-result-weight`) are
currently deliberately uncacheable and bypass single-flight
(`query.cljc:4193-4204`). They are safe to run concurrently but can duplicate
identical computation. Any future sharing must preserve each caller's resource
contract rather than joining incompatible limits.

### KNN and other secondary reads

The generic `ISecondaryIndex` protocol does not assert thread safety. Every
adapter must qualify its own read and close contract before it enters shared
read capacity.

The selected Proximum HNSW implementation explicitly specifies lock-free,
thread-safe search and thread-safe locked inserts (`HnswIndex.java:41-44`). Its
Datahike adapter captures the current immutable Proximum value from an atom for
each search (`src-secondary/.../proximum.clj:137-165,175-189`). Transaction
updates return a fresh adapter/index value (`proximum.clj:289-310`). Multiple
KNN searches over one captured owner can run concurrently.

Proximum's adapter also mutates its private `!idx` during durable flush so the
next transaction sees the synced immutable value (`proximum.clj:109-151,
193-245`). That mutation stays inside Datahike's one writer. Search may observe
either safely published immutable value. Close blocks until Proximum cleanup
settles (`proximum.clj:145-151`), so authority release must first drain KNN
users of that exact owner.

Do not assume another secondary adapter has this contract. Scriptum, Stratum,
or future embedding indexes need the same source-level proof for concurrent
search, transaction replacement, and close.

## Failure isolation and resilience

### Request-local failures

Query, pull, and KNN exceptions occur on the authority worker that called the
synchronous dependency operation. They fail that request. A bounded executor
must catch them as ordinary error data, relinquish the database value in
`finally`, and keep its worker and unrelated databases available.

Query cancellation is cooperative through Datahike's active request identity.
Pull and current Proximum KNN have no equivalent common cancellation signal.
Cancel can abandon their result, but release must still wait for the actual
operation to return before closing resources. The protocol must report this
distinction truthfully.

### Writer failures

An ordinary transaction exception resolves that invocation and the processing
loop continues. A fatal `Error` closes that connection's queues; a durable
commit failure is terminal for that writer and resolves every accepted callback
(`writer.cljc:111-193,240-250`). The writer object does not intentionally stop
other connection writers or the JVM.

The authority should isolate the failed database entry, reject new mutations
for it, preserve its last durable coordinate, and reconnect according to an
explicit policy. It should not restart healthy connections. Process-wide
failures such as `OutOfMemoryError`, corrupted shared runtime state, or JVM
termination remain process failures; a supervising Bun cluster or observer can
reconnect every session after the JVM restarts.

### Slow or disconnected clients

Client delivery must not execute in a writer callback or retain a database
value. Materialize ordinary data, relinquish the value, and then encode/page it
under separate byte bounds. A disconnected session cancels its queued and
cooperative read work; accepted mutations are recovered through transaction
receipts rather than described as rolled back.

### Release races

The authority needs an exact-generation fence ahead of Datahike release:

1. reject new work for that database generation;
2. remove queued operations and detach cooperative query callers;
3. wait for running operations and request-lifetime database values;
4. call Datahike release, which drains accepted writes;
5. close historical materializations owned outside the live connection; and
6. remove the registry entry only for that generation.

A reconnect creates a new generation (`connections.cljc:67-73`). Cleanup for
the old generation must never address the replacement.

## Implementation seam

Use one authority dispatcher with bounded work classes, not one executor per
operation or database:

- lifecycle and cancel stay constant-time and bypass heavy work;
- mutation admission is selected fairly by database, then dispatched to that
  database's existing Datahike writer;
- read workers execute whole query, pull, temporal, index, and qualified KNN
  calls over captured values;
- Proximum construction consumes authority-supplied CPU capacity instead of a
  hidden nested pool;
- provider calls and response encoding have distinct non-CPU/byte bounds but
  never acquire database writers; and
- per-database queues plus global active limits prevent one cluster from
  retaining all work or memory.

Fairness decides which database starts next only when capacity is contested.
It does not round-robin a single database's writes, add a global transaction
order, or migrate one operation between database values.

## Verification performed

The focused current-source gate ran the stress, release, cache/single-flight,
and writer-failure namespaces across persistent-set, hitchhiker-tree, and specs:

```text
104 tests, 392 assertions, 0 failures.
```

The retained tests cover parallel reads and writes, identical concurrent query
joining, independent cache evidence, final-release fencing of a late cache put,
release waiting for accepted writes and asynchronous writer operations,
concurrent releasers, failed-open secondary cleanup, and fatal writer failure
settling accepted callbacks. This proves the current dependency contracts; it
does not replace authority-level fairness and density measurements.

## Implementation acceptance tests

### Ordering and parallel progress

- Submit at least 32 mutations to one database from several sessions and prove
  the durable results match Datahike writer order; batching may share commit IDs.
- Hold database A's commit path, transact database B, and prove B commits before
  A is released.
- Run distinct CPU-heavy reads over one identical DB with 1, 2, 4, and 8
  authority workers; prove equal results and retain the throughput/latency curve.
- Run the same matrix across 1, 2, 4, and 8 databases and prove per-database
  progress under one saturated database.
- Submit identical cacheable misses from 2, 8, and 32 sessions; prove one
  Datahike owner, independent response completion, and zero retained flight.

### Coordinate and lifetime

- Capture head T, advance to T+1, and concurrently query/pull/KNN T; every
  operation must return T data while new unpinned work sees T+1.
- Resolve one historical value for execute-many, prove every member receives
  the identical DB and index objects, and prove exactly one relinquish after
  success, member failure, outer cancellation, and disconnect.
- With Proximum plus a second Closeable fixture, repeat historical requests and
  prove opened-minus-closed returns to zero; head secondary owners close only
  at final connection release.
- Race final release with queued, running, single-flight owner/waiter, pull, and
  KNN work. Release must wait for actual users, return zero retained work, and
  never close a reconnected generation.

### Failure isolation

- Make database A's transaction function throw an ordinary exception; its next
  valid mutation and all database B work still succeed.
- Inject a fatal writer/commit failure into A; every accepted A callback settles,
  new A mutations reject, and B reads/writes continue without JVM restart.
- Throw from query, pull, KNN, materialization, encoding, and client delivery;
  each request becomes error data and releases its DB/value/byte accounting.
- Disconnect a session with queued and running reads plus an accepted mutation;
  queued work disappears, cooperative query work detaches, pull/KNN drain
  without delivery, and mutation outcome is recoverable by receipt.

### Capacity and retained memory

- Measure CPU, allocation, RSS, GC, queue depth, cache contention, cold storage
  reads, KNN native memory, and p50/p95/p99 latency at 2, 4, and 8 available
  processors.
- Verify global and per-database admission bounds by filling every work class;
  retained requests/bytes must stop growing and lifecycle/cancel must remain
  responsive.
- Verify Proximum construction never creates runnable work beyond the one
  authority CPU ceiling.
- After repeated acquire/read/release and writer-failure/reconnect cycles,
  connection references, cache generations, flights, secondary owners, file
  descriptors, mapped bytes, and authority queue counters return to baseline.

## Remaining measured choices for Sean

The source settles semantic seams, not deployment constants. Keep Sean involved
in these measured tradeoffs:

- leave one processor for Bun/OS versus use every processor on a dedicated
  authority host;
- read/KNN active limits on two-core modest hardware;
- whether hot result-cache atom contention justifies authority sharding;
- Proximum search breadth and construction capacity while interactive reads are
  queued; and
- mutation, response-byte, and historical-value bounds for the target cluster
  density.

None requires changing the protocol. They are capacity-map values selected from
the graduation measurements.
