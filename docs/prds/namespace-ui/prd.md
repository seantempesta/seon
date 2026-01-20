# PRD: Namespace UI

**Status:** Planning
**Priority:** High
**Branch:** TBD (after agent-observatory completes)

---

## Vision

Every Clojure namespace in Seon becomes a viewable, introspectable "app". The system provides:

1. **Default renderer** - Automatically shows functions, vars, atoms, schemas, and DB entities via introspection
2. **Custom renderers** - Agents can override to build tailored UIs for specific domains
3. **Live tiles** - Windows Phone-style dashboard where each namespace is a configurable tile
4. **Session model** - View namespaces read-only, or launch sessions for live interaction

Think of Seon as an OS where namespaces are apps. Users can:
- Browse available namespaces from the dashboard
- Open namespace views (read-only introspection)
- Launch sessions for live REPL + DB access
- Have multiple instances (e.g., workout tracker for different users)
- Direct the orchestrator to create new namespaces and watch agents build them live

---

## Goals

1. **Every namespace viewable** - Any `seon.*` namespace can be inspected via web UI
2. **Zero-config default** - Useful view without any namespace modifications
3. **Agent-customizable** - Agents can override rendering for domain-specific UIs
4. **Session-aware** - Read-only introspection OR live session with ctx + DB
5. **Responsive tiles** - Namespace views adapt to tile/half/full view modes

---

## Problem Statement

Currently, understanding a namespace requires:
- Reading source code directly
- Using REPL to inspect vars/atoms
- Querying XTDB manually for related data

There's no unified view that shows "what's in this namespace and what's its current state?"

The agent observatory (in progress) solves this for agents specifically. This PRD generalizes the pattern to ALL namespaces.

**Impact:** Transforms Seon from a "headless" system into a visual, introspectable platform where both humans and agents can see and interact with any part of the system.

---

## URL Structure

```
/                              -> Orchestrator dashboard (namespace browser)
/seon.ai.claude                -> Namespace view (read-only introspection)
/seon.ai.claude?id=e45gf       -> Session view (live ctx + DB for that session)
```

The namespace IS the route. Session ID is optional query param.

**Rationale:**
- Minimal cruft in URLs
- Namespace names are already unique identifiers
- Query param for session keeps the base route clean
- `/` as dashboard is intuitive starting point

---

## Phase 1: Dynamic Routing + Namespace Introspection

**Goal:** Any namespace accessible via URL, basic introspection data available.

### 1.1 Dynamic Route Handler

```clojure
;; Route: GET /{namespace}
;; Examples: /seon.ai.claude, /seon.trading, /seon.health.workout

(defn namespace-handler [{:keys [path-params query-params]}]
  (let [ns-str (:namespace path-params)
        session-id (:id query-params)
        ns-sym (symbol ns-str)]
    (if (find-ns ns-sym)
      (render-namespace ns-sym session-id)
      {:status 404 :body "Namespace not found"})))
```

### 1.2 Namespace Introspection

```clojure
(ns seon.ns.introspect)

(defn introspect
  "Return structured data about a namespace.

   Response:
     {:ns-name    'seon.ai.claude
      :doc        \"Claude provider namespace...\"
      :functions  [{:name 'launch-agent! :arglists '([request]) :doc \"...\"}]
      :vars       [{:name '*default-model* :value \"claude-opus-4-5\"}]
      :atoms      [{:name 'agent-registry :value-preview \"{2 agents}\"}]
      :schemas    [{:name ::message-entity :schema [:map ...]}]
      :requires   ['seon.ai 'seon.schema]}"
  [ns-sym]
  ...)
```

**Key decisions:**
- Only introspect loaded namespaces (use `find-ns`)
- Atoms show preview, not full value (could be huge)
- Schemas from `seon.schema` registry for this namespace
- Functions include arglists and docstrings

### 1.3 Basic HTML Renderer

Render introspection data as simple HTML. No styling yet, just structure:

```html
<h1>seon.ai.claude</h1>
<p>Claude Code provider namespace...</p>

<h2>Functions (15)</h2>
<ul>
  <li>launch-agent! ([request]) - Launch a Claude Code agent...</li>
  ...
</ul>

<h2>Vars (2)</h2>
...
```

### Deliverables

- [ ] `seon.ns.introspect/introspect` function
- [ ] Route handler at `/{namespace}`
- [ ] Basic HTML template
- [ ] 404 handling for unknown namespaces

---

## Phase 2: Default Renderer (Debug View)

**Goal:** Polished default view useful for debugging and exploration.

### 2.1 Function Display

