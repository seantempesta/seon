---
type: issue
status: open
severity: cleanup
created: 2026-09-16
tags: [issue, testing, performance]
---

# Coordinator progress attributes worker startup to program analysis

`seon.test.runner/run-coordinator!` prints `SELECT building the program graph`,
then reads the already-published manifest and waits on all worker initialization
futures. `bin/test` supplies `seon.test.published-base`, so this path has not
rebuilt the program graph since commit `45e5c6c5605b4c42223b9970a0a2256abf6954a9`.

The enclosing 18-second interval was incorrectly attributed to graph analysis
in the [execution research note](../../prds/steward-platform/research/test-execution-model-2026-09-16.md).
The [preparation-cost landing note](../../prds/steward-platform/research/test-preparation-costs-2026-09-16.md)
records the source archaeology and a new **23.018-second** enclosing interval;
neither interval measures analysis independently.

Correct the label and distinguish manifest acquisition from waiting for worker
readiness/initialization. Verify the actual published-base path on a real gate.
Do not change selection semantics or replace the existing manifest read with
a second program analysis. This is step 3 of the preparation assignment,
deferred at its explicit step-2 design review boundary.
