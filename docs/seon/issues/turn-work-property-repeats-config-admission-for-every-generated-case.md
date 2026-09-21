---
type: issue
status: open
severity: friction
tags: [issue, test, performance, seon.turn-work-test]
---

# Turn-work property repeats configuration admission for every generated case

The 2026-09-23 test-system measurement corrects the 05:00 ledger's attribution:
`test/seon/turn_work_test.clj` has no timed wait. Its
`situation-totality-property` calls the canonical database fixture and
`configure-cap!` separately for each of 200 generated cases (lines 483–494).
The 270-second value is the runner's ordinary worker exchange bound in
`src/seon/test/bounds.clj:11`, not an assertion that a test passed.

The HEAD-plus-paths run at `bf3b5df9f` began this property at
`2026-09-21T00:34:51.741145Z`. At `00:37:35Z`, approximately 163 seconds
later, its main thread was RUNNABLE in Datahike query cardinality estimation,
called through `seon.config/apply!`, `test-support/apply-config!`, and
`configure-cap!`. The lane stopped its own JVM; this is a lower-bound
observation, not a completed test duration or recorded result.

The six preceding successful turn-work tests each completed between 472 and
3903 ms. Another test refused its stale `:seon.error/kind` fixture write.
These observations do not establish the property alone caused the historical
worker expiry. They do falsify the claimed timed-wait implementation.

The repair must retain generated state coverage while moving invariant
configuration admission outside each trial, then measure the remaining
state transactions and reads. Do not lower the trial count, declare minutes
of allowance, or replace the database with a mocked derivation. The current
queue's terminal-event change does not repair this separate algorithm.

Evidence and verification boundaries:
[test-system landing](../../prds/steward-platform/research/test-system-fork-2026-09-23.md).
