---
type: issue
status: open
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
