---
type: issue
status: resolved
severity: friction
tags: [issue, testing, flow, runtime]
---

# `submission-time-limit-covers-the-pre-start-wait` hangs without its siblings

## Problem

`seon.flow-test/submission-time-limit-covers-the-pre-start-wait` deliberately
pauses its launcher, but its cleanup used to send an unacknowledged `resume`
immediately before `stop-work-launcher!` sent Flow's asynchronous `stop`.
Repeated isolated execution eventually left the launcher proc parked on its
control tap while cleanup awaited Seon's proc-stop promise.

The class: test cleanup must not invent a lifecycle transition the subject does
not require. Flow can stop a paused proc directly, so the extra resume created
an unnecessary control-command race and made the regression timing-dependent.

## Evidence

- The first nine-worker full measurement on 2026-08-10 reproduced the same
  var-level hang. The task began at `2026-08-11T02:14:41.331900Z`; the last
  other worker completed at `02:19:17.469359Z`; the suite liveness backstop
  then stopped the run at 24:55.66 wall time with exit 124. A
  virtual-thread-aware dump shows the worker main thread blocked in
  `seon.flow/stop-work-launcher!` waiting on `drained`, reached from the test's
  `finally` cleanup at `test/seon/flow_test.clj:411`. The retained run root is
  `tmp/test-runs/run.3xA5xw`; the measurement is recorded in
  [parallel-runner-measurement-2026-08-10.md](../../../prds/sci-execution-runtime/research/parallel-runner-measurement-2026-08-10.md).
- `bin/test --platform` on 2026-08-07 night, with four `seon.flow-test` vars
  declared `:seon.test/platform` (graph construction, callback contracts,
  submission time limit, completion diagnostics). Three completed; the run then
  produced no reporter progress for 300 s and the backstop halted it with
  exit 124.
- Backstop `last-progress`:
  `BEGIN test seon.flow-test/submission-time-limit-covers-the-pre-start-wait`
  at `2026-08-08T02:41:36.031796Z`; nothing after it.
- The same var inside a full-namespace run: 0.12 s
  (`tmp/bare-gate-2026-08-06d.log` and the 2026-08-07 platform run's own
  per-test timings).
- Diagnostic (virtual-thread-aware dump) retained at the failed run root:
  `tmp/test-runs/run.XrO8Qv/tmp/test-liveness/98978-1786157196912.log`.
- The cause probe corrected the sibling-state attribution. A clean-process
  isolated run and a reusable-worker isolated run both passed once, but 50
  consecutive isolated `clojure.test/test-vars` executions wedged on iteration
  10. The virtual-thread-aware dump showed the test thread in
  `seon.flow/stop-work-launcher!` and one Flow proc still parked in
  `clojure.core.async.flow.impl/proc` at the running-state `alts!!`; no
  background-I/O submissions existed and `drained` was immediately
  realizable. Removing only the cleanup's pre-stop `flow/resume` made the same
  50-run falsifier complete.

## Impact

The gate's platform tier had to drop to namespace granularity, so the Flow
graph-construction, atomic-settlement, and sci-fork moving parts have no
fail-fast coverage: their namespaces (`seon.flow-test` 12.8 s,
`seon.cluster.run-test` 39.7 s, `seon.sci.eval-test` 118.9 s) are too expensive
to run in a seconds-scale tier, and picking the cheap structural vars out of
them is what this defect blocks.

## Acceptance

- The var passes repeatedly in isolation and inside the complete namespace,
  with no sibling setup or timing-dependent cleanup transition.
- The production join remains event-driven through the proc-stop promise; no
  clock or second lifecycle mechanism is added.

## Owner

The Flow/test-infrastructure lane. Related: the seven consolidated direct
moving-part regressions in
[test-infrastructure-spec-2026-08-07.md](../../../prds/sci-execution-runtime/plan/test-infrastructure-spec-2026-08-07.md)
would replace these hand-picked vars entirely.

## Resolution

Resolved by commit `b9c0f63fd`.

The test now stops the deliberately paused graph directly. Flow already
handles `stop` from the paused state and invokes the proc's stop transition, so
the cleanup no longer sends a redundant unacknowledged `resume` immediately
before the stop command.

The exact pre-fix repeated-isolation falsifier completed 50 consecutive runs
after the change. The complete `seon.flow-test` namespace then passed 17 tests
and 250 assertions with zero failures or errors; the repaired var completed in
103 ms in that sibling run.
