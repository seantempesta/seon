---
type: research
status: active
tags: [research, prd, database, flow]
---

# Proximum dispatcher seams — 2026-07-16

## Decision

Seon's authority dispatcher can own all admission without wrapping Proximum in
another service or giving every database an executor. Proximum search is a
synchronous function over one immutable index value and already runs entirely
on its caller. The Datahike secondary-index bridge should retain that direct
call and submit it as ordinary CPU work. Provider calls remain separately
bounded blocking work, and document composition and vector normalization remain
ordinary CPU work. These classes share one authority capacity map and one fair
admission decision; they do not need one Java executor implementation.

HNSW batch construction is the exception. Pinned Proximum
`9846d3e79e1aee48474bc876d3d563d7137209c6` owns a hidden static ForkJoinPool.
The smallest correct fork patch removes that owner and makes the authority pass
the construction pool explicitly. The dispatcher reserves the same number of
CPU permits before the call, so construction plus searches cannot exceed the
process CPU ceiling. A supplied pool is an internal host value, not protocol
data.

The first patch set should also route Proximum's already-implemented search
bounds and replace Datahike's O(index size) filter predicate with external
entity IDs. These are higher-leverage than a new vector protocol.

## Exact ownership today

### Search has no hidden executor

`proximum.hnsw` calls `HnswSearch/search` synchronously in the caller at
`reference-code/proximum/src/proximum/hnsw.clj:712-732`. The native search uses
the caller thread, a memory segment, the persistent edge value, and thread-local
visited storage. It creates no Future and enters no pool. Independent immutable
index values can therefore run concurrently on the dispatcher's existing CPU
workers. A KNN permit must remain retained until the synchronous call returns;
Datahike release must stop admission and drain those calls before closing the
secondary index and its mmap arena.

The current Clojure path recognizes only `:ef`. Although its Malli
`SearchOptions` advertises `:timeout-ms`, `:patience`, and
`:patience-saturation`, `hnsw.clj:715-725` calls the overload without the Java
`SearchOptions` value. The Java implementation is real but unreachable from
the Clojure API:

- `SearchOptions.java:20-35` owns `ef`, minimum similarity, timeout, maximum
  distance computations, patience saturation, and patience;
- `HnswSearch.java:97-127` and `194-229` route timeout, distance count, and
  patience into unfiltered and filtered beam search; and
- `HnswSearch.java:429-505` checks them in the beam loop.

Minimum similarity is currently stored but never read anywhere in the pinned
Java source. It must not be advertised as an enforced bound.

### Construction ignores its public bound

`proximum.hnsw/insert-batch` reads `:parallelism` and passes it to Java at
`hnsw.clj:658-710`. Java never uses that argument. `HnswInsert.java:19-41`
constructs a process-wide static ForkJoinPool at class initialization, defaulted
to half of `Runtime.availableProcessors`; `HnswInsert.java:281-305` always
invokes the batch task on that pool. Therefore `{:parallelism 1}` neither limits
threads nor connects construction to Seon's capacity accounting.

The recursive task splits only above 100 vectors
(`HnswInsert.java:311-356`). Batches of 100 or fewer are sequential even when a
large pool exists. This threshold and actual batch-size distribution must be
included in construction benchmarks; merely sampling a small batch would
incorrectly conclude that the pool is bounded.

### Provider and encoding are outside Proximum

Proximum neither calls Gemini nor encodes documents. Seon currently owns a
separate six-thread provider executor in `src/seon/embed.clj:594-637`, then
performs batching/retries through it at `embed.clj:795-864`. L2 normalization
is ordinary caller-thread CPU work at `embed.clj:541-553`. These should become
dispatcher classes, not Proximum fork APIs:

- provider: bounded blocking calls, retry delay releases CPU capacity but keeps
  the derived job addressable;
- encode: document composition, truncation, hashing, response conversion, and
  normalization on shared CPU workers; and
- KNN: synchronous Proximum search on shared CPU workers.

One scheduler may use platform CPU workers and bounded virtual provider threads
without being two admission authorities. The immutable startup capacity map is
the sole owner of active and queued limits.

## Smallest fork patches

### 1. Route existing search options

