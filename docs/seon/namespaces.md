---
type: reference
status: active
updated: 2026-03-11
tags: [reference, index]
---
# Namespace Inventory

> Complete inventory of every namespace under `src/seon/`, grouped by architectural layer.

## Core Layer

Foundation: system lifecycle, configuration, schema, runtime registry.

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.core` | `src/seon/core.clj` | System entry point, two-phase startup, Integrant lifecycle | mature |
| `seon.config` | `src/seon/config.clj` | Aero config loading, Integrant ref readers | mature |
| `seon.system` | `src/seon/system.clj` | Integrant system map, init-key/halt-key! for all components | mature |
| `seon.system.config` | `src/seon/system/config.clj` | Malli schemas for Integrant component configurations | stable |
| `seon.runner` | `src/seon/runner.clj` | Thin -main wrapper delegating to seon.core | mature |
| `seon.schema` | `src/seon/schema.clj` | Global Malli schema registry (`register!`, introspection) | mature |
| `seon.runtime` | `src/seon/runtime.clj` | Unified runtime registry for all namespace instances (status, location, flows) | mature |
| `seon.logging` | `src/seon/logging.clj` | Centralized Timbre logging configuration, file appenders | mature |
| `seon.health` | `src/seon/health.clj` | System health checks (nREPL, pool, agents, resources) | stable |
| `seon.ctx` | `src/seon/ctx.clj` | Unified stateful context for namespace instances (atom + Datahike + SSE + Malli) | mature |
| `seon.ctx.history` | `src/seon/ctx/history.clj` | Pure diff utilities for ctx history (deltas, undo/redo) | stable |

## Database Layer

See [[seon/components/database]] for full component documentation.

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.db` | `src/seon/db.clj` | Sole database API — `transact!`, `query`, `pull-by-name`, flow routing | mature |
| `seon.db.schema` | `src/seon/db/schema.clj` | Malli-to-Datahike schema bridge, entity schema validation | mature |
| `seon.db.tx` | `src/seon/db/tx.clj` | Transaction metadata (timestamps, caller, source) for every write | stable |
| `seon.db.datahike.conn-process` | `src/seon/db/datahike/conn_process.clj` | Connection manager: caching, per-DB locking, Integrant component | mature |
| `seon.db.datahike.system` | `src/seon/db/datahike/system.clj` | `[JVM track — paused]` Datahike Integrant system wiring (embedded LMDB store) | mature |
| `seon.db.datahike.flow` | `src/seon/db/datahike/flow.clj` | Infrastructure flow writer/reader step-fns over Datahike | mature |
| `seon.db.datahike.schema` | `src/seon/db/datahike/schema.clj` | Bridge translating registered Malli schemas to Datahike schema tx | mature |
| `seon.db.datahike.tx-bus` | `src/seon/db/datahike/tx_bus.clj` | Transaction bus for tx-listener fan-out | mature |

## Flow Layer

