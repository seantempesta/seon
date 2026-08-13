---
type: issue
status: open
severity: friction
tags: [issue, sci, test, class/p2, wave/sci-eval-readiness]
---

# Publish eval arming before testing concurrent interruption

## Problem

The concurrent-evaluation isolation test uses 100 million interpreted entries
and assumes that work always exceeds 200 ms but completes within 10 seconds.
CPU speed, JIT state, scheduling, and host contention are therefore part of the
correctness oracle.

## Evidence

`test/seon/sci/eval_test.clj:930-956` releases two futures from a latch before
either calls `run-in`, gives the same finite-spin function 200 ms in one future
and 10 seconds in the other, then expects one `:time` outcome and one exact
completion. The 15-second derefs are valid deadlock backstops, but the
200 ms/10 s computation window is calibrated workload timing.

`src/seon/sci/eval.clj:320-380` installs each thread's armed state and privately
schedules its reached marker. It publishes no post-installation readiness
event. The test's start latch therefore proves only that both futures were
released, not that both evaluation threads were armed together.

The test passed 5/5 quiet repetitions during the 2026-08-02 sweep; this is a
source-proven speed-dependent risk, not a reproduced failure.

## Owner

The `seon.sci.eval` arm/evaluate boundary and its concurrent-interruption test.

## Acceptance

Publish a test-observable readiness event after each evaluation thread installs
its ThreadLocal arm and creates its scheduled interruption task. The test
awaits both readiness events, releases event-controlled interpreted bodies,
interrupts only one, and proves one `:time` result plus one sibling `:ok` exact
value. Wall clocks remain only loud deadlock backstops; no fixed amount of
interpreted work decides which semantic outcome occurs.
