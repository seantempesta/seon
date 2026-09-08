---
type: defect
status: resolved
severity: blocker
tags: [testing, instrument, runner, class/absence-as-health]
---

# The test runner's mid-run re-arm kills the worker under its own contract

Found by `custody-isolation` (2026-09-08,
`research/custody-isolation-landing-2026-09-08.md` §4). A pooled worker
exited 1 before any assertion ran: `seon.config/result-caps violated its
contract (invalid-input)` thrown at `seon.test.runner/arm-contracts!`
(`runner.clj:897`) reached from `reassert-contracts!` (`runner.clj:968`) —
the mid-run re-arm. On the first arm nothing is instrumented so the call
answers; on re-arm the same call runs through its own armed `:panic`
contract, all 18 problems being absent OPTIONAL config dials, and the
worker dies. The suite then reports the namespaces that worker held as red
("confirmation parallel-only") — `seon.custody-stability-test` was
mis-attributed to custody for a day because of it. Not reproducible on
demand: three later runs were clean.

## Fix

Carry the caps `::arming` already decided into the re-arm instead of
re-deriving them under the armed world (the hunk is in the landing note),
and make a worker death during re-arm a LOUD typed verdict naming the
re-arm, never a per-namespace red.

## Resolved 2026-09-08 (`instrumented-gate-backlog-2`, the re-arm's author)

Exactly the fix this note names. `seon.test.runner/arming-decision` decides
the shipped decisions, the admission caps and the program namespaces ONCE,
before the first arm installs anything, and the worker carries that decision;
`arm-contracts!` takes it as an argument and never re-derives it. So a re-arm
asks no config question under the contracts the first arm installed.

The second half landed too: a re-arm that dies is now a LOUD verdict naming
the re-arm and the worker, not a silent worker death whose namespaces are
reported red against their own owners.

Regression: `seon.test-runner-test/a-worker-rearms-only-when-a-task-stripped-
its-contracts` asserts the carried decision reaches the arm.

Gate: `bin/test seon.test-runner-test seon.custody-stability-test` GREEN,
including `seon.custody-stability-test/cross-cluster-write-isolation`, the
test this defect mis-attributed to custody. `bin/test --platform` GREEN
73/398/0.

Left open, and separate: **`seon.config/defaults` does not satisfy
`:seon.config/effective`.** The compiled shipped effective config legitimately
leaves eighteen optional dials absent while the key's schema requires them, so
`config/result-caps` refuses its own declared input whenever it is called
under armed contracts. Nothing calls it there any more, which is why this is
closed — but the disagreement is real and is a check nobody asks. It belongs
to `seon.config`.
