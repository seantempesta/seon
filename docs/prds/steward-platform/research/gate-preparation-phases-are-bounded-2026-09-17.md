---
type: research
status: landed
created: 2026-09-17
tags: [research, testing, gate, bounded-execution, tooling]
---

# The gate's preparation phases are bounded and loud

Read AGENTS.md §0–§5 end to end before this slice; §2.3 is the law it
implements: every execution surface carries its declared bound at the seam
that admits the work, and *a bound firing is itself a bug report naming what
never arrived* — never a silent retry, never a hang.

## The observable this stands in for

On the night of 2026-09-16/17 every `bin/test` invocation refused inside its
`dependency-cache-and-classpath` phase for roughly an hour and nobody saw it.
The refusal exited non-zero into a file; four further gates sat printing
`phase=snapshot` and then waited for a test slot; the slot wait's own 1800 s
bound made a wedged phase read as an ordinary queue. The owner: *"Are we not
failing loud in development?"*

Three absences produced one invisible hour:

1. The test phase had a bound (the suite liveness watchdog,
   `SEON_TEST_SILENCE_SECONDS` ↔ `seon.test.runner/silence-seconds`). The five
   PREPARATION phases had none at all.
2. A failing phase's first refusal line went only into its log.
3. The slot wait announced itself once, then blocked silently for up to
   thirty minutes.

## What landed

`bin/test` (`bin/test:44-79`) declares one bound per preparation phase as a
named variable beside the existing declared bounds, each with its env override
and a comment naming the measured observable it stands in for:

| phase | variable | default | observable |
|---|---|---|---|
| `snapshot` | `SEON_TEST_SNAPSHOT_SECONDS` | 300 | 3–6 s warm, 21 s with five gates on the disk |
| `test-slot` | `SEON_TEST_SLOT_PHASE_SECONDS` | `SEON_TEST_SLOT_WAIT_SECONDS`+60 | backstop for a slot loop that stops looping |
| `dependency-cache-and-classpath` | `SEON_TEST_DEPENDENCY_CACHE_SECONDS` | 300 | 3–6 s warm, 57 s for one cold immutable rebuild |
| `worker-checkouts` | `SEON_TEST_WORKER_CHECKOUTS_SECONDS` | 300 | 2–5 s |
| `published-base` | `SEON_TEST_PUBLISHED_BASE_SECONDS` | 600 | 0 s on an exact cache hit, 107–131 s cold, 171 s cold under concurrent gates |
| `coordinator-and-tests` | `SEON_TEST_COORDINATOR_PHASE_SECONDS` | 0 | bound owned elsewhere: the suite liveness watchdog |

A bound of `0` declares that the phase's bound is owned elsewhere, so the
absence of a watchdog is a stated decision rather than an oversight.

`begin_phase NAME` arms a watchdog; `record_phase NAME` disarms it. On firing
the watchdog writes one stderr line naming the phase, the elapsed seconds, the
bound and where that phase's own output is, appends
`phase=NAME exceeded-bound-seconds=N` to the run ledger, drops a marker, then
terminates the launcher's descendants before signalling the launcher itself —
a launcher blocked in a foreground child runs no pending trap until that child
returns. The trap sees the marker and exits **70** with
`retained-reason=phase-bound-exceeded` in the ledger.

**A bound is for a child that never returns, never for one that already
failed.** Owner, 2026-09-17: *"waiting minutes to find out a problem we should
know immediately is a bug."* Every phase reports the instant its child returns
non-zero: the dependency-cache tool's refusal is caught by `if ! dev_cache_output=$(…)`,
the published-base child by `await_runner`'s status, and everything else by an
`ERR` trap (`bin/test:527-546`) that names the phase in flight, because
`set -euo pipefail` otherwise ends the gate with whatever the failing command
happened to print and no phase named at all. The trap returns without acting
wherever errexit is deliberately off, read from `$-` rather than from a list of
places. Measured with a fake `clojure` that refuses: **3 s** from launch to the
named refusal, and with a `rmdir` that cannot succeed: **0 s**, naming
`PHASE dependency-cache-and-classpath FAILED: a command in this phase exited 1
at bin/test line 773`.

`announce_phase_failure` echoes a refusing phase's first lines (up to five) to
the caller's stderr as `bin/test: PHASE <name> FAILED: <line>`, followed by the
phase's output path, and records `phase=<name> failed=true`. It is wired to
every refusal in `dependency-cache-and-classpath`, `test-slot` and
`published-base`.

`bin/_test-slot` keeps its 1800 s bound and now announces on entry and then
every `SEON_TEST_SLOT_ANNOUNCE_SECONDS` (default 60) on **stderr**, naming each
slot's holder pid, that holder's own elapsed time from `ps -o etime=`, and its
run root.

## Two bash defects found and fixed at the root

