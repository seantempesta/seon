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
layout, the root agent’s view, routes, CSS, client — reusing the same
primitives, with zero `src/seon` edits.

## The block and its two renders

A **block** (`:seon.agent.ctx/block`, registered in [[data-model]]) carries up to
two **renders**, selected by key presence — there is no stored discriminator:

- **ai render** (`:seon.render/ai`) → **prompt** text: a verbatim string, or a
  qualified symbol late-resolved each render via `seon.eval/lookup-value`.
- **html render** (`:seon.render/html`) → a **surface**: a symbol, a literal hiccup
  vector, else the structural pretty-print.

Presence decides placement: ai-render-only = prompt only (no surface);
html-render-only = a surface only (zero prompt tokens); both = both.
`:seon.agent.ctx/name` is
one keyword in three roles — the prompt header, the per-agent upsert key, and the
DOM slot id `#surface-<name>` — always in sync, which is what makes "the agent edits
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
`:my.plan/*` row carries `:my.plan/agent` (each agent sees its own). Same block
registration; the render fn scopes by what it reads. (See [[data-model]] for the
domain schemas + data-ref scoping; [[agent-runtime]] for the fact-first atomic
birth transaction and post-commit safe declaration load.)

## `install!` / `remove!` — the one override

`seon.agent.ctx/install!` and `seon.agent.ctx/remove!` are the sole functions that
shape a block set:

- `install!` is **scope-aware + variadic** — one block map OR a vector of block
  maps to load the whole set at once. In an agent's scope it targets THAT
  agent's `:seon.agent/ctx`.
  Idempotent **upsert by `:seon.agent.ctx/name`**.
- `remove!` drops a block by name; because `:seon.agent/ctx` is a component
  vector, the child entity cascade-retracts.

The cluster manifest declares the initial block data. Agents may later call
`install!`/`remove!` against the same database-owned collection. A pure ADD
needs nothing more: name a block and its render symbols; the symbols resolve
late.

**Pinning a fn is a block; config shapes the seed.** Any render fn an agent wants
always-on is nothing but a block — `install!` at a chosen priority pins it,
`remove!` drops it, so the agent dials context in and the cost is derived at
render. (`my.skills` explicit load/unload reuses this exact override; importing
a corpus alone installs no block—see [[data-model]] §5.5 + [[context]].) And the
per-cluster `seon.config`
manifest (aero `config/system.edn`) shapes the seed set declaratively WITHOUT a
code change. An absent block tree means no blocks; no hidden code fallback or
implicit skill-body injection exists.

## The render engine

One engine, `seon.render`, renders every page and the prompt. It is a single
recursive, **guarded** walker over the agent's blocks in two views (`:ai` →
String, `:html` → hiccup). Renders are projections, never persisted. A throwing or
hung render yields a `:seon/error` value (see [[data-model]] §6) for THAT render
only; siblings never crash.

**prompt == page by construction.** Both derive from the same blocks over the
same db value resolved from the turn's
`{database-id, branch, commit-id, t}`: the prompt is
`seon.agent.ctx/render-context` (ai renders concatenated by
`:seon.agent.ctx/priority`), the page is the same blocks' html renders placed into
a layout's slots. "What the agent saw at turn N" is a re-derive from that exact
commit; t alone is not a durable bookmark.

**The typed block renderer.** Above `seon.ui.html` sits one reusable value→hiccup
layer, `seon.render/block` — `(block view x)` dispatches on the value-KIND `x`
carries (the namespaced key ON the value, never a stored `:kind`): a **message**
(`:seon.render/markdown`) → `seon.ui.markdown/md->hiccup`, a **source**
(`:seon.render/source`) → `clj->hiccup`, a **data** projection → the value panel, a
**`:seon/error`** → an error card, a literal **hiccup** vector → passthrough, and
anything else → the data panel (never throws). The transcript and the canvas both
route their bodies through it, so every surface "just displays the block."

**Markdown renders server-side.** Agent text becomes HTML on the server via
`seon.ui.markdown/md->hiccup` (the `block` message lane) — the view shim loads NO
client markdown JS; the old client-side `data-markdown` / marked.js lane is gone from
the agent-view page. One lane, server-side, for every message/eval body.

