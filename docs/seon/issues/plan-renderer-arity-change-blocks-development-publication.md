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

## Issues-sweep recurrence — 2026-09-08

Bare `bin/test` also refused during shared published base preparation in
`tmp/test-runs/run.c49nRe`, snapshot base
`e9e15a585727adfec7b781d28f5e3eca7035e8c9`. The only four blocking findings
are the same two-argument calls at lines 73, 196, 230, and 440; the renderer
accepts one argument. No tests ran. The lane stopped under its explicit
protected-boundary instruction. See the
[landing evidence](../../prds/context-generation/research/issues-sweep-landing-2026-09-08.md).
