---
type: issue
status: open
severity: friction
tags: [issue, testing, flow, lifecycle]
---

# Acquire Flow test resources inside their cleanup scope

## Problem

The Flow Monitor integration test acquires and activates resources before the
`try` that releases them. A setup assertion or exception can leave a wedged
proc, server, fanout, graph, or executor alive, making later gate behavior
depend on test order.

## Evidence

`test/seon/flow_test.clj:1227-1270` starts the graph and fanout, resumes work,
injects a wedge, awaits it, fills the submission buffer, and starts the monitor
before the cleanup `try` at line 1271. The `finally` at lines 1307-1320 also
waits for the parked injection before stopping later resources, so one cleanup
failure can skip the remaining releases.

`docs/prds/sci-execution-runtime/research/test-problems-triage-2026-07-29.md`
identifies the same failure class and proposes one scoped resource fixture that
registers cleanup immediately after each successful acquisition.

No intermittent failure was reproduced in the 2026-08-02 sweep. This is a
source-proven order/leak risk, not a claimed observed flake.

## Owner

`seon.test-support` resource scoping and `seon.flow-test` integration fixtures.

## Acceptance

Inject failure after every acquisition ordinal and prove every earlier
resource closes exactly once while no later resource opens. The Flow Monitor
test acquires each graph, fanout, server, wedge, and executor inside that
scope; every cleanup runs even when an earlier cleanup throws. The focused
Flow namespace and full gate leave no live test-owned process, graph, server,
or executor.
