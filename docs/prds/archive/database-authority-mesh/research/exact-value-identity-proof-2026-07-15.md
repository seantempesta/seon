---
type: research
status: active
tags: [research, prd, database, capability]
---

# Exact committed-value identity proof — 2026-07-15

## Scope and settled first-cut law

This proof spike settles the first implementable Datahike cache-identity unit.
It does not change Datahike or Seon source.

First-cut law:

> A query result is cacheable only for a raw, durably committed `DB` value
> attached to one active connection/branch generation. Its exact process-local
> cache identity is `(connection-id, generation, commit-id)`. Temporal,
> filtered, detached, and speculative values are uncached. A final release
> evicts only its exact `(connection-id, generation)` scope.

This deliberately chooses correctness and releasable ownership before temporal
or cross-branch sharing. It leaves Datahike's ordinary query semantics intact
and can expand later without changing Seon's durable coordinate protocol.

Selected dependency: Datahike
`9ada755087228e10cfb179fa5779ce227a6ed220`.

## Why the current identity cannot remain

The current result-cache bucket key is `[hash max-tx max-eid]`
(`query.cljc:2457-2460`). The prior proof demonstrated a deterministic collision
that returned one DB's result for another
([[datahike-cache-identity-2026-07-15]]). It also contains no physical store,
branch, or lifetime scope, so final release cannot evict one database without
searching or clearing unrelated values.

The raw `DB` stored hash is the additive sum of datom hashes
(`db.cljc:959-975`; `db/transaction.cljc:395-432,508`). `DB` equality falls
back to schema plus a full EAVT comparison only when the hash agrees
(`db.cljc:675-687`), but the result cache uses the three-number vector directly
and never performs that equality check.

## Existing exact ingredients

### Connection and branch scope

`datahike.store/connection-id` is:

```clojure
[store-id branch]
```

for a self writer, with a remote-writer backend discriminator appended when
needed (`store.cljc:41-61`). This already distinguishes physical stores,
branches, and incompatible local/remote resource owners.

Connection acquisition additionally compares a semantic acquisition key and a
physical-store key before sharing one live connection
(`connector.cljc:232-249`; `connections.cljc:37-88`). Therefore one active
connection generation has one adopted semantic configuration. The cache key
does not need to copy every configuration dimension when it is scoped to that
generation.

### Durable committed-value identity

Every successful commit receives a commit ID after index roots flush. Ordinary
mode derives it from `[hash max-tx max-eid meta]`; audit-grade mode uses Merkle
roots, schema metadata, counters, and metadata
(`writing.cljc:332-351,441-463`). The commit ID is stamped on both the returned
DB and stored form (`writing.cljc:448-451`). `commit-id` is already a stable,
remote-capable Datahike API operation
(`api/specification.cljc:701-711`).

Within one connection/branch generation, the commit ID is Datahike's canonical
name for one durable raw head. The first cut treats this existing canonical ID
as exact; it does not invent another content fingerprint.

### Why a generation is necessary

`connection-id` survives release/reconnect. Final release removes the registry
entry only after writer, secondary-index, and store cleanup
(`connector.cljc:438-510`). Later reconnect creates a new set of process-local
resources under the same `connection-id`.

An asynchronous stale cleanup keyed only by `connection-id` could therefore
evict a newly reconnected cache. A freshly minted generation at the opening
reservation makes the lifetime explicit. Every cache entry and eviction names
that generation, so old cleanup is structurally unable to address new entries.

## Proposed internal data shape

### Connection registry entry

Extend Datahike's existing connection entry, not a second registry:

```clojure
{connection-id
 {:conn connection
  :count 3
  :generation #uuid "process-local-lifetime"
  :acquisition-key {...}
  :physical-store-key {...}
  :write-hooks ...}}
```

The opening owner mints `generation`; opening waiters and acquired references
share it. Reconnect after final release mints a different generation.

### Raw DB cache context

Add one process-local field to raw `DB`:

```clojure
{:datahike.cache/scope connection-id
 :datahike.cache/generation generation
 :datahike.cache/commit-id commit-id}
```

The field is called `cache-context` below. It is never serialized by
`db->stored`, never included in commit hashing, and never printed as durable DB
identity. It exists only on a raw DB value currently owned by the active
connection generation.

Cacheability predicate:

```clojure
(defn committed-cache-identity [db]
  (when (and (instance? datahike.db.DB db)
             (= (get-in db [:cache-context
                            :datahike.cache/commit-id])
                (get-in db [:meta :datahike/commit-id])))
    [(get-in db [:cache-context :datahike.cache/scope])
     (get-in db [:cache-context :datahike.cache/generation])
     (get-in db [:cache-context :datahike.cache/commit-id])]))
```

