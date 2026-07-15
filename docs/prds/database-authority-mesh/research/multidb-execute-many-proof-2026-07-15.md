---
type: research
status: active
tags: [research, prd, database, flow, agent]
---

# Multi-database execute-many proof — 2026-07-15

## Scope and status

This approved proof spike used disposable in-process JVM harnesses with real
Datahike memory databases. It proves mechanisms and exposes contract questions;
it does not implement or freeze Seon architecture.

The harness ran on JDK 26.0.1 with Datahike
`9ada755087228e10cfb179fa5779ce227a6ed220`. It created independent
`:memory` stores using distinct UUIDs, `:schema-flexibility :read`, persistent-set
indexes, and self writers. Every connection and database was released/deleted in
the disposable process. No Seon source, tests, sockets, or lifecycle ran.

## Source inventory

- `reference-code/datahike/src/datahike/writer.cljc:76-251,264-300` — one
  transaction queue, commit queue, processing loop, commit loop, and in-flight
  set per connection;
- `reference-code/datahike/src/datahike/query.cljc:2388-2427,2559-2615,
  4000-4085` — bounded query cache, cache lookup/put/propagation, and operation
  budgets;
- `reference-code/datahike/src/datahike/resource.cljc:1-151` — timeout,
  cancellation, work, result-node, value, and result-weight enforcement;
- `reference-code/datahike/src/datahike/writing.cljc:227-285` — immutable
  database materialization and attached index roots;
- `reference-code/datahike/src/datahike/connector.cljc:270-408,438-510` —
  connection acquisition and final release;
- `src/seon/db/transport/uds.clj:127-232` — current synchronous per-channel
  request loop and concurrent connection workers;
- `src/seon/db/writer.clj:1107-1180` — current database-scoped dispatch; and
- [[replica-lifetime-fairness-2026-07-15]] and
  [[datahike-resource-lifetime-2026-07-15]] — preceding resource and fairness
  constraints.

## Harness data and limitations

The four-database phase seeded 1,000 entities in each database. Entities carried
`:item/id` and `:item/value`; later writes added ordinary namespaced attributes.
The execute-many phase separately seeded 1,500 entities in one database and ran
real `q`, `pull`, and scalar query members over one dereferenced immutable value.
The cancellation phase used 3,000 entities.

Reported timings are single-run proof evidence after JVM startup, not a
benchmark suitable for capacity selection. Memory stores omit filesystem and
remote-store latency. Query shapes are small, the host is powerful, no UDS or
Transit work is included, and p95/p99 over nine short tasks are order statistics,
not distribution estimates. The proof is valuable for overlap, ordering,
identity, and scheduler topology only.

## Proof 1 — execute-many uses one immutable database value

The harness dereferenced one connection exactly once, retained its basis
transaction, and launched three Futures over that same immutable value:

1. count every entity carrying `:item/id`;
2. pull entity id 43; and
3. query the value for `:item/id 1499`.

Observed result:

```clojure
{:probe :execute-many
 :basis 536870913
 :results [1500 42 "1499"]
 :elapsed-ms 141.486708}
```

All members saw one database value at one basis. They returned independently
typed ordinary data. The elapsed time includes Future startup and query
compilation/cache behavior, so it is not a parallel speedup claim.

An initial falsifier attempted `(pull db '[*] [:item/id 42])` without declaring
`:item/id` unique. Datahike correctly rejected the lookup ref. The successful
probe used numeric entity id 43. The remote interface must preserve Datahike's
lookup-ref rules instead of silently treating arbitrary attribute/value pairs as
identities.

### Candidate execute-many request