**The human transcript is chat-first.** Message entities render as the visible
conversation. Eval entities render as fixed-size one-line activity rows derived
from their called symbol and status; source, arguments, result projections, and
full errors are not embedded in the normal transcript DOM. The visible history
is bounded by the transcript block's database-owned `turns-retained` policy,
with one preceding message retained for conversational orientation.
Consecutive equivalent failures remain coalesced into one row. Exact AI text
and technical data remain available in the separate debug/data surfaces. This
changes only the HTML projection—the agent's AI transcript remains
byte-faithful.

**Large context twins are summaries first.** Plan roots render as compact
title/progress disclosures; only the focused root starts open, and its tree has
a bounded internal scroll region. Long titles and goals line-clamp, every
technical surface wraps or horizontally scrolls, and the canvas has a bounded
default height. Scale is handled by disclosure and windowing, never smaller
unbounded text.

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

- **slot** — `(slot :name)` emits
  `[:div {:id "surface-<name>" :data-slot :name}]`, a
  named, DB-keyed EMPTY hole keyed on `:seon.agent.ctx/name`. It does not resolve
  `:name`; it marks a hole. Resolution happens at expansion: render the named
  block's html, and if THAT output contains more slots, recurse to fixpoint.
- **layout** — a render whose hiccup contains slots; it queries the db (the
  request carries it) + path-params and owns placement + CSS.
  **layout-vs-surface is
  a role, never stored**: a render with child slots is a layout, a render with
  none is a leaf **surface**.

## Pages — agent view, root view, debug view, app

Every **page** is a layout placing block html renders into slots; each filled slot
is a surface. All pages are agent views — one mechanism, a tree of routes:

- **agent view** (`/agent/{id}`) — one agent: a large primary panel plus a right
  rail containing every current HTML context-block render ordered by database
  transaction recency. Selecting a rail card sets this tab's **page focus** and
  displays that render in the primary panel. Missing and AI-only renders are
  omitted. The canvas is NOT a `(slot :canvas)` block — it is the agent's focal
  surface projection.

  Two focus values are deliberately distinct. **Agent-derived focus** is shared
  database meaning: the agent's `:seon.render.canvas/content` pin when present,
  otherwise its **last agent-updated surface**, otherwise
  `seon.render.canvas/welcome`. **Page focus** is this tab's valid manual surface
  selector when present, otherwise agent-derived focus. The selector is scoped
  to the tab's database-backed web-session location; it never changes another
  tab or becomes an agent-global selected-surface projection.

  Renderer recency is the latest transaction by this agent through the REPL,
  found by a bounded indexed history lookup over scoped inputs captured by the
  renderer's current runtime-observed database reads; canvas writes share that
  same coordinate. Content recency orders the rail, while focus recency treats
  an agent-to-human reply as a transcript update and a canvas/domain write as a
  canvas update; eval bookkeeping alone never steals focus.
  `seon.render.surface/last-updated-surface` is pure over the db value plus the
  runtime-derived read plan (see [[context]]). Pinning is the exact durable
  override; retracting the pin falls back to recency. The page-focused surface
  is skipped as its own supporting card, so it is not duplicated. The welcome
  surface leads with the agent's latest reply as markdown and falls back to the
  greeting only before the agent has spoken.

  Focus recency is intentionally a current-renderer heuristic, not historical
  dependency replay: it does not reconstruct old conditional branches, and a
  broad/unknown read earns definition recency only. Session selection and agent
  pinning are separate overrides at separate scopes. Live invalidation remains
  exact before/after result comparison and is not weakened by this focus policy.

  Both provenance dimensions are load-bearing for agent-derived focus. Root boot
  and config transactions legitimately name root as their user, but are system
  maintenance rather than updates authored by the root agent. Requiring the
  REPL process keeps those facts available for provenance without letting them
  select or reorder the root canvas.
