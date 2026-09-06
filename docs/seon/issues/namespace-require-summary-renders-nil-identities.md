---
type: issue
status: open
severity: friction
tags: [issue, render, wave/render-producers]
---

# Render actual namespace identities in dependency summaries

On 2026-09-06, the live `lab-browser-0906` page at
`/ns/seon.flow/debug` displayed repeated `[nil :as nil]` entries in the
namespace dependency summary. Removing rendering cuts exposed the complete
output, including these incorrect summaries. Function summaries are present.

The observation comes from the actual browser and hot-loaded JVM code,
not a fresh cluster publication. The reproducible browser probe is
`docs/prds/context-generation/research/debug_complete_render_probe_2026_09_06.cjs`;
the captured screenshot is `tmp/debug-complete-render-2026-09-06.png`.

The owning code is `src/seon/render/ns.clj`. Trace its acquired dependency
refs against the same immutable database before deciding whether the defect
is acquisition or rendering. Acceptance: the complete live summary names its
real required namespaces and aliases, and reports unavailable identities
explicitly. This specialized renderer repair is deferred by the owner's
visualization-first instruction.
