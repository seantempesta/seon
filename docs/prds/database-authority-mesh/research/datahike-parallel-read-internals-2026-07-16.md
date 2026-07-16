---
type: research
status: active
tags: [research, prd, database, flow]
---

# Datahike parallel read internals — 2026-07-16

## Decision

Use one bounded per-database-fair read executor for query, pull, temporal, and
bounded index work. Do not create separate query, pull, or index pools unless a
measured workload reveals a distinct blocking resource.

Datahike query execution is synchronous on the caller thread:
`reference-code/datahike/src/datahike/query.cljc:120-132` enters `raw-q`, and
`query.cljc:4179-4205` executes the query or secondary aggregate inline. Pull is
also synchronous and invocation-local; `pull_api.cljc:264-337` walks a local
frame stack with a per-operation resource budget. `pull-many` is sequential over
its entity IDs at `pull_api.cljc:350-359`.

Immutable database index access is directly thread-safe for parallel callers.
`db.cljc:246-279` routes datoms, seek, and reverse seek to persistent index
slices. The vendored persistent-sorted-set concurrency contract explicitly
permits unrestricted readers of safely published persistent trees while
retaining one owner for transient settle. This matches Datahike's one writer per
connection.

Parallelize independent complete operations, not clauses, pull attributes, or
index slices within one operation. This preserves Datahike planning,
single-flight, dynamic work accounting, transient ownership, and cache locality.

## Existing coordination to retain

Exact duplicate cacheable queries already join Datahike's one single-flight
owner. `query/single_flight.cljc:35-186` admits by exact database and query key;
the owner computes on its caller thread and waiters use independent completions.
Different values, arguments, and databases continue concurrently. Seon must not
add a second query cache or duplicate-work registry.

Query-with-pull caches the complete query result. Direct pull and pull-many do
not have their own result cache or single-flight. A separate pull cache would be
a second mechanism.

## Measured risks

The completed query cache is one process-global weighted LRU atom at
`query.cljc:2409-2468`. Hot hits touch it through `swap!` at
`query.cljc:2664-2677`; high-hit concurrency across eight databases can cause
CAS retries. Parse and plan caches are global volatiles at
`query.cljc:2389-2394`; concurrent misses may lose pure cache insertions and
repeat CPU work without corrupting results.

Persistent-sorted-set uses a per-connection cached storage owner at
`datahike/index/persistent_set.cljc:409-469`. Cold concurrent reads of one
database can contend on its cache and backend I/O, while independent database
connections do not share that cache. These are stronger benchmark candidates
than inventing more worker classes.

The optional Hitchhiker Tree source is not vendored and is not the maintained
default path. No HHT concurrency claim is accepted until its exact selected
source is checked out and probed.

## Graduation matrix

Measure one, two, four, and eight workers across one, two, four, and eight
databases for:

- identical cold queries, proving one owner and bounded waiters;
- distinct heavy queries, proving useful CPU parallelism and fair completion;
- hot cache hits, exposing global LRU CAS contention;
- cold same-database versus independent-database reads, exposing storage cache
  and backend limits;
- query-with-pull versus direct pull-many allocations and work evidence;
- release during owner and waiter execution, proving terminal cleanup; and
- independent max-work, max-results, and result-weight evidence.

Worker count follows measured host CPU, not database count. Eight databases on
modest hardware do not justify eight times as many threads.
