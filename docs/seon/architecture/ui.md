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
renderers run through the one `seon.sci.eval/evaluate` path: a fresh `fork`,
the one `:interrupt-fn`, value admission, and bounded output. Supervision,
bounded evals, and cheap flow-graph rebuilds protect the process; the UI does
not rely on a process wall.

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
the UI is the largest consumer of. A **unit** is any map that declares, per
**output kind**, the fully qualified symbol of the function projecting that
unit into that kind. `seon.render/render` resolves the symbol late — with
`requiring-resolve`, invoking the var, so re-evaluating a projection's `defn`
changes the next render — and applies it to the unit; `seon.render/kinds`
derives what a unit can become from the unit itself, so nothing keeps a
registry of kinds or of producers.

The kind set is therefore open, and each kind names its consumer:
`:seon.render/ai` is read by the prompt, `:seon.render/html` by a surface,
`:seon.render/log` by the process log. Adding a kind is one key at the producer
and one function; the router never changes. Declarations ride on the value
being projected — derived when the projection depends on who is asking, as an
error notice's steering prose does — and never as a stored symbol repeated on
every row.

Failures are flat `:seon.error` values, never throws: the router runs on the
error path, so an undeclared kind, an unresolvable symbol, and a projection
that throws are each a value naming what is broken.

### Generic default, specialist where the unit is built

Every kind has a **generic default** renderer that can project any value of its
family, and a producer that knows more points that value's key at a
**specialist** — chosen where the unit is BUILT, computed from the value's own
attributes, never by a conditional at the consumer. `seon.render.block/select`
is that choice: an ordered set of pure rules belonging to the one producer that
owns the family, first accepting rule wins, falling through to the default.
`:seon.render/html`'s generic default is `seon.render.block/data-panel`.

The two halves are one design. Because the key on the unit is the whole answer,
**the consumer never branches and the specialist's name never leaves its
producer** — which is what makes "consumers reach a value's renderings only
through the router" a property of the code rather than a rule people remember.
An error's steering prose is not a function anybody calls; it is the default
that `:seon.render/ai` points at, and a malli violation's detailed explanation
is a specialist the error producer selects from the fact's own attributes.

The rules are a producer's, not a registry: they are built from one family's own
attributes and are never consulted by anybody rendering. Selection is total,
because it runs where units are built and that is often the error path — a rule
that throws or does not resolve has not accepted, so a broken rule costs its own
specialist and never the render. Both halves are late-resolved symbols, so
re-evaluating a rule or a renderer changes the next render.

## The block and its two renders

A **block** (`:seon.block/block`, owned by `seon.render.block`) is the UI's
unit, and it declares two of the kinds above — selected by key presence, with
no stored discriminator:

- **ai render** (`:seon.render/ai`) → **prompt** text: a qualified symbol the
  router resolves late.
- **html render** (`:seon.render/html`) → a **surface**: a qualified symbol,
  resolved the same way, whose output is hiccup.

The DURABLE slot is a qualified symbol only. A literal hiccup vector or a
verbatim string is admissible on a runtime unit, but never in the database:
restricting the stored value to one type is what lets it store natively as
`:db.type/symbol` instead of a `pr-str` EDN string that every read has to
decode. A block with nothing special to say points at the kind's generic
default, which costs nothing to store and renders anything.

Presence decides placement: ai-render-only = prompt only (no surface);
html-render-only = a surface only (zero prompt tokens); both = both. A block
declaring neither is legal and renders nowhere — it is data the agent owns and
nothing displays.

`:seon.block/name` is one keyword in three roles — the prompt header, the
per-agent upsert key, and (through `seon.render.block/surface-id`) the DOM
element id — always in sync, which is what makes "the agent edits the same
thing the human sees" true. `surface-id` is the ONE derivation, so the hole and
the patch cannot drift; it ESCAPES characters it cannot use rather than
dropping them, because dropping is how two names become one id and two blocks
morph over each other. Renders are symbols; the rendered output is ephemeral,
never stored.

**The morph target is the block.** Each block is patched independently at its
own `surface-id`, so a transcript append repaints the transcript and the header
is not recomputed. This is the difference between the block set being a
convenient grouping and being the unit of live update: interest, registration
memory and equality suppression are all keyed by block, and "32 tabs, one
authored evaluation" is one evaluation of one block. Whole-page morphing is not
the live-update fallback: initial paint builds the page once, then every change
targets the smallest stable block whose value changed. Serialization, authored
evaluation, and slow-reader pressure therefore scale with changed content
rather than page size.

## Seed-copy — one collection, no merge

