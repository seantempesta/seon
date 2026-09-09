---
type: research
status: complete
tags: [research, operator, performance, test]
---

# Detached hook publication — 2026-09-08

The hook landed as `ef424c37c`; lock-free status landed as `e136c6af1`.
The resumed assignment transfers HTTP availability to page-feed; its
`dd7fc589a` and `77abbf43a` are not changes by this lane.

Read the AGENTS.md lane rules and turn PRD §10 end to end, and
`hook-coalesce-landing-2026-09-08.md` end to end. Read the active roadmap
README and working edge end to end. The supplied assignment owns the order.

## Dependency ledger

The hook's existing ProcessBuilder launcher (`bin/seon-hook`,
`launch-hook-worker!`) already detaches output and admits one worker under
the pending file lock. Its worker consumes the pending snapshot before
publication, preserving later edits for the next window. Removing the
editor's tail process and wait preserves that mechanism.
Babashka process dereference waits for child exit
(`reference-code/babashka-process/src/babashka/process.cljc:116`).

Status reuses `cluster-truth`/`source-observations` in
`script/seon/fresh_operator.clj`, with both JVM probing and ordinary offline
roster reads disabled. `resources/seon/operator/state.clj:1189` already
provides constant-work filesystem-space observation; `footprint` at line
1209 is the explicit recursive scan used only by verbose status. The
existing root lock path is declared at line 342; the regression uses a real
Java FileChannel lock and the live probe uses POSIX nonblocking record locks.
Malli's existing single-Var collection seam is
`reference-code/malli/src/malli/instrument.clj:48`; test restoration belongs
to `seon.test-support/preserving-instrumentation-state`.

## Measurements

Before: the supplied session measured 172 adoptions / up to 486 s lock wait
for hook-coalesce, 115 / 314 s for record-render, and 46 / 183 s for
cluster-scoped-registry. The previous landing note's five callers returned
in 28.023–29.266 s to refusal; its last one-editor refusal took 216.064273 s.

After removing the editor wait, five real hook calls launched in 1.521601 s
returned in 0.867827, 0.363804, 0.224401, 0.562547, and 0.282627 s.
All named publication `e9596525-688a-411a-8b65-44b0681f4770`.
These prove prompt queue acknowledgement, not successful adoption.
The reusable command is `python3 test/seon/dev/hook_convergence_probe.py --five-only`.

## Inherited boundaries

MCP runtime_status failed before discovery because `bin/mcp-server` omitted
src from the Babashka classpath. Recorded in
`docs/seon/issues/archive/mcp-launcher-omits-source-classpath.md` before workaround.
The existing MCP execute-tool implementation with script:src:resources
answered JVM `(+ 1 1)` as 2 in 1 ms. No alternate prepl implementation used.
At the initial probe, default was PID 45036; this lane has not stopped or restarted it.

Initial protected paths are recorded by git status; runner, turn-cut,
fixture, and rendering test edits are preserved. The hook and feedback test
were clean when acquired.


## Resumed delivery

The resumed instruction narrows this lane to the detached hook, read-only
status, and this landing note. The original refresh-suite repair and HTTP
listener work are no longer claimed as this lane's deliverables.

### Hook: committed ef424c37c

Reviewed the complete owned hook diff against HEAD. It removes the editor's
tail process and terminal wait; pending-lock admission, one detached source
worker, and snapshot consumption remain the existing owners. A worker takes
the pending set before adoption. Later edits create the next pending set;
the worker drains it after the next quiet window.

Five actual hook invocations from a pool capped at three concurrent callers
launched within **1.541462 s**. Return times were **0.333157, 0.075506,
0.080381, 0.164071, 0.078070 s**. All five named publication
`cc0568d6-960e-4acf-b40c-e95aaf5dca53`. Its single terminal result names
exactly those five paths, one worker PID **80052**, and successful convergence
to source commit **6aa0e7ff-6232-5578-a71a-c835483fc608**, digest
`ae1ed9884f0a4249a531652c5710cf2db5d38045371d5d821919c137379f0965`.
That is **one adoption for five hook calls (0.2 per call)**, rather than
five editor-blocking waits. These probes touch existing files and invoke
real edit hooks; they do not claim five changed function definitions.

