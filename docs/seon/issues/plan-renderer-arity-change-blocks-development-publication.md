---
type: issue
status: open
severity: blocker
tags: [issue, wave/contract-gate]
---

# Plan renderer and its tests disagree during development publication

Observed by turn-cut on 2026-09-08. The edit hook rejected static publication
before adopting the read-evidence work. `src/my/plan.clj` changes
`my.plan/render-plan-html` from two positional arguments to one
`:my.plan/component-view` argument. `test/my/plan_test.clj` still calls it
with two arguments at lines 73, 196, 230, and 440.

All four are `:invalid-arity` errors in `logs/current-source-failure.log`.
These source changes were already in flight outside turn-cut. That lane
preserved the files and removed its incomplete evidence changes, following
its explicit stop-at-foreign-breakage rule.

Acceptance: the renderer's callers agree with its final contract and
`init --dev default` publishes and adopts the current program.
