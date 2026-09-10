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