The hook gate used HEAD `42d6a48b465591ca92a28cf8465fe9417f6b0587` plus
only the four owned paths. `seon.dev.edit-feedback-test` passed **9 tests,
72 assertions, zero failures/errors**. Snapshot 2 s, dependency preparation
3 s, checkouts 3 s, published base 33 s, coordinator/tests 47 s.
The regression independently awaits worker exit and checks its single
terminal refusal against an absent JVM; that refusal test is separate from
the successful live adoption above.

The same commit repairs `bin/mcp-server`'s omitted src classpath. A new real
launcher process received MCP `tools/call` for default JVM `(+ 1 1)` and
returned value **2**, **1 ms**, through the normal JSON-RPC protocol.

### Status: measured under a real init lock

`status` now reads descriptors and advertisements without taking the lifecycle
lock or repairing either. It neither creates nor rewrites root claims.
Ordinary status performs filesystem-space queries but no recursive scan or
JVM database request. It reports unqueried test evidence as UNKNOWN and directs
the caller to `status --verbose` for database evidence and a fresh disk scan.
Verbose status remains read-only and does not take the lifecycle lock.

`python3 test/seon/dev/status_lock_probe.py` launched real
`bin/seon init --dev default --changed bin/seon-hook`, PID **81443**.
The lock was acquired at **2026-09-09T05:11:23.891Z**. During that operation,
status returned exit 0 in **0.239742 s**. Kernel nonblocking lock probes
confirmed the lifecycle lock held both before and after status; the holder
file bytes were identical and named that exact init PID. A second independent
measurement under the same holder was **0.203006 s**. Uncontended status was
**0.303969 s**.

The init exited **0** and converged to
`6aa0ea63-8166-5b3b-b2ef-478880af03a8`, digest
`6f757dee5d1ca6ac60f9334dd2235cdd9408f8886c28fa34aa814350343d70a0`.
Default stayed PID **22932**, port **7994** throughout these resumed probes.
No stop, refork, restart, or listener rebind was performed by this lane.

The real operator regression holds the selected root's kernel lock while
launching `bin/seon --root ROOT status`; it asserts <2 s, the recorded process
appears, the lock remains valid, root claim bytes are unchanged, and unqueried
test evidence is not called healthy.

The initial fast operator run exposed the already-recorded isolated-init
problem: dependency preparation searched for `deps.edn` in a data-only root.
Its result was 36 tests / 201 assertions, 36 failures / 9 errors; this was
an iteration verdict, not a green status gate. The fix stays in the operator:
pass the invoking checkout (`repository-root`) to dependency preparation.
The subprocess test classpath likewise now includes src, matching bin/seon.
The stopped-cluster test asks for --verbose when it needs offline branch facts.

### Evidence artifacts and boundaries

Exact hook acknowledgements, terminal result, status output and lock-holder
bytes are in [hook-async-measurements-2026-09-08.json](hook-async-measurements-2026-09-08.json).
Reproduction scripts are `test/seon/dev/hook_convergence_probe.py` and
`test/seon/dev/status_lock_probe.py`.

The resumed assignment explicitly protects `bin/test`, `bin/test-fast`,
`src/seon/test/runner.clj`, and `src/seon/fn.clj`. None was edited, committed,
or restored by this lane. AGENTS.md and the testing skill also held unrelated
edits and were preserved. AGENTS.md's status-footprint wording now needs the
owner to say `status --verbose`; the protected file was not changed here.


### Platform checkpoint

The first platform attempt, `run.0eCRor`, failed before assertions: the
launcher prepared two worker checkouts while the HEAD runner independently
selected nine. `pool-3` could not load `seon/test/runner`. This reproduces
`docs/seon/issues/platform-worker-count-exceeds-prepared-checkouts.md`;
no protected runner source was modified.

The retry explicitly aligned both existing controls:

```
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Dseon.test.worker-count=2' SEON_TEST_WORKERS=2 bin/test --paths script/seon/fresh_operator.clj test/seon/dev/fresh_operator_test.clj test/seon/dev/status_lock_probe.py --platform
```

`run.Qh9rU7` passed **82 tests / 486 assertions, zero failures/errors**.
The coordinator reported **workers=2**; coordinator/tests took **76 s**.
Its successful root was removed by the runner. This snapshot includes the
final status behavior and the stopped-cluster --verbose expectation, plus
updated help text. No --all or --full run was made.

