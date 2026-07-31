---
type: architecture
status: active
tags: [architecture, web, agent]
---

# UI — pages, blocks, renders, and routes

> **Target design** (present tense). Implementation state, gaps, order, and
> evidence live only in [[roadmap]].

The human's UI and the agent's prompt are the same data, dual-rendered. Every
settled page state is a derived projection of the database; transient streamed
prefixes are process-local render-unit inputs, and nothing rendered is stored. The
context unit is the **block**; the engine is `seon.render`; the HTTP router is
**reitit**; the live channel is a Datastar **morph** stream backed by one
normalized reactive registration and database-scoped selective interest per
demanded computation. Loopback streams are uncompressed by
default; remote compression is explicit configuration. Every layer is a
symbol or a datom, so a third party overrides any of it — blocks, canvas,
layout, the root agent’s view, routes, CSS, client — reusing the same
primitives, with zero `src/seon` edits.

The web UI runs inside the **cluster JVM** beside the writer and the agent
graphs. It
serves HTTP through http-kit, frames SSE through the Datastar Clojure SDK, and
reads the cluster's current immutable database values directly. Agent-authored
renderers run only through the one guarded SCI invocation boundary: the retained
`ctx`/`fork`, one `:interrupt-fn`, the render invocation-class `time-limit`,
value admission, and bounded output. No direct Var invocation or
`requiring-resolve` path bypasses that door. Supervision, bounded evals, and
cheap flow-graph rebuilds protect the process; the UI does not rely on a
process wall.

**Kind is the boundary (owner, 2026-07-29).** A kind is never chosen
interior to the system: the delivery boundary states it — the agent
prompt boundary asks for `:seon.render/ai`, the browser boundary for
`:seon.render/html`, the log sink for `:seon.render/log`. The same
value returning from the same eval renders ai to the agent and html to
the browser because the ENDPOINT differs, not the data. Only boundary
owners name a kind; interior code passes units through; renderers
declare what they can produce and the boundary picks. This is why kind
is a request argument and never a stored fact: a boundary is a place,
not a property of data.

## Interactions are database transactions

An interactive control posts the authored handler symbol and ordinary
arguments. The route validates the handler's exact committed schema and source
identity, transacts one pending interaction/run fact, and acknowledges
immediately. The HTTP response is never an execution-result channel.

The agent's own flow graph acquires and executes the interaction. Its
result or flat error becomes committed interaction facts. A normal HTML block
queries the latest terminal outcome for the page's agent and returns no render
when no such fact exists. The existing database interest, equality
suppression, `mult`, per-tab `(sliding-buffer 1)` tap, and Datastar morph chain
therefore own every visible outcome; reconnecting derives the same surface
from database truth.

## The projection contract — one router, an open kind set

Rendering is not a UI mechanism that other things borrow; it is one contract
the UI is the largest consumer of. A **unit** is schema'd data discovered by
the recursive entity walk. The boundary requests an output kind, and the
renderer resolves one projection through the program graph. First-party Vars
remain live under re-evaluation. A nonidentical base-Var redefinition is
accepted and emits the ordinary Clojure-style warning. Agent-authored corpus
functions are installed SCI values invoked through the guarded door, never
host-resolved Vars.

The kind set is open, and each kind names its consumer:
`:seon.render/ai` is read by the prompt, `:seon.render/html` by a surface,
`:seon.render/log` by the process log. Adding a kind adds a projection without
changing the walk. Explicit declarations ride on the value being projected;
otherwise the resolver queries the program graph and schema metadata.

The log kind governs projecting a renderable unit for a log sink; it does not
turn process-control annunciators into render units. A recursion-fence failure,
an overflow callback with no admitted fault fact, a development panic
annunciator, or a startup/export invariant warning writes a brief direct stderr
diagnostic because it reports the state of the projection or durability
machinery itself. Once a durable fault exists, its reusable human
representation is still only the notice's `:seon.render/log` projection. A
direct annunciator never becomes another stored presentation or a consumer
call to the fault's projection function.

Failures are flat `:seon.error` values, never throws: the router runs on the
error path, so an undeclared kind, an unresolvable symbol, and a projection
that throws are each a value naming what is broken.

### One resolution chain and one floor

Every discovered value resolves through one chain, most specific first:

