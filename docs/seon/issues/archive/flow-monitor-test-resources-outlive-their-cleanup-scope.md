---
type: issue
status: resolved
severity: friction
tags: [issue, flow, test, class/n4, wave/test-fixture]
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

## Repair in progress — 2026-09-08

The fixture now acquires its launcher, HTTP client, fanout, monitor, and
WebSocket inside nested `with-open` scopes. A small `test-support/closeable`
adapter carries the value and its release function; Clojure's existing macro
owns cleanup ordering and exception unwinding. The class regression injects
setup/body failure after each acquisition count and cleanup failures at every
resource, asserting that each acquired resource closes once and no later one
opens. HTTP and WebSocket waits use declared event bounds. Verification is
running; the note remains open until the isolated gate passes.

## Verified — 2026-09-09

`bin/test --paths test/seon/flow_test.clj -- seon.flow-test`: 21 tests /
200 assertions, zero failures/errors; coordinator/tests 39 seconds.
The resource unwinding class regression also landed with `2531b2e70`.
Fault fixtures carry the canonical database projection through their
environment; the bridge regression uses the gate's entering wrapper rather
than collecting incompatible schemas or mutating instrumentation.
Selected native clj-kondo: zero errors / 532 warnings, 3,995 ms.
