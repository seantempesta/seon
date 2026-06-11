---
type: dashboard
tags: [dashboard, index]
status: active
---
# Seon System Map

> Infrastructure for AI agents to write reliable software.

## Active focus — agent runtime (2026-05-23)

Branch: **`feature/agent-runtime`**. PRD root: [[../prds/agent-runtime/README]].

- **What runs today:** the V0 CLJS pod (Node-hosted, in `src/seon/*.cljs`). Phase 1 substrate hardening shipped + Phase 2 test capture as data + Phase A HTTPS allowlist + symbol-lookup refactor (`seon.eval/lookup-value` + render fns renamed `*-render`). See `docs/seon/pod/` and [[../prds/agent-runtime/STATUS]].
- **MVP track next:** v1 spec implementation — sessions/turns/ctx composition, run-turn!, six default section fns, eval-replay resume. Design: [[../prds/agent-runtime/v1]].
- **Platform track next:** Phase 2.5 substrate primitives (D13 dynvar probe, `seon.id`, history flip, tx-meta auto-merge) in flight; Phase 3 WASM-Tauri containment alpha-blocking. Phases 8–10 (Tauri shell, LAN/Tailscale remote, mobile pod-in-webview) designed.
- **Cross-platform delivery:** [[../prds/agent-runtime/platform]] Phases 8–10 lay out write-once-run-everywhere via Tauri 2 (macOS/Win/Linux/iOS/Android). Mobile path uses pod-in-webview (Apple's WKWebView JIT exception) to sidestep wasmtime's iOS JIT restriction.
- **WASM containment:** Authoritative design at [[pod/wasm-spike-2026-05-20]]; pod-host workspace at `pod-host/wasm-tauri/`.
- **Dev loop:** [[../cljs-dev-loop]] (V0 / pre-WASM). WASM dev loop is documented in the spike doc.

The component tables below describe the **JVM substrate** (Datahike + Integrant + flow topology). That substrate is paused as the active feature track but not deleted — its files still live under `src/seon/*.clj`. The active development is the CLJS pod + Phase 3 WASM-Tauri work, not the JVM seat.

## How to Use This Vault

**Orchestrator**: Start here. Read [[orchestrator/active]] for current pipeline. Issues are in `orchestrator/issues/`. PRD index at [[orchestrator/prds]].

**Agents**: Read the component note for your area before writing code. After making changes, update the relevant component note to match reality.

### Rules for Updating Notes

1. **Component notes describe what IS** — no future state, no aspirational language
2. **After changing code**, update the component note: namespaces table, API surface, dependencies
3. **After fixing an issue**, update its status in `orchestrator/issues/`
4. **After adding a namespace**, add it to [[namespaces]] in the correct layer
5. **After adding/removing tests**, update [[components/testing]] coverage map
6. **Use `components/X` link format** — always full path from `seon/`, never short names
7. **Status vocabulary**: production, stable, design, deprecated, experimental

## Vision & Milestones

[[vision/index]] — Project thesis and aspirational capabilities

| Milestone | Status | Bootstrap |
|-----------|--------|-----------|
| [[vision/m1-reliable-runtime|M1: Reliable Runtime]] | partial | Foundation |
| [[vision/m2-trustworthy-data|M2: Trustworthy Data]] | partial | Foundation |
| [[vision/m3-convention-uniformity|M3: Convention Uniformity]] | in-progress | 1: Claude Code |
| [[vision/m4-discoverable-codebase|M4: Discoverable Codebase]] | partial | 1: Claude Code |
| [[vision/m5-observable-system|M5: Observable System]] | partial | 1: Claude Code |
| [[vision/m6-eval-pipeline|M6: The Eval Pipeline]] | not-started | 2: REPL Agents |
| [[vision/m7-namespace-as-process|M7: Namespace as Living Process]] | not-started | 3: Autonomous |
| [[vision/m8-autonomous-agents|M8: Autonomous Namespace Agents]] | not-started | 3: Autonomous |

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
| [[components/web-brand]] | stable | Downstream brand surface — env-synced rows, titles/h1/theme, CSS hook |
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
| [[concepts/progressive-enhancement]] | design | Smart defaults, agents add specificity over time |
| [[concepts/socratic-agents]] | experimental | Agents that think before acting |
| [[concepts/namespace-stewardship]] | design | Agent-driven namespace auditing |

## Architecture

- [[architecture/overview]] — How the system works today
- [[architecture/decisions/]] — Settled architectural decisions (001-007)

## Orchestrator

- [[orchestrator/active]] — Current pipeline and session recovery
- [[orchestrator/prds]] — PRD index with status
- `orchestrator/issues/` — Individual issue notes (49 open, 2 resolved)

## Reference

- [[namespaces]] — Full inventory (102 namespaces with file paths and layer groupings)
- [[reference/]] — Datastar, Hyperlith, Gemini reference docs
