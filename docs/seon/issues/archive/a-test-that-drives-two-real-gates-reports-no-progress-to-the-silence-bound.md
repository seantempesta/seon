---
type: issue
status: resolved
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

## Stage 1 runner follow-up, 2026-09-17

Reproduced at isolated HEAD `707508b6f` in the requested four-namespace fast
run: test JVM 76470 entered the concurrent-launcher regression at
19:50:16.415680Z, then the watchdog reported 320 seconds without progress and
exited 124. The declared test-body allowance was zero. No environment override
was used. The full tally and the remaining namespaces were not reached.

The test now declares `:seon.test/long` and `:seon.test/long-ms 1800000`.
This is the issue's second direction: two complete gates are explicitly long
work. The bound accommodates two store preparations, the test's parallel
child waits and bounded output collection/cleanup; it does
not remove any child preparation or execution bound. Explicit namespace runs
continue to include it. Verification is recorded in
[the Stage 1 note](../../prds/steward-platform/research/test-system-stage1-2026-09-17.md).

The next complete fast run reached the tally: 141 tests, 1,026 assertions,
9 failures and zero errors. Two were separately corrected graph-test mistakes;
seven came from this fixture. Its inner `12 * event-backstop-seconds` wait
was only **240 seconds** (`event-backstop-seconds` is 20), so both children
were killed with exit 137 during base publication despite the longer outer
declaration. The child completion waits now derive remaining milliseconds from
the single declared test deadline, starting before store preparation. Output
collection and cleanup retain their existing bounded waits. No environment
override is involved. This removes the two conflicting body-duration bounds.

The final serial fast iteration at isolated HEAD `02cb1b2b7` passed all 141
tests and 1,026 assertions, with zero failures/errors. The concurrent-launcher
test began at 20:40:45.833340Z and ended at 20:50:21.239062Z (575.406 seconds),
with both child tallies observed. Evidence:
`tmp/test-system-stage1-resumed-fast-5.log:400–405`. Cold integration remains
the orchestrator's responsibility.