- **the root agent’s view** (`/`) — the all-agents overview IS the **root
  agent's** view (`:seon.agent/id "root"`). Its system-scoped blocks query across
  all agents to render a card each. A cheap card shell always shows identity,
  purpose, derived state, and the agent-derived focus label. Visible non-root
  cards materialize that surface's compact HTML face through
  the same `seon.render.surface` catalog/focus/materializer used by the agent's
  own page;
  its working data uses colocated `:seon.render.surface/*` keys. Expanded details
  lazily show up to five recent messages and failed evals. Root remains in the
  roster, but its self-card is summary-only: its agent-derived focused surface is
  this fleet `system-view`, so materializing that preview or canvas-AI twin would
  recurse. These are independent view units, so one agent update does not rebuild
  the fleet. Dive
  into one via reverse routing (step back to see all, dive into one). Its human
  input addresses root, whose
  deliberately small role context is to understand the fleet, start/select an
  ordinary agent, delegate, and route the originating browser tab there. Root's
  operational detail comes from its orchestration/navigation namespace cards
  and current-namespace source, not a long root instruction block. The IDENTICAL
  block/layout/route
  machinery — NOT a separate overview page. It grounds the render + route tree: root
  view (`/`) → per-agent views (`/agent/{id}`) → apps. (Root's
  lifecycle/orchestrator facet lives in [[agent-runtime]]; here it is just the
  agent whose view is `/`.) It is the same page as any `/agent/{id}` view,
  with `seon.render.system/system-view` as its canvas (its canvas content). A
  first-ever database also contains one ordinary agent; `bin/seon up --open`
  opens that ordinary agent while `/` remains available as mission control.

  `system-view`'s AI twin always names every agent and its status/focused
  surface. Within one explicit block budget it adds non-root canvas-AI,
  five-message, and recent-failure detail in the order running → erroring →
  recent. A cap never
  silently drops the roster; it marks which detail was omitted. The same twin
  includes the normalized location from the root message's originating browser
  session, so root knows what that human is currently seeing.

  Host telemetry is a separate optional system-status surface, not prose added
  to every turn. Once the operator exposes one reusable process-status
  projection, the surface samples pod/writer liveness, CPU, RSS, uptime, and
  feed pressure on demand. It is one independently refreshed unit on the normal
  feed, persists no rolling projection, and contributes to root's AI context
  only when anomalous.
- **debug view** (`/agent/{id}/debug`) — the exact AI context grouped into
  collapsible blocks, with HTML twins alongside when present. It also derives
  the total prompt token estimate, per-block token breakdown, cache boundary,
  agent state, and turn diagnostics. It is available for every agent and does
  not alter the prompt. Its page GET is an empty shell: the feed renders AI
  once, retains the exact assembled prompt behind a lazy raw unit, and exposes
  source-block bodies plus HTML twins as closed stubs. HTML discovery projects
  metadata only; opening one twin materializes only that current renderer.
  It uses the same gzip Datastar subscription graph and activation door as every
  other live page, not a provenance-routed debug listener. With no open page it
  owns no listener or render work.
- **app** (`/agent/{id}/app/{x}`) — an agent-authored sub-page; its route handler
  is an agent layout symbol, SCI-bounded.

## The persistent header — one shared render unit

Every page carries a fixed-top **status bar**, `seon.ui.header/system-header`
(NEVER throws — degrades to a brand-only bar).
Left→right: the brand (`seon.web.brand`, links `/`); agents-by-state dots+counts
(reusing `seon.render.system/fleet-summary` — one fleet counter, not
re-derived); datom count (links `/data`) + `SEON_EMBED` on/off; and a
`+ new agent` button + home/data links + a health dot. It is one shared stable
  render unit: a relevant database dependency change renders it once and
  fans the same complete element to every subscribed page. It does not recompute
  inside every whole view and it uses a cheap index count rather than a full-database
  inventory scan. The `+ new agent` button POSTs the one `/agents` creation door
  with an empty purpose and switches to the new `/agent/{id}`. The same door
  accepts an optional purpose from a root-fleet form; there is no separate
  creation or roster page.

The persistent header has no rolling clock-driven rate. Usage totals and rates
over an operator-selected interval are derived on demand from timestamped turn/
log facts; merely passing time never forces a page morph.

## Graceful default routes (#28)

No request dead-ends on a raw 404. The reitit no-match default-handler 302s to
`/` (root's dashboard); a well-formed but UNKNOWN `/agent/{id}` (stale bookmark,
reset database, typo) also 302s home. `/agent/root` canonicalizes to `/` before
render, so root never has two live page/feed identities. There is no GET
`/agents` view or `/agents/feed`; a GET to the creation collection follows the
same no-match redirect to `/`.

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