```
+-------------------------------------------------------------+
| launch-agent!                                               |
| ([{::ai/keys [node namespace prompt] ...}])                 |
+-------------------------------------------------------------+
| Launch a Claude Code agent with an isolated Seon session.   |
|                                                             |
| Creates everything the agent needs:                         |
| - Isolated XTDB database for the namespace                  |
| - Persisted ctx atom for state management                   |
| ...                                                         |
+-------------------------------------------------------------+
```

- Syntax highlighted arglists
- Collapsible docstrings (expand on click)
- No invoke button (agents use REPL)

### 2.2 Atom Display (Live Updates)

```
+-------------------------------------------------------------+
| agent-registry                                   * live     |
+-------------------------------------------------------------+
| {"a1b2" {:session-id "a1b2" :namespace "seon.trading" ...}  |
|  "f602" {:session-id "f602" :namespace "seon.ai" ...}}      |
|                                                             |
| 2 entries                                      [refresh]    |
+-------------------------------------------------------------+
```

- Show current value (pretty-printed, truncated if large)
- SSE for live updates (or poll button)
- Entry count for collections

### 2.3 Schema Display

```
+-------------------------------------------------------------+
| ::launch-agent-request                                      |
+-------------------------------------------------------------+
| [:map                                                       |
|   [::ai/node ::ai/node]                                     |
|   [::ai/namespace ::ai/namespace]                           |
|   [::ai/prompt ::ai/prompt]                                 |
|   [::model {:optional true} ::model]                        |
|   ...]                                                      |
+-------------------------------------------------------------+
```

- Query `seon.schema` registry for schemas in this namespace
- Pretty-print Malli schemas
- Link to referenced schemas (clickable `::ai/node`)

### 2.4 DB Entities (Session-Aware)

When viewing with session ID (`?id=e45gf`):

```
+-------------------------------------------------------------+
| XTDB Entities (session: e45gf)                              |
+-------------------------------------------------------------+
| Table: ai_sessions (1 row)                                  |
| Table: ai_messages (47 rows)                                |
|                                                    [browse] |
+-------------------------------------------------------------+
```

Without session: show orchestrator DB entities related to namespace.

### Research: Existing Clojure UI Tools

Before implementing, research these for patterns:

