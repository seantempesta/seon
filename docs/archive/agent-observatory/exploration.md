# Web UI Infrastructure Exploration

**Date:** 2026-01-20
**Agent:** d641 (seon.ui-exploration)
**Purpose:** Document current state of web UI infrastructure for agent observatory

---

## Summary

The Seon web UI is **fully operational** with a mature Datastar/SSE architecture. The foundation is solid and ready for extension with new features like the agent observatory.

**Key Finding:** All infrastructure needed for the observatory already exists - routes, SSE handlers, HTML templates, real-time updates. We just need to add new routes and views.

---

## Current Routes

| Method | Path | Handler | Purpose |
|--------|------|---------|---------|
| GET | `/` | `handlers/dashboard` | Import dashboard shim page |
| POST | `/` | `handlers/dashboard-sse` | Dashboard SSE stream |
| GET | `/api/health` | `handlers/health` | Health check (JSON) |
| POST | `/api/import/start` | `handlers/start-import` | Start bulk import |
| POST | `/api/import/stop` | `handlers/stop-import` | Stop running import |
| GET | `/api/import/status` | `handlers/job-status` | Job status (JSON) |
| GET | `/api/stats` | `handlers/database-stats` | DB statistics (JSON) |
| GET | `/logs` | `handlers/log-viewer` | Log viewer shim page |
| POST | `/logs` | `handlers/log-viewer-sse` | Log viewer SSE stream |
| POST | `/api/logs/filter` | `handlers/log-filter` | Update log filter |
| POST | `/api/logs/refresh` | `handlers/log-refresh` | Refresh logs |
| POST | `/api/logs/toggle-scroll` | `handlers/log-toggle-scroll` | Toggle auto-scroll |
| GET | `/primer` | `primer-handlers/primer-page` | Primer shim page |
| POST | `/primer` | `primer-handlers/primer-sse` | Primer SSE stream |
| GET | `/primer/ctx` | `primer-handlers/ctx-handler` | Primer context (EDN) |
| GET | `/primer/debug` | `primer-handlers/debug-page-handler` | Primer debug page |
| POST | `/primer/action/:action-id` | `primer-handlers/action-handler` | Dynamic action routes |

---

## Live Endpoints Tested

All endpoints are working:

```bash
# Health check
$ curl http://localhost:8080/api/health
{"status":"ok","timestamp":"2026-01-20T06:37:07.779110Z"}

# Database stats
$ curl http://localhost:8080/api/stats
{"empty?":true,"symbols-count":0,"by-symbol":[],...}

# All HTML pages render correctly (/, /logs, /primer)

```

---

## Architecture

### Server Stack

```
src/seon/web/
├── server.clj    ; Integrant component, http-kit, SSE init
├── routes.clj    ; Map-based routing + dynamic routes
├── handlers.clj  ; HTTP handlers (dashboard, logs, import)
├── sse.clj       ; SSE infrastructure (render-handler, refresh-all!)
├── html.clj      ; HTML templates (Chassis + Tailwind v4)
├── brotli.clj    ; Streaming brotli compression
├── jobs.clj      ; Import job state management
├── logs.clj      ; Log viewer state
└── stats.clj     ; Cached database statistics

```

### Key Components

**1. SSE Infrastructure (`seon.web.sse`)**
- `init-sse!` - Creates broadcast channel with 100ms throttle
- `render-handler` - Wraps render functions with hash-based change detection
- `refresh-all!` - Triggers re-render for all connected clients
- `wrap-refresh-mult` - Middleware to inject refresh channel into requests

