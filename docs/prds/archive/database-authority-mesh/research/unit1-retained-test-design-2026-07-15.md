---
type: research
status: active
tags: [research, prd, database, capability]
---

# Unit 1 retained-test and hazard design — 2026-07-15

## Scope

This is an independent proof review of
[[exact-value-identity-proof-2026-07-15]]. It specifies retained tests and
implementation hazards for exact committed raw-DB cache identity. It does not
change Datahike source, tests, or lifecycle.

Selected Datahike: `9ada755087228e10cfb179fa5779ce227a6ed220`.

## Highest-risk findings

### A completing open currently reconstructs and can drop generation

`reserve-connection-opening!` creates an opening entry, but
`complete-connection-opening!` replaces that entry with a newly constructed map
containing only connection, count, acquisition key, physical-store key, and
write hooks (`connections.cljc:37-106`). Adding generation only to reservation
will therefore silently lose it at publication.

Required implementation law:

- mint generation in the first atomic reservation;
- return it to the owner;
- retain it through opening waiters;
- copy it into the published entry explicitly;
- return or derive the same generation for existing acquisitions; and
- return the exact generation to the final releaser.

Place the state-machine unit tests in
`test/datahike/test/connector_release_test.clj`, which already directly probes
reservation state (`connector_release_test.clj:31-48`).

### A completed query can resurrect an evicted released generation

Current query execution is:

```text
cache lookup -> uncached computation -> cache put
```

with no atomic generation-admission check (`query.cljc:2559-2585,4077-4083`).
If final release closes and evicts a generation while a query is between compute
and put, the old query can recreate the released bucket after eviction.
Generation prevents damage to a reconnect but does not prevent leaked old
results.

Unit 1 therefore needs cache-generation admission state before Unit 2
single-flight:

```clojure
{:datahike.cache/open-generations #{[scope generation]}
 :datahike.cache/completed weighted-lru}
```

Opening registers the generation. Final release atomically removes it and
evicts its completed buckets in one cache-state swap. `result-cache-put!` may
publish only when the generation is still open in that same state. Lookup also
rejects a closed generation.

Do not use the connection registry as a separate non-atomic admission check:
release can occur between checking the registry and updating the cache.

### Writer batching requires a union, not the final report's attributes

The transaction loop advances an in-memory speculative `old`, while the commit
loop greedily drains several `[tx-report callback]` pairs and commits only the
last report's DB (`writer.cljc:155-227`). Every callback receives that same final
committed DB.

Moving propagation to committed publication is correct only if modified
attributes are unioned across every report in `txs`. Using the final report's
datoms can miss an earlier transaction's dependency and propagate a stale
result.

The parent for propagation is `@connection` immediately before reset, not any
intermediate report's `:db-before`. Attach the child's committed cache context,
propagate parent to final child with the union, then reset/publish.

### DB record transformations preserve new fields automatically—including stale ones

`db-transient` and `db-persistent!` update index fields on the same record and
preserve every other record field (`db.cljc:198-231`). Transaction paths built
from that record therefore inherit `cache-context`. Adding the field does not
make speculative values uncached by itself.

Explicitly clear it on every mutation entry/result path:

- `core/with` / `db-with`;
- writer `transact!`;
- `load-entities-with` / writer `load-entities`;
- merge writer;
- secondary-index installation, whose report has empty `:tx-data`; and
- any direct DB transformation returning a raw `DB` not known durably committed.

A single clearing point immediately after every immutable mutation is safer
than remembering callers, but retained tests must exercise all public paths.

### WeightedLRU has no removal or collection API

`WeightedLRU` implements lookup, association, and contains only. It is neither
seqable nor countable and has no `dissoc` implementation
(`lru.cljc:131-149`). Scoped eviction cannot use ordinary `filter`, `dissoc`, or
`into` on the cache object.

Add one pure LRU removal operation, such as:

```clojure
(lru/evict-where weighted-lru predicate)
```

