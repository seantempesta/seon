---
title: "One merge base, GC: commit-graph backfill and a shared record reader"
type: research
date: 2026-10-15
status: notes for one-merge-base.md Implementation notes (lane omb-gc)
---

### GC: commit-graph backfill

Lane `omb-gc` (Opus), 2026-10-15. To go under "Implementation notes" in `one-merge-base.md` when lane `omb-p2b`
releases it (it held the file when this was written). Fork commit **`00c58326`** on `seon/overlay-file-frontend` (parent `593a8276`), pushed. The Seon submodule pointer is not bumped: root bumps it in the restart batch.

**Decision: both, in order.** (a) a one-time backfill, written explicitly by root, and then (b) a record reader
shared across calls.
- (a) alone removes the legacy derivation. It does not remove the repeated walk: on the clone after the
  backfill, 127 merge bases still cost 6.6 s and 24.7 GB. Each call re-reads the same main-history records, at
  about 0.2 MB of konserve overhead per read.
- (b) is one optional argument to `merge-base` plus a pure per-commit read. Seon backs that read with `seon.cache`,
  keyed by commit id. With it the same 127 calls cost 0.9 s CPU and 1.4 GB.
- (b) without (a) would be wrong: on a legacy commit, `commit-graph-record` derives that commit's whole unrecorded
  lineage on every call, which is quadratic. That is why the order matters.
- The legacy path stays only for stores nobody has backfilled. Once root has run the backfill, the writer keeps
  every new commit recorded and the derivation has no work. Deleting it later is a fork follow-up.

**API (fork, `seon/overlay-file-frontend`).**
- `(datahike.api/backfill-commit-graph! conn-or-store)`, or `(... {:batch-size n})` with a default of 1000.
  - It derives once over every branch head (`datahike.writing/commit-graph-records`), then writes the missing
    records parents first, in `k/multi-assoc` batches.
  - It returns `{:datahike/branches n :datahike/written n}`.
  - It raises `:commit-graph-disabled` on a `:commit-graph? false` store.
  - It is idempotent and needs no writer. Records derive from immutable commits, so a concurrent commit that writes
    the same record writes the same value. A record whose commit gc sweeps during the run is an orphan, and the
    next gc takes it.
  - Because it writes parents first, a stored record implies its ancestors' records. A torn run therefore resumes
    where it stopped.
  - `datahike.writing/commit-graph-writes` now also orders the derived records parents first, for the same
    invariant.
- `(datahike.api/commit-graph-record conn-or-store cid)` returns a commit's record: the stored one, else one derived
  without writing anything. It answers nil for an unknown commit.
- `(datahike.api/merge-base conn-or-store a b read-record)` is a new optional fourth argument: a function from cid
  to its record or nil. The 3-arity is unchanged.

**What root runs.**
1. **At the restart that bumps the fork pointer:** run this once, in JVM mode, right after boot:
   `(datahike.api/backfill-commit-graph! (seon.cluster.boot/connection "default"))`.
   - It must run before the 03:00 UTC collection. Expected cost: about 3 s and 5 GB allocated.
   - It may run while default works, because the records are idempotent.
   - A second run is a cheap check: 253 head reads, 151 ms, 368 MB, writing 0.
2. **Apply `omb-gc-seon.patch` after that backfill, never before.** It touches `seon.cluster.source/merge-base`,
   whose owner is the lane holding Seon's merge files. The patch backs the reader with `seon.cache` kind
   `:seon.cluster.source/commit-graph-record`, keyed by commit id.

**Boot does not call the backfill.** Each run reads every head, which is 368 MB at every boot, to keep a one-time
conversion alive. Root's single run is an explicit write by its actor.

**When collection runs, and whether `retained-before` runs then.**
- Collection is `seon.schedule` `root/maintenance/compact` at `0 3 * * *` UTC. `fire-due!` uses `:latest`
  recovery: after a boot it fires once only if a 03:00 nominal instant has passed since its last fire. The last fire
  was 2026-10-15T03:00Z, so a restart today does not fire it.
- On default, `:seon.config.maintenance/retention?` is **false** (checked in JVM mode).
  `seon.maintenance/collection-cutoff` therefore uses `registry/plain-cutoff` and never calls `retained-before`.
- The 125-merge-base risk arrives only when root enables retention. Until then, `merge-base` runs per call: in
  `fork!`, in `branch-merge-base` and in the merge readers.

**Proofs.**
- **Fork tests.** `datahike.test.merge-base-test` has two new deftests (all 10 deftests pass, 43 assertions).
  `the-backfill-writes-every-missing-record-once`:
  - it uses the phase 1 legacy fixture, with all records deleted, on 3 branches including a relaunch;
  - a legacy record is derived on read, and nothing is written;
  - one run writes exactly the deleted set, equal to what the writer had written;
  - merge-base answers are unchanged, with and without a shared reader;
  - a second run writes 0.

  `the-backfill-writes-parents-first`: a torn run resumes to the full set.
- **Full suite.** `clojure -M:test -m kaocha.runner clj-pss` on `00c58326`: 969 tests, 5,635 assertions, 0
  failures. Phase 1's parent was at 967 tests, and the two new deftests account for the difference.

**Measured.** Setup:
- An APFS clone of `data/store` (2.3 GB, 390k files, 253 branches: 125 `base/*`, 123 `agent-*`, 2 `chain-*`) was
  taken after root's restart onto `3dfd4a9a6`. That restart was already on fork `593a8276`, so main's lineage had
  begun converging, and the before numbers are lower than phase 1's 4.3 GB.
- The measurement ran in a throwaway JVM on the fork classpath and used the raw store, with no writer.
- Each number is per-thread CPU and allocation, one run each.
- "All branches" is the merge base of each of the 127 non-`base/` branches with `:cluster-default`, which is what
  `retained-before` would do.

| case | wall ms | cpu ms | MB |
|---|---|---|---|
| before: `:chain-f1f6dc8e6652` vs main, cold | 934 | 919 | 1,586 |
| before: the same, warm | 670 | 662 | 1,580 |
| before: all 127 branches (126 bases, 1 nil) | 8,641 | 7,988 | 27,898 |
| **backfill** (3,132 records written) | 2,839 | 2,680 | 5,045 |
| backfill again (0 written) | 151 | 146 | 368 |
| after: f1f6 vs main, cold | 412 | 248 | 413 |
| after: f1f6 vs main, warm | 121 | 120 | 410 |
| after: all 127 branches, a reader per call | 6,562 | 5,976 | 24,739 |
| **after: all 127 branches, shared reader** (4,989 records) | 2,313 | 919 | 1,414 |
| after: all 127 again, shared reader warm | 197 | 196 | 511 |

The answer is `6acf6fed` throughout, the base claim 1 found.

**Remaining cost.** About 0.2 MB of konserve allocation per small-record read, plus a whole head read per branch
keyword (about 1.45 MB). Both are konserve and read-head follow-ups and are not needed here.