1. explicit `:seon.render/ai` or `:seon.render/html` keys on the value;
2. a same-schema renderer found by a program-graph query in the viewing
   agent's namespace, then the data's owning namespace;
3. the schema-attached default renderer; and
4. the structural floor.

Function rows carry input and output schemas, so namespace override resolution
is one ordinary corpus query through the query cache. There is no producer rule
registry, slot redirect, or second floor. A broken renderer yields a flat error
unit and never promotes a different mechanism silently.

## The block and its two renders

A **block** is the informal name for one renderer function call's identified
output in either projection, never a database entity or stored data type. Its
identity derives from the renderer function and explicit arguments; the HTML
projection derives one escaped stable DOM element id from that identity.

- **ai render** (`:seon.render/ai`) → prompt bytes.
- **html render** (`:seon.render/html`) → a surface whose output is hiccup.

The same call may provide either or both projections. Explicit render keys on a
value override the corpus/schema chain, but their absence never makes the
system partial: the structural floor can render any value in both projections.
AI always includes every discovered unit. On the curated HTML page, a unit that
would reach the generic structural floor is hidden by default and appears only
when that tab enables **show everything**. The checkbox is transient per-tab
display state, never a durable fact. Totality and default visibility are
separate decisions.

**The morph target is the block.** Each block is patched independently at its
stable element id, so a transcript append repaints the transcript and the
header is not recomputed. Cache and equality state are keyed by renderer
function call, so equivalent calls share one byte result before fan-out.
Whole-page morphing is not the live-update fallback: initial paint builds the
page once, then every change targets the smallest stable block whose bytes
changed. Serialization, authored evaluation, and slow-reader pressure
therefore scale with changed content rather than page size.

## Membership is the walk — one derivation, no stored set

**Blocks are derived, not installed** (owner ruling 2026-07-31; see
`docs/prds/sci-execution-runtime/plan/README.md`). The render walks the agent
entity and the values reachable from it at one database value, resolves a
renderer per value, and emits one block per rendered value. Membership and
order both fall out of the render calls: order is the basis at which each
call's bytes last changed ([[context]]), and there is no static scaffold path.
The cluster entity owns the authoritative refs to shared instruction rows;
agent entities carry only genuine additions. Both are ordinary schema'd facts
rendered by ordinary, overridable renderers, and instruction text mutates in
place on its stable identity.

**Resolution is one chain, most specific first:**

1. explicit `:seon.render/ai` / `:seon.render/html` keys ON THE VALUE;
2. a same-schema render function in a governing namespace — the viewing
   agent's own namespace first, else the data's owning namespace
   (viewer-constancy);
3. the schema-attached default renderer;
4. the structural floor.

There is one chain and one floor. A layout never redirects resolution, and no
stored membership, band, or priority attribute exists.

Global-vs-per-agent is decided by the DATA the render fn queries, never by the
block: a `:my.kb.*` row carries no agent ref (one KB, every agent sees it), a
`:my.plan/*` row carries `:my.plan/agent` (each agent sees its own). The render
fn scopes by what it reads. (See [[data-model]] for the domain schemas +
data-ref scoping; [[agent-runtime]] for the fact-first atomic birth transaction
and post-commit safe declaration load.)

## The render engine

One engine, `seon.render`, renders every page and the prompt. Every render call
requires a complete immutable database value under `:seon.db/db`; absence is a
flat `:core-bug`, never permission to consult an ambient latest value. The
engine is a single guarded walker over the agent's blocks in two views (`:ai` →
String, `:html` → hiccup). Renders are projections, never persisted. A failing
render yields a flat error value (see [[data-model]] §6) for that render only;
siblings never crash.

**prompt == page by construction.** Both derive from the same blocks at the
turn's complete ordinary database value. The turn acquires and formats
the AI renders in last-bytes-changed order ([[context]]); the web UI applies
the same order, including branch tie-clustering, to visible HTML renders.
"What the agent saw at turn N" is a re-derive from that exact value; `:t` alone
is not a durable bookmark.

**The structural floor is one merged data browser.** It combines the bounded
value skeleton with `/data`'s drill navigation on the admission codec's one
walk discipline. It can render any value without throwing, preserves
identities/indices and explicit elision markers, and pays only for opened data.
Specialized message, source, error, and hiccup projections sit above it through
the one resolution chain; they do not create another walker or floor.

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