It must update `:key-value`, `:gen-key`, `:key-gen`, `:weights`, and
`:total-weight` consistently using the existing `evict-key` logic
(`lru.cljc:79-129`). Test it against the independent reference model in
`lru_weighted_property_test.cljc`, not only by inspecting one example.

### Release ordering must close cache admission before cleanup can race

Final `release` first moves the connection count to zero, then drains writer,
closes secondary indexes, releases store, and deletes the connection registry
entry (`connector.cljc:438-510`). New connect sees `:releasing` until deletion.

Required order:

1. atomically mark the connection reference final/zero;
2. atomically close cache-generation admission;
3. drain the accepted writer work;
4. remove listeners/secondary resources according to existing owner order;
5. evict completed entries for the closed generation if not combined with step
   2;
6. release store and delete registry entry; and
7. publish one completion/failure to concurrent releasers.

Closing cache admission early prevents late query publication. It does not
cancel immutable queries; they may return their ordinary result to callers but
cannot retain it after release.

On cleanup failure, the current implementation still deletes the connection and
reports `:connection-release-failed` to all releasers
(`connector_release_test.clj:101-143`). Cache generation must remain closed and
evicted on this path; reopening it would resurrect an owner whose resources were
partially closed.

## Record construction and serialization hazards

### Constructor compatibility

Search found no direct `DB.` or `->DB` constructor call in Datahike source/tests;
construction uses `map->DB` in `empty-db` and `init-db`
(`db.cljc:910-929,931-979`). Adding one record field should therefore avoid
positional constructor arity breakage inside this checkout. Still run AOT and
CLJS compilation because downstream Java/compiled callers could use the record
constructor.

### Storage exclusion is currently explicit and favorable

`db->stored` destructures a fixed field list and constructs an explicit stored
map (`writing.cljc:49-181`). A new `cache-context` record field will not be
serialized unless someone explicitly adds it. `stored->db` starts from
`empty-db` and assigns durable fields explicitly (`writing.cljc:227-285`), so
the restored context begins nil and must be attached by connector after the
generation is known.

Retain a serialization test anyway. An identity-preserving memory/tiered store
can expose accidental object retention that byte serialization hides; the
comments at `writing.cljc:136-141,248-258` explicitly warn about this class of
bug.

### Commit hashing must not see process state

`create-commit-id` consumes DB `hash`, counters, metadata, and optionally the
stored form's Merkle roots/schema metadata (`writing.cljc:332-351`). Keep
`cache-context` outside `:meta` and `:config`: both participate in durable
identity or storage. A dedicated DB field excluded by `db->stored` is safer.

Regression: attaching two different process generations to the same raw
committed content must not change `commit-id`, printed DB durable identity, DB
hash, datoms, or stored bytes/map.

### Equality and printing intentionally ignore process context

`equiv-db` compares hash, schema, and EAVT, not other record fields
(`db.cljc:675-687`). DB printers emit store/commit/counters, not arbitrary
fields (`db.cljc:689-739`). Preserve this: cache ownership is not database
semantic equality and must not appear in serialized/printed values.

## Exact retained test placement and forms

The forms below are implementation-ready sketches. Use existing temp-db helpers
and `try/finally` cleanup in the actual tests.

### `query_cache_test.cljc` — cacheability identity

Add portable helpers that inspect cache state through a supported metrics/test
helper rather than JVM-only `.-state` where possible.

```clojure
(deftest connected-committed-raw-db-is-cacheable
  (with-temp-db label-schema
    (fn [conn]
      (d/transact conn [{:c/id "x"}])
      (dq/clear-query-cache!)
      (let [db @conn
            id (dq/committed-value-identity db)]
        (is (= [(store/connection-id (:config db))
                (:datahike.cache/generation id)
                (d/commit-id db)]
               id))
        (d/q '[:find ?e :where [?e :c/id]] db)
        (is (= 1 (:datahike.cache/buckets
                  (dq/cache-metrics id))))))))
```

Prefer one namespaced identity map or one vector consistently; the assertion
must match the final public/internal contract rather than the mixed illustrative
access above.

