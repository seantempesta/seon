---
type: issue
status: open
severity: friction
tags: [issue, render, plan, contracts]
---

# Blocked plan values refuse pull during AI projection

Observed on default's `/ns/my.agents.juniper/debug`, 2026-09-08, adopted
source `6aa0eb45-8cdb-53bd-94f9-35e7292775e5`. The AI result for
`(my.plan/blocked)` contains two ExceptionInfo objects saying
`projection failed: seon.db/pull violated its contract (invalid-input): should be an integer`.
Current and ready plan queries render their item data correctly after the
nested source substitution fix. Cause is not yet established; this is an
observed rendering failure, not an attribution to another lane.