**The database browser pays for opened data.** `/data` exposes the same
structural floor directly. Bounded expansion uses a cursor that says where to
resume instead of silently eliding.

Navigation is a `get-in` path, and the cursor — path plus offset — lives in the
URL. So a drilled position is a link somebody can send, reconnecting to one
costs one derivation, and nothing stores or retracts where a reader is looking.
Breadcrumbs are the path's own prefixes: the path IS the trail, so there is no
history to keep.

The window reports its honest total, because a reader must know a window is a
window, and offers a resume offset exactly when there is somewhere to resume, so
"is there a next page?" is key presence rather than arithmetic repeated at every
call site. Ordering is derived and stable — an unstable order shows a row twice
and never shows another.

The cost of displaying a page does not depend on the size of what it links to.
A child is summarised by what it IS, never by walking it. Selecting an attribute
opens a bounded AEVT window through the same discipline; the URL carries the
last visible cursor, and the server reads only enough to render the page and
prove whether a next one exists. It never scans every entity or transaction to
manufacture counts. Domain attributes lead by default; framework attributes
remain reachable explicitly.

A drilled page carries no feed: repainting a page under a reader would move the
ground they are standing on, so reload is the refresh and the URL is the state.

**Large context twins are summaries first.** Plan roots render as compact
title/progress disclosures; only the focused root starts open, and its tree has
a bounded internal scroll region. Long titles and goals line-clamp, every
technical surface wraps or horizontally scrolls, and the canvas has a bounded
default height. Scale is handled by disclosure and windowing, never smaller
unbounded text.

**Capability + cache.** The cluster JVM acquires authored program and ordinary
inputs at one immutable database value and invokes authored code through the
one SCI door with the `:interrupt-fn`, invocation-class `time-limit`, and value
admission. A function may raise the class default only through `defn` metadata
lifted into its program row at index time; render owns no private timer dial.
Renderers may request genuine effects only through
`seon.effect/request!`; the normal render path remains pure and returns
ordinary data. The byte-stable prefix of longest-unchanged blocks is
preserved for provider prefix-caching.

**Byte identity is a design property.** The complete database value, verified
configuration and file fingerprints, render inputs, and program artifact
determine the cacheable bytes. Host timezone, process clocks, environment
reads, ambient database state, and process-local result liveness cannot alter
them. Process-local inputs are explicit renderer arguments and therefore part
of the per-call identity; no free tail renders outside the block system.

**Context assembly is its own domain.** How the prompt derives a tree and orders
units by last-bytes-changed (no bands, no priority attributes) and the
namespace-as-location model live in [[context]] — this doc owns the shared
block/render machinery and the human-facing projection. The structural HTML
floor remains available for every block even when the curated page hides it.

### Render coverage converges on blocks

A domain value becomes broadly observable by accreting projections on the same
unit: AI for agent context, HTML for a surface, log for operations, and later
kinds without a router change. Consumers never rebuild or reclassify it. A new
consumer asks the router for its kind; a producer that knows more selects a
specialist while building the unit.

Prompt assembly follows the same convergence. Context sections are AI renders
of blocks, not strings assembled beside the renderer. As domains gain AI, HTML,
and log projections, prompt, page, and operational views become different
bounded projections of the same units rather than parallel presentation
systems.

## Streamed replies — the one high-churn path

A streamed reply is the only genuinely high-churn thing the UI shows, and it
rides a channel, never the writer. Partials are COALESCED COMPLETE VALUES
offered onto one `(sliding-buffer 1)` conn from the turn's provider fold into
the render proc's in-port; only the settled reply becomes a database fact (its
bytes a blob behind the turn's reply ref). Streamed partials never touch a
database attribute. (This supersedes the 2026-07-23 no-history-attribute
streaming design, per the 2026-07-28 transport-law ruling: the one database
path is for facts; in-flight transients ride channels with loss-encoding
buffers.)

Each clause is load-bearing. Complete values rather than deltas, because a
consumer that missed a delta is permanently wrong while one that missed a
snapshot is briefly behind. A channel rather than the writer, because a
token-by-token record of a reply that already exists in full is pure churn
through the durable store. Latest-wins, so there is never a queue of stale
prefixes between the model and the eye, and the settled fact simply supersedes
the last snapshot.