The actual implementation should use qualified internal keys and direct field
access where profiling supports it. The equality check is load-bearing: a
transaction-created speculative DB initially carries its parent's metadata.
Mutation must clear `cache-context`, and only durable commit publication may
restore it with the new commit ID.

### Result-cache shape

```clojure
WeightedLRU
{[connection-id generation commit-id]
 {semantic-query-key
  {:result result
   :attrs attribute-dependencies
   :weight structural-weight}}}
```

No raw DB or connection is retained in the key. The result may contain ordinary
query data only; existing weight certification still decides admission.

### Internal functions

Proposed minimal functions, names illustrative:

```clojure
(datahike.cache/attach-committed-context
  db connection-id generation commit-id)

(datahike.cache/clear-context db)

(datahike.cache/committed-value-identity db)
;; => [connection-id generation commit-id] or nil

(datahike.query/propagate-committed-cache!
  parent-committed-db child-committed-db modified-attrs)

(datahike.query/evict-cache-generation!
  connection-id generation)

(datahike.query/cache-metrics
  {:datahike.cache/scope connection-id
   :datahike.cache/generation generation})
```

`evict-cache-generation!` is idempotent. It removes only keys whose first two
components exactly match. It must also remove the same generation's in-flight
keys when single-flight lands.

## Disposable multi-store/branch/reconnect probe

The in-memory probe created one store, transacted a value on `:db`, branched it
to `:feature`, connected the branch, populated the current result cache,
committed an unrelated attribute on main, released both connections, and
reconnected main.

Raw branch and propagation evidence:

```clojure
{:main
 {:scope [#uuid "7192dfc8-5867-4cb9-85bd-db711d8c83f6" :db]
  :commit #uuid "6a580983-e60a-5435-b67a-953f0dbac621"}
 :feature
 {:scope [#uuid "7192dfc8-5867-4cb9-85bd-db711d8c83f6" :feature]
  :commit #uuid "6a580983-e60a-5435-b67a-953f0dbac621"}
 :same-commit true
 :same-scope false
 :propagation
 {:parent-key [3550761130 536870913 1]
  :child-key [4212634804 536870914 2]
  :child-bucket? true}}
```

The branch initially points to the exact same durable commit, but Datahike's
existing connection identity gives it a different scope. The approved first cut
therefore does not share results across branches accidentally. The current
cache did propagate an unaffected entry from main's parent bucket to its child
bucket.

After releasing and reconnecting main:

```clojure
{:reconnect-scope
 [#uuid "7192dfc8-5867-4cb9-85bd-db711d8c83f6" :db]
 :reconnect-commit
 #uuid "6a580983-99f0-5d93-ad65-c5d18d68c728"}
```

The scope is stable across reconnect, as required for database identity. The
head commit is the later committed child. The proposed generation, absent in
current source, distinguishes the old released resource lifetime from the new
one.

A second physical store necessarily has a different store UUID in its
`connection-id`; even if it resolves the same commit ID, its complete first-cut
identity differs at the scope component. The earlier two-store probe observed
distinct store scopes and no equality between DB values. The regression suite
below makes this a retained law rather than relying on random transaction
metadata.

## Generation-fenced eviction model probe

A pure disposable model installed an old entry under generation `g1`, removed
the old registry owner, reconnected under `g2`, installed a new entry, then ran
stale `g1` eviction.

Raw result:

```clojure
{:registry
 {[#uuid "34a23672-92df-44f1-a9d0-1c5b4938118d" :db]
  {:generation #uuid "23f1dfbb-9c64-42fe-ac01-f1f7a1c8f0d1"
   :count 1}}
 :cache
 {[[#uuid "34a23672-92df-44f1-a9d0-1c5b4938118d" :db]
   #uuid "23f1dfbb-9c64-42fe-ac01-f1f7a1c8f0d1"
   #uuid "e4d736f7-f7b8-4c99-a7f1-5edc6f06496f"]
  :new}
 :new-survived? true
 :old-removed? true}
```

This proves the data model's non-interference law: eviction of
`(scope, g1)` cannot name `(scope, g2, commit)`. It does not prove the concurrent
release implementation; retained barrier tests must do that.

## Propagation must move to committed publication

The current propagation point is too early for commit-based identity.

- `core/with` propagates immediately from the input DB to speculative
  `:db-after` (`core.cljc:135-147`).
- committed transaction assembly also propagates during
  `complete-db-update`, before durable writer commit
  (`writing.cljc:536-551`).
- the writer assigns the durable commit ID later in `commit!`
  (`writing.cljc:441-463`).
- writer batching commits only the latest accumulated DB and returns that one
  committed DB to every accepted report (`writer.cljc:203-227`).

