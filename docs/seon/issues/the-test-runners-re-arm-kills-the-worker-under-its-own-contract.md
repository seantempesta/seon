---
type: defect
status: open
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
