# PRD: Namespace UI - Vision Document

**Status:** Active Development
**Priority:** High
**Branch:** feature/namespace-ui

---

## Phase Summary

| Phase | Goal | Status | PRD |
|-------|------|--------|-----|
| 0 | Cleanup + Dashboard | Done | - |
| 0.5 | Live Agent Widgets | Done | - |
| 1a | Render convention + view system | Done | - |
| 1b.1-7 | Observatory tool renderers, hover, pairing | Done | [`observatory-polish`](../observatory-polish/prd.md) |
| 1b.8 | Truncated log view (4-line max) | Done | [`truncated-log-view`](../truncated-log-view/prd.md) |
| 1b.9 | Namespace render toggle | Done | [`namespace-render-toggle`](../namespace-render-toggle/prd.md) |
| 1b.10 | **Datalevin-based Observatory** | **Active** | [`observatory-xtdb`](../observatory-xtdb/prd.md) |
| 1c | Agent robustness | Done | [`stability-improvements`](../stability-improvements/prd.md) |
| 2 | Expand/collapse + styling | Pending | [`data-viewer`](../data-viewer/prd.md) |
| 3 | Malli schema viewer | Pending | [`schema-viewer`](../schema-viewer/prd.md) |
| 4 | Datalevin entity browser | Pending | - |
| 5 | Live atom updates | Pending | [`live-updates`](../live-updates/prd.md) |
| 6 | Dashboard polish | Pending | [`dashboard-polish`](../dashboard-polish/prd.md) |
| 7 | Custom renderers | Pending | [`custom-renderers`](../custom-renderers/prd.md) |
| 8 | Flow-routed namespace rendering | Pending | - |

**Implementation details live in focused PRDs.** This document defines vision, philosophy, and shared context.

---

## Vision

Every Clojure namespace in Seon becomes a viewable, introspectable "app". The system provides:

1. **Default renderer** - Automatically shows functions, vars, atoms, schemas, and DB entities via introspection
2. **Custom renderers** - Namespaces can override to build tailored UIs for specific domains
3. **Live tiles** - Dashboard where each namespace is a configurable tile
4. **Session model** - View namespaces read-only, or launch sessions for live interaction

Think of Seon as an OS where namespaces are apps:
- Browse available namespaces from the dashboard
- Open namespace views (read-only introspection)
- Launch sessions for live REPL + DB access
- Direct the orchestrator to create new namespaces and watch agents build them live

**All introspection is runtime** - no hardcoded table names, schema keys, or function names.

**Flow-routed rendering:** In the flow architecture, agent JVMs return domain data maps (not hiccup). The orchestrator resolves the appropriate renderer via spec-driven resolution (see [`spec-driven-rendering`](../spec-driven-rendering/prd.md)), caches the rendered output, and serves it via SSE. This means agent JVMs stay free of UI dependencies -- the orchestrator owns all rendering.

---

## Design Philosophy: The Terminal Soul

> "The screen is a window into a living computational process."

Seon's UI is rooted in McCarthy's vision of Lisp and the golden age of computing. This isn't "dark mode with code" - it's a philosophy.

### Core Principles

1. **Liveness** - The UI reflects actual system state. Data flows, states change, indicators pulse. You're looking at a breathing organism, not a static page.

2. **Terminal Heritage** - Monospace typography everywhere. Information density. Fixed-width columns. The aesthetic of Symbolics Lisp Machines and Emacs, modernized.

3. **Warm Phosphor** - Not cold corporate blues. Amber/cream tones inspired by vintage CRT monitors - warm, humane, inviting long sessions.

4. **Every Pixel Earns Its Place** - No padding for "breathing room." No decorative cards. Tables over cards. Information density is a feature.

5. **Code as UI, UI as Code** - In a Lisp system, there's no distinction between data and interface. The namespace view IS the namespace.

### Design System

See [`design-system.md`](design-system.md) for:
- Color palette (Phosphor theme)
- Typography scale (JetBrains Mono, 11px primary)
- Spacing system (4px base unit)
- Component patterns (log viewer, status indicators, tables)

---

## Goals

1. **Every namespace viewable** - Any `seon.*` namespace can be inspected via web UI
2. **Zero-config default** - Useful view without any namespace modifications
3. **Agent-customizable** - Agents can override rendering for domain-specific UIs
4. **Session-aware** - Read-only introspection OR live session with ctx + DB
5. **Responsive** - Works at 50% screen width for split-screen usage

