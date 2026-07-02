---
type: architecture
status: active
tags: [architecture, web, agent]
---

# UI — pages, blocks, renders, and routes

> **Target design** (present tense — the system as it is when built). Current code state + the migration path live in [[roadmap]].

The human's UI and the agent's prompt are the same data, dual-rendered. Every
page is a derived projection of the database; nothing rendered is stored. The
context unit is the **block**; the engine is `seon.render`; the front door is
**reitit**; the live channel is a gzip-compressed datastar **morph** stream
driven by one tx-listener. Every layer is a
symbol or a datom, so a third party overrides any of it — blocks, canvas,
layout, the root agent's world, routes, CSS, client — reusing the same
primitives, with zero `src/seon` edits.

## The block and its two renders

A **block** (`:seon.agent.ctx/block`, registered in [[data-model]]) carries up to
two **renders**, selected by key presence — there is no stored discriminator:

- **ai render** (`:seon.render/ai`) → **prompt** text: a verbatim string, or a
  qualified symbol late-resolved each render via `seon.eval/lookup-value`.
- **html render** (`:seon.render/html`) → a **tile**: a symbol, a literal hiccup
  vector, else the structural pretty-print.

Presence decides placement: ai-render-only = prompt only (no tile); html-render-
only = a tile only (zero prompt tokens); both = both. `:seon.agent.ctx/name` is
one keyword in three roles — the prompt header, the per-agent upsert key, and the
DOM slot id `#tile-<name>` — always in sync, which is what makes "the agent edits
the same thing the human sees" true. Renders are fns/symbols; the rendered output
is ephemeral, never stored.

## Seed-copy — one collection, no merge

Each agent OWNS its complete block set in `:seon.agent/ctx`, seeded at creation.
Render reads that complete collection sorted by `:seon.agent.ctx/priority` and
stops: there is no render-time merge and no separate default set — every block an
agent renders, it owns. The set is deduped app-level by `:seon.agent.ctx/name` (a
plain `:keyword`, NOT a datahike identity, so two agents can each own a
`:transcript` block); priority sorts with a stable by-name tiebreaker.

Global-vs-per-agent is decided by the DATA the render fn queries, never by the
block: a `:my.kb.*` row carries no agent ref (one KB, every agent sees it), a
`:my.todo/*` row carries `:my.todo/agent` (each agent sees its own). Same block
registration; the render fn scopes by what it reads. (See [[data-model]] for the
domain schemas + data-ref scoping; [[agent-runtime]] for how the seed runs as
quiet `:core` bootstrap forms at creation.)

## `install!` / `remove!` — the one override

`seon.agent.ctx/install!` and `seon.agent.ctx/remove!` are the sole verbs that
shape a block set:

- `install!` is **scope-aware + variadic** — one block map OR a vector of block
  maps to load the whole set at once. With no agent scope (boot) it builds the
  default seed set; in an agent's scope it targets THAT agent's `:seon.agent/ctx`.
  Idempotent **upsert by `:seon.agent.ctx/name`**.
- `remove!` drops a block by name; because `:seon.agent/ctx` is a component
  vector, the child entity cascade-retracts.

seon's `my.*` namespaces DEFINE the render fns and block data and batch-install
the set at seed. acme overrides by calling `install!`/`remove!` from its own
namespaces (loaded via `SEON_EXTRA_SRC`), so new acme agents seed acme's set. One
mechanism for everyone — seon, acme, and the agents themselves. A pure ADD needs
nothing more: name a block and its render symbols; the symbols resolve late.

**Skills are blocks; config shapes the seed.** A loadable skill ([[loadable-skills]])
is nothing but a `:skill/<name>` block — `(my.skills/load :datahike)` is
`install!`, `unload` is `remove!`, so the agent dials knowledge into its own context
and the cost is derived at render. And the per-cluster `seon.config` manifest
(aero `config/system.edn`) shapes the seed set declaratively WITHOUT a code change:
its per-role loadouts add blocks, seed skill bodies always-on (`default-load`), and
drop seeded blocks/routes by name. Absent config ⇒ byte-identical to a no-config
boot; both are the SAME seed-copy mechanism, fed by data instead of a hardcode.

