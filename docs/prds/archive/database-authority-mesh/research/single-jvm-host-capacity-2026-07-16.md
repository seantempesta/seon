---
type: research
status: completed
tags: [research, prd, database, flow]
---

# Single-JVM host capacity — 2026-07-16

## Decision

Replace the unrelated fixed read and embedding thread counts with one immutable
host-capacity map, computed once when the authority starts from
`Runtime.availableProcessors()`. Java reports the processors available to this
JVM, including container constraints; it is the right default input. An
explicit operator override remains necessary for shared hosts and repeatable
2/4/8-core measurements.

The map supplies limits to the existing work classes. It does not create a new
pool per database or a second scheduler:

- read, KNN search, response encoding/compression, and HNSW construction share
  one CPU ceiling of `max(1, available-processors - 1)`;
- each class has its own active and queued bounds beneath that ceiling;
- database round robin remains the selection rule inside each ready class;
- lifecycle and constant-time control do not enter a heavy-work queue;
- provider calls use bounded virtual threads and connections, not CPU workers;
- mutation admission is bounded before the existing per-connection Datahike
  writer, which remains the sole ordering and commit owner; and
- one HNSW construction may run in the process and must use authority-supplied
  parallelism instead of Proximum's static physical-core pool.

This is less machinery than independent fixed executors. One startup value,
one fair dispatcher, and explicit class limits replace the current read pool,
embedding scheduler pool, embedding provider pool, hidden Proximum pool, and
frame-count-only delivery queues.

## Exact source constraints

### Datahike reads and writes

Datahike query execution is synchronous on its caller at
`reference-code/datahike/src/datahike/query.cljc:4179-4205`; pull is likewise a
synchronous invocation-local walk at
`reference-code/datahike/src/datahike/pull_api.cljc:264-359`. Immutable indexes
therefore need CPU admission, not another asynchronous dependency layer.
Identical queries already join the Datahike single-flight owner, so a second
authority cache or duplicate registry would only add retention and contention.

Every Datahike connection owns one ordered writer, but not two permanent OS
threads. `reference-code/datahike/src/datahike/writer.cljc:85-263` implements
the processing and commit loops as parked core.async work. On the selected
core.async and JDK 26, `go` dispatch defaults to virtual threads:
`reference-code/core.async/src/main/clojure/clojure/core/async/impl/dispatch.clj:55-127`
selects the virtual-thread-per-task executor for I/O, and
`reference-code/core.async/src/main/clojure/clojure/core/async.clj:520-570`
uses it when virtual threads are available. More databases therefore do not
justify more authority CPU workers.

The dangerous retained bound is instead Datahike's default transaction and
commit queue size of 120,000 each at
`reference-code/datahike/src/datahike/writer.cljc:78,281-299`. Across many
databases this is not a modest-hardware policy. Seon should admit a small
bounded number before `d/transact`; the internal queues then cannot approach
their generic library defaults. Datahike continues to batch accepted commits
and serialize each connection; independent connections still commit in
parallel.

### Provider calls

`src/seon/db/writer.clj:1545-1560` currently starts six embedding scheduler
threads. A selected job then calls `seon.embed/embed-texts`, which owns another
six-thread `ThreadPoolExecutor` and a 64-batch queue at
`src/seon/embed.clj:594-637,795-835`. One embedding can therefore occupy one
scheduler thread while creating up to six more provider workers: twelve
platform threads before the SDK's resources.

The Java GenAI `Client` is explicitly thread-safe at
`reference-code/java-genai/src/main/java/com/google/genai/Client.java:29`.
Its `ApiClient` supports `maxConnections` and `maxConnectionsPerHost` through
OkHttp's dispatcher at
`reference-code/java-genai/src/main/java/com/google/genai/ApiClient.java:279-305`
and shuts that executor and connection pool at lines 906-913. The close seam is
already correct. The simpler owner is one fair provider admission limit,
virtual-thread execution, and an SDK connection limit with the same value.
Delete the nested batch executor. Retry backoff parks only a virtual thread,
but retains one provider permit so retries cannot amplify admission or defeat
the upstream rate limit.

Embeddings remain repairable asynchronous derived data. A full provider queue
rejects new repair work without changing the primary transaction result; the
existing document-hash scan repairs it later. This makes a small queue safer
than retaining arbitrary request bodies.

### Proximum native work

KNN search is synchronous CPU and memory-bandwidth work. It should consume one
shared CPU slot and its own KNN active limit. Running search is not interrupt
cancellable, so its class limit must leave progress for other CPU work on
hosts with more than two processors. Existing `SearchOptions` timeout, maximum
distance computations, and patience bounds remain the per-search work seam.

