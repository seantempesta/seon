---
type: research
status: complete
tags: [database, research, decision, capability]
---

# Datahike temporal query sharing seam

## Decision

Extend Datahike's existing committed query-cache identity inside
`datahike.query`; do not add a Seon cache, database handle, coordinate field,
or public Datahike value.

For a direct numeric `AsOfDB` whose origin is an attached committed raw `DB`,
the internal database key should be:

```clojure
[connection-id generation commit-id t]
```

The existing query cache then uses that key as its database bucket, and the
existing single-flight key remains:

```clojure
[[connection-id generation commit-id t]
 [query non-db-arguments offset limit order-by planner-mode]
 max-work max-results max-result-weight]
```

This is the smallest dependency-native change. Completed results continue to
live in Datahike's weighted LRU, in-flight computations continue to live in
Datahike's bounded coordinator, and caller cancellation and evidence continue
to use the existing code. `t` is not another Seon coordinate or a new public
name; it is the existing `AsOfDB.time-point` appended to Datahike's existing
process-local committed cache key.

Only a *direct*, numeric `AsOfDB` is eligible in the first cut. Keep `SinceDB`,
`HistoricalDB`, nested wrappers such as `(d/history (d/as-of db t))`,
`FilteredDB`, speculative values, detached values, and Date-based `AsOfDB`
uncacheable. `[connection-id generation commit-id t]` denotes direct as-of
semantics and cannot also denote history-through-as-of or a filtered view.
Admitting those wrappers under the same key would allow identical query text to
return a result computed with different database semantics. Supporting them
later requires a measured need and an internal discriminator; it does not
justify broadening this first seam.

Do not propagate temporal completed results across commits in the first cut.
The containing commit remains part of identity even when two descendant
commits can answer the same earlier `t`. This satisfies the required isolation,
avoids relying on ancestry or schema equivalence inside the cache, and leaves
the existing raw-value propagation path unchanged.

## Dependency ledger

The selected checkout is `reference-code/datahike` at
`1296cfc4cb8c9b4868dde8bb6c3f4d4dc523d043` (`Make pull-many ordered for
missing entities`), pinned by Seon commit `09c6b4755ff84da50cc14e390753953ecccf994b`.
All Datahike paths below are relative to that checkout.

| Owner | Exact source | Constraint |
|---|---|---|
| Raw committed identity | `src/datahike/db.cljc:385-411` | `committed-value-identity` exposes only ordinary connection, generation, and commit facts for attached raw `DB`; wrappers and speculative values deliberately return nil. Keep this public behavior. |
| As-of value | `src/datahike/db.cljc:565-631` | `AsOfDB` contains only `origin-db` and `time-point`; reads delegate to the origin with an inclusive temporal predicate. |
| As-of construction | `src/datahike/api/impl.cljc:153-183` | Numeric `t >= tx0` constructs `AsOfDB`; Date is accepted; a future numeric `t` is not rejected. Seon's strict resolver already owns exact-coordinate validation. |
| Query cache | `src/datahike/query.cljc:2413-2582,2681-2714` | The weighted LRU is keyed by a database-key vector and then the semantic query key. `db-cache-key` is the single eligibility seam. Cache puts are fenced by generation admission and an epoch. |
| Propagation | `src/datahike/query.cljc:2716-2750`, `src/datahike/writer.cljc:205-239` | The writer propagates surviving raw committed entries from parent to child after durable publication. It receives raw parent and child values, so a direct-AsOf extension need not alter this path. |
| Acquisition | `src/datahike/query.cljc:4196-4327,4338-4424` | A nil database key selects `acquire-direct!`; an admitted key gets completed lookup, cache recheck, bounded single-flight acquisition, result publication, and host `acquire-q!` integration. |
| Single-flight | `src/datahike/query/single_flight.cljc:93-172,175-383,419-518` | One atom owns exact-flight admission and request lookup. Capacity, reentrancy, cancellation, scope close, clear, completion, and stale finishing are already handled. |
| Connection generation | `src/datahike/connector.cljc:362-400,483-498` | Connect creates a new generation and opens it before publication. Final release closes the exact generation before writer drain, evicting results and failing/detaching its flights. |
| Loaded commits | `src/datahike/versioning.cljc:68-88,415-443` | `commit-as-db` through an attached connection copies the same connection/generation ownership and the requested commit UUID. `release-materialized-db` releases native materialized resources but does not close the connection generation. |
| Retained cache proof | `test/datahike/test/query_cache_test.cljc:44-235,283-595` | Tests already prove store/branch/generation separation, release fencing, cache clear, 16-way sharing, per-caller budgets, 32 host callers, cancellation, failure/retry, and reconnect behavior for raw values. Temporal tests should extend this file rather than create another mechanism. |
| Existing temporal benchmark | `benchmark/src/benchmark/datascript_bench.clj:810-900` | The maintained benchmark already constructs a 20k-entity history-enabled database and an earlier `AsOfDB`, but line 874 disables result caching. Add a focused sharing mode rather than treating its current cold-query comparison as evidence for this optimization. |
| Seon strict coordinate | `docs/prds/database-authority-mesh/research/strict-temporal-coordinate-seam-2026-07-16.md` | Seon already resolves one containing commit and exact numeric `t`, proves branch ancestry, rejects future/missing transaction cuts, and keeps the raw containing value alive through physical work. No cache validation belongs in Seon. |

