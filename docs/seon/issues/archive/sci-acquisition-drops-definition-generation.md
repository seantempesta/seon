---
type: issue
status: resolved
severity: friction
tags: [issue, sci, test]
---

# SCI acquisition drops definition generation

In the 2026-09-23 focused test-system run, both
`selection-is-one-function-on-both-hosts` and
`fileless-sci-tests-use-the-same-selection` evaluated their `deftest` successfully:
the complete evaluation maps contained `:seon.eval/outcome :ok` and the expected
Var, but no `:seon.program/row`. The next call to `program/declaration-row`
therefore refused nil. The original label "SCI contract error" hid this cause.

`seon.sci.eval/acquire!` reset the receiving context's environment to the
unforked generated base. That removed the generation used by `turn-interns`
to recognize definitions. SCI `fork` adds that generation
(`reference-code/sci/src/sci/core.cljc:345–352`), and `eval-def` stamps it
on defined Vars (`reference-code/sci/src/sci/impl/evaluator.cljc:25–49`).
Acquisition now installs the environment of a fork of the generated base.

The existing fileless declaration assertions passed after this one-line
repair. Both-host selection also reached execution and passed its semantic
assertions after the independent reporter projection correction. Run
`ac51f1a8b816` still failed their duration bounds: **58060.672041 ms** and
**13030.503541 ms** respectively. This resolves the missing-row correctness
defect, not those acquisition/setup costs. Fixtures now surface a missing
program row with its complete evaluation evidence before calling the row
constructor.
