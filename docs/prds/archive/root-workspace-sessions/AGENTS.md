---
type: orchestrator
status: active
tags: [orchestrator, prd, web, agent, flow]
---

# Root workspace sessions — working context

This PRD owns the root agent's distinct system workspace and database-backed
per-browser-tab location. Read [[roadmap]], architecture UI/context/agent-
runtime/data-model, the `data-oriented-clojure`, `datahike`, `datastar-web-ui`,
and `browser-automation` skills, and the closest source authorities first.

Begin with exact dependency/source grounding for Datastar signals and morphs,
reitit routes, Datahike identity/refs, session storage semantics, and the
existing root/agent view implementation. Probe two-tab location transitions in
the live default system before editing.

Root is the one existing root agent with a dedicated layout over the same
blocks and render-unit engine. A tab location is one database entity, not an
agent-state atom or duplicated selection field. Do not add a second root model,
fleet cache, root-only feed, or client-authoritative session registry.

Research belongs in `research/`; current gaps, order, and proof belong in
[[roadmap]]. Implementation follows the settled render-unit contract.