core.async.flow routing backbone for cross-namespace calls and DB access.

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.flow.topology` | `src/seon/flow/topology.clj` | Reply router, `request!` entry point, topology wiring | mature |
| `seon.flow.msg` | `src/seon/flow/msg.clj` | Message envelope schemas for wire protocol (Nippy serialization) | mature |
| `seon.flow.status` | `src/seon/flow/status.clj` | Runtime status collection from registered flows (ping, throughput, errors) | stable |
| `seon.flow.trace` | `src/seon/flow/trace.clj` | Flow event tracing and persistence to Datahike for Observatory | stable |
| `seon.flow.pool` | `src/seon/flow/pool.clj` | Pre-warmed JVM pool for instant agent startup (LinkedBlockingQueue) | mature |
| `seon.flow.agent-runner` | `src/seon/flow/agent_runner.clj` | Agent JVM entry point (-main), nREPL server, Datahike connection | mature |
| `seon.flow.harness` | `src/seon/flow/harness.clj` | Orchestrator-side flow process for a namespace, routes calls to agent JVM via TCP | mature |
| `seon.flow.harness.proxy` | `src/seon/flow/harness/proxy.clj` | Proxy namespace generation for transparent cross-namespace calls in agent JVMs | stable |
| `seon.flow.harness.channel` | `src/seon/flow/harness/channel.clj` | Bidirectional TCP-to-core.async adapter (length-prefixed Nippy) | mature |
| `seon.flow.harness.bridge` | `src/seon/flow/harness/bridge.clj` | Agent JVM bridge step-fn: executes calls locally, reverse channel for remote calls | mature |

## Graph Layer

Knowledge graph: static analysis, ingestion, querying.

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.graph.scanner` | `src/seon/graph/scanner.clj` | Static source scanner (edamame) for schema/register! and def forms | mature |
| `seon.graph.extract` | `src/seon/graph/extract.clj` | Unified extraction pipeline: edamame + clj-kondo, merges results | mature |
| `seon.graph.analyzer` | `src/seon/graph/analyzer.clj` | Code analysis via clj-kondo (full project or incremental) | mature |
| `seon.graph.ingest` | `src/seon/graph/ingest.clj` | Ingest analysis data into Datahike (upsert + retract-stale pattern) | mature |
| `seon.graph.query` | `src/seon/graph/query.clj` | Datalog query API for knowledge graph (deps, call graph, discovery) | mature |
| `seon.graph.context` | `src/seon/graph/context.clj` | Topological context builder for AI agents (recursive pull, linearized text) | stable |

## Render Layer

Multi-format rendering system for displaying data in HTML, AI, and human contexts.

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.render` | `src/seon/render.clj` | Multi-format rendering with graph-based renderer resolution | mature |
| `seon.render.default-page` | `src/seon/render/default_page.clj` | Default page template for namespaces with ctx but no custom renderer | stable |
| `seon.render.code` | `src/seon/render/code.clj` | Code and documentation rendering from the knowledge graph | stable |
| `seon.render.example` | `src/seon/render/example.clj` | Example usage of multi-format rendering | experimental |

## Namespace System Layer

Dynamic namespace lifecycle, introspection, views, and HTTP routing.

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.ns.routes` | `src/seon/ns/routes.clj` | Namespace HTTP routes: page rendering, introspection, function calls, SSE | mature |
| `seon.ns.lifecycle` | `src/seon/ns/lifecycle.clj` | Dynamic namespace lifecycle: ctx creation, var injection, renderer resolution | mature |
| `seon.ns.introspect` | `src/seon/ns/introspect.clj` | Runtime namespace introspection (fns, vars, atoms, multimethods, requires) | stable |
| `seon.ns.view` | `src/seon/ns/view.clj` | Multimethod-based view system dispatching on [format view-type] | stable |
| `seon.ns.example` | `src/seon/ns/example.clj` | Example namespace demonstrating the view system | experimental |

## Web Layer

