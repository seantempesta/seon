---
type: issue
status: resolved
severity: blocker
tags: [issue, test-fixture, runtime, contracts]
---

# A successful shared fixture base retains pre-adoption contracts

## Probe — 2026-09-16

Default PID 53378, after reloading tests through `seon.test/with-test-loader`.
A fresh canonical fixture branch still declares `seon.test/stale` with only
`[:=> [:cat :seon.db/database-value] ...]`. Default's program row declares
both that arity and `[database test-symbols]`. The loaded plan caller at
`src/seon/plan.clj:570` calls the latter. Under the fixture's projection the
call refuses with `:malli.core/invalid-arity`, offending argument count 2.

The complete stack reaches `seon.plan/stale-issue-tests`, `run-issue-tests!`,
then ordinary turn settlement. The generated-attempt scenario probe never
reaches its verdict. This is a successfully realized old base, not evidence
of the separately recorded cached construction throwable.

The [batch-28 continuation](../../prds/context-generation/research/turn-test-reds-cache-2026-09-16.md)
records the exact returned contracts and stack. No protected owner was edited,
no base was replaced, and no default lifecycle operation occurred. The current
wave's explicit rule requires orchestrator direction before rebuilding the
shared canonical base; that direction was requested.

## Owning boundary

`test/seon/test_support.clj` canonical base acquisition and development source
adoption. A new fixture branch must carry contracts coherent with the code it
executes. Reloading only the test namespace cannot update a realized base.
Acceptance is an in-process regression that adopts an accreted function arity,
acquires a fresh canonical fixture, and calls that arity under armed contracts.

## Authorized recovery

The owner authorized one refresh on 2026-09-16. In default PID 53378, the
existing canonical constructor completed in **27548.6125 ms**; its returned
program row declares both `seon.test/stale` arities. The new base was installed
only after construction succeeded. No retry, process restart, or refork was
performed. The immediate verification boundary is cleared; this issue remains
open for automatic fixture/source coherence.

## Recurrence — 2026-09-17, default PID 88182

Same class, same JVM boundary, measured by the program-shapes-adoption lane.
Two accreted arities were adopted (`bin/seon init --dev default` converged,
cluster `:seon.source/commit-id` matched, and both arities answered from the
prepl) and BOTH were refused inside `seon.test/run` on the shared canonical
base: `seon.fn/reconcile-tx refused argument count ... of 4` and
`seon.program/exact-replacement-tx refused argument count ... of 3`. Three
existing regressions (`seon.fn-test/indexed-declarations-carry-exact-file-bytes`,
`static-findings-are-replaced-with-their-program-rows`, and the lane's own new
one) reported errors that were entirely this staleness.

The lane worked around it WITHOUT rebuilding the base, by not adding an arity:
`seon.fn/reconcile-tx` keeps its single arity and delegates to a private
`reconcile-tx-in`, and `seon.program/exact-replacement-tx-in` is a new NAME
rather than a new arity. A private function is not a callable root and a new
name has no stale row, so both are provable in process today. After that
restructure all three regressions are green (12/0/0, 9/0/0, 7/0/0).

That is a workaround, not the fix: the acceptance this issue already states —
a fresh canonical fixture carrying contracts coherent with the code it
executes — is unchanged, and until it lands **no lane can prove an accreted
arity in process**.

Second, separate trap measured the same day: `seon.test/run` reloads the test
namespace INSIDE the run, so a `test-var` resolved before the call runs the
PREVIOUS definition. Two runs reported an error from test code the file no
longer contained. Reload through `seon.test`'s loader and resolve the Var
AFTER the reload, in the same evaluation.

## Resolved — 2026-09-17, the shared base follows adoption

`seon.test-support/database-base` is now KEYED by the publication it was built
from, and so is `seon.test-support/source-manifest`. `publication-key` is the
isolated worker's immutable snapshot path when `seon.test.published-base` is
set, and otherwise the `:current-src` head of this JVM's held stores
(`seon.operator.runtime/root-store-holder` → `seon.cluster.source/current`,
measured 1.05 ms per read). A converged `bin/seon init --dev` advances that
head, so it is a base cache MISS by construction: the next run builds a base
from the current publication. Nothing is rebuilt by hand and no JVM is
restarted. A retired base is closed only after its last holder releases it, so
a run holding the old base completes on it and no branch is deleted under an
active connection; `fork-cluster-ctx` forks the base the enclosing fixture
holds. The daemon-thread construction property is unchanged — a caller's bound
never interrupts a construction and a failure is retried, never cached — and at
most one construction now runs at a time.

Regressions: `seon.test-support-test/the-shared-base-follows-the-published-commit`
(11 assertions) and
`seon.test-support-test/the-publication-key-derives-from-the-published-head`
(8 assertions).

The acceptance this note originally stated — a regression that adopts an
accreted arity and calls it through a fresh canonical fixture — cannot run in a
`bin/test` worker, which has no adoption seam and whose published snapshot is
immutable for the JVM's life. It is replaced by those two regressions plus the
live in-process proof in default PID 88182: the head advanced under the JVM,
`realized?` answered false for the superseded base, and the next branched
fixture built a new base and completed (9/0, 67389.731625 ms). Evidence and the
measured adoption churn are in
[the landing note](../../prds/steward-platform/research/shared-base-follows-adoption-2026-09-17.md).

The second trap recorded above — `seon.test/run` reloading the test namespace
INSIDE the run — is unchanged and still requires resolving the Var after the
reload.