| Tool | What to Learn |
|------|---------------|
| [Portal](https://github.com/djblue/portal) | Atom/var rendering, lazy loading large values |
| [Clerk](https://github.com/nextjournal/clerk) | Notebook-style rendering, viewer dispatch |
| [Reveal](https://github.com/vlaaad/reveal) | REPL integration, value navigation |
| [XTDB Console](https://github.com/xtdb/xtdb) | Entity browsing patterns |
| Datomic Console | Entity relationship visualization |

### Deliverables

- [ ] Research doc: `exploration.md` with findings from tools above
- [ ] Styled function cards with syntax highlighting
- [ ] Atom viewer with live updates (SSE)
- [ ] Schema browser with cross-references
- [ ] DB entity summary (integrates with agent-observatory `/db` browser)
- [ ] Tailwind styling consistent with agent-observatory

---

## Phase 3: Orchestrator Dashboard

**Goal:** `/` shows all namespaces with mini-previews.

### 3.1 Namespace Tree

```
+-------------------------------------------------------------+
| Seon Namespaces                                             |
+-------------------------------------------------------------+
| > seon.ai                                                   |
|   +- seon.ai.claude         | 15 fns | 2 atoms | 12 schemas|
|   +- seon.ai.claude.sdk     |  8 fns | 0 atoms |  7 schemas|
|   +- seon.ai.agent          |  6 fns | 1 atom  |  9 schemas|
|   +- seon.ai.gemini         |  3 fns | 0 atoms |  2 schemas|
| > seon.domains                                              |
|   +- seon.trading           |  ...   |         |           |
|   +- seon.health            |  ...   |         |           |
| > seon.web                                                  |
|   ...                                                       |
+-------------------------------------------------------------+
```

- Hierarchical tree based on namespace segments
- Click row to navigate to `/seon.ai.claude`
- Quick stats (fn count, atom count, schema count)

### 3.2 Live Tiles (Mini Views)

```
+--------------------+ +--------------------+ +--------------------+
| seon.ai.agent      | | seon.trading       | | seon.health        |
| -----------------  | | -----------------  | | -----------------  |
| 2 agents running   | | 5 positions        | | 3 workouts today   |
| f602: seon.trading | | P&L: +$234.50      | | 450 cal burned     |
| a1b2: seon.ai      | |                    | |                    |
|            [open]  | |            [open]  | |            [open]  |
+--------------------+ +--------------------+ +--------------------+
```

- Configurable tile sizes (small, medium, large)
- Each tile shows namespace-specific summary
- Default: introspection stats
- Custom: namespace can define `tile-render-fn`

### 3.3 Active Sessions Panel

```
+-------------------------------------------------------------+
| Active Sessions                                             |
+-------------------------------------------------------------+
| e45gf | seon.trading    | port 7891 | 5 evals |    [view]  |
| a1b2  | seon.ai.claude  | port 7892 | 47 evals|    [view]  |
+-------------------------------------------------------------+
```

- Shows all running sessions (from `session/list-agent-sessions`)
- Click to navigate to session view
- Integrates with agent-observatory

### Deliverables

- [ ] `/` route with namespace tree
- [ ] Tile grid layout (CSS grid, responsive)
- [ ] Active sessions panel
- [ ] Navigation to namespace/session views

---

## Phase 4: Custom Renderers

**Goal:** Namespaces can override default rendering.

### 4.1 Render Function Convention

If a namespace's ctx atom contains `:seon.ui/render-fn`, use it:

```clojure
;; In agent code for seon.trading namespace:
(swap! *ctx* assoc :seon.ui/render-fn 'seon.trading/render-dashboard)

;; The render function:
(defn render-dashboard
  "Custom renderer for trading namespace.

   Request:
     {:view-mode :tile | :half | :full
      :session-id \"e45gf\" | nil
      :ctx @*ctx*
      :db <xtdb-connection>}

   Returns: Hiccup HTML"
  [{:keys [view-mode session-id ctx db]}]
  (case view-mode
    :tile  (render-tile ctx)
    :half  (render-half-view ctx db)
    :full  (render-full-dashboard ctx db)))
```

### 4.2 View Modes

| Mode | Use Case | Typical Size |
|------|----------|--------------|
| `:tile` | Dashboard mini-view | 200x150 px |
| `:half` | Side-by-side comparison | 50% viewport |
| `:full` | Dedicated view | Full viewport |

Custom renderers receive view mode and should adapt content accordingly.

### 4.3 Fallback Chain

```
1. Check ctx for :seon.ui/render-fn -> use custom renderer
2. Otherwise -> use default introspection renderer
```

### Deliverables

- [ ] View mode detection and passing
- [ ] Custom renderer lookup from ctx
- [ ] Fallback to default renderer
- [ ] Documentation for writing custom renderers

---

## Phase 5: Tile System / Window Management

**Goal:** Drag-and-drop tile management, persistent layouts.

### 5.1 Tile Configuration

```clojure
;; Stored in orchestrator ctx or XTDB
{:seon.ui/layout
 {:tiles [{:namespace "seon.ai.agent" :size :medium :position [0 0]}
          {:namespace "seon.trading" :size :large :position [1 0]}
          {:namespace "seon.health" :size :small :position [0 1]}]}}
```

### 5.2 Drag-and-Drop

- CSS Grid for layout
- Drag tiles to reposition
- Resize handles for changing tile size
- Persist layout changes to ctx/XTDB

### 5.3 Window Mode (Future)

Eventually: floating windows, minimize/maximize, window snapping.

This is lower priority - the tile system covers 80% of use cases.

### Deliverables

- [ ] Tile size configuration
- [ ] Drag-and-drop reordering
- [ ] Layout persistence
- [ ] Resize handles

---

## Technical Constraints

- **Datastar/SSE** - Use existing patterns from agent-observatory
- **Tailwind CSS** - Consistent with current styling
- **No external JS frameworks** - Keep it simple, Datastar handles reactivity
- **REPL-friendly** - All introspection functions usable from REPL
- **Session isolation** - Session views only see their own data

---

## Success Criteria

1. **Phase 1:** Can navigate to any namespace via URL and see basic info
2. **Phase 2:** Default renderer shows useful debug information
3. **Phase 3:** Dashboard at `/` shows all namespaces with quick navigation
4. **Phase 4:** Agent can customize a namespace's rendering
5. **Phase 5:** User can arrange tiles on dashboard

---

## Dependencies

- **agent-observatory** - Phase 2-3 builds on XTDB browser and SSE patterns
- **seon.schema** - Schema introspection depends on registry
- **seon.orchestrator.session** - Session-aware views need session system

---

## Out of Scope (This PRD)

- Multi-user authentication
- Remote access / public URLs
- Mobile-optimized layouts
- Namespace creation UI (orchestrator handles this via REPL)

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

## Resources to Study

| Resource | What's There |
|----------|--------------|
| `src/seon/web/handlers.clj` | Existing route patterns |
| `src/seon/orchestrator/session.clj` | Session lifecycle |
| `src/seon/schema.clj` | Schema registry |
| `docs/prds/agent-observatory/` | SSE patterns, Datastar usage |
| `reference-code/datastar-clojure/` | Datastar SDK |

---

## Research Documents

Create these as we go:

- `exploration.md` - Research on Portal, Clerk, Reveal, etc.
- `routing-design.md` - Detailed route handler implementation
- `introspection-api.md` - Full introspection function design
