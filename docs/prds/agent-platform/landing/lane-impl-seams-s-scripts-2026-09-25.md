---
type: evidence
status: 7 code commits (81f78faa3..35f8c4131); shell and dependency-cache reds classified and fixed, rerun awaits root
---
# Lane impl-seams-s-scripts-opus: lost-error seams cut S-scripts (2026-09-25)

Design: `docs/research/agent-platform/lost-error-seams-design-2026-09-25.md` (round 3, `78b922ea4`), cut
S-scripts, correction 7 (bootstrap and operator scripts stay on JDK primitives). Each file gets one small local owner.
No Seon runtime import was added to a script.

## Broken or unproved first

- **Run 015 at 23:50: shell.jvm-test had 6 fails. All were CAUSED by `fc5ea0890`.** `task-outcomes` and
  `finish-evidence` declared their tasks as `[:vector :map]`, but each task is a `[member task]` pair. The armed
  contract refused the test row, and it would have refused production shell runs too.
  - Fixed in `1a0c359e4` with `[:vector [:tuple :qualified-keyword :map]]`. At default, the new schema accepts the
    real vector and the old one refuses it.
  - Lesson: a scratch copy of a namespace doesn't arm its contracts, so contract shapes need their own validation
    probe.
- **Run 023: dependency-cache-test had 3 pass, 3 fail and 2 errors. All were PRE-EXISTING, not `e98de259c`.**
  - The test looked up `dependency-pins` and `git-dependency-pins` in `dev-cache`. Those seams moved to
    `seon.dev.dependency-digest` in `4133085b3`, a retired assumption.
  - `pins-unavailable` nested a copied error map under `:seon.error/data`, where the test and the flat-error law
    expect top-level `member`, `expected` and `offending`.
  - Both fixed in `35f8c4131` and probed with bb in a scratch root: the git query is silent (nil), and the refusal is
    flat with `recorded-pins-present` false.
- **Out of scope:** `dev_cache.clj:25` keeps its own copy of `dependency-pins-file` beside the digest's. That is a
  duplicate, and `dev_cache.clj` is not this lane's file.

- **Root's run 018 at `6ab6f5fb`: shell.jvm-test had 12 pass and 5 fail. All 5 were CAUSED, and all came from my new
  row.** The armed `finish-evidence` contract refused the row's nil connection. The fix, `fc5ea0890`:
  - Split out `task-outcomes`, which reads the tasks without the database world.
  - `finish-evidence` now only describes the outputs.
  - Later timeouts move to `::later-timeouts` under `:seon.error/data`, because that schema is `:map`.
  - Added a positive row: after reaping, a broken stdin pipe gives `[nil nil nil]`.

  Checked at default's REPL with a scratch copy: every case answers as predicted, the timeout validates against the
  installed `:seon.await/timeout-error`, and the new contract compiles against the projection registry. No reds
  were PRE-EXISTING or RETIRED ASSUMPTIONS.

- **Not run by this lane (LOAD RULE):** `seon.shell.jvm-test` and the dependency-cache test. Root runs them (commands
  below). The new `every-capture-outcome-is-read-and-none-is-lost` row was run only as a scratch-namespace copy (see
  the evidence section).
- **`seon.shell.jvm` is not loaded in default** (`(find-ns 'seon.shell.jvm)` → nil at 21:55Z). So the hook can't have
  adopted it. The test request loads it.
- **Default shows `cluster-state :degraded`** in MCP replies (`branch-errors`: "No live connection holds this branch").
  `bin/seon status` showed `:faulted` with 21 open faults before this lane's first probe. This lane did not cause
  it.
- **B34 was probed, not converted.** `'seon.dev.dependency-digest/dependency-pins` has exactly one producer,
  `pins-unavailable` (`dependency_digest.clj:106`), so `operator.clj`'s catch at `:488` only catches that case. The
  new digest throws carry no operation and still propagate. The fallback still drops the refusal's content: it keeps
  `::pins-unavailable` rather than the refusal map. Converting it changes the miss map's `input-digest` shape, so that
  is left for the owner.
- **G07 is bounded by construction, not by a timed deref.** `@census` now runs on every exit (A11). Its body is two
  `command!` calls, each bounded at 30 s. After G08, each call either proves exit or refuses naming a survivor. So
  the census's exit is bounded by those calls, and no separate timed deref was added.
- **B14 discriminates by message.** The JDK has no subclass for a closed process pipe. The two messages were probed
  in default's JVM: "Stream closed" (writing after exit) and "Broken pipe" (killed mid-write). No regex is used, only
  set membership.
- **`force-stop!` (B25) had no live probe.** It needs a sibling JVM, and this lane may not boot one.
- **G13:** the move test's cleanup now asserts the holder's exit (`is`). It still deletes the base after a failed
  assertion. The failure is loud, not silent.

## What changed, by instance

