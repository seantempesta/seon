---
type: issue
status: resolved
severity: friction
tags: [issue, flow, test, wave/test-fixture]
---

# Inject transform failures without replacing proc lifecycle behavior

The last-resort fault regression replaced every arity of the committer's step
function with a throw. A late resume therefore failed in the transition
rather than the transform, producing no `::flow/op :step`. The isolated gate
`run.72XcCa` reported 29 tests, 257 assertions, one failure and no errors;
the confirmation worker passed the same test, demonstrating the scheduling
sensitivity. No process-global state leak was reported before that task.

`reference-code/core.async/src/main/clojure/clojure/core/async/flow/impl.clj`
invokes lifecycle transitions separately from the transform catch that attaches
`:op :step`. The fixture now delegates zero-, one-, and two-argument calls to
the original step function and throws only from the transform arity. The
existing fault-channel class regression must pass in the armed isolated gate
before this note is archived.

## Verified — 2026-09-09

`bin/test --paths test/seon/flow_test.clj -- seon.flow-test`: 21 tests /
200 assertions, zero failures/errors; coordinator/tests 39 seconds.
The resource unwinding class regression also landed with `2531b2e70`.
Fault fixtures carry the canonical database projection through their
environment; the bridge regression uses the gate's entering wrapper rather
than collecting incompatible schemas or mutating instrumentation.
Selected native clj-kondo: zero errors / 532 warnings, 3,995 ms.