**The producer side is isolated because it runs on the provider's socket
thread.** The fold offers the newest complete prefix onto the sliding-1 conn
and returns; the put never parks. Presentation may lag and may DROP
intermediate snapshots — both are correct — and it may never slow the model
call. The governing invariant is the provider reference's own: partial display
cannot affect transport, parsing, usage, or evaluation. A streamed call and a
one-shot call return the same completion value, so nothing downstream can tell
which transport ran.

The live token count derives from the SAME fold that produces the text, never a
second mechanism counting the same thing twice: the provider's own usage once it
has sent one, and the chunk count until then, which is an approximation and is
named as one.

**Both are ordinary blocks.** The token counter and the streaming reply declare
`:seon.render/html` and return hiccup like any other surface, and each is its
own morph target. The highest-churn thing in the system needs no render
machinery of its own — which is the reason those two were chosen as the
exercises that prove the design.

## The tree and layouts

A **layout** is an ordinary first-party HTML renderer over an already resolved
render tree. It receives blocks and branch relationships as data and owns only
placement and CSS. It never names a block to trigger renderer resolution,
redirects one render to another, or changes membership. The retired slot
redirect has no dormant fixpoint or missing-block installation behavior.

**Expansion is bounded** by node and depth budgets shared with the structural
floor. The per-walk rendered set detects an already emitted unit and produces a
back-reference, while branch budgets bound wide fan-out. Elision is explicit
and drillable; a cycle, failed child render, or exhausted budget produces an
in-place error/elision unit so one branch never blanks the page.

**Pages are bounded folds over the entity tree.** A page begins at the route's
agent entity, walks refs, resolves each schema'd value once through the one
chain, and hands the resulting tree to its layout. The `/data` browser is the
floor exposed directly: paged `get-in` navigation into any nested value uses
the same bounded expansion and cursor discipline.

## Pages — agent view, root view, debug view, app

Every **page** is a layout arranging the already resolved HTML render tree;
each visible block is a surface. Pages may have different layouts, but all use one rendering,
render-unit, routing, and live-morph mechanism in one route tree:

- **agent view** (`/agent/{id}`) — one agent: a large primary panel plus a right
  rail of curated HTML renders. The rail uses the same
  last-bytes-changed order and branch tie-clustering as context. Units that
  would reach the generic HTML floor are hidden until this tab enables **show
  everything**; AI-only projections remain absent from the curated rail.
  Selecting a rail card previews that render in the primary panel; its explicit
  focus control keeps it selected across subsequent updates. The canvas is the
  agent's focal surface projection, not a renderer-resolution redirect.

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
  projection and samples process-group liveness, CPU, RSS, uptime, and
  feed pressure on demand. It is one independently refreshed unit on the normal
  feed, persists no rolling projection, and contributes to root's AI context
  only when anomalous.
- **debug view** (`/agent/{id}/debug`) — a read-only walk beginning at that
  agent's entity through the one merged structural floor. It is always
  available alongside the curated page, drillable by preserved refs and
  `get-in` paths, and shows schema'd system apparatus as well as domain data.
  The view exposes AI/HTML projections, total and per-unit token estimates,
  dependency commit IDs, conservative/code revisions, digest,
  last-bytes-changed order, agent state, and turn/error-routing diagnostics.
  It ignores the curated page's floor-visibility checkbox and never transacts a
  display choice. Its page GET remains an empty shell and uses the same
  Datastar subscription graph as every other live page; with no open page it
  owns no database interest or render work.
- **app** (`/agent/{id}/app/{x}`) — an agent-authored sub-page; its route handler
  is an agent layout symbol executed by the cluster JVM with the one
  `:interrupt-fn`.

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
  every database entity. The `+ new agent` button POSTs the `/agents` creation endpoint
  with an empty purpose and switches to the new `/agent/{id}`. The same endpoint
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