## Why the four-part internal identity is exact

### Connection and generation

Connection identity separates stores and attached branch routes. The retained
tests demonstrate that sibling branch connections at the same commit UUID have
different connection and generation facts. Generation prevents an old result
or late owner from crossing a final release and reconnect.

Do not replace these two facts with store ID, branch, raw DB hash, maximum
transaction, or object identity. Datahike has already removed those collision
and lifecycle hazards from the raw cache.

### Commit

The commit UUID selects the immutable containing primary indexes. Several
logical transactions may share one persisted writer-batch commit, so maximum
transaction alone is not the containing-value identity. A commit loaded through
the attached connection retains the same generation ownership and substitutes
its own commit UUID, which is exactly what historical materialization needs.

### Transaction

Two `AsOfDB` wrappers over the same containing value but different `t` expose
different datoms and must not share. Appending the numeric transaction to the
existing vector separates them without retaining the wrapper or origin object.

The query and all non-database inputs remain in the existing semantic query
key. Resource limits remain in the single-flight key, so callers with different
cold-work limits do not share a possibly failing computation. A completed
semantic result can still be reused and certified independently against each
caller's result limits, matching current raw-value behavior.

### No branch or database ID duplication

Seon's portable coordinate also contains database ID and branch. Those are
authority routing and authorization facts. Datahike's connection and generation
already express its process-local cache ownership, and the commit plus `t`
express the immutable read. Copying Seon's fields into Datahike would create a
second identity contract without improving isolation.

## Smallest implementation

Keep `datahike.db/committed-cache-identity` unchanged so its documented and
tested meaning remains "attached committed raw DB." Change only the private
query eligibility seam around `datahike.query/db-cache-key`:

1. For a raw `DB`, return `db/committed-cache-identity` exactly as today.
2. For a direct `AsOfDB`, inspect `dbi/-origin` and `dbi/-time-point`.
3. Admit only an integer time point within `tx0 <= t < origin.max-tx` and an
   origin with an existing committed cache identity.
4. Return `(conj origin-identity (long t))`.
5. Return nil for every other value, preserving `acquire-direct!` behavior.

The lower-bound and earlier-than-head eligibility tests are cheap scalar checks;
do not scan `:db/txInstant` while deriving a cache key. Datahike's public
`as-of` semantics permit cuts between real transactions, while Seon's strict
protocol already proves transaction existence before query acquisition.
Restricting the optimization to earlier cuts also avoids creating a second
bucket for `AsOfDB` at the raw head and leaves Datahike's currently accepted
future-as-head behavior unoptimized rather than silently legitimizing it.

No LRU, single-flight, public API, generated binding, capability catalog, or
wire type needs to change. The cache's weighted LRU accepts vector keys of
either length. Existing release predicates destructure connection and
generation from the front of the vector, and single-flight scope close does the
same through the flight key, so temporal entries naturally join the current
generation lifecycle.

An implementation should still make those variable-length assumptions explicit
in tests. A private helper for extracting the first two identity facts is
acceptable if it makes close predicates clearer; a second cache or coordinator
is not.

## Invalidation, propagation, and lifetime

### Generation close and clear

`close-query-cache-generation!` atomically removes all LRU database buckets
whose first two facts match the closed connection and generation, then
`single-flight/close-scope!` removes and fails matching flights. A four-element
database key is covered by both predicates. `clear-query-cache!`, cache-size
changes, and cache-weight changes replace the LRU epoch and clear every flight,
so an already-admitted owner cannot repopulate the old cache.

Final connection release therefore provides the required temporal cleanup.
Reconnect opens a fresh generation and cannot observe an old temporal result or
join an old temporal flight.

### Materialized database release

