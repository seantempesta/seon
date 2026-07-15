---
type: research
status: active
tags: [research, prd, database, flow, agent]
---

# Unit 1 adversarial retained-test design — 2026-07-15

## Purpose

This report designs the shortest deterministic retained tests for Unit 1's
database-value identity and generation-lifetime contract. It targets Datahike
revision `0b65221586a20182639f2dd7984ca203238ea9f7` and does not modify source or
tests.

The six required adversaries are:

- a forced collision under the superseded `[hash max-tx max-eid]` identity;
- two physical stores;
- sibling branches at one commit;
- connection configuration mismatch;
- stale generation close after reconnect; and
- zero retained generation resources after final release.

They can be covered by five tests. None needs sleeps, random collision search,
live sockets, or lifecycle processes.

## Contract implemented by the selected source

A committed raw `DB` is cacheable only when its `:cache-context` contains
connection id, generation, commit id, and `committed?`; the exact identity is
`[connection-id generation commit-id]`
(`reference-code/datahike/src/datahike/db.cljc:385-397`). Temporal wrappers and
speculative values have no committed identity.

Each first connection reservation creates a fresh generation UUID. Concurrent
or later acquisitions of the same live connection preserve that generation;
configuration or physical-store mismatch is rejected before joining the entry
(`datahike/connections.cljc:37-92,94-111`).

The query cache atom owns both current generation admission and its weighted
LRU. Opening records `connection-id -> generation`. Closing compare-checks the
exact current generation, removes only that admission, and evicts only keys
whose first two identity components match (`datahike/query.cljc:2426-2495`). A
result put performs the same generation comparison in the same atom transition,
so a late computation from a closed generation cannot resurrect a bucket
(`query.cljc:2594-2623`).

Connect attaches the generated cache context and opens cache admission before
publishing the connection (`datahike/connector.cljc:361-372`). Final release
drains the writer, closes the exact query-cache generation, closes secondary
indexes and store, deletes the connection entry, then publishes completion
(`connector.cljc:482-527`).

## Deterministic fixture

Put the new tests beside the existing cache regressions in
`test/datahike/test/query_cache_test.cljc`; keep low-level reservation-only
assertions in `connector_release_test.clj`. The integrated tests below need
`datahike.connections`, `datahike.lru`, and `datahike.store` on CLJ.

Use one fixture helper that isolates process-global registries without depending
on suite order:

```clojure
(defn- with-isolated-generations [f]
  (let [cache-before @dq/query-result-cache]
    (binding [connections/*connections* (atom {})]
      (try
        (dq/clear-query-cache!)
        (is (zero? (:open-generation-count (dq/query-cache-metrics))))
        (f)
        (finally
          ;; Every test must close its own generation before restoring the
          ;; unrelated process-global cache state.
          (is (empty? @connections/*connections*))
          (is (= {:snapshot-count 0
                  :total-weight 0
                  :open-generation-count 0}
                 (dq/query-cache-metrics)))
          (reset! dq/query-result-cache cache-before))))))
```

This sketch intentionally fails if a test leaks a generation. Do not hide a
leak by clearing the cache in `finally` before asserting. Every database uses a
fixed literal UUID local to its test or a unique fixture UUID; determinism comes
from explicit collision fields and gates, not the UUID value.

Helper observations:

```clojure
(defn- cache-keys []
  (set (keys (lru/weighted-entries (:lru @dq/query-result-cache)))))

(defn- connection-id [conn]
  (store/connection-id (:config @conn)))
```

## Test 1 — forced legacy collision across two stores

### Name

`committed-cache-identity-ignores-forced-legacy-collision-across-stores`

### Fixture

Create two memory databases with distinct store UUIDs but the same schema,
entity count, and transaction count. Store `:c/note "store-a"` in A and
`:c/note "store-b"` in B. Connect both and retain `db-a` and `db-b`.

Force the old identity to collide without changing the selected identity:

```clojure
(let [legacy-a (assoc db-a :hash ::forced :max-tx 7 :max-eid 9)
      legacy-b (assoc db-b :hash ::forced :max-tx 7 :max-eid 9)]
  (is (= (mapv legacy-a [:hash :max-tx :max-eid])
         (mapv legacy-b [:hash :max-tx :max-eid])))
  ...)
```

Do not use `with-redefs` on Clojure's `hash`; that would perturb maps, sets, LRU
internals, and query keys. Associating the three legacy fields is the exact,
local deterministic falsifier.

### Actions and assertions

