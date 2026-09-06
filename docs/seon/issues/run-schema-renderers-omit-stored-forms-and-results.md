---
type: issue
status: resolved
severity: friction
tags: [issue, render]
---

# Run schema renderers omit stored forms and results

## Evidence — 2026-09-06

The schema `:seon.cluster.run/run` selects `seon.cluster.run/render-ai` and
`render-html`, which display run status. The existing
`seon.render.transcript/render-run-ai` and `render-run-html` display a selected
run's stored sources and results but are not selected by that declaration.

Live debug inspection of entity 32255 on `lab-browser-0906` displayed only
"It completed." The same run has two stored sources and two admitted results.
Calling the transcript owner with its actual database, projection state, SCI
context and execution inputs returned both sources, the `result/e0` reference,
and the stored plan-item title. This is a wiring defect in the visualization,
not evidence that another evaluator or source-execution mechanism is needed.

## Required correction

Use the existing selected-run transcript functions as the run schema defaults,
preserve run status, and accept the normalized agent ref supplied by generic
render invocation. Verify selected-run isolation and pending versus settled
forms through focused tests and the real debug UI. Viewing the run must not
execute its forms or create new database facts.

## Resolution — 2026-09-06

`a34f91ae5` connects the existing transcript functions to the run schema,
preserves status, and resolves both pulled and normalized agent refs using the
handed database. The focused transcript-run test passed 89 assertions.
The fresh `lab-run-inspection` branch displayed bootstrap run 32019 through
both transcript projections. The browser positively checked stored `(help)`,
`(dir my.message)`, `(dir seon.db)` and their results, with zero JavaScript
errors. Database basis remained 536870952 with 11 evaluations before and after
inspection. The reproducible browser probe is
`docs/prds/context-generation/research/debug_stored_run_probe_2026_09_06.cjs`
(pass the debug URL and `bootstrap`). This proves display of an existing run;
it does not claim a new source submission interface.
