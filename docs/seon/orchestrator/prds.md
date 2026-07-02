---
type: orchestrator
tags: [index, prd]
status: active
---

# PRD Index

All feature specifications live in `docs/prds/`. Each active PRD folder carries a
`CLAUDE.md` (the always-in-context runbook), a roadmap or prd, and `research/`.
The **canonical target-design docs** (architecture, data-model, agent-runtime,
ui, toolkit, library-grounding, observability) live in
`docs/seon/architecture/` — PRD folders own the "we-are-here" roadmaps, not the
target design.

## Active tracks

| Track | Status | Summary | Entry points |
|-------|--------|---------|--------------|
| agent-fsm | active | The core program: config-driven agent init, the data-FSM loop, blocks/renders/tiles, the `my.*` toolkit. Target design is the canonical set in `docs/seon/architecture/`; the sole "we-are-here" doc is the roadmap. | [[prds/agent-fsm/CLAUDE]], [[prds/agent-fsm/roadmap]], [[architecture]] |
| diffusion-dynamic-context | active | Diffusion LLM as a dynamic-context provider: unified `refine` oracle, control loop, worker clamp/infill/renoise/KV-reuse. Offline-proven both sides; deploy+measure remain (owner-driven). | [[prds/diffusion-dynamic-context/CLAUDE]], [[prds/diffusion-dynamic-context/north-star]] |
| embeddings | dormant | Semantic recall on Vertex `gemini-embedding-2` + Proximum HNSW on the wire-server. Gated behind `SEON_EMBED`. | [[prds/embeddings/vertex-usage-reference-2026-06-25]] |
| gym-v2 | active | Agent exercise harness: long-term planning + database-memory drives, scored without coaching. | [[prds/gym-v2/design]] |
| inspect.ai bridge | research | Benchmark bridge driving seon agents from Inspect AI; spike lives under agent-fsm research. | [[prds/agent-fsm/research/inspect-bridge-spike/init-agent-unification-spec-2026-07-01]] |

## Paused (JVM-track era)

The embedded-datahike JVM main app is the paused track; its PRDs resume with it.

| PRD | Status | Summary |
|-----|--------|---------|
| [[prds/refinement/prd]] | paused | Unified end-to-end JVM system: flow topology, agent runtime, MCP REPL, Observatory (~98% done when paused). |
| [[prds/startup-reliability/prd]] | paused | Zero-issue JVM startup: cascading failures, agent OOM, MCP blocking. |
| [[prds/namespace-ui/prd]] | paused | Namespace-as-app UI on the JVM track. The design system (Phosphor Terminal) remains the live UI reference. |
| [[prds/render-pipeline/prd]] | paused | Spec-driven rendering on the JVM track; superseded in spirit by the block/render model in [[ui]]. |
| [[prds/mcp-resilience/prd]] | paused | MCP server resilience (Phase 1 done). |
| [[prds/agent-repl-interface/prd]] | paused | Agent REPL-only development with composable `*ctx*` atom. |
| [[prds/test-infrastructure/design]] | paused | Unified JVM test fixtures + generative testing. |

## Completed

| PRD | Status | Summary |
|-----|--------|---------|
| [[prds/datahike-migration/prd]] | complete | Datalevin → embedded Datahike; Phase 3 demo shipped 2026-04-25. |
| [[prds/schema-unification/design]] | complete | Malli as single schema source: validation gate, Nippy serialization, bridge. |
| [[prds/graph-cleanup/prd]] | complete | Removed derived attrs from the graph; resolution via Datalog. |
| [[prds/unified-flow/design]] | complete | core.async.flow as the JVM routing backbone (superseded on the pod — see ADR 005 banner). |
| [[prds/flow-datalevin-writer/prd]] | complete | Single-writer flow for Datalevin writes (Datalevin since removed). |
| [[prds/datalevin-migration/prd]] | complete | Datalevin platform (since replaced by Datahike). |
| [[prds/super-repl/prd]] | complete | Federated agent runtime with JVM pool (evolved into unified-flow + seon.repl; separate-JVM isolation superseded — see ADR 006 banner). |
| [[prds/stability-improvements/prd]] | complete | Health checks, error boundaries, session cleanup. |

## Abandoned / superseded

| PRD | Status | Summary |
|-----|--------|---------|
| [[prds/spec-driven-rendering/prd]] | abandoned | Absorbed into render-pipeline and graph-cleanup. |
| [[prds/test-coverage-audit/findings]] | superseded | ml-options-era audit; references code that no longer exists. |
| [[prds/logging-system/prd]] | not started | Agent-safe log fns + web log viewer. |
| [[prds/data-viewer/prd]] | not started | Expand/collapse for nested Clojure data in browser. |
| [[prds/schema-viewer/prd]] | not started | Web-based Malli schema browser. |
| [[prds/dashboard-polish/prd]] | not started | Terminal-style dashboard polish. |