| Instances | File | Owner / change |
|---|---|---|
| A08, G04, B14 | `src/seon/shell/jvm.clj` | `finish-evidence` reads every task (stdout, stderr, stdin) against **one** `seon.await` deadline (`await.clj:115-118`, `:seon.await/deadline-nanos`). The first failure is thrown with later failures and timeouts suppressed. A timeout carries the later ones in `:seon.error/data`. The interrupt path reads the reaped tree's tasks too. After reaping, only a closed-pipe IOException on stdin counts as the end of input. |
| A12, C05, G11 | `script/seon/dev/dependency_digest.clj` | The output future is joined after the child's proved exit. Three cases throw, each naming its event: a survivor of forced termination, an exceeded bound, and output that never ends. The `::unavailable` sentinel no longer falls through as "no pins". |
| A05, A06, G06 | `script/seon/operator.clj` | New `joined`: closes the resource the losing `CompletableFuture` is blocked on, then `.get`s the future within a bound and puts its value or failure into the refusal (`:seon.operator/accept`, `:seon.operator/read`). An accepted-but-lost socket is closed. A race timeout is now data (`::timeout`), not a raw `TimeoutException`. The child is not killed. |
| G08, G09, G10 | `script/seon/operator.clj` | New `reap-child!`: `.destroyForcibly`, then a bounded `.waitFor`, refusing with the pid if the child survives. Used by `command!`, `same-program?` and the OS opener. `command!` deletes its output file only once the child has exited. |
| A11 | `script/seon/operator.clj` | The census is read on every exit, and its failure is suppressed onto extraction's. |
| B25 | `script/seon/operator.clj` | `force-stop!` keeps the reply failure (`:seon.operator/reply-failure`, via `diagnostic`) beside the observed exit. If the exit never comes, the failure is thrown with the timeout suppressed. |
| A13, G13 | `script/seon/operator_move_test.clj` | Exit is proved (forced if needed) before `.exitValue`. The output join is bounded. The holder's exit is asserted. |

Deleted per-site copies:
- the nested stdout-then-stderr early return in `finish-evidence` (−9);
- the duplicated `:exited`/interrupted evidence-and-input blocks in `execute` (−22), which included `(catch Throwable _ nil)`;
- the digest's `finally` destroy with its ignored wait result, and the `::unavailable` fall-through (−6);
- three bare `.destroyForcibly` sites in the operator;
- the `::disconnected` sentinel;
- the move test's unbounded `@out`.

## Size (physical lines, `git diff --numstat`)

| File | + | − | net |
|---|---|---|---|
| src/seon/shell/jvm.clj | 79 | 63 | +16 |
| script/seon/dev/dependency_digest.clj | 13 | 15 | −2 |
| script/seon/operator.clj | 48 | 14 | +34 |
| **source total** | 140 | 92 | **+48** (design estimated −10/+47) |
| test/seon/shell/jvm_test.clj | 28 | 3 | +25 |
| script/seon/operator_move_test.clj | 8 | 4 | +4 |

Commits: `81f78faa3` (shell), `e98de259c` (digest), `298753fc2` (operator), `49669dc31` (move test).

## Evidence (forms in `tmp/impl-seams-s-scripts-opus/`)

- **JVM pipe messages** (default, scratch namespace, 115 ms): writing to an exited child's stdin gives
  `IOException "Stream closed"`; a child killed mid-write gives `"Broken pipe"`.
- **`finish-evidence`**: the new source, loaded as scratch namespace `tmp.impl-seams-shell`, 112 ms for all cases
  with a 50 ms deadline.
  - A throwing stdout plus a never-ending stderr throws "stdout failed", with stderr's `time-limit-ms` timeout
    suppressed.
  - Two never-ending readers: the stdout timeout carries the stderr timeout. The shared deadline makes the second
    read immediate.
  - A stdin pipe failure without reaping is returned. A blob failure after reaping is thrown.
  - The scratch namespaces were removed afterwards (checked: none left).
- **Digest** (bb, `probe_digest.clj`, a copy with the command and bound as parameters, bound 300 ms):

  | Command | Result | Time |
  |---|---|---|
  | `echo x` | `"x\n"` | 3 ms |
  | `sleep 30` | "exceeded its execution bound" | 307 ms |
  | `sh -c "sleep 3 & echo x"` | "output never ended after its exit" | 1014 ms |

  The live `dependency-pins "."` returns 2311 chars, and `configuration-digest` still computes on bb's `script`
  classpath.
- **Operator owners** (bb, `probe_operator.clj`, through the loaded `seon.operator`):
  1. child exit wins the accept race: the accept is joined, `SocketException "Socket closed"` is consumed, and the
     future is done;
  2. an accepted-but-lost socket is returned and closed;
  3. a blocked terminal `readLine` ends when its socket is closed, its failure is consumed, and a double close is
     safe;
  4. a task that outlives its closed resource refuses with its bound;
  5. `reap-child!` proves `sleep 30` exited;
  6. `command! ["sleep" "30"]` at 200 ms refuses in 211 ms and leaves no output file behind.
- **Census shape** (bb): extraction failure `"extract failed"` has the suppressed `"census failed"`; the census is
  realized.
- **Babashka load**: `bb --config bb.edn --deps-root . --classpath script:src:resources -e "(require 'seon.operator)"`
  loads, with the same 20 kondo warnings as HEAD and no new ones. The move test under bb: 6 tests, 35 assertions,
  0 failures.
- **Edit hook**: the PostToolUse `bin/seon-hook` ran on every edit and caught one syntax slip mid-edit, which was
  then repaired.

## Root runs

- `bin/test-check default --ns seon.shell.jvm-test`
- `bin/test-check default --ns seon.dev.dependency-cache-test`
- `bin/test-check default --ns seon.await-owner-census-test` (`task-result` is still the declared await caller)
- Operator on its own classpath:
  `bb --config bb.edn --deps-root . --classpath script:src:resources -e "(require 'seon.operator-move-test) (clojure.test/run-tests 'seon.operator-move-test)"`,
  then `bin/seon status` and one `bin/seon start` or `start --head` at a quiet window to exercise the boot race's
  success path.
