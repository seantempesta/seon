---
type: issue
status: open
severity: cleanup
tags: [issue, dependency, database, datahike]
---

# Delete the konserve LRU our fork allocates and never reads

## Problem

Every store our Datahike fork opens wraps itself in a konserve LRU cache and
then never reads through it. Reads go straight to `konserve.core`, which
ignores the cache entirely. The real node cache is a second LRU built
elsewhere — sized by the same config key. So one cache is doing the work and
a same-sized twin is allocated, held for the store's lifetime, and consulted
by nothing.

Two mechanisms where there should be one, and the dead one is invisible
because it is spelled like the live one.

## Evidence

`reference-code/datahike/src/datahike/store.cljc:33-35` calls
`kc/ensure-cache` with an LRU sized by `:store-cache-size`.

No function from `konserve.cache` (`reference-code/konserve/src/konserve/cache.cljc:41-192`
— `get`, `get-in`, `assoc`, `update`, `dissoc`, `exists?`) is called anywhere
in the fork. Reads go through `konserve.core` at
`reference-code/datahike/src/datahike/index/persistent_set.cljc:314,387,437`,
which does not consult `(:cache store)`.

The cache that actually serves reads is `CachedStorage` at
`persistent_set.cljc:461-463`, built with the same `:store-cache-size`
threshold.

Separately, `:store-cache-size` is never set by Seon
(`src/seon/cluster/store.clj:164-174`), so both LRUs run at the default 1000.

The retained probe
`docs/prds/sci-execution-runtime/research/scripts/store-options-before-after-2026-08-02.clj`
reopened a fresh 3,600-datom file database and measured both caches. The outer
Konserve cache was present with threshold 1,000 and stayed at **0 entries**
before and after three direct `konserve.core/get` reads. A first cold Datahike
query made the independent `CachedStorage` perform five storage reads; the same
query a second time added **zero** reads (`:reads` stayed 5), proving the inner
node cache serves hits while the outer LRU remains unused. The script also
reports that both cache atoms exist and are not identical, so deleting the
outer allocation has a direct before/after capability measure.

Full sweep:
`docs/prds/sci-execution-runtime/research/upstream-delta-sweep-2026-07-31.md`.

## Owner

`reference-code/datahike/src/datahike/store.cljc` as a maintained fork.

## Acceptance

- One cache remains: either the `ensure-cache` wrap is deleted, or reads are
  routed through `konserve.cache` and `CachedStorage` is the one that goes.
- `:store-cache-size` names exactly one cache, and what it sizes is stated.
- Datahike's suite passes at the change, and a probe shows the surviving
  cache serving hits.

## Current disposition 2026-08-02

**Safe fork cleanup, proven but not yet applied.** Current Datahike source has
no `konserve.cache` call site besides `ensure-cache`; changing every database
read to the cache API would introduce a second read mechanism, while deleting
the unused wrapper leaves the measured `CachedStorage` hit path unchanged. The
before metric is: two distinct cache atoms allocated, outer 0 → 0 entries on
core reads, inner five backing reads on the first query and none on the second.