Releasing one `commit-as-db` value must not evict its completed query results.
The weighted cache retains ordinary result data and attribute dependencies, not
the raw DB, `AsOfDB`, secondary-index handle, connection, Future, or callback.
Another materialization of the same commit in the still-open generation may
legitimately reuse that result. Native resources remain owned by the raw
materialized value and are released after its physical query work completes.

If final connection release races a running temporal owner, generation close
detaches the flight, fails its callers, sets the cooperative cancel flag, and
fences its cache put. The physical owner may finish stale, but cannot resurrect
the generation. Seon's authority must continue retaining the raw containing
value through that physical completion; caching does not replace database-value
lifetime ownership.

### No temporal propagation in the first cut

It is tempting to copy a result at `t` from containing commit A to descendant
commit B because later datoms are filtered out. Do not do this yet:

- the acceptance contract requires different commits not to share work;
- `AsOfDB` delegates schema and identifier resolution to its containing origin,
  so a later schema commit can change execution even when the selected data cut
  is earlier;
- Date-to-transaction resolution is relative to the containing origin;
- merge and sibling ancestry would require another proof inside cache
  propagation; and
- the normal writer currently receives raw DBs and correctly propagates only
  raw cache buckets.

Attribute dependencies are still stored on temporal entries because evidence
and future analysis use them, but they must not cause a temporal bucket to be
copied to another commit. Cross-commit propagation could be reconsidered only
after proving schema-at-`t` semantics and descendant ancestry, and after a
benchmark shows the additional logic beats recomputation.

## Date as-of

Keep Date-based `AsOfDB` uncacheable in this cut.

The planner resolves a Date to the greatest transaction whose
`:db/txInstant <= date` in `query/execute.cljc:679-749`. Its private bounded
memo uses `[store-id branch max-tx Date]`, while the result cache uses committed
connection ownership. Reusing the Date object directly in a result identity is
unsafe because `java.util.Date` is mutable, and resolving it during cache-key
derivation would duplicate or expose a private planner concern.

If Date query sharing becomes important, first move Date-to-transaction
resolution to one dependency-owned function, copy the input to immutable epoch
milliseconds, resolve it once, and feed the numeric transaction to both planner
and cache identity. The result key should still end in resolved `t`, not Date,
so two dates selecting the same exact cut can share safely. That is a separate
measured change; Seon's authority protocol already uses numeric `t` and does not
need it.

## Cancellation, capacity, failure, and reentrancy

The existing coordinator behavior should be inherited unchanged:

- identical temporal owners join only when database key, semantic query key,
  and cold resource limits all match;
- `*max-active-flights*` bounds distinct temporal and raw computations together,
  preventing an unbounded historical-flight pool;
- a caller request ID remains globally unique among active calls;
- cancel detaches exactly that caller, cooperatively signals only when the last
  caller leaves, and names the unstarted owner job when applicable;
- failures are delivered to every joined caller and are never cached, so the
  next call retries;
- an owner recursively issuing the same exact query on its execution thread
  uses the existing reentrant direct path rather than deadlocking on itself;
- completion removes the exact flight before notifying callers, and a stale
  owner finishing after close cannot remove a successor flight; and
- cached results are certified against each caller's result limits even though
  their computation was shared.

The cache key must continue including scale-sensitive query arguments and
planner mode exactly as it does for raw DBs. Do not special-case temporal query
arguments or weaken current resource-key separation to increase hit rate.

## Shortest falsifier

Add one focused CLJ test beside
`identical-concurrent-datahike-queries-compute-once` in
`test/datahike/test/query_cache_test.cljc`:

1. Create a history-enabled attached database with one fact, record `t`, then
   change that fact in a later transaction.
2. Construct one direct `(d/as-of @conn t)` value.
3. Use a query input predicate that increments an atom and blocks briefly.
4. Start 32 `d/acquire-q!` calls with distinct request IDs over that same
   temporal value before running the owner.

Current behavior falsifies the target immediately: all 32 calls report `:run`
because `db-cache-key` is nil and each receives an `acquire-direct!` execution.
The target behavior is one `:run`, 31 `:waiting`, one predicate call, identical
results, one miss-owner, 31 miss-joined, and zero active flights/callers after
completion. A subsequent call must be a completed hit with zero resource work.

This falsifier exercises the host acquisition seam used by Seon and avoids
thread scheduling ambiguity in a futures-only test.

## Required implementation proof

Extend the retained query-cache test namespace, preserving all existing raw
tests, with the following acceptance matrix:

