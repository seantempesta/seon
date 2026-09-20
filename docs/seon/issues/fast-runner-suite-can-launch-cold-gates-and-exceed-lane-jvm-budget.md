---
type: issue
status: open
severity: blocker
created: 2026-09-20
tags: [issue, test, gate, bounded-execution, publication]
---

# The named fast runner suite can launch cold gates and exceed a lane's JVM budget

The publication assignment requires `seon.test-runner-test` in its fast pass
while prohibiting cold gates and overlapping lane JVMs. These requirements
conflict with `concurrent-bin-test-invocations-both-reach-their-tallies`
(`test/seon/test_runner_test.clj:2086`): it launches two actual `bin/test`
processes and calls `as-orchestrator` on their environments. Its long-test
metadata supplies a time bound, but does not enforce the lane's resource or
validation boundary. Explicit namespace selection includes this test.

The publication lane's HEAD-plus-owned-paths run `run.O81Ldt` reached it at
2026-09-20T05:23:29Z, after source verification. Inspection found parent JVM
15851 and nested JVM 32918, plus two child gate launchers. Upon recognizing
the boundary, the lane terminated only this process tree, deepest children
first. The enclosing fast invocation exited 143 and removed its snapshot;
all enumerated PIDs were confirmed absent. No other lane was operated.
The lane did not intentionally request a cold gate, but one nested gate JVM
was running: this run cannot be described as remaining within one JVM.
Raw parent log: `tmp/publication-dissolution/owned-regression-final-fast.log`.

The prior [silence-bound issue](a-test-that-drives-two-real-gates-reports-no-progress-to-the-silence-bound.md)
is resolved; this is a resource/admission boundary, not a request to increase
its bound. The remaining runner tests and all function suites were unexecuted
when this invocation was stopped. Function suites continue separately through
the prescribed fast entry point. The nested-gate proof belongs to the orchestrator.

Recommended direction: separate the real child-gate fixtures into an explicitly
selected integration namespace, retaining their recurring platform coverage.
This keeps ordinary lane fast selection within one JVM without a new policy
schema or silently skipping an obligation. Changing validation selection is
an orchestrator decision; this lane has not changed runner policy or fixtures.
