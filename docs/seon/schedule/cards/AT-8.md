---
title: "AT-8: cache counters are Caffeine's"
type: reference
status: ready after SR-H, SR-J
created: 2026-10-08
---

# AT-8: Cache counters are Caffeine's

Ledger row AT-8 (group E). AU-Q6 answered (owner 2026-10-08): Caffeine's `recordStats` for whole-cache hits, misses,
loads and evictions; per-kind entries and bytes computed on demand from the map; **per-kind hit rates are dropped**;
`claim` stays (Caffeine's atomic `get` forbids the nested lookups our derivations make). Model: Sonnet.

## What exists

`seon.cache/create` (`src/seon/cache.clj:53-74`) builds one Caffeine `AsyncCache` without `recordStats` and keeps a
`ConcurrentHashMap` of per-kind `LongAdder`s (`counters` `:40-45`, `kind-counters` `:47-51`) incremented on every
lookup and by the removal listener; `status` (`:281-307`) reports them per kind.

## Files

- `src/seon/cache.clj`
- `resources/seon/schemas/seon.cache.edn` (the `:seon.cache/status` schema; find where `:seon.cache/kinds` is declared)
- `test/seon/cache_test.clj`

## Change

- `create`: add `.recordStats` to the builder; drop `::stats` and the removal listener's counter updates (keep the
  listener only if something else needs it; nothing else does today).
- Delete `counters`, `kind-counters` and every `.increment`/`.add` on them (`rg -n "kind-counters|::hits|::misses|::load-nanos|::evictions|::bytes" src/seon/cache.clj`).
- `status` answers:
  `{:seon.cache/max-bytes … :seon.cache/weighted-bytes … :seon.cache/stats {hits misses hit-percent evictions} :seon.cache/kinds {kind {:seon.cache/kind k :seon.cache/entries n :seon.cache/bytes b :seon.cache/byte-percent p}}}`
  where `:seon.cache/stats` comes from `(.stats (.synchronous cache))` (`CacheStats`: `hitCount`, `missCount`,
  `loadSuccessCount`, `totalLoadTime` nanos, `evictionCount`) and `:kinds` from ONE pass over `(.asMap cache)`
  grouping entries by `(first key)` and summing their `::weight`. Update the status schema accordingly; per-kind
  `:hits`, `:misses`, `:hit-percent`, `:load-ms`, `:evictions` leave it.
- Manually completed futures (`claim`/`settle!`) bypass Caffeine's loader, so Caffeine reports no load time for them:
  `status` reports none (no `:load-ms`), and its docstring says why; nothing counts it separately.
- Callers of the dropped keys: `rg -n "seon.cache/hits|seon.cache/misses|hit-percent|seon.cache/load-ms" src test docs/seon .agents` and convert (health and the storage audit forms read them).

## Proof

New `seon.cache-test/status-reads-caffeine-stats`: on a fresh `(cache/create 10000)`, a scripted sequence (two
misses, three hits, one eviction by weight) gives `:seon.cache/stats` equal to that sequence's counts, and
`:kinds` lists each kind's entries and bytes. Convert `kind-status-names-its-kind-and-states-hit-and-byte-shares`
(`cache_test.clj:133`) to the new shape (byte share per kind; hit share is whole-cache). The other cache tests stay
green.

`--test seon.cache-test --test seon.health-test`

## Cost

Expected: no per-lookup allocation of our own (Caffeine's striped counters); `status` is one pass over the map.

## Commit

`bin/lane commit <lane> -m "AT-8: cache counters are Caffeine's recordStats; per-kind entries and bytes on demand" --test seon.cache-test --test seon.health-test src/seon/cache.clj resources/seon/schemas/seon.cache.edn test/seon/cache_test.clj`

## Dependencies

SR-H and SR-J edit `cache.clj` first (claim after them).
