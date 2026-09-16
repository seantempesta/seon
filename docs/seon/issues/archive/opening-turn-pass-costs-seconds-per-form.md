---
type: issue
status: resolved
severity: friction
created: 2026-09-16
resolved: 2026-09-16
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

## Resolution (2026-09-16)

Two changes at the owner, no bound raised.

1. **Only the ordinary close asks the question.** `seon.turn/closing-settlement?`
   derives the close from the same two facts that emit `close-tx` — the settled
   disposition and the undisposed agent form. The system-turn writer runs no
   issue tests at all; `evaluation-terminal-data` and `settle-batch!` run them
   only for the settlement that closes, so a generated opening form costs none.
   `close-turn` is unchanged: it was already the ordinary close.
2. **Only the stale tests run.** `seon.plan/stale-issue-tests` filters the
   issue's tests through `seon.test/stale`, which grew a named arity answering
   for exactly the supplied tests instead of digesting the whole population. A
   test whose reach closure is unchanged since its recorded result is not
   re-run; the done-query still reads `verified?`, so a step completes on that
   recorded evidence. When nothing is stale the settlement acquires no SCI
   context, no provenance and no deadline.

Measured in-process on default (PID 53378), canonical fixture:

| Run | Before | After |
|---|---|---|
| `seon.issue-test/issue-worker-opening-links-its-issue` | 21,452 ms, 0/0/1 — exceeded the declared 20 s bound | 12,921 ms, **8/0/0** |
| `seon.issue-settlement-test/issue-settlement-runs-tests-and-derives-completion` | (system-turn driver) | 13,509 ms, **32/0/0** |

The settlement regression was rewritten onto the real seam: it drives an
ordinary turn to its durable close through `turn/virtual-turn!` plus
`turn/next-agent-work`/`turn/turn`, and asserts the three ruled facts — a
system-turn settlement records no run for either issue test; editing one
reached function re-runs exactly that test and leaves the other's recorded
run identity untouched; and the step completes from the unchanged test's
recorded evidence.
