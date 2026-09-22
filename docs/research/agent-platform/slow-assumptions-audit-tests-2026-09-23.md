---
type: research
status: point-in-time audit, 2026-09-23 (lane slow-assumptions-tests; fact-finding, no src/test/resources edits)
scope: the test and verification path — bin/test, bin/test-fast, the published base, the test JVM, the platform tier, the HEAD-load proof, lane probe JVMs, scratch roots, clj-kondo
---

# Slow assumptions audit — tests and verification

The owner, 2026-09-23: "I want an audit of all assumptions that take a long time.
Every single time we've looked into this it's unnecessary", and "by design most ops
should be sub second." This section covers the test and verification path. Rows are
ranked by estimated time wasted per day (cost × frequency). The frequencies are
estimates, and the basis for each is given. The target they are measured against is
README §7 "Tests run as agents run" and
[one lifecycle](../../prds/agent-platform/plan/lane-realities-one-lifecycle.md): a test
is a one-body isolated agent on the shared JVM. The plan's step is README §4 1.3d
commit 4 (one `seon.test/run` on the handle) and commit 5 (machinery deletion).

## Headline

1. **The installed seams already answer an in-process test request in tens of
   milliseconds.** In the live `default` JVM, measured today: reaching-set selection
   (`seon.fn/gate-sets`) takes 17–37 ms, `sci/fork` takes 0.00047 ms, reading the
   database value takes 0.003 ms, and the recorded warm fixture branch p50 is
   37–46 ms. The same focused test run through `bin/test-fast` spends about 38 s
   before its first test body starts.
