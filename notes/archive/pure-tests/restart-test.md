# Where `a-restart-catches-up-and-a-new-generation-resumes` spends its time

`seon.source-test/a-restart-catches-up-and-a-new-generation-resumes` (test/seon/source_test.clj:236)
took 351 s in the baseline `bin/test --all`. In one timed run of the test alone it took 481 s, and
**73% of that was its two loop turns (351 s)**. The build plus the `target/` clone was 65 s (13%), and
the clone itself took about 5 s of that. The reviewer's estimate ("build + copying target/ is at
least half") is wrong for this run: an APFS `cp -cR` of 2.1 GB copies only metadata.

## How it was measured

- I ran the test alone (`jolt -A:test run` with a scratch script calling `clojure.test/test-vars`)
  on branch jolt/pure-tests at 7878c90, under `nice -n 15`.
- I edited the test temporarily to print the wall time after each phase. The edit is reverted:
  `git diff test/seon/source_test.clj` is empty.
- I kept the run's directory to read the generation's `.build.log` and its file times, then deleted it.
- Load average was 38 to 53 during the run (18 cores) and 25 at the end, so every number is inflated
  by contention. I ran it once: the owner ruled no second run (load about 60, swap nearly full).
- The test passed.

## Phases (one run, 2026-10-23 06:20:41 to 06:28:42)

| phase | s | % | what it does |
|---|---:|---:|---|
| `git clone --local` | 0.6 | 0 | the tree root imports |
| `bin/root start` (new store) | 11.5 | 2 | boot, import of src, test and schemas |
| **turn 1** `(seon.e2e/loop-turn! sys)` | **193.3** | **40** | release, `root/run!`, merge, commit, load in place, `quiet!`, `fresh` agent |
| **turn 2** `(seon.e2e/loop-turn! sys :loop-2)` | **157.4** | **33** | the same, adding `seon.fixtures/generations` |
| SIGKILL and wait | 0.1 | 0 | |
| `bin/root start` (catch-up) | 23.5 | 5 | boot, `load-all!` of main's two merges, re-import of test/ |
| the asks after the restart | 0.0 | 0 | |
| `my.root/build!` | 64.9 | 13 | breaks down into the next three rows |
|  of which `bin/worktree --at` | ~5 | 1 | `git worktree add`, 12 submodule worktrees, `cp -cR` of target/ (06:27:07 to 06:27:12 in `.build.log`) |
|  of which the stamp checks | ~1 | 0 | chez, jolt, native, lmdb, lz4 and zstd all "up to date" |
|  of which `jolt build -m seon.check` | 59 | 12 | 06:27:13 to 06:28:12, with **warm** shared caches (see below) |
| `bin/root next` | 26.9 | 6 | stop, cold boot of the generation binary, catch-up |
| final ask, `bin/root stop`, `--remove-at` | 2.6 | 1 | |
| **total** | **480.9** | | |

Two more observations:

- My standalone runner process exited 59 s after the test finished (06:28:42 to 06:29:41). I did not
  find the cause. Under `bin/test` that is shard teardown, paid once per shard rather than once per test.
- **The build hits the warm cache only because of inherited environment.** `seon.fx/build`
  (src/seon/fx.clj:244, `clean`) unsets `SEON_CHECKOUT`, `JOLT_LMDB_LIB`, `JOLT_PWD` and `HOME`, but
  not `JOLT_CACHE_DIR`, `JOLT_BUILD_CACHE_DIR` or `JOLT_RUNTIME_CACHE_DIR`. `bin/env` keeps an
  inherited value (`${VAR:-…}`), so the generation used the root's caches, which are the main tree's
  `target/cache`.
  - Evidence: `tree/target` was never created.
  - Without that inheritance, `bin/env` would derive `MAIN_TREE` from the generation's git common dir.
    In this test that is the throwaway clone (`tree/.git`), so the caches would be cold and the build
    much slower.
  - In production the source root is the main tree, so both routes give the same answer. The test
    relies on this, and nothing states it.

## What dominates

The two loop turns: 351 s, 73%.

- The test's own docstring budgets about 14 s per turn. This run measured 157 s and 193 s, 11 to 14
  times that.
- Load (about 45, all of it niced) explains part of it. That a turn costs 150 s or more is a finding
  in its own right: it is the same turn that check step e2e-loop runs, and probably part of resume's
  cost too.
- I did not split up a turn's time:
  - `loop-turn!` already returns `:turn-ms` (release to quiet) and `:spans` (each attempt's start and
    end), but the test discards them.
  - The rest of the turn is `fresh`: forking an agent after the merge and waiting for its `:ready`.
