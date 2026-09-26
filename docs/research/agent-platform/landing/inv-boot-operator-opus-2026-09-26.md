# Landing: inv-boot-operator-opus (2026-09-26)

Commit f9183ee43. Net source +104 lines (184 added, 80 deleted in `operator.clj`,
`dependency_digest.clj`, `boot.clj`). New test file: +96 lines.

## Broken or unverified (first)

- **RESET NEEDED.** Nothing here ran against a live boot. Lanes may not stop
  default, so the new plain `start`, the new output and the exit codes are
  unproven on a real JVM. Root must run the steps under "What root must run".
- **The next restart pays the class-cache fill once.** The key changed, and the
  SCI pin moved at 5bed888d0. Option A fills it before the old JVM stops:
  about 111 s added to that one `start --head`, while default keeps serving.
- **`init --dev --changed` is inert against an archive JVM.** Changed checkout
  paths lie outside its source directory and are refused
  (`cluster.clj:2921`). `bin/seon-hook` never published; it only lints and asks
  the live root to compile contracts. So there was no hook publication step to
  delete, and the `.codex/hooks.json` and `.claude/settings.json` entries stay
  because they run the linters. I corrected the hook's and `seon-hook.edn`'s
  comments. Still stale, not mine:
  - `.agents/skills/repl/SKILL.md:159` teaches adoption by `init --dev --changed`.
  - `bin/test-check` sends `--changed` (see `operator.clj` `parse-argv`); its
    use against archive JVMs needs its owner's ruling.
- **Docs corrected (root-granted):**
  - AGENTS.md:326 (the `start` sentence).
  - `.agents/skills/seon-flow-architecture/references/degraded-start.md` §3
    (the patch applied there; `SKILL.md` itself has no `start` claim).
- **The REPL static check failed on other lanes' work.** I ran
  `seon.contracts-compile-test/check` over the checkout with my `boot.clj`. It
  threw "A stored error member must have a storable registered attribute" for
  `:seon.render.value/insufficient-floor-budget-error` (member
  `:seon.render.data/path`). The cause is uncommitted schema edits by other
  lanes (`resources/seon/schemas/seon.render.value.edn`, `seon.print.edn`,
  `seon.render.walk.edn`). My `boot.clj` change touches only a message string,
  and clj-kondo reports 0 errors.
- **The CLI still runs from the working tree.** `bin/seon` runs babashka on the
  checkout's `script/`, and `shipped-default-decisions` reads the checkout's
  `config/default.edn`. This affects the command-line tool, not boot. A
  half-edited `operator.clj` still breaks the tool.

## Defect 1: boot reads the committed archive, never the working tree

