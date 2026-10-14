---
title: "What fills seon.cache after boot"
type: research
status: measured
---

# What fills seon.cache after boot (measurement, 2026-10-14)

Scope: measurement only. Default JVM restarted 2026-10-14T14:16Z onto 86beed937. Read by `(seon.cache/status)` and `(seon.cache/retained ...)` in JVM mode. No source edits. Counters are cumulative since that JVM began, so they include agent work since boot.

## Summary

1. **Question premise, VERIFIED in part.** The 471,716,183 B reading at the first request after ready was not logged anywhere, so it cannot be re-read. Live now: 510,512,588 of 512,000,000 B weighted (99.7%). Growth since that reading (about 39 MB) is agent work, not boot; UNVERIFIED which kinds grew.
2. **Four kinds hold 92% of bytes.** `seon.db/value-projection` 267.8 MB (25 entries), `seon.db/projection` 149.7 MB (12 entries), `seon.schema/declaration-projection` 28.8 MB (3 entries), `seon.fn.analyzer/parsed-facts` 17.0 MB (160 entries). VERIFIED. Thirty-six kinds are held.
3. **value-projection is the boot-or-write question.** It holds 25 speculative projections of about 3,760 forms each, with 2 hits against 30 misses and 5 evictions. Its entries are not shared by identity (93,981 form references, 93,981 distinct objects). VERIFIED. Whether they are written at boot or by requests is UNVERIFIED.
4. **Weigher is an estimate.** 2,850 B per form times form count, plus 256 B per entry. Real retained size was not measured: UNVERIFIED. For the 12 `projection` entries, 45,035 forms times 2,850 is 128.3 MB, but the kind reports 149.7 MB; the 21 MB gap is UNVERIFIED.
5. **No boot-window profile exists.** No per-kind insert log or snapshot at ready. `seon.log` holds only old Caffeine removal-listener traces (Oct 12). VERIFIED. Boot-time callers are identified by grep below; which of them fire during boot is UNVERIFIED.
6. **Full at 99.7% is the designed state.** The cache fills to its budget under W-TinyLFU. A full cache is not by itself a defect. The question for each kind is whether its bytes are read again: `value-projection` is not (2 hits for 25 entries).

## 1. Source and boot callers

`src/seon/cache.clj`: one Caffeine `AsyncCache` with `maximumWeight` from `:seon.config.cache/max-bytes` (shipped default 512,000,000). Weigher: `entry-bytes` (256) plus the caller's weight. Per-kind counters are `LongAdder`s (hits, misses, evictions, bytes, load nanos). The surface is `lookup`, `lookup-within`, `present`, `evict!`, `retained` (walks all entries), `evict-kinds!` and `status`. There is no per-kind listing other than `status` and no insert log.

Boot path (operator log `operator-start-20261014T141621.375.edn`): `seon.source/current` completes at 34,988 ms of the 38,968 ms ready time, so almost all boot time is source current. Layers after it (`seon.store/branch`, config result, SCI ctx, flow graph, web) take about 4 s. The log has no cache field.

Call sites that run during source current, config and schema load (by grep; firing at boot UNVERIFIED for each):
- `seon.schema.edn/packaged-population`, `packaged-digest` (`schema/edn.clj:411-417`)
- `seon.schema/projection`, `declaration-projection`, `population-predicate-symbols`, `canonical-keys` (`schema.clj:1280-1291, 2186, 2429, 900`)
- `seon.db/projection`, `value-projection`, `projection-alias`, `builder-digest` (`db.clj:1504-1848`)
- `seon.fn.analyzer/parsed-facts` (`fn/analyzer.clj:710`), `seon.cluster/file-derivation-key`, `file-schema-keys` (`cluster.clj:1647-1731`)
- `seon.config/effective` (`config.clj:945`), `seon.sci.eval/program-context`, `program-key`, `loaded-digests` (`sci/eval.clj:3955-4007`)
- `seon.task/file-declarations`, `tasks-tx`, `task-basis` (`task.clj:202-393`)

## 2. Per-kind bytes now (default, JVM mode, cumulative since 14:16Z)

