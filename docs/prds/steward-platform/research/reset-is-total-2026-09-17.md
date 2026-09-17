---
type: research
status: active
tags: [operator, reset, bounded-execution, deletion]
---

# Reset refuses known source failures before waiting or destroying

Read end to end: AGENTS.md sections 0–6, `bin/seon`,
`script/seon/fresh_operator.clj`, and `src/seon/operator.clj`. Read the
`ccccea806` and `9fa1f101d` deletion admission changes, the active roadmap,
and the data-oriented-clojure, repl, and clojure-testing skills.

## Incident evidence

The supplied logs are under `tmp/orchestrator/refork/`. The second reset
log was read only after its final line was `180M data/store`.

- `restart-2026-09-17T0045Z.log`: installed `:db/noHistory` incompatibility
  refused reopening. Refork is the correct recovery.
- `refork-2026-09-17T0100Z.log`: publication/refork evidence.
- `reset-2026-09-17T0120Z.log`: lifecycle contention, then stale advertisement
  repair and cleanup failure when the cleanup JVM loaded the broken program.
- `reset-2026-09-17T0135Z.log`: the subsequent recovery required separate
  start and adoption commands; its final store size was 180M.

`bin/seon` already uses `exec bb` under `set -euo pipefail`; the operator
already called `System/exit 1` on an exception. The caller's pipeline status
was not evidence of the launcher's status. The new subprocess regression
asserts the launcher's own exit code directly.

## Dependency ledger and implementation

The lifecycle seam is `FileChannel.tryLock`, with a sibling `.holder.edn`
record. It is not a mkdir/PID-file mutex. JDK 26.0.1 `lib/src.zip`,
`java.base/java/nio/channels/FileLock.java:42–45`, states that channel close
or JVM termination releases the lock; lines 101–105 explain why opening and
closing a second channel can release a process's locks on some systems.
The existing in-process acquisition slot remains in place.

`resources/seon/operator/state.clj` reads the holder immediately, reports
PID, process start, command, acquisition time and identity liveness once,
and limits acquisition to the holder's remaining publication allowance.
The allowance comes from `.claude/seon-hook.edn`'s
`:current-source :timeout-seconds` (180 seconds in this snapshot). A dead
holder record is replaced only after actual kernel acquisition. A dead
record with a still-held kernel lock is an immediate explicit inconsistency;
unlinking that inode would create two independent locks and is forbidden.
`ProcessHandle.isAlive` is now part of process-start observation.

The destructive admission introduced by `ccccea806` moves intact to
`seon.fs/admit-destructive-path!`. The store owner delegates to it. The
existing cleanup body moves to the BB/JVM-shared operator state owner; the
JVM operator delegates to it, and the CLI invokes it directly while holding
lifecycle custody and an exclusive store-lock probe. All target admissions
precede deletion. The existing no-follow filesystem walker is unchanged.
No cleanup JVM loads `seon.db` or the cluster program. `down` verifies the
store lock without launching an offline program just to read a roster.

The launcher runs native clj-kondo syntax checking over changed and untracked
source/config inputs before lifecycle acquisition, with one shared 5,000 ms
subprocess budget. It repeats the check after acquisition because the tree
may have changed during contention. Argument/config refusals also precede
acquisition. This is the syntactic preflight explicitly allowed by the
assignment, not a claim that static syntax checking proves successful JVM
compilation or publication. The native parser is used directly: vendored
`reference-code/clj-kondo/src/clj_kondo/impl/core.clj:440–565` shows that
`--skip-lint` without analysis skips the parse work we need.

Reset now sequences down, destroy, republish, refork, start, and adoption.
Each phase creates an operation log, preserves failure data, names its log
in the failure line, and prevents later phases from running. A refusal
before/during destruction reports remaining store bytes. Census and stale
advertisement repair include process/log evidence. Offline publication and
roster reads use the existing dependency class cache; source publication
uses the configured publication bound.

## Verification

