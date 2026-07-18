---
type: architecture
status: active
tags: [architecture, web, agent]
---

# UI — pages, blocks, renders, and routes

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

The human's UI and the agent's prompt are the same data, dual-rendered. Every
page is a derived projection of the database; nothing rendered is stored. The
context unit is the **block**; the engine is `seon.render`; the front door is
**reitit**; the live channel is a Datastar **morph** stream opened by one
database-scoped selective interest. Loopback streams are uncompressed by
default; remote compression is explicit configuration. Every layer is a
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

**prompt == page by construction.** Both derive from the same blocks at the
turn's complete ordinary database value. The compiled prompt child acquires
and formats the AI renders in `:seon.agent.ctx/priority` order; the web UI places
the same blocks' HTML renders into a layout's slots. "What the agent saw at turn
N" is a re-derive from that exact value; `:t` alone is not a durable bookmark.

**The typed block renderer.** Above `seon.ui.html` sits one reusable value→hiccup
layer, `seon.render/block` — `(block view x)` dispatches on the value-KIND `x`
carries (the namespaced key ON the value, never a stored `:kind`): a **message**
(`:seon.render/markdown`) → `seon.ui.markdown/md->hiccup`, a **source**
(`:seon.render/source`) → `clj->hiccup`, a **data** projection → the value panel, a
**`:seon/error`** → an error card, a literal **hiccup** vector → passthrough, and
anything else → the data panel (never throws). The transcript and the canvas both
route their bodies through it, so every surface "just displays the block."

**Markdown renders server-side.** Agent text becomes Hiccup through
`seon.ui.markdown/md->hiccup`. The view shim does not parse Markdown in the
browser; every message and eval body uses the same server-side projection.

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

**The database browser pays for opened data.** `/data` uses the same Datastar
Datastar feed and observed-read invalidation as every other live page. Its
default navigator derives from installed schema only. Selecting an attribute
opens a bounded AEVT window through `seon.db/index-datoms`; the URL carries the
last visible index cursor, and the server reads only enough rows to render
the page and prove whether a next page exists. It never scans every entity or
transaction to manufacture counts. Domain attributes lead by default;
framework attributes remain reachable explicitly.

**Large context twins are summaries first.** Plan roots render as compact
title/progress disclosures; only the focused root starts open, and its tree has
a bounded internal scroll region. Long titles and goals line-clamp, every
technical surface wraps or horizontally scrolls, and the canvas has a bounded
default height. Scale is handled by disclosure and windowing, never smaller
unbounded text.

**Capability + cache.** The async outer web/context owner acquires the authored
program and its ordinary inputs at one immutable database value, invokes the owning
agent's compiled Bun child under the one host deadline, and gives the pure
synchronous renderer only the ordinary result. Agent-authored renders, layouts,
and route handlers never run inside the web host or perform leaf RPCs. The
byte-stable cache prefix at low priority is preserved for provider
prefix-caching.

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
is a surface. Pages may have different layouts, but all use one rendering,
render-unit, routing, and live-morph mechanism in one route tree:

