---
type: issue
status: open
severity: blocker
tags: [issue, operator, test, wave/operator-lock-contention]
---

# Restore the parked-collection regression's observable GC entrance

## Problem

`seon.operator-test/parked-datahike-collection-yields-lock-and-retains-store-custody`
no longer reaches the `d/gc-storage` replacement that announces its required
`gc-entered` event. The test exhausts its declared event backstop before it can
exercise either custody assertion.

## Evidence

`bin/test seon.operator-test` on 2026-09-03 ran 28 tests and 222 assertions,
with zero failures and one error. The runner reproduced the error in its
isolated confirmation worker. Both executions ended at
`test/seon/operator_test.clj:941` with `The test did not observe its required
latch event`; the first took 101,070 ms and the confirmation took 20,024 ms.

The failing test was last authored in `732573ddb`. It replaces
`datahike.api/gc-storage` at `test/seon/operator_test.clj:930-934`, while the
collection future calls `seon.operator/collect!` at lines 935-940. The required
latch is counted down only inside that replacement, so the absent event proves
that the current collection path does not invoke the Var the regression
controls. The exact reason that path and replacement no longer meet remains to
be verified at the collection owner; absence of the event is not health.

## Owner

The `seon.operator/collect!` → store-collection boundary and its parked-custody
regression. The test must control the exact dependency Var production invokes,
without weakening the real-store custody proof.

## Acceptance

The explicit `seon.operator-test` gate observes the GC entrance, proves that
root lifecycle custody is released while store custody remains held, settles
the withheld completion, and reaches a zero-error total tally in both the main
worker and isolated confirmation path.
