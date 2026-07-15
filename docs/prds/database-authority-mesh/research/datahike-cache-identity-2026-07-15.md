---
type: research
status: active
tags: [research, prd, database, flow]
---

# Datahike cache identity and single-flight audit — 2026-07-15

## Scope and decision status

This report executes database-authority-mesh research tasks 1–3 against the
selected Datahike fork. It is evidence for a decision with Sean, not a selected
architecture. No source or test was changed.

Selected dependency:

- Datahike `9ada755087228e10cfb179fa5779ce227a6ed220` at
  `reference-code/datahike`.
- Clojure `1.12.4` from that checkout's `deps.edn`.
- JVM reported by the runs as OpenJDK `26.0.1`.

## Executive result

Three facts constrain the design:

1. The current raw-DB cache key, `[hash max-tx max-eid]`, is not an exact
   identity. `hash` is a 32-bit additive content hash, and the cache map does
   not perform database equality after a key match. A forced collision returned
   database A's result for database B. Physical database and branch identity
   are also absent, preventing exact database-scoped eviction.
2. The completed-result cache is useful but narrower than the roadmap wording:
   only a raw `datahike.db/DB` is cacheable. `FilteredDB`, `HistoricalDB`,
   `AsOfDB`, and `SinceDB` bypass it. Propagation shares the persistent result
   bucket across a raw parent and raw child when known modified attributes do
   not intersect query dependencies.
3. Concurrent identical misses are not coalesced. In the smallest falsifier,
   eight callers performed 800 predicate evaluations instead of 100. They did
   run concurrently (about 254 ms wall time versus about 382 ms for the
   cold one-worker run), but they duplicated all query work.

The strongest option is therefore an internal Datahike cache lifecycle—not a
Seon wrapper—with exact scope and snapshot/view identity, completed-result LRU,
single-flight, scoped eviction, and metrics exposed by a small generic API.
The exact identity representation and cancellation semantics remain decisions.

## Source-grounded behavior

### Database equality and identity

- `DB` stores `hash`, `config`, and `meta` alongside its indexes and temporal
  state (`db.cljc:307`). Its JVM hash code is exactly the stored `hash`
  (`db.cljc:321`).
- `equiv-db` first compares the hash, then schema, then every EAVT datom
  (`db.cljc:675-687`). It does not compare store, branch, commit, temporal view,
  secondary indexes, or config.
- `init-db` constructs the stored hash by adding the hash of each input datom
  (`db.cljc:959-975`). Transactions likewise add and subtract datom hashes
  (`db/transaction.cljc:395-432,508`). This is not a collision-resistant
  snapshot identifier.
- Printed raw DB identity is richer: store connection ID, commit ID, max-tx,
  and max-eid (`db.cljc:689-722`). `connection-id` is `[store-id branch]` for a
  self writer and includes a remote backend discriminator when applicable
  (`store.cljc:41-61`).
- Commit IDs include hash, max-tx, max-eid, and metadata in ordinary mode, or
  Merkle roots and schema metadata in audit-grade mode
  (`writing.cljc:332-351`). A commit ID therefore exists for committed values,
  but cannot alone identify every speculative `db-with` value.
- Temporal wrappers report the origin DB's max-tx and max-eid
  (`db.cljc:508-509,573-574,639-640`). Their time point or history flavor must
  be part of identity if they become cacheable.

### Completed-result cache

- One process-global weighted LRU contains snapshot buckets
  (`query.cljc:2422-2427`). Defaults are at most 64 snapshot buckets and
  1,000,000 units of shallow structural result weight
  (`query.cljc:2388-2407`).
- Results whose bounded shallow weight cannot be certified are not cached
  (`query.cljc:2409-2420,2574-2585`). The outer LRU is bounded by both bucket
  count and aggregate bucket weight (`lru.cljc:68-158`).
- The exact current snapshot key is `[(:hash db) (:max-tx db) (:max-eid db)]`
  (`query.cljc:2457-2460`). The request key includes query, non-DB arguments,
  offset, limit, ordering, and planner mode, with special BigDecimal scale
  preservation (`query.cljc:4070-4077`).
