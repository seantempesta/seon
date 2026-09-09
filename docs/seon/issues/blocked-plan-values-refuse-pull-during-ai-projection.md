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

Re-observed after fresh turn-rename construction on 2026-09-09 at
`http://127.0.0.1:7872/ns/my.agents.juniper/debug`: the same two
`seon.db/pull` integer-contract refusals appear in `(my.plan/blocked)`.
The page itself returns HTTP 200; this retained rendering defect is not a
claim that every result on the page is correct. Source and reset evidence
are in the turn rename landing note.

Reproduced during loop-proof, 2026-09-09: the live system-turn preview on
default stores two ExceptionInfo-shaped values for `(my.plan/blocked)`, each
naming the same `seon.db/pull` integer-contract refusal. This is unreadable
shown text, not a successful plan observation. No new cause is attributed.