**No `BASHPID`.** `bin/test`'s shebang is `/usr/bin/env bash`; on this machine
that resolves to `/bin/bash`, *GNU bash 3.2.57(1)-release (arm64-apple-darwin25)*,
which predates `BASHPID`. The watchdog needs its own pid so that
`terminate_descendants` does not kill the watchdog before it can signal the
launcher. The first attempt published the pid through a file in the run root;
that both raced and polluted the snapshot-difference listing with a stray
`A .phase-watchdog`. The root fix is portable and needs no file:

```bash
watchdog=$(exec sh -c 'echo $PPID')
```

A command substitution that `exec`s its command makes that child's `PPID` the
enclosing subshell's own pid. Measured on this bash 3.2: parent-observed `$!`
and the subshell's self-read agree exactly, where the same expression *without*
`exec` is off by one fork.

**An orphaned timer holds the caller's pipe open.** The watchdog first ran its
timer as a foreground `sleep "$bound"`. Disarming the watchdog kills the
subshell but not that foreground child, which is reparented to init and keeps
the launcher's inherited stdout/stderr **pipe** open for the rest of the bound.
A reader slurping that pipe — exactly what the regression's `ProcessBuilder`
does — then blocks after the gate has already exited. This is the disease
AGENTS.md §2.3 names: the watchdog that was supposed to make a hang impossible
manufactured one. It cost a 300 s `SUITE LIVENESS BUG` kill of the whole fast
gate, whose dump read `descendants: none` with `main` blocked in
`Process$PipeInputStream.read` — the pipe was held by a process that was no
longer anyone's descendant. A `ps -eo pid,ppid` sweep of the machine found
**23** orphaned `sleep 1860` / `sleep 300` processes with `ppid=1`, one per
armed phase across every gate run that night, including other lanes' gates
(a bare `bin/test-fast` snapshots the working tree's `bin/test`).

The root fix makes the timer a background child that is waited on, so `wait` is
interruptible by a trap and ending the watchdog ends its timer:

```bash
trap 'kill $timer_pid 2>/dev/null; exit 0' TERM INT HUP
sleep "$bound" &
timer_pid=$!
wait "$timer_pid" || exit 0
```

After the fix the same fixture closes its pipe in **2 s** instead of never, and
a `ppid=1` sleep sweep comes back empty.

## One foreign red fixed in the fixture it broke

Every launcher fixture in `test/seon/test_runner_test.clj` builds a checkout
that copies `src/seon/test/cache.clj` and hands it to babashka. `5df50a193`
gave `seon.test.cache` a `seon.test.selection` require; the two checkout
scripts still copied only `cache.clj`, so the published-base child of every
launcher fixture died at load with
`Could not locate seon/test/selection.clj on classpath` — which is exactly the
class this slice is about, previously visible only as `exited 1`. Both scripts
now copy `selection.clj` (`test/seon/test_runner_test.clj:1171` and `:2163`).

## The regression

`test/seon/test_runner_test.clj`,
`a-preparation-phase-that-outruns-its-declared-bound-fails-loudly`: a launcher
fixture checkout whose `PATH` supplies a fake `clojure` that sleeps 600 s on
`-T:dev-cache`, run with `SEON_TEST_DEPENDENCY_CACHE_SECONDS=1`. It asserts the
process ends within the declared event backstop, exits non-zero, prints
`PHASE dependency-cache-and-classpath EXCEEDED ITS BOUND` with `bound-seconds=1`
and an `elapsed-seconds=` figure, names `dependency-cache-and-classpath.log`,
and leaves `phase=dependency-cache-and-classpath exceeded-bound-seconds=1` in
the retained run's `test-run.txt`. It is a class regression: the wedged phase is
a stand-in for any preparation phase that stops returning.

## Verification boundary

- `bin/test-fast --paths bin/test bin/_test-slot test/seon/test_runner_test.clj -- seon.test-runner-test`
  — the new regression runs green in ~7 s.
- One manual end-to-end proof of a real gate, on the real `/bin/bash` 3.2:

  ```
  SEON_TEST_PUBLISHED_BASE_SECONDS=1 bin/test --paths src/seon/schedule.clj -- seon.db-test
  ```

  exit **70**, stderr verbatim:

  ```
  bin/test: PHASE published-base EXCEEDED ITS BOUND: elapsed-seconds=1 bound-seconds=1 output=/Users/sean/src/seon/tmp/test-runs/run.X6yv4R/test-run.txt
  bin/test: preparation bound fired; retained isolated operator root /Users/sean/src/seon/tmp/test-runs/run.X6yv4R
  ```

  and, from the same run's slot wait, the new progress announcement:

  ```
  bin/test: all 3 test JVM slots busy; waiting up to 1800s (SEON_TEST_SLOTS / SEON_TEST_SLOT_WAIT_SECONDS)
  bin/test:   slot-1 pid=87247 elapsed=04:52 run-root=/Users/sean/src/seon
  bin/test:   slot-2 pid=89321 elapsed=01:09 run-root=/Users/sean/src/seon/tmp/test-runs/run.7TZT3l
  bin/test:   slot-3 pid=87309 elapsed=04:39 run-root=/Users/sean/src/seon/tmp/test-runs/run.Az987l
  ```

Two reds remain in that namespace, neither from this slice:

- `the-agent-fork-callable-returns-the-committed-projection` —
  `seon.db/transact!` refuses the test row for a missing
  `:seon.schema.admission/source`, the concurrently landing required-key work.
- `concurrent-bin-test-invocations-both-reach-their-tallies` — the test starts
  two complete `bin/test` runs and emits no reporter progress while it waits,
  so the coordinator's 300 s suite silence watchdog fires under load and kills
  the run at exit 124 with 31 of 33 tests already done. The dump shows `main`
  parked in `clojure.core/deref` on the test's own future: no deadlock, no
  leaked descriptor, and a `ppid=1` sleep sweep is empty. Filed as
  [a test that drives two real gates reports no progress to the silence bound](../../../seon/issues/a-test-that-drives-two-real-gates-reports-no-progress-to-the-silence-bound.md).

**Not proven here.** No cold `bin/test` gate of this namespace was run: the
slice's one allowed real `bin/test` invocation was spent on the manual proof
above. `seon.test-runner-test/the-agent-fork-callable-returns-the-committed-projection`
is red at this HEAD for a reason outside this slice — `seon.db/transact!`
refuses the test row for a missing `:seon.schema.admission/source`, which is
the concurrently landing required-key work, not a preparation-phase fact.
`src/seon/test/runner.clj` and `test/seon/test/runner_test.clj` were not
touched.


## Addendum, 2026-09-17: cold gate batch 115 attribution

Batch 115 B (`tmp/orchestrator/gate-results/batch-115.log`, retained root
`tmp/test-runs/run.Z4ufFh`, HEAD `defd915cd`) named two reds in
`seon.test-runner-test` as possibly new since `cde8b17fa`. Neither is.

**`gate-completions-travel-as-a-file-not-as-code`** — `(= 2000 (count
(commit-results! …)))` returned `9`, immediately after
`:datahike/write-rejected {:kind :transaction/validation-rejected}`. `9` is the
key count of the `:seon.error/value` the refusal returns in place of the
recorded facts. Two sibling tests in the same namespace fail on the identical
refusal, quoting the missing key verbatim:
`result-recording-is-total-under-concurrent-test-retraction` (lines 900, 902,
904) and `the-agent-fork-callable-returns-the-committed-projection` (1037,
1052). The root is that `seon.test.runner/record-tx` creates a test row with
no `:seon.schema.admission/source` while the final-report validator now
rebuilds the merged entity from the resulting datoms and requires it. Filed as
[the recorder creates a test row the write-grammar validator refuses](../../../seon/issues/the-recorder-creates-a-test-row-the-write-grammar-validator-refuses.md);
`src/seon/test/runner.clj` belongs to the write-admission lane and is untouched
here.

Attribution against this slice, measured rather than asserted:

- The key has been REQUIRED on the stored test entity since `9648aed33`
  (2026-08-12); it is required at `cde8b17fa^` and at HEAD.
- The validator commits that made it bite — `35c5d2fa8`
  (2026-09-16T14:10:17-06:00) and `b1508dc8a` (14:42:13) — are both ancestors
  of `cde8b17fa` (19:41:16), landing five hours earlier.
- `cde8b17fa`'s only changes to `test/seon/test_runner_test.clj` are one
  independent `deftest` and two `cp` lines inside fixture checkout shell
  scripts. None of the three failing tests starts a process or loads `bin/test`
  or `bin/_test-slot`.

**`concurrent-bin-test-invocations-both-reach-their-tallies`** — confirmed to
be the filed silence-bound class and nothing else. The failing assertion is
`(every? true? completed)` → `[false false]`
(`test/seon/test_runner_test.clj:1826`): neither child gate finished inside the
test's own `event-backstop-seconds`. Both children's captured output ends at
`bin/test: SOURCE program rows started` — mid published-base, with
`PHASE snapshot`, `PHASE test-slot`, `PHASE dependency-cache-and-classpath` and
`PHASE worker-checkouts` all recorded normally, **no** `EXCEEDED ITS BOUND` and
**no** `PHASE … FAILED`. No preparation bound fired; the children were simply
still publishing.

That test also HID the other two reds from the fast loop: it is declared before
them, and in the 2026-09-17 fast run it consumed the whole 300 s suite silence
bound and the coordinator killed the JVM after 32 of 46 tests, so
`gate-completions-travel-as-a-file-not-as-code` was never reached. A test that
blocks silently does not only fail — it conceals every test behind it, which is
the reason its issue is worth fixing rather than tolerating.