```clojure
(deftest speculative-and-temporal-values-do-not-cache
  (let [raw @conn
        values [(d/db-with raw [{:c/id "spec"}])
                (d/history raw)
                (d/as-of raw (d/basis-t raw))
                (d/since raw datahike.constants/tx0)
                (d/filter raw (constantly true))]]
    (doseq [db values]
      (dq/clear-query-cache!)
      (is (nil? (dq/committed-value-identity db)))
      (d/q '[:find ?e :where [?e :c/id]] db)
      (is (zero? (:datahike.cache/buckets (dq/cache-metrics)))))))
```

Also cover `d/with` report `:db-after`, `load-entities`, merge, and secondary
index installation where available. For a mutation result returned before
commit, assert identity nil; for the report delivered after writer commit,
assert identity equals the connection head.

### `query_cache_test.cljc` — collision/store/branch isolation

```clojure
(deftest forced-legacy-hash-collision-cannot-share
  ;; Reuse the deterministic collision from the exact-value research.
  ;; Attach different committed scope/generation/commit identities and assert
  ;; A=:a, uncached-B=:b, cached-B=:b.
  )

(deftest sibling-branches-at-one-commit-have-distinct-cache-scopes
  (d/branch! main :db :feature)
  (let [feature (d/connect (assoc cfg :branch :feature))]
    (is (= (d/commit-id @main) (d/commit-id @feature)))
    (is (not= (dq/committed-value-identity @main)
              (dq/committed-value-identity @feature)))
    ;; Populate both with a predicate counter keyed per branch; each computes
    ;; once, then hits only its own bucket.
    ))
```

Two-store isolation should force the same commit component in a constructed raw
test value while preserving different attached scopes, rather than relying on
random transaction metadata to create different commit IDs.

### `query_cache_test.cljc` — committed propagation and writer batches

The existing invalidation tests cover pull/where/wildcard behavior
(`query_cache_test.cljc:35-131`). Add committed-report tests rather than
speculative `db-with` tests.

```clojure
(deftest unrelated-committed-write-propagates-once
  (seed-and-count-query! conn :c/id)
  (let [parent @conn
        report (d/transact conn [{:other/value 1}])
        child (:db-after report)]
    (is (= child @conn))
    (is (not= (dq/committed-value-identity parent)
              (dq/committed-value-identity child)))
    (is (= :hit (query-with-cache-evidence child)))))
```

For batching, use latches around `writing/commit!` or the writer queue, following
`connector_release_test.clj:50-99`. Submit two transactions without awaiting:

- first changes attribute A used by cached query;
- second changes unrelated attribute B;
- force both into one writer batch;
- release commit gate;
- assert every report shares final committed identity;
- assert A-dependent result recomputes and is correct; and
- assert a C-dependent result propagates/hits.

This directly falsifies “use only final report attrs.” Repeat with first
unrelated, second dependent to prove order independence.

### `connector_release_test.clj` — generation state machine

Extend `connection-entry` helper (`connector_release_test.clj:16-29`).

```clojure
(deftest concurrent-first-connects-share-one-generation
  ;; Gate ks/connect-store as existing concurrent-first-connect test does.
  ;; Assert one store open, identical conn, refcount N, and exactly one equal
  ;; generation across owner, waiters, published registry, and @conn identity.
  )

(deftest retained-release-keeps-generation
  (let [a (d/connect cfg) b (d/connect cfg) g (generation a)]
    (d/release a)
    (is (= g (generation b)))
    (is (cache-generation-open? scope g))))

(deftest final-release-reconnect-mints-generation
  (let [g1 (generation conn)]
    (d/release conn)
    (let [re (d/connect cfg)]
      (is (not= g1 (generation re)))
      (is (= (connection-id cfg) (scope re))))))
```

Configuration mismatch and `release-all?` tests already exist nearby; add
generation assertions to those paths rather than duplicating fixtures.

### `connector_release_test.clj` — late-put resurrection barrier