Construction is currently outside authority control.
`reference-code/proximum/src-java/proximum/internal/HnswInsert.java:19-42`
creates one static `ForkJoinPool` sized to half of available processors (or
`proximum.physical_cores`), and lines 281-305 ignore the caller's `parallelism`
argument. The public Clojure defaults independently use all available
processors at `reference-code/proximum/src/proximum/hnsw.clj:530,668`.
An outer construction permit alone therefore does not prevent
oversubscription. The fork must honor the passed parallelism using a supplied
pool or sequential path. Only one construction runs process-wide, and every
fork/join worker it uses counts against the shared CPU ceiling.

### Transit and socket delivery

Current request connections each own a platform thread and synchronously
encode, write, and read the next request at
`src/seon/db/transport/uds.clj:149-230`. Publication encodes one frame and
duplicates `ByteBuffer` views correctly, but bounds each subscriber by 16
frames rather than bytes at lines 267-360. With the 16 MiB frame limit, one
slow subscriber may retain about 256 MiB plus overhead. This defeats any
thread-count tuning.

The persistent multiplexed transport should account bytes before encoding,
encode one immutable response body once, and retain only a session offset per
writer. Encoding/compression consumes a shared CPU slot only for that phase;
socket waiting consumes no CPU slot. Semantic paging is the normal large-result
path. A slow session cannot retain a read, KNN, mutation, or encoding permit.

### JVM capacity facts

On the development JVM inspected for this report,
`availableProcessors()` returned 18 and `ForkJoinPool.commonPool()` reported
parallelism 17. Proximum simultaneously defaults its private construction pool
to nine workers. The current Seon defaults can therefore make eight read
workers, six embedding scheduler workers, six embedding provider workers, nine
construction workers, one platform thread per request/session, and SDK threads
eligible at once. These independent ceilings are the oversubscription bug; a
larger machine merely hides it.

## Initial modest-hardware defaults

These are measurement defaults, not a claim that every workload has the same
optimum. `CPU total` is the only simultaneous CPU ceiling. Class active limits
are caps beneath it, not additional workers. The dispatcher selects another
ready class/database whenever a slot returns.

| Available processors | CPU total | read active | KNN active | encode active | mutation admitted | provider active | HNSW construction workers |
|---:|---:|---:|---:|---:|---:|---:|---:|
| 2 | 1 | 1 | 1 | 1 | 1 | 2 | 1 |
| 4 | 3 | 3 | 1 | 1 | 2 | 4 | 2 |
| 8 | 7 | 7 | 2 | 2 | 4 | 6 | 4 |

`mutation admitted` bounds transactions awaiting the Datahike writer across
the process; each database additionally admits at most one running mutation
into its ordered writer. A mutation occupies CPU capacity only while doing
substantial transaction/index work. The first implementation may conservatively
hold its admission through commit; measurements should split compute from
storage wait only if that retained permit is a demonstrated bottleneck.

Provider limits deliberately do not consume CPU total while blocked in network
I/O. Parsing a large provider response and building assertions re-enters CPU
admission before publication. The provider limit grows more slowly than core
count because upstream quota and batch size, not local cores, determine useful
concurrency.

On two processors, one non-interruptible KNN or encode operation necessarily
delays other CPU work if oversubscription is forbidden. Lifecycle/cancel/health
still progress because they bypass heavy capacity. On four and eight processors,
KNN, encoding, and construction caps leave at least one CPU slot available to
other ready work.

## Queue and byte bounds

Derive job bounds from CPU total rather than database count:

- read ready jobs: global `max(16, 8 * CPU total)`, at most 8 per database;
- KNN ready jobs: global `max(4, 2 * KNN active)`, at most 2 per database;
- encode ready jobs: global `max(8, 4 * encode active)`, at most 4 per database;
- mutation ready jobs: global `max(8, 4 * mutation admitted)`, at most 8 per
  database, with Datahike's internal queue configured no larger than 32;
- provider ready jobs: global `2 * provider active`, at most 2 per database;
- HNSW construction: one running and one replaceable pending request; and
- lifecycle/control: at most 64 decoded requests globally and 8 per session,
  with cancel, health, and release handled inline only while constant-time.

Queue limits count accepted work, including running identity where relevant.
They do not retain completed values. Per-database limits prevent one database
from occupying the global ready bound before round robin has a chance to act.

Use semantic pages with a 256 KiB target and a 4 MiB maximum encoded frame.
Bound queued delivery bytes, including the current partial frame, to 8 MiB per
session and to 32/64/128 MiB globally on 2/4/8 processors. Reject or close a
slow session before exceeding either bound; its ordinary retry from a pinned
coordinate recovers. Compression is configurable, happens once per shared
body, and both uncompressed and compressed sizes count while both are retained.
Loopback remains uncompressed by default.

Job counts are insufficient for query arguments and results. Admission also
needs a small request-byte bound, while result paging and delivery own response
bytes. No queue retains a Datahike value, connection, native index, provider
response, or encoded body after completion/release.

## Safe borrowing and protected progress

Borrowing means selection from one shared CPU dispatcher, not moving permits
between independent executors. Read may use every idle CPU slot. KNN and encode
may use only their class caps. Construction reserves its declared worker count
from CPU total before it starts. No class can borrow provider connections,
mutation ordering, lifecycle admission, or delivery bytes.

