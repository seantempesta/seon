---
type: research
status: completed
tags: [research, prd, database, flow]
---

# Execute-many immutable-value reuse — 2026-07-16

## Decision

`execute-many` should acquire one exact immutable Datahike DB value for the
outer request, run its independent members as flat jobs through the existing
fair read executor, return outcomes in member-vector order, and relinquish the
value after every member has settled.

Do not call `commit-as-db` independently in every member. Do not run the whole
request sequentially on one worker. Do not submit work recursively from a read
worker into the same pool. The smallest correct host mechanism is a
request-lifetime DB owner guarded by the existing exact executor scope:

1. retain the exact scope under the executor lock;
2. resolve the coordinate once as a fair read job;
3. submit indexed member jobs that all carry the identical internal DB value;
4. collect every success or error into a pre-sized result vector;
5. cancel queued and running member work by the outer request ID; and
6. in `finally`, relinquish the value and the retained scope.

The wire retains one `:seon.db.protocol/request-id`. Vector position already
correlates each input and output member, so no member ID is needed. Internally,
concurrent Datahike queries derive their active caller IDs from the outer
request ID and member position. Those strings are process-local, exist only
while their queries run, and let outer cancellation fan out through Datahike's
existing `cancel-query!`; they are not a second wire identity or durable fact.

## Dependency ledger

- Seon `05aaa5eb1085feb2c23fda5d6d786d7d425fa2f1`:
  `src/seon/db/writer.clj`, `src/seon/db/executor.clj`, and
  `src/seon/db/protocol.cljc`.
- Datahike `092f5b0580c892c32b1dc65bf9acdbe37db90c4f`:
  `versioning.cljc`, `writing.cljc`, `connector.cljc`, `db.cljc`,
  `query.cljc`, and `api/impl.cljc`.
- Konserve `df6818d43ea3363a808cd051c0d68917f1b987a9`:
  `konserve.cache` and its store protocols.
- Persistent sorted set
  `e1a17bbe767c7801e67407c81f64efabfd2f1601`:
  `PersistentSortedSet.java` and Datahike's
  `index/persistent_set.cljc` adapter.
- Prior contracts:
  [[multidb-execute-many-proof-2026-07-15]],
  [[authority-protocol-contract-2026-07-16]], and
  [[read-materialization-contract-2026-07-16]].

The probes used JDK 26.0.1, an in-memory Konserve backend, a persistent-set
Datahike database with 20,000 entities, retained history, and fused index roots.
They are mechanism probes, not production capacity numbers.

## What `commit-as-db` actually reuses

The head path is already ideal. `datahike.api.impl/db` simply dereferences the
connection (`reference-code/datahike/src/datahike/api/impl.cljc:142-143`). With
the self writer active, connection dereference returns the current value held
by its wrapped atom (`connector.cljc:82-97`). Repeated head reads therefore
return the identical DB and index objects.

Historical resolution is structurally different:

1. `commit-as-db` reads the commit record through Konserve and calls
   `stored->db` every time (`versioning.cljc:414-433`).
2. Konserve's read-through cache shares the stored immutable commit record on a
   warm hit, but every hit still updates the one LRU atom
   (`reference-code/konserve/src/konserve/cache.cljc:27-39`).
3. `stored->db` reads schema metadata, restores secondary indexes, creates an
   empty DB, attaches six primary indexes, and merges a fresh DB record
   (`reference-code/datahike/src/datahike/writing.cljc:226-287`).
4. Persistent-set attachment shallow-copies each index while sharing its node
   tree and storage (`datahike/index/persistent_set.cljc:572-598`). Fused roots
   are seeded into those unpublished copies, while deeper nodes remain lazy
   (`writing.cljc:247-264`). `PersistentSortedSet.root()` otherwise restores
   lazily by address and retains the restored node through the configured
   reference (`reference-code/persistent-sorted-set/src-java/org/replikativ/
   persistent_sorted_set/PersistentSortedSet.java:57-75`).
5. Loading through the attached connection copies its connection ID and
   generation into the older commit's cache context
   (`versioning.cljc:67-87,414-433`). That lets independently reconstructed DB
   records share Datahike's completed-query and single-flight keys, but it does
   not make the DB or index wrapper objects identical.

The identity probe observed:

```clojure
{:probe/db-identical? false
 :probe/eavt-identical? false
 :probe/eavt-root-identical? true
 :probe/eavt-storage-identical? true
 :probe/store-identical? true
 :probe/head-db-identical? true
 :probe/head-eavt-identical? true}

```

