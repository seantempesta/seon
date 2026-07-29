---
type: research
status: active
tags: [research, database, render]
---

# Query invalidation for context-walk S4

This is the evidence ledger for deciding how a rendered piece can know that
its inputs changed without re-deriving the piece. Chunk 1 establishes the
selected dependency sources and measures Datahike's existing caches. Old-system
precedent, read tracing, and the S4 verdict remain intentionally pending.

## Dependency ledger

### Selected dependencies

- Datahike is selected by `deps.edn:21-25` as the local root
  `reference-code/datahike`, excluding its transitive Konserve coordinate.
  The checked-out submodule is
  `9a7a9ef10a954c32075e60d929f9101a9ac8abd9`
  (`git describe`: `0.8.1729-92-g9a7a9ef1`).
- Konserve is selected independently by `deps.edn:26-33` at Git SHA
  `b5c99bc02a7175652a610324215288b78551801f`. The
  `reference-code/konserve` submodule is at the same SHA. Datahike therefore
  compiles against the maintained Konserve fork selected by the root project,
  not an incidental transitive version.
- The old `:writer` alias repeats the same local Datahike root and Konserve SHA
  at `deps.edn:106-119`. The CLJS quarry declares published Datahike
  `0.8.1681` at `deps.edn:184`, then overrides it with the same local Datahike
  checkout and Konserve SHA at `deps.edn:189-195`. Historical dependency
  changes and old source ownership are reserved for the old-precedent chunk.

### Dependency implementation read map

- Query entry and normalization:
  `reference-code/datahike/src/datahike/query.cljc:123-142`.
- Parsed-query LRU and memoized parser:
  `reference-code/datahike/src/datahike/query.cljc:2413-2417` and
  `:3051-3074`.
- Query-plan LRU:
  `reference-code/datahike/src/datahike/query.cljc:2415-2418`; the key is
  where clauses, bound variables, rules keys, and schema hash, so it is
  intentionally stable across transactions while schema is stable.
- Committed query-result cache, sizing, and evidence:
  `reference-code/datahike/src/datahike/query.cljc:2433-2658`.
- Dependency extraction and conservative widening:
  `reference-code/datahike/src/datahike/query.cljc:2677-2943`.
- Cross-basis compatibility and lazy result inheritance:
  `reference-code/datahike/src/datahike/query.cljc:2949-3039`.
- Exact committed database identity:
  `reference-code/datahike/src/datahike/db.cljc:388-411`.
- Connection generation admission and release:
  `reference-code/datahike/src/datahike/connector.cljc:360-400` and
  `:480-493`.
- Commit-time attribute revision advancement:
  `reference-code/datahike/src/datahike/writer.cljc:219-240`.
- Index dispatch and persistent-set implementation:
  `reference-code/datahike/src/datahike/index/interface.cljc` and
  `reference-code/datahike/src/datahike/index/persistent_set.cljc`.
- Datahike's Konserve read-through wrapper:
  `reference-code/datahike/src/datahike/store.cljc:20-34`.
- Konserve's generic key/value LRU:
  `reference-code/konserve/src/konserve/cache.cljc:19-40`.
- Persistent-set node LRU and storage reads:
  `reference-code/datahike/src/datahike/index/persistent_set.cljc:409-466`.
- Listener implementation will be read with the read-tracing chunk; it is not
  evidence for this chunk's cache timing.

### Fresh render read seams

Fresh render code currently calls Datahike directly rather than through a
`seon.db` read facade:

- `src/seon/render/agent.clj:97-158,220,340` uses `d/pull` and `d/q`.
- `src/seon/render/walk.clj:221,285,307,325,361` uses `d/q` and `d/pull`;
  `d/pull` is explicitly named the canonical entity read at line 90.
- `src/seon/render/block.clj:223-237,504,1077` uses `d/q` and `d/pull`.
- `src/seon/render/web.clj:295-309,632,773,869` uses `d/q` and `d/pull`.
- `src/seon/render/root.clj:79-82,107,139` uses `d/q`.
- Current behavioral coverage is concentrated in
  `test/seon/render/agent_test.clj`, `block_test.clj`, `root_test.clj`,
  `value_test.clj`, and `web_test.clj`. Exact tests for the chosen trace seam
  are reserved for the read-tracing chunk.

## Datahike caching truth

The selected Datahike fork has four distinct caches relevant to this question:

1. An exact-query parsed representation LRU avoids parsing the same query form
   again.
2. A query-plan LRU reuses index selection and merge ordering across database
   transactions while the schema hash and other plan inputs remain equal.
3. A committed query-result weighted LRU caches the result by exact committed
   database identity plus query and non-database inputs. On a miss at basis
   N+1, it may inherit a result from an older basis when the parsed dependency
   plan is narrower than `:all`, the conservative revision is unchanged, and
   every depended-on attribute revision is unchanged. The inherited row is
   promoted lazily into the demanded N+1 snapshot bucket; commits do not copy
   result rows.
4. Two storage layers avoid repeated persistence reads: Konserve caches whole
   keys through `konserve.cache/read-through`, while Datahike's persistent-set
   `CachedStorage` separately caches decoded index nodes by address. These
   storage caches accelerate index traversal; they are not query-result
   invalidation.

Only attached committed database values carry a query-cache identity.
Speculative and most temporal wrappers do not participate. Cache ownership is
fenced by the connection generation, and release evicts that generation.
Schema-changing, merge, unknown, or otherwise unsafe commits advance a
conservative revision and prevent inheritance.

## Timing probe

Reproduce from the repository root:

```sh
clojure -M:dev tmp/query-invalidation/datahike_query_cache_timing.clj
```

The probe creates an isolated in-memory database, installs four attributes,
transacts 8,000 entities, and queries a two-pattern filtered count. It warms
the JVM/parser/planner with 12 discarded executions. It then records:

- 20 result-cache-cold executions, clearing only the query-result cache before
  each sample;
- one seed plus 20 same-basis warm executions;
- a tiny N→N+1 transaction touching only
  `:query-invalidation/noise`, followed by 20 executions at N+1.

Every timing is paired with `q-with-evidence`'s cache outcome. Thus “warm” and
“inherited” are not labels inferred from wall time.

Recorded 2026-07-29 on Java 26.0.1:

| Case | Samples | Cache evidence | min | p50 | p95/max |
|---|---:|---|---:|---:|---:|
| same basis, result-cache cold | 20 | 20 `miss-owner` | 5,789.667 µs | 6,643.542 µs | 8,733.750 µs |
| same basis, result-cache warm | 20 | 20 `hit` | 23.625 µs | 25.459 µs | 448.708 µs |
| N→N+1, unrelated attr | 20 | 20 `hit` | 17.792 µs | 21.666 µs | 1,087.542 µs |

The first N→N+1 access cost 1,087.542 µs and reported `hit`; subsequent exact
N+1 hits account for the much lower steady-state values. The dependency
evidence was exactly
`#{:query-invalidation/group :query-invalidation/value}`; the transaction
touched only `:query-invalidation/noise`. Cache metrics ended with two snapshot
buckets and total structural weight 2, showing lazy promotion into N+1 rather
than an eager copy at commit.

Raw microsecond samples:

```clojure
{:cold
 [7803.083 7443.334 7820.833 7603.0 7286.084
  6643.542 6901.041 6537.541 6318.5 5789.667
  6132.917 8733.75 6459.0 6769.417 6341.083
  6694.458 6003.0 6548.584 6228.625 6315.75]
 :warm
 [448.708 46.791 29.083 26.458 25.375
  24.833 24.375 24.125 24.083 23.625
  25.291 25.625 23.916 25.459 24.25
  25.958 23.875 119.333 35.25 67.792]
 :n-to-n+1
 [1087.542 63.791 28.542 60.0 28.125
  22.042 20.458 21.666 25.083 22.084
  20.083 19.291 51.916 20.958 19.334
  19.625 18.875 19.334 18.208 17.792]}
```

These numbers isolate only the query-result cache: the discarded warmup made
JIT, parsed-query, query-plan, and in-memory index effects hot, while
`clear-query-cache!` cleared the result cache and its single-flight coordinator
before every cold sample. This is deliberately not a process-cold benchmark.
At p50, an exact same-basis result hit was about 261× faster than recomputing
this particular aggregate. That ratio is workload-specific; the evidence of
cross-basis reuse is the reported hit and unchanged attribute revisions, not
the ratio.

## Old precedent

Pending chunk 2. No claim about designed versus built old behavior is made
here.

## Read-tracing feasibility

Pending a later chunk.

## S4 verdict

Pending completion of the old-precedent and read-tracing falsifiers.