Each agent OWNS its complete block set in `:seon.cluster.agent/blocks`, seeded at creation.
Render reads that complete collection sorted by `:seon.block/priority` and
stops: there is no render-time merge and no separate default set — every block an
agent renders, it owns. The set is deduped app-level by `:seon.block/name` (a
plain `:keyword`, NOT a datahike identity, so two agents can each own a
`:transcript` block); priority sorts with a stable by-name tiebreaker.

Global-vs-per-agent is decided by the DATA the render fn queries, never by the
block: a `:my.kb.*` row carries no agent ref (one KB, every agent sees it), a
`:my.plan/*` row carries `:my.plan/agent` (each agent sees its own). Same block
registration; the render fn scopes by what it reads. (See [[data-model]] for the
domain schemas + data-ref scoping; [[agent-runtime]] for the fact-first atomic
birth transaction and post-commit safe declaration load.)

## Installing and removing — the one override

`seon.render.block/install-tx` is the sole function that shapes a block set. It
is PURE: it returns transaction data and the caller commits it, so the
derivation stays a function of a database value and one owner does the writing.

- It takes a vector of blocks and targets one agent's
  `:seon.cluster.agent/blocks`. Idempotent **upsert by `:seon.block/name`**.
- The upsert REPLACES a same-named block wholesale rather than merging it,
  because removing a key from a block must remove it from the block — a merge
  would make `:seon.render/ai` un-deletable and quietly keep a block in the
  prompt after its author took it out.
- Removal retracts the block entity; because `:seon.cluster.agent/blocks` is a
  component collection, the child cascade-retracts.
- Installing nothing is no transaction. Converged means zero writes.

The name is a plain keyword and deliberately NOT a database identity, so two
agents may each own a `:transcript`; uniqueness is per agent and the upsert
enforces it.

The cluster manifest declares the initial block data. Agents may later install
and remove against the same database-owned collection. A pure ADD needs nothing
more: name a block and its render symbols; the symbols resolve late.

**Pinning a fn is a block; config shapes the seed.** Any render fn an agent wants
always-on is nothing but a block — `install!` at a chosen priority pins it,
`remove!` drops it, so the agent dials context in and the cost is derived at
render. (`my.skills` explicit load/unload reuses this exact override; importing
skill source alone installs no block—see [[data-model]] §5.5 + [[context]].)
The per-cluster `seon.config`
manifest (aero `config/system.edn`) shapes the seed set declaratively WITHOUT a
code change. An absent block tree means no blocks; no hidden code fallback or
implicit skill-body injection exists.

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
the AI renders in `:seon.block/priority` order; the web UI places
the same blocks' HTML renders into a layout's slots. "What the agent saw at turn
N" is a re-derive from that exact value; `:t` alone is not a durable bookmark.

**The typed block renderer.** Above `seon.ui.html` sits one reusable value→hiccup
layer, `seon.render/block` — `(block view x)` dispatches on the value-KIND `x`
carries (the namespaced key ON the value, never a stored `:kind`): a **message**
(`:seon.render/markdown`) → `seon.ui.markdown/md->hiccup`, a **source**
(`:seon.render/source`) → `clj->hiccup`, a **data** projection → the value panel, a
flat **error data** → an error card, a literal **hiccup** vector → passthrough, and
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

**The database browser pays for opened data.** `/data` is bounded expansion
whose CURSOR says where to resume instead of eliding — the same caps and the
same walk as a value panel, one keeping the tail and one discarding it.

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
inputs at one immutable database value and invokes authored code through
`seon.sci.eval/evaluate` with the one `:interrupt-fn` and value admission.
Renderers may request genuine effects only through
`seon.effect/request!`; the normal render path remains pure and returns
ordinary data. The byte-stable cache prefix at low priority is preserved for
provider prefix-caching.

**Byte identity is a design property.** The complete database value, verified
configuration and file fingerprints, render inputs, and program artifact
determine the cacheable bytes. Host timezone, process clocks, environment
reads, ambient database state, and process-local result liveness cannot alter
them. The same value rendered in a replacement process yields the same bytes;
only an explicitly separated free-dynamic tail may vary under [[laws]].

**Context assembly is its own domain.** How the prompt bands by dynamism
(stable prefix / sliding window / free dynamic tail), the
namespace-as-location model, and the cache gradient live in [[context]] —
this doc owns the shared block/render machinery and the human-facing twin:
every context band renders an html representation for inspectability.

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

## Slots and layouts

