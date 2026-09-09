---
type: issue
status: open
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
