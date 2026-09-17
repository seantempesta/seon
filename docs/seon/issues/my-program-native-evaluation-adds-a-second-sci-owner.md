---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, test, program-graph, sci]
---

# The SCI ownership census now finds my.program/native!

The path-limited `seon.fn-test` fast run at HEAD `5160057f6` finished with
59 tests, 440 assertions, one failure and no errors. The changed
`publication-refuses-a-required-artifact-load-finding` regression passed.
The failure was `sci-evaluation-has-one-first-party-owning-namespace`:
expected `#{"seon.sci.eval"}`, observed `#{"my.program" "seon.sci.eval"}`.
Its reported callers include `my.program/native!`; that function calls SCI
directly at `src/my/program.clj:442`. This is outside the launcher guardrail
assignment, and the source was not changed by that run.

The evaluation owner must decide whether this direct execution belongs in
`seon.sci.eval` or whether the ownership invariant has intentionally changed.
Fix the owning seam and its census together; do not silently exclude this
caller from the graph query.
