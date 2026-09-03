---
type: issue
status: resolved
severity: blocker
tags: [issue, operator, test, wave/operator-lock-contention]
---

# Restore the parked-collection regression's observable GC entrance

## Problem

`seon.operator-test/parked-datahike-collection-yields-lock-and-retains-store-custody`
once failed to reach the transitive `d/gc-storage` replacement that announced
its required `gc-entered` event. The test exhausted its declared event backstop
before it could exercise either custody assertion.

## Evidence

`bin/test seon.operator-test` on 2026-09-03 ran 28 tests and 222 assertions,
with zero failures and one error. The runner reproduced the error in its
isolated confirmation worker. Both executions ended at
`test/seon/operator_test.clj:941` with `The test did not observe its required
latch event`; the first took 101,070 ms and the confirmation took 20,024 ms.

The failing test was last authored in `732573ddb`. It replaces
`datahike.api/gc-storage` at `test/seon/operator_test.clj:930-934`, while the
collection future calls `seon.operator/collect!` at lines 935-940. The required
latch was counted down only inside that replacement, so the absent event proved
that one invocation did not reach the transitive Var the regression controlled.
It did not establish that the production collection path had moved; absence of
the event is not health.

The failure did not reproduce at `388cb321c`: before any edit, the same
explicit gate ran 28 tests and 226 assertions with zero failures and zero
errors, and this regression completed in 84 ms. No collection owner changed
between the issue commit `b136f574f` and that proof. An isolated live JVM probe
also confirmed both current links: `seon.operator/collect!` reaches
`collect-store!`, whose two passes call `seon.cluster.registry/collect!`, and
that registry owner still calls `datahike.api/gc-storage` at
`src/seon/cluster/registry.clj:503-531`. The earlier absent event therefore did
not prove that production stopped collecting; its transient cause is not
reproducible and remains unattributed.

## Owner

The `seon.operator/collect!` → store-collection boundary and its parked-custody
regression. The test must control the exact dependency Var production invokes,
without weakening the real-store custody proof.

## Acceptance

The explicit `seon.operator-test` gate observes the direct store-collection
entrance, proves that root lifecycle custody is released while store custody
remains held, settles the withheld completion, and reaches a zero-error total
tally.

## Resolution

Commit `8a261c9c1` makes the regression control the direct owner seam that
`collect-store!` invokes: `seon.cluster.registry/collect!`. The first call
announces its latch event and parks until released; the test then proves that
the real operation-store flock still refuses a second open while the root
lifecycle lock is available. After release, it requires exactly two registry
collection calls, covering the primary and verification passes, and proves
that terminal collection releases store custody. This removes the unused
transitive Datahike replacement without changing the production collection
path.

`bin/test seon.operator-test` after the change ran 28 tests and 227 assertions
with zero failures and zero errors. The parked regression completed in 99 ms;
the successful isolated root was removed by the gate. The acceptance behavior
is satisfied at the operator-owned collection boundary.
