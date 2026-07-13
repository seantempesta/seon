---
type: orchestrator
tags: [index, prd]
status: active
---

# PRD index

The target system is documented once in [[../architecture/architecture]]. A PRD
folder owns only the current work for one focused chunk: its `AGENTS.md`, its
roadmap, and dated supporting evidence.

## Current work

| Track | Status | Purpose | Entry points |
|---|---|---|---|
| runtime reliability | active | Collapse the proven prototype into one database server, one CLJS agent/web runtime, one database protocol, one reactive render path, one operator, and focused behavioral tests. | [[../../prds/runtime-reliability/AGENTS]], [[../../prds/runtime-reliability/roadmap]] |
| REPL autocomplete | active, separate lane | Database-derived completion for the agent REPL. | [[../../prds/repl-autosuggest/CLAUDE]], [[../../prds/repl-autosuggest/roadmap]] |
| Inspect AI evaluations | active support | The sole model/agent evaluation harness. | `src-inspect-ai/README.md` |
| embeddings | dormant | Optional Vertex/Proximum semantic retrieval on the JVM database server. | [[../../prds/embeddings/vertex-usage-reference-2026-06-25]] |
| diffusion dynamic context | experimental | Optional diffusion-model research; not a runtime-reliability completion gate. | [[../../prds/diffusion-dynamic-context/CLAUDE]] |

Finish and merge one roadmap chunk before promoting another branch to current.
Do not use an old PRD as present-tense runtime documentation.

## Archived work

The former embedded-JVM application, Integrant lifecycle, core.async flow
topology, JVM renderer/web server, nREPL tooling, and their test harnesses are
archived in git at `runtime-reliability-pre-refactor-2026-07-13`. Historical PRD
folders remain evidence of how the design evolved; they are not alternate
implementation tracks.
