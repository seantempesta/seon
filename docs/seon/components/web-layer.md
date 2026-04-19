---
type: component
status: stable
tags: [component, web]
---
# Web Layer

> HTTP server, SSE-driven UI, and Datastar integration — the real-time rendering backbone for Seon's browser interface.

## Purpose

The web layer serves Seon's browser UI using a **shim + SSE** pattern: each page loads a minimal HTML shell (the "shim") that immediately POSTs back to establish an SSE connection. The server renders full views on each refresh event, diffs via content hashing, and sends only changed HTML as Datastar `patch-elements` events. This gives every page live-updating behavior with zero client-side framework code.

## Namespaces

| Namespace | File | Role |
|-----------|------|------|
| `seon.web.server` | `src/seon/web/server.clj` | Integrant component: http-kit on port 8080, middleware stack, late-binding handler resolution |
| `seon.web.routes` | `src/seon/web/routes.clj` | Static + dynamic route map, regex-based path params, static file serving |
| `seon.web.handlers` | `src/seon/web/handlers.clj` | Dashboard, log viewer, health check handlers; SSE render-handler wiring |
| `seon.web.sse` | `src/seon/web/sse.clj` | Core SSE engine: broadcast mult, hash-based change detection, render loop, Brotli negotiation |
| `seon.web.sse.flow` | `src/seon/web/sse/flow.clj` | core.async.flow topology for code-change aggregation, client registry, broadcast fan-out |
| `seon.web.html` | `src/seon/web/html.clj` | Base page template (Chassis/Hiccup), Datastar init, nav bar, dashboard + log viewer content |
| `seon.web.components` | `src/seon/web/components.clj` | Design system library: status dots, cards, tables, log lines, buttons, empty states |
| `seon.web.flows` | `src/seon/web/flows.clj` | Flow monitor page — topology diagrams, process tables, error feeds |
| `seon.web.agents` | `src/seon/web/agents.clj` | Agent Observatory — running + completed agents, detail view with conversation logs |
| `seon.web.logs` | `src/seon/web/logs.clj` | Log viewer state: tail `logs/app.log`, parse logback format, level filtering |
| `seon.web.namespace` | `src/seon/web/namespace.clj` | Namespace introspection handlers (`namespace-page`, `namespace-sse`) — not currently wired into routes (superseded by `seon.ns.routes`) |
| `seon.web.reactive.transform` | `src/seon/web/reactive/transform.clj` | Hiccup transform: `:on:click` to `data-on:click @post(...)`, `:field` to HTML `name` |
| `seon.web.reactive.actions` | `src/seon/web/reactive/actions.clj` | Action resolution: resolve `seon.*` function symbols, security gate |
| `seon.web.reactive.demo` | `src/seon/web/reactive/demo.clj` | Instance-based reactive demo: counter, item list, live input |
| `seon.web.browser` | `src/seon/web/browser.clj` | REPL-to-browser execution bridge: eval JS/CLJS in connected browsers, structured results |
| `seon.web.tailwind` | `src/seon/web/tailwind.clj` | Integrant component: manages `tailwindcss --watch` child process |
| `seon.web.caddy` | `src/seon/web/caddy.clj` | Integrant component: Caddy reverse proxy for HTTPS on localhost:3030 |
| `seon.web.brotli` | `src/seon/web/brotli.clj` | Streaming Brotli compression for SSE (90-100x over connection lifetime) |

## Public API Surface

**SSE engine** (`seon.web.sse`):

- `render-handler` — factory that creates SSE handlers from a render function. Options: `:poll-ms`, `:write-profile`, `:use-view-transition?`, `:auto-brotli?` (default `false`), `:on-open`, `:on-close`, `:render-on-connect` (default `true`)
- `refresh-all!` — trigger re-render for all connected SSE clients
- `patch-elements` — build Datastar SSE event with selector, mode (outer/inner/append/prepend/before/after/replace/remove)
- `execute-script` — build Datastar SSE event for JS execution
- `init-sse!` / `shutdown-sse!` — lifecycle for the broadcast mult with optional throttling

**Browser bridge** (`seon.web.browser`):

- `eval!` / `eval!!` — execute JavaScript in connected browser, structured/parsed result
- `cljs!` / `cljs!!` — execute ClojureScript via Scittle
- `errors` / `clear-errors!` — browser error tracking
- `connected?` / `clients` — check browser connectivity

**Route dispatch** (`seon.web.routes`):

- Static routes map: `{[:method "/path"] #'handler}`
- Dynamic routes with regex patterns and path params
- Custom router (no library dependency), `requiring-resolve` for hot reload

## Dependencies

**Uses:**