- **Seeded core routes:** `/` + `/feed` (root agent’s view) and
  `/agent/{id}` + `/agent/{id}/feed` — GET. Each view is TWO GET routes: the
  shim page and its long-lived SSE stream at a `…/feed` sibling path (the shim's
  `data-init="@get('…/feed')"` opens the stream). The one action door is
  `/agent/{id}/call` (POST); `POST /agents` is the sole agent-birth HTTP door.
  Agents add `/agent/{id}/app/{x}` rows
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

### Database-backed human location and root-directed navigation

Each browser tab owns one compact `:seon.web.session/id` represented by database
facts defined in [[data-model]]. Tab-local browser storage keeps the attachment
tuple `{database-id, branch, session-id}` needed to reconnect it. The
session carries a ref to the human plus one normalized local location string.
That location is the fact: route name, agent target, and URL are derived through
reitit rather than duplicated as more session attributes. Transaction metadata
provides recency, so there is no stored `updated-at`, `active?`, or presence
registry.

First load has no browser-generated identity. Bootstrap accepts a stored tuple
only when its database/branch match the current attachment and its lookup ref
exists in that database for the current human. Otherwise the page asks the
writer's one `seon.db.id/allocate!` path to create the session entity atomically
with its initial normalized location, returns the replacement tuple, stores it
in `sessionStorage`, and only then opens the feed keyed by it. Reload and
reconnect reuse a validated ID. Every subsequent route observation compares the
normalized location and transacts only when it changed. If a reset or restore
removes the session beneath an already-open feed, that feed sends one
auto-removing control patch that clears only this tab's Seon session tuple and
reloads the current local route through the same bootstrap; it never preserves
a ghost cursor or client-upserts the missing identity.

An agent page's manual surface selection is the one meaningful sub-route state:
it is encoded in the normalized location's query component. With no selection
parameter, the page uses agent-derived focus. Clicking a rail card updates the
URL/session fact and the Datastar signal together; reload restores that tab's
selection, and root can query it through the originating session, but a fleet
card does not adopt it. Scroll position, disclosure state, and form signals stay
browser-transient and are not falsely promoted to database facts.

Opening/navigating a route reconciles that same session location. A human message
links to the originating session, and each turn records the exact inbound
message it is assigned to answer as `:seon.agent.turn/cause-message`; the run's
waking message is insufficient because a run can absorb later input. Root can
therefore receive the right session through the ordinary injection boundary.
Root calls the protected, fully specified
`seon.web.session/select-agent!`; its required
`:seon.web.session/agent-id` names the target and its optional injected
`:seon.web.session/id` names the originating tab. That key is context-only at
the eval boundary: agent input cannot override it. It validates/reverse-routes
the target, compares the normalized location, and transacts only a real change. A
missing originating session or target returns an error envelope. The already-open
feed for that session applies the
official Datastar redirect-helper semantics: an auto-removing script patch over
the existing stream, not a second event family or channel, only when the stored
location differs from that feed's normalized current route. Arrival at the new
route observes equality, writes nothing, and emits no redirect. Another tab has
a different session identity and does not move.

This is desired/current UI state, not authentication and not a second command
queue. Root can query exactly what the human who messaged it is seeing, while
the browser remains a projection of database state. A missing originating
session returns an explicit error envelope instead of guessing which tab to
move.

## The live channel — gzip morph SSE

The live channel is **ours** (reitit has no streaming primitives by design): one
**tx-listener** on a read-replica is the refresh signal, and the view is a pure
derivation of the DB. The agent only `transact!`s datoms; it never opens or writes
a stream. The model is hyperlith's `view = f(db)` ported into the Node pod, proven
in `seon.web.datastar`.

- **view = f(db-at-coordinate), compiled into stable units.** Reitit route
  match + normalized path/query/resolved coordinate define one view key. Database route,
  context, and program facts compile to one plan containing a shell, shared
  header, fleet cards, surfaces, focus controller, debug panes, or data result
  as stable ID-addressed units. Initial paint derives the whole
  `[:main#app-view …]`; later updates render only dirty units and emit their
  complete elements in one `datastar-patch-elements` event (default `outer`). A
  plan/membership change falls back to a whole `#app-view` morph so conditional
  elements disappear honestly. One subscription owns the plan/cache for all
  equivalent tabs. Subscriptions reference an ephemeral normalized-unit registry
  (unit id + params + attachment/commit), so truly shared units such as the header own
  one read/output cache across different page keys and render once. Frozen as-of
  subscriptions do no current work.
