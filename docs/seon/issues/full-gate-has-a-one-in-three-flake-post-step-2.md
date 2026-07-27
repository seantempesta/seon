---
type: issue
status: open
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
