---
type: issue
status: open
severity: blocker
tags: [issue, flow, testing, runtime]
---

# Put adopted Flow contracts on the default test gate

## Problem

`seon.flow` is fresh-tree production source, but the default `bin/test` gate
discovers only `test/seon/flow/loop_test.clj`. Most of the namespace's
surviving launcher, error-fanout, proc lifecycle, backpressure, containment,
and multi-flow contracts remain under `test-old/`, which the default project
does not run.

The fresh gate can be green while an adopted foundation is broken.

## Evidence

- `src/seon/flow.clj:200-713` owns the bounded work launcher and core-fault
  fanout. `src/seon/flow.clj:795-1036` owns source, indexer, eval, mailbox, and
  database procs.
- The four discovered tests in `test/seon/flow/loop_test.clj:488-717` exercise
  only seeded lineage outcomes, planner/owner behavior, escalation, and
  admission rejection. None calls the owners above.
- The recurring tests still exist at `test-old/seon/flow_test.clj`, including
  fixed-buffer backpressure, wedge capacity, interrupt survival, error
  fanout/drop accounting, mailbox bounds, multi-flow isolation, child death,
  and database serialization. A direct run against current `src/seon/flow.clj`
  passed 15 tests / 72 assertions.
- `bin/test` passed 11 tests / 55 assertions, but those 15 tests were absent
  from its namespace list.

This violates the construction handbook's rule that every proof must be
claimed by a recurring surface and the fresh-tree ruling that `bin/test` is
the system gate.

## Owner

The `seon.flow` port manifest and `test/` adoption boundary. Move only tests
that assert surviving Flow mechanisms; delete old-model tests rather than
keeping a second gate.

## Acceptance

- `bin/test` repeatedly covers bounded submission/backpressure, timeout/wedge
  accounting, stop/interrupt cleanup, error fanout and drops, mailbox bounds,
  database proc serialization, and per-flow isolation.
- Indexer/source tests that still describe fresh `seon.flow` are either
  adopted or explicitly ruled dead with their source removed.
- No current `seon.flow` public contract relies only on `test-old/`.
- The default gate fails when any one of those surviving mechanisms is
  falsified.