This is the essential new concurrency test. Add a private test hook around
result-cache publication or redefine `result-cache-put!` through a deliberately
exposed test seam. Do not sleep to guess timing.

```clojure
(deftest final-release-prevents-late-cache-put
  (let [compute-entered (CountDownLatch. 1)
        continue-compute (CountDownLatch. 1)
        db @conn
        old-id (dq/committed-value-identity db)
        query-result
        (future
          (d/q query db
               (fn [v]
                 (.countDown compute-entered)
                 (.await continue-compute 10 TimeUnit/SECONDS)
                 v)))]
    (is (.await compute-entered 10 TimeUnit/SECONDS))
    (is (nil? (d/release conn)))
    (.countDown continue-compute)
    @query-result
    (is (false? (dq/cache-generation-open? old-id)))
    (is (zero? (:datahike.cache/entries (dq/cache-metrics old-id))))))
```

The query may return successfully; only retention is forbidden. Then reconnect,
populate `g2`, invoke an idempotent stale `g1` eviction, and assert the `g2`
entry remains.

### `connector_release_test.clj` — release failure

Extend `concurrent-releasers-observe-the-same-failure`
(`connector_release_test.clj:101-143`): after synthetic shutdown failure, assert
the old generation is closed/empty, then reconnect and assert a new generation
can cache normally.

### `lru_weighted_test.cljc` and property test — removal bookkeeping

Examples:

```clojure
(deftest weighted-lru-evict-where-updates-all-bookkeeping
  (let [c (-> (lru/weighted-lru 10 100 count)
              (assoc [:a 1] [1 2])
              (assoc [:b 1] [3])
              (assoc [:a 2] [4 5 6]))
        c' (lru/evict-where c #(= :a (first %)))
        st (state c')]
    (is (= #{[:b 1]} (set (keys (:key-value st)))))
    (is (= #{[:b 1]} (set (keys (:key-gen st)))))
    (is (= #{[:b 1]} (set (keys (:weights st)))))
    (is (= 1 (:total-weight st)))))
```

Extend the independent list-based reference model with remove-by-predicate and
generate mixed put/evict operations. Assert surviving values, LRU order,
weights, and later eviction after a removal. Run in both CLJ and the existing
Node runner, which already includes weighted-LRU tests
(`nodejs_test.cljs:35-53`).

### Storage/round-trip tests

Place near existing tiered-storage tests because identity-preserving stores are
the dangerous case.

```clojure
(deftest cache-context-never-enters-stored-form
  (let [db @conn
        [_ stored] (writing/db->stored db false)]
    (is (some? (:cache-context db)))
    (is (not (contains? stored :cache-context)))
    (is (= (d/commit-id db)
           (writing/create-commit-id db stored)))))
```

Round-trip stored→DB must start without context, then connector attachment adds
the current generation. Compare stored form, `pr-str`, DB hash, commit ID, and
datoms before/after attaching different generations.

## CLJC hazards and proof

- `defrecord-updatable` expands differently for CLJ and CLJS
  (`db.cljc:96-126`). Field access and helper code must compile on both.
- ClojureScript `WeightedLRU` has a separate deftype implementation
  (`lru.cljc:131-149`); add removal to shared functions or both platform method
  surfaces.
- Current Node tests include weighted-LRU suites but not
  `query-cache-test` (`nodejs_test.cljs:35-53`). Add the portable identity/cache
  namespace or a focused portable subset to the Node runner.
- JVM generation can use `random-uuid`; CLJS has the same ordinary UUID value,
  but never rely on JVM identity or `WeakHashMap`.
- Do not put connection, atom, callback, Future, channel, or JS object into
  `cache-context`; the intended vector/map must be ordinary comparable data.
- Temporal wrappers hold an origin DB. Even though `instance? DB` currently
  excludes them from result caching (`query.cljc:4066-4069`), assert nil
  committed identity explicitly so a later generalized predicate cannot admit
  them accidentally.

## Batching and publication hazards

- Attach committed identity before `reset! connection` and before callbacks, so
  deref, reports, and listeners all observe the same identified value.