**2. HTML Templating (`seon.web.html`)**
- Uses [Chassis](https://github.com/onionpancakes/chassis) (compile-time Hiccup)
- Tailwind CSS v4 via CDN with custom `@theme` tokens
- Datastar v1.0.0-RC.6 for reactive updates
- Shared `base-page` template with nav bar
- Skeleton loading states

**3. Routing (`seon.web.routes`)**
- Simple map-based static routes: `{[:method "/path"] handler}`
- Dynamic routes with regex patterns for path params
- 404 fallback returns JSON error

### Data Flow

```
Browser loads page
    │
    ▼
GET / → Returns HTML shim with Datastar init
    │
    ▼
Datastar auto-POSTs to / (same path)
    │
    ▼
POST / → SSE handler streams updates
    │
    ▼
render-handler calls render-fn, compares hash, sends if changed
    │
    ▼
User action (button click) → POST /api/action
    │
    ▼
Handler updates state → Watch triggers refresh-all!
    │
    ▼
All connected clients receive update

```

---

## Datastar Integration

### Attributes in Use

| Attribute | Purpose | Example |
|-----------|---------|---------|
| `data-init` | Auto-execute on load | `@post('/')` |
| `data-on:click` | Click handler | `@post('/api/stop')` |
| `data-on:submit` | Form submit | `@post('/api/start', {contentType: 'form'})` |
| `data-on:online__window` | Reconnect handler | `@post('/')` |
| `id="morph"` | SSE update target | `<main id="morph">` |

### SSE Wire Format

```
event: datastar-patch-elements
id: 7f8a3c2d
data: elements <main id="morph">...
data: elements </main>

```

---

## Agent Infrastructure

The `seon.ai.agent` namespace provides everything needed for the observatory:

### Registry API

```clojure
;; List all agents
(agent/agents {})
;; => [{::agent/session-id "f602"
;;      ::agent/namespace "seon.session-analytics"
;;      ::agent/provider :claude
;;      ::agent/agent-status :running
;;      ::agent/nrepl-port 7892
;;      ::agent/ai-session-id "ses-9d781e29-..."}]

;; Get specific agent
(agent/get-agent {::agent/session-id "f602"})

;; Stream messages (returns core.async channel)
(agent/tail {::agent/session-id "f602"})

;; Interrupt agent
(agent/interrupt! {::agent/session-id "f602"})

```

### Live Data (as of exploration)

```clojure
(agent/agents {})
;; Returns 4 agents:
;; - 3856 (seon.e2e-test) - completed
;; - f2cb (seon.hook-test) - completed
;; - f602 (seon.session-analytics) - running
;; - d641 (seon.ui-exploration) - running (me!)

```

---

## What's Working

1. **HTTP Server** - http-kit on port 8080, properly configured
2. **SSE Streaming** - Brotli-compressed, hash-based change detection
3. **Datastar Integration** - Auto-POST, reconnect on online event
4. **HTML Templates** - Chassis + Tailwind v4 with custom theme
5. **Navigation** - Shared nav bar between Dashboard/Logs
6. **State Management** - Atoms with watchers triggering SSE refresh
7. **API Endpoints** - JSON responses for health, stats, job management
8. **Log Viewer** - Real-time log streaming with filters
9. **Primer System** - Interactive story engine with sessions

---

## What's Missing for Observatory

### Routes Needed

```clojure
;; Add to routes.clj
[:get "/agents"]              handlers/agent-list
[:post "/agents"]             handlers/agent-list-sse
[:get "/agents/:id"]          handlers/agent-detail
[:post "/agents/:id"]         handlers/agent-detail-sse
[:post "/agents/:id/interrupt"] handlers/agent-interrupt

```

### Handlers Needed

1. **agent-list** - Shim page for agent list view
2. **agent-list-sse** - SSE handler rendering agent table
3. **agent-detail** - Shim page for single agent
4. **agent-detail-sse** - SSE handler streaming agent messages
5. **agent-interrupt** - POST handler to stop agent

### HTML Templates Needed

1. **agent-list-page** - Table of all agents with status badges
2. **agent-detail-page** - Message stream with tool calls, results
3. **nav-bar update** - Add "Agents" link to existing nav

### State Integration

Need to wire up `agent/agents` and `agent/tail` to SSE handlers:

```clojure
(def agent-list-sse
  (sse/render-handler
    (fn [_request]
      (html/agent-list-content (agent/agents {})))))

```

For agent detail view, need to tap into `agent/tail` channel and stream messages via SSE.

---

## Recommendations for PRD

### Phase 2.1: Agent List View

**Implementation approach:**
1. Add routes to `routes.clj`
2. Create handlers in `handlers.clj` (or new `agent_handlers.clj`)
3. Create HTML templates in `html.clj` (or new `agent_html.clj`)
4. Add "Agents" to nav-bar in `base-page`
5. Use existing `status-badge` pattern for agent status

**Data source:** `(agent/agents {})` - already returns all needed info

### Phase 2.2: Agent Detail View

**Challenge:** The `agent/tail` function returns a core.async channel. Need to bridge this to SSE.

**Options:**
1. Poll `agent/tail` on each SSE refresh (simple, might miss messages)
2. Dedicated goroutine that collects messages into an atom (recommended)
3. New multimethod that returns accumulated messages

**Recommended approach:**

```clojure
;; Store recent messages per agent
(defonce agent-message-buffers (atom {}))

;; In launch-agent!, add message accumulator
(go-loop []
  (when-let [msg (<! messages-ch)]
    (swap! agent-message-buffers update session-id
           (fn [buf] (conj (or buf []) msg)))
    (sse/refresh-all!)  ; Trigger UI update
    (recur)))

```

### Phase 2.3: XTDB Browser

**Implementation:** Use existing `stats.clj` pattern for caching
- Query `ai_sessions` and `ai_messages` tables
- Display in table format similar to symbol breakdown in dashboard
- Add `/db` routes

---

## Open Questions Resolved

1. **SSE or WebSocket?** → SSE. Already working, simpler, Datastar native.
2. **How to get agent messages?** → `agent/tail` returns channel, need to buffer.
3. **How to add new pages?** → Follow Log Viewer pattern: shim + SSE handler.

---

## Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `src/seon/web/routes.clj` | Modify | Add agent routes |
| `src/seon/web/handlers.clj` | Modify | Add agent handlers |
| `src/seon/web/html.clj` | Modify | Add agent templates, update nav |
| `src/seon/ai/agent.clj` | Modify | Add message buffering for SSE |

---

## Conclusion

The web UI infrastructure is **production-ready**. The observatory can be built by:

1. Following the existing Log Viewer pattern for routes/handlers
2. Using `seon.ai.agent` registry for data
3. Adding message buffering to bridge async channels to SSE
4. Extending the nav bar and HTML templates

Estimated complexity: **Medium** - No new infrastructure needed, just new views.
