---
type: issue
status: open
severity: friction
tags: [issue, test, sci]
---

# Task-execution fixture has no acquired SCI program

Run `e63e0d5af33e`, reproduced in `75801c3ccaed`, 2026-09-23:
`seon.test.runner-test/default-red-does-not-launch-confirmation` expects one
assertion failure and the deliberate fixture's message. Instead its task
returns `The SCI context did not acquire the tested program` from
`seon.test.runner/run-task!`. The failure count is zero and the expected
message is absent. The task never exercised the deliberately failing body.

This is outside the six assigned duration bodies. Acceptance: acquire the
fixture's actual program through its existing SCI owner, then verify the
failure is captured exactly once and no confirmation task executes. Do not
replace the acquired-program check or treat the preliminary refusal as the
intended failing assertion.