- **slot** — `(slot :name)` emits
  `[:div {:id "surface-<name>" :data-slot :name}]`, a
  named, DB-keyed EMPTY hole keyed on `:seon.block/name`. It does not resolve
  `:name`; it marks a hole. Resolution happens at expansion: render the named
  block's html, and if THAT output contains more slots, recurse to fixpoint.
- **layout** — a render whose hiccup contains slots; it queries the db (the
  request carries it) + path-params and owns placement + CSS.
  **layout-vs-surface is
  a role, never stored**: a render with child slots is a layout, a render with
  none is a leaf **surface**.
- **top-level is DERIVED**, never a stored `:layout` flag: a block is top-level
  when no other block's surface slots it in. A page is its top-level surfaces
  in priority order, so several root cards and one all-slotting layout are the
  same mechanism arranged differently. When every surface is slotted and none
  is top-level, the slots form a closed cycle; every surface becomes top-level
  and the cycle is reported in the hole that closes it, because rendering
  nothing would be the silent failure this design refuses.

**Expansion is BOUNDED, by a node budget and a depth budget, not only by a
visited set.** A visited set is per path: it refuses cycles and permits fan-out,
and a block set with no cycle anywhere can still expand exponentially. The
budgets are the same `:seon.sci.admit/caps` dials the value codec takes, because
a graph that fans out and can cycle is the problem that codec already solved and
a second set of dials would drift from the first. Node and depth are separate
budgets, because a long thin chain and a wide DAG are different ways to be too
big. The walk is depth-first and left to right, so one input always elides the
same holes — equality suppression depends on it.

**Pages are bounded folds over the entity graph.** A page begins with its root
units and recursively reduces their rendered refs through this same mechanism.
Following a connection is the same act as filling a slot: ask the router for a
node, descend, count it, stop at the budget or at a node already on the path.
The entity graph can genuinely cycle where a value tree could not, so the
visited set is load-bearing and the budget bounds the fan-out. The `/data`
browser is the purest case: paged `get-in` navigation into any nested value is
bounded expansion whose cursor says where to resume instead of eliding.

Expansion refuses in place rather than throwing, so one bad slot costs one hole
and not the page: a slot naming a block the agent does not own keeps the hole
and names the missing block (install it and the next render fills it); a slot
naming a block whose surface FAILED gets that surface's error card, so the
failure appears where it belongs; and a cycle is refused at the hole that
closes it; and an exhausted budget keeps the hole and says which budget ran
out. The visited set along the path is the observable fact for a CYCLE; the
budgets are what bound a graph that merely fans out.

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
  projection and samples process-group liveness, CPU, RSS, uptime, and
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
  not independent database render units; opening one performs no authored invocation.
  It uses the same Datastar subscription graph and activation endpoint as every
  other live page, not a provenance-routed debug interest. With no open page it
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

1. a transaction commits and `listen!` wakes only matching registrations
   through a `(sliding-buffer 1)` interest channel;
2. the render proc runs each affected agent-authored renderer through the one
   `seon.sci.eval/evaluate` path: `fork`, `:interrupt-fn`, admission, and
   bounded output;
3. the registration compares the completed Clojure value with its current
   process-local snapshot using `=` and suppresses equal output;
4. a `mult` fans each changed stable-ID element patch to one per-tab
   `(sliding-buffer 1)` tap for each visible render unit; and
5. one connection-owned virtual thread per tab reads its tap and batches
   available Datastar element patches onto that tab's single SSE connection,
   with bounded writes.

Flow topology is static within each `graph-def`. The cluster render graph names
the interest and render procs, their `step-fn`s, bounded channels, and `conns`.
A tab is deliberately NOT a graph: connections churn with browsers while graph
topology is static, so opening a tab taps the `mult` and starts one virtual
thread that owns the SSE connection; closing the tab untaps and ends it.
Core.async's `executor-for :compute` runs guarded render work; socket writes
park their virtual threads. Each graph's report
channel and unmodified `flow-monitor` are the operational and visualization
surfaces. Flow channels are disposable in-process scheduling state, never a
second database work ledger.

**Nothing rendered is stored.** The equality snapshot exists only in the
registration's memory. Restart discards it and performs one render for every
pinned canvas at boot. There is no stored render snapshot, presentation
attribute, or replay log for display output.

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

Datahike's reverse attribute interest index selects registrations from committed
transaction reports. Each evaluation receives the report's exact immutable
`:db-after` value. The analyzer's `:seon.fn/read-attrs` facts remain discovery
metadata; actual observed Datahike reads determine invalidation, and missing
evidence widens to `:all`. A registration's in-memory equality snapshot affects
performance only and never becomes database authority.

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
of crashing siblings. The html render shows it as an
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
