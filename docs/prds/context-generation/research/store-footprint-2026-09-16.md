# The 72 GB development store is 7 days of uncollected index copies

Dated 2026-09-16, measured against the shared root `/Users/sean/src/seon`
(`data/store`, live cluster `default`, pid 53378). Read-only probes; no test
JVM, no cluster restart, no deletion. The GC dry run was deliberately NOT run —
it takes the store's reachability permit, which
[the blob retention sweep](../../../seon/issues/blob-retention-sweep-starves-every-roster-writer.md)
already holds ~96% of wall time.

## Verdict

The store is 71.6 GB in 382,425 konserve files, and **93% of them are
copy-on-write persistent-sorted-set LEAVES** left behind by ordinary
transactions on `:cluster-default`. Nothing ever collects them: the only
`d/gc-storage` caller in the tree is `seon.cluster.registry/collect!`
(`src/seon/cluster/registry.clj:473`, `:541`), scheduled ONCE A WEEK as
`root/maintenance/compact`, `"0 3 * * 0"` UTC (`src/seon/schedule.clj:66-70`).
The store was created 2026-09-08 11:32:21 and grew straight through the one
Sunday window since.

This is not a history-retention story and not a blob story: only 3,624 of
380,285 keys are binary. It is garbage — superseded index nodes that no branch
head references.

## Numbers

Filesystem (`find`/`stat -f '%z %m'` over every `.ksv`):

```
files 382,425   total 71.6 GB   avg 201 KB
<10K  744      10-100K 232,092      100K-1M 141,001      >1M 8,588
bytes  0.00 GB       12.73 GB          35.32 GB           23.54 GB
```

Composition, 3,000-file uniform sample, classified by the type tag in the
konserve header: **2,800 `pss/leaf` (93.3%)**, 200 other — commit records
(`pss/set` with inlined roots, `eavt-key`/`temporal-*-key`) and binary blobs.
The largest files are 20.4 MB `pss/leaf` nodes full of `datahike.datom.Datom`.

Growth, by file mtime day:

```
09-08   444 files 0.07 GB     09-13  25,827 files  2.78 GB
09-09   406        0.04       09-14 103,131       15.42
09-10 1,234        0.20       09-15 250,251       52.86
09-12 1,011        0.22
```

09-15 hourly: idle 01:00-07:00 ≈ **1,000 files / 0.11 GB per hour**; working
hours 13,000-21,000 files / 2-6 GB per hour; peak 09-15 00:00 = 21,038 files /
5.98 GB. Writes are continuous, not bursty — with a 60 s gap threshold one
burst spans 13.7 h. Right now: 4,388 files in 15 minutes = **292 files/min**,
of a 400-file sample 385 leaves to 15 commit records.

## Who writes it

`:cluster-default` is the hot branch. Two head reads 30 s apart:

```
:cluster-default max-tx 416 -> 419   (~6 transactions/min; 396 -> 419 over 4.5 min)
:current-src      11 (unchanged)   :test-results 12   :cluster-beta 1234 (idle since 09-08)
```

So **~50 files and ~1.5-1.8 MB of permanently retained index nodes per
transaction** (idle floor: 1 tx/min -> 1,000 files and 0.11 GB per hour).

What those transactions carry, last 15 transactions on `:cluster-default` by
attribute: `:seon.issue/functions` 1463, `:seon.fn.ast/*` ~2,400 (edit-hook
program upserts), and `:seon.maintenance.result/*` 290 each — the scheduled
maintenance firings. The portfolio runs `blob-retention` at `"* * * * *"` and
`process-census` at `"5 * * * *"` (`src/seon/schedule.clj:61-75`), so the store
grows by its own bookkeeping at ~0.11 GB/hour with nobody at the keyboard.

The amplifier is leaf size: average 171 KB per leaf, i.e. ~334 B/datom at
datahike's 512-datom branching. Copy-on-write rewrites the WHOLE leaf, across
six indexes (`keep-history? true` doubles it), for a transaction that changes
one maintenance row.

## What a reset or GC would reclaim

Live data is small. `:cluster-default`'s six indexes hold 1,553,829 datom slots
(eavt 362,082 / aevt 362,082 / avet 164,475 / temporal 277,842 / 277,842 /
109,506) ≈ 3,035 leaves. Five branches of that order is ≤ ~20,000 live nodes ≈
4 GB at the measured average. **Estimate: ≥ 93%, ~67 GB, is unreachable.**
`gc-storage!` whitelists the branch roster and marks from each head
(`reference-code/datahike/src/datahike/gc.cljc:22-77`), so every superseded
leaf qualifies. A reset reclaims all 72 GB.

The exact figure is one `collect!` dry run away
(`:seon.operator.collect/dry-run? true` returns `candidate-files` and
`candidate-bytes`, `src/seon/cluster/registry.clj:468-486`) — worth running the
moment the permit contention in the blocker issue is fixed.

## Plan, simplest first

- **(a) Reset the dev root now.** Reclaims 72 GB in seconds; database data is
  disposable by ruling. Does not stop regrowth: at the measured rate the store
  is back past 50 GB within three working days.
- **(b) Run the existing GC often enough.** `root/maintenance/compact` already
  exists; move it off weekly (daily, or event-driven on a footprint threshold
  from `seon.operator/observe-footprint!`). Blocked today: the per-minute blob
  retention sweep holds the exclusive permit, so `collect!` would queue behind
  it, and a mark over 72 GB reads most of the store.
- **(c) Stop creating a megabyte of garbage per bookkeeping row.** The
  per-minute maintenance schedule writes `:seon.maintenance.*` rows into the
  cluster branch, each costing ~1.5 MB of retained index copies. Either the
  firings do not belong in the same branch as the program and agent facts, or
  the sweep does not belong on a one-minute period at all.

**Recommendation: (c) then (a), with (b) as the standing floor.** (c) is the
root cause — a mechanism that costs 1.5 MB of permanent storage to record that
a sweep ran is a defect on its own terms, and it is the same "does this
mechanism belong on that path" question the open blocker already asks about the
one-minute sweep. (a) is the right immediate reclaim once (c) lands, because
resetting first only restarts the clock. Not implemented here.