There is no reason to reserve idle CPU workers permanently. Protected progress
comes from three simpler rules:

1. lifecycle/control never enters a heavy queue and never performs a heavy
   operation inline;
2. class caps prevent non-interruptible KNN, encoding, or construction from
   occupying CPU total on hosts with at least four processors; and
3. the dispatcher rotates ready work classes, then databases, before assigning
   the next returned CPU slot.

Equal-cost round robin remains sufficient. Do not add deficit, aging, or
weights until measurements show a product requirement that ordinary rotation
cannot meet. Mutation and provider keep separately bounded admission because
they wait on different resources; they are not borrowing candidates.

## Existing evidence names

Extend, do not rename, the evidence already returned by
`seon.db.executor/evidence`: `queued`, `running`, `running-by-database`,
`retained-identities`, `fenced`, `completed`, `rejected`, and `stopped?`.
Report those by existing operation/work class plus the configured active and
queue limits. Delivery adds the already used `frame-bytes` name and queued
bytes. Datahike keeps its existing query cache/resource evidence and request
identity. Proximum keeps `index-metrics` names for live/deleted vectors,
capacity, utilization, edges, branch, commit, and cache hits/misses.

Measurements need queue delay, execution duration, and end-to-end duration,
but these are histograms in the observability owner, not retained job records.
Evidence must never contain a DB, connection, request arguments, result,
Throwable, Future, thread, socket, or native index.

## Smallest owning seam and deletions

Strengthen `seon.db.executor` in place into the one authority dispatcher. Its
startup input receives the immutable host-capacity map; submissions retain the
existing database name, exact scope, request ID, and request data while adding
only the existing protocol operation needed for class selection. Preserve the
current per-database ready queues and fencing. Add global/per-database admission,
class rotation, and byte accounting only where their resource is actually
owned.

Then delete:

- `read-worker-count` and the separate read executor construction in
  `seon.db.writer`;
- the separate six-worker embedding executor in `seon.db.writer`;
- `seon.embed`'s fixed `ThreadPoolExecutor`, 64-batch queue, futures,
  cancellation/purge helpers, and batch-window nesting;
- Proximum's static `PhysicalCoreExecutor` after its batch insertion accepts
  authority-owned parallelism;
- one platform thread per persistent request/session when the native
  multiplexed selector/session owner replaces it;
- publisher frame-count queues after queued-byte/session-offset accounting;
- Datahike replica/broadcast/replay consumers when direct coordinate-pinned
  reads land; and
- every fixed 8, fixed 6, and 120,000-entry Seon runtime default made
  unreachable by authority admission.

Datahike's writer, query single-flight/cache, immutable indexes, and Proximum
index remain their one owners. No replacement writer, query cache, broker, or
second capacity registry is introduced.

## Measurement matrix and graduation

Run the same release build with processor override 2, 4, and 8. For each,
measure 1/2/4/8 databases and 1/4/16/64 persistent Bun sessions under:

- hot identical and distinct cold queries;
- mixed query/pull/execute-many at one pinned coordinate;
- continuous writes to one database plus writes and reads on another;
- provider latency, retry, failure, queue rejection, and stale repair;
- KNN search alone and concurrent with reads;
- one HNSW build concurrent with reads/writes, sampling actual native threads;
- small results and 256 KiB/4 MiB pages with fast and stalled clients;
- cancel/release while queued and running in every class; and
- repeated acquire/release plus authority shutdown.

Record throughput, p50/p95/p99 latency, CPU time, RSS/heap/direct/mapped bytes,
allocation rate, live and peak platform/virtual threads, queued jobs/bytes,
class/database service counts, maximum scheduler turns before progress,
provider connections, native construction workers, Datahike queue depth, cache
owner/waiter evidence, and zero retained resources after release.

Compare the proposed shared dispatcher against the current 8+6+6 independent
workers, not against an idle baseline. Graduate only when the configured CPU
ceiling is observed, database B progresses during database A saturation,
control remains responsive, every queue/byte bound rejects deterministically,
and shutdown/release return all retained counts to zero.

## Consequential choices for Sean

Source evidence settles the ownership and removal plan. Sean should remain in
the following measured product choices:

- whether one processor is always left for Bun/OS, or a dedicated authority
  host may use all processors;
- latency versus throughput for KNN active limits and public Proximum search
  bounds;
- provider concurrency and whether retry backoff retains its permit;
- the 256 KiB page target, 4 MiB hard frame, and 8 MiB session byte limit;
- whether two-core mixed CPU work may oversubscribe to two workers for latency;
- HNSW construction parallelism and whether construction is allowed while
  interactive work is queued; and
- the final one-versus-2/4-authority-shard result after the density matrix.

The recommended first implementation uses the table above, retains provider
permits during backoff, never oversubscribes two-core hosts, pauses new HNSW
construction while interactive CPU work is queued, and changes none of these
defaults without measured evidence.