- **agent view** (`/agent/{id}`) — one agent: a large primary panel plus a right
  rail containing every current HTML context-block render ordered by database
  transaction recency. Selecting a rail card previews that render in the
  primary panel; its explicit pin control keeps it selected across subsequent
  updates. Missing and AI-only renders are omitted. The canvas is NOT a
  `(slot :canvas)` block — it is the agent's focal surface projection.

  Two focus values are deliberately distinct. **Agent-derived focus** is shared
  database meaning: the agent's `:seon.render.canvas/content` pin when present,
  otherwise its **last agent-updated surface**, otherwise
  `seon.render.canvas/welcome`. **Page focus** is this tab's valid explicit
  surface pin when present, otherwise agent-derived focus. An unpinned rail
  selection is transient and the next deliberate surface update replaces it.
  The pin is scoped to the tab's database-backed web-session location; it never
  changes another tab or becomes an agent-global selected-surface projection.

  Renderer recency is the latest transaction by this agent through the REPL,
  found by a bounded indexed history lookup over scoped inputs captured by the
  renderer's current runtime-observed database reads; canvas writes share that
  same database value. Content recency orders the rail, while focus recency treats
  either direction of the human-agent conversation as a transcript update and a
  canvas/domain write as a canvas update; eval bookkeeping alone never steals
  focus.
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
- **the root system view** (`/`) — root remains the supervising
  `:seon.agent/id "root"`, but `/` uses a dedicated system layout over the SAME
  blocks, render units, route resolution, database projections, and Datastar morph
  feed as every other page. It is not wrapped in the ordinary-agent heading,
  context rail, or canvas pin. Its primary surface is an attractive, calm grid
  of ordinary-agent work sessions; root itself is not rendered as a recursive
  card. System-scoped blocks query across all agents. A cheap card shell always
  shows identity, derived state, and the agent-derived focus label. Visible
  cards materialize that surface's compact HTML face through
  the same `seon.render.surface` catalog/focus/materializer used by the agent's
  own page;
  its working data uses colocated `:seon.render.surface/*` keys. Expanded details
  lazily show up to five recent messages and failed evals. Each card overlays a
  concise derived work description: an active plan's goal/title and current
  active-or-ready step first, explicit purpose second, then a bounded recent
  conversation fallback. This is a projection of existing facts, never a stored
  display summary. These are independent view units, so one agent update does
  not rebuild the fleet. Dive
  into one via reverse routing (step back to see all, dive into one). Its human
  input addresses root, whose
  deliberately small role context is to understand the fleet, start/select an
  ordinary agent, delegate, and route the originating browser tab there. Root's
  operational detail comes from its orchestration/navigation namespace cards
  and current-namespace source, not a long root instruction block. It shares
  block, render-unit, route-resolution, and feed machinery rather than creating
  a second reactive or routing system. It grounds the render and
  route tree: root system view (`/`) → per-agent views (`/agent/{id}`) → apps. (Root's
  lifecycle/orchestrator facet lives in [[agent-runtime]]; here it is just the
  agent whose supervising view is `/`.) Its layout differs from an ordinary
  agent page while its rendering and live-update mechanisms remain identical. A
  fresh database also contains one ordinary agent; initial navigation opens
  that ordinary agent while `/` remains available as mission control.

  `system-view`'s AI twin always names every agent and its status/focused
  surface. Within one explicit block budget it adds non-root canvas-AI,
  five-message, and recent-failure detail in the order running → erroring →
  recent. A cap never
  silently drops agents from the list; it marks which detail was omitted. The same twin
  includes the normalized location from the root message's originating browser
  session, so root knows what that human is currently seeing.

  Host telemetry is a separate optional system-status surface, not prose added
  to every turn. It consumes the operator's one reusable process-status
  projection and samples pod/writer liveness, CPU, RSS, uptime, and
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
  Raw AI disclosures are lazy slices of the already acquired prompt result,
  not independent database render units; opening one performs no child call.
  It uses the same Datastar subscription graph and activation door as every
  other live page, not a provenance-routed debug interest. With no open page it
  owns no database interest or render work.
- **app** (`/agent/{id}/app/{x}`) — an agent-authored sub-page; its route handler
  is an agent layout symbol executed by that agent's bounded Bun child.

## The persistent header — one shared render unit

Every page carries a fixed-top **status bar**, `seon.ui.header/system-header`
(NEVER throws — degrades to a brand-only bar).
Left→right: the brand (`seon.web.brand`, links `/`); agents-by-state dots+counts
(reusing `seon.render.system/fleet-summary` — one fleet counter, not
re-derived); datom count (links `/data`) + `SEON_EMBED` on/off; and a
`+ new agent` button + home/data links + a health dot. It is one shared stable
  render unit: a relevant database dependency change renders it once and
  fans the same complete element to every subscribed page. It does not recompute
  inside every whole view and it uses a cheap index count rather than reconstructing
  every database entity. The `+ new agent` button POSTs the one `/agents` creation door
  with an empty purpose and switches to the new `/agent/{id}`. The same door
  accepts an optional purpose from a root-fleet form; there is no separate
  creation or agents page.

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
  the Bun web host) consuming `:seon.route/*` datoms. A route datom carries its pattern,
