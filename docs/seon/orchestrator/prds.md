---
type: orchestrator
tags: [index, prd]
status: active
---
# PRD Index

All feature specifications live in `docs/prds/`. Each directory contains a `prd.md` (or `design.md`), optional `decisions.md`, `notes.md`, and `research/`.

## Active PRDs

| PRD | Status | Summary | Components |
|-----|--------|---------|------------|
| [[prds/agent-repl-interface/prd]] | design | Agent REPL-only development with composable `*ctx*` atom | [[components/agent-system]] |
| [[prds/refinement/prd]] | active | Unified end-to-end system: flow topology, agent runtime, MCP REPL, Observatory (~98% done, auto-proxy pending) | [[components/flow-topology]], [[components/agent-system]] |
| [[prds/startup-reliability/prd]] | active | Zero-issue startup: fix cascading failures, agent OOM, MCP blocking | [[components/flow-topology]], [[components/agent-system]], [[components/database]] |
| [[prds/namespace-ui/prd]] | active | Namespace-as-app UI: introspection, renderers, Observatory, dashboard | [[components/web-layer]], [[components/renderer]] |
| [[prds/render-pipeline/prd]] | active | Wire spec-driven rendering end-to-end, replace manual renderer registration | [[components/renderer]], [[components/code-graph]] |
| [[prds/test-infrastructure/design]] | design | Unified test fixtures, data isolation, generative testing with shrinking | [[components/testing]] |
| [[prds/mcp-resilience/prd]] | active | MCP server resilience: async request processing (Phase 1 done, remaining paused) | [[components/agent-system]] |
| [[prds/datahike-migration/prd]] | draft | Replace Datalevin with embedded Datahike: per-namespace DBs, file backend default, single in-process writer, git-friendly time-travel | [[components/database]], [[components/schema-system]] |

## Completed PRDs

| PRD | Status | Summary | Components |
|-----|--------|---------|------------|
| [[prds/schema-unification/design]] | complete | Malli as single schema source: validation gate, pipeline tests, Nippy serialization, bridge | [[components/schema-system]], [[components/database]] |
| [[prds/graph-cleanup/prd]] | complete | Remove derived attrs from graph, unify resolution via Datalog queries | [[components/code-graph]] |
| [[prds/unified-flow/design]] | complete | core.async.flow as routing backbone: process isolation, inject/ping, topology | [[components/flow-topology]] |
| [[prds/flow-datalevin-writer/prd]] | complete | Single-writer flow for Datalevin writes via topology, separate JVM isolation | [[components/flow-topology]], [[components/database]] |
| [[prds/datalevin-migration/prd]] | complete | Datalevin database platform: server, connection manager, multi-DB isolation | [[components/database]] |
| [[prds/super-repl/prd]] | complete | Federated agent runtime with JVM pool (evolved into unified-flow + seon.repl) | [[components/agent-system]] |
| [[prds/stability-improvements/prd]] | complete | Health checks, error boundaries, automated cleanup for agent sessions | [[components/agent-system]] |

## Stalled / Not Started PRDs

| PRD | Status | Summary | Components |
|-----|--------|---------|------------|
| [[prds/spec-driven-rendering/prd]] | abandoned | Absorbed into render-pipeline and graph-cleanup PRDs | [[components/code-graph]], [[components/renderer]] |
| [[prds/polymarket-analysis/prd]] | active | Polymarket trader analysis tools (Stages 1-3 done, 4-7 remaining) | |
| [[prds/logging-system/prd]] | not started | Agent-safe log functions + web UI log viewer | [[components/web-layer]] |
| [[prds/data-viewer/prd]] | not started | Expand/collapse interaction for nested Clojure data in browser | [[components/web-layer]] |
| [[prds/schema-viewer/prd]] | not started | Web-based Malli schema browser with navigation | [[components/web-layer]], [[components/schema-system]] |
| [[prds/dashboard-polish/prd]] | not started | Information-dense terminal-style dashboard (Phosphor Terminal theme) | [[components/web-layer]] |
| [[prds/test-coverage-audit/findings]] | superseded | Audit findings from ml-options era — references `dsl/primitives.clj` which no longer exists | [[components/testing]] |
