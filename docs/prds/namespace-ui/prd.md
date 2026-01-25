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
| 1b | Observatory UI improvements | In Progress | [`observatory-polish`](../observatory-polish/prd.md) |
| 1c | Agent robustness | Done | [`stability-improvements`](../stability-improvements/prd.md) |
| 2 | Expand/collapse + styling | Pending | [`data-viewer`](../data-viewer/prd.md) |
| 3 | Malli schema viewer | Pending | [`schema-viewer`](../schema-viewer/prd.md) |
| 4 | XTDB entity browser | Pending | - |
| 5 | Live atom updates | Pending | [`live-updates`](../live-updates/prd.md) |
| 6 | Dashboard polish | Pending | [`dashboard-polish`](../dashboard-polish/prd.md) |
| 7 | Custom renderers | Pending | [`custom-renderers`](../custom-renderers/prd.md) |

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
- Querying XTDB manually for related data

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
/xtdb-queries        -> SQL patterns, temporal queries, multi-database
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

### Render Convention

Namespaces can provide a `render` function:

```clojure
(defn render
  "Called by /ns/{namespace} route."
  [{:keys [format id]}]
  (if id
    (view/render (view/typed :my-ns/detail (get-detail id)) format)
    (view/render (view/typed :my-ns/list (get-list)) format)))
```

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
4. Browse XTDB entities with forward/reverse refs
5. View atom in browser, change in REPL, see browser update within 100ms
6. Dashboard shows namespace tree and active sessions
7. Custom renderers work via `:seon.ui/render-fn` in ctx

---

## Future Vision

Once this is working:

1. **Live coding** - Watch namespace update as agent writes code
2. **Entity relationships** - Visualize how namespaces connect via requires
3. **Time travel** - XTDB bitemporal queries to see past states
4. **Agent marketplace** - Share namespace "apps" with others
5. **Voice control** - "Show me the trading dashboard"

---

## Open Questions

1. **Namespace filtering** - Show all `seon.*` or also user namespaces?
2. **Launch session UX** - Button on read-only view? Or separate action?
3. **Tile persistence** - Store in orchestrator ctx or separate XTDB table?

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

## Research Documents

| Document | Contents |
|----------|----------|
| `research/clerk-research.md` | Clerk viewer architecture |
| `research/viewer-architecture.md` | Portal, Reveal, XTDB Inspector patterns |
| `research/rendering-review.md` | Critical review of rendering approaches |
| `research/datafy-render-research.md` | Datafy/nav exploration |