Add one private `map->search-options` in `proximum.hnsw` and call the existing
Java overloads for both `search` and `searchFiltered`. Reuse these public names:
`:ef`, `:timeout-ms`, `:max-distance-computations`, `:patience`, and
`:patience-saturation`. Add the missing Malli attribute for
`:max-distance-computations`. Do not expose `:min-similarity` until it has a
defined distance-specific implementation.

The bounds are best-effort early termination, not proof of total request cost:

- timeout starts after upper-layer greedy descent;
- the distance limit is checked once per outer beam iteration, so one neighbor
  expansion may overshoot it; and
- timeout or budget exhaustion returns the best partial result with no native
  termination reason.

The authority can report the selected options, queue duration, elapsed run
duration, and result count. Proximum cannot yet truthfully report actual distance
computations or which condition ended the search.

### 2. Pass construction execution explicitly

Replace `PhysicalCoreExecutor` with an `insertBatch` overload accepting a
`ForkJoinPool`. Keep a convenience overload only if it creates no hidden owner:
parallelism one runs the task directly; greater parallelism requires a supplied
pool or fails clearly. In Clojure, accept an internal `:executor` option beside
`:parallelism` and pass it to Java. The authority owns one construction pool,
creates and closes it with the JVM runtime, and reserves `:parallelism` CPU
permits before invoking it.

Do not submit a construction job from a worker into the same fixed CPU executor
and block while its child tasks wait for those workers. Either the dispatcher
runs the admitted coordinator outside its CPU worker set while the explicit
ForkJoinPool consumes the reserved permits, or it uses the ForkJoinPool as the
CPU carrier for all admitted CPU work. The former is the smaller first cut.

One process-wide construction job remains the initial limit because each batch
puts its private PersistentEdgeIndex into transient mode and consumes high
memory bandwidth. Separate databases may construct concurrently only after
measurements show that splitting the same CPU permits improves aggregate
throughput without harming interactive tail latency.

### 3. Convert external entity IDs without scanning metadata

Datahike's bridge currently passes a predicate at
`reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:154-185`.
Proximum receives that predicate and executes it for every internal node while
building an `ArrayBitSet` (`hnsw.clj:766-772`). Scoped KNN therefore performs
O(vector count) Clojure calls and metadata lookups before approximate search.

Datahike already owns the correct representation: `EntityBitSet` is a JVM
RoaringBitmap and `entity-bitset-seq` enumerates its external entity IDs at
`reference-code/datahike/src/datahike/index/entity_set.cljc:101-105`. Proximum's
public `search-filtered` already accepts a set of external IDs and translates
each with its persistent external-id index at
`proximum/api_impl.clj:80-102`. The smallest no-new-interface fix is therefore:

1. turn `entity-filter` into its external ID sequence;
2. pass a set of those external IDs to `prox/search-filtered`; and
3. let Proximum translate only the allowed IDs into its internal ArrayBitSet.

That changes setup from O(all vectors) predicate calls and metadata reads to
O(allowed external IDs * external-index lookup). It preserves Datahike entity
IDs as the public identity and keeps Proximum node IDs internal. For repeated
searches against the same Datahike value and same entity filter, a later
measured optimization may cache the converted Proximum ArrayBitSet for the
request lifetime. It must never persist across a different Proximum index value,
because internal node IDs are index-local.

For dense filters, enumerating nearly every external ID can lose to the scan.
Measure a crossover by filter cardinality/index cardinality before adding two
paths. The sparse external-ID path is the correct first implementation for
agent scopes.

### 4. Make running cancellation honest

Neither insertion nor search checks thread interruption. The authority may
cancel queued work immediately and discard a late running result, but it cannot
claim that native work stopped. Existing timeout and distance bounds reduce the
maximum damage after client cancellation but are not request cancellation.

The smallest truthful native patch adds an optional Java `BooleanSupplier` to
SearchOptions and checks it during upper-layer descent and inside each neighbor
loop. Seon supplies its existing request cancellation signal; it does not add a
wire field or new request identity. The search result then needs termination
evidence (`completed`, `timeout`, `distance limit`, or `canceled`) before the
authority can distinguish a partial result from a complete one. Until that
patch exists, canceled running KNN returns the existing cancellation response
and drops the eventual vector result; evidence must say native cancellation was
not confirmed.

