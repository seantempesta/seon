---
type: issue
status: open
severity: friction
tags: [issue, runtime, test, wave/contract-gate]
---

# Co-host start can race the reachability sweep

On2026-09-08, page-feed's HEAD-plus-owned-paths platform gate at `22f6163e5`
ran 82 tests / 470 assertions with one error in
`seon.cluster.cohost-boot-test/a-second-cluster-boots-under-the-first-cluster-s-instrumentation`.
After cohost-a started, `seon.cluster/start!` refused the second start:
`A reachability sweep is in progress; retry start later.` The gate's isolated
confirmation passed. This is an observed boot/sweep race, not evidence of a
render failure or a confirmed process-global instrumentation leak.

The page-feed lane owns cluster.clj only for the adoption web-server hunk and
has not modified this boundary. Verify start/sweep admission using the canonical
cohost fixture and preserve the bounded typed refusal. Evidence and rerun results:
[page-feed landing note](../../prds/context-generation/research/page-feed-landing-2026-09-08.md).


## 2026-09-09 custody slice recurrence

At `b4d665f89` plus the single agent-fixture correction, the explicit platform
run with three workers returned **83 tests / 474 assertions, zero failures,
one error** in the same cohost test. The refusal text was identical. Its
isolated confirmation passed, and the runner classified it `parallel-only`;
it found no preceding worker-global state change. Log:
`tmp/custody-final-platform.log`, retained root `run.Z9PWqq` removed after
confirming no live holder. The earlier platform run over the custody source
passed 83 / 490. The final rerun and verification boundary are recorded in
[the custody landing note](../../prds/context-generation/research/turn-rename-landing-2026-09-09.md).
The admission/sweep implementation was not changed by this slice.

The final single-worker platform rerun passed **83 tests / 490 assertions,
zero failures/errors** (`tmp/custody-corrected-platform.log`). This verifies
the final custody snapshot; it does not resolve the parallel boot/sweep race.

## 2026-09-09 reply-reader slice recurrence

The final reader/value/empty-directory snapshot at `6f7c6faa3` reproduced the
same refusal with one worker: **83 tests / 474 assertions, zero failures,
one error**. The isolated confirmation passed; the runner reported no
preceding worker-global change. The earlier platform snapshot passed
83 / 490. Log: `tmp/context-blocks/resume-slice4-final-platform.log`;
retained root: `tmp/test-runs/run.Xh7E6w`. The reply-reader slice does not
change cohost startup or sweep admission. Final rerun numbers are in the
context-blocks landing note; a green rerun does not resolve this race.