- Turn 2 is 36 s cheaper than turn 1, which suggests a first-time cost (the fork server warming up,
  or the first merge's load of the program in place).

## Proposals, ranked by measured saving, each keeping the proof

1. **Find why a turn costs 150 to 190 s instead of about 14 s.** Up to about 320 s here, and it also
   helps e2e-loop and production.
   - First step, no code change: in a scratch run, print `(select-keys t1 [:turn-ms :spans :quiescent])`.
     That splits model and agent time from merge and commit, from `quiet!`, and from `fresh`.
   - Suspects to check:
     - with `:seon.config.source/dirs` set to `["src" "test" "resources/seon/schemas"]`, the program
       is about twice what e2e-loop loads, and the merge's load in place
       (`seon.program/load-changes!`, `seon.merge/program-cost-follows-what-changed`) may cost per
       program rather than per change;
     - agents forked from a root under load 45 at nice 15;
     - `await-quiet` waiting on something that is not the turn's work.
   - It may change production: a turn is the owner's merge path.
   - Hand it to an agent with this note as its brief.

2. **One turn instead of two.** Saves about 157 s (33%).
   - What the proof needs from the turns: main holds merges commits the old binary has not loaded, so
     the restart must catch up at boot; and both a changed definition (`generation` 1 → 2) and a new
     one (`generations`) are run.
   - One scenario can make both changes in one task and one merge:
     - a `:loop-restart` entry in `seon.fixtures/scenarios` (src/seon/fixtures.clj, about 10 lines),
       or reuse `:loop-2` with a base that already has `generation` 2;
     - the test (source_test.clj:285-289) asks once and expects `(count (:commits t)) = 1`.
   - Kept: the catch-up of a redefined var and an added var, `build!` from the last merges commit, and
     `bin/root next`.
   - Lost: only "two successive merges each live in place". e2e-loop already proves the first; nothing
     else proves the second, so decide whether it is worth 157 s.
   - Test-only: no production change.

3. **Run the turn once, in e2e-loop, and continue there.** Removes the duplicated turn from the suite
   as a whole: about 190 s across `bin/test` plus `bin/check`.
   - Fold the kill → catch-up → `build!` → `bin/root next` sequence into check step e2e-loop
     (`seon.e2e/loop-run`, src/seon/e2e.clj:145, plus `u-e2e-loop` at bin/check:195), and delete the
     test. e2e-loop already boots a root on a clone and runs `loop-turn!`.
   - It moves 130 s or more of restart and build into `bin/check`, and the restart through `bin/root`
     (the owner's path) would have to be driven from inside a step that is itself the root.
   - About 40 lines moved, test-only. Worth it only if (1) and (2) leave the test long.

4. **The build, 59 s, is already warm.** No cheap saving keeps the proof.
   - The merge changed `seon.fixtures`, so the generation's tree differs from every built tree, and
     `jolt build` re-emits and re-links the whole app. `bin/test`'s own comment puts a no-op build at
     about 30 s; under load it was 59 s here.
   - A faster link is Jolt work, on a `jolt/<topic>` branch.
   - Reusing a binary by tree id (§5, content-keyed reuse) gives no hit here.
   - **Record the cache inheritance described above** as a sentence in seon.fx/build's docstring (or
     docs/source.md#shared-build-caches), so nobody "cleans" those variables away and turns this into
     a cold build. No time saved; it protects the 59 s from becoming several minutes.

5. **Make `bin/worktree --at` clone only what `bin/build` reads.** Saves 1 to 3 s at most; the whole
   `--at` step measured about 5 s, including the git worktrees.
   - `bin/build seon` reads `target/chez`, `target/jolt`, `target/lib`, `lmdb-kill`, the `*.stamp`
     files, and `target/home` without `.jolt`. It never reads `target/src`.
   - It does not need `seon.build` (776 MB), `hello.build` (448 MB), `seon`, `hello`, `check-store`,
     `prove` or the logs. Jolt rewrites `<out>.build`'s app units every build and caches the compiled
     `.so` in `JOLT_BUILD_CACHE_DIR`, not there.
   - Change bin/worktree:66-68 from an exclude glob to an include list, about 3 lines. Only for
     `--at`: `bin/worktree NAME` sessions want `seon` and `hello` so they can test without a build.
   - It does change production (`seon.fx/build` is the real generation path), but the time saved is
     negligible. The gains are a smaller tree and quicker `--remove-at`. Low priority.

6. **The catch-up boots cost about 12 to 15 s each** (23.5 s and 26.9 s, against 11.5 s for a fresh
   boot). That is `load-all!` of main's program plus the re-import of test/. I did not measure inside
   them. Look at them only after (1).

## What the test needs no matter what

A real merge on main, a SIGKILL and a restart through `bin/root`, a real `bin/build seon` in a
generation worktree, and `bin/root next`. The floor under this load is about 1 boot + 1 turn
(14 s if (1) succeeds) + 1 catch-up boot + 1 build + 1 cold boot, roughly 11 + 14 + 24 + 65 + 27 ≈ 140 s.

## Not measured

- How a turn's time divides (proposal 1's first step).
- The same test at low load.
- What happened in the 59 s exit tail.

Every number above comes from one run at load 38 to 53.