```clojure
{:seon.db.protocol/operation
 :seon.db.protocol.operation/execute-many
 :seon.db.protocol/request-id request-id
 :seon.db.protocol/database-name database-name
 :seon.db.protocol/coordinate coordinate
 :seon.db.protocol/members
 [{:seon.db.protocol.member/id :count-items
   :seon.db.protocol.member/operation :seon.db.operation/query
   :seon.db.protocol.member/request {...}}
  {:seon.db.protocol.member/id :item
   :seon.db.protocol.member/operation :seon.db.operation/pull
   :seon.db.protocol.member/request {...}}]
 :seon.db.protocol/deadline deadline
 :seon.db.protocol/budget {...}}
```

Names are illustrative. The contract is more important:

- resolve one attachment and immutable coordinate once;
- every member receives that exact value;
- independent members may execute concurrently under bounded query permits;
- member IDs correlate results without relying on completion order;
- each member returns success or an error value; request policy decides whether
  one failure cancels siblings;
- the outer request carries a deadline and aggregate work/result/byte limits;
- cancellation stops admission immediately and reaches running Datahike work;
  and
- encoding/delivery occurs after database compute permits are released.

This is transport composition of existing database operations, not a second
query language and not JVM-hosted application rendering.

## Proof 2 — identical in-flight work can coalesce

An isolated in-flight map keyed by the immutable database identity fragment and
request key admitted eight concurrent callers. The first installed a promise,
computed a real Datahike count query after a 30 ms delay, delivered the value,
and compare-removed the promise. Other callers awaited it.

```clojure
{:probe :coalesce
 :callers 8
 :computes 1
 :distinct 1
 :elapsed-ms 33.027167}
```

This proves the small state transition, not the final cache key. Task 1 must
still settle physical database, branch, temporal wrapper, and speculative-value
identity. The coalescer must live adjacent to Datahike's result cache, remove a
failed owner, distinguish owner cancellation from waiter cancellation, and
never store Future/Promise values as completed query results.

Execute-many coalescing occurs per member. Two members or two requests with the
same semantic query and immutable value may share one computation while keeping
separate deadlines and result envelopes.

## Proof 3 — writes are ordered per connection, not globally

### One connection

Twelve concurrent transaction calls targeted one connection. All 12 reports
completed. Their returned `db-after` transaction values were:

```clojure
[536870914 536870915 536870915 536870915 536870915 536870915
 536870915 536870915 536870915 536870916 536870916 536870916]
```

The final connection was at `536870916`. This is correct Datahike behavior: the
connection processing loop orders accepted mutations, while the commit loop
batches queued transaction reports and publishes one committed database value
to multiple callbacks (`datahike/writer.cljc:194-227`). Therefore:

- one connection has one ordered mutation lane;
- multiple requests may share one durable commit coordinate;
- request identity and receipt/idempotency cannot be inferred from `t`; and
- the protocol must not promise one distinct commit for every transaction call.

The harness did not record internal queue admission sequence, so it does not
claim spawned Future order equals mutation order. Correct ordering evidence is
the connection's serialized processing and monotonically advancing committed
values.

### Two independent connections

Two databases concurrently transacted 1,200 new entities each:

| Database | Duration ms | Committed t |
|---:|---:|---:|
| 2 | 28.635 | 536870914 |
| 3 | 28.417 | 536870914 |

Combined wall time was 28.845 ms, individual durations summed to 57.051 ms, and
measured interval overlap was 28.380 ms. Nearly the entire write lifetime
overlapped. This directly falsifies a global writer lane: separate Datahike
connections can process and commit independently.

Shared CPU, GC, memory bandwidth, or a common external storage backend may
still contend. Those are bounded resource classes, not semantic write ordering.

## Proof 4 — fair per-database selection prevents gatekeeping

The four-database harness used a shared fixed pool of four workers. Database A
submitted 12 cold tasks; each ran ten real uncached relation queries over its
1,000-entity immutable value. Database B submitted six scalar queries and three
real writes.

Two admission sequences were compared:

- global FIFO: all long A work entered before B; and
- database-aware interleave: one A then one B while B had work.

