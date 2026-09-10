---
type: research
status: working
tags: [agent-context, render, repl]
---

# Context page review — 2026-09-09

Authority: reread chart §9.4 and §14 through the roadmap. This checkout
references §14a in roadmap 1c but contains no §14a heading or body; the owner's
17:55 assignment supplies its five concrete requirements. The previously read
AGENTS and specialized skills still apply.

Default probe: PID 92059, JVM MCP with an explicit connection, help is a vector
of 13 lines, 2498 UTF-8 bytes in `pr-str`. No default write was made.

Dependency ledger: schema-selected terminal projection already lives in
`src/seon/render.clj/project-node*`; `source-producer?` derives source intent
from indexed return contracts. The value renderer currently admits only flat
errors to AI schema selection. Reuse that selection and exclude source-producing
pairs, so a returned help entity renders its lines while a pulled agent remains
data. `seon.repl` owns historical shown text. `seon.eval/of-agent` owns ordered
evaluation acquisition; the debug page must carry that result to its prompt pane.

Foreign boundary: the data lane holds `src/seon/agent.clj`,
`src/seon/cluster/agent.clj`, `src/seon/plan.clj`, `src/my/plan.clj` and the plan,
config schemas. Its landed identity entry is `(seon.id/id title 8)`; its landing
note confirms hashing `(pr-str data)`. Renderer hunks for held files belong here
until those files are released. No foreign session is operated.

RESET NEEDED with the data lane's schema batch. Never stop, restart, or refork
default. Paid trial remains `:unavailable` after the recorded OpenRouter HTTP 402;
this assignment makes no paid request.

## Help slice

`help` returns `#:seon.help{:lines [...]}`. Its declared pair renders bare
lines for AI and a `ul` for HTML. The identity source already performs `(help)`
first, so no change to the held identity file is needed. The value renderer now
consults the existing schema selection for returned maps while excluding
source-producing pairs by their indexed contracts; nested pulls remain data.

Real SCI on the canonical fixture passed **26 tests / 167 assertions** in the
fast loop and **26 / 171** isolated. The returned-error regression separately
passed **1 / 14**, retaining its schema-first error behavior. Platform:
**83 / 490**, zero failures/errors. The scratch fixture's actual stored `(help)`
result is **2432 UTF-8 bytes**, exactly equal to `render-help-ai` of its returned
map; [value, shown text, and HTML](context_page_help_2026_09_09.edn).

Default's publication attempt saw changing source during analysis and refused;
it still returned the old vector. The scratch checkout isolates HEAD `24adad072`
plus these paths. Its first settings probe saw a newly loaded function without
its program row, so its zero-argument call correctly refused; scratch development
adoption must finish before the final reseed and capture. This is not a prompt
pass or a reason to operate default.

## Turns and returned data

Turn concern pairs now emit no AI text and render HTML headers only: opening,
trigger, evaluation count, and reply. A blob-backed reply shows its stored ref
instead of claiming no reply. The prompt pane renders each current evaluation
through `seon.repl/render-html`, without repeated floor wrappers sharing one id.

The browser falsified the first current-turn selector: at the observed scratch
basis, turn `2b4e991b596f` opened at 00:09:43 and committed at 536871024 with
eight evaluations, while `93959e14d8d4` opened at 00:09:42 and committed later,
at 536871026, with none. Commit order selected the wrong turn. The selector now
orders by `opened-at`; the canonical regression deliberately commits an older
opening later and also verifies an actually newer empty turn. Aggregate count
returns nil for zero matching evaluations; the known turn's header shows `0`.

The full prompt also exposed schema selection inside a returned settings vector:
the provider map matched both the settings source renderer and missing-model
prose. [The direct-map collision was separately reproduced](context_page_render_collision_2026_09_09.edn).
AI selection now applies to the returned entity and flat errors; nested data
retains its shape. An input accepted by a source-generating block stays data even
when it also matches a prose candidate. The regression covers the vector and
the direct provider map, alongside nested pull preservation and returned errors.

Native Chrome is available even though the browser connector inventory is empty.
On the scratch debug page, its accessibility tree and screenshot showed
`Turns (4)`, the latest two-evaluation turn's headers, a blank concern AI side,
and `Context now` containing the two actual evaluations. The first screenshot
also exposed an absent generated CSS asset in the throwaway checkout;
`bin/css` built it in 89 ms. No default tab was changed; the probe uses a new tab.

Final combined fast gate: **29 tests / 205 assertions**. Isolated gate:
**29 / 209**. Platform: **83 / 490**, all green. These isolated gates use
HEAD `dbec8be7e`, which landed the data lane's plan/component slice during this
review, plus only this slice's named render/test paths. The last platform run
used one worker; all runs capped `SEON_TEST_WORKERS` at three. The settings/plan
patch still applies cleanly after that commit; the shared writer files remain
untouched as assigned. RESET NEEDED includes `dbec8be7e`.
