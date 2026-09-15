---
type: issue
status: resolved
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
[landing evidence](../../../prds/context-generation/research/issues-sweep-landing-2026-09-08.md).

## Resolution (2026-09-15 triage)

surface: adoption-publication

Commit `080628130` corrected the callers. At triage HEAD `131fa2a562b1d81b800329cc9b785c42cf4e9167`, `test/my/plan_test.clj:77,228,262,469` each passes one `render-view` argument. The owner is now `src/seon/plan.clj:1254–1258`, with one `unit` argument; `my.plan` is the thin public API. Verified with `git show HEAD:test/my/plan_test.clj` and `git show HEAD:src/seon/plan.clj`. The four invalid-arity calls described here cannot occur in this source. This is source/caller proof, not a claim that every development publication succeeds.