HTTP server, SSE, routing, UI components.

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.web.server` | `src/seon/web/server.clj` | HTTP server Integrant component (http-kit) | mature |
| `seon.web.routes` | `src/seon/web/routes.clj` | Map-based HTTP router, static file serving | mature |
| `seon.web.handlers` | `src/seon/web/handlers.clj` | HTTP request handlers (health, dashboard, API) | stable |
| `seon.web.html` | `src/seon/web/html.clj` | HTML templating via Chassis (compile-time Hiccup), Tailwind v4 | mature |
| `seon.web.sse` | `src/seon/web/sse.clj` | SSE implementation following Datastar SDK patterns (hash-based, Brotli) | mature |
| `seon.web.sse.flow` | `src/seon/web/sse/flow.clj` | Flow-based SSE infrastructure for code change propagation | stable |
| `seon.web.components` | `src/seon/web/components.clj` | Shared UI component library (Phosphor Terminal design system) | stable |
| `seon.web.namespace` | `src/seon/web/namespace.clj` | Namespace introspection web handlers (legacy, largely replaced by ns.routes) | deprecated |
| `seon.web.flows` | `src/seon/web/flows.clj` | Flow monitor page (/flows) — topology, metrics, errors | stable |
| `seon.web.agents` | `src/seon/web/agents.clj` | Agent observatory (/agents) — running + completed sessions | stable |
| `seon.web.logs` | `src/seon/web/logs.clj` | Log viewer state management and log fetching | stable |
| `seon.web.browser` | `src/seon/web/browser.clj` | REPL-to-browser execution bridge (JS eval via SSE) | stable |
| `seon.web.brotli` | `src/seon/web/brotli.clj` | Brotli compression utilities for streaming SSE | mature |
| `seon.web.tailwind` | `src/seon/web/tailwind.clj` | Tailwind CSS watcher Integrant component | stable |
| `seon.web.caddy` | `src/seon/web/caddy.clj` | Caddy reverse proxy Integrant component (HTTP/2 + TLS) | stable |
| `seon.web.reactive.demo` | `src/seon/web/reactive/demo.clj` | Demo page for reactive UI (instance-based, Datastar) | experimental |
| `seon.web.reactive.actions` | `src/seon/web/reactive/actions.clj` | Action resolution for reactive UI (namespace-qualified fn dispatch) | stable |
| `seon.web.reactive.transform` | `src/seon/web/reactive/transform.clj` | Hiccup transformation: clean syntax to Datastar-compatible HTML | stable |

## AI Layer

AI provider integrations, agent lifecycle, persistence.

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.ai` | `src/seon/ai.clj` | Base AI namespace: provider-agnostic schemas and entity persistence for sessions/messages | stable |
| `seon.ai.agent` | `src/seon/ai/agent.clj` | Provider-agnostic agent extension points, registry, observatory | mature |
| `seon.ai.claude` | `src/seon/ai/claude.clj` | Claude Code provider: schemas, lifecycle, message normalization | mature |
| `seon.ai.claude.sdk` | `src/seon/ai/claude/sdk.clj` | Claude Code CLI process management (spawn, communicate) | mature |
| `seon.ai.gemini` | `src/seon/ai/gemini.clj` | Native Clojure Gemini API client (text, grounding, code execution) | stable |
| `seon.ai.agent.log` | `src/seon/ai/agent/log.clj` | Structured per-agent logging to logs/agents/{session-id}.log | stable |
| `seon.ai.agent.views` | `src/seon/ai/agent/views.clj` | View renderers for agent data types (HTML, AI, human formats) | stable |

## Orchestrator/Agent Layer

Session management, agent environment, helpers.

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.orchestrator.session` | `src/seon/orchestrator/session.clj` | Agent session management: runtime + ctx + pool JVM lifecycle | mature |
| `seon.agent.helpers` | `src/seon/agent/helpers.clj` | SQL helpers for agents using implicit *ctx* | deprecated |
| `seon.agent.env` | `src/seon/agent/env.clj` | Agent environment toolkit: graph search, schema discovery, context persistence | stable |

## REPL Layer

REPL-specific utilities for interactive development.

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.repl` | `src/seon/repl.clj` | REPL form router: classifies forms, stores in Datahike, routes eval through flow topology | stable |
| `seon.repl.context` | `src/seon/repl/context.clj` | Context cockpit for AI agents (wraps graph context + render) | stable |
| `seon.repl.graduate` | `src/seon/repl/graduate.clj` | Namespace graduation: Datahike-stored forms to .clj file on disk | experimental |

## Dev Layer

