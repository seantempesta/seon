---
name: datastar-web-ui
description: "The ACTIVE pod web UI — Datastar SSE over a gzip whole-element morph, hiccup in .cljs, reitit routes from :seon.route/* datoms, the seon.render/block + slot renderer, and the Phosphor Terminal theme. Use when editing seon.web.serve / seon.web.datastar / seon.web.router / seon.web.debug, the seon.ui.* layout/tiles (agent view, header, markdown, clojure), or seon.render. Use when working with data-init / data-bind / data-on:submit / data-effect / data-signals / data-text signal attributes, the /agents + /agent/{id} pages and their /feed SSE streams, tiles/slots, time-travel as-of feeds, or styling (warm blacks / cream / amber / monospace / dot+text status). Use when a live UI doesn't update on a tx, an SSE stream won't verify in the browser, or a human input bar loses focus on morph."
---

# Datastar Web UI — the active pod surface

The UI you edit is the **CLJS pod's** loopback web surface on
`http://127.0.0.1:7890` — hiccup rendered in `.cljs`, streamed to the browser
as Datastar SSE. There is ONE update model: **`view = f(db-as-of t)`** — every
datahike commit re-renders a whole element and morphs it in place.

> The old `html.clj` / `sse.clj` / `routes.clj` / `components.clj` JVM web stack
> (Ring + the `refresh-all!` broadcast channel + `data-on:click → @post`-returns-
> HTML) is the **paused JVM track**. If a file ends in `.clj` under
> `src/seon/web/`, you are in the wrong lane — the active surface is `.cljs`.
>
> Hand-offs (don't duplicate): db reads/writes inside a handler →
> **`datahike`**; `^:async`/`await`/Promise (every handler that writes is
> async) → **`clojurescript`**; data-oriented mindset → **`data-oriented-clojure`**;
> verifying a page in a real browser → **`browser-automation`**.

## The whole model: `view = f(db)`, gzip-morphed on every tx

`seon.web.datastar` ports the hyperlith pattern into the pod. There is no
per-fragment update, no `refresh-all!`, no signal-diffing handler-per-action:

1. ONE render fn produces the **whole** `#app-view` element (`agents-view` for
   `/agents`, or `seon.ui.agent-view/agent-view` for `/agent/{id}`).
2. Each page is **two routes**: a tiny **shim page** (GET) and a **separate
   long-lived `/feed` GET** that is a gzip-compressed SSE stream. The shim's
   `<main id="app-view">` opens the feed via `data-init="@get('/agents/feed')"`.
3. The feed registers in `!feeds` with its OWN bound `view-fn` thunk. A
   `db/listen!` tx-listener (`on-tx`) fires `schedule-broadcast!` — a 50 ms
   trailing coalesce so an agent turn's many datoms become ONE morph — which
   re-renders each connection's `view-fn` and writes a `datastar-patch-elements`
   event, then `gz.flush(Z_SYNC_FLUSH)` so bytes hit the wire immediately.
4. Datastar's client-side `idiomorph` diffs the pushed whole element against the
   live DOM and patches only what changed. Default patch mode is `outer` (morph
   the element whose `id` matches), so a whole-element morph needs no
   selector/mode dataline — just `data: elements <line>` per HTML line, blank
   line terminates.

The render fn is **pure of external state** and **NEVER throws** — a render
error degrades to a visible `#app-view-error` tile inside the same element, because
the morph engine must be crash-proof. Source: `src/seon/web/datastar.cljs`
(`patch-elements`, `agents-view`, `push-conn!`, `broadcast!`, `open-feed!`).

### Time-travel falls out for free

`view = f(db)` rendered against `(db/as-of @*conn* t)` is a PAST snapshot that
is naturally FROZEN (re-rendering it on a later tx yields identical bytes). The
`/agent/{id}/feed?t=<tx-id>` stream binds `view-fn` to the as-of db; the
time-travel bar drives it with ONE `data-effect` `@get` that auto-cancels the
prior stream so exactly one feed targets `#app-view`. (`open-agent-feed!`,
`time-travel-bar-html`.)

## CRITICAL: verify the stream SERVER-SIDE, not in the browser agent

The in-tool Chrome agent's network layer **503s long-lived
`text/event-stream`** connections, so you cannot confirm a feed morphs by
watching it in the browser MCP. Verify the wire server-side: a tiny Node client
that GETs `/agents/feed`, gunzips the response stream, and prints the
`datastar-patch-elements` frames — plus a human eyeball on the real page. Don't
trust "the browser agent saw nothing"; trust the gunzip client + `logs/pod.log`
(the `FEED OPEN` / `broadcast` lines). See **`browser-automation`** for the
browser side and its limits.

## Human input bars live OUTSIDE the morphed element

A whole-`#app-view` morph **replaces** everything inside `#app-view` on every tx — so
a `<form>`/`<input>` placed inside it loses focus/value mid-typing. Every human
affordance (chat bar, new-agent bar, time-travel slider) is a **sibling of
`<main id="app-view">` in `<body>`**, spliced via the shim's `extra-body`, so the
feed never clobbers it. A fixed bottom bar + an inline-height spacer reserves
scroll room. (`chat-form-html`, `new-agent-bar-html`, `time-travel-bar-html` in
`datastar.cljs`.)

## Datastar attributes (the signal idioms actually used)

Event attributes use a **colon**: `data-on:click`, `data-on:submit`,
`data-on:change` — `data-on-click` (hyphen) is wrong and silently does nothing.
In hiccup that needs a colon in the key, use `(keyword "data-on:submit")`.

| Attribute | Purpose | Live example (datastar.cljs) |
|---|---|---|
| `data-init` | open the feed on element init | `@get('/agents/feed', {retryMaxCount: Infinity, openWhenHidden: false})` |
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

Tiles are hiccup vectors built in `.cljs` and serialized by
`seon.ui.html/->string` (`html.cljc`). The layout pieces:

| ns | Role |
|---|---|
| `seon.ui.agent-view` | `agent-view = f(db, agent-id)` — primary canvas + selectable HTML context-block rail |
| `seon.ui.header` | `system-header = f(db)` — the fixed fleet status bar (agents/throughput/store/health) |
| `seon.ui.markdown` | `md->hiccup` — LLM markdown replies → styled hiccup, no client JS |
| `seon.ui.clojure` | `clj->hiccup` — server-side Clojure syntax highlight (`.hljs-*` palette) |

## The render engine — `seon.render/block` and `slot`

`src/seon/render.cljs` is the typed renderer every surface shares. Two ideas:

- **`(block view x)`** — `view` is `:html` (→ hiccup) or `:ai` (→ prompt
  String). It DISPATCHES ON THE VALUE'S KIND via the namespaced key the value
  carries (the tagged-value contract — never a stored `:kind`):
  `{:seon.render/markdown "…"}` → `md->hiccup`; `{:seon.render/source "…"}` →
  `clj->hiccup`; a `:seon.render.value/tree` projection → the collapsible data
  panel; a `:seon.error/message` value → an error tile; a literal hiccup vector
  → passthrough; anything else → the data panel. GUARDED — a throwing render
  becomes an error card, siblings intact, never an exception.
- **`(slot ctx :name)`** — place the agent's `:seon.agent/ctx` block named
  `:name` into a tile hole, rendered through the guarded engine and wrapped as
  `[:div#tile-<name> {:data-slot "<name>"} …]` — a stable DOM id so idiomorph
  anchors it across morphs. Injected into every render ctx as
  `:seon.render/slot`, so a layout calls `((:seon.render/slot in) :canvas)`.

Renders are **functions resolved late** (`seon.eval/lookup-value` over a
qualified symbol), never stored output — a redefine takes effect on the next
render with no wiring. A tile that THROWS degrades to a calm "updating this
tile" placeholder; the agent learns its tile is broken via a DERIVED context
section (no stored error flag — self-heals on the next clean render).

## Routes are `:seon.route/*` datoms (reitit)

`seon.web.router` derives the reitit route vector from the database:
`db->routes` is a PURE projection of the seeded `:seon.route/*` datoms (pattern
/ method / handler-symbol / middleware) — the core routes (`/`, `/agents`,
`/agents/feed`, `/agent/{id}`, `/agent/{id}/feed`, `/agent/{id}/call`). Handler
symbols resolve **late** at request time via `eval/lookup-value`, so a redefine
needs no rebuild. Routes NOT yet seeded as datoms (static assets, the secondary
POST doors `/chat`/`/stop`/`/resume`/`/clear`/`/agents/new`, `/sse`, `/data`,
`/debug`) live in the `static-supplement`. A socket-owning handler (SSE/static/
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
`references/design-principles.md` + `docs/prds/namespace-ui/design-system.md`.

## Key files

| File | Purpose |
|---|---|
| `src/seon/web/serve.cljs` | HTTP server on 7890; POST handlers (`/chat`, `/agents/new`, `/stop`, …); same-origin gate |
| `src/seon/web/datastar.cljs` | the gzip-morph `view=f(db)` feed: `agents-view`, `open-feed!`, `broadcast!`, the human input bars, time-travel |
| `src/seon/web/router.cljs` | reitit over `:seon.route/*` datoms; the Node↔Ring adapter + hijack sentinel |
| `src/seon/web/debug.cljs` | operator dev tools: `/agent/{id}/debug` (the exact LLM bytes) + `/data` (datom browser) |
| `src/seon/web/brand.cljs` | downstream brand seam (name/tagline/theme as DATA, read at render time) |
| `src/seon/render.cljs` | `block` (typed-value renderer) + `slot` + the recursive guarded engine |
| `src/seon/ui/agent_view.cljs` · `header.cljs` · `markdown.cljs` · `clojure.cljs` | the layout, status bar, and content renderers |
| `reference-code/datastar-clojure/` · `reference-code/datastar/` | datastar source — the `patch-elements`/gzip idioms (`tiny_gzip.clj`); read it, don't guess |

## When to read which reference

- `references/design-principles.md` — the full Phosphor palette, type scale,
  density rules, status/log/table component patterns, anti-patterns.
- `docs/prds/namespace-ui/design-system.md` — the design-system spec (philosophy,
  Tailwind v4 `@theme`, animations).
- `reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj` — a minimal
  separate-GET gzip SSE stream (the shape `open-feed!` mirrors).
