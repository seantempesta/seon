---
type: prd
status: completed
tags: [prd, archive]
---

# PRD: Namespace Render Toggle

## Status: SUPERSEDED by render-pipeline — render toggle folded into unified render pipeline

**Status:** Complete (Phase 1-2 done, Phase 3 future)
**Priority:** High
**Branch:** feature/namespace-ui
**Related:** VISION.md (Layer 4 & 5), docs/prds/namespace-ui/prd.md

---

## Goals

1. **Unified URL** - `/ns/seon.web.agents?id=6958` shows the Observatory agent detail view
2. **View toggle** - Switch between custom render and introspection view
3. **Debug-friendly** - Always accessible introspection, even when custom render exists

---

## Problem Statement

The `/ns/{namespace}?id=session_id` route system supports custom `render` functions, but:

1. **No toggle** - Once a namespace has a `render` function, you can't see the introspection view (functions, vars, atoms)
2. **Observatory is separate** - `/agents/:id` and `/ns/seon.web.agents?id=:id` are different routes
3. **No debugging escape hatch** - If custom render breaks, can't fall back to introspection

This blocks the Layer 4/5 vision where namespaces are first-class observable entities with both custom views AND introspection.

**Impact:** Users can't debug namespace internals when custom views are present. Two URL schemes for the same concept.

---

## Resources to Learn From

| Resource | What's There |
|----------|--------------|
| `src/seon/ns/routes.clj` | Current `/ns/` route system with `namespace-has-render?` check |
| `src/seon/web/agents.clj` | Observatory agent detail view (`agent-detail-content`) |
| `src/seon/ns/introspect.clj` | Namespace introspection logic |
| `VISION.md` | Layer 4 (Observability) and Layer 5 (Dynamic Context) vision |

---

## Solution Design

### Phase 1: Add render function to seon.web.agents

Move agent detail rendering to a public `render` function:

```clojure
(ns seon.web.agents
  ...)

(defn render
  "Custom render for Observatory agent view.
   Called by /ns/seon.web.agents?id=session_id"
  [{:keys [format id]}]
  (case format
    :html (agent-detail-content id)
    :ai   (agent-detail-ai id)    ; future: structured for AI
    nil   (agent-detail-content id)))

```

After this: `/ns/seon.web.agents?id=6958` shows the same view as `/agents/6958`.

### Phase 2: View toggle in ns-routes

Add query param `?view=introspect` to force introspection view:

```
/ns/seon.web.agents?id=6958           → custom render (agent detail)
/ns/seon.web.agents?id=6958&view=introspect → introspection view

```

Toggle button in header to switch views.

### Phase 3: Deprecate /agents/:id route (future)

Once `/ns/seon.web.agents?id=:id` works:
- Redirect `/agents/6958` → `/ns/seon.web.agents?id=6958`
- Remove old route after transition

---

## Constraints

- Must work with SSE live updates
- Must not break existing `/agents/:id` route during transition
- Introspection view must always be accessible via `?view=introspect`
- Toggle must preserve other query params (id, filters)

---

## Success Criteria

1. `/ns/seon.web.agents?id=6958` shows Observatory agent detail view
2. `?view=introspect` shows functions, vars, atoms in that namespace
3. Toggle button visible in header to switch views
4. SSE updates work for both views
5. Namespaces without render function still show introspection by default

---

## Deliverables

- [x] `render` function in `seon.web.agents`
- [x] `?view=introspect` query param handling in `seon.ns.routes`
- [x] Toggle UI component in namespace page header
- [ ] Tests for both view modes (not blockers, existing tests pass)

---

## Known Issues

- [ ] `?view=introspect` shows raw HTML instead of rendered content (SSE escaping issue)
