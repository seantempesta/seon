---
type: issue
status: open
severity: friction
tags: [issue, test, fixture, turn]
---

# The history prompt fixture retracts required turn attributes

The 2026-09-16 paths-only fast run of `seon.render.web-test` reports five
failures in `next-turn-context-keeps-runtime-history-through-an-empty-turn-and-a-wake`.
Its reset helper transacts `[:db/retract turn :seon.turn/agent agent]` for
stored turns. Whole-entity validation now correctly refuses the resulting
turn with its required agent missing. Three refusal assertions fail; the
two downstream history-byte expectations then fail against retained history.

The writer's rejection is not a prompt-rendering failure. Repair the fixture
through the canonical turn/history owner rather than weakening write
validation or ignoring the refused transaction. Observed in the reference
repair's four-namespace run (160 tests, 1,729 assertions, 26 failures, no
errors); the new typed prompt-refusal HTTP test passed in that same run.
