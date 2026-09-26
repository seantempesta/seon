---
type: research
lane: commit-floor-research-opus
date: 2026-09-26
status: measured; konserve fork fix committed (b8d92d1, unpushed); Seon validator cause filed (task 4bb788513862); owner options at the end
---

# Why one small settlement commit costs ~118 ms (2026-09-26)

**What exists.** A trivial agent form `(inc 1)` settles in ONE Datahike transaction (`seon.turn/settle-batch!`
→ `seon.db/transact-call` → `d/transact!`): 25 datoms (a run, one evaluation, its read evidence, the runtime's
new turn ref). On the writer, Datahike applies it, runs Seon's transaction functions and the final-report
validator (`:datahike/validate-report` → `seon.db/validate-write-report`), then `datahike.writing/commit!`
flushes dirty index nodes and writes, through konserve's file store, every node, the commit record and the
branch head (`reference-code/datahike/src/datahike/writing.cljc:436-572`, per-key path
`write-pending-kvs!` :423-434). Konserve writes each key as: temp blob, `FileChannel.force`, rename,
directory `force` (`reference-code/konserve/src/konserve/impl/defaults.cljc` `update-blob`, filestore
`sync-base` :43-53).

**The answer.** About two thirds of the commit is drive-cache flushes. On macOS the JDK's
`FileChannel.force` is `fcntl(F_FULLFSYNC)`, a whole-drive flush: **3.78 ms** measured in default's JVM,
against **0.03 ms** for `FileDescriptor.sync` (plain `fsync(2)`); python on the same volume: F_FULLFSYNC
3.92 ms, fsync 0.031 ms, F_BARRIERFSYNC 0.34 ms. A settlement commit writes **14.8 keys** (12.8 index
leaves + commit record + head), each with two forces: **29.6 flushes, 71-76 ms**, serialized by the device.
The rest is Datahike serialization (~20-25 ms), Seon's validator and transaction functions (~17-20 ms).

## Method

- Default pid 89341 (HEAD af7ba85b7..bb07fdf08) and, after root restarts, pid 12212 (HEAD ~d1f6a37fb). Other
  lanes' tests ran intermittently; the numbers below are from quiet windows unless marked.
- Disposable MCP branch `commit-floor-research` (hosted agent `mcp/commit-floor-research`), retired after.
- Probe `tmp/commit-floor-research-opus/probe.clj` (scratch ns `commit-floor-probe`, JVM mode): 20 `(inc 1)`
  through `evaluate-source!` with a `d/listen` on the branch connection only (removed after) and
  `seon.profile/begin`/`explain`; the captured settlement's datoms replayed as fresh entities through raw
  `d/with`, raw `d/transact`, `seon.db/with`, the validator and its parts; the same shape on scratch Datahike
  stores (memory; file with Seon's configuration, 96k-480k datoms, optional 400 interleaved attributes,
  `diff-buf-size`, `keep-history?`), deleted after.
- JFR (`jcmd JFR.start`, `jdk.FileForce`/`jdk.FileWrite` at 0 ms threshold, `profile` for CPU samples),
  joined to wall-clock windows the probe records: `io_windows.py`, `per_commit.py`, `cpu_windows.py`.
- Force patterns in isolation: `fsbench.py` (python, same APFS volume, real node/record sizes) and
  `konserve_probe.cljc` (scratch ns in default's JVM: the loaded `konserve.impl.defaults/update-blob` against
  the fork's modified copy, on a scratch filestore, driven in `commit!`'s order: 13 immutable nodes
  concurrently, then the immutable commit record, then the mutable head).

## Breakdown of one settlement commit (p50, n ≥ 20)

| component | ms | owner | evidence |
|---|---:|---|---|
| **drive flushes: 29.6 `F_FULLFSYNC` per commit** (14.8 keys × blob force + directory force) | **71-76** | konserve policy on the filesystem | JFR: 29.6 forces/commit in the e2e window, identical for a raw `d/transact` of the same shape; per-commit timeline: 13 node forces in parallel 0-27 ms, 13 directory forces device-serialized 16-63 ms, commit record 68-80, head 84-92. Real `update-blob` replay: 70.9 / 76.0 ms |
| serialization + commit-id + flush CPU on the critical path | ~20-25 | Datahike + konserve | raw commit 100 ms − I/O ~75; CPU samples per commit: konserve serialize 15.5 ms, `create-commit-id` 3.5, `db->stored` 2. The record (134 KB) is serialized twice, commit and head, in sequence |
| Seon final-report validator | 12.0 @179 turns, 13.8 @219 | Seon `seon.db` | `validate-write-report` 11.95 / 13.77; of it `write-owned-values-error` 10.79 / 12.30; the rest < 0.3 each |
| Seon transaction functions | ~4-5 | Seon `seon.turn`, `seon.plan` | profile: `close-folded-call` 2.7, `system-run-call` ~1 (writer half), `open-call`, `plan/settle-call` < 1.6 |
| Datahike apply (25 datoms into eavt/aevt/avet) | 0.7-3.2 | Datahike | in-memory store 0.74; raw `d/with` on the branch 3.2 |
| history index (`:keep-history? true`) | ≈ 0 | Datahike | additions touch only eavt/aevt/avet (`datahike/db/transaction.cljc:458-466`); scratch true vs false 44.1 vs 39.2 ms, within that run's noise (a test run shared the disk) |
| listeners (`d/listen`) | ~0.1 | Seon wake | `seon.cluster.wake/wake-attributes` 4 ms over 40 commits; Datahike notifies after `deliver` (`writer.cljc:413-440`), off the caller's path |
| Seon caller side before `transact!` (fault windows, preparation) | 4-6 | Seon `seon.turn` | `settle-batch!` − `transact-call`; `with-fault-windows` 4.1 (13.3 on pid 89341, where `observation-selector` missed its projection cache: 40 ms first call, 0 after) |
| **sum ≈ `transact-call`** | **112-118** | | profile inclusive `transact-call` 111.8 (pid 12212) / 118 (pid 89341) |