Implementation and destructive work are isolated in `tmp/reset-total-wt`
from HEAD `149ea07d1`. No operator command targeted the main default cluster, and protected source
files were not edited. One early edit-tool call automatically queued hook
publication `2d5ae833-8b29-4ede-91bd-fbea94e46f9e` for a path inside this
worktree. Its result reports exit 124 and no publication result. Subsequent
edits used shell writes; no default adoption is claimed. Main AGENTS.md has foreign uncommitted edits;
its Operating command example needs the reset description extended with
“start, adopt” when that authority is reconciled. The launcher help already
states the resulting behavior.

The first focused `bin/test-fast seon.dev.fresh-operator-reset-test
seon.fs-test` passed 7 tests / 83 assertions. Its real launcher syntax
refusals, while a lifecycle lock was held, measured reset 0.936 s, start
1.262 s, init 0.988 s against the 5 s assertion. The store sentinel survived
each refusal. Lock and phase regressions also assert the declared bound;
phase injection proves no later action runs after any of the six failures.
The cleanup regression plants an outside symlink sentinel.

Raw development evidence: `tmp/reset-total/`. A preliminary scratch reset
correctly refused at republish because this lane edited the source during
analysis; it exited 1 and did not refork/start/adopt. That is not attributed
to another lane. The frozen run's syntax checks measured 461 ms before
acquisition and 480 ms after acquisition. Final results are recorded below.

The broader exploratory fast invocation reached the known cold canonical
fixture preparation boundary documented in
[the cold-fixture issue](../../../seon/issues/the-cold-fixture-base-outruns-the-liveness-silence-backstop.md);
its incomplete run is not claimed green. No `bin/test` or platform gate was
run: the assignment explicitly says “Never bin/test.” Fast iteration is not
cold-gate evidence.

## Final results

The frozen isolated reset exited **1** at republish's **180,000 ms** child
deadline. The result recorded `:seon.operator.subprocess/reaped? true`.
No refork, start, or adoption phase ran. `down --force` then exited **0**,
reported no recorded JVMs, and verified the store lock free. The remaining
partial store had **4,242,024 regular file bytes**; phase logs were preserved
under `tmp/reset-total/drill-phase-logs/`, then the scratch root was deleted.

This is a verified loud refusal, **not** a successful live/adopted reset.
The publication cost remains with the existing
[publication-bound issue](../../../seon/issues/concurrent-publications-serialize-past-the-hook-bound.md).
MCP answered for the isolated root with an empty cluster list. Consequently,
the requested live `seon.fn/tests-reaching` query could not be run there;
no main-root query was substituted. The existing operator test owner and
filesystem tests were used for the bounded regressions.

The final focused command was `bin/test-fast
seon.dev.fresh-operator-reset-test seon.fs-test`: **9 tests / 97 assertions,
0 failures / 0 errors**, exit 0. It armed 1,102 registered contracts in panic
mode. Final reset/start/init syntax refusals measured **1.716 / 1.972 / 1.772
seconds**, each below the declared **5 seconds**. Reporter timestamps measure
the child-deadline/log-retention test at **0.623 seconds**, the holder-evidence
test at **0.610 seconds**, and all six phase-failure injections together at
**0.101 seconds**. The corresponding tests assert the 5-second bound.

The real drill preceded the final diagnostic retention adjustment; that
adjustment has its own actual subprocess regression. Existing longer reset
integration tests were updated to stop the cluster that reset now starts;
the entire longer integration namespace was not run to completion. No live
success claim rests on those edits.

The source syntax check now includes changed/untracked test files too,
because complete publication analyzes those files. No broad semantic-load
or successful-publication guarantee is inferred from that check.


## Review follow-up: elapsed phases and low-load drill

The reviewed slice is `c4d1be3ac`. This follow-up starts from clean HEAD
`ad6fe0fdd` in `tmp/reset-proof-wt`, excluding all concurrent uncommitted
edits. Every `phase!` invocation now prints and appends its monotonic
`elapsed-ms` in a `finally` block, on success and refusal alike. Its
regression checks both outcomes and the retained log. The deadline-output
fixture uses a minimal Python process instead of loading BB within a 300 ms
deadline; the previous BB fixture could expire before printing its marker.

**A lane must never run `bin/test` for this assignment.** That prohibition
also includes `bin/test --all`, `bin/test --full`, path gates, and platform
gates. Scoped iteration uses `bin/test-fast seon.dev.fresh-operator-reset-test`.
The only cluster this lane boots is the one isolated reset drill. No command
targets the main root's default cluster.