| Case | Required evidence |
|---|---|
| Two and 32 identical direct numeric as-of calls | One physical predicate call; owner/waiter evidence; identical result; completed follow-up hit. |
| Different `t` in one commit | Two owners and distinct results where data differs. |
| Different containing commit with the same `t` | Two owners/database buckets, even on a proved descendant. |
| Different connection and generation | No sharing; final release evicts temporal completed entries and fails temporal flights; reconnect is isolated. |
| Different query or non-database arguments | No sharing, using the existing scale-sensitive semantic key. |
| Different cold resource limits | No in-flight sharing; completed result is independently certified for each caller. |
| Cancel one waiter, then the last caller | Exact detach, unstarted-owner identification, cooperative signal, and zero retained flight. |
| Failure and retry | One shared failure, no completed entry, one successful new owner on retry. |
| Cache clear during acquired/running owner | Callers fail, late put fenced by epoch/generation, zero temporal bucket. |
| Reentrant identical temporal query | Direct reentrant computation completes without deadlock and leaves no flight. |
| Date, history-through-as-of, since, filtered, detached, speculative | Remain uncacheable/direct; they never collide with the direct numeric as-of bucket. |
| Raw current query | Existing allocation/evidence and latency gates remain unchanged. |

Also assert cache occupancy through bounded evidence rather than exposing
results. A temporary private-test inspection of database keys may prove that a
temporal bucket has four plain-data facts and no DB/wrapper object; it should
not become public API.

## Benchmark plan and graduation threshold

Add a focused Datahike-only temporal sharing benchmark alongside the existing
temporal benchmark rather than enabling cache globally in the comparative
suite.

Use the existing 20k-person, 2k-modification history fixture and measure both a
selective two-clause query and a wider join at an earlier numeric `t`. Warm the
JIT but clear Datahike's result cache between independent samples. Record:

- direct uncached temporal execution;
- first admitted temporal owner, including key/coordinator bookkeeping;
- sequential completed hit;
- concurrent batches of 2, 8, and 32 identical callers;
- 32 callers spread over 32 different `t` values; and
- the same raw-current query before and after the change.

For each, record wall latency distribution, CPU time, physical predicate/query
count, allocation bytes if the profiler supports them, retained cache weight,
active-flight peak, and GC count/time. Run enough forks to separate JIT warmup
from steady state; report the exact JVM and heap.

Graduate only if:

- a cold direct numeric as-of query that does not share regresses by no more
  than 3% median latency and 5% allocation versus the same pinned baseline;
- raw current-query median latency and allocation do not regress beyond noise;
- 8 and 32 identical callers execute once and materially reduce total CPU;
- a completed temporal hit is at least 5x faster than recomputation for both
  selected workloads;
- the different-`t` workload remains bounded by the existing flight and LRU
  limits; and
- release, reconnect, clear, failure, retry, and cancellation finish with zero
  active temporal flights and no old-generation bucket.

If the selected queries are so cheap that the four-part key, atom lookup, and
result certification do not win at two concurrent callers or on a sequential
hit, retain only single-flight or keep those query shapes direct. The benchmark,
not architectural preference, decides whether completed temporal caching earns
its retained memory.

## Tradeoffs and rejected alternatives

### Seon-side cache

Rejected. It would duplicate query identity, result weights, generation
fencing, cancellation, failure retry, and release behavior while retaining
serialized or host values farther from the Datahike computation.

### Put cache context on `AsOfDB`

Rejected for the first cut. Mutating the wrapper shape or making it appear to
be a raw committed value broadens public identity semantics and risks generic
code treating it as the containing commit. The query namespace can derive the
four-part key without changing the value.

### Key only by commit and `t`

Rejected. It crosses connection ownership and reconnect generations, recreating
the exact stale-result and final-release races the current cache identity fixed.

### Key by origin object identity

Rejected. Equivalent materializations of one commit would not share, object
lifetime would leak into cache identity, and reconnect isolation would be
implicit rather than fenced.

### Share across commits using attribute dependencies

Rejected for now. Later transactions are excluded by `t`, but schema,
identifier resolution, Date mapping, branch ancestry, and the explicit
different-commit isolation contract make propagation neither free nor proven.

### Cache every temporal wrapper recursively

Rejected. Direct as-of, since, history, filtered, and nested combinations are
different views. A bare trailing `t` cannot distinguish them. The narrow
numeric `AsOfDB` seam covers Seon's strict point reads with the least code and
leaves broader temporal identities for evidence-driven work.

## Result

The optimal seam is one private eligibility extension in Datahike query code.
It reuses the existing weighted completed cache, bounded single-flight,
generation lifecycle, resource certification, evidence, cancellation, and
failure semantics. The only new retained fact is the already-existing numeric
transaction appended to the already-existing committed identity. No database
value, wrapper, connection, Future, callback, or Seon coordinate is retained or
placed on the wire.