A separate read-only `status --verbose` succeeded in **13.695944 s**, showing
**33.54 GiB** and full namespace test evidence. That scan is intentionally
outside the <2 s descriptor-only status contract.

### Files touched by this lane

- `bin/seon-hook`: detached queue acknowledgement.
- `bin/mcp-server`: source classpath repair.
- `test/seon/dev/edit_feedback_test.clj`: asynchronous terminal verification.
- `test/seon/dev/hook_convergence_probe.py`: three-caller live probe.
- `script/seon/fresh_operator.clj`: read-only status and checkout-scoped dependency preparation.
- `test/seon/dev/fresh_operator_test.clj`: real lock regression and operator fixture corrections.
- `test/seon/dev/status_lock_probe.py`: real init/status measurement.
- `docs/seon/issues/archive/seon-status-waits-on-the-lifecycle-lock.md`: resolved issue, moved from the open directory.
- `docs/seon/issues/archive/mcp-launcher-omits-source-classpath.md`: resolved launcher issue.
- `docs/seon/issues/platform-worker-count-exceeds-prepared-checkouts.md`: appended this lane's reproduced boundary; another lane subsequently committed the shared note.
- This landing note and `hook-async-measurements-2026-09-08.json`: durable measured evidence.

HTTP availability remains page-feed's delivered proof: the orchestrator
reported **900/900 HTTP 200** through adoption at `dd7fc589a`, with adoption
cache invalidation at `77abbf43a`. This lane did not repeat or claim that
loop. A resumed read-only request to default returned HTTP 200 in **0.464247 s**.

### Operator gate iteration

`run.J9nTWl` completed **36 tests / 235 assertions, 2 failures / 1 error**
in **972 s**. Fresh-worker confirmation reproduced both failing tests:

- `populated-stopped-cluster-reopens-after-full-operator-restart` expected
  stopped database branches from ordinary status (lines 1462/1464). Its
  final expectation uses `status --verbose`.
- `add-refreshes-a-genuinely-stale-wrapper-before-current-start` threw
  `:malli.core/register-function-schema` at line 1580 while recollecting
  every `seon.cluster` function during cleanup. The regression now uses
  Malli's existing `-collect!` on its one test subject and the canonical
  `preserving-instrumentation-state` fixture, and deletes its scratch root.

The real lock regression completed in **485 ms** in that gate. These two
failures are owned test corrections, not attributed to another lane.
The final complete operator namespace gate includes both corrections.

After recording these results and confirming no process command referenced
either retained root, this lane deleted its own failed `run.J9nTWl` and
`run.0eCRor` roots without following symlinks.

### Final status gate and landing

`e136c6af1` commits the status implementation, real kernel-lock regression,
operator fixture corrections, live reproduction script, and resolved issue.
Its final gate was:

```
JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=6 -Dseon.test.worker-count=3' SEON_TEST_WORKERS=3 bin/test --paths script/seon/fresh_operator.clj test/seon/dev/fresh_operator_test.clj test/seon/dev/status_lock_probe.py -- seon.dev.fresh-operator-test
```

`run.Kb6Qft` passed **36 tests / 239 assertions, zero failures/errors** in
**770 s** of coordinator/tests. The HEAD runner selected one worker for
the explicit namespace. Both previously failing tests passed. The successful
root was removed by the runner; this lane's failed roots and copied scratch
measurement outputs have also been removed.

The landing note is a separate documentation commit. All three deliverables
use explicit path-limited commits; no protected runner or function-owner
changes were included. The main default cluster was never stopped, reforked,
or restarted by this lane.

The final documentation snapshot at status commit `e136c6af1` also passed
`bin/test --paths <this note, measurements JSON, resolved MCP issue> --platform`:
**82 tests / 486 assertions, zero failures/errors**, **2 workers**, **50 s**
of coordinator/tests, root `run.1rXuOc` (removed by the runner). It used
`JAVA_TOOL_OPTIONS='-XX:ActiveProcessorCount=4 -Dseon.test.worker-count=2'`
and `SEON_TEST_WORKERS=2`. This is the final platform verdict before reporting.
