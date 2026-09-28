---
type: landing
status: patches-pending
lane: heap-retention-opus
date: 2026-09-27
base: b718d78c5
---
# heap-retention-opus: what default's old gen holds after test rounds (2026-09-27)

## Broken first

- **The memory-pressure admission counts garbage as live.** `seon.sci.kernel/collection-usage`
  reads `G1 Old Gen`'s collection usage. On default (JDK 26.0.1, G1), the `G1 Young Generation`
  collector manages `G1 Old Gen` (probed through `GarbageCollectorMXBean/getMemoryPoolNames`), and
  after a young pause the collection usage equals current usage (2,041,047,320 bytes for both). So
  the "after a collection" number includes every promoted object that no marking has reclaimed yet.
  G1's adaptive IHOP lets that garbage stand while the heap has room (IHOP was about 31 % in the
  r10 log). The 8.53 GB that r9 refused on was not live data. Fix: patch
  `tmp/heap-retention/kernel-live-pressure.patch` (kernel.clj is held by one-path-impl). Task:
  `memory-pressure-crossed-during-test-rounds` (revised in `resources/seon/tasks/seon.sci.kernel.edn`).
- **Default died of OOM during this lane (pid 42397, 00:07:10Z).** The cause was
  `Requested array size exceeds VM limit`: a 2 GB `byte[]` inside a `StringWriter`. It was on the
  prepl thread `Clojure Connection seon.cluster/default 1`, and the stack was
  `user$eval…/verdict → pr-str`. That matches `tmp/red-triage-r8/proof.clj`, whose `verdict`
  calls `(pr-str (:actual event))` and only then clips the text with `subs`. The value printed was
  a failing `(every? #(identical? (:ctx context) %) @seen-contexts)` from `seon.render.web-test`,
  so its `:actual` holds SCI contexts. The rest of the heap was 2.2 GB. This is not retention. It is
  unbounded printing outside the floor printer (AGENTS.md "nothing prints unbounded"). The dump and
  MAT indexes are kept in `data/heap/analysis-42397/` (10 GB). Root restarted default as pid 51590
  at 00:09:52Z.
- **Two arity caches sat outside the byte budget.** `seon.db/arity-commit-cache` (core.cache LRU
  16) and `arity-base-cache` (LRU 4) retained 207 MB and 64 MB in the OOM dump. One base on default
  now measures 108,330 edges and 8,094 callers, which is 58 MB at the calibrated 540 B/edge, and a
  cold build takes 452 ms. Sixteen commit entries can each pin a distinct base, so the count bound
  permits about 0.9 GB. Fix: patch `tmp/heap-retention/db-arity-central-cache.patch`, which moves
  both kinds into `seon.cache` under its one byte dial. db.clj is held by boot-source-perf.

## Retained breakdown (live bytes, `jcmd GC.class_histogram`, which forces a full GC)

| point | live heap | note |
|---|---|---|
| pid 42397, 11 min after boot | 1.53 GB | baseline |
| + read-evidence, profile, db tests (107 members) | 1.77 GB | |
| + sci.eval-test (50 executed) | 2.37 GB | |
| + turn-test (8 executed) | 2.26 GB | falls, so it is not monotonic |
| pid 51590 after the whole r10 round (16 nss, about 350 members) | **1.98 GB** | old gen 1.88 GB; peak old after any young pause in r10: 3.24 GB |
| OOM dump of 42397, excluding the 2 GB print buffer | 2.2 GB | |

Owners in the OOM dump (MAT, retained):

| owner | retained | bounded by |
|---|---|---|
| `seon.cache/process` | 248 MB | 512 MB estimate dial (it reported 290 to 355 MB) |
| `seon.db/arity-commit-cache` + `arity-base-cache` | 207 + 64 MB | entry counts only (fixed by the patch) |
| `datahike.connections/*connections*` | 109 MB | 2 to 7 open connections |
| Datahike node cache | 2.9 MB, **one instance shared by all 10 CachedStorages** | the fork already shares it (67b00071, pinned in 591c825d) |
| `seon.db-test/fixture-projection` | 10 MB | test namespace def |
| `seon.sci.eval/base-context-cache` | 7 MB | LRU 4 |
| `seon.db/projection-cache` + value tier | 3.1 + 1.0 MB | ruling B (boot-source-perf); numbers only |

After the rounds, the rosters were clean. There were 2 to 3 Datahike connections (cluster, `:db`,
and one agent branch). Test members opened and released their own. `running-instances` held only
`default`. The `tfp-sel`, `tfp-sel2` and `tfp-sel3` branches are unconnected roster entries from
test-feedback-perf's MCP retire NPE. They hold no heap. Retention GC reclaims their store once they
are unlinked (root's call).

## Verdict

This is **not a leak**. Live bytes after a full 16-namespace round are 1.98 GB, against 1.53 GB at
boot, and they fall between namespaces. The 8.3 to 8.9 GB readings were uncollected garbage that
the pressure gate took for live data. The one real byte-unbounded holder was the arity caches (up
to about 0.9 GB permitted).

Task `4e824da5bfb7` (a per-connection node cache of about 350 MB per branch) is already fixed by
datahike 67b00071, and the dump shows one cache shared by 10 storages. I deleted it from
`seon.cluster.registry.edn`.

## Patches for root (held files)

1. `tmp/heap-retention/kernel-live-pressure.patch` (kernel.clj, +14/-8 → one-path-impl).
   - `enter-memory-pressure!` runs one `System/gc` per crossing notice.
   - It enters only when the collection still reads at or above `::enter`.
   - The full collections observed here took 0.1 to 0.5 s at 1.5 to 2.4 GB live.
   - Resume is unchanged: it re-reads on each admission while pressure is held.
2. `tmp/heap-retention/db-arity-central-cache.patch` (db.clj, db_test.clj → boot-source-perf).
   - Deletes both `defonce` LRUs.
   - Adds the kinds `:seon.db/arity-base` (content key) and `:seon.db/arity-commit` (commit id)
     through `seon.cache/lookup`, weighed by `arity-base-weight` (540 B/edge + 100 B per key datom).
   - The seed in `report-arity-comparison` goes through the same kind.
   - `a-write-seeds-its-final-values-arity-base` reads `seon.cache/retained`.
   - Net +10 lines. Both new private fns are contracted, and the contracts compile against default's
     projection.
   - Tests root should run: `seon.db-test/a-write-seeds-its-final-values-arity-base` and the
     arity-gate members of `seon.db-test`.

## Limits

- The patches are not installed, so there is no before/after for them on default.
- After-install proof: repeat r10 and read the peak collection usage and the live histogram.
  Expect no pressure fault, and `:seon.db/arity-*` bytes listed in `seon.cache/status`.
- Forced full collections in this lane:
  - 2 on pid 42397 (baseline and after the small round), plus 2 more inside the heavy round's
    per-namespace histograms before the OOM;
  - 1 on pid 51590 after r10.
