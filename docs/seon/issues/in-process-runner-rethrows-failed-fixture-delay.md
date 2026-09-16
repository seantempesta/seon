---
type: issue
status: resolved
severity: friction
tags: [issue, test, wave/test-fixture]
---

# In-process runner state inspection rethrows a failed fixture delay

## Problem

After the canonical fixture base delay fails, every in-process test run
rethrows that failure before entering the test body, including tests using
their own fresh canonical database.

## Evidence

Entity-pairs, default PID 7595, 2026-09-16: direct invocation of
`seon.test.runner/ambient-snapshot` under default's carried projection
returned `Program indexing transaction was refused`, with
`:seon.fn/index-phase :seon.fn/population` and transaction result
`:seon.db/unknown-failure`, `java.lang.InterruptedException`,
`:seon.db/transaction-outcome-unknown true`.

`src/seon/test/runner.clj:878` checks `realized?` on
`seon.test-support/database-base`, then dereferences it. A failed delay is
realized too. `run-var!` takes that snapshot before `clojure.test/test-vars`
(`src/seon/test/runner.clj:534`). Recorded P2 tests returned 0 passes,
0 failures, 1 error in roughly 250 ms (runs 45228 and 45235); no assertion
was reached. The lane did not reset the shared delay or change the runner.

Later on the same JVM, scoped test-classpath loading and canonical fixture
warm acquisition completed; runs 51140 and 51142 passed 17 and 24 assertions
respectively. This removes the lane's immediate execution blocker but does
not falsify the failed-delay observation above.

## Owner

The runner's observation of canonical fixture acquisition. Distinguish
failed acquisition from an acquired SCI context; unknown observation must
not execute the stored failure as test setup.

## Acceptance

A failed fixture acquisition remains visible as evidence, while an unrelated
test using a fresh canonical fixture can execute and record its own result.
Exercise a failed delay, an unrealized delay, and an acquired base without
changing shared worker globals.

## Resolution — 2026-09-16

Resolved by `f2d537187`, at `src/seon/test/runner.clj:869`. The existing
observer accepts the delay explicitly, leaves unrealized acquisition alone,
and reports a failed acquisition as an explicit unavailable observation.
It no longer rethrows a memoized failure before unrelated test bodies.
The canonical in-process regression
`seon.test-reaching-test/fixture-state-observation-is-total` passed 7/0/0
before editing (run 55954) and after hot loading (run 57596, basis 536871350)
on `(seon.operator/connection "default")`. The shared delay was not reset.
Complete adoption remains bounded by the reach-digest landing note.