Construction cancellation is riskier because interruption midway through a
transient graph cannot publish a partially built immutable index. Keep it
non-cancelable after start in the first cut. Queue cancellation remains exact;
shutdown waits for the admitted build, seals the edge index in `finally`, and
then closes the pool.

## Dispatcher integration contract

Use one ordinary data request at admission. No executor, index, DB, Future,
thread, ArrayBitSet, or cancellation signal crosses the protocol.

| Work | Execution seam | Active capacity | Cancellation |
|---|---|---:|---|
| query/pull | shared CPU worker | shared CPU permits | Datahike cooperative signal |
| KNN | same shared CPU worker | KNN and shared CPU permit | queued exact; running bounded, then native signal |
| encode/normalize | same shared CPU worker | encode and shared CPU permit | interrupt/check between inputs |
| provider | bounded virtual thread | provider permit, no CPU permit while blocked | interrupt plus client deadline |
| HNSW construction | explicit authority ForkJoinPool | construction job plus reserved CPU permits | queued exact; running drain |

The scheduler rotates work class and then database before starting work. KNN
and encode class bounds sit below the shared CPU ceiling; they are not additional
threads. Provider retries remain derived work and never block exact reads,
writes, or KNN on already-present vectors. HNSW construction receives replaceable
pending semantics for backfill/compaction, while the one running build is
allowed to finish.

## Proof and measurements before defaults graduate

### Fork tests

- Search options: deterministic graph fixtures prove timeout, distance budget,
  and patience are actually routed by both filtered and unfiltered Clojure APIs.
- Search cancellation: a controlled cancellation supplier stops a high-`ef`
  search and reports cancellation; no result is published as complete.
- Construction: inject pools of parallelism one, two, and four, record worker
  names/peak concurrency, and prove no `proximum.physical_cores` static pool or
  thread remains after close.
- Construction failure: injected worker failure seals PersistentEdgeIndex back
  to persistent mode and publishes no replacement index.
- External IDs: non-contiguous Long entity IDs resolve correctly; missing IDs
  are ignored; raw internal IDs never escape.
- Filter equivalence: predicate and external-ID paths return the same ordered
  results across sparse, dense, deleted-node, branch, and restored-index cases.

### Integrated authority tests

- Saturate query, KNN, encode, and provider work across at least four databases;
  assert the shared CPU peak never exceeds the configured ceiling and every
  database progresses.
- Start release during KNN; assert admission closes, the active call drains, the
  index closes once, and subsequent calls return an ordinary released error.
- Cancel queued and running KNN separately and assert truthful evidence.
- Run a construction job while interactive reads queue; prove the configured
  reservation leaves the intended interactive capacity or, on two cores,
  deliberately pauses construction.
- Reopen after construction/commit and compare external entity IDs, count,
  ordering, and recall.

### Benchmark matrix

Record throughput, p50/p95/p99 latency, CPU time, allocation, RSS/mapped bytes,
and recall for:

- 2, 4, and 8 available processors;
- 10k, 100k, and 1m vectors at the production dimension;
- KNN permits 1/2/4 and `ef` 50/100/200;
- filter density 0.01%, 0.1%, 1%, 10%, 50%, and 100%;
- construction reservation 1/2/4/all available CPU permits; and
- provider concurrency 2/4/6 with matched HTTP connection limits.

Count predicate calls in the current filter path: the source guarantees exactly
one call per vector before search. The replacement's setup work should track
allowed external-ID cardinality instead. For construction, use batches above
100 so the recursive task actually forks. Sample OS threads as well as CPU;
`:parallelism` is currently misleading and a latency-only benchmark would miss
the hidden retained pool.

## Consequential choices for Sean after measurement

- Whether an authority dedicated to database work uses all detected CPUs or
  leaves one for Bun and the OS.
- Whether two-core systems pause HNSW construction whenever interactive CPU
  work is queued, or allow time-sliced oversubscription.
- KNN active limit and default `ef`/distance budget at each host size.
- The sparse/dense external-filter crossover, if a second path is justified.
- Whether running KNN cancellation is required before the first transport cut,
  or bounded execution plus truthful late-result discard is sufficient.

These choices do not alter the protocol. They select capacity data and resource
options behind the same query/KNN/cancel operations.