First-cut implementation law:

1. Any `with`/transaction mutation clears `cache-context`; speculative values
   are uncached and receive no propagated bucket.
2. The writer captures the prior committed connection DB before publication.
3. A writer batch unions user-visible modified attributes across every accepted
   transaction in the batch.
4. After `commit!` returns the child with its durable commit ID, the writer
   attaches the same connection scope/generation plus that child commit ID.
5. It propagates once from prior committed parent to final committed child,
   invalidating the union of modified attributes.
6. It publishes/reset!s the committed child and returns it in reports.

This removes speculative/intermediate cache buckets and makes every retained
bucket durable, resolvable, and releasable. It also preserves structural sharing
for unaffected completed results.

Schema or purge operations whose modified attributes cannot be proven continue
to skip propagation conservatively. In-flight single-flight state never
propagates.

## No-false-sharing proof by construction

For cacheable raw values `a` and `b`, identity equality requires:

```clojure
(and (= (connection-id a) (connection-id b))
     (= (generation a) (generation b))
     (= (commit-id a) (commit-id b)))
```

Therefore:

- two stores differ by store UUID;
- two branches differ by branch in `connection-id`, even at one shared commit;
- two connection lifetimes differ by generation;
- two committed values in one lifetime differ by commit ID;
- a reconnect cannot inherit an old lifetime's entries;
- a speculative value has no identity and cannot hit or populate;
- temporal/filtered wrappers are not raw `DB` and cannot hit or populate; and
- a disconnected/detached committed DB without active attached context cannot
  hit or populate.

There is intentionally no cross-branch or cross-generation result reuse in this
unit. Future explicit content sharing can add a secondary content bucket while
retaining separate scope ownership, but must not weaken this law.

## Configuration dimensions

The identity does not append the full Datahike config because:

- store and branch are already in `connection-id`;
- remote writer backend is appended when it changes process-local ownership;
- `reserve-connection-opening!` refuses an incompatible acquisition key for an
  active connection (`connections.cljc:37-88`);
- create-time index/store settings are adopted and checked during opening
  (`connector.cljc:268-380`); and
- a new open after release gets a new generation, even if configuration changes.

This is smaller and stronger than hashing selected config fields into every
query lookup. Add a configuration fingerprint only if a retained test finds two
semantically different raw DBs inside one accepted active generation, which the
current acquisition contract is designed to forbid.

## Speculative token conclusion

No speculative token is needed in the first cut. `db-with` and intermediate
transaction values clear `cache-context` and execute uncached. This avoids:

- retaining short-lived index versions;
- defining release ownership for detached values;
- assigning process tokens on the query hot path;
- ambiguity between an inherited parent commit ID and uncommitted content; and
- broadening the unit before committed multi-database behavior is proven.

If measurement later shows speculative repeated queries matter, add an explicit
retained-value acquisition API with a process-local token and release. Never
silently cache every `db-with` value.

## Performance overhead

The identity adds two UUID-bearing components versus the current three-number
key but removes collision risk and enables exact eviction. Datahike's bounded
structural-weight helper reported:

```clojure
{:old-shallow 4
 :new-shallow 6}
```

for `[hash max-tx max-eid]` versus `[connection-id generation commit-id]`. This
is a relative structural unit, not bytes.

A disposable two-million-iteration construction loop observed approximately
`1.69 ns` versus `2.04 ns` per vector. The JVM can escape-eliminate these tiny
vectors, so those numbers are not a trustworthy allocation benchmark. They only
show no obvious arithmetic bottleneck. The implementation should precompute
`committed-value-identity` once on the DB field; cache lookup then reads the
existing vector rather than rebuilding it.

Expected positive effects:

- no full DB equality scan on collision;
- no speculative/intermediate snapshot buckets;
- exact O(number-of-buckets-in-generation) scoped eviction, or O(1) if the LRU
  gains a scope index;
- no copied config fingerprint per lookup; and
- one committed propagation per writer batch instead of intermediate
  propagation per transaction.

Measure actual allocation, p50/p99 hit latency, propagation time, eviction time,
and retained heap at 1/2/4/8 databases before accepting the unit. If linear LRU
scope scanning is visible, add an internal scope-to-keys index maintained in the
same atomic cache state; do not add another cache registry.

## Source edit inventory

Exact expected Datahike owners:

1. `src/datahike/db.cljc`
   - add the process-local `cache-context` raw `DB` field;
   - add pure attach/clear/identity helpers or delegate them to one cache owner;
   - ensure transient/persistent DB transformations preserve or deliberately
     clear the field.
2. `src/datahike/connections.cljc`
   - mint generation on first opening reservation;
   - retain it through waiters/references;
   - return it to connector publication and final release.
