---
title: "DH-4a: Datahike keeps loaded nodes only in its counted cache"
type: reference
status: ready after dials-16gb
created: 2026-10-08
---

# DH-4a: Datahike keeps loaded nodes only in its counted cache

Ledger row DH-4a (group E; waste row W-16). Owner ruling 2026-10-08: replace Datahike's soft-referenced node
retention with a byte-bounded cache (a config dial); this card removes the soft retention, DH-4b weighs the cache in
bytes. Model: Sonnet for the fork change and measurement; stop and report if the settings do not reach restored trees.

## What holds the memory

persistent-sorted-set (Maven `org.replikativ/persistent-sorted-set` 0.4.137, `reference-code/datahike/deps.edn:23`)
keeps each tree's loaded children behind `java.lang.ref.SoftReference` by default: `Settings` `:ref-type` is
`:strong`, `:soft` or `:weak` (`org/replikativ/persistent_sorted_set.clj:150-157` `map->settings`, `:176-181`
`settings->map`, inside the jar). Datahike passes only `:branching-factor` and `:diff-buf-size`
(`reference-code/datahike/src/datahike/index/persistent_set.cljc:507-511` `empty-index`, `:528-533` `init-index`), so
every tree is `:soft`. On default: 3.76 M live `Datom` objects (150 MB) against 577 k current datoms; the counted node
LRU (`:seon.config.db/store-cache-size` 8,192) holds 15% of them; G1 keeps the rest about an hour with gigabytes free.
With `:weak`, a node stays loaded only while the counted LRU or a live reader holds it.

## Files

- the Datahike fork: `src/datahike/index/persistent_set.cljc` (and its tests); the gitlink in Seon
- `src/seon/cluster/store.clj` (the store's `:index-config`, `store.clj:219-233`)
- a measurement note: `docs/research/agent-platform/2026-10-08/memory/dh-4a.md`

## Fork change

1. Find every place a sorted set's `Settings` is built: `empty-index` (`:507`), `init-index` (`:528`), and the
   restore path (the fressian read handlers in `pss-fress`/`add-konserve-handlers`, `:553` onward, which rebuild a
   set from a stored root: read how the restored set gets its `Settings`).
2. Carry `:ref-type` beside `:branching-factor` from the store: `add-konserve-handlers` reads
   `(get-in config [:index-config :ref-type] :soft)` and assocs `:datahike/ref-type` on the store, as it does for
   `bf`; each `Settings` site passes `:ref-type (:datahike/ref-type store :soft)`. `:ref-type` is a runtime setting:
   it must not be written into stored roots or nodes (check that the root/node write handlers serialize only
   `:branching-factor`/`:diff-buf-size`; if they serialize the whole settings map, exclude `:ref-type`).
3. Fork test: a store with `{:index-config {:ref-type :weak}}` builds, writes, reopens and restores; `settings->map`
   of the restored EAVT's settings says `:weak`; query answers equal a `:soft` store's.

## Seon change

`store.clj`'s `:index-config` adds `:ref-type :weak` (not a dial: there is one right answer under the owner's ruling;
DH-4b's byte bound is the dial).

## Measurement (throwaway root, a copy of default's store; never default)

`docs/research/agent-platform/2026-10-08/memory/probe.clj` phases `:snapshot`, `:concurrent 100`, `:snapshot` on
parent and change: live heap after full GC, `Datom` count from `jcmd GC.class_histogram`, and p50/p95 of a fixed
query set (the probe's or five `my.program` reads). Record in `dh-4a.md`. Expected: live Datoms near the LRU's share;
p50 within noise of parent (the LRU holds the hot nodes); report any regression with its numbers.

## Proof

The fork test above; Seon: `seon.cluster.store-test/the-store-builds-weak-referenced-indexes` (open a test store,
read its EAVT settings, assert `:weak`).

`--test seon.cluster.store-test`

## Commit

Fork first (push to `seantempesta/datahike`), then:
`bin/lane commit <lane> -m "DH-4a: index nodes are held only by Datahike's counted cache (weak refs)" --test seon.cluster.store-test reference-code/datahike src/seon/cluster/store.clj test/seon/cluster/store_test.clj docs/research/agent-platform/2026-10-08/memory/dh-4a.md`

## Dependencies

dials-16gb holds `store.clj` and `store_test.clj`; LR-CACHEb's Datahike gitlink lands first (one fork line at a time).
DH-4b follows.
