---
type: dashboard
---
# Seon System Map

> Infrastructure for AI agents to write reliable software.

## How to Use This Vault

**Orchestrator**: Read this dashboard to understand the system. Direct agents to specific component notes for implementation context. Track problems in `architecture/cleanup.md` and `architecture/next-state.md`.

**Agents**: Read the component note for your area before writing code. After making changes, update the relevant component note to match new reality.

### Rules for Updating Notes
1. **Component notes describe what IS** — no future state, no aspirational language
2. **After changing code**, update the component note: namespaces table, API surface, dependencies, refactoring opportunities
3. **After fixing a problem**, remove it from `cleanup.md` or `next-state.md`
4. **After adding a namespace**, add it to `namespaces.md` in the correct layer
5. **After adding/removing tests**, update `components/testing.md` coverage map
6. **Use `[[components/X]]` link format** — always full path, never short names
7. **Status vocabulary**: production, stable, design, deprecated, experimental

## Components (What Exists)

| Component | Status | Summary |
|-----------|--------|---------|
| [[components/schema-system]] | production | Source of truth for all types |
| [[components/database]] | production | Sole DB API, flow-serialized access |
| [[components/runtime]] | production | Instance tracking, flow registry, ID generation |
| [[components/system-lifecycle]] | production | Integrant boot, health checks, two-phase startup |
| [[components/context]] | production | Per-instance state atoms, persistence, SSE push |
| [[components/flow-topology]] | production | Async backbone, request/reply, infrastructure |
| [[components/harness]] | production | TCP proxy to agent JVMs |
| [[components/code-graph]] | production | Ingest + query + scanner, self-introspection |
| [[components/renderer]] | production | Specificity-based discovery, multi-format |
| [[components/namespace-lifecycle]] | production | Dynamic ns startup, ctx injection |
| [[components/web-layer]] | stable | HTTP, SSE, Datastar, Phosphor Terminal |
| [[components/agent-system]] | stable | AI providers, sessions, observatory |
| [[components/dev-tools]] | production | Hook, instrumentation, REPL helpers |
| [[components/testing]] | production | 70 test files, ~819 tests, REPL-first runner |

## Concepts (Patterns That Span Components)

| Concept | Status | Description |
|---------|--------|-------------|
| [[concepts/step-functions]] | production | 4-arity flow process pattern |
| [[concepts/renderer-discovery]] | production | Specificity-based function discovery |
| [[concepts/request-reply]] | production | Promise-based cross-ns calls |
| [[concepts/namespace-as-process]] | design | Every namespace as a flow process |
| [[concepts/subscriptions]] | design | Reactive push from data sources |
| [[concepts/feeds]] | design | Broadcast signals between namespaces |

## Architecture

- [[architecture/current-state]] — How the system works today
- [[architecture/cleanup]] — Known problems: dead code, duplication, naming, coupling
- [[architecture/next-state]] — Architectural gaps and missing capabilities

### Settled Decisions
- [[architecture/decisions/001-nippy-serialization]]
- [[architecture/decisions/002-absence-over-nil]]
- [[architecture/decisions/003-ref-type]]
- [[architecture/decisions/004-schema-unification]]

## Reference

- [[namespaces]] — Full inventory (101 namespaces with file paths and layer groupings)
