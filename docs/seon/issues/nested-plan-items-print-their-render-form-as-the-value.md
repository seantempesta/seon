---
type: defect
status: resolved
severity: blocker
tags: [render, value-renderer, ai-projection, ugly-output, plan]
---

# A plan item nested in a result prints its render form, not its data

Observed by the orchestrator reading the default debug page at 19:20,
2026-09-08 (after `ff9507c1b` and `3fe446205`; at 17:52 the same block was
correct):

```
my.agents.juniper=> (my.plan/current)
#:seon.repl{:value (my.plan/format-item-ai (my.plan/item {:my.plan.item/id "juniper/render-plan"})), :ms 5}
```

At 17:52 the same form printed
`#:my.plan.item{:expected-result "…", :id "juniper/render-plan", :title "Render this plan clearly"}`.

`my.plan/render-item-ai` (`src/my/plan.clj:1024`) returns SOURCE — the
ruled shape for a block's AI projection, which a system turn evaluates.
Something in the value renderer / REPL shown-text path now substitutes
that source for a plan item VALUE nested inside an evaluation result and
prints it unevaluated. The agent would read a form where it expects data;
`(my.plan/current)` becomes useless to it.

Rule (PRD §13/§15, one clipping spot): the value renderer prints the DATA
of a nested value under the profile; a render function's source is only
ever evaluated as a top-level block form, never printed as a value. Find
the commit that introduced the substitution, remove it, and add the
regression: `(my.plan/current)` shown text contains the item's id and
title, never `format-item-ai`.

## Verification, 2026-09-08

The same new canonical Datahike/SCI regression passed at `22f6163e5` and
`77abbf43a` (one test, eight assertions each), and failed after `ff9507c1b`
with two assertions: the title was absent and `format-item-ai` was present.
These are the only two intervening commits touching the evaluation/render
seam. `ff9507c1b` introduced `shown-result`, passing the actual result through
`seon.render.value/render-ai`; that exposed nested AI producer selection.

The fix limits entity-producer selection inside the value printer to HTML.
AI recursively prints the returned data under its profile. Top-level block
source evaluation remains the render walk's responsibility.

Fast and path-isolated commit gates: 23 tests, 91 assertions, zero
failures/errors (one new test, 22 inherited). After default
converged to `6aa0eb45-8cdb-53bd-94f9-35e7292775e5`, its served plan AI block
contained the item id and title, with no `format-item-ai`. Page size was
158,305 bytes, response time 0.139602 seconds. Native browser inspection
refused with `cgWindowNotFound`; browser paint is not claimed.