method, unique name (reverse routing), owning agent (`:seon.route/owner`, rides as
route-data for auth), and a `:seon.route/handler` symbol that **IS a layout
symbol** — the same render machinery as a block's html render, not a separate
mechanism. `db->routes` projects the datoms into reitit's route vector. A small
boundary translates Bun's WHATWG Request into Ring routing data; handlers return
WHATWG Response values. The router remains a pure derived value of the route
datoms rebuilt on tx via a reloading thunk. This replaces hand-rolled
`case`/`cond`/`re-matches` dispatch. (The `:seon.route/*` attributes are
registered per [[data-model]].)

- **Seeded core routes:** `/` owns one dedicated root shell and one feed,
  `POST /agents` creates an agent, and `/agent/{id}` owns the ordinary agent
  shell and feed. A page shell and its long-lived SSE stream are distinct GET
  routes. The one action door is
  `/agent/{id}/call` (POST); `POST /agents` is the sole agent-birth HTTP door
  and shares the same database route projection.
  Agents add `/agent/{id}/app/{x}` rows. Application functions may live in any
  allowed namespace; route ownership and source-transaction authorship are
  independent from namespace organization.
- **Nested routes ARE nested layouts** — reitit meta-merges route-data parent →
  child (`:seon.route/owner` + middleware flow down). `match-by-name` gives reverse
  routing; build-time path/name conflict detection catches overlaps the
  hand-rolled `cond` silently shadowed.
- **`/agent/{id}/call` is the one action door, and the capability gate
  (`seon.web.reactive.call`) remains the authorization boundary.** reitit dispatches the URL to that one
  per-agent door; the fn rides as a route-data **descriptor** (the `?fn=` param),
  NOT its own route — **namespaces are not a routing level**. The gate authorizes
  the fn by proving at one immutable database value that the route agent is
  live and that the registered function's source transaction was authored by
  an agent through the REPL process and that the function is not private.
  Public agent-authored functions are shared
  cluster capabilities: the caller and original author may differ, and the
  function may live in any allowed application namespace;
  refusal precedes any invoke; args stay data; the call runs in the owning
  bounded Bun child → it transacts → the page re-derives and the stream morphs.
  reitit replaces the FRAGILE dispatch, not the SECURE gate.
- **Interactivity is plain Clojure.** Agents author fn-calls in handler slots; a
  render-time server-side postwalk rewrites a fn-call `(cancel-order! id)` or a
  fn-ref `submit-order!` into one standard datastar `@post` to the agent's
  `/agent/{id}/call` door (fn-call args transit-serialized in the query; the
  fn-ref case pulls form values from datastar **signals** — the POST body).
  The render owner supplies the agent id; ordinary Clojure resolution supplies
  the fully qualified function symbol. Bare symbols resolve in the renderer's
  authoring namespace, while already-qualified symbols remain unchanged.
  Transient client state — an input value, a popover, a time-slider — lives in
  datastar signals, never in DOM attributes, so a whole-element morph never
  clobbers it. Routing is orthogonal to this rewrite.
- **Auth + error-catch ride as middleware.** Per-route concerns are reitit
  route-data middleware referenced by keyword through a registry; a `:compile`
  middleware reads route-data and vanishes when N/A. Auth is wired empty — adding
  it later is one keyword + one registry entry, zero handler edits.

### Database-backed human location and root-directed navigation

Each browser tab owns one compact `:seon.web.session/id` represented by database
facts defined in [[data-model]]. Tab-local browser storage keeps the
`{:db-name db-name :session-id session-id}` tuple needed to reconnect it. The
session carries a ref to the human plus one normalized local location string.
That location is the fact: route name, agent target, and URL are derived through
reitit rather than duplicated as more session attributes. Transaction metadata
provides recency, so there is no stored `updated-at`, `active?`, or presence
registry.

First load has no browser-generated identity. Bootstrap accepts a stored tuple
only when its database name matches the current database and its lookup ref
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