- Compute modified attributes from every report's effective datoms. With
  attribute refs enabled, resolve numeric attrs through the final child's
  `ref-ident-map`, matching current normalization
  (`writing.cljc:536-550`).
- If any report has unknown/empty modified attributes for a real state change,
  skip propagation conservatively for the whole batch. Secondary-index install
  returns empty `:tx-data` (`writing.cljc:787-799`); never propagate a primary
  result through an unclassified semantic index/schema change merely because
  another report has known attrs.
- Commit failure must neither attach child identity nor propagate.
- Every callback in a writer batch receives the final commit DB; tests must not
  expect per-transaction commit IDs (`writer.cljc:203-227`).
- Listener callbacks run after the committed report returns through writer
  dispatch (`writer.cljc:350-368`). Their `:db-after` must already carry final
  identity.

## Performance hazards and gates

### Avoid scanning more than the bounded snapshot count

The outer result cache defaults to 64 snapshot buckets. A pure
`evict-where` scan is acceptable for Unit 1 if measured release time remains
small. Do not add a second mutable scope index preemptively. If snapshot limits
grow or 1/2/4/8 database release measurements show scanning contention, fold a
scope-to-key index into the same immutable weighted-LRU/cache state.

### Do not serialize queries on the cache atom

Cache atom swaps may read/touch/publish/evict metadata only. Query computation
must remain outside the swap. The closed-generation membership test and result
publication must occur in one final swap, but that function receives an already
computed result.

### Precompute identity

Attach the final identity vector once to committed DB. Lookup should not rebuild
config fingerprints or hash DB/index content. Assert in a microbenchmark that a
cache hit does not enumerate datoms or call `equiv-db`.

### Propagate once per durable writer batch

Record propagation count and batch size in test instrumentation. A batch of N
transactions should perform one parent-bucket read and at most one child-bucket
publication, not N speculative propagations.

### Required retained measurements

- hit/miss latency and allocation before/after identity change;
- one committed propagation with 0/10/1,000 cached query entries;
- scoped close/eviction with 1/16/64 buckets;
- release during a long query, including late-put rejection;
- 1/2/4/8 databases concurrently querying while one releases;
- CLJ PSS/HHT and CLJS in-memory cache paths; and
- retained heap confirming cache keys contain no DB/index/connection reference.

## Implementation review checklist

- [ ] Generation survives reservation, waiter, publication, acquire, release,
      failure, and reconnect state transitions.
- [ ] Raw restored DB gets context only after connector knows generation.
- [ ] Every speculative mutation clears context.
- [ ] Context is absent from config, metadata, stored maps, commit hashes, and
      printing.
- [ ] Cache get/put require an open committed generation.
- [ ] Close plus eviction is atomic with respect to late put.
- [ ] WeightedLRU removal updates all five bookkeeping structures.
- [ ] Writer batch unions all known modified attrs and becomes conservative on
      unknown state changes.
- [ ] Propagation occurs once after commit ID assignment and before publication.
- [ ] Release failure cannot reopen or retain the generation.
- [ ] Stale old-generation eviction cannot affect reconnect.
- [ ] Temporal, filtered, detached, and speculative DBs remain uncached.
- [ ] Query computation is never performed under cache/connection locks.
- [ ] Portable tests run in both CLJ and the Node CLJS runner.

## Unit 1 proof exit

The implementation is ready for integration only when:

1. deterministic retained tests cover every identity/cacheability class;
2. a barrier test proves no late query can resurrect a released generation;
3. a forced writer batch proves union invalidation and one committed
   propagation;
4. state-machine tests prove one shared generation and a new generation after
   reconnect;
5. storage tests prove process context never affects durable identity;
6. weighted-LRU property tests prove scoped removal bookkeeping in CLJ and
   CLJS;
7. release failure and stale cleanup leave no old entries and preserve the new
   generation; and
8. focused performance/heap evidence shows no global query gate, no DB retained
   in keys, and bounded release overhead.