Reproduced by reading the code. Plain `start` with no live JVM called `launch!`,
which ran `(launch-child! request (repository-root) true)`, so the program
directory was the checkout. Boot then ran `changed-source-paths` against the
published program (`boot.clj:120`). It compared the working-tree files, so any
half-edited file went into publication and refused the boot ("Static program
analysis found blocking errors", open-items platform state).

I found no other path by which boot reads the checkout:
- `start --head` and `nuke` already ran archives.
- Inside the JVM, `fs/source-directory` is the archive (checked live:
  `.../data/source/61d207c3e…`).
- `publication-paths` refuses changed paths outside it (`cluster.clj:2921`).

**Fix.** `prepared-source!` is the one "archive + shared caches + classpath"
step. `launch!` (plain `start`) and `move-to-head!` both use it, so every
launch runs HEAD's archive. `program-source`'s hook-publication `:on`/`:off`
field and the `HOOK-PUBLICATION` stderr line are deleted, because the value
was now always `:off`.

**What `start` now means:**
- If no JVM is running, `start [NAME]` launches HEAD's committed archive and
  starts NAME in it. It keeps the store and caches, and it does no rollback and
  no adoption.
- If a JVM is running, `start NAME` asks that JVM to start the cluster
  (unchanged).
- `start --head` is the only way to move a running root to a new commit. It
  replaces the JVM, rolls back if the new one fails, and adopts.
- Uncommitted edits reach no JVM. To run them, commit, then `start --head`.
- With no JVM running, `start` is the same as `start --head` minus the
  head-capture and rollback. Root could make `start` with no JVM run
  `move-to-head!` for `default` and keep `launch!` only for named scratch roots.

**Regression:**
`seon.operator-outcome-test/a-start-runs-the-committed-archive-never-the-checkout`.
It checks that the program directory is `<root>/data/source/<HEAD sha>`, not
the checkout, that `target` is a link, and that `.cpcache` was computed before
any stop.

## Defect 2: 30 s boot and a class-cache miss on every restart

**Where the time goes.** `:seon.boot/layers` and the operator phases, from
`tmp/orchestrator/start-head-*.txt`:

| Move to | classes | total | pre-boot | ready | store→branch | adopt |
|---|---|---|---|---|---|---|
| 6ccf12a5a | hit | 28.7 s | 6.3 s | 13.3 s | 10.2 s | 6.4 s |
| 5bed888d0 | miss | 56.5 s | 15.6 s | 30.3 s | 26.1 s | 9.9 s |
| 61d207c3e | miss | 52.2 s | 12.3 s | 30.5 s | 27.3 s | 6.7 s |

The 61d207c3e layers, in cumulative ms: prepl 334, store 473, branch 27745,
connection 27752, commit-id 27760, config-result 28349, ctx 29765,
work-launcher 29765, graph 30441, web 30474.

**Top costs and the functions responsible:**

1. **store→branch took 27.3 s, and it was a full republication.**
   - I placed the boot window at 15:49:10.8–15:49:38.0Z. The JVM started at
     15:48:58.2; launch-ms minus ready-ms gives the offset.
   - Within that window:
     - The analysis cache (`data/source/analysis-cache`) rewrote all 466
       first-party namespace entries at 15:49:18–20.
     - `current-src` took three transactions: 15:49:27.9, :30.1 and :37.7Z.
       The last one stores the new `:seon.source/analyzer-digest`
       (d59929e6 → 358cc097; history query recorded).
   - Between the two programs (5bed888d0 → 61d207c3e), 13 input files changed,
     including `src/seon/fn/analyzer.clj` and `src/seon/cluster/source.clj`.
     Both are in analyzer reach, so `seon.cluster/full-source-refresh!` chose
     `:all` (`cluster.clj:1963`). It lints and analyzes every namespace through
     `seon.fn/analyze-rows`: lint ≈ 8 s, analysis ≈ 8 s, three transactions
     ≈ 10 s.
   - Root ruled to keep this behaviour. So "30 s boot" is the cost of a commit
     that touches analyzer reach, not the steady-state boot.
   - Warm, the same steps now take 6 ms (`ensure-dependency-analysis!`) and
     85 ms (`changed-source-paths`, 0 changed), measured live.
   - These costs are in `cluster.clj` and `fn.clj`, which I do not own.
2. **Pre-boot took 12.3 s on a miss and 6.3 s on a hit.** Pre-boot is the JVM
   start, `(require 'seon.cluster.boot)` and head capture. On a miss, about 450
   dependency namespaces (clj-kondo 82, datahike 56, SCI 37, and others) compile
   from source.
3. **Adoption after readiness took 6.4–9.9 s.** The profile shows
   `seon.cluster/development-source-refresh!` at 5171 ms and
   `seon.cluster/adopt-rows!` at 2761 ms. They re-adopt what the fresh JVM
   already loaded (already in
   [move-to-head-takes-a-minute](../../../seon/issues/move-to-head-takes-a-minute-or-more-not-seconds.md)).
4. config-result→ctx took 1.4 s and →graph 0.7 s.

**Why the dependency-class cache misses.** The cache key is the SHA-256 of
`deps.edn` bytes, the submodule pins and the JVM runtime identity. I recomputed
it per commit with `configuration-digest`:
- 6ccf12a5a: e23ac1f3…. This equals the only cache's `:input-digest` (hit).
- 5bed888d0: 9eda2e95…. The SCI pin moved. That is a real invalidation,
  because the cache compiles SCI.
- c2b757cb3 and 61d207c3e: edd1c412…. Only `deps.edn` `:jvm-opts`
  (HeapDumpPath) changed. That is a false invalidation.
- Between those changes, nothing fills the cache. The operator reports the
  fill command in `:seon.dev-cache/fill` and never runs it. So every restart
  misses until someone runs `clojure -T:dev-cache ensure-cache` by hand.

**Fix (the false invalidation).** `dependency-digest/dependency-declarations`
digests the `deps.edn` value with the aliases' launch options removed
(`:jvm-opts :main-opts :exec-fn :exec-args :ns-default :ns-aliases`). The
printer is bound so babashka and the JVM print identically. Checked:
- babashka and `clojure -M` produce the same digest (56b89042… for
  5bed888d0 = c2b757cb3 = 61d207c3e).
- The existing `dependency_cache_test` assertions still hold: same pins give
  the same digest, moved pins give a different one, a recorded root digests
  like the checkout.

**Regression:** `the-class-cache-key-ignores-launch-options`.

**Fill (root ruling: option A, implemented).** `filled-classes!` runs after
`prepared-source!` in both `launch!` and `move-to-head!`, before `down!`. On a
`:no-matching-cache` miss it runs `clojure -T:dev-cache ensure-cache` in the
archive. The archive's `target` links the checkout's, so the fill lands in the
one shared cache. It then selects again, and the result carries
`:seon.operator/class-fill {:before … :after …}` and phase `class-fill-ms`. The
old JVM serves throughout. A failed fill refuses the start before anything
stops. Bound: 300 s (a measured 111 s cold build plus room). `:pins-unavailable`
does not fill, and a nuke reads no derived state. The outcome line shows
`class-fill-ms` and the class status. No unit test covers the fill: a real
fill is a 111 s two-JVM build, so root verifies it at the restart.

## Defect 3: a failed move reads like success

Reproduced from the recorded outputs `start-head-1300`, `-2000`, `-2130` and
`-1435` (the last one has no previous program).

**Cause.**
- The launch printed `REPL` and `DEPENDENCY-CLASSES` lines as each child
  started, the same lines a success prints.
- `-main` then `prn`'d the whole failure (410 KB) as one stderr line.
- The failed child's real cause was two links down, under the launch form's
  caught "SEON CORE FAULT (panic): no live cluster database…". Boot's
  `request-world` cannot record a failure that happens before the database
  exists.

**Fix.**
- Every command's stderr now starts with one line from `headline`:
  - Failure: `SEON <cmd> FAILED (exit N[, previous program runs | no JVM runs]): <message> CAUSE: <root cause> [MISSING: layers] DIAGNOSTIC: <file>`
  - Success: `SEON start --head OK <sha> total-ms … ready-ms … classes <status> <reason>`
- The cause comes from `failure-cause`: the child's `Throwable->map` `:cause`,
  then its message, then the adoption's message, then the chain.
- The whole diagnostic goes to `data/clusters/<name>/logs/operator-<cmd>-<stamp>.edn`.
- Launch observations (class selection, log, heap dumps) are now data in the
  result.
- Boot's panic message now includes the failure it could not record.

**Exit codes (`exit-code`):**

| Code | Meaning |
|---|---|
| 0 | ok |
| 1 | failed while a JVM still runs |
| 3 | nuke fell back to an older program |
| 4 | failed, and the previous program runs again |
| 5 | failed, and no JVM of this root runs (observed with `selected-processes`) |

**Replayed on the recorded outputs.** Each line names its real cause:
- `-1300`: exit 4, "A durable Malli definition contains an unnamed callable…".
- `-2130`: exit 4, "namespace 'seon.config' not found".
- `-2000`: exit 4, "Cannot classify host binding: … seon.operator…".
- `-1435`: exit 5, "Cannot classify host binding: … sci.impl.namespaces…".

Live checks:
- `bin/seon status` → `SEON status OK`, exit 0.
- An empty root → `SEON status FAILED (exit 5, no JVM runs): No live exact-root JVM…`,
  exit 5, with the file written.

**Regressions:** `a-failed-move-leads-with-one-line-naming-its-cause-and-what-runs`,
`a-successful-outcome-is-one-line-and-keeps-its-codes` and
`a-failure-diagnostic-is-written-whole-to-the-file-its-line-names`. Together
with the existing move and nuke tests: 12 tests, 63 assertions, 0 failures,
1.9 s under babashka.

## Defect 4: start --head end to end

- **Before (61d207c3e, class miss, full republication):** total 52.2 s.
  source 1.5, down 0.76, launch 42.8 (ready 30.5), adopt 6.7.
- **Before, warm reference (6ccf12a5a, hit):** total 28.7 s.
- **After:** not measured, because a lane may not restart default.
- **Expected:** the phases are unchanged apart from this commit's output. The
  next restart misses the class cache (new key) unless root fills it first.

## What root must run

1. Operator tests (babashka, about 2 s):
   `bb --config bb.edn --deps-root . --classpath script:src:resources -e "(require 'seon.operator-outcome-test 'seon.operator-move-test 'seon.operator-nuke-test) (clojure.test/run-tests 'seon.operator-outcome-test 'seon.operator-move-test 'seon.operator-nuke-test)"`
   Expect 12 tests, 0 failures.
2. The move, with the fill:
   `bin/seon start --head > tmp/orchestrator/start-head-after.out 2> tmp/orchestrator/start-head-after.err; echo "exit $?"`
   - Expect exit 0.
   - The first line of `.err` should read
     `SEON start --head OK <sha> total-ms … class-fill-ms … ready-ms … classes hit`.
   - With the fill, total-ms rises by about 111 s once. Downtime is launch-ms
     plus adopt-ms.
3. `bin/seon status 2>&1 | head -1`: expect `SEON status OK`. Then
   `bin/seon status | grep -o ':seon.operator/source-root "[^"]*"'` should name
   `data/source/<HEAD sha>`.
4. A second `bin/seon start --head` on the same HEAD should show no
   `class-fill-ms` and `classes hit`. Record its total-ms, ready-ms,
   launch-ms minus ready-ms, and `:seon.boot/layers` as the steady-state row.
5. Failure output on an empty root:
   `mkdir -p tmp/inv-boot-empty && bin/seon --root tmp/inv-boot-empty status; echo "exit $?"`
   Expect `SEON status FAILED (exit 5, no JVM runs)` and exit 5. Then
   `rm -r tmp/inv-boot-empty`.
6. After the restart: `bin/test-check default --ns seon.dev.dependency-cache-test`.