The load gate samples `uptime` every 60 seconds for at most 20 minutes and
uses its one-minute load average, requiring a value below 20 before the one
reset invocation. Raw samples and final logs are under `tmp/reset-proof/`.
Initial samples: 36.43, 23.95, 35.76.

The gate passed after approximately 4.53 minutes: subsequent one-minute
samples were 26.95 and **19.04**. Exactly one reset was invoked in the
snapshot: `bin/seon --root tmp/reset-drill-root reset --force`. It exited
**0**, including start and first development adoption. Its terminal phase
lines were:

```text
● reset phase=preflight elapsed-ms=261 log=/Users/sean/src/seon/tmp/reset-proof-wt/tmp/reset-drill-root/data/operator/operations/reset-preflight-80446.log
● reset phase=preflight elapsed-ms=207 log=/Users/sean/src/seon/tmp/reset-proof-wt/tmp/reset-drill-root/data/operator/operations/reset-preflight-80446.log
● reset phase=down elapsed-ms=196 log=/Users/sean/src/seon/tmp/reset-proof-wt/tmp/reset-drill-root/data/operator/operations/reset-down-80446.log
● reset phase=destroy elapsed-ms=12 log=/Users/sean/src/seon/tmp/reset-proof-wt/tmp/reset-drill-root/data/operator/operations/reset-destroy-80446.log
● reset phase=republish elapsed-ms=173210 log=/Users/sean/src/seon/tmp/reset-proof-wt/tmp/reset-drill-root/data/operator/operations/reset-republish-80446.log
● reset phase=refork elapsed-ms=33043 log=/Users/sean/src/seon/tmp/reset-proof-wt/tmp/reset-drill-root/data/operator/operations/reset-refork-80446.log
● reset phase=start elapsed-ms=22059 log=/Users/sean/src/seon/tmp/reset-proof-wt/tmp/reset-drill-root/data/operator/operations/reset-start-80446.log
● reset phase=adopt elapsed-ms=125323 log=/Users/sean/src/seon/tmp/reset-proof-wt/tmp/reset-drill-root/data/operator/operations/reset-adopt-80446.log
● reset phase=lifecycle elapsed-ms=354072 log=/Users/sean/src/seon/tmp/reset-proof-wt/tmp/reset-drill-root/data/operator/operations/reset-lifecycle-80446.log
```

Final descriptor status, before cleanup:

```text
default                   82823 alive       64322 http://127.0.0.1:64327   -
1/1 clusters alive
recorded JVM pid 82823 generation 2c2dc119-ef51-415c-bcbe-30205d48e771 alive
orphan seon JVMs: none
```

One read-only MCP JVM evaluation against the explicit scratch root compared
non-nil `seon.cluster.source/current` and the cluster's
`:seon.source/commit-id`: both were
`6aab45fa-7065-55ea-962b-0b8272d650b2`, and `converged?` was **true**.
MCP runtime status independently reported `alive`; all three plumbing
procs replied. It also reported six errored historical evaluations; this
proof establishes live source adoption, not an error-free application.
The same read-only evaluation queried `seon.fn/tests-reaching` for
`seon.operator/cleanup-root!`, returning the declared-root and complete,
truthful, symlink-safe cleanup regressions in `seon.operator-test`.

Scoped `bin/test-fast seon.dev.fresh-operator-reset-test` passed **7 tests,
89 assertions, zero failures/errors**. The earlier iteration had one
failure because BB startup exceeded the deadline-output fixture's 300 ms;
the minimal child correction above retained the same production deadline
and made the intended output/reaping assertion observable.

This successful drill supersedes the earlier publication-bound refusal as
the live-reset proof. Publication still consumed 173210 ms of its declared
180000 ms bound; that measured margin is narrow, and no faster-publication
claim is made. No bound was increased. Foreign uncommitted source changes
were excluded by the HEAD worktree, never edited or operated.

Cleanup used `bin/seon --root tmp/reset-drill-root down --force`, which
exited 0 and reaped pid 82823. Phase logs were retained under
`tmp/reset-proof/phase-logs`; the scratch root and worktree were removed.
No full gate was launched and no main-root cluster was booted or changed.