- Cache lookup and execution are separate. A miss runs `uncached`, computes
  dependencies, and then stores the completed result
  (`query.cljc:4077-4083`). There is no in-flight state or atomic ownership.
- Queries with stats, count functions, work/result bounds, profiling, disabled
  caching, or a non-raw `DB` execute uncached (`query.cljc:4055-4069`). The
  `instance? DB` check excludes every temporal and filtered wrapper.
- Dependency extraction is concrete for literal data-pattern attributes and
  explicit non-wildcard pull attributes. Variable attributes, rule calls,
  wildcard pulls, variable pull patterns, malformed forms, and unknown clauses
  conservatively become `:all` (`query.cljc:2462-2557`).
- Transactions copy a parent's persistent bucket to the raw child and remove
  entries marked `:all` or intersecting known user-modified attributes
  (`query.cljc:2587-2615`). Both speculative `with` and committed updates invoke
  propagation (`core.cljc:135-147`; `writing.cljc:536-551`). Empty or unknowable
  modified-attribute sets skip propagation rather than risk stale reuse.

### Test coverage that exists

The focused Datahike suite proves pull-only attribute invalidation, wildcard and
variable-pull conservative invalidation, where-attribute invalidation, and
BigDecimal request-key separation
(`query_cache_test.cljc:35-160`). Weighted-cache tests prove the global weight
budget, scalar result caching, and rejection of an overweight result
(`lru_weighted_test.cljc:60-139`).

The current suite does not cover exact physical database/branch identity,
temporal-view caching, scoped eviction, cache hit/miss instrumentation, or
concurrent miss coalescing.

## Executable falsifiers and raw evidence

All commands ran from `reference-code/datahike`. These are disposable REPL
forms; they did not mutate a durable database.

### Falsifier A — independent database observations

Two independent memory databases received the same user datom. They had the
same max-tx and max-eid but happened to receive different additive hashes,
commit IDs, and therefore cache keys:

```clojure
{:equal false
 :identical false
 :hashes [-745802334 -745802356]
 :keys [[3549164962 536870913 1]
        [3549164940 536870913 1]]}

```

This does not prove safety: transaction metadata made this pair differ. It does
prove that the cache key contains no explicit store or branch scope even though
the printed DB values do.

### Falsifier B — a cache-key collision returns the wrong database result

The shortest deterministic falsifier created two one-datom immutable DB values,
made their stored 32-bit hashes equal while retaining different indexes, then
queried A followed by B:

```clojure
(let [a  (d/db-with (ddb/empty-db)
                    [{:db/id 1 :probe/value :a}])
      b0 (d/db-with (ddb/empty-db)
                    [{:db/id 1 :probe/value :b}])
      b  (assoc b0 :hash (:hash a))
      q  '[:find ?v . :where [?e :probe/value ?v]]]
  (dq/clear-query-cache!)
  {:a-result (d/q q a)
   :b-uncached (binding [dq/*query-result-cache?* false] (d/q q b))
   :b-after-a-cached (d/q q b)})

```

Raw result:

```clojure
{:a-key [1871511767 536870913 1]
 :b-key [1871511767 536870913 1]
 :a-result :a
 :b-uncached :b
 :b-after-a-cached :a}

```

Mutating the record's hash is an artificial way to make a deterministic test,
but the violated law is real: the cache treats a non-unique 32-bit sum as exact
identity. The regression test should use an explicitly constructed collision,
not wait probabilistically for one.

### Falsifier C — concurrent identical misses duplicate computation

A raw immutable DB contained 100 entities. A query took one stable predicate
function input; the predicate incremented an atom and slept for 2 ms. A latch
released all futures at once after clearing the result cache.

```clojure
{:sequential-first
 {:workers 1, :predicate-calls 100, :elapsed-ms 381.675916}
 :concurrent-eight
 {:workers 8, :predicate-calls 800, :elapsed-ms 253.782334}}

```

This is a cold, single-run wall-clock observation rather than a benchmark.
Predicate calls are the decisive deterministic measure: eight simultaneous
misses performed eight full computations. The shorter eight-worker wall time
also confirms that the JVM actually scheduled the work concurrently; sharing
must coalesce computation without serializing unrelated query keys.