3. `src/datahike/connector.cljc`
   - attach committed cache context after restoring the branch head;
   - pass generation into writer creation or connection metadata;
   - invoke exact generation eviction during final release before resources are
     removed, with idempotent cleanup on failure.
4. `src/datahike/core.cljc`
   - clear cache context on speculative `with` output;
   - remove speculative result-cache propagation.
5. `src/datahike/writing.cljc`
   - stop propagating in `complete-db-update` before durable commit;
   - ensure process-local cache context is excluded from `db->stored` and commit
     identity.
6. `src/datahike/writer.cljc`
   - attach final committed context after `commit!` returns;
   - union modified attributes over a writer batch;
   - propagate parent→final committed child once before publication.
7. `src/datahike/query.cljc`
   - replace `db-cache-key` with committed identity or bypass caching;
   - add exact generation eviction and scoped metrics;
   - retain existing completed-result weight/dependency behavior.
8. `src/datahike/api/specification.cljc`
   - expose read-only cache metrics and explicit scoped lifecycle only if useful
     to generic embedders; keep attach/clear helpers internal.

Expected test owners:

- `test/datahike/test/query_cache_test.cljc` for identity, cacheability, and
  committed propagation;
- `test/datahike/test/lru_weighted_test.cljc` for weight/count and scoped
  eviction bounds;
- `test/datahike/test/connection_lifecycle_test.clj` or the existing closest
  connection test namespace for generation/reconnect barriers;
- `test/datahike/test/versioning_test.cljc` for same-commit branch isolation;
  and
- CLJS test coverage for portable raw-DB cacheability and explicit uncached
  temporal/speculative behavior.

No Seon source change is needed to prove this Datahike unit. Seon consumes the
new exact identity/cache evidence only in the later capability/session unit.

## Regression matrix

### Identity and isolation

- two physical stores with intentionally equal commit IDs still differ by
  scope;
- main and feature at one shared commit do not share buckets;
- two commits in one generation have distinct identities;
- reconnect has the same connection ID, a new generation, and a resolvable
  current commit;
- remote writer backend discrimination remains part of scope;
- config mismatch cannot join one active generation; and
- forced legacy hash collision cannot produce a cache hit.

### Cacheability

- connected committed raw head caches and hits;
- `db-with`, transaction intermediate, detached `init-db`, and empty DB do not
  cache;
- `history`, `as-of`, `since`, filtered, and valid-time wrapper values do not
  cache;
- overweight/lazy-uncertifiable results remain uncached; and
- stats, profiling, count functions, and resource-bounded query rules retain
  their current explicit bypass behavior.

### Propagation

- unrelated committed transaction propagates a completed result;
- dependent attribute change invalidates it;
- pull-only dependencies invalidate;
- wildcard/rule/variable dependencies invalidate conservatively;
- writer batch unions modified attributes before one propagation;
- speculative intermediate buckets never appear;
- parent committed bucket remains queryable while owned; and
- in-flight state never propagates.

### Release generation

- non-final release retains entries;
- final release removes only exact `(scope, generation)` completed/in-flight
  entries;
- stale generation cleanup after reconnect cannot remove new entries;
- release failure remains observable and does not silently claim cleanup;
- unrelated database and sibling branch entries survive;
- repeated eviction/release is idempotent; and
- no raw DB/index/connection strong reference remains through cache keys.

### Concurrency

- lookup/publication atomics never hold a lock during query computation;
- different identities and query keys execute concurrently;
- identical-key single-flight composes with committed identity;
- final release fences new admission before evicting its generation; and
- reconnect/open reservation cannot publish into the old generation.

## Implementation exit

The first-cut exact identity unit exits when:

1. every cacheable raw DB exposes one precomputed
   `[connection-id generation commit-id]` identity;
2. no uncommitted, detached, temporal, or filtered value can hit or populate;
3. two-store, same-commit sibling-branch, collision, reconnect, and config
   mismatch tests prove isolation;
4. committed parent→child propagation occurs once at writer publication and
   passes the existing dependency invalidation suite;
5. final release evicts only its exact generation and a barrier test proves a
   stale releaser cannot touch reconnect;
6. weighted bounds and unrelated-query parallelism remain green on CLJ and the
   applicable CLJS surface;
7. retained measurements show key lookup/propagation overhead is negligible
   relative to query work and release bounds memory; and
8. cache metrics can report scope, generation, buckets, entries, weight,
   hits/misses, propagation, invalidation, and eviction without exposing DB
   values.

Only after this exit should the next unit add identical-miss single-flight.
Temporal/speculative caching remains a later evidence-driven expansion, not an
unfinished part of this unit.