1. Assert `(db/committed-cache-identity legacy-a)` and the B identity differ in
   connection id even if commit ids happen to match.
2. Run the identical query `[:find ?n . :where [_ :c/note ?n]]` on A, then B,
   then A, then B.
3. Assert results remain `"store-a"`, `"store-b"`, `"store-a"`, `"store-b"`.
4. Assert `cache-keys` contains exactly both committed identities.
5. Release A. Assert only B's key and generation remain and B still hits the
   correct result.
6. Release B and assert fixture-zero resources.

### Failure exposed

Any fallback to the legacy triple aliases two physical stores, returns the first
cached value for the second, or scopes release broadly enough to evict B.

This one test covers both forced legacy collision and two stores.

## Test 2 — sibling branches share a commit but not a cache scope

### Name

`sibling-branches-at-one-commit-have-independent-generations`

### Fixture

Create one memory database, transact schema plus one note, and capture its head
commit id. From that exact head create branches `:left` and `:right` before
either branch changes:

```clojure
(let [head (d/commit-id @main)]
  (d/branch! main head :left)
  (d/branch! main head :right))
```

Connect using the same store config with `:branch :left` and `:branch :right`.
Release `main` once the branches are durable so the assertion set contains only
the sibling owners.

### Actions and assertions

1. Assert both branch database values report the same commit id.
2. Assert their committed cache identities differ in connection id because the
   connection id includes branch; their generation UUIDs also differ.
3. Execute the identical note query against both and assert two cache buckets.
4. Transact `"left-new"` only on left; assert left advances while right remains
   at the original commit and result.
5. Release left. Assert right's admission and bucket survive.
6. Release right and assert fixture-zero resources.

### Failure exposed

A cache keyed by commit id alone aliases sibling attachment owners. An eviction
keyed only by physical store removes the surviving branch. The test rejects both
errors with one branch fixture.

## Test 3 — configuration mismatch creates no reference or generation

### Name

`config-mismatch-does-not-acquire-or-open-cache-generation`

### Fixture

Create and connect one memory database with `:keep-history? true`. Populate one
cache entry. Capture:

- the exact connection registry entry and reference count;
- committed cache identity;
- full `query-cache-metrics`; and
- `cache-keys`.

Attempt `d/connect` to the same store/branch with `:keep-history? false`.

### Assertions

1. The attempt throws `:config-does-not-match-existing-connections`.
2. Reference count stays one.
3. Registry generation stays identical.
4. `open-generation-count`, snapshot count, total weight, and cache keys remain
   byte-for-byte/equality unchanged.
5. The original connection remains queryable and returns the cached value.
6. Its final release reaches fixture-zero resources.

### Why retain this despite existing tests

`connector_release_test.clj:224-268` already proves that opening and live
configuration mismatch do not leak a connection reference. Unit 1 additionally
needs the cache-generation assertion because generation admission is new. The
shortest implementation may extend `config-mismatch-returns-its-acquired-
reference` with metrics, but keeping cache assertions in `query_cache_test`
avoids coupling the connector test to query internals. Do not duplicate both.

## Test 4 — stale close cannot erase a reconnected generation

### Name

`stale-generation-close-after-reconnect-is-an-exact-no-op`

### Fixture and deterministic sequence

No race or sleep is needed:

1. Connect as generation G1, query once, and capture `[connection-id G1 C1]`.
2. Release fully; assert zero admission and buckets.
3. Reconnect as G2 and assert `G2 != G1`.
4. Query once so G2 owns one visible bucket.
5. Invoke `(dq/close-query-cache-generation! connection-id G1)` directly. This
   models delayed cleanup from the old generation after reconnect.

### Assertions

1. The current generation map still contains `connection-id -> G2`.
2. G2's exact cache key remains in `cache-keys`.
3. Metrics remain one open generation and one snapshot with unchanged weight.
4. Querying G2 still returns the correct value and does not recompute if the
   test uses a counting query function/hook.
5. Closing G2 removes its admission and exact bucket.

### Failure exposed

An unconditional close by connection id lets delayed G1 cleanup erase G2.
Comparing the generation inside the same query-cache atom transition makes the
stale operation a no-op.

The existing `final-release-atomically-fences-late-cache-put`
(`query_cache_test.cljc:49-75`) proves late G1 put rejection and fresh generation
identity. It does not explicitly call stale G1 close after G2 is populated; this
test fills only that missing edge.

## Test 5 — final release retains no generation resources

### Name

`final-release-removes-all-exact-generation-resources`

### Fixture