---

## Problem Statement

Currently, understanding a namespace requires:
- Reading source code directly
- Using REPL to inspect vars/atoms
- Querying the database manually for related data

There's no unified view that shows "what's in this namespace and what's its current state?"

**Impact:** Transforms Seon from a "headless" system into a visual, introspectable platform where both humans and agents can see and interact with any part of the system.

---

## URL Structure

```
/                              -> Dashboard (namespace browser)
/ns/seon.ai.claude             -> Namespace view (read-only introspection)
/ns/seon.ai.claude?id=e45gf    -> Session view (live ctx + DB for that session)
/agents                        -> Agent Observatory
/agents/{id}                   -> Agent detail view
/schemas                       -> Malli schema browser (future)
```

The namespace IS the route. Session ID is optional query param.

---

## Skill Usage Guide

Different skills are relevant for different work:

### When Building UI Components

```
/datastar-web-ui     -> SSE patterns, data-on:click, merge-fragment
/frontend-design     -> Visual design, avoiding generic aesthetics
/browser-automation  -> Test the result visually, debug in browser
```

### When Working with Data/Queries

```
/xtdb-queries        -> SQL/Datalog patterns, queries (migrating to Datalevin)
/clojure-testing     -> Test patterns, generators, mocking
```

### Skill Combinations by Task

| Task | Primary Skill | Supporting Skills |
|------|---------------|-------------------|
| Log viewer work | `/datastar-web-ui` | `/browser-automation` |
| Agent status widget | `/datastar-web-ui` | `/xtdb-queries` |
| Theme polish | `/frontend-design` | `/browser-automation` |
| Test failures | `/clojure-testing` | - |

---

## Architecture Overview

### Flow-Routed Rendering

When flows are running, agent JVMs return domain data maps. The orchestrator resolves renderers via the code index and renders locally:

```
Browser → GET /ns/seon.trading?instance=a1b2
  → SSE connection to orchestrator
  → orchestrator checks render cache for instance a1b2
  → if cached and not invalidated: serve cached HTML immediately
  → if stale: topology/request! to seon.trading flow process
    → TCP to agent JVM
    → agent returns domain data map (e.g. {:seon.trading/positions [...], ...})
    → orchestrator resolves renderer via spec-driven resolution (code index)
    → rendered hiccup cached + SSE merge-fragment to browser
```

The `topology/request!` function (in `seon.flow.topology`) is the blocking entry point for cross-namespace calls. It creates a promise, injects a request into the flow via `flow/inject`, and derefs the promise with a timeout. Replies are delivered by the `reply-router-step` which matches reply envelopes to waiting promises by request ID. For agent JVMs, cross-namespace relay go-loops forward requests from the agent's TCP bridge through the same `request!` mechanism.

Renderer resolution uses the spec-driven algorithm from [`spec-driven-rendering`](../spec-driven-rendering/prd.md): the code index matches data keys against `:seon.fn/render-input-keys`, picking the most specific function. No per-namespace `render` function is required.

### Render Convention

Rendering is spec-driven, not per-namespace. There is no explicit `render` function per namespace. Instead:

1. **Agent JVMs return domain data maps** -- plain maps with namespaced keys (e.g. `{:seon.trading/positions [...]}`)
2. **The orchestrator resolves a renderer** via the code index (see [`spec-driven-rendering`](../spec-driven-rendering/prd.md)) -- matching data keys against `:seon.fn/render-input-keys`
3. **Render functions live in `.render` companion namespaces** by convention (e.g. `seon.trading.render`), keeping domain code free of UI dependencies
4. **Fallback** -- if no renderer matches, `pprint-clipped` provides a default view

**URL mapping:**
- `/ns/seon.trading` (no `?instance=` param) → static namespace view from introspection (functions, vars, schemas)
- `/ns/seon.trading?instance=a1b2` → `topology/request!` to agent JVM → domain data → spec-driven render → cached hiccup

Cached at orchestrator, invalidated on `*ctx*` change (via the watch-based push pattern from `seon.web.reactive.instance`).

### View Multimethod

```clojure
;; In seon.ns.view
(defmulti render* (fn [value format] [format (extract-view-type value)]))
```

### Key Files