### Existing focused suite

```text
clj -M:test -m kaocha.runner --focus datahike.test.query-cache-test
21 tests, 51 assertions, 0 failures.

```

Kaocha exercised the namespace across `specs`, `clj-hht`, and `clj-pss` test
configurations.

## Identity options for Sean

### Option A — retain `[hash max-tx max-eid]`

Benefits: zero migration and the smallest key. Costs: demonstrated incorrect
reuse under collision, no exact database/branch scope, no scoped eviction, and
no view identity. This is not suitable for one JVM serving many databases.

Reversibility is high, but accepting known silent cross-database corruption is
not a reasonable optimization tradeoff.

### Option B — use the raw DB value as the cache key

Benefits: hash-map collision resolution invokes Datahike equality, fixing the
demonstrated false hit without a new public identity. Costs: equality can scan
the complete EAVT after a hash collision; equivalent values from different
physical databases can still share one bucket; a raw DB key strongly retains
the indexes; scoped release remains awkward; wrappers still require a view key.

This is a small, reversible correctness patch, but not the best lifecycle seam.

### Option C — internal cache scope plus exact value/view identity

Give each connection/branch an internal cache scope based on Datahike's existing
`connection-id`. Within that scope, identify committed raw values by commit ID
plus the configuration dimensions that affect query semantics. Give
speculative values a process-local immutable value token assigned by Datahike,
and represent temporal/filtered views as a descriptor over their origin value
only if Datahike chooses to make those views cacheable.

Benefits: exact isolation, direct scoped eviction, no full-index equality on a
hit, natural branch ownership, and one owner for committed, speculative, and
view values. Costs: touches DB construction/restoration/wrapper creation;
process-local tokens cannot cross serialization; filter predicates may be
uncacheable unless they have an explicit stable identity.

This is the strongest general Datahike interface. The token is an internal
artifact, not a second Seon coordinate. Durable protocol requests continue to
use durable database coordinates; Datahike resolves those to its internal value
identity.

Questions Sean should decide with the next evidence:

- Should semantically identical commits on sibling branches share results, or
  should branch-scoped cleanup and accounting take priority? A two-level
  `scope -> value -> request` design can support explicit sharing later without
  making it accidental.
- Should temporal raw-history/as-of/since results enter the first release, or
  remain deliberately uncached until their view descriptors and memory behavior
  are measured?
- Should speculative `db-with` values be cached by default, given their shorter
  lifetime, or only when explicitly retained by a caller?

## Single-flight ownership options for Sean

### Option A — protocol-dispatcher coalescing

Benefits: can coalesce encoded remote requests and attach per-client
cancellation. Costs: local Datahike callers still duplicate work; each adapter
must reproduce exact Datahike request-key semantics; it creates another cache
owner and cannot naturally compose with internal propagation.

This is appropriate only for sharing encoded response bytes after Datahike has
produced a value, not for owning query computation.

### Option B — single-flight inside Datahike's query result cache

Maintain completed results in the existing weighted LRU and a separate bounded
map of in-flight keys. The first miss becomes owner; later callers await that
owner. Success inserts an ordinary result and removes the in-flight entry.
Failure removes it and propagates the same failure to current waiters without
poisoning later calls. Never put a Future, Promise, or channel in the completed
result LRU.

Benefits: every CLJ caller benefits; one canonical key; propagation stays in
the existing owner; Seon deletes dispatcher query coalescing. Costs: synchronous
`q` waiters block their calling threads; reentrant identical queries need an
owner-thread guard; CLJS needs an honest platform-specific implementation or an
explicit capability difference; waiter cancellation cannot automatically mean
owner cancellation.

This is the strongest candidate. Suggested generic internal shape:

```clojure
(query-cache/lookup-or-compute
  {:datahike.cache/scope scope
   :datahike.cache/value value-id
   :datahike.cache/request request-key
   :datahike.cache/compute uncached})

```

The sketch names data owned by Datahike; it is not a recommendation to expose
closures or synchronization objects through Seon's protocol.

### Option C — no single-flight