Development tooling: hook, testing, linting, instrumentation.

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.dev.hook` | `src/seon/dev/hook.clj` | Main orchestrator for development feedback hook (PreToolUse/PostToolUse) | mature |
| `seon.dev.test` | `src/seon/dev/test.clj` | REPL-first test system returning structured data | mature |
| `seon.dev.test-select` | `src/seon/dev/test_select.clj` | Dependency-aware test selection via code graph | stable |
| `seon.dev.lint` | `src/seon/dev/lint.clj` | Shared Clojure validation: syntax check (edamame), clj-kondo lint | mature |
| `seon.dev.instrumentation` | `src/seon/dev/instrumentation.clj` | Malli function instrumentation with agent-friendly error messages | mature |
| `seon.dev.context` | `src/seon/dev/context.clj` | Agent context tracking for dev hook (edit events, rate-limited review) | stable |
| `seon.dev.analysis` | `src/seon/dev/analysis.clj` | Unified code analysis via clj-kondo (call graph, format) | stable |
| `seon.dev.codebase` | `src/seon/dev/codebase.clj` | Codebase introspection: file-to-namespace mapping, source reading | stable |
| `seon.dev.repair` | `src/seon/dev/repair.clj` | Delimiter repair for Clojure source (parinfer) | stable |
| `seon.dev.verify` | `src/seon/dev/verify.clj` | Test orchestration for dev hook (unit + generative) | stable |
| `seon.dev.review` | `src/seon/dev/review.clj` | AI code review via Gemini (advisory, non-blocking) | stable |
| `seon.dev.compliance` | `src/seon/dev/compliance.clj` | Convention compliance checking (missing schemas, positional args) | stable |
| `seon.dev.suggestions` | `src/seon/dev/suggestions.clj` | Symbol suggestion via Levenshtein distance ("Did you mean?") | stable |
| `seon.dev.clojure-replace` | `src/seon/dev/clojure_replace.clj` | Comment-aware s-expression match/replace via rewrite-clj | mature |
| `seon.dev.hook-test-ns` | `src/seon/dev/hook_test_ns.clj` | Test namespace for dev hook experimentation | experimental |
| `seon.dev.markdown` | `src/seon/dev/markdown.clj` | Pure markdown analysis: parse, validate, auto-fix docs files (Seon-native linter) | stable |

## Domain Layer

Application-specific namespaces (test cases for the infrastructure).

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.health.metrics` | `src/seon/health/metrics.clj` | Body composition metrics (BMI computation, categorization) | stable |
| `seon.health.workout` | `src/seon/health/workout.clj` | Workout tracking schemas and sample data | stable |
| `seon.health.workout.render` | `src/seon/health/workout/render.clj` | Render companion for workout tracking (page + item renderers) | stable |
| `seon.getting-started` | `src/seon/getting_started.clj` | Interactive 4-step walkthrough demonstrating living document UX | stable |
| `seon.getting-started.render` | `src/seon/getting_started/render.clj` | Page renderer for getting-started walkthrough | stable |

## UI Layer

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.ui.viewer` | `src/seon/ui/viewer.clj` | Value viewer with multimethod dispatch for Hiccup rendering | stable |

## Experimental / Scratch

| Namespace | File | Purpose | Status |
|-----------|------|---------|--------|
| `seon.claude.exploration` | `src/seon/claude/exploration.clj` | Protocol exploration for Claude Code CLI (research tool) | experimental |
| `seon.experimental.sci-exploration` | `src/seon/experimental/sci_exploration.clj` | Sci (Small Clojure Interpreter) sandboxed evaluation research | experimental |
| `seon.test.hello` | `src/seon/test/hello.clj` | Trivial namespace to verify isolated agent JVM works | experimental |
| `seon.hook-test-scratch` | `src/seon/hook_test_scratch.clj` | Scratch file for hook testing | experimental |

## Summary

- **Total namespaces**: 102
- **Mature**: 30 | **Stable**: 42 | **Experimental**: 8 | **Deprecated**: 2
- **Largest layers**: Web (18), Dev (16), Flow (10)