| File | Purpose |
|------|---------|
| `src/seon/ns/view.clj` | `render` multimethod, `typed` helper, default renderers |
| `src/seon/ns/routes.clj` | Namespace routing, SSE handler |
| `src/seon/ai/agent.clj` | Agent registry, observatory API, `render` fn |
| `src/seon/ai/agent/views.clj` | View methods for agent summary and log lines |
| `src/seon/web/components.clj` | Shared UI components |
| `src/seon/web/html.clj` | Base template, nav, dashboard |

---

## Technical Constraints

- **Datastar/SSE** - Use existing patterns from agent-observatory
- **Tailwind CSS** - Consistent with current styling
- **No external JS frameworks** - Datastar handles reactivity
- **REPL-friendly** - All introspection functions usable from REPL
- **Session isolation** - Session views only see their own data

---

## Success Criteria

1. Navigate to `/ns/seon.ai.claude` and see functions, vars, atoms listed
2. Click collections to expand/collapse
3. Browse Malli schemas with clickable cross-references
4. Browse Datalevin entities with forward/reverse refs
5. View atom in browser, change in REPL, see browser update within 100ms
6. Dashboard shows namespace tree and active sessions
7. Custom renderers work via `:seon.ui/render-fn` in ctx

---

## Future Vision

Once this is working:

1. **Live coding** - Watch namespace update as agent writes code
2. **Entity relationships** - Visualize how namespaces connect via requires
3. **Time travel** - Datalevin history queries to see past states
4. **Agent marketplace** - Share namespace "apps" with others
5. **Voice control** - "Show me the trading dashboard"

---

## Open Questions

1. **Namespace filtering** - Show all `seon.*` or also user namespaces?
2. **Launch session UX** - Button on read-only view? Or separate action?
3. **Tile persistence** - Store in orchestrator ctx or separate Datalevin entity?

---

## Resources

| Resource | What's There |
|----------|--------------|
| `src/seon/web/handlers.clj` | Existing route patterns |
| `src/seon/orchestrator/session.clj` | Session lifecycle |
| `src/seon/schema.clj` | Schema registry |
| `reference-code/datastar-clojure/` | Datastar SDK |
| `docs/reference/datastar-quick-reference.md` | SSE patterns |
| `docs/reference/xtdb-v2-reference.md` | Database queries |

---

## Phase 8: Flow-Routed Namespace Rendering

The capstone phase where `/ns/` routes go through the flow topology to agent JVMs. This is the culmination of the flow harness (super-repl) and the namespace rendering system.

**Goal:** When an agent JVM owns a namespace, `GET /ns/seon.trading` fetches domain data from the agent JVM via `topology/request!`, then the orchestrator resolves the renderer via the code index and caches the result. Agent JVMs never produce hiccup -- they return plain data maps.

**Key work:**
- Wire `/ns/` route handler to detect flow-owned namespaces and route through `topology/request!`
- Agent JVM returns domain data map (namespaced keys, no UI dependencies)
- Orchestrator resolves renderer via spec-driven resolution (`find-renderer` from code index)
- Implement render cache at orchestrator keyed by `[format (set (keys data))]`, invalidated on scanner updates or `*ctx*` change
- Handle fallback: if no flow is running for a namespace, render locally (current behavior); if no renderer matches, `pprint-clipped`
- SSE push path: `*ctx*` change in agent JVM -> flow event -> orchestrator cache invalidation -> SSE merge-fragment to browser
- Error handling: timeouts, agent JVM crashes, graceful degradation to static view

**Depends on:** spec-driven-rendering (Phase 2: renderer resolution), super-repl flow harness (Phase 4+), datalevin-migration

---

## Related PRDs

- [`datalevin-migration`](../datalevin-migration/prd.md) — Database layer migration from XTDB to Datalevin
- [`super-repl`](../super-repl/prd.md) — Flow harness, namespace isolation, agent JVM pool, cross-ns calls via `topology/request!`
- [`spec-driven-rendering`](../spec-driven-rendering/prd.md) — Code index, automatic renderer discovery, resolution algorithm

---

## Research Documents

| Document | Contents |
|----------|----------|
| `research/clerk-research.md` | Clerk viewer architecture |
| `research/viewer-architecture.md` | Portal, Reveal, XTDB Inspector patterns |
| `research/rendering-review.md` | Critical review of rendering approaches |
| `research/datafy-render-research.md` | Datafy/nav exploration |