- **gzip + immediate flush.** The stream is long-lived and `Content-Encoding:
  gzip`: each event is written then sync-flushed (`Z_SYNC_FLUSH`) so the
  compressed bytes reach the browser connection at once; the browser transparently gunzips before
  datastar reads. The streamer is crash-proofed — error handlers on the gzip
  stream + the response, a `writableEnded` guard before every write, and
  `req.on('close')` ends the gzip stream and deregisters the connection. A
  backpressured connection retains only its newest derived event and resumes on
  `drain`; stale UI states never form an unbounded write queue.
- **Observed reads + exact result change.** Each unit renders under a synchronous
  runtime-only observer at the `seon.db` boundary. Actual query/pull/entity
  requests compile into an in-memory attribute→read→unit index; no dependency
  datoms are stored. A coalesced batch retains earliest `db-before`, latest
  `db-after`, and changed datoms/attributes. Attributes select candidate reads;
  each normalized read is evaluated once on both immutable values, and only an
  unequal result invokes its unit renderer. Identical serialized output is
  suppressed. Broad/unknown reads are compared conservatively rather than
  blindly rendering. Coalescing has a bounded maximum wait, so continuous
  structural writes cannot starve a view.
- **Separate GET feed path.** The shim page (`/view`, `/agent/{id}`) and its live
  stream (`/view/feed`, `/agent/{id}/feed`) are two GET URLs; the shim's
  `data-init="@get('…/feed')"` opens the stream. Two distinct URLs sidestep the
  GET/POST same-URL cache collision that forces hyperlith's same-path-POST `&u=`
  hack, and this matches datastar-clojure's own example (`tiny_gzip.clj`: page `/`,
  stream GET `/updates`). reitit routes the feed GET to the SSE handler, which rides
  the raw `node:http` `res` — the thin Node↔Ring adapter injects it and the handler
  returns `{:seon.http/hijacked true}` so the adapter does not double-write.
- **Transient state is signals; time-travel and reconnect are just re-renders.**
  Transient client state lives in datastar **signals** only, never DOM attrs. Time
  travel is `view = f(db-at-coordinate)` over the bitemporal DB—a different
  resolved commit, the same render. Reconnect needs no numeric `since-t` replay:
  the first paint fires
  immediately on open and repaints the current view. **Status: LIVE**
  (`/agent/{id}`). A historical request carries the complete canonical
  `{database-id, branch, commit-id, t}`; the server verifies it against the
  registered database attachment and echoes that coordinate in its
  response/bookmark. There is no bare-t compatibility selector. That feed is
  marked frozen and excluded from current broadcasts. No coordinate means the current
  dependency-reactive feed. The `/agent`
  shim's time-travel controls (siblings of `#app-view`) own the feed connection;
  one `data-effect` `@get` lets Datastar's per-attribute auto-cancellation abort
  the prior stream, so exactly one stream targets `#app-view`. Signals hold
  `$live`, a transient scrub `$t`, and the last resolved branch/commit/t; only
  the resolved coordinate enters the feed URL/cache key. The slider domain may
  display branch-local t values and human timestamps, but commit id is the
  durable bookmark. Proven server-side: a resolved pre-creation commit → empty
  view, a mid-lineage commit → frozen partial view that holds under tx pressure,
  and no coordinate → live auto-morph. A reconnect whose attachment changed or
  whose last commit is not an ancestor receives a full reset, never numeric
  replay across lineages.
- **The hard invariant: no agent code ever touches an SSE connection.** agent →
  datom → tx-listener → derived render → morph, one way; actions reverse it (a
  browser POST → the owning agent's sandbox → result datoms → tx-listener →
  morph). The DB is the bus both ways.

The same gzip subscription mechanism serves root, agent, debug, and data
views. There is no provenance-routed debug stream or unused generic `/sse`
registry. Transaction user/process is relevant to agent-derived focus semantics,
never to dependency invalidation. Legitimate expensive units are bounded before
building hidden hiccup; collapsed markup alone is not a compute bound.

The streamer is a **role, not a process** — any process holding a read-replica + a
tx-listener can play it, so the UI-host is relocatable and can split into N
streamer-processes later (see [[architecture]] for the deployment topology).

