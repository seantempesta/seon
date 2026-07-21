---
type: research
status: active
tags: [research, prd, database, flow]
---

# Proximum native capacity — 2026-07-16

## Dependency correction

Seon runs Proximum `9846d3e79e1aee48474bc876d3d563d7137209c6`
from `seantempesta/proximum`. The `reference-code/proximum` submodule had drifted
to upstream `5f7142d5`; it is now pinned to the selected fork SHA so research and
runtime source are identical.

## Ownership

The Datahike bridge owns one mutable Proximum index reference per secondary
index value at
`reference-code/datahike/src-secondary/datahike/index/secondary/proximum.clj:105-151`.
Search captures that reference once and invokes Proximum synchronously. Flush
runs in Datahike's commit path, persists mmap/vector/graph state, then publishes
the replacement bridge. Datahike release drains the writer before closing
secondary indexes and then storage.

External KNN work is not tracked by the Datahike writer. Authority release must
therefore stop admission and drain or fence external KNN use before Proximum
close unmaps its arena.

## Separate search and construction capacity

KNN search is synchronous, allocation-heavy, and primarily consumes SIMD and
memory bandwidth. Index construction is a different resource: pinned
`HnswInsert.java:27-42` owns a static `ForkJoinPool` sized to physical cores,
and `HnswInsert.java:281-305` ignores the public batch parallelism argument and
uses that pool. One build can therefore engage the whole host despite an outer
permit of one.

Initial policy is independently measured KNN permits and one process-wide index
construction permit. Incremental secondary inserts remain inside each
Datahike ordered writer. Do not increase construction concurrency until
Proximum honors its parallelism setting and shared vector-store safety is
proved.

## Existing bounds to expose

Thread interruption is not checked in the HNSW search or insertion loops, so a
running native request is not truthfully interrupt-cancellable. Cancellation
may remove queued work and drop a late result but must not claim native stop.

Proximum already contains the closer seam. `SearchOptions.java:15-228` has
timeout nanos, maximum distance computations, and patience; HNSW beam loops
check those bounds. The narrow fork improvement is to pass these existing
options through the Proximum Clojure and Datahike capability surfaces. Do not
invent replacement timeout or work names.

Proximum does not currently report actual distance computations. Per-request
evidence may truthfully include selected options, elapsed time, result count and
weight, and authority queue/wait/run facts, but not a fabricated native work
count.

## High-leverage hot paths

The Datahike bridge converts a sequential query vector to a new float array;
Proximum then defensively clones that array before search. An internal
owned-array entry point can remove the second copy while preserving the public
defensive API.

Filtered KNN is more serious. Datahike passes an EntityBitSet membership
predicate. Proximum's predicate path scans every vector and metadata row to
construct an ArrayBitSet before HNSW search. Scoped search therefore pays
O(index size) work before approximate search. The fork should accept the
existing primitive IDs or bitset directly and avoid the generic predicate scan.

Existing `proximum.metrics/index-metrics` already reports vector, deleted and
live counts, deletion ratio, capacity, utilization, edges, branch, commit, and
cache hits/misses. Reuse those names as resource evidence.

## Falsifiers and Sean's decisions

Before changing the public capability contract:

- interrupt a high-ef search and prove it continues, then pass each existing
  SearchOptions bound and measure termination and recall;
- request build parallelism one while sampling CPU and the pool;
- count entity-filter predicate calls and prove they equal vector count;
- race a long search with release and prove no access after mmap close;
- backfill while ordinary writer inserts continue, then reopen and verify exact
  vector IDs/count and recall; and
- repeatedly acquire/release databases and prove file descriptors and mapped
  memory return to baseline.

Sean retains the latency-versus-throughput choice for KNN permits, which
existing search bounds become public, whether the Proximum fork is patched in
this unit, and the direct filter representation. The source evidence supports
patching the fork now; benchmark results select the public defaults.
