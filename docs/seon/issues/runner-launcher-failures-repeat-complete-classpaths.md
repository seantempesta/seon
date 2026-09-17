---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, test, diagnostics, rendering]
---

# Launcher failure reports repeat complete classpaths

In the Stage 1 fast run at isolated HEAD `02cb1b2b7`, the concurrent-launcher
fixture's two child gates exceeded its old 240-second wait. Seven assertions
then printed the same captured launcher output repeatedly, including the whole
resolved dependency classpath and source warnings. The child output was tens of
thousands of characters; repeating it obscured the actual evidence: both
completion waits were false and both child exits were 137.

Evidence: `tmp/test-system-stage1-resumed-fast-4.log:416–2445` and
`test/seon/test_runner_test.clj`'s
`concurrent-bin-test-invocations-both-reach-their-tallies`. No claim is made that
a classpath entry caused the timeout. The wait-bound repair is recorded in
[the liveness issue](a-test-that-drives-two-real-gates-reports-no-progress-to-the-silence-bound.md).

The reporting owner should retain the complete child output as evidence and
render one useful failure per child, naming its terminal state and last phase.
Do not introduce another clipping helper: presentation bounds belong to the
existing value renderer. The full stdout must remain available for diagnosis.