`(inc 1)` end to end: 140.8 ms (pid 89341), 135.8 (pid 12212), 150.3 over 40 forms (turns 179 → 219).

## The same transaction without Seon (24-datom shape, p50 of 20)

| store | commit | empty commit | keys per commit |
|---|---:|---:|---:|
| Datahike in memory, 480k datoms | 0.74 | 0.31 | — |
| plain Datahike file store, Seon's configuration, 480k datoms | 44.0 | 23.2 | 4.6 |
| same, 96k datoms + 400 interleaved attributes, steady state (300 warm commits) | 45.2 | 22.0 | 4-7 |
| raw `d/transact` on default's real store (the branch) | 99.9 / 100.1 | 47-52 | 14.8 (empty: 4) |
| Seon settlement (`transact-call`) | 112-118 | | 14.8 |

Attribution: the filesystem/drive flush is ~3.8 ms each; konserve's per-key policy turns one commit into 30
of them (~60 ms of the ~75 are policy, not physics); Datahike adds ~20-25 ms of serialization and writes 3×
more leaves on the real store than on a fresh one; Seon adds ~17-20 ms on the writer.

Under concurrent load the flushes of every writer on the disk queue: in a window where another lane's test
ran, the mean force rose from 3.8 to 10-18 ms, an empty raw commit took 179 ms and `(inc 1)` 499 ms. That is
the unexplained 176-193 ms of the one-commit landing.

## Causes ranked by ms, with fixes

### 1. Two drive flushes per key (−57 to −62 ms per commit) — FIXED ON THE FORK, pending adoption

- **Model error:** konserve pays a whole-drive flush (blob + directory) for every key, although only one
  ordering matters: the mutable branch head must not become durable before the immutable values it names.
  Datahike already awaits every node and the commit record before issuing the head
  (`writing.cljc:423-434`, :557-572).
- **Fix (konserve fork `b8d92d1`, unpushed):** an optional protocol `PDataSyncBackingStore/-sync-data`
  (storage_layout.cljc), implemented by the filestore as `fsync(2)` through a read-only descriptor
  (`RandomAccessFile`/`FileDescriptor.sync`; Jimfs keeps `force`). `update-blob` then pushes every blob
  with `-sync-data`; an `{:immutable? true}` key moves with no barrier; a mutable key pays `-sync-store`
  (directory `F_FULLFSYNC`: every earlier key's data and rename durable) before its move and again after it.
  Two drive flushes per commit instead of 30. Linux keeps its guarantee (`fsync` there is durable per file;
  the pre-move directory sync makes earlier renames durable).
- **What changes:** an immutable key's rename becomes durable at the next mutable write in the store, which is
  the first moment anything can reference it. Seon writes no standalone immutable keys (`seon.blob` uses
  `k/bassoc` without the marker); a process crash is unaffected (the data reached the device).
- **Measured, real konserve code in default's JVM, commit order:** 70.9 / 76.0 ms → **14.8 / 14.0 ms** p50
  (`konserve_probe.cljc`); python emulation 79.5 / 83.0 → 16.0 / 16.8. Round trip of nodes, record and head,
  `:immutable?` meta kept, no `.new` leftovers.
- Lines: src +47 / −5 (3 files), test +28 (`immutable-values-are-durable-through-the-next-mutable-write`).
- Expected: settlement ~118 → ~55-60 ms; `(inc 1)` ~136 → ~75-80 ms; cross-lane force contention ÷15.
- **Root runs:** push the fork; bump `deps.edn` konserve `:git/sha` and the `reference-code/konserve` gitlink
  to `b8d92d1`; restart default; the konserve tests `konserve.filestore-test` (this lane could not: no extra
  JVM); repeat `tmp/commit-floor-research-opus/probe.clj` `e2e` + `raw-batch` for the after numbers.
  Task `b08e692710dc` (amended).

### 2. The validator reads every turn the agent ever ran (−11 to −13 ms now, growing) — Seon, `seon.db`

- **Model error:** each settlement adds its turn to `:seon.runtime/turns`, a cardinality-many **component**,
  so `write-owned-values-error` expands the runtime's whole owning value: 179 turns → 10.8 ms, 219 → 12.3 ms
  (about 37 µs per turn), on the writer, for every form, until `:seon.config.db/validation-node-limit`
  (250,000) refuses the agent's writes. Work proportional to history for a change of one child.
- **Fix, either:** validate only the changed children and the owner's own attributes (children not in the
  report were validated when they were written; ~+20 / −10 lines in `write-owned-values-error`), or delete
  the `runtime → turns` edge that `:seon.turn/agent` already derives (schema + its writers; net deletion,
  loses component cascade on runtime retraction, which the owner must accept).