Thus Datahike already computes and stores the durable indexes once. Repeated
historical resolution does **not** rebuild their contents, but it does allocate
DB/index wrappers, touch store and schema caches, and potentially restore
resource-owning secondary indexes. Reusing one DB value is still a material
optimization and is necessary for exact object identity across members.

## Measured materialization cost

The allocation probe used the current thread's allocated-byte counter after
warmup and reported the median of five trials with 1,000 outer requests per
trial. Each outer request either called `commit-as-db` for every member or once
and reused that value.

| Members | Repeated resolution time/request | Repeated bytes/request | One resolution time/request | One resolution bytes/request |
|---:|---:|---:|---:|---:|
| 1 | 16.4 µs | 38,553 | 12.5 µs | 38,352 |
| 8 | 106.6 µs | 306,625 | 10.6 µs | 38,328 |
| 32 | 384.2 µs | 1,226,497 | 10.9 µs | 38,328 |

At 32 members, one resolution removed about 1.19 MB of allocation per request
and was about 35 times faster at the resolution seam. A separate 5,000-call
trial measured one historical load at 25.0 µs and 38,786 bytes. Repeated head
dereference measured 23.6 ns with effectively zero allocation.

These are warm memory-backend numbers. A file, cloud, or cold cache makes the
avoidable Konserve read more expensive; native secondary-index restoration can
make it much more expensive. The proof therefore understates the production
benefit rather than establishing a deployment latency target.

## Members should still execute concurrently

One immutable DB value is safe for concurrent queries and pulls. Its primary
indexes are persistent structures; lookups share immutable nodes while lazy
restoration uses the shared store cache. Datahike query execution is ordinary
synchronous caller-thread work, so independently scheduled members can use
separate read workers.

As a shortest falsifier, eight distinct aggregate queries each scanned the
`:probe/value` attribute of one 100,000-entity immutable DB. Sequential
execution took 955.2 ms. Eight Futures over the identical DB value took
174.6 ms, a 5.47-times wall-time reduction in this single warmed run. Different
divisors produced different result counts and avoided identical-query joining.

This is evidence that parallel member execution matters, not a throughput
benchmark. The executor's measured worker cap, simultaneous databases, cache
atom contention, GC, and query shapes still determine production speedup.

## Design comparison

### Independent member jobs that each resolve the coordinate

This preserves flat fairness and avoids nested waits. It is also the current
one-read behavior repeated N times: `execute-read!` calls `pinned-database` in
each job (`src/seon/db/writer.clj:507-590`). Exact coordinates guarantee equal
contents, and attached cache identity lets identical queries share results, but
the DB/index objects are not identical. The measured allocation scales almost
linearly with member count. Native secondary owners may also be recreated.

**Rejected:** correct contents, wrong compute, allocation, and resource seam.

### One outer read worker executes every member sequentially

This resolves once and requires little code. It also makes one large request a
coarse scheduling unit, delays cancellation checks until member boundaries,
and discards demonstrated member parallelism. A bounded member count limits but
does not remove head-of-line delay.

**Rejected:** simple but needlessly serial and less fair.

### One outer worker submits nested jobs and waits

If every read worker starts an outer request, every worker can block waiting for
members queued to the same pool. A second pool avoids that deadlock only by
duplicating read capacity and oversubscribing the JVM. ForkJoin compensation is
not an execution contract and does not restore per-database selection.

**Rejected:** deadlock or parallel infrastructure.

### Request-lifetime immutable DB owner with flat member jobs

One fair internal resolution job returns a host-owned DB only to its request
coordinator. Flat member jobs share that pointer and enter the same existing
per-database ready queues as one-member reads. The coordinator waits outside
the read-worker pool and writes results at their vector positions. There is no
second query executor, database cache, transport operation, or wire identity.

The executor currently deduplicates and fences jobs by `job-id` and exact scope
(`src/seon/db/executor.clj:253-290,300-386`). Add a small retained-scope count:

- retaining fails after that scope is closed;
- final release closes admission, cancels its member jobs, and waits for both
  running jobs and retained request owners;
- the coordinator relinquishes in `finally`; and
- queued work contains repeated pointers to the same DB, not copied indexes.

This closes the gap between the resolution job finishing and its member jobs
settling. It also makes current final-release ordering remain truthful:
`fence-database-work!` drains the exact read scope before registry release
closes Datahike (`src/seon/db/writer.clj:1242-1266`).

**Selected:** one owner, one pool, flat parallelism, exact lifecycle.

## Ordered results and cancellation without wire vocabulary

