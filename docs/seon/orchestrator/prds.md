---
type: orchestrator
---
# PRD Index

All feature specifications live in `docs/prds/`. Each directory contains a `prd.md` (or `design.md`), optional `decisions.md`, `notes.md`, and `research/`.

## Active PRDs

| PRD | Status | Summary | Components |
|-----|--------|---------|------------|
| [[prds/agent-repl-interface/prd]] | design | Agent REPL-only development with composable `*ctx*` atom | [[components/agent-system]], [[components/repl]] |
| [[prds/refinement/prd]] | active | Unified end-to-end system: flow topology, agent runtime, MCP REPL, Observatory | [[components/flow-system]], [[components/agent-system]], [[components/repl]] |
| [[prds/startup-reliability/prd]] | active | Zero-issue startup: fix cascading failures, agent OOM, MCP blocking | [[components/flow-system]], [[components/agent-system]], [[components/database]] |
| [[prds/namespace-ui/prd]] | active | Namespace-as-app UI: introspection, renderers, Observatory, dashboard | [[components/web-ui]], [[components/render-system]] |
| [[prds/render-pipeline/prd]] | active | Wire spec-driven rendering end-to-end, replace manual renderer registration | [[components/render-system]], [[components/graph]] |
| [[prds/test-infrastructure/design]] | design | Unified test fixtures, data isolation, generative testing with shrinking | [[components/test-system]] |
| [[prds/mcp-resilience/prd]] | stalled | MCP server resilience: async request processing (Phase 1 done) | [[components/repl]] |

## Completed PRDs

| PRD | Status | Summary | Components |
|-----|--------|---------|------------|
| [[prds/schema-unification/design]] | complete | Malli as single schema source: validation gate, pipeline tests, Nippy serialization, bridge | [[components/schema-system]], [[components/database]] |
| [[prds/graph-cleanup/prd]] | complete | Remove derived attrs from graph, unify resolution via Datalog queries | [[components/graph]] |
| [[prds/unified-flow/design]] | complete | core.async.flow as routing backbone: process isolation, inject/ping, topology | [[components/flow-system]] |
| [[prds/flow-datalevin-writer/prd]] | complete | Single-writer flow for Datalevin writes via topology, separate JVM isolation | [[components/flow-system]], [[components/database]] |
| [[prds/datalevin-migration/prd]] | complete | Datalevin database platform: server, connection manager, multi-DB isolation | [[components/database]] |
| [[prds/super-repl/prd]] | complete | Federated agent runtime with JVM pool (evolved into unified-flow + seon.repl) | [[components/agent-system]], [[components/repl]] |
| [[prds/stability-improvements/prd]] | complete | Health checks, error boundaries, automated cleanup for agent sessions | [[components/agent-system]] |

## Stalled / Not Started PRDs

| PRD | Status | Summary | Components |
|-----|--------|---------|------------|
| [[prds/spec-driven-rendering/prd]] | stalled | Datalevin-backed code index + automatic render function discovery (~Phase 1-3 partial) | [[components/graph]], [[components/render-system]] |
| [[prds/polymarket-analysis/prd]] | stalled | Polymarket trader analysis tools (Stages 1-3 done, 4-7 remaining) | [[components/trading]] |
| [[prds/logging-system/prd]] | not started | Agent-safe log functions + web UI log viewer | [[components/web-ui]] |
| [[prds/data-viewer/prd]] | not started | Expand/collapse interaction for nested Clojure data in browser | [[components/web-ui]] |
| [[prds/schema-viewer/prd]] | not started | Web-based Malli schema browser with navigation | [[components/web-ui]], [[components/schema-system]] |
| [[prds/dashboard-polish/prd]] | not started | Information-dense terminal-style dashboard (Phosphor Terminal theme) | [[components/web-ui]] |
| [[prds/test-coverage-audit/findings]] | complete | Audit findings: critical gaps in financial calculations, DSL executor | [[components/test-system]] |
