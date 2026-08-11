---
type: issue
status: open
severity: friction
tags: [issue, testing, flow, runtime]
---

# `submission-time-limit-covers-the-pre-start-wait` hangs without its siblings

## Problem

`seon.flow-test/submission-time-limit-covers-the-pre-start-wait` completes in
0.12 s when the whole `seon.flow-test` namespace runs, and never completes when
it is the only var selected from that namespace. It is therefore coupled to
state some earlier var in the namespace leaves behind, not to its own fixtures
— `clojure.test/test-vars` runs the same once- and each-fixtures either way.

The class: a test whose passing depends on a sibling test having run first is
green for the wrong reason. It also makes the gate's platform tier
namespace-granular by force — any var-granular selection of that namespace
wedges the whole gate at the 300 s liveness backstop instead of failing.

## Evidence

- The first nine-worker full measurement on 2026-08-10 reproduced the same
  var-level hang. The task began at `2026-08-11T02:14:41.331900Z`; the last
  other worker completed at `02:19:17.469359Z`; the suite liveness backstop
  then stopped the run at 24:55.66 wall time with exit 124. A
  virtual-thread-aware dump shows the worker main thread blocked in
  `seon.flow/stop-work-launcher!` waiting on `drained`, reached from the test's
  `finally` cleanup at `test/seon/flow_test.clj:411`. The retained run root is
  `tmp/test-runs/run.3xA5xw`; the measurement is recorded in
  [parallel-runner-measurement-2026-08-10.md](../../prds/sci-execution-runtime/research/parallel-runner-measurement-2026-08-10.md).
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

## Impact

The gate's platform tier had to drop to namespace granularity, so the Flow
graph-construction, atomic-settlement, and sci-fork moving parts have no
fail-fast coverage: their namespaces (`seon.flow-test` 12.8 s,
`seon.cluster.run-test` 39.7 s, `seon.sci.eval-test` 118.9 s) are too expensive
to run in a seconds-scale tier, and picking the cheap structural vars out of
them is what this defect blocks.

## Acceptance

- The var passes in isolation (`bin/test --platform` with only that var
  declared, or any var-granular selection), with the coupling removed at its
  cause rather than by re-adding a sibling dependency.
- The dump names what it was waiting for; the wait becomes event-driven or the
  test constructs its own precondition.
- Once green in isolation, the cheap structural vars of `seon.flow-test`,
  `seon.cluster.run-test`, and `seon.sci.eval-test` are declared
  `:seon.test/platform` so the three moving parts regain fail-fast coverage.

## Owner

The Flow/test-infrastructure lane. Related: the seven consolidated direct
moving-part regressions in
[test-infrastructure-spec-2026-08-07.md](../../prds/sci-execution-runtime/plan/test-infrastructure-spec-2026-08-07.md)
would replace these hand-picked vars entirely.