## The render engine

One engine, `seon.render`, renders every page and the prompt. It is a single
recursive, **guarded** walker over the agent's blocks in two views (`:ai` →
String, `:html` → hiccup). Renders are projections, never persisted. A throwing or
hung render yields a `:seon/error` value (see [[data-model]] §6) for THAT render
only; siblings never crash.

**prompt == page by construction.** Both derive from the same blocks over the
same db value (`as-of` the turn's `t`): the prompt is
`seon.agent.ctx/render-context` (ai renders concatenated by
`:seon.agent.ctx/priority`), the page is the same blocks' html renders placed into
a layout's slots. "What the agent saw at turn N" is a re-derive from
`db-as-of(t)`.

**The typed block renderer.** Above `seon.ui.html` sits one reusable value→hiccup
layer, `seon.render/block` — `(block view x)` dispatches on the value-KIND `x`
carries (the namespaced key ON the value, never a stored `:kind`): a **message**
(`:seon.render/markdown`) → `seon.ui.markdown/md->hiccup`, a **source**
(`:seon.render/source`) → `clj->hiccup`, a **data** projection → the value panel, a
**`:seon/error`** → the error tile, a literal **hiccup** vector → passthrough, and
anything else → the data panel (never throws). The transcript and the canvas both
route their bodies through it, so every surface "just displays the block."

**Markdown renders server-side.** Agent text becomes HTML on the server via
`seon.ui.markdown/md->hiccup` (the `block` message lane) — the world shim loads NO
client markdown JS; the old client-side `data-markdown` / marked.js lane is gone from
the world page. One lane, server-side, for every message/eval body.

**Capability + cache.** Agent-authored renders, layouts, and route handlers run
SCI-bounded (`seon.render.sci/invoke-bounded`, a deadline), never
`lookup-value`-direct; core symbols run compiled, and the bootstrap CLJS compiler
stays out of the web bundle. The byte-stable cache prefix at low priority is
preserved for provider prefix-caching.

**Context assembly is its own domain.** How the prompt bands by dynamism
(stable prefix / sliding window / free dynamic tail), the
namespace-as-location model, and the cache gradient live in [[context]] —
this doc owns the shared block/render machinery and the human-facing twin:
every context band renders an html representation for inspectability.

## Slots and layouts

- **slot** — `(slot :name)` emits `[:div {:id "tile-<name>" :data-slot :name}]`, a
  named, DB-keyed EMPTY hole keyed on `:seon.agent.ctx/name`. It does not resolve
  `:name`; it marks a hole. Resolution happens at expansion: render the named
  block's html, and if THAT output contains more slots, recurse to fixpoint.
- **layout** — a render whose hiccup contains slots; it queries the db (the
  request carries it) + path-params and owns placement + CSS. **layout-vs-tile is
  a role, never stored**: a render with child slots is a layout, a render with
  none is a leaf **tile**.

## Pages — world, the root agent's world, app

Every **page** is a layout placing block html renders into slots; each filled slot
is a tile. All pages are agent worlds — one mechanism, a tree of routes:

- **world** (`/agent/{id}`) — one agent: the focal **canvas** IS the agent's
  **live tile** (`seon.render/render-agent-tile` resolving
  `:seon.render.live-tile/content`, the one HTML surface the agent rewrites),
  rendered into `#world-canvas`, above a `:seon.agent.ctx/priority`-ordered
  scroll of the agent's html `:seon.agent/ctx` blocks as supporting tiles
  (`:transcript` included). The canvas is NOT a `(slot :canvas)` block —
  `:canvas` is just a block name like any other; the hero is always the live
  tile (decision #19, observer-confirmed KEEP). With no custom tile, the
  default (`seon.render.live-tile/welcome`) LEADS with the agent's latest
  reply rendered as a markdown card (through the `block` message lane), falling
  back to the greeting only before the agent has spoken.
- **the root agent's world** (`/`) — the all-agents overview IS the **root
  agent's** world (`:seon.agent/id "root"`). Its system-scoped blocks query across
  all agents to render a preview tile each; dive into one via reverse routing
  (step back to see all, dive into one). The IDENTICAL block/layout/route
  machinery — NOT a separate overview page. It grounds the render + route tree: root
  world (`/`) → per-agent worlds (`/agent/{id}`) → apps. (Root's
  lifecycle/orchestrator facet lives in [[agent-runtime]]; here it is just the
  agent whose world is `/`.) It is the same page as any `/agent/{id}` world,
  with `seon.render.system/system-view` as its canvas (its live-tile content).
- **app** (`/agent/{id}/app/{x}`) — an agent-authored sub-page; its route handler
  is an agent layout symbol, SCI-bounded.

## The persistent header — `system-header = f(db)`

Every page carries a fixed-top **status bar**, `seon.ui.header/system-header`, a
pure function of the db value (NEVER throws — degrades to a brand-only bar).
Left→right: the brand (`seon.web.brand`, links `/`); agents-by-state dots+counts
(reusing `seon.render.system/fleet-summary` — one fleet counter, not
re-derived); throughput; datom count (links `/data`) + `SEON_EMBED` on/off; and a
`+ new agent` button + home/data links + a health dot. On the morphed world pages
(`/`, `/agent/{id}`, `/world`) it lives INSIDE `#world`, so it rides the live
morph and the stats tick on every commit; on the server-rendered `/data` +
`/agent/{id}/debug` it is a request-time snapshot. The `+ new agent` button POSTs
the same `/agents/new` create door and SWITCHES to the new `/agent/{id}`.

**Throughput is honest.** No per-turn duration is stored, so an instantaneous
tokens/sec is not derivable. `header/throughput` instead reports a ROLLING rate —
tokens from turns STARTED in the last 60 s ÷ 60 s (via `seon.agent.ctx.usage/extract`
over `:seon.agent.turn/llm-usage`) — beside the all-time token total + turn/eval
counts. Live-proven: a driven turn moved the bar to `1309.5 tok/s · 78.6k tok`.

## Graceful default routes (#28)

No request dead-ends on a raw 404. The reitit no-match default-handler 302s to
`/` (root's dashboard); a well-formed but UNKNOWN `/agent/{id}` (stale bookmark,
reset store, typo) also 302s home (`"root"` always resolves, never redirected).

## Routing is data — reitit + the capability gate

The front door is **reitit** (vendored `reference-code/reitit`, `.cljc`, runs in
the Node pod) consuming `:seon.route/*` datoms. A route datom carries its pattern,
method, unique name (reverse routing), owning agent (`:seon.route/owner`, rides as
route-data for auth), and a `:seon.route/handler` symbol that **IS a layout
symbol** — the same render machinery as a block's html render, not a separate
mechanism. `db->routes` projects the datoms into reitit's route vector;
a ~20-line Node↔Ring adapter feeds the router, which is a pure derived value of
the route datoms rebuilt on tx via a reloading thunk. This replaces hand-rolled
`case`/`cond`/`re-matches` dispatch. (The `:seon.route/*` attributes are
registered per [[data-model]].)

- **Seeded core routes:** `/` (root agent's world), `/world` + `/world/feed`, and
  `/agent/{id}` + `/agent/{id}/feed` — all GET. Each world is TWO GET routes: the
  shim page and its long-lived SSE stream at a `…/feed` sibling path (the shim's
  `data-init="@get('…/feed')"` opens the stream). The one action door is
  `/agent/{id}/call` (POST). Agents add `/agent/{id}/app/{x}` rows
  (capability-gated, handler in the agent's own `my.agent.<id>` ns).
- **Nested routes ARE nested layouts** — reitit meta-merges route-data parent →
  child (`:seon.route/owner` + middleware flow down). `match-by-name` gives reverse
  routing; build-time path/name conflict detection catches overlaps the
  hand-rolled `cond` silently shadowed.
- **`/agent/{id}/call` is the one action door, and the capability gate
  (`seon.web.reactive.call`) is unchanged.** reitit dispatches the URL to that one
  per-agent door; the fn rides as a route-data **descriptor** (the `?fn=` param),
  NOT its own route — **namespaces are not a routing level**. The gate authorizes
  the fn by resolving its owning agent from the fn's `my.agent.<id>` namespace and
  granting it only if it is a registered `:seon.fn` in that agent's home ns;
  refusal precedes any invoke; args stay data; the call runs SCI-bounded → it
  transacts → the page re-derives and the stream morphs. reitit replaces the
  FRAGILE dispatch, not the SECURE gate.
- **Interactivity is plain Clojure.** Agents author fn-calls in handler slots; a
  render-time server-side postwalk rewrites a fn-call `(cancel-order! id)` or a
  fn-ref `submit-order!` into one standard datastar `@post` to the agent's
  `/agent/{id}/call` door (fn-call args transit-serialized in the query; the
  fn-ref case pulls form values from datastar **signals** — the POST body).
  Transient client state — an input value, a popover, a time-slider — lives in
  datastar signals, never in DOM attributes, so a whole-element morph never
  clobbers it. Routing is orthogonal to this rewrite.
- **Auth + error-catch ride as middleware.** Per-route concerns are reitit
  route-data middleware referenced by keyword through a registry; a `:compile`
  middleware reads route-data and vanishes when N/A. Auth is wired empty — adding
  it later is one keyword + one registry entry, zero handler edits.

## The live channel — gzip morph SSE

The live channel is **ours** (reitit has no streaming primitives by design): one
**tx-listener** on a read-replica is the refresh signal, and the view is a pure
derivation of the DB. The agent only `transact!`s datoms; it never opens or writes
a stream. The model is hyperlith's `view = f(db)` ported into the Node pod, proven
in `seon.web.datastar`.

- **view = f(db-as-of t).** ONE render fn produces the WHOLE element (a world =
  `[:main#world …tiles…]`). On every datahike commit the tx-listener re-renders
  `view = f(db)` and writes ONE `datastar-patch-elements` event (default patch
  mode `outer`) to every open stream. datastar's **idiomorph** diffs the DOM
  client-side, so pushing the whole element MORPHS only what changed and preserves
  in-element state (focus, scroll, selection, the open popover). There is no
  server-side tree diff, no per-tile `{id, html}` packet, and no slot-tree BFS —
  one whole-element morph, granularly applied by idiomorph.
- **gzip + immediate flush.** The stream is long-lived and `Content-Encoding:
  gzip`: each event is written then sync-flushed (`Z_SYNC_FLUSH`) so the
  compressed bytes hit the wire at once; the browser transparently gunzips before
  datastar reads. The streamer is crash-proofed — error handlers on the gzip
  stream + the response, a `writableEnded` guard before every write, and
  `req.on('close')` ends the gzip stream and deregisters the connection.
- **One throttle.** A drop-latest (coalescing) throttle collapses a tx burst into
  ONE morph — an agent turn commits many datoms; the human sees a single
  re-render.
- **Separate GET feed path.** The shim page (`/world`, `/agent/{id}`) and its live
  stream (`/world/feed`, `/agent/{id}/feed`) are two GET URLs; the shim's
  `data-init="@get('…/feed')"` opens the stream. Two distinct URLs sidestep the
  GET/POST same-URL cache collision that forces hyperlith's same-path-POST `&u=`
  hack, and this matches datastar-clojure's own example (`tiny_gzip.clj`: page `/`,
  stream GET `/updates`). reitit routes the feed GET to the SSE handler, which rides
  the raw `node:http` `res` — the thin Node↔Ring adapter injects it and the handler
  returns `{:seon.http/hijacked true}` so the adapter does not double-write.
- **Transient state is signals; time-travel and reconnect are just re-renders.**
  Transient client state lives in datastar **signals** only, never DOM attrs. Time
  travel is `view = f(db-as-of t)` over the bitemporal DB — a different `t`, the
  same render. Reconnect needs no UI-side `since-t` replay: the first paint fires
  immediately on open and repaints the current world. **Status: LIVE** (`/agent/{id}`).
  `open-agent-feed!` reads an optional `?t=<tx-id>` and binds the view-fn to
  `world-layout (db/as-of @*conn* t)` — a PAST snapshot that is naturally FROZEN
  (re-rendering `db-as-of-t` on a later tx yields identical bytes, so the broadcast
  harmlessly re-pushes the same `#world`); no `?t` ⇒ the current auto-morphing feed,
  unchanged. The `/agent` shim's time-travel bar (a SIBLING of `#world`, outside the
  morph) owns the feed via ONE `data-effect` `@get` so datastar's per-attribute
  auto-cancellation aborts the prior stream → exactly one stream targets `#world`
  (the shim omits `data-init` on `#world` for that reason). Signals: `$live` /
  `$t` (scrub position) / `$ct` (committed as-of tx, set on slider release). Domain
  is `[db/origin-t .. db/basis-t]` (tx-ids). Proven server-side: pre-creation `t` →
  empty world, mid `t` → frozen partial world that holds under tx pressure, no-`t` →
  live auto-morph. The timeline UX (human timestamps, ticks, diff) is the owner's to
  refine.
- **The hard invariant: no agent code ever touches an SSE connection.** agent →
  datom → tx-listener → derived render → morph, one way; actions reverse it (a
  browser POST → the owning agent's sandbox → result datoms → tx-listener →
  morph). The DB is the bus both ways.

The streamer is a **role, not a process** — any process holding a read-replica + a
tx-listener can play it, so the UI-host is relocatable and can split into N
streamer-processes later (see [[architecture]] for the deployment topology).

**Streamed surfaces are verified server-side, not in a browser agent.** A
long-lived `text/event-stream` 503s through the in-tool chrome agent's net layer,
so verify a streamed change with a Node client that opens the gzip stream, gunzips
the payload, and asserts it changes on a real tx; the final live-morph eyeball is
the owner's, in real Chrome.

## Errors render as tiles

Any render failure becomes a **`:seon/error`** value (the one base shape — see
[[data-model]] §6) instead of crashing siblings. The html render shows it as an
**error tile** — friendly message, the offending block/route name and symbol, an
actionable hint — ancestors and siblings untouched, self-healing on the next
render. The SAME source feeds the agent's **warnings block** (its ai render), so a
render failure the agent owns enters its prompt as fix-oriented prose; when the
underlying fn is fixed, both the tile and the warning vanish (pure fn of state,
never stored). Route/layout throws flow identically via the error-catch
middleware.

## Total override — the acme proof

Every layer is a symbol or a datom; acme overrides each reusing the same
primitives, with zero `src/seon` edits. Each row below is verified on the live
acme cluster's NEW per-agent page (`/agent/{id}`), not just the legacy console:

| Layer | Override mechanism | Default |
|---|---|---|
| **block set** (supporting tiles) | `ctx/install!` / `ctx/remove!` from acme's own nses | seon's seeded set |
| **seed/skill loadout per cluster** | `config/system.edn` (`SEON_CONFIG`, `#profile`) loadouts/`default-load`/`include`/`exclude`/route-`removes` | full env-dir scan + `default-seed-blocks` |
| **a tile's look** | the block's `:seon.render/html` symbol | seon's html render fn |
| **focal `#world-canvas` (the live tile)** | the agent's `:seon.render.live-tile/content` symbol | seon's `welcome` tile |
| **the calm hero error (a broken live tile)** | `set!` of `seon.render.live-tile/error-response` (the CALM hero — keeps the agent-facing `:seon.render/ai`/`:seon.render/error`, swaps only the human hiccup) | seon's "updating this tile" card |
| **slot / world / entity error tiles** | `set!` of `seon.render.live-tile/error-tile` (the `(fn [:seon/error] → hiccup)` seam the `render` / `slot` / `render-entity-html` catches call) | seon's `default-error-tile` (informative card) |
| **root agent's world (`/`)** | the `/` route handler symbol (root's world layout) | seon's root world layout |
| **routes / apps** | `:seon.route/*` rows | seeded core routes |
| **brand head — name / tagline / CSS** | `SEON_BRAND_NAME` / `SEON_BRAND_TAGLINE` / `SEON_BRAND_CSS` (on every shim `<head>`, incl. `/agent/{id}`) | seon defaults / Phosphor |
| **client JS** | `SEON_EXTRA_PUBLIC` + scripts | datastar.js |

acme installs at preload in its own namespaces (loaded via `SEON_EXTRA_SRC`),
where it already wires its overrides. There are **two error seams**, and acme
`set!`s BOTH so every error surface on the page is branded:

- the **focal `#world-canvas`** flows through the LIVE-TILE path:
  `world-layout`'s canvas IS `render-agent-tile` resolving the agent's
  `:seon.render.live-tile/content`, and a throwing tile routes through the CALM
  hero seam `seon.render.live-tile/error-response` (the human never sees the
  failure here — it rides the agent twin, so the hero does NOT delegate).
- the **slot / world / entity error tiles** (`render` / `slot` /
  `render-entity-html` catches) route through the SEPARATE
  `seon.render.live-tile/error-tile` var (`(fn [:seon/error] → hiccup)`,
  defaulting to `default-error-tile`).

One `set!` per seam — two lines in `acme.overrides` — brands every error tile on
the page.

Acceptance (proven server-side on `/agent/{id}`, agent `zeG-2606272150`, acme
cluster port 7980 — gunzipped feed, not inference): with acme's
`:seon.render.live-tile/content` wired to `acme.widget/broken-tile` (a throwing
tile) and its blocks installed via `ctx/install!`, the focal `#world-canvas`
(via `error-response`) and the `#tile-acme-broken` slot (via `error-tile`) BOTH
render acme's branded `"Acme is preparing this view…"` card — NOT seon's stock
"updating this tile" card and NOT the informative `default-error-tile` — and the
`#world-tile-canvas` phantom (the stale pre-#19 `:canvas` block) is gone, while
the agent's normal tiles (`#world-tile-acme-tile`, `#world-tile-acme-widget`)
still render. `bin/acme build` ran with zero warnings; the default seon UI
(default cluster) is untouched.

## Malli throughout

Every map is a registered `:malli/schema` — the block, `:seon.route/*`, the
`:seon/error` value, layout I/O — instrumented like everything else. reitit
route-data is open maps, so our malli-validated maps ride as route-data with no
friction; reitit-malli coercion (vendored, optional) validates/coerces path-params
/ query / body against a route's `:parameters` schema for free, since we
malli-everything already.

## See also

Strict single-ownership: when a fact you need is owned by another doc, follow the
link and read it.

- [[architecture]] — the map: glossary, the cross-cutting principles, deployment topology.
- [[data-model]] — the block / `:seon.route/*` / `:seon/error` schemas these renders read, and the `my.*` domains.
- [[agent-runtime]] — the loop that assembles the prompt, the bootstrap that seeds blocks, and the run-status block's data source (`derive-status`).
- [[toolkit]] — `my.tile` and the agent verbs that drive the live tile.
- [[loadable-skills]] — skills as `:skill/<name>` blocks; the `seon.config` seed/skill override.
- [[roadmap]] — current code state + the dependency-ordered migration to this target (Lane U).
- [[datahike-primer]] — the datahike-in-the-grain mindset.