An agent page's explicit surface pin is the one meaningful sub-route state: it
is encoded in the normalized location's query component. With no pin parameter,
the page uses agent-derived focus. Clicking a rail card changes only the
transient selection; pinning it updates the URL/session fact and Datastar signal
together. Reload restores that tab's pin, and root can query it through the
originating session, but a fleet card does not adopt it. Unpinned selection,
scroll position, disclosure state, and form signals stay browser-transient and
are not falsely promoted to database facts.

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

## The live channel — selective Datastar morph SSE

The live channel is **ours** (reitit has no streaming primitives by design): one
database-scoped interest receives an ordinary transaction report plus conservative
changed-attribute evidence, and the view is a pure `:db-after`-pinned derivation.
There is no web-host Datahike replica or global transaction broadcast. The agent
only transacts datoms; it never opens or writes a stream. The Bun web host
implements the `view = f(db)` model through `seon.web.datastar`.

- **view = f(db), one complete morph.** Reitit route match plus normalized
  path/query defines one semantic subscription key. Initial paint and each
  relevant later database value derive the complete `[:main#app-view …]` and
  emit one `datastar-patch-elements` event (default `outer`). Conditional
  elements therefore disappear honestly without a second patch or renderer.
  Equivalent tabs share one render and the same serialized bytes; their view
  IDs own only socket replacement. Frozen as-of subscriptions do no current
  work.
- **Async work is bounded by the normalized subscription.** One subscription
  runs at most one database-value-pinned async render and retains at most the newest
  coherent pending database change. A completion publishes only while the same
  subscription still owns the latest request; a transaction racing the initial
  paint requires a new complete `#app-view`. Equivalent sockets share the one
  invocation and its serialized bytes. Closing the final consumer releases the
  active/pending ownership, and any later Promise completion is inert. There is
  no catalog, active-token registry, per-unit fetch route, or partial-render
  acceptance path.
- **native stream + configurable compression.** `Bun.serve` returns one direct
  `ReadableStream` response whose controller owns browser backpressure and
  disconnect. Loopback development uses identity encoding for observability and
  latency; remote deployments enable measured compression by configuration.
  `SEON_FEED_COMPRESSION=gzip` selects Bun's native Rust zlib stream through
  its standard `node:zlib` namespace; each SSE event uses `Z_SYNC_FLUSH` so it
  reaches the browser immediately. `Accept-Encoding` remains authoritative and
  an identity client is never compressed. A standard `CompressionStream` is
  not used because it buffers event payload until stream closure, and separate
  per-event gzip members are not used because common HTTP decoders stop after
  the first member.
  Compression changes only response bytes, never event or database semantics.
  One shared heartbeat timer emits inert SSE
  comments for every writable feed, so reverse proxies can keep otherwise-idle
  views open without one timer per socket. A
  backpressured connection retains only its newest derived event and resumes
  after Bun's `flush(true)` drain boundary; stale UI states never form an
  unbounded write queue.
- **Selective interest, complete rendering.** Each renderer declares the
  attributes that can affect its complete projection. The one database interest
  is the union across live subscriptions; native transaction `:tx-data`
  supplies conservative changed-attribute evidence and `:db-after` supplies the
  exact immutable value to render. Intersecting attributes enqueue the semantic
  subscription once, while unrelated commits merely advance its accepted
  database value. Missing evidence fails open. Identical serialized output is
  suppressed. Coalescing has a bounded maximum wait, so continuous structural
  writes cannot starve a view.
- **Declared renderer reads are database facts.** The analyzer tee persists
  qualified keyword reads as `:seon.fn/read-attrs`; focus/recency and an optional
  cold-start hint consume those facts. They never regex-scan function source as
  a compatibility path. The declared set is non-transitive through helper
  calls, so it can never exclude a runtime-observed dependency or veto exact
  replay. The declared set narrows wakeups but never changes result semantics.
- **Caching is automatic at the subscription boundary.** Core and agent-authored
  renderers do not call `memoize`. One semantic subscription retains the
  database value that proved its last complete serialized event and shares that
  event across equivalent sockets and reconnects. Unaffected commits advance
  the proof value without recomputation; affected commits replace the complete
  event only after the latest render finishes. A source or projection-input
  change selects a different semantic subscription. Eviction affects
  performance only.
