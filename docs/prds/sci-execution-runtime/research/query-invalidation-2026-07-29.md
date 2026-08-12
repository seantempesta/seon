---
type: research
status: active
tags: [research, database, render]
---

# Query invalidation for context-walk S4

This evidence settles how a rendered piece can know that its inputs changed
without first re-deriving the piece. It covers the selected dependency
sources, measured Datahike caches, old-system precedent, a concrete read-trace
falsifier, and the context-walk S4 verdict.

## Dependency ledger

### Selected dependencies

- Datahike is selected by `deps.edn:21-25` as the local root
  `reference-code/datahike`, excluding its transitive Konserve coordinate.
  The checked-out submodule is
  `9a7a9ef10a95`
  (`git describe`: `0.8.1729-92-g9a7a9ef1`).
- Konserve is selected independently by `deps.edn:26-33` at Git SHA
  `b5c99bc02a71`. The
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
- Listener and commit-time matching:
  `reference-code/datahike/src/datahike/connector.cljc` and
  `src-old/seon/db/writer.clj:2756-3205`.

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
  `value_test.clj`, and `web_test.clj`.

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

The Posh idea existed at three different levels; collapsing them into “Posh
was built” loses the important distinction.

1. **Posh itself was reference-only.** `.gitmodules:113-115` vendors
   `reference-code/posh`, but `deps.edn` contains no Posh coordinate and
   `git log --all -Sposh -- deps.edn` returns no dependency history. The June
   PRD explicitly ruled “no Posh on the classpath” and proposed porting only
   its matcher lessons
   (`docs/prds/archive/agent-runtime/reactive-interface-prd-2026-06-03.md:71-79`).
2. **Exact E/A/V pattern interests were built, but were not the automatic
   render path.** The old writer accepts explicit datom patterns and matches
   entity, attribute, value, and addedness
   (`src-old/seon/db/writer.clj:2982-3002`). Its reverse candidate index is
   attribute-addressed (`:2773-2810,3174-3200`), and the standing test proves
   one exact pattern addresses one of 1,000 interests
   (`test-old/seon/db/writer_interest_test.clj:802-860`). That is real
   Posh-style infrastructure, not merely a design memory.
3. **Automatic render invalidation used captured Datahike dependency plans,
   normally at attribute granularity.** `seon.db` recorded the dependency plan
   returned by each query or pull (`src-old/seon/db.cljc:320-348`), while
   `seon.reactive` owned registration, the newest database value,
   recomputation, and equality suppression
   (`src-old/seon/reactive.cljc:1-7,120-128,252-349`). The writer reduced
   captured plans to dependency attributes and installed those interests
   (`src-old/seon/db/writer.clj:2847-2899`). This computes attributes from the
   real read forms; it does not reconstruct concrete E/A/V pairs from returned
   rows.

The UI therefore did not have one coarse behavior:

- the main Datastar path wrapped rendering in `db/with-read-evidence`, returned
  the aggregate evidence, and registered it through `reactive/observe!`
  (`src-old/seon/web/datastar.cljs:395-446`);
- equal serialized output was suppressed and one computation fanned out to
  equivalent sockets, proven in
  `test-old/seon/web/datastar_test.cljs:341-408`;
- the generic JVM data feed explicitly returned `::db/read-evidence :all`, so
  it woke and re-rendered coarsely
  (`src-old/seon/web/feed.clj:145-153`).

Verdict on precedent: source proves a built selective dependency-matched render
path plus a coarse fallback. It does **not** prove automatic Posh-grade
entity/value pattern extraction for ordinary render queries.

## Read-tracing feasibility

`tmp/query-invalidation/read_trace_probe.clj` uses the actual fresh
`seon.render.agent/agent-header-html` renderer. It temporarily wraps the
renderer’s direct `d/pull`/`d/q` vars, records concrete `(e,a)` pairs present
in pulled results, installs a real Datahike listener, and intersects listener
`:tx-data` with that set.

The constructed results were:

| Transaction | Render dependency | Trace decision | Correct? |
|---|---|---|---|
| change the already returned agent id | `(8, :seon.cluster.agent/id)` recorded | wake | yes |
| change an unrelated noise entity | no intersection | skip | yes |
| change the namespace name read by `d/q` | query result has no E/A provenance | skip | **no**; rendered output changed |
| add a previously absent queried attribute | positive read-set is empty | skip | **no**; query changed from empty to entity 8 |

There is another failure in the renderer itself: it pulled
`:seon.cluster.agent/run`, but because that attribute was absent, a
returned-datom tracer did not record it. Adding the run would also be skipped.
Wildcard pull, reverse refs, rules, predicates, `not`, `or`, database
functions, and dynamic selectors broaden the same problem. A result-level
wrapper can observe values that came back; it cannot recover the index ranges
and negative facts whose absence affected the result.

The wrapper’s 12 warmed batches of 400 existing-renderer calls measured p50
19.20 µs/render without capture and 22.09 µs with concrete-pair capture,
about 1.15×. This small local cost does not rescue correctness.

The “one seam” is not currently present in fresh Seon: all five render owners
call `datahike.api` directly (dependency-ledger call sites above). The old
`seon.db` facade was the one capture seam, but the fresh-tree skill correctly
notes that facade has not landed yet. If selective piece invalidation is
implemented, the seam must cover every `q`, `pull`, and `entity` read and
capture Datahike’s parsed dependency plans—not infer positive `(e,a)` pairs
afterward. `entity` access is open-ended unless the access operation itself
records each requested attribute; touching the whole entity must widen to
`:all`.

## S4 verdict

Recommend **runtime registration of Datahike-computed query/pull dependency
plans, with `:all` as the fail-open case**, backed by the existing per-attribute
commit revisions and query-result cache. Do not build concrete returned-datom
read tracing, and do not build a Seon query parser.

| Mechanism | Can skip whole piece safely? | Absence-safe? | Existing support | Honest cost |
|---|---|---|---|---|
| Concrete returned `(e,a)` trace | no | no | none | ~1.15× wrapper overhead here, plus false skips |
| Seon-authored pattern parser/registration | potentially | only if fully conservative | old exact-pattern matcher only | duplicates Datahike semantics; rules and dynamic forms require continuing maintenance |
| Captured Datahike dependency plans | yes, conservatively between relevant commits | yes, at attribute granularity | `q-with-evidence`, `pull-with-evidence`, plan widening, attribute revisions; old Seon precedent | false wakes for same-attribute unrelated entities; wildcard/dynamic reads become `:all` |
| Datahike result cache alone | no; caller/render still runs | yes for cached `q` | fully built | warm `q` is cheap, but pull/composition/serialization still run |
| Unconditional report wake + equality suppression | only after re-render | yes | current `wake/route!` | simplest and soundest; pays every render on every commit |

For S4, first measure the current unconditional path and Datahike cache evidence
on the real S0-S3 pieces. Add piece-level selective registration only if render
cost or churn measurements justify it. A registration remains process-local
derived memory: after restart the first render computes its plan again. On a
relevant report, render fresh from the one loop-start database value and retain
byte-equality suppression/digests; on an irrelevant report, reuse the prior
bytes because the dependency revision proof says its inputs did not change.
Freshness still outranks cache.

`src/seon/cluster/wake.cljc:112-118` says the unconditional wake survives until
the program graph can compute the read attributes. The prototype’s concrete
pair tracing is **not** that computation: it under-approximates real reads.
Datahike’s execution-aware dependency plan is the computation already present
in the selected dependency. Capturing that plan dynamically at the one read
seam eliminates the hand-list class; authoring a second parser or maintaining
renderer-declared lists would reintroduce it.
