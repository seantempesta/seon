---
type: issue
status: open
severity: friction
tags: [issue, render, agent, wave/render-producers]
---

# Runtime component still renders as an attribute map

Data lane observation, 2026-09-09: the fresh scratch Juniper namespace route
returned HTTP 200, 20,499 bytes. The runtime component displayed the default
attribute-map representation: agent ref, trigger ref, and a vector of turn
identity maps. The new facts are present, but this is not the chart's intended
state line and turn headers. Browser paint could not be observed because CUA
listed no browser surfaces and native Chrome returned `cgWindowNotFound`.

The component schema is `resources/seon/schemas/seon.runtime.edn`; the
existing turn header pair is `src/seon/render/transcript.clj`'s
`render-history-ai` / `render-history-html`. Chart roadmap 9–12 owns the new
component's block pair. Connect the existing functions to the runtime concern
when that lane implements the chart's block functions; do not add a second
turn history or copy runtime state onto the agent.

Evidence: `docs/prds/context-generation/research/data-lane-landing-2026-09-09.md`.
