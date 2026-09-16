---
type: issue
status: open
severity: friction
created: 2026-09-16
tags: [turn, agent, issue, performance]
---

# Every settlement reruns the agent's issue tests, costing seconds per opening form

## Problem

`seon.turn/settle-batch!` calls `(plan/run-issue-tests! cluster agent-id)` for
each settling agent, in the same breath as the terminal transaction. The
opening settles ONE form per `:generate` pass, so an issue-family worker
reruns its issue's whole test set once per generated form. Each `:generate`
pass measures about 3.5 s, of which the test rerun is about 2.3 s.

A four-form opening therefore needs roughly 18–20 s before its close and
`seon.issue-test/issue-worker-opening-links-its-issue` now exceeds the
declared 20 s `seon.test-support/event-backstop-seconds` — all eight of its
assertions pass when given wall time (measured 8/0/0 with the bound raised;
45 s wall for the whole test).

## Evidence

2026-09-16, default PID 53378, in-process, `seon.turn/turn` and its parts
wrapped with a nanosecond timer for one traced run of that test:

- `:generate` passes: 4439, 3561, 3571, 3474, 3636, 3723, 3307 ms
- `seon.turn/declared-sources`: 167, 167, 168, 159 ms
- `seon.turn/evaluate-sources`: 147, 75, 77, 159 ms
- `seon.plan/run-issue-tests!`: 2290 ms

So neither planning the declared sources nor evaluating the form is the cost;
the per-settlement test rerun is. For contrast, the program-graph reach digest
that `my.issue/status` reaches is cheap: 714 ms cold, 0 ms warm, on the
canonical fixture.

## Boundary

Do not raise the backstop to hide it — a bound firing is the bug report. The
question is why a per-form settlement reruns tests at all: the issue's tests
define completion, which is a question to ask when the work changes, not once
per generated opening line.