Benefits: maximum parallelism and no waiter coordination. Costs: the measured
eightfold duplicate work, allocation, and contention at exactly the many-agent
burst boundary. It remains useful as an opt-out for very cheap or explicitly
non-cacheable queries.

## Generic Datahike changes to research next

These changes belong inside the maintained Datahike fork if selected:

1. Replace the lossy `db-cache-key` with an internal exact scope/value/view
   identity shared by lookup, propagation, metrics, and release.
2. Add `evict-query-cache-scope!` rather than making the authority globally
   clear unrelated databases. Eviction must remove completed and in-flight
   ownership only with generation fencing so stale release cannot evict a
   reconnect's new scope generation.
3. Add single-flight around `uncached` while preserving ordinary completed
   values in the weighted LRU.
4. Add read-only cache evidence: completed hits/misses, owner computations,
   joined waiters, failures, rejected overweight results, propagated entries,
   invalidations by reason, evictions, weight, and in-flight count. Prefer a
   data-returning metrics function over exposing LRU implementation fields.
5. Add an optional trace/event hook only if aggregate metrics cannot explain
   tail latency; do not make instrumentation mandatory on the query hot path.

Compatibility risks:

- Cache identity must include every semantic dimension without making durable
  storage depend on a process-local token.
- Single-flight must not deadlock when a query function recursively performs
  the exact same query on the owner thread.
- An interrupted waiter must not cancel work still wanted by other waiters.
- Scoped release must use a generation or exact owner identity to avoid a stale
  disconnect evicting a rapid reconnect.
- CLJ and CLJS share `query.cljc`; a JVM-only Future implementation cannot leak
  into portable completed-result semantics.
- Changing cache behavior can reveal callers that accidentally depended on
  query functions executing for side effects. Such functions are already at
  odds with referential query semantics, but compatibility evidence is needed.

## Upstream-quality proof matrix

Add tests in Datahike's existing cache test namespaces, not Seon wrappers:

- two physical stores with deliberately colliding raw DB hashes never share;
- two branches at the same commit obey the selected explicit sharing law;
- reconnect to one branch resolves the same committed identity without letting
  stale release evict the new generation;
- two distinct speculative `db-with` values at equal tx/eid counters isolate;
- raw, history, as-of at two points, since at two points, and filtered values
  either isolate correctly or are explicitly proven uncached;
- exact sequential repeat records one computation and one hit;
- 2/8/32 simultaneous identical misses record one computation;
- different requests and different immutable values still run concurrently;
- owner success, ordinary exception, fatal throwable policy, reentrant same-key
  query, interrupted waiter, and all-waiters-cancelled behavior;
- propagation shares unaffected completed results but never in-flight state;
- weight/count eviction remains bounded after coalesced completion;
- scope eviction leaves unrelated databases untouched and releases strong
  references after the last owner;
- CLJ and CLJS conformance for every portable cache law, with explicitly
  documented capability differences where unavoidable.

Retain JMH or criterium-style warm measurements for 1/2/8/32 callers, cheap and
expensive queries, hit/miss/propagation, allocation, retained heap, GC, and
contention. The disposable cold run above is only the falsifier.

## Decision brief for Sean

The evidence rules out the current key as the final multi-database identity and
shows that burst queries duplicate computation today. The decision is not
whether to add a Seon cache; it is how much of a generic Datahike cache lifecycle
to implement in the first unit.

Recommended decision discussion:

1. Approve or reject Datahike-internal Option C identity: connection/branch
   scope plus exact committed/speculative/view identity.
2. Approve or reject Datahike-internal single-flight for cacheable `q`, leaving
   encoded-byte sharing and remote cancellation in the authority layer.
3. Choose first-release view coverage: raw committed DB only, raw plus
   speculative, or raw plus temporal wrappers.
4. Choose branch policy: strict branch-scoped buckets first, or deliberate
   content sharing with separate ownership/accounting.
5. Require scoped eviction and aggregate metrics in the same Datahike unit so
   memory cleanup and performance are observable rather than inferred.

All choices are reversible at the internal key layer if callers see only
Datahike query operations and metrics data. The least reversible mistake would
be exposing a process-local cache token as Seon's durable database identity;
the two must remain separate.