The outer request contains an ordered vector of member request data. Each
member omits request ID, attachment, coordinate, and database name because the
outer request owns all four. The response contains an equally sized vector;
position `i` is the outcome for input position `i`. Completion order is an
internal scheduling fact and never changes response order.

The existing external request ID remains the only addressable request. The
authority can derive a temporary Datahike string such as the external request
ID plus `/` and the numeric vector position because Datahike currently specifies
query caller IDs as strings (`reference-code/datahike/src/datahike/api/
types.cljc:117`). Datahike retains that value only while the query is active;
its single-flight coordinator uses it for independent detachment
(`query.cljc:4179-4246`).

Outer cancellation performs three internal actions:

- remove member jobs not yet started;
- call Datahike `cancel-query!` for the derived caller IDs of running query
  members; and
- stop admitting remaining work while preserving already completed vector
  outcomes.

Running pull and pull-many are not yet cooperatively cancellable. Their executor
results may be abandoned, but the retained scope must remain until the actual
call returns. The protocol must not claim stronger cancellation until Datahike
pull accepts the same work signal.

## Secondary-index lifecycle finding

`stored->db` calls `restore-secondary-indices` for every historical load. For a
versioned index, Datahike creates and closes a skeleton, then returns a restored
index instance (`reference-code/datahike/src/datahike/writing.cljc:182-224`).
Some returned indices are `java.io.Closeable`. Connection release closes only
the secondary indices on the live connection DB
(`reference-code/datahike/src/datahike/connector.cljc:258-273,483-527`).

There is no public release operation for an independently returned historical
DB. Consequently, current coordinate-pinned historical reads can create
resource-owning secondary handles without a matching close. Execute-many must
not multiply that defect. [[historical-db-secondary-index-lifetime]] records
the issue.

Before historical KNN or secondary-accelerated reads graduate, the owned
Datahike fork needs one explicit value-lifetime seam. The smallest candidates
to falsify are:

1. acquire/relinquish a historical DB value and close only the secondary
   handles owned by that materialization; or
2. let `commit-as-db` omit secondary restoration for primary-index-only reads,
   while a separately bounded KNN operation acquires the exact secondary
   generation it needs.

Do not close the head DB's secondary indices from an execute-many coordinator;
those are connection-owned and may be in use by the writer. Do not add an
unbounded completed DB-value cache merely to avoid explicit ownership; it would
retain every queried historical commit until connection release.

## Smallest implementation sequence

1. Add the version-3 execute-many schema as one outer request with request ID,
   attachment, coordinate, aggregate bounds, and a bounded member vector.
   Members contain only query, pull, or pull-many operation data.
2. Add exact-scope retain/relinquish to `seon.db.executor`; teach
   `fence-and-drain!` to wait for retained request owners as well as jobs.
3. Resolve the DB once through one ordinary fair read job while the scope is
   retained. Keep the value only in the outer coordinator.
4. Submit every independent member as a flat job to the same read executor,
   using internal `[outer request ID, vector position]` job identity and
   temporary Datahike caller strings. Do not add another pool.
5. Fill a fixed result vector by position and return all outcomes. Stop
   admitting new members after cancellation, but do not discard completed
   outcomes.
6. Relinquish historical value resources and the exact scope in `finally`.
7. Once this path is proven, make one-member reads use the same internal
   coordinator with a one-element vector. That yields one mechanism rather
   than preserving parallel single/many implementations.

The last step is important: `execute-many` is transport composition, not a
second read engine. Query/pull/pull-many should share validation, resolution,
execution, materialization, evidence, cancellation, and release code.

## Graduation falsifiers

- Head and old-coordinate requests with 1/8/32 members call DB resolution once,
  and every member observes `identical?` DB/index objects.
- Eight independent CPU-heavy members overlap through the configured read
  worker bound; 2/4/8 simultaneous databases retain round-robin progress.
- Two identical query members compute once through Datahike single-flight yet
  occupy two ordered outcomes and detach independently on cancellation.
- A member failure does not erase completed siblings; a canceled request stops
  later admission and returns every outcome already settled.
- Final release racing before resolution, after resolution, while members are
  queued, and while query/pull is running never closes storage early, crosses a
  replacement generation, or leaves a retained scope/job/caller ID.
- Historical DB values with Proximum and another Closeable secondary index
  acquire once and close once; head secondary owners are never closed by a read.
- At 32 members, retained allocation stays near one materialization rather than
  32 materializations, and there is no second executor, result cache, or host
  owner registry after completion.
- Transit sees only ordered ordinary result data; no DB, connection, index,
  Future, promise, executor, or derived member caller ID reaches the wire.
