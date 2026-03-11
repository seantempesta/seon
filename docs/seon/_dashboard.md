---
type: dashboard
---
# Seon System Map

> Infrastructure for AI agents to write reliable software.

## How to Use This Vault

**Orchestrator**: Start here. Read [[orchestrator/active]] for current pipeline. Issues are in [[orchestrator/issues/]]. PRD index at [[orchestrator/prds]].

**Agents**: Read the component note for your area before writing code. After making changes, update the relevant component note to match reality.

### Rules for Updating Notes
1. **Component notes describe what IS** — no future state, no aspirational language
2. **After changing code**, update the component note: namespaces table, API surface, dependencies
3. **After fixing an issue**, update its status in `orchestrator/issues/`
4. **After adding a namespace**, add it to [[namespaces]] in the correct layer
5. **After adding/removing tests**, update [[components/testing]] coverage map
6. **Use `[[components/X]]` link format** — always full path from `seon/`, never short names
7. **Status vocabulary**: production, stable, design, deprecated, experimental

## Vision & Milestones

- [[vision/index]] — Project thesis and aspirational capabilities

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
| [[concepts/socratic-agents]] | experimental | Agents that think before acting |
| [[concepts/namespace-stewardship]] | design | Agent-driven namespace auditing |

## Architecture

- [[architecture/overview]] — How the system works today
- [[architecture/decisions/]] — Settled architectural decisions (001-006)

## Orchestrator

- [[orchestrator/active]] — Current pipeline and session recovery
- [[orchestrator/prds]] — PRD index with status
- [[orchestrator/issues/]] — Individual issue notes (36 open issues)

## Reference

- [[namespaces]] — Full inventory (101 namespaces with file paths and layer groupings)
- [[reference/]] — Datastar, Hyperlith, Gemini, ThetaData reference docs