**Streamed surfaces are verified server-side, not in a browser agent.** A
long-lived `text/event-stream` 503s through the in-tool chrome agent's net layer,
so verify a streamed change with a Node client that opens the gzip stream, gunzips
the payload, and asserts it changes on a real tx; the final live-morph eyeball is
the owner's, in real Chrome.

## Errors render as surfaces

Any render failure becomes a **`:seon/error`** value (the one base shape — see
[[data-model]] §6) instead of crashing siblings. The html render shows it as an
**error card** — friendly message, the offending block/route name and symbol, an
actionable hint — ancestors and siblings untouched, self-healing on the next
render. The SAME source feeds the agent's **warnings block** (its ai render), so a
render failure the agent owns enters its prompt as fix-oriented prose; when the
underlying fn is fixed, both the error card and the warning vanish (pure fn of state,
never stored). Route/layout throws flow identically via the error-catch
middleware.

## Total override — the downstream proof

Every layer is a symbol or a datom; a downstream cluster overrides each by
reusing the same primitives, with zero `src/seon` edits:

| Layer | Override mechanism | Default |
|---|---|---|
| **block set** (supporting surfaces) | `ctx/install!` / `ctx/remove!` from downstream namespaces | the configured DB block set |
| **initial block tree per cluster** | explicitly applied manifest `:seon.config/agent-context` / `:seon.config/root-context`; omission from the complete route population removes a route | schema/default compiler input materialized as DB facts |
| **a surface's look** | the block's `:seon.render/html` symbol | Seon's html render fn |
| **focal `#agent-view-primary-canvas` (the canvas)** | the agent's `:seon.render.canvas/content` symbol | Seon's `welcome` surface |
| **the calm hero error (a broken canvas)** | `set!` of `seon.render.canvas/error-response` (the calm hero keeps the agent-facing `:seon.render/ai`/`:seon.render/error` and swaps only the human hiccup) | Seon's “canvas is updating” card |
| **slot / view / entity error cards** | `set!` of `seon.render.canvas/error-card` (the `(fn [:seon/error] → hiccup)` seam the `render` / `slot` / `render-entity-html` catches call) | Seon's `default-error-card` |
| **root agent’s view (`/`)** | the `/` route handler symbol (root's agent-view layout) | seon's root agent-view layout |
| **routes / apps** | explicitly applied `:seon.config/routes` → exact `:seon.route/*` rows | config-compiler route input materialized as DB facts |
| **brand head — name / tagline / CSS** | explicitly applied `:seon.config/brand` → exact `[:seon.web.brand/id "brand"]` DB facts | config-compiler brand input / Phosphor |
| **client JS** | `SEON_EXTRA_PUBLIC` + scripts | datastar.js |

acme installs at preload in its own namespaces (loaded via `SEON_EXTRA_SRC`),
where it already wires its overrides. There are **two error seams**, and acme
`set!`s BOTH so every error surface on the page is branded:

- the **focal `#agent-view-primary-canvas`** flows through the canvas path:
  `seon.ui.agent-view/agent-view` calls `render-agent-canvas`, resolving the agent's
  `:seon.render.canvas/content`, and a throwing surface routes through the calm
  hero seam `seon.render.canvas/error-response` (the human never sees the
  failure here — it rides the agent twin, so the hero does NOT delegate).
- the **slot / view / entity error cards** (`render` / `slot` /
  `render-entity-html` catches) route through the SEPARATE
  `seon.render.canvas/error-card` var (`(fn [:seon/error] → hiccup)`,
  defaulting to `default-error-card`).

One `set!` per seam — two lines in `acme.overrides` — brands every error card on
the page.

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
- [[agent-runtime]] — the loop that assembles the prompt, fact-first agent initialization, and the run-status block's data source (`derive-status`).
- [[toolkit]] — `my.canvas` and the agent functions that drive the canvas.
- [[context-rebuild]] — the measured arc for knowledge-on-demand (cards +
  state-gated teaching + pull); imported `my.skills` bodies remain explicit
  overrides rather than a default context block.
- [[roadmap]] — current code state + the dependency-ordered migration to this target (Lane U).
- [[datahike-primer]] — the datahike-in-the-grain mindset.
