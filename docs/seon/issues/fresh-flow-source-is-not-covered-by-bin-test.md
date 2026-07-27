---
type: issue
status: closed
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
- The recurring suite formerly at `test-old/seon/flow_test.clj` includes
  fixed-buffer backpressure, wedge capacity, interrupt survival, error
  fanout/drop accounting, mailbox bounds, multi-flow isolation, child death,
  and database serialization. A direct run against current `src/seon/flow.clj`
  passed 15 tests / 72 assertions.
- `bin/test` passed 11 tests / 55 assertions, but those 15 tests were absent
  from its namespace list.
- The 2026-07-27 adoption moved the suite unchanged to
  `test/seon/flow_test.clj`. It still passes 15 tests / 72 assertions under
  `-M:test:writer-test`, but both `bin/test seon.flow-test` and the full
  `bin/test` gate fail while loading the namespace because
  `clojure.core.async.flow-monitor` is available only from the quarry-facing
  `:writer-test` alias. The fresh `:test` alias must own that test dependency
  before the adoption can close.

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

## Resolution

Adopted 2026-07-27: `208a4a748` moved the suite; the orchestrator added
flow-monitor to the `:test` alias and adopted the `kill_child` helper the
child-JVM falsifier spawns. Full gate green: 32 tests / 158 assertions /
0 failures / 0 errors via `bin/test`.