- File held (`db.clj`, one-commit lane): subject mark as a patch,
  `tmp/commit-floor-research-opus/patches/subject-marks.patch`. Task `4bb788513862` (amended: named cause).

### 3. The real store rewrites ~13 index leaves per commit (~10 ms after fix 1) — store configuration

- 12.8 leaves (45-127 KB, ~1.3 MB) per 24-datom commit on default's store; 2-3 on a fresh file store with the
  same settings; **0.6** with `:diff-buf-size 2048` in steady state (scratch, 400 interleaved attributes,
  300 warm commits; 256: 2.9). The 256-entry buffer of each 4096-way root is saturated, so each commit
  flushes a leaf per ~5 buffered operations. After fix 1 a leaf costs its serialization (~1 ms CPU), not two
  flushes, so re-measure before acting. A larger buffer is creation-time (`seon.cluster.store/datahike-configuration`,
  needs a new store) and grows the inlined root (scratch empty commit 22 → 29-31 ms at 2048).
  Task `f8a2a19046f7`.

### 4. The root record is serialized twice (~5-8 ms) — Datahike fork

- `commit!` writes the same `db-to-store` (134 KB) as commit record and branch head; konserve serializes each.
  One serialization for both keys, or a head naming the durable commit record: ~15 lines in
  `writing.cljc`. Task `c9fb93f57092`.

### 5. Smaller items (≤ 5 ms each), not fixed here

- Transaction functions ~4-5 ms (`close-folded-call` 2.7 ms: the pass already knows which run it closes).
- `create-commit-id` 3.5 ms CPU per commit (Datahike).
- Refuted: listeners (~0.1 ms), history indexes for this shape (≈ 0), the "~55 queries per form" as a writer
  cost: 48 `seon.db/q` per form, 24 ms, run on the pass (`next-agent-work`, `opening-deferred?`,
  `episode-runs`, `unanswered-wakes`; tasks `62c45543598c`, `d6e3b775f474`), not in the commit.

## Options for the owner (simplest viable first)

1. **Adopt the konserve fork commit only.** Root pushes, pins, restarts, runs `konserve.filestore-test`.
   - Result: settlement ~118 → ~55-60 ms; `(inc 1)` ~136 → ~75-80 ms; far less cross-lane disk contention.
   - Guarantee: the head is durable only after every value it names (unchanged); immutable keys are durable
     at the next mutable write.
   - Cost: ~30 min of root time; 0 Seon lines. Sacrifice: the validator keeps growing ~37 µs per turn per form.
2. **Recommended: option 1 plus the validator fix (cause 2).** One slice on `db.clj` after the one-commit lane
   releases it (~+20 / −10 lines and one regression: a settlement's validation time does not grow with the
   agent's turn count), Astra review.
   - Result: settlement ~45 ms, `(inc 1)` ~65 ms, and no history-proportional writer cost.
   - Cost: one lane slice plus review. Sacrifice: none known.
3. **Option 2 plus store shape (causes 3 and 4).** Re-measure leaf count after option 1; if leaves still cost,
   create stores with a larger `:diff-buf-size` (nuke) and serialize the root record once (Datahike fork).
   - Result: settlement ~30-35 ms, `(inc 1)` ~50 ms (estimates; the buffer's cost on the root record must be
     measured).
   - Cost: a store rebuild, a Datahike fork change, two reviews. Sacrifice: creation-time settings pinned anew.

## Verification limits

- The fork change was measured through the real `update-blob` code copied into a scratch namespace (Vars of
  default are never redefined), not through a restarted default; the end-to-end after number is root's.
- Konserve's own tests were not run (no extra JVM). `clj-kondo --cache false` on the three changed files:
  no errors.
- Machine load varied (another session's ffmpeg, other lanes' tests; root restarted default twice during the
  lane); every table row names its window's conditions or is a p50 of ≥ 20 in a quiet window.
- Evidence files: `tmp/commit-floor-research-opus/` (`run-2..5.edn`, `scratch-*.edn`, `jfr/*.jfr`, the
  scripts above).