| Admission | B p50 ms | B p95 ms | B p99 ms | Total wall ms | Unfinished |
|---|---:|---:|---:|---:|---:|
| Global FIFO | 212.6 | 214.5 | 214.5 | 214.5 | 0 |
| Per-database fair | 15.2 | 26.1 | 26.1 | 35.3 | 0 |

The unusually lower fair total is likely affected by JIT, cache warmth, and
contention order; it must not be presented as an 83% throughput improvement.
The reliable result is that B completed roughly an order of magnitude earlier
when database selection preceded shared-pool submission.

The scheduler must therefore have no global request queue. Each database owns
ready work. A fair selector chooses a database only when the required work-class
capacity is available.

### Simplest implementable fairness owner

Weighted deficit round robin is the strongest candidate when work estimates
vary:

1. every active database has a queue per work class;
2. each selection round adds that database's configured quantum;
3. a request runs when its estimated cost fits the deficit and a class permit;
4. actual Datahike work/result/CPU/byte evidence corrects the estimate; and
5. age and a nonzero quantum guarantee background progress.

Strict round robin is a simpler first falsifier but treats a one-row pull and a
million-node history query equally. One generic FIFO executor is rejected.

## Proof 5 — cancellation must enter running Datahike work

An isolated cooperative loop repeatedly ran a real uncached 3,000-entity query
and checked an `AtomicBoolean` between members. Cancellation arrived after 20
ms:

```clojure
{:probe :cooperative-cancel
 :result :cancelled
 :members-completed 1
 :cancel-latency-ms 115.283083
 :total-ms 140.976709}
```

The 115 ms delay proves that checking only between execute-many members is not
enough. Datahike already supports a cancel signal and resource budget within
query execution (`datahike/query.cljc:4000-4014`; `datahike/resource.cljc`). The
authority must translate request cancellation and deadlines into that existing
signal for each running member. Pull and heavy projection paths require the same
budget propagation.

Cancellation semantics should be ordinary data:

- queued member: remove before execution and refund admission cost;
- coalesced waiter: detach that waiter without cancelling an owner needed by
  others;
- sole computation owner: signal Datahike and remove failed/incomplete in-flight
  state;
- transaction already accepted by the ordered writer: do not claim rollback;
  return its final receipt/coordinate when known; and
- encode/delivery: stop retaining bytes when no recipient remains.

## Separate bounded work classes

Maximum parallelism does not mean one unbounded executor. The authority should
model at least these independently bounded classes, selected fairly by database:

| Work class | Examples | Bound and isolation |
|---|---|---|
| Query CPU | Datalog, pull, history, index scans, execute-many members | Core-sized pool/permits; Datahike work/result/deadline budget |
| Blocking provider | Embedding provider calls, remote object storage | Virtual tasks plus connection/request concurrency and byte bounds |
| Secondary/KNN | HNSW/Lucene/native search, index build or projection | Separate measured permits because native memory and SIMD CPU differ |
| Encode/delivery | Transit/compression/socket writes | Byte-weight permits; never hold query permits while a client is slow |
| Ordered mutation | Final Datahike transactions and index installation | Existing writer per connection; independent across databases |
| Lifecycle/control | cancel, health, release, capabilities | Small separately bounded capacity that heavy work cannot consume |

An embedding or KNN job for database A receives an addressable request/job ID,
deadline, cancellation signal, and its class permit. It may produce ordinary
data or a final transaction request, but that mutation enters database A's
existing ordered writer. It cannot occupy database B's query, writer, encode,
or lifecycle capacity merely because it is blocked on a provider or using a
secondary index.

Separate classes are not fixed thread pools for every database. Databases have
logical queues; classes share bounded executors/permits across databases under
fair selection. Unused KNN capacity need not reserve platform threads, and a
future policy may allow safe borrowing while retaining hard floors for query,
mutation, and control.

### Heavy A versus interactive B acceptance proof

