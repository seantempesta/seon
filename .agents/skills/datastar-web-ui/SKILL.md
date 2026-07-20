---
name: datastar-web-ui
description: "The active Seon pod web UI: Datastar SSE over gzip element morphs, ClojureScript hiccup, database-derived reitit routes, render units, blocks/surfaces/canvas/slots, and the Phosphor Terminal theme. Use when editing seon.web.*, seon.ui.*, or seon.render; working on root /, /agent/{id}, /data, debug feeds, Datastar signal attributes, focus/layout, canvas controls, or live updates; or diagnosing feed, morph, input-focus, and render-cost bugs."
---

# Datastar web UI — the active pod interface

The UI you edit is the **CLJS pod's** loopback web UI on
`http://127.0.0.1:7890` — hiccup rendered in `.cljs`, streamed to the browser
as Datastar SSE. There is ONE update model: **`view = f(db-as-of t)`** — a
matching Datahike commit re-renders a whole element and morphs it in place.

> Hand-offs (don't duplicate): db reads/writes inside a handler →
> **`datahike`**; `^:async`/`await`/Promise (every handler that writes is
> async) → **`clojurescript`**; data-oriented mindset → **`data-oriented-clojure`**;
> verifying a page in a real browser → **`browser-automation`**.

## The one live model: `view = f(db)`, morphed from database changes

`seon.web.datastar` ports the hyperlith pattern into the pod. There is no
action-specific refresh path, no `refresh-all!`, and no second signal-diffing
channel:

1. One pure route view derives the complete, stable-ID `#app-view` element.
   The current subscription boundary is that whole element; there is no
   second per-unit feed or server-side tree diff.
2. Each page is **two routes**: a tiny **shim page** (GET) and a **separate
   long-lived `/feed` GET** SSE stream. Loopback uses identity encoding;
   remote deployments explicitly set `SEON_FEED_COMPRESSION=gzip` and still
   negotiate `Accept-Encoding`. Root `/`
   reuses the agent shim for `root`, whose external opener GETs
   `/agent/root/feed`; ordinary agents GET `/agent/{id}/feed`.
3. `!feeds` normalizes equivalent views into one `seon.reactive` registration.
   The computation captures Datahike-owned dependency plans from parent and
   execution-child reads and installs one database-scoped writer interest.
   Unrelated committed attributes never reach the computation.
4. A matching report advances the registration to its newest database value.
   One evaluation may be active and only the newest pending value is retained;
   configured settle and maximum latency bound progress under continuous
   commits. An affected registration derives one complete element, suppresses
   an equal serialized value, and flushes one `datastar-patch-elements` event
   through the selected response encoding. A fresh socket receives the current
   established value once.
5. Datastar's client-side `idiomorph` morphs each complete pushed element into
   the live DOM. Default patch mode is `outer`, so the stable element ID is the
   unit boundary.

The render fn is **pure of external state** and **NEVER throws** — a render
error degrades to a visible `#app-view-error` card inside the same element, because
the morph engine must be crash-proof. Source: `src/seon/web/datastar.cljs`
(`patch-elements`, `open-feed!`, `serve-root!`, `serve-agent-page!`,
`open-agent-feed!`).

### Historical feeds carry complete identity

`view = f(db)` rendered against an exact immutable database value is naturally
FROZEN. A historical `/agent/{id}/feed` request supplies all four Proximum
branch-head fields: `store-id`, `branch`, `commit-id`, and `basis-t`. The server
uses that branch head as the frozen subscription key and echoes it in
`Seon-Database-Branch-Head`. A partial or malformed branch head returns 422; it
never silently opens the live feed. With none of the fields, the feed is live.
(`open-agent-feed!`.)

## CRITICAL: verify the stream SERVER-SIDE, not in the browser agent

The in-tool Chrome agent's network layer **503s long-lived
`text/event-stream`** connections, so you cannot confirm a feed morphs by
watching it in the browser MCP. Verify the feed server-side with a client that
GETs `/agent/root/feed` or `/agent/{id}/feed`, negotiates the configured
encoding, and prints the
`datastar-patch-elements` frames — plus a human eyeball on the real page. Don't
trust "the browser agent saw nothing"; trust the server-side client + `logs/pod.log`
(the `FEED OPEN` / `broadcast` lines). See **`browser-automation`** for the
browser side and its limits.

## Human input bars live OUTSIDE the morphed element

A structural whole-`#app-view` morph can replace everything inside `#app-view`, so
a `<form>`/`<input>` placed inside it loses focus/value mid-typing. Every human
affordance (currently the chat bar and feed opener) is a **sibling of
`<main id="app-view">` in `<body>`**, spliced via the shim's `extra-body`, so the
feed never clobbers it. A fixed bottom bar + an inline-height spacer reserves
scroll room. (`chat-form-html` and `agent-feed-opener-html` in `datastar.cljs`.)

## Datastar attributes (the signal idioms actually used)

Event attributes use a **colon**: `data-on:click`, `data-on:submit`,
`data-on:change` — `data-on-click` (hyphen) is wrong and silently does nothing.
In hiccup that needs a colon in the key, use `(keyword "data-on:submit")`.

| Attribute | Purpose | Live example (datastar.cljs) |
|---|---|---|
| `data-init` | open the feed on element init | `@get('/agent/root/feed', {retryMaxCount: Infinity, openWhenHidden: false})` |
| `data-effect` | re-run + auto-cancel prior `@get` | the time-travel bar's sole feed opener |
| `data-signals` | declare reactive state | `{t: <basis>, ct: <basis>, live: true}` |
| `data-bind` | two-way bind input → signal | `data-bind "text"` on the chat input |
| `data-on:submit` | form submit → form-mode POST | `@post('/chat?agent=<id>', {contentType:'form'}); $text=''` |
| `data-text` | bind text content | `"$live ? '● live' : '⏸ as-of t=' + $ct"` |
| `data-class` | toggle classes by signal | `{'text-signal': $live, 'text-warning': !$live}` |

`@post(url,{contentType:'form'})` reads the form's named fields and posts them
`application/x-www-form-urlencoded` — exactly the shape `serve.cljs`'s handlers
parse. A 204 reply closes the stream cleanly (no morph from the POST); the
agent's reply transacts and the broadcast feed re-renders. Page load ships
**only `datastar.js`** (no client highlighter) — server-rendered Clojure/markdown
is morph-safe by construction.

## Hiccup, in `.cljs`

Surfaces are hiccup vectors built in `.cljs` and serialized by
`seon.ui.html/->string` (`html.cljc`). The layout pieces:

| ns | Role |
|---|---|
| `seon.ui.agent-view` | `agent-view = f(db, agent-id)` — primary canvas + selectable HTML context-block rail |
| `seon.ui.header` | `system-header = f(db)` — the fixed agent/database health bar |
| `seon.ui.markdown` | `md->hiccup` — LLM markdown replies → styled hiccup, no client JS |
| `seon.ui.clojure` | `clj->hiccup` — server-side Clojure syntax highlight (`.hljs-*` palette) |

## The render engine — `seon.render/block` and `slot`

`src/seon/render.cljs` is the typed renderer every surface shares. Two ideas:

- **`(block view x)`** — `view` is `:html` (→ hiccup) or `:ai` (→ prompt
  String). It dispatches on the value's tagged shape via the namespaced key the value
  carries (the tagged-value contract — never a stored `:kind`):
  `{:seon.render/markdown "…"}` → `md->hiccup`; `{:seon.render/source "…"}` →
  `clj->hiccup`; a `:seon.render.value/tree` projection → the collapsible data
  panel; a `:seon.error/message` value → an error card; a literal hiccup vector
  → passthrough; anything else → the data panel. GUARDED — a throwing render
  becomes an error card, siblings intact, never an exception.
- **`(slot ctx :name)`** — place the agent's `:seon.agent/ctx` block named
  `:name` into a layout slot, rendered through the guarded engine and wrapped
  in a stable ID-addressed element so idiomorph anchors it across morphs.
  Injected into every render ctx as
  `:seon.render/slot`, so a layout calls `((:seon.render/slot in) :canvas)`.

Renders are **functions resolved late** (`seon.eval/lookup-value` over a
qualified symbol), never stored output — a redefine takes effect on the next
render with no wiring. A surface that throws degrades to a calm updating
placeholder; the agent learns its surface is broken via a derived context
section (no stored error flag — self-heals on the next clean render).

## Routes are `:seon.route/*` datoms (reitit)

`seon.web.router` derives the reitit route vector from the database:
`db->routes` is a PURE projection of the seeded `:seon.route/*` datoms (pattern
/ method / handler-symbol / middleware) — the core routes (`/`, `POST /agents`,
`/view/unit`, `/agent/{id}`, `/agent/{id}/feed`, `/agent/{id}/debug`,
`/agent/{id}/debug/feed`, `/agent/{id}/call`). Handler
symbols resolve **late** at request time via `eval/lookup-value`, so a redefine
needs no rebuild. Routes NOT yet seeded as datoms (static assets, the secondary
POST doors `/chat`/`/stop`/`/resume`/`/clear`/`/log`, `/agents/run`, `/data`,
and `/data/feed`) live in the `static-supplement`. A socket-owning handler (SSE/static/
gzip-feed) returns the **hijack sentinel** `{:seon.http/hijacked true}` so the
adapter writes nothing. State-changing POSTs carry the `:seon.route/same-origin`
middleware (loopback binding is not CSRF protection).

To add a page: write the whole-element render fn (pure, never-throws), serve a
shim page + a `/feed` route, and either seed a `:seon.route/*` datom or add to
the static supplement. Don't invent a second update mechanism — reuse
`open-feed!` + a bound `view-fn`.

## Phosphor Terminal theme (non-negotiable)

Warm blacks, cream text (NOT white), amber accent; density over whitespace;
monospace everywhere; dot+text status (`● running`), never pill badges.

- Backgrounds `bg-base-950/900/850/800`; text `text-text-100/200/400/500`;
  accent `text-signal` / `text-amber-400`; semantic `text-success/error/warning`.
- `text-xs` is the PRIMARY size (`text-2xs` for meta); `p-3` not `p-6`; 1px
  borders `border-base-800`; `rounded`/`rounded-md`, never `rounded-full`
  except status dots.
- **Never** `bg-white`, `text-white`, `text-gray-*`/`text-zinc-*`, decorative
  shadows, AI-cliche gradients.
- Tailwind is local (`resources/public/css/input.css`, Tailwind v4 `@theme` +
  `@source` scanning), NOT a CDN. Only classes in the built/safelisted
  vocabulary render — a height not in the vocab is an inline `:style`. After
  editing `input.css`, rebuild (`npm run css:build`).

Full palette, type scale, density rules, component patterns, and anti-patterns:
`references/design-principles.md`. Maintained Tailwind tokens live in
`resources/public/css/input.css`; the namespace-UI PRD is archived history.

## Key files

| File | Purpose |
|---|---|
| `src/seon/web/serve.cljs` | HTTP server on 7890; POST handlers (`/chat`, `/agents`, `/stop`, …); same-origin gate |
| `src/seon/web/datastar.cljs` | shared gzip morph feeds, render units, root/agent shims, and human input bar |
| `src/seon/web/router.cljs` | reitit over `:seon.route/*` datoms; the Node↔Ring adapter + hijack sentinel |
| `src/seon/web/debug.cljs` | operator dev tools: `/agent/{id}/debug` (the exact LLM bytes) + `/data` (datom browser) |
| `src/seon/web/brand.cljs` | downstream brand seam (name/tagline/theme as DATA, read at render time) |
| `src/seon/render.cljs` | `block` (typed-value renderer) + `slot` + the recursive guarded engine |
| `src/seon/ui/agent_view.cljs` · `header.cljs` · `markdown.cljs` · `clojure.cljs` | the layout, status bar, and content renderers |
| `reference-code/datastar-clojure/` · `reference-code/datastar/` | datastar source — the `patch-elements`/gzip idioms (`tiny_gzip.clj`); read it, don't guess |

## When to read which reference

- `references/design-principles.md` — the full Phosphor palette, type scale,
  density rules, status/log/table component patterns, anti-patterns.
- `resources/public/css/input.css` — the maintained Tailwind v4 `@theme`
  tokens and animation definitions.
- `reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj` — a minimal
  separate-GET gzip SSE stream (the shape `open-feed!` mirrors).
