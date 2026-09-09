---
type: defect
status: open
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
