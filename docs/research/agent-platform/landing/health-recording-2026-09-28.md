---
type: landing
lane: health-recording-opus
date: 2026-09-28
plan: docs/research/agent-platform/health-plan-2026-09-28.md §1.2, §1.3, §5 step 2
evidence: tmp/health_recording/proof.clj (named proofs with numbers), tmp/health_recording/bench.clj
---

# Health recording: histogram cells, transactions that describe their work, measurements on change

What exists now: every armed call counts into a 40-bucket log₂ histogram on its cell and, inside an evaluation's
world, into that world's call sink with its self time. `seon.profile/transaction-observation` turns a world's sink
delta into `/ms /calls /hot /repeated` on the transaction it writes; `seon.profile.measurement/significant-tx` writes a
definition version's p50/p99/error-class buckets only when they move. Nothing writes a commit of its own.

## Broken or not yet live

- **Not installed until root's restart and three patches.** Default runs HEAD's `seon.profile`; the stamp, settle
  hooks, world clock and member run time are patches (below). Every proof here ran on a scratch copy of the
  committed text loaded as `tmp.health-recording.profile` in default's JVM, on disposable branches.
- **Tests not run by this lane** (lanes run none): `seon.profile-test`, `seon.profile.measurement-test`.
- **The test-batch significance hook waits** on test members having a world with a call sink (health-attribution);
  run time rides the batch commit now (astra patch).
- **`seon.batch`'s private five-slot sink counter repeats `seon.profile/new-adder`**: `seon.profile` requires
  `seon.batch`, so the batch cannot call it. One line; fold into one owner when the sink moves.
- Schema registration costs ~300 ms per key of whole-program work (task in `resources/seon/tasks/seon.schema.edn`).
- The bracket's ThreadLocal leaves one map entry per cell per thread (task in `resources/seon/tasks/seon.profile.edn`).

## What changed (commit below)

- `src/seon/profile.clj`: cell = histogram (one LongAdder per bucket; calls are its sum), total, max, entry counter
  (in flight = entries − calls), throws by class, measured mark. The weak `live` set becomes the roster keyed by
  `[sym digest]` (`cells-of` is O(1) for a settle). `timed` reads `*world*` once and the clock twice on every path
  (HEAD read it three times inside a world: `exited-in-world`); self time comes from a per-thread callee frame and
  the same two reads. Macros build hinted gensyms outside the syntax-quote: the MCP reader qualifies `.method` and
  hint symbols inside it. `begin`/`explain` read the entry counter. No `seon.db` dependency, so `seon.db` can stamp.
- `src/seon/profile/measurement.clj` (new): window since the cells' marks, `measure` (quantile rank never the single
  largest call; positions floored at 2^13 ns), hysteresis ½ bucket, one query over the measured ids.
- Schemas: `seon.profile.edn` (cell, window, measurement values; named warnings `:seon.profile/over-bound-warning`
  = p99 ≥ bucket 31, `:seon.profile/frequent-throws-warning` = errors :frequent — no stored label),
  `seon.profile.measurement.edn` (new; noHistory buckets keyed by `[sym digest]` as values: the definition version
  may be absent on the storing branch), `seon.db.transaction.edn` (`/ms /calls` indexed, `/hot /repeated` not).
- Deleted: the weak `live` set, the call and throw counters, the third clock read in a world, `over-bound?` as a
  stored fact (derived from p99).

Sizes: source +549 −118 (profile.clj +361 −117, measurement.clj +188), tests +134 −1, schemas +126 −8. Much of the
source is docstrings and contracts; it is new capability the plan asks for, not a replacement of a duplicate path.

## Rules stated

- A window is measured from 20 calls; below that it accumulates.
- A quantile never rests on the window's single largest call (rank ≤ calls − 1).
- Floor: positions under 13.0 (2^13 ns, 8.2 µs) are 13.0, so every definition under ~8 µs sits in bucket 14 and
  never moves (a 1 µs function moved 39 times in 10,000 unchanged windows before the floor).
- A position moves its bucket only ½ bucket beyond its edges (×1.41): ±30 % never writes; ≥2.83× always does.
- Error class: none, rare (< 1 in 10), frequent.

## Numbers (default pid 85484, JDK 26 aarch64 macOS; unarmed scratch copy)

| Measure | Result |
|---|---|
| bracket ns/call, 1 thread, no world / world | HEAD 45.4 / 104.4 → new 48.7 / 65.5 |
| bracket ns/call, 8 threads, no world / world | HEAD 542 / 837 → new 554 / 552 |
| histogram as one AtomicLongArray, 8 threads | +7.5 % (one hot slot) — rejected for per-bucket adders |
| cell bytes | ~1,765 B uncontended; 2,563 live cells ≈ 4.5 MB |
| significance, ±30 % jitter, seed 42 | baseline 1 write; 10 noise settles 0; 4× slower same digest 1; new digest 1 |
| 100 unchanged settles, 3 jittered 200 µs functions | 0 writes |
| `significant-tx`, 1,000 definitions, all measured, first | ~200 ms (one query over 1,000 ids); 10 definitions ~3 ms |
| `transaction-observation` | median 36 µs, max 90 µs (3 definitions) |
| one query for slow or call-heavy transactions | 0.23 ms over 45 stamped; returned exactly the 2 slow + 3 N+1 with op, agent, hot, repeated |
| bytes per commit (idx/-mark), 30 rounds | bare 689 KB; names in `seon.db.transaction`, unindexed +45 KB; distant namespace +228 KB; history + AVET index on ms/calls ~+670 KB |

The index on `/ms /calls` is root's ruling (model proof ~+0.6 files per commit per indexed attribute); this lane
measured ~+670 KB per commit for indexed-with-history against +45 KB unindexed — root to reconcile.

## Patches (tmp/health-recording/patches/)

- `root/db-stamp-transaction.patch` (+7 −4): `seon.db` requires `seon.profile`; stamp's lazily installed attribute
  list gains the four; the stamp merges `(profile/transaction-observation world)`.
- `root/test-support-cell-calls.patch` (+1 −2): `armed-counts` reads `(:seon.profile/calls (profile/observation c))`
  — required with this commit (test_support.clj is held by one-path-impl-2).
- `one-path-impl-2/settle-measurements-and-world-clock.patch` (+36 −8): `settle-batch!` and `turn.batch/settle`
  measure once outside their retries and ride the commit (the recording writer only when it commits anyway); world
  builders start `:seon.batch/stamped-ns`; the batch sink folds self nanos (five slots); `seon.batch.edn` declares
  the clock.
- `astra-fixer/test-member-run-ms.patch` (+8 −2): member result carries `:seon.test.member/run-ms`, stored by
  `complete-members` in the batch's one commit; declared in `seon.test.member.edn`.

## Checks

Lint `src script test --cache false`: 0 findings in touched files (one foreign error, `test/seon/problems_test.clj:65`
`problems/block`, against another lane's uncommitted `problems.clj`). Contracts (`seon.contracts-compile-test/check`)
over both namespaces and both test namespaces: 0. Schema adoption: the measurement and transaction attributes were
registered and written on branches of the live store (`:health-recording-final`, `:health-recording-tx`) before the
floor and index rulings; the final resources are proven by root's publication at restart.

Focused tests for root: `seon.profile-test`, `seon.profile.measurement-test`, `seon.test-support` callers of
`armed-counts`, and after the patches `seon.turn-test`, `seon.cluster.turn-test`, `seon.test.runner-test`.
