---
type: issue
status: open
severity: friction
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

- `bin/test: no reporter progress for 300 seconds`, last-progress
  `BEGIN test seon.test-runner-test/concurrent-bin-test-invocations-both-reach-their-tallies`
  at `2026-09-17T01:34:40.874346Z`, killed `exiting 124`.
- The same namespace's other launcher fixtures, which drive ONE child gate,
  complete well inside the bound.

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