2. **Nearly all of the 29–35 s "JVM start and load" is dependency namespaces compiled
   from source. The AOT class cache that removes it already exists and only `bin/test`'s
   full gate uses it.** A bare JVM starts in 330 ms. `(require 'datahike.api)` takes
   8,262 ms on the `-M:test` classpath and 1,413 ms with
   `target/dev-dependency-classes/<digest>` in front of it. The recorded 19.2 s HEAD-load
   check of four namespaces measured **5,338 ms wall** with that cache on the
   classpath. `bin/test-fast`, the HEAD-load ritual, lane probe JVMs and `default`
   itself (pid 51528's classpath has no cache entry) all start with `clojure -M…` and
   pay the full cost.
3. **`bin/_test-slot` still enforces two test-JVM slots** (`test_slot_count=2`). This
   contradicts AGENTS.md and README §4 ("no lane count, no test-JVM slot", owner
   2026-09-23). Recorded slot waits were 21–160 s.
4. **The published test base takes 136–141 s to prepare, goes stale by 46–52 commits,
   and leaves 15.6 GB on disk** (two bases of 7.6 and 7.8 GB, under
   `target/test-published-bases`). Its only purpose is a fixture store, and the branch
   seam replaces it.

## Ranked rows

Legend: **cost** is wall time per occurrence; **freq** is the estimated number of
occurrences per day; **waste/day** is cost × freq, minus the expected cost after the
fix.

### 1. Focused test JVM preamble (`bin/test-fast`, `bin/test --fast --paths`)

- **Who / how often.** Every lane and the orchestrator run it for each focused check.
  About 230 distinct test JVM pids appear in `tmp/**/*.log` modified in the last two
  days (`PACKAGED TEST PROJECTION ACQUIRED … pid=`), so roughly **115/day**.
- **Cost.** Recorded, not re-measured: 46–50 s per command. The phases are snapshot
  4–5 s, JVM plus load about 29–35 s, arming 1,692–1,702 contracts 2.4–4.1 s, and the
  bodies themselves 2.4–10.2 s. Sources:
  `docs/seon/issues/a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md`
  and `landing/lane-oversight-owning-instance-2026-09-23.md:116`. The preamble before
  the first body is about 38 s.
- **Measured split of the load (this audit).** Bare JVM 330 ms. `clojure -Spath
  -A:test` 607 ms. On the `-M` classpath, `require seon.db` takes 7,816 ms in a fresh
  JVM. With the AOT dependency cache on the classpath it takes 1,339 ms, and datahike,
  sci, malli, core.async.flow and clj-kondo together take 1,659 ms. The load is
  proportional to the **whole dependency closure**, not to the change.
- **Work.** A JVM compiles every dependency namespace and every Seon namespace. It then
  acquires the packaged projection, arms 1,700 contracts and opens the stale published
  base. All of this is whole-program work, repeated for every focused run.
- **Assumption.** A test needs its own JVM so that it sees the edited bytes and
  cannot disturb `default`.
- **True?** No, for ordinary edits. The branch is the isolation. `d/branch!`
  (`reference-code/datahike/src/datahike/versioning.cljc:212`) gives a pointer and no
  copy. `sci/fork` (`reference-code/sci/src/sci/core.cljc:345`) gives a child context
  that the original never sees. Custody (`seon.db/call-with-custody`) scopes writes to
  the branch's connection. Edited bytes reach the branch through the index function
  (`seon.fn/index!` on the branch). A `defn` change needs no recompilation of its
  callers, because Clojure's Var indirection means callers deref the Var
  (README §4 row A, ruled 2026-09-23). The installed `seon.test/run` already takes
  `[test-var connection options]` (probe P6). The assumption holds only for destructive
  or host-bound members; README §7 keeps the platform host for those.
- **Verdict.** REPLACE with `seon.test/run` on a branch in `default`. Interim
  (DELETE-able now): prepend the existing AOT dependency class directory to
  `bin/test-fast`'s classpath, since `bin/test` already resolves it in its
  `dependency-cache-and-classpath` phase. Clojure itself prefers a class file that is
  newer than its source (`reference-code/clojure/src/jvm/clojure/lang/RT.java:440`).
- **After.** In process: selection 17–37 ms, fork below 0.001 ms, fixture branch
  37–75 ms, arming only the changed identities, then the bodies. Interim with the AOT
  classpath: roughly 38 s drops to about 12–15 s (dependency load falls by about 6 s
  per 8 s measured; the Seon namespaces still compile).
- **Waste/day.** About 115 × 38 s ≈ **73 min/day**. The interim fix saves about
  40–45 min/day; 1.3d c4 saves nearly all of it.
- **Fix size / owner.** 1.3d commit 4 (`src/seon/test.clj`, one request) and commit 5
  (deletes `bin/test-fast`, workers, slots). Interim: about 3 lines in `bin/test` at
  the fast branch (`bin/test:876-884`), reusing `dev_cache_path`. That is the owner's
  call, since c5 deletes the file.

### 2. From-zero scratch-root boot as a landing proof ("schema change ⇒ scratch boot")

- **Who / how often.** Lanes and the orchestrator run it after schema, resource or
  indexer edits. 26 landing notes under `docs/prds/agent-platform/landing/` cite a
  from-zero or scratch-root boot. Estimate **about 10/day** over 2026-09-21/23.
- **Cost.** Recorded, not re-measured: readiness 103,142 ms and 111,444 ms
  (`fable-monitor-notes-2026-09-21.md:470-471`), 152,951 ms
  (`landing/lane-a2-db-deletions-2026-09-23.md:480`), and 168,184 ms to a refusal
  (`landing/lane-publication-envelope-2026-09-22.md:134`).
- **Work.** A fresh JVM compiles the whole closure without the AOT cache, indexes the
  whole program, populates the schema and boots flow. All of it is proportional to the
  whole program.
- **Assumption.** A schema or resource change can only be proven by building a store
  from nothing.
- **True?** No. The owner ruled it out on 2026-09-23 (README §4 1.3e, superseded
  row: "a schema change should not require a from scratch boot. Period."). Accretion
  already lives in `src/seon/cluster.clj` (`declaration-changes` about :1033,
  `accretive-property-change?` about :968, per the brief). The proof is a declaration
  transaction on a branch of the live store, and it is change-proportional.
- **Verdict.** DELETE the ritual. What remains is a declaration transaction on a
  fixture branch, costing a fixture branch (37–75 ms) plus one transaction.
- **After.** Sub-second. The platform tier keeps whatever from-zero member it declares.
- **Waste/day.** About 10 × 130 s ≈ **22 min/day**, plus each lane's own scratch root
  (703 MB across 8 `tmp/*root*` entries today).
- **Fix size / owner.** Ruling already recorded (AGENTS.md, README 1.3e). The
  remaining step is to remove "from-zero boot" from lane-spec templates and landing
  checklists. Blocked in practice by row 4: no current canonical fixture branch can be
  opened (`docs/seon/issues/test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md`,
  last paragraph).

### 3. Waiting for a test-JVM slot (`bin/_test-slot`)

- **Who / how often.** Every `bin/test` and `bin/test-fast` invocation, about
  115/day.
- **Cost.** Recorded in retained ledgers (`tmp/test-runs/run.*/test-run.txt`):
  0/0/21/30 s. Across all `PHASE test-slot` lines in the last two days of logs:
  300 × 0 s, plus waits of 21, 25, 26, 30, 36, 65, 75 and 160 s. About 2,900 s over
  380 lines, a mean of about 7.7 s. Some lines are duplicated by tee'd logs.
- **Work.** None. The invocation waits for one of two directory slots.
- **Assumption.** Concurrent test JVMs starve the machine (2026-09-15: eight JVMs,
  40 GB).
- **True?** Moot. The owner ruled on 2026-09-23 (AGENTS.md "no lane count, no
  test-JVM slot, no prober cap"), yet the script still enforces
  `test_slot_count=2` (`bin/_test-slot`). The underlying cost is row 1's JVM, which
  goes away.
- **Verdict.** DELETE. The script is listed in 1.3d c5 ("slots").
- **After.** 0.
- **Waste/day.** About 115 × 7.7 s ≈ **15 min/day**. This is an estimate and
  duplication inflates it.
- **Fix size.** Delete `bin/_test-slot` (about 150 lines) and its two `source` sites.
  1.3d c5 owns it. This is a current-behavior versus AGENTS.md contradiction and should
  be corrected in the same commit.

### 4. Published test base and `bin/test --prepare-head-base`

- **Who / how often.** The orchestrator runs it, and every gate reuses it. Cold
  preparations appear in logs at 136 s ×3 and 141 s ×1, with further 50–54 s phases,
  `bin/test` rows 50–54. Estimate **2–4 cold preparations per day**. Separately, every
  fast run reads the newest base, whatever its age.
- **Cost.** Recorded: 141 s published-base plus 73 s coordinator
  (`research/agent-platform/tests-as-agents-data-pack-2026-09-22.md:241-242`); 136 s
  (`fable-monitor-notes-2026-09-21.md:236`); 13.9 s warm
  (`fable-monitor-notes:472`); a 484 s export on a 248 GiB source
  (`issues/the-cold-fixture-base-outruns-the-liveness-silence-backstop.md`,
  base-export section). On disk today there are 4 bases totalling 15.9 GB (7.8 +
  7.6 GB + 317 MB + 232 MB) in `target/test-published-bases`.
- **Work.** It publishes HEAD into a private store, exports it, and serves it as the
  fixture population. The work is proportional to the whole program and the store.
- **Hidden cost: staleness.** `bin/test-fast` takes `cache/newest-base`
  (`src/seon/test/cache.clj:566`) at 46–52 commits behind HEAD. Three lanes lost runs to
  11 fixture-setup errors each (issue `test-fast-runs-on-a-published-base-older-than-heads-schema-validator.md`).
  The preparation step itself reads the live checkout's `resources/`
  (`issues/prepare-head-base-reads-the-live-checkouts-resources.md`), so it fails
  whenever any lane holds a resource hunk (6.67 s refusal).
- **Assumption.** A test fixture needs a copy of a published store.
- **True?** No. A fixture is `registry/branch!` → `store/open-branch!` off a captured
  commit of the live store (README §7). The recorded cost is 74.65 ms for the branch
  plus 25.62 ms to open it (README §7 candidate row), with a 37 ms p50 warm. Datahike's
  commit id names the immutable root
  (`reference-code/datahike/src/datahike/db.cljc:385-396`, `committed-value-identity`),
  so a fork at an unchanged commit acquires nothing
  (`src/seon/sci/eval.clj` `acquired-database?`).
- **Verdict.** DELETE with 1.3d c4/c5. Interim: none worth building, because the issue
  note's own disposition says "do not repair in place". The 15.9 GB of old bases can be
  swept once no gate holds them. That is the orchestrator's action.
- **After.** 0 preparation. A fixture is a branch.
- **Waste/day.** About 3 × 140 s ≈ 7 min, plus the stale-base red runs (about 3 lanes ×
  3 runs × 46 s ≈ 7 min) and their diagnosis: **about 15 min/day**.
- **Fix size.** 1.3d c4/c5 (`src/seon/test/cache.clj` base publication, the
  `bin/test` published-base phase, `test_support` base acquisition).

### 5. "HEAD loads" proof in a fresh JVM from `git archive`

- **Who / how often.** Every lane at every commit, as AGENTS.md and README §6 make it
  the commit gate. 10 landing notes cite an archive load. With 265 commits in 36 h,
  estimate **about 40 archive loads/day**.
- **Cost.** Recorded 19.2 s for four namespaces
  (`landing/lane-oversight-owning-instance-2026-09-23.md:119`). Measured today:
  `git archive HEAD | tar` takes 645 ms (161 MB). The same four-namespace require with
  the AOT dependency classes takes **5,338 ms wall** (`seon.db` 1,339 ms, then the four
  in 3,283 ms, 447 namespaces loaded). On plain `-M`, `seon.db` alone takes 7,816 ms.
- **Work.** It compiles the whole closure of the named namespaces from the committed
  bytes.
- **Assumption.** A warm JVM cannot prove that the committed bytes load, because
  stale Vars or namespaces survive and hide a deleted definition.
- **True?** Partly. A warm `require :reload` (`reference-code/clojure/src/clj/clojure/core.clj:6111`)
  never unmaps a deleted Var, so a caller of a deleted Var still resolves in a warm
  JVM. That case is exactly what clj-kondo's unresolved-var lint catches statically on
  the changed files: 27 ms for one file, 4.3 s for all of `src`+`test` (measured, P8).
  Compiling the committed bytes is proportional to the closure only because the
  dependency cache is missing.
- **Verdict.** REPLACE now: run the same check on the AOT classpath (19.2 s → about
  5.3 s). After 1.2b/1.3d, the save-time `require :reload` of the changed namespaces
  plus dependents is the load proof, together with a kondo pass over the changed
  files. The fresh-JVM archive load remains only at a cut boundary, next to the
  platform tier.
- **Waste/day.** About 40 × 14 s saved ≈ **9 min/day** now, and about 12 min/day
  after the save-time gate.
- **Fix size.** A one-line classpath prefix in the lane checklist, or a small
  `bin/` helper. No plan row owns it yet; README §6 owns the commit-gate wording.

### 6. Lane probe JVMs (`clojure -M:test tmp/<lane>/probe.clj`)

- **Who / how often.** Lanes run them when they cannot or will not use MCP on
  `default`. Estimate **about 20/day**, from landing notes citing
  `clojure -M -e "(require …)"` or probe scripts.
- **Cost.** Recorded 20.9 s wall for a 13 ms probe body
  (`issues/a-focused-test-jvm-spends-thirty-seconds-before-its-first-test.md`).
- **Assumption.** A lane's uncommitted source can only be exercised in a JVM that
  loaded it.
- **True?** Only until 1.4c. MCP `eval_clj` already has a `branch` member that
  acquires an isolated handle through the agent entrance (1.3d c2 landed,
  `1ada78050`). Hook publication is paused, so edits do not reach a branch yet. Once
  the lane's candidate branch and reload exist (1.4c, 1.2b), the probe runs there in
  milliseconds.
- **Verdict.** REPLACE with `eval_clj` on the lane's branch. Interim: the AOT
  classpath takes the 20.9 s down to roughly 6 s.
- **Waste/day.** About 20 × 15 s ≈ **5 min/day**.
- **Fix size.** 1.4c (candidate per lane). No new code for the interim beyond row 5's
  helper.

### 7. Full gate `bin/test` (coordinator + workers) and the platform tier

- **Who / how often.** The orchestrator at each step landing (README §6). Estimate
  **about 5/day**.
- **Cost.** Recorded: coordinator plus tests 73 s (data pack :242); platform tier
  74 s with 0 failures and 2 errors (`fable-monitor-notes:472`); snapshot 2–5 s,
  dependency cache 4 s (40 s and 57 s cold), worker checkouts 0–5 s
  (`tmp/test-runs/run.*/test-run.txt`, `bin/test:46-66` bound comments).
- **Assumption.** Platform and destructive members need an isolated host.
- **True?** Yes, for 16 test files carrying `:seon.test/platform` and the 66 tests
  reaching the two `:seon.fn/destroys` owners (README §4 1.3d c5). They stay in
  isolation until B4 §2e proves confinement. The snapshot, the worker checkouts and
  the per-invocation base are not needed for those members either: the one request
  runs them in one isolated JVM from one archive.
- **Verdict.** KEEP the isolated platform host. DELETE the worker, checkout and
  snapshot-overlay machinery (1.3d c5). The platform JVM should also load from the
  AOT cache, which it already does through `-Scp "$test_classpath"`.
- **Waste/day.** About 5 × 20 s of overhead ≈ **2 min/day**. It is small because it
  is rare.

### 8. Per-test fixture acquisition and contract arming inside a test JVM

- **Cost.** Recorded: first `with-database` 1,604 ms (projection 809.586 ms), then a
  subsequent p50 of 45.9 ms; arming 1,692–1,702 contracts in 2.4–4.1 s per JVM
  (issue `the-cold-fixture-base-outruns…`, run `a642f8d78e9f`).
- **Assumption.** Each JVM must derive its projection and arm every contract.
- **True?** Only because each test gets a new JVM. In `default` the projection is a
  memoized function of the value keyed by `:cache-context` (README §7, ruled
  2026-09-23; `reference-code/datahike/src/datahike/db.cljc:394`), and every contract
  is already armed. A branch arms only its changed identities (README §4 row C, 1.3).
- **Verdict.** DELETE with row 1. This cost is already counted inside row 1's
  preamble.
- **Note for the c4 lane.** `seon.sci.eval/base-ctx` is not memoized. Measured today,
  three calls on one unchanged commit took 452, 1,192 and 537 ms. The commit-id reuse
  sits in `acquire!`/`acquired-database?` (`src/seon/sci/eval.clj` about :2285-2330).
  A test request must fork the held, acquired cluster context (`fork-cluster-ctx`),
  never call `base-ctx`. Otherwise every test pays about 0.5–1.2 s of whole-program
  context construction.

### 9. clj-kondo lint runs

- **Cost.** Measured on a scratch copy of the 14 MB cache: one file 27 ms; `src` and
  `test` with `--parallel` 4,301 ms. The dependency repopulation
  (`clj-kondo --lint "$(clojure -Spath)" --dependencies --skip-lint --copy-configs`)
  was not measured; it runs only on a stale cache.
- **Assumption.** A mechanical sweep needs a full lint.
- **True?** Yes, for a sweep across many files, and 4.3 s is proportional to the
  linted set. The in-JVM analyzer's shared-cache lock contention
  (`issues/a-fast-gate-jvm-dies-on-the-shared-kondo-cache-lock.md`) is a symptom of
  several JVMs sharing one checkout, and disappears with row 1.
- **Verdict.** KEEP: per-file in the hook, full lint once per sweep.
- **Waste/day.** Negligible.

### 10. RESET NEEDED of `default`

- **Cost.** Recorded readiness of 111,444 ms (pid 51528), plus loss of turns, tasks and
  private state.
- **Assumption.** A schema or identity change in stored data needs a new store.
- **True?** For accretive changes, no (row 2). For a replacement with different
  semantics, AGENTS.md already says to use a new key, and retirement needs only the
  1.3e retraction path. Truly incompatible stored shapes remain; the owner allows
  those resets ("DB data is disposable").
- **Verdict.** REPLACE most occurrences with incremental adoption (1.3e). This belongs
  to the boot/publication audit section; it is listed here only because lanes emit it
  as a verification ritual.

## Timings of this audit's own probes

All probes ran on 2026-09-22 local (2026-09-23 UTC), on the host that runs `default`
(pid 51528, commit `da703089b` plus the working tree). Each probe JVM had a hard
10–25 s `timeout`, exited, and was confirmed gone. Scratch copies were deleted.

| # | probe | result |
|---|---|---|
| P1 | `clojure -Spath` (warm cpcache) / `clojure -Spath -A:test` | 33 ms / 607 ms |
| P2 | bare JVM `clojure -M -e nil` | 330 ms |
| P3 | `git archive HEAD \| tar -x` into scratch (161 MB) | 645 ms |
| P4 | `bb -e nil` / `bb … cache/newest-base` (the test-fast pre-step) | 36 ms / 89 ms |
| P5a | fresh JVM, `-M:test` classpath: `(require 'datahike.api)` | 8,262 ms |
| P5b | fresh JVM, AOT dev-dependency classes first: `(require 'datahike.api)` | 1,413 ms |
| P5c | AOT cp: datahike + sci + malli + core.async.flow + clj-kondo | 1,659 ms |
| P5d | fresh JVM, `-M`: `(require 'seon.db)` (in-JVM clock) / wall | 7,816 ms / 8,235 ms |
| P5e | AOT cp: `seon.db` then `seon.oversight seon.render.web seon.cluster seon.cluster.agent` | 1,339 ms + 3,283 ms; **5,338 ms wall**; 447 namespaces (recorded plain-`-M` equivalent: 19.2 s) |
| P6 | `default` JVM (MCP, read-only): `d/db` / arglists | 0.003 ms; `seon.test/run [test-var connection options]` installed |
| P7 | `default`: `seon.fn/gate-sets` for `seon.id/valid?` / `seon.oversight/agent-views` / `seon.db/transact!` | 17.96 ms → 17 tests / 17.40 ms → 0 / 36.85 ms → 1,349 |
| P7b | `default`: `sci/fork` of the program base ctx, mean of 1,000 | 0.000466 ms |
| P7c | `default`: `seon.sci.eval/base-ctx` on one unchanged commit, three calls | 452 / 1,192 / 537 ms (not memoized) |
| P8 | clj-kondo, scratch cache copy: `src/seon/oversight.clj` / `--parallel src test` | 27 ms / 4,301 ms |
| P9 | disk: `target/test-published-bases` / `target/dev-dependency-classes` / `tmp/*root*` | 15.9 GB (4 bases) / 382 MB / 703 MB |

## What remains after 1.3d lands, and what can go now

| ritual | now | after 1.3d c4/c5 + 1.4c |
|---|---|---|
| focused test | DELETE the slot wait; REPLACE the classpath with the AOT cache (about 38 s → about 12–15 s) | `seon.test/run` on a branch in `default`: tens of ms plus the bodies |
| from-zero scratch boot | DELETE as a landing proof (already ruled) | a declaration transaction on a fixture branch |
| published base / `--prepare-head-base` | stop running it while resource hunks are open (issue disposition); sweep 15.9 GB | deleted; a fixture is a branch |
| HEAD-load check | REPLACE with the AOT classpath (19.2 s → 5.3 s) | save-time `require :reload` of changed namespaces plus dependents, plus kondo on changed files; the fresh JVM only at a cut boundary |
| probe JVMs | AOT classpath (about 21 s → about 6 s) | `eval_clj` with the lane's `branch` |
| platform tier | KEEP, isolated | KEEP until B4 §2e proves confinement; run through the one request |
| kondo | KEEP | KEEP |

## Out-of-scope findings (not filed; this lane commits only this file)

- `default` itself boots without the AOT dependency cache. Pid 51528's `-classpath`
  has no `dev-dependency-classes` entry, and datahike alone costs about 6.8 s of its
  111 s readiness. This belongs to the boot audit section. No issue note was written
  because this lane may commit only its own file.
- `bin/_test-slot` contradicts the current AGENTS.md "no test-JVM slot" ruling (row 3).
  It is a current-claim versus behavior mismatch owned by 1.3d c5.
- `seon.sci.eval/base-ctx` costs 0.45–1.2 s on every call for an unchanged commit
  (P7c). Callers must go through `acquire!`, which is commit-id memoized. This is
  relevant to the B2 and 1.3d c4 lanes.