The retained harness did not invoke an embedding provider or KNN index, so this
is a required next falsifier rather than measured proof:

- saturate A's provider and KNN bounds with long jobs;
- continuously issue B scalar queries, pulls, writes, cancels, and health;
- prove A uses zero B query/write/control permits;
- prove B p95/p99 remains inside its target;
- prove A cancellation releases provider/native/byte ownership; and
- prove any A result mutation is ordered only through A's writer.

No generic `Future` queue may obscure these classes.

## Async remote integration contract

Bun clients see one asynchronous, data-shaped authority interface:

1. submit returns or establishes an addressable request ID immediately;
2. request data names database, coordinate, operation/member IDs, deadline,
   budgets, and supported capability—not JVM executors;
3. the authority validates before admission and returns bounded queue evidence;
4. progress, completion, cancellation, and failure are correlated data;
5. one session may multiplex independent requests so a long request does not
   block the channel;
6. backpressure bounds request count and bytes before decoding/admission;
7. execute-many members share one immutable value but have explicit individual
   outcomes;
8. mutations return request receipt and committed coordinate without implying a
   unique commit per request; and
9. disconnect releases delivery ownership and session-local handles, while
   admitted mutation semantics remain truthful.

The CLJS/Bun wrapper may offer `await`, but it must never fake synchronous
network access. Agent-facing and durable values remain namespaced ordinary data.

## Metrics required in the retained implementation proof

Every operation records timestamps for decode, validation, per-database queue,
class-permit wait, execution, single-flight wait, writer acceptance/commit,
encode wait, encode, delivery wait, and completion.

Aggregate by database, work class, operation, cache state, and outcome:

- p50/p95/p99 queue, execution, cancellation, and total latency;
- admitted/queued/running/completed/cancelled counts and weighted depth;
- estimated versus actual Datahike work, result nodes/weight, CPU, allocations,
  and bytes;
- query-cache hit, single-flight owner/waiter, and computation count;
- writer queue depth, batch size, request receipts, distinct commits, and commit
  overlap across databases;
- executor/virtual-thread count, carrier pinning, permit use, and starvation;
- provider/KNN concurrency, native memory, and index-build state;
- encoded/compressed bytes and slow-recipient retention; and
- lifecycle/control latency while every heavy class is saturated.

The retained benchmark must run 2 and 4 databases first, then 8, with repeated
warm/cold trials and enough samples for meaningful tail percentiles.

## Decision briefs for Sean

1. **Execute-many failure semantics:** return every member outcome, or fail-fast
   and cancel siblings? Per-member outcomes preserve more completed work; fail-
   fast bounds wasted work when members are dependent.
2. **Default database weight:** equal per database remains the safest baseline.
   Decide whether active UI work receives a bounded extra quantum with a
   guaranteed background floor.
3. **Class borrowing:** reserve hard minimum capacity for query, mutation, and
   lifecycle, or permit idle heavy-class capacity to be borrowed under a global
   ceiling?
4. **Transaction cancellation:** once accepted by Datahike's writer, should the
   client always await/receive the durable receipt asynchronously even after its
   original session disconnects?
5. **Coalesced owner cancellation:** continue computation while any waiter
   remains, or nominate a replacement owner? Continuing is simpler; replacement
   may preserve caller-specific deadlines.

## Proof conclusion

The spike supports a concrete direction without freezing the final PRD:

- one immutable database value can serve concurrent query/pull members;
- identical in-flight reads can compute once at the Datahike cache seam;
- writes serialize and batch per connection;
- independent database writes overlap almost completely in the memory harness;
- database-aware selection prevents a long database from gatekeeping a short
  database; and
- cancellation, heavy projections, encoding, and lifecycle need distinct
  bounded ownership rather than one executor.

The next retained proof should implement only the scheduling harness/API seam,
not production protocol code, and collect repeated distributions plus real
provider/KNN isolation before Sean chooses weights and failure semantics.