Use the two-store plus two-branch resources already constructed by Tests 1 and
2 if the test runner supports a single integrated test; otherwise create one
store with main and sibling branch plus a second independent store. Populate at
least one query bucket per live connection.

Record each exact identity and assert before release:

- registry entries equal active connection count;
- open generation count equals active connection count;
- every expected identity occurs in `cache-keys`; and
- snapshot count/weight are positive.

Release connections one at a time in a deliberately noncreation order. After
each release assert only that connection/generation's registry entry, generation
admission, and cache keys disappear. The unrelated resources must remain usable.

After the last release assert:

```clojure
(is (empty? @connections/*connections*))
(is (= {:snapshot-count 0
        :total-weight 0
        :open-generation-count 0}
       (dq/query-cache-metrics)))
(is (empty? (cache-keys)))
```

Then delete the databases. Database deletion is not release proof and must occur
only after the zero-resource assertions.

### Scope of “resources”

These deterministic assertions cover every generation resource introduced by
Unit 1: connection registry ownership, generation admission, and completed query
buckets/weight. Store handles and secondary indexes are already covered by
connector release tests, including failed opening cleanup
(`connector_release_test.clj:270-295` and following). Do not add weak GC/heap
reachability assertions to this unit. A later resource-observability unit can
expose backend cache/native handle counts explicitly.

## Minimal retained suite structure

The shortest nonredundant suite is:

1. two stores + forced legacy collision + scoped first release;
2. sibling branches at same commit + scoped first release;
3. mismatch leaves connection/cache state unchanged;
4. stale G1 close cannot touch populated G2; and
5. integrated multi-owner final-release zero census.

If runtime cost is a concern, fold test 5's final census into tests 1 and 2 and
add one pure assertion helper used by every `finally`. Retain a named test 5 only
if graduation requires one visible multi-owner lifecycle proof. Do not create
six large fixtures merely to mirror six English adversaries.

## Exact assertion helpers, not implementation hooks

Tests may inspect these deliberate or existing surfaces:

- `db/committed-cache-identity` — public internal identity law;
- `dq/query-cache-metrics` — bounded occupancy;
- `dq/query-result-cache` plus `lru/weighted-entries` — exact keys in retained
  dependency tests;
- `connections/*connections*` — existing connector lifecycle test surface; and
- `store/connection-id` — process-local connection owner.

Only the stale-close test should call `close-query-cache-generation!` directly,
because its subject is idempotent generation fencing. Avoid redefining
`result-cache-get` or `db-cache-key`; assertions should pass through real `d/q`.

## Race tests deliberately excluded

- Do not use probabilistic Futures to make close and reconnect overlap. Datahike
  rejects reconnect while the zero-reference entry is releasing; the relevant
  stale cleanup edge is deterministically modeled by replaying G1 close after G2
  opens.
- Do not search for natural hash collisions. Associate the legacy identity
  fields explicitly.
- Do not compare only metrics. Exact keys and correct query results prove
  isolation; metrics prove bounded census.
- Do not assert one cache bucket per query invocation. Hits, propagation, and
  weight eviction are allowed; fixtures must choose limits large enough to keep
  their expected buckets.
- Do not use global cache clear as the behavior under test. It would hide scoped
  eviction defects.

## Acceptance matrix

| Adversary | Identity assertion | Behavioral assertion | Cleanup assertion |
|---|---|---|---|
| Forced legacy collision | Old triples equal; committed identities differ | A/B/A/B results never cross | Release A preserves B |
| Two stores | Connection-id store owners differ | Same query is isolated | Each exact generation evicts alone |
| Sibling branches/same commit | Commit equal; connection/generation differ | Left mutation never changes right | Left release preserves right |
| Config mismatch | Original generation unchanged | Original cached query remains usable | No new ref/admission/bucket |
| Stale close | G1 differs from G2 | G1 close leaves G2 hit/result | G2 close reaches zero |
| Final release | Every live identity enumerated | Survivors work after each partial close | Registry, generations, buckets, weight zero |

## Recommended implementation order

1. Add assertion helpers and the deterministic forced-collision/two-store test.
2. Add same-commit sibling branch isolation.
3. Extend one existing mismatch test with cache-generation census.
4. Add the direct stale-close-after-reconnect test.
5. Decide whether the repeated zero-resource fixture assertions make a separate
   integrated final-release test redundant.

Run only the focused query-cache and connector-release namespaces first. Unit 1
graduates when all five contracts pass repeatedly without sleeps and the full
Datahike suite retains no global cache/connection state between tests.
