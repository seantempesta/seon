---
type: issue
status: resolved
tags: [issue, testing]
severity: friction
---

# Full gate has a one-in-three flake post error step 2

## Evidence

2026-07-27 night, immediately after `e1f7262c6` (error step 2): three
consecutive `bin/test` runs on an unchanged tree yielded 203/913/1,
203/913/0, 203/913/0. The failing test's name was not captured (the
grep run that would have named it was itself green). The step-2 unit
added timing-sensitive live falsifiers (the error-storm bound: 5
facts, 4 messages, then quiet in ~1.5s; the armed-idle wake) — the
flake most plausibly lives there.

## Acceptance

- The flaky test is NAMED (rerun the gate in a loop until it fails,
  capture the report) and made deterministic — event-driven waits,
  never sleeps tuned to pass.
- Ten consecutive full-gate runs green on an unchanged tree.

## Resolved 2026-07-27 — named, and it was an equality racing a storm

Reproduced by looping the gate: the failing test is
`seon.cluster.armed-test/an-escaped-throwable-becomes-a-fact-and-a-message`
at the assertion `(= 1 (count told))`.

The cause was the suite's, not the system's, and it is worth stating
because the same mistake is easy to make again. The injected fault
STORMS until the recurrence fence bounds it — that is the designed
behaviour the very next assertion checks — so "root was told exactly
once" is true only in the instant between the first message commit and
the second. Whether the test saw that instant depended on how fast the
machine got from the fault to the wake to the next fault.

The fix is not a longer sleep and not a retry. The wait is now for the
MESSAGE THAT NAMES THIS FACT — an event the derivation can decide on —
and the question of how many there are belongs to the storm test, which
asserts an UPPER BOUND. An upper bound is monotone-safe under a
producer that is still producing; an equality is a race by
construction.

Evidence: ten consecutive `bin/test` runs green on the unchanged tree
(`tmp/gate-loop.log`).
