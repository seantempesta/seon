---
type: dashboard
tags: [dashboard, index]
status: active
---
# Seon System Map

> Infrastructure for AI agents to write reliable software.

## Active focus — the CLJS pod (agent-fsm)

Start here: the **canonical target-design set** in `docs/seon/architecture/`
([[architecture/architecture]] first), and the agent-fsm PRD
([[../prds/agent-fsm/roadmap]] = "we are here" / how the live system works today,
[[../prds/agent-fsm/CLAUDE]] = the runbook).

Seon runs two tracks. The **active** track is the CLJS pod (Node-hosted, port
7890), backed by the `wire-server` central datahike writer (file-backed datahike
at `data/clusters/default/store`); the pod does NOT embed datahike — it forwards
writes over a Unix socket to `wire-server`, and reads are local lazy db values.
The mental model: the agent's **loop is a function of the DB** and its **context
is a render of the DB**. The **paused** track is the JVM main-app (`./bin/run`,
nREPL 7888 / HTTP 8080, embedded in-process datahike LMDB, core.async flow) —
paused but not deleted, and the convergence target.

- **What runs today:** the CLJS pod (in `src/seon/*.cljs`) — the agent FSM
  (run/turn/loop with run-id fencing), block-rendered context, the Datastar SSE
  web UI, schema-first `seon.db` over the wire, and a boot-time
  program-graph index. Drive it from `http://localhost:7890/agents`.
- **In flight (agent-fsm):** agent-correctness on the shared default pod —
  presentation arc (block renderer, markdown/clojure tiles, root dashboard,
  tokens-not-chars), config-manifest seam (loadouts/routes/skills), and
  semantic recall behind `SEON_EMBED`. See [[../prds/agent-fsm/roadmap]] for
  the single "we are here".
- **Embeddings:** designed + proven against Vertex `gemini-embedding-2`, gated
  by `SEON_EMBED` (on by default in `bin/seon`, graceful no-op without creds);
  converging onto the live pipeline. Specs:
  [[../prds/embeddings/vertex-usage-reference-2026-06-25]].
- **Cross-platform / WASM (later):** Tauri 2 shell + WASM containment remain the
  designed delivery path; not the current build. Design: [[../prds/agent-runtime/research/wasm-spike-2026-05-20]].

The component tables below still describe the **JVM core** (Datahike + Integrant
+ flow topology) — `[JVM track — paused]`. Those `.clj` files live under
`src/seon/`; the live `.cljs` surfaces are documented in the CLJS-pod component
notes (web-ui, agent-system, reply-segmenter, loadable-skills) and in
[[../prds/agent-fsm/roadmap]].

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
| [[vision/m6-eval-pipeline|M6: The Eval Pipeline]] | prototyped | 2: REPL Agents |
| [[vision/m7-namespace-as-process|M7: Namespace as Living Process]] | prototyped | 3: Autonomous |
| [[vision/m8-autonomous-agents|M8: Autonomous Namespace Agents]] | prototyped | 3: Autonomous |

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
| [[components/web-ui]] | active | CLJS pod web lane — dashboard/roster/agent/debug pages, SSE morphing, findings pane, debug overlay |
| [[components/agent-system]] | stable | AI providers, sessions, observatory |
| [[components/agent-reply-segmenter]] | active | CLJS pod — LLM reply → form/prose/read entries (`parse-forms`) |
| [[components/loadable-skills]] | active | CLJS pod — `my.skills`, dial knowledge into agent ctx, drop when done |
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

- [[architecture/architecture]] — The canonical target-design map
- [[../prds/agent-fsm/roadmap]] — How the system works today ("we are here")
- [[architecture/decisions/]] — Settled architectural decisions (001-007)

## Orchestrator

- [[orchestrator/active]] — Current pipeline and session recovery
- [[orchestrator/prds]] — PRD index with status
- `orchestrator/issues/` — Individual issue notes (49 open, 2 resolved)

## Reference

- [[namespaces]] — Full inventory (102 namespaces with file paths and layer groupings)
- [[reference/]] — Datastar, Hyperlith, Gemini reference docs
