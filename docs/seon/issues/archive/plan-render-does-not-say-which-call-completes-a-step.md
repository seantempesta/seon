---
type: issue
status: resolved
severity: friction
tags: [issue, my.plan, render, help, live-test]
created: 2026-09-15
---

# The plan render does not say which call completes a step; `current!` vs `complete!` cost five turns in two runs

## Observed (runs 4 and 5)

The model completed step 1 in its head at turn 4 and only called
`(my.plan/complete! …)` at turn ~15 after `(dir my.plan)`, `(doc
my.plan/current!)`, and a `current!` call on an already-read step; the
compact plan render (context-renders rule 5) kept the write examples
"once" and the model did not find them when it needed them. Also
`my.note/add!` refused for a missing `:my.note/id`; its example must show
the id.

## Wanted

- The plan render's one teaching line names the exact call for the
  current step: `(my.plan/complete! {:my.plan.item/id "juniper/read"})`
  — derived from the current step's id — and one line for `current!`
  ("select a step; completing clears the selection").
- `my.note/add!`'s docstring example includes `:my.note/id`; the
  executable-example regression already runs examples, so the example
  must be complete to pass.

## Resolution — 2026-09-15

`seon.plan/render-plan-ai` derives the exact `my.plan/complete!` call from
the current step and teaches that `current!` selects and completion clears
the selection. The HTML pair's source bytes are unchanged. my.plan's
docstrings agree; my.note/add!'s existing complete id-bearing example passes
the executable-example regression.

The selected isolated gate passed 38 tests / 547 assertions. The unchanged
live help-trial harness scored 12/12 on the new prompt. Exact bytes and
provider evidence are in the
[landing note](../../../prds/context-generation/research/contracts-and-plan-landing-2026-09-15.md).
