---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [issue, test, database]
---

# Issue worker creation does not complete within its in-process test bound

## Problem

Two serial runs of `seon.issue-test/issue-worker-creation-is-atomic` through
`seon.test/run` on default ended with the declared 20,000 ms completion-bound
failure. Neither run completed an assertion. This does not establish a
worker-creation defect or identify where the test spent its time.

## Evidence

On 2026-09-16 at 02:46:25 and 02:49:48 UTC, MCP JVM mode returned complete
result values: 0 passes, 0 failures, 1 error. Recorded run entities were
66104 and 66126; their bases were 536871615 and 536871632. MCP elapsed times
were 23,257 and 22,915 ms. Both explicitly named the missing test completion
and `:seon.test/remaining-ms` bound. Default PID 7595 remained alive.

The [settlement landing note](../../prds/steward-platform/research/issue-settlement-2026-09-16.md)
contains the exact invocation and evidence; its adjacent EDN retains the
replayable MCP request. `src/seon/test.clj:44` owns bounded execution;
`test/seon/issue_test.clj:127` owns the canonical regression.

## Owner

The in-process test runner and issue-family regression owners. Inspect the
actual pending operation under the canonical fixture before attributing the
failure to fixture preparation, contention, or worker creation. Do not just
increase the bound. Concurrent issue-family edits were preserved.

## Acceptance

The canonical regression completes through `seon.test/run` under armed
contracts and its declared bound, with positive assertions and recorded
provenance. A diagnostic probe identifies the prior stalled operation or
records a reproducible boundary if the defect persists.
