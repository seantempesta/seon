---
type: issue
status: open
severity: blocker
created: 2026-09-17
tags: [issue, test, gate, bounded-execution, liveness]
---

# A test that drives two real gates reports no progress to the silence bound

## Problem

`seon.test-runner-test/concurrent-bin-test-invocations-both-reach-their-tallies`
(`test/seon/test_runner_test.clj:1754`) starts two complete `bin/test`
invocations and then blocks on their futures. Between the `BEGIN test` line and
the tallies it emits NO reporter progress, so the coordinator's suite liveness
watchdog — declared at 300 s
(`SEON_TEST_SILENCE_SECONDS` ↔ `seon.test.runner/silence-seconds`) — measures
the test's silence rather than its liveness.

Under load the two child gates genuinely need longer than 300 s: each performs
its own dependency-cache, worker checkouts, published base and suite. Observed
2026-09-17 with three other lanes' gates on the machine: the watchdog fired at
exactly 300 s, killed the whole namespace's run with exit 124, and the dump
showed `main` parked in `clojure.core/deref` on the test's own future — no
deadlock, no leaked file descriptor, no orphan process. The run had already
completed 31 of 33 tests.

This is AGENTS.md §2.3's own failure shape from the other side: a bound that
reports what never arrived, where what "never arrived" is only a progress line
the test declines to emit. It also makes the namespace's result depend on how
busy the machine is, which is exactly the property a gate must not have.

## Evidence

- Reproduced by the test-system-stage2 lane on isolated HEAD `0b900a2b0`:
  parent JVM 82698 last progressed at `2026-09-17T03:07:25.484199Z`, then
  reported the same 300-second silence and exited 124. Its dump reported
  `deadlocked-thread-ids nil` and live child gate processes. The three new
  admission/recorder regressions had already passed; the two later recorder
  regressions remained unreached. The next iteration explicitly uses the
  previously documented `SEON_TEST_SILENCE_SECONDS=1800` observation bound.
  See [the lane note](../../prds/steward-platform/research/test-system-stage2-2026-09-17.md).

- `bin/test: no reporter progress for 300 seconds`, last-progress
  `BEGIN test seon.test-runner-test/concurrent-bin-test-invocations-both-reach-their-tallies`
  at `2026-09-17T01:34:40.874346Z`, killed `exiting 124`.
- The same namespace's other launcher fixtures, which drive ONE child gate,
  complete well inside the bound.

## It also conceals every test behind it

The test is declared before `gate-completions-travel-as-a-file-not-as-code` and
`result-recording-is-total-under-concurrent-test-retraction`. In the 2026-09-17
fast run the watchdog killed the JVM here after 32 of the namespace's 46 tests,
so those two reds were never reached and were first seen in the cold gate hours
later. With `SEON_TEST_SILENCE_SECONDS=1800` the same fast invocation ran all
46 tests in 13 minutes and reported 13 failures across 4 tests — the same two
classes the cold gate found, and nothing else. A silent block is not one failing test; it is an unknown number of
unrun ones. That is why this is a blocker and not friction.

## Direction

Two options, neither taken here because both are outside the gate-preparation
slice that found it:

1. The test reports progress while it waits — one reporter line per child
   milestone — so the watchdog measures the work and not the silence. This is
   the §2.3-shaped fix: the bound stays, the observable becomes real.
2. The test declares `:seon.test/long` and its own bound, acknowledging that
   two nested gates are not a fast-loop regression.

Whichever lands, the child gates' own preparation bounds (300–600 s, declared
at `bin/test:44-79`) are now longer than the parent's 300 s silence bound, so
the parent must either outlast them or say why it need not.