- http-kit — async HTTP server, `hk/as-channel` for SSE
- Chassis (`dev.onionpancakes.chassis.core`) — compile-time Hiccup to HTML
- Datastar — client-side: `@post()` for SSE init, `datastar-patch-elements` / `datastar-execute-script` events
- core.async — broadcast mult, sliding buffers, throttling, virtual threads for render loops
- core.async.flow — SSE flow topology (aggregator, registry, broadcaster)
- Integrant — server, tailwind, caddy lifecycle components
- Brotli4j — streaming compression for SSE connections
- `seon.ai.agent` — dashboard agent count, observatory data
- `seon.ns.routes` — namespace page rendering, function call dispatch
- `seon.ctx` — client registry for browser bridge
- `seon.flow.status` — flow monitor data collection

**Used by:**

- `seon.ai.datalevin` — triggers `refresh-all!` on writes for observatory updates
- `seon.ai.agent` — `init!` adds watch on agent-registry to trigger SSE refresh
- Browser clients via Datastar

## How Data Flows

1. **Page load**: Browser GETs `/agents` -> server returns HTML shim with Datastar init script
2. **SSE connect**: Datastar auto-POSTs to `/agents` -> `render-handler` creates SSE channel via `hk/as-channel`
3. **Initial render**: Virtual thread calls `render-fn(request)` -> HTML string -> content hash -> `patch-elements` event -> browser morphs DOM
4. **Live updates**: `refresh-all!` puts `:refresh-event` on broadcast channel -> mult fans to all tapped channels -> each handler re-renders -> hash comparison -> send only if changed, otherwise keepalive
5. **Polling fallback**: Handlers with `:poll-ms` also re-render on timeout (e.g., flows at 1s, logs at 2s)
6. **Actions**: Datastar `@post('/ns/seon.foo/bar')` -> dynamic route match -> `actions/resolve-action` -> call function -> `refresh-all!`
7. **Browser bridge**: `eval!` sends `datastar-patch-elements` with injected `<script>` -> browser executes -> POSTs result to `/api/browser/result` -> promise delivered

### SSE Flow Topology (code-change propagation)

```
[changes] -> [aggregator] -> [broadcaster]
                  ^
[register] -> [registry] -> (client tracking)
[unregister] -/

```

The aggregator debounces rapid code changes (50ms default), groups by namespace, and emits aggregated updates. The registry tracks connected SSE clients by page type.

## Design Decisions

**Shim + SSE pattern**: Every page is a thin HTML shell that Datastar populates via SSE POST. This means the server owns all rendering — no client-side templates, no hydration mismatch, no build step for UI code. Code changes reflect immediately via `refresh-all!`.

**Hash-based diffing**: The render loop hashes the full HTML output. If unchanged, it sends a keepalive comment instead. This prevents unnecessary DOM morphing and detects dead connections.

**Late-binding handler resolution**: `server.clj` uses `requiring-resolve` at request time (not startup time) so `clj-reload` can swap namespace Vars without server restart. The server survives `(user/reset)` via Integrant suspend/resume.

**Phosphor Terminal design system**: Warm blacks (`bg-base-850/900/950`), cream text (`text-50/200/400`), amber accents (`text-signal`), monospace everywhere, density over whitespace (`p-3` not `p-6`, `text-xs` primary). Status dots with pulse for active states. Defined in `components.clj`, documented in `docs/prds/namespace-ui/design-system.md`.

**Virtual threads for SSE**: Each SSE connection gets a virtual thread (`Thread/ofVirtual`) for its render loop, avoiding thread pool exhaustion with many concurrent connections.

**Streaming Brotli**: The compressor maintains state across writes over the SSE connection lifetime, learning patterns and achieving 90-100x compression. Negotiated via `Accept-Encoding` header.

**Reactive transform layer**: Agent-friendly hiccup (`:on:click :increment!`, `:field :user-name`) is transformed to Datastar attributes at render time. Agents never write `data-on:click` or `@post()` directly.

## Refactoring Opportunities

- **`seon.web.agents`** is the largest file (~1150 lines) — the observatory rendering could be split into a dedicated view namespace, similar to how `seon.ai.agent.views` works
- **`seon.web.html`** mixes base template with dashboard-specific content; dashboard content could move to its own namespace
- **Log viewer title** still says "ML Options Trading" (`log-viewer-shim`) — stale from before the rename to Seon
- **`seon.agent.helpers`** SQL functions all throw "not yet migrated to Datalevin" — dead code that should be removed or migrated
- **SSE flow** (`sse/flow.clj`) exists alongside the simpler broadcast mult in `sse.clj` — the flow adds code-change aggregation and client tracking but the broadcast mult is what actually drives page updates. These could be unified.
- **`seon.web.namespace`** creates a fresh `render-handler` per request (to ensure current bindings) — this works but allocates a new channel tap each time