Columns: bytes, hits, misses, evictions, load ms. Trimmed to the largest and the ones relevant to boot.

| Kind | Bytes | Hits | Misses | Evict | Load ms |
|---|---:|---:|---:|---:|---:|
| seon.db/value-projection | 267,832,300 | 2 | 30 | 5 | 839 |
| seon.db/projection | 149,742,584 | 10 | 16 | 2 | 838 |
| seon.schema/declaration-projection | 28,762,218 | 66 | 5 | 2 | 1,579 |
| seon.fn.analyzer/parsed-facts | 16,970,020 | 160 | 582 | 538 | 2,143 |
| seon.schema.edn/packaged-population | 4,237,160 | 25,202 | 4 | 1 | 0 |
| seon.cluster/file-derivation-key | 4,716,480 | 6,104 | 2,285 | 603 | 924 |
| seon.task/file-declarations | 4,147,888 | 201 | 405 | 207 | 110 |
| seon.schema/projection | 21,435,362 | 8 | 4 | 2 | 1,884 |
| seon.program/source-resolver-names | 1,457,576 | 14,354 | 10,529 | 8,835 | 2,347 |
| seon.ref/document | 1,144,128 | 7,914 | 5,818 | 4,064 | 454 |
| seon.db/projection-alias | 26,368 | 90,329 | 184 | 76 | 0 |

Totals: 36 kinds, 510,512,588 B weighted, 99.7% of the 512 MB budget. Earlier snapshots: `dig-content-keys.md` (14:40Z, 31 kinds, 506,023,779 B) and `cache-fill.md` (14:13Z, 48 kinds, 490.8 MB, packaged-population 18%, reach-attribute 18%, program-context 17%, projection 10%). Those percentages do not match now: `value-projection` and `projection` dominate, `packaged-population` is 4 MB (was 62 entries and about 90 MB), and `reach-attribute` and `program-context` are no longer held. Kind counts moved from 48 to 31 to 36 across snapshots: the set changes with what runs (UNVERIFIED which requests drove it).

## 3. Value-level checks

- **value-projection entries.** `retained` returns 25 completed entries (earlier misread of the status tuple as 2 entries is corrected here). Form counts 3,758 to 3,761 each. Identity sharing across entries: 93,981 form references, 93,981 distinct objects. So no structural sharing by identity, and the weigher's per-form estimate does not overcount by sharing. Value-level equality sharing not checked.
- **projection entries.** 12 entries, 45,035 forms total (3,752 to 3,758 each).
- **Weigher vs real size.** Not measured. A retained-size walk was not run. 2,850 B/form is an estimate. The 21 MB gap in `projection` (149.7 MB reported against 128.3 MB from forms times 2,850) is UNVERIFIED: could be counter drift from removals, or weights of other entries.
- **Read again?** `value-projection` 2 hits, 30 misses, 5 evictions: each speculative write derives about 3,760 forms and most entries are never read again. Whether these are boot writes or request writes is UNVERIFIED. `db.clj` comments say value-projection holds speculative `with` results and report `:db-after`, so they come from writes, not reads.
- **Recomputed under a different key?** `content-projection` keys committed projections by `[::declaration-content content-key]` under `::projection`, and speculative ones under `::value-projection` by `advanced-key`. The same content can be derived under both kinds. The magnitude is UNVERIFIED (not counted).

## 4. Profile and log

No `seon.profile` summary over the boot window was found and none was run. The `status` counters start at JVM boot and have no timestamps, so per-kind inserts during boot can only be inferred from cumulative totals. Log: no cache lines since boot.

## Not done (time box)

Boot-window per-kind attribution (needs a snapshot at ready, not taken). Value-level sharing of projections. Retained-size walk. Which request types create value-projection entries.

## Recommendation (not acted on)

Add a per-kind snapshot at `:seon.boot/ready` into the operator log, with insert counts by kind. That makes boot-time versus request-time bytes VERIFIED rather than inferred. Separately, `value-projection`'s 268 MB for 2 hits is worth a task on whether speculative projections need retaining at all.