The HTTP router is **reitit** (vendored `reference-code/reitit`, `.cljc`,
loaded by the cluster JVM) consuming `:seon.route/*` datoms. A route datom
carries its pattern,
method, unique name (reverse routing), owning agent (`:seon.route/owner`, rides as
route-data for auth), and a `:seon.route/handler` symbol that **IS a layout
symbol** — the same render machinery as a block's html render, not a separate
mechanism. `db->routes` projects the datoms into reitit's route vector.
http-kit supplies ordinary Ring request and response data. The router remains a pure derived value of the route
datoms rebuilt on tx via a reloading thunk. This replaces hand-rolled
`case`/`cond`/`re-matches` dispatch. (The `:seon.route/*` attributes are
registered per [[data-model]].)

- **Seeded core routes:** `/` owns one dedicated root shell and one feed,
  `POST /agents` creates an agent, and `/agent/{id}` owns the ordinary agent
  shell and feed. A page shell and its long-lived SSE stream are distinct GET
  routes. The browser-action endpoint is
  `/agent/{id}/call` (POST); `POST /agents` is the sole agent-birth HTTP endpoint
  and shares the same database route projection.
  Agents add `/agent/{id}/app/{x}` rows. Application functions may live in any
  allowed namespace; route ownership and source-transaction authorship are
  independent from namespace organization.
- **Nested routes ARE nested layouts** — reitit meta-merges route-data parent →
  child (`:seon.route/owner` + middleware flow down). `match-by-name` gives reverse
  routing; build-time path/name conflict detection catches overlaps the
  hand-rolled `cond` silently shadowed.
- **`/agent/{id}/call` is the browser-action endpoint, and the callback gate
  (`seon.web.reactive.call`) remains the authorization boundary.** reitit dispatches the URL to that one
  per-agent endpoint; the fn rides as a route-data **descriptor** (the `?fn=` param),
  NOT its own route — **namespaces are not a routing level**. The gate authorizes
  the fn by proving at one immutable database value that the route agent is
  live and that the registered function's source transaction was authored by
  an agent through the REPL process and that the function is not private.
  Public agent-authored functions are shared
  cluster capabilities: the caller and original author may differ, and the
  function may live in any allowed application namespace;
  refusal precedes any invoke; args stay data; the call runs in the cluster JVM
  with the one `:interrupt-fn` → it transacts → the page re-derives and the
  stream morphs.
  reitit replaces the FRAGILE dispatch, not the SECURE gate.
