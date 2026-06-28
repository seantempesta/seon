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

**Capability + cache.** Agent-authored renders, layouts, and route handlers run
SCI-bounded (`seon.render.sci/invoke-bounded`, a deadline), never
`lookup-value`-direct; core symbols run compiled, and the bootstrap CLJS compiler
stays out of the web bundle. The byte-stable cache prefix at low priority is
preserved for provider prefix-caching.

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

- **world** (`/agent/{id}`) — one agent: a **canvas** (the focal agent↔human
  communication block) in `(slot :canvas)` plus a `:seon.agent.ctx/priority`-
  ordered scroll of the agent's tiles.
- **the root agent's world** (`/`) — the all-agents overview IS the **root
  agent's** world (`:seon.agent/id "root"`). Its system-scoped blocks query across
  all agents to render a preview tile each; dive into one via reverse routing
  (step back to see all, dive into one). The IDENTICAL block/layout/route
  machinery — NOT a separate overview page. It grounds the render + route tree: root
  world (`/`) → per-agent worlds (`/agent/{id}`) → apps. (Root's
  lifecycle/orchestrator facet lives in [[agent-runtime]]; here it is just the
  agent whose world is `/`.)
- **app** (`/agent/{id}/app/{x}`) — an agent-authored sub-page; its route handler
  is an agent layout symbol, SCI-bounded.

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
  immediately on open and repaints the current world. **Status:** reconnect-as-paint
  is LIVE; the time-travel half is DESIGNED, not yet wired — the feed view-fns
  currently close over the CURRENT db (`@db/*conn*`) with no `t` thread and no
  time-slider signal. Wiring it = a slider signal → `db/as-of` in the view-fn.
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
| **a tile's look** | the block's `:seon.render/html` symbol | seon's html render fn |
| **focal `#world-canvas` (the live tile)** | the agent's `:seon.render.live-tile/content` symbol | seon's `welcome` tile |
| **any broken/error tile — live tile OR slot** | `set!` of `seon.render.live-tile/error-response` (one error contract for both paths) | seon's "updating this tile" card |
| **root agent's world (`/`)** | the `/` route handler symbol (root's world layout) | seon's root world layout |
| **routes / apps** | `:seon.route/*` rows | seeded core routes |
| **brand head — name / tagline / CSS** | `SEON_BRAND_NAME` / `SEON_BRAND_TAGLINE` / `SEON_BRAND_CSS` (on every shim `<head>`, incl. `/agent/{id}`) | seon defaults / Phosphor |
| **client JS** | `SEON_EXTRA_PUBLIC` + scripts | datastar.js |

acme installs at preload in its own namespaces (loaded via `SEON_EXTRA_SRC`),
where it already wires its overrides. The `#world-canvas` override flows through
the LIVE-TILE path: `world-layout`'s focal canvas IS `render-agent-tile` resolving
the agent's `:seon.render.live-tile/content`, and a throwing tile routes through
the overridable `seon.render.live-tile/error-response` — the SAME var the slot
error path (`render/error-tile-hiccup`) calls, so one consumer `set!` brands every
error surface on the page.

Acceptance (proven server-side on `/agent/{id}`, agent `vKt-2606261227`, acme
cluster port 7980 — gunzipped feed, not inference): with acme's
`:seon.render.live-tile/content` wired to `acme.widget/broken-tile` (a throwing
tile) and its blocks installed via `ctx/install!`, the `#world-canvas` section and
the `#tile-acme-broken` slot BOTH render acme's branded
`"Acme is preparing this view…"` card — NOT seon's stock "updating this tile" card
— and the page `<head>` carries `<title>Acme · agent …</title>` plus the inlined
`acme/branding/acme.css`. `bin/acme build` ran with zero warnings; the default
seon UI (default cluster) is untouched.

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
- [[roadmap]] — current code state + the dependency-ordered migration to this target (Lane U).
- [[datahike-primer]] — the datahike-in-the-grain mindset.
