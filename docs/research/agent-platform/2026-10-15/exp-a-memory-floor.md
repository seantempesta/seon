# exp-a: the memory floor (lane exp-a, Sonnet, 2026-10-15)

Throwaway JVMs on an APFS clone of `data/store` (`cp -c -R`, then `cp -c -R -n`: 40 s + 11 s), plain `clojure -M` on the repo classpath at fork `593a8276`, `-Xmx5g`. Nothing touched default except read-only `jcmd GC.class_histogram` and JVM-mode reads. Scripts: `tmp/exp-a/warm.clj`, `tmp/exp-a/overlay.clj` (gitignored; the forms are quoted below). Clone and JVMs deleted.

## 1. Baseline: the "2.5 GB floor" is not live heap

| JVM | heap used (no GC) | live after full GC (histogram total) |
|---|---|---|
| pid 52140 (before Root's restart) | 1.69 GB (`heap-used`) | 925 MB (22.4 M objects) |
| pid 62715 (fresh boot at 3dfd4a9a6, `ready-ms` 30,965) | 2.96 GB (`heap-used`) | **895 MB** (21.9 M objects) |

The ~2.5-3 GB figure is `heap-used` = uncollected G1 garbage (`SoftMaxHeapSize` = `MaxHeapSize` = 4 GB, so G1 has no reason to collect). The **live floor is about 0.9 GB**, not 2.5 GB.

Split of the 895 MB (fresh boot, histogram + stats):

| owner | measured | note |
|---|---|---|
| Datahike node cache (one, file store) | weighed 512 MB; real about 235 MB (see 3) | 16,151 nodes; `Leaf` 19,975, `Branch` 1,221, `Datom` 1.70 M (68 MB shallow) |
| `seon.cache` | weighed 254 MB (`:seon.db/projection` 105 MB, `:seon.sci.eval/program-context` 32 MB, `:seon.schema/projection` 22 MB, declaration-projection 20 MB, parsed-facts 16 MB) | real size not isolated |
| Malli/SCI/other | remainder, about 150-250 MB | `PersistentVector` 3.87 M (155 MB) is Datom tuple values plus Malli children |
| overlays / SCI contexts | none open; `sci.impl.opts.Ctx` 11, `datahike.db.DB` 7 | |

Weighed total 766 MB against live 895 MB: the two caches together cannot be hiding GBs at idle.

## 2. Claims, verified against source and measurement

**1A overlay cache per world: CONFIRMED, and worse than claimed.**
- `seon.cluster.store/open-overlay!` builds `{:backend :datahike/overlay :id .. :overlay (random-uuid) :backend-config (:store shared)}` and inherits the file store's `:store-cache-bytes` 512,000,000. `datahike.connections/reserve-connection-opening!` reuses a `:node-cache` only when `:physical-store-key` is equal; `datahike.store/physical-store-key` keeps the whole store map, so the random `:overlay` makes it unique. Measured: 4 overlays gave 5 node caches.
- Measured cost: each overlay that scans eavt adds **128 MB live heap** (175 MB with only the base warm, 689 MB with 4 overlays; weighed 257 MB each). A cache cap does not cure it: with `:store-cache-bytes` 16 MB (cache holds 16 MB weighed, 628 nodes) four overlays still added **127 MB each** (303 to 813 MB). The retained bytes are therefore not the overlay's node cache. Hypothesis (not isolated): the overlay's tiered memory frontend keeps what it reads through (`datahike.store` `-connect-store :datahike/overlay`, konserve tiered). Sharing the base's node cache removed it (below), because reads hit the shared cache and never reach the frontend.
- Release: PARTLY. `seon.cluster.store/release-store!` releases the `:db` connection and calls `d/delete-database`. With no branch connection open, all 4 overlays released cleanly: heap back to 175 MB, 1 cache. With a branch connection still open on the overlay (`acquire-branch!` via `(assoc (:config @main-connection) :branch b)`), `d/delete-database` throws `:database-has-active-connections` (loud, not a silent leak), and releasing only `:db` leaves 2 connections and 2 caches (measured). A leak needs a caller that skips `release-branch!`: `seon.test` around line 3186 calls `release-store!` in the failure path without `release-branch!`, and that call throws and is suppressed (`.addSuppressed`), so that path can strand an overlay with its cache. Not reproduced end to end in default.

**1B `node-bytes` under-weighs 2.5-3x: REFUTED.** Argon quoted a `getBytes`/40-byte formula that is not the code at the pinned fork. `datahike.index.node-cache/node-bytes` is `256 + value-bytes (fressian/node->map node)`, with strings at `40 + 2n`, symbols and keywords at `80 + 2n`, collections `32 + 16n + elements`. Measured (eavt, aevt, avet scans of the clone):
- 512 MB weighed (18,512 nodes) = **233 MB live** (280 MB after warm minus 47 MB base): the weigher **over**-weighs by about 2.2x.
- one eavt scan: 257 MB weighed = 128 MB live (ratio 0.5); with the base also warm, 175 MB base.
So "512 MB" cache costs about 235-260 MB of heap. Lowering `store-cache-bytes` is a hit-rate tradeoff, not a correctness fix. (A per-type JOL calibration was attempted and hung on this JDK 26 agent; the whole-heap ratio above is the evidence.)

**1C `seon.cache` weighs schemas as tiny leaves: UNPROVEN, attribution wrong.** `seon.cache/retained` is not a weigher (it lists values). The weight is supplied per call site (`weigh` argument of `seon.cache/lookup`; e.g. `seon.db/projection-bytes`). At idle the cache reports 254 MB and the whole live heap is 895 MB with 235 MB node cache, so no GB-scale excess exists at the floor. The 1.6 KB-per-schema estimate came from the OOM dump (where seon.cache was 494 MB weighed) and was not reproduced; the throwaway JVM cannot rebuild projections cheaply, so no prototype (c). Recommend only a measurement (histogram before and after `seon.cache/evict-all!` on a disposable JVM), not a change.

## 3. Prototypes (throwaway JVM, base warm = 175 MB live)

| variant | 4 overlays, each scans eavt | live heap | node caches |
|---|---|---|---|
| current (random overlay in physical key) | | 689 MB (+514) | 5 |
| (b1) overlay `:store-cache-bytes` 16 MB | | 813 MB over 303 base (+510) | 5 |
| (b2) overlays share the base node cache (`datahike.store/physical-store-key` of an overlay config answers its `:backend-config`) | | 304 MB (+1 over 303) | **1** |
(a) A better weigher: not worth it; the current one is conservative (over by 2x). If a tighter one is wanted, scale `node-bytes` by 0.5 after confirming per index type; not recommended.

## 4. Recommended change (Opus)

1. **Fork, `datahike.connections/reserve-connection-opening!` (and the `physical-store-key` it is given from `datahike.connector`)**: look up the shared `:node-cache` by the overlay's `:backend-config` store key, so an overlay uses its backend's cache. Safe because node addresses are content addresses under one store id (the overlay already shares them, per `store.cljc` comments), and the connection id still carries the overlay marker, so connections stay separate. Keep write-hooks keyed as now. No change to Seon `open-overlay!` except deleting nothing.
2. **Seon, `seon.test` failure path (near line 3186)**: release branch connections before `seon.cluster.store/release-store!` so the throw cannot strand an overlay; the producer fix is `release-store!` releasing the overlay's remaining branch connections itself (`datahike.connections/*connections*` entries whose connection id carries `[:datahike/overlay uuid]`) or refusing by name. Mention only; not measured as a live leak.
3. **Operations**: the apparent 2.5 GB floor is garbage. Set G1 to collect earlier (`-XX:SoftMaxHeapSize` below `-Xmx`, e.g. 1.5 GB) or report `heap-live` as the floor in `seon.cluster.status`; `heap-used` misleads.

Red proof: a test (heap, not wall-clock) in the fork's `datahike.test.overlay-test`: open the base, then 4 overlays on one config; assert `(count (distinct (keep :node-cache (vals @datahike.connections/*connections*))))` = 1, and that the sum of `ii/node-cache-stats` `:weighted-bytes` over distinct caches does not exceed the base's `:max-bytes`. Seon-side measured assertion through `seon.profile/measure`: live heap after full GC with 4 test worlds open minus with 0 open under 20 MB (measured now: 510 MB).

Expected after: floor about 0.9 GB live unchanged; peak with N overlays about 0.9 GB + about 0 per overlay (was +128 MB each, +512 MB for 4, and 4 caches = 1.61 GB weighed in the OOM dump).

## 5. Limits
Single clone and one scan pattern; ratios vary by index mix. Overlay frontend retention hypothesis is inferred from the cache-cap control, not from a histogram of the frontend. JOL failed. Numbers from pid 52140 are labelled; the fresh-boot numbers are pid 62715.