- **Interactivity is plain Clojure.** Agents author fn-calls in handler slots; a
  render-time server-side postwalk rewrites a fn-call `(cancel-order! id)` or a
  fn-ref `submit-order!` into one standard datastar `@post` to the agent's
  `/agent/{id}/call` endpoint (fn-call args transit-serialized in the query; the
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

## The in-process render flow

The live channel uses `core.async.flow` graphs inside the cluster JVM:

1. a transaction commits and offers a coalesced wake through a
   `(sliding-buffer 1)` channel;
2. the walk dumbly invokes each discovered renderer call; its per-call cache
   answers immediately unless a dependency commit ID differs by `not=`, the
   conservative revision moved, or the process-local code revision moved;
3. first-party walk/render work runs on correctly classified Flow procs, while
   agent-authored calls cross the bounded SCI launcher with `:interrupt-fn`,
   invocation-class `time-limit`, admission, and output caps;
4. the cache compares the completed bytes/digest and suppresses equal output;
5. a `mult` fans each changed stable-ID element patch to one per-tab
   `(sliding-buffer 1)` tap for each visible render unit; and
6. one connection-owned virtual thread per tab reads its tap and batches
   available Datastar element patches onto that tab's single SSE connection,
   with bounded writes.

Flow topology is static within each `graph-def`. The cluster render graph names
the interest and render procs, their `step-fn`s, bounded channels, and `conns`.
A tab is deliberately NOT a graph: connections churn with browsers while graph
topology is static, so opening a tab taps the `mult` and starts one virtual
thread that owns the SSE connection; closing the tab untaps and ends it.
Scheduling is global Flow policy, never a render-local scheduler. Blocking-only
walk/read leaves use `:io`, units proven entirely compute use `:compute`, and
mixed or unresolved work uses fail-closed `:mixed`; socket writes park their
connection-owned virtual threads. The bounded compute executor remains fair
when one agent floods wakes. Each graph's report channel and unmodified
`flow-monitor` are the operational and visualization surfaces. Flow channels
are disposable in-process scheduling state, never a second database work
ledger.

**Nothing rendered is stored.** Per-call bytes, digests, dependency commit IDs,
and last-bytes-changed bases exist only in process memory. Restart discards the
cache and re-derives demanded pages from facts. There is no stored render
snapshot, presentation attribute, or replay log for display output.

Streamed reply partials enter this same flow as another producer. The turn's
provider fold reduces its byte stream for the durable terminal reply while
offering coalesced complete prefixes onto the render proc's sliding-1 in-port;
a full buffer drops intermediate prefixes rather than delaying inference. The
terminal reply blob and attempt receipt remain the forensic facts. A reconnect
paints current database truth and does not replay transient prefixes.

### Fine-grained Datastar element patches

Initial paint is the only whole-page render. Later work is per visible render
unit: root cards, canvas, context, debug, `/data`, and shared header units keep
their stable element IDs and emit independent Datastar patches. Conditional
elements disappear through the same unit patch. Equivalent render-unit demands
share one evaluation before `mult` fan-out; a slow tab retains only its newest
patch per unit through its `(sliding-buffer 1)` tap.

Each evaluation receives a committed report's exact immutable `:db-after`
value. A renderer's read form yields its concrete attribute dependency plan and
pull selector. The cache keeps last-seen per-attribute commit IDs and compares
them directly on wake; there is no writer-side reverse registration index.
Missing evidence uses the conservative revision, and code/schema changes use
the process-local code revision. This process-local state affects performance
only and never becomes database authority.

The shim page and feed remain distinct GET routes. http-kit and the Datastar SDK
own the one SSE response per tab; `Accept-Encoding` remains authoritative.
Transient browser state lives in Datastar signals, never DOM attributes.
Historical requests carry the complete
`{:db-name :t :as-of :since :history :datahike/commit-id}` value and are frozen
from current transaction wakes. Reconnect performs an initial paint from the
resolved current or historical database value; there is no numeric replay.

The hard invariant is unchanged: no agent code touches an SSE connection.
Agent-authored renderers return admitted values; connection-owned writer
threads alone serialize
and write patches. Browser actions become database facts or guarded callback
results, and the same render flow derives the visible consequence.

## Errors render as surfaces

Any render failure becomes the flat error value from [[data-model]] §6 instead
of crashing siblings. The HTML render shows it as an **error card**—friendly
message, offending render-call identity and symbol, actionable hint—while
ancestors and siblings continue. At the SCI boundary the same failure enters
durable problem routing as a fact naming the agent that ran the code. That
agent's next context reaches it and root receives the escalation through the
same graph; no exception crosses into a proc and no failure disappears after
one page pass. Fixing the underlying function replaces the current error card
on the next render, while database history retains the forensic fact.
Agent-authored route/layout failures follow the same guarantee.

## Downstream composition

A downstream cluster composes the same public mechanisms: route facts choose
page handlers, schemas and entity facts choose renderers through the one
resolution chain, canvas facts select
focal content, and an explicitly selected
manifest supplies brand and route populations. Consumer-specific files,
launchers, and wiring remain in the downstream repository. Mutable global
`set!` seams are not target state; a reusable customization becomes a public
symbol selected by facts or config through the one render engine.

## Malli throughout

Every map is a registered `:malli/schema` — the block, `:seon.route/*`, the
flat error value, layout I/O — instrumented like everything else. reitit
route-data is open maps, so our malli-validated maps ride as route-data with no
friction; reitit-malli coercion (vendored, optional) validates/coerces path-params
/ query / body against a route's `:parameters` schema for free, since we
malli-everything already.

## See also

Strict single-ownership: when a fact you need is owned by another doc, follow the
link and read it.

- [[architecture]] — the map: glossary, the cross-cutting principles, deployment topology.
- [[data-model]] — the block, route, and flat error schemas these renders read, and the `my.*` domains.
- [[agent-runtime]] — the agent graph that assembles the prompt, fact-first agent initialization, and the run-status block's data source (`derive-status`).
- [[toolkit]] — `my.canvas` and the agent functions that drive the canvas.
- [[context-rebuild]] — the measured arc for knowledge-on-demand (cards +
  state-gated teaching + pull); imported `my.skills` bodies remain explicit
  overrides rather than a default context block.
- [[roadmap]] — implementation state, gaps, work order, and evidence.
- [[datahike-primer]] — the datahike-in-the-grain mindset.