- **One generic transition engine.** Root, ordinary-agent, canvas, context,
  debug, and `/data` layouts use the same
  acquire/select/enqueue/render/serialize transition. Page namespaces define
  complete projection and presentation, not custom invalidation algorithms.
  Agent-authored surfaces inherit the mechanism by participating in their
  ordinary complete page render; no special API or caching instruction is
  exposed to the agent.
- **Separate GET feed path.** The shim page (`/view`, `/agent/{id}`) and its live
  stream (`/view/feed`, `/agent/{id}/feed`) are two GET URLs; the shim's
  `data-init="@get('…/feed')"` opens the stream. Two distinct URLs sidestep the
  GET/POST same-URL cache collision that forces hyperlith's same-path-POST `&u=`
  hack, and this matches datastar-clojure's own example (`tiny_gzip.clj`: page `/`,
  stream GET `/updates`). reitit routes the feed GET to the SSE handler, which rides
  Bun's native server. One final host function converts ordinary Ring response
  data to a Bun `Response`; no Node response object or hijack sentinel enters
  page code.
- **Transient state is signals; time-travel and reconnect are just re-renders.**
  Transient client state lives in datastar **signals** only, never DOM attrs. Time
  travel is `view = f(db)` over the bitemporal DB—a different
  resolved commit, the same render. Reconnect needs no numeric `since-t` replay:
  the first paint fires immediately on open and repaints the current view. A
  historical request carries the complete canonical
  `{:db-name :t :as-of :since :history :datahike/commit-id}` value; the server
  verifies its retained lineage and echoes that value in its response/bookmark.
  There is no bare-`:t` compatibility selector. That feed is marked frozen and
  excluded from current broadcasts. No explicit database value means the current
  dependency-reactive feed. The `/agent`
  shim's time-travel controls (siblings of `#app-view`) own the feed connection;
  one `data-effect` `@get` lets Datastar's per-attribute auto-cancellation abort
  the prior stream, so exactly one stream targets `#app-view`. Signals hold
  `$live`, a transient scrub `$t`, and the last complete database value; only
  that value enters the feed URL/cache key. The slider domain may display `:t`
  values and human timestamps, but `:datahike/commit-id` plus `:db-name` is the
  durable lineage bookmark. A reconnect whose database name changed or
  whose last commit is not an ancestor receives a full reset, never numeric
  replay across lineages.
- **The hard invariant: no agent code ever touches an SSE connection.** Agent →
  datom → committed `:db-after` value → selective derivation → morph, one way; actions
  reverse it (a browser POST → the owning agent's sandbox → result datoms →
  selective derivation → morph). The database is the bus both ways.

Each authored invocation owns one immutable input message and parent-side
deadline. No process-global input/deadline cell can be overwritten by a nested
or future concurrent render. A deadline or disconnect poisons that child
against reuse before termination, so a late result cannot enter a newer render.
Repeated failure logging/recording uses a bounded FIFO suppression window; it
cannot retain an unbounded set of broken symbols.

The same Datastar subscription mechanism serves root, agent, debug, and data
views. There is no provenance-routed debug stream or unused generic `/sse`
registry. Transaction user/process is relevant to agent-derived focus semantics,
never to dependency invalidation. Legitimate expensive units are bounded before
building hidden hiccup; collapsed markup alone is not a compute bound.

The streamer is a role inside the Bun web host: it owns one direct authority
session plus database-scoped interests, derives render units at exact database
values, and writes browser patches. Page code depends on that role's data
contract rather than a transport-global registry.

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

## Downstream composition

A downstream cluster composes the same public mechanisms: route facts choose
page handlers, block facts choose renderers, `install!`/`remove!` reconcile a
block collection, canvas facts select focal content, and an explicitly selected
manifest supplies brand and route populations. Consumer-specific files,
launchers, and wiring remain in the downstream repository. Mutable global
`set!` seams are not target state; a reusable customization becomes a public
symbol selected by facts or config through the one render engine.

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
- [[roadmap]] — implementation state, gaps, work order, and evidence.
- [[datahike-primer]] — the datahike-in-the-grain mindset.
