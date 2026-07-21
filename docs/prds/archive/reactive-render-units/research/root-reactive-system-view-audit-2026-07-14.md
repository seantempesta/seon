---
type: research
status: active
tags: [research, web, agent, database]
---

# Root reactive system view audit — 2026-07-14

## TL;DR

The root page looks like an ordinary agent because it literally is one:
`serve-root!` writes the ordinary agent shell for `"root"`, and
`/agent/root/feed` runs `seon.ui.agent-view/render-agent-view`. The system grid
is merely root's pinned canvas inside that layout. This duplicates the grid as
the selected primary surface and a hidden rail preview, adds irrelevant agent
chrome, and makes the complete fleet dashboard one indivisible render unit.

The more serious live bug is now isolated. A surface's runtime-captured database
reads are exact, but `seon.ui.agent-view/transition` only checks them after the
transaction attributes intersect the renderer's analyzer-produced
`:seon.fn/read-attrs`. That stored set contains qualified keyword literals from
only the renderer function's own body. It is deliberately non-transitive, so a
renderer that calls `fleet-summary`, the plan implementation, or transcript
helpers does not watch the facts those helpers query. Exact observations exist
but are never consulted. Existing tabs therefore stay stale while a fresh feed,
which performs a complete render, is correct.

The simplest durable design is:

- a dedicated root page layout, not an agent page, using the same route,
  render-unit, gzip feed, and Datastar morph machinery;
- one general observed render-unit transition engine shared by root, agent,
  debug, and data pages;
- runtime observations as the live-correctness authority;
- stored `:seon.fn/read-attrs` retained only for deliberate surface recency and,
  at most, as a proven-conservative optimization hint;
- one cheap root shell plus one independently observed card shell and one lazy
  compact-preview unit per non-root agent; and
- the existing `my.plan.internal/anchor` as the one derivation for root-plan
  title/goal, current step, and progress. Do not add a summary entity, activity
  stamp, roster, or second planner.

At current scale, replaying every observation belonging to every *active* unit
on a transaction is the correct first cut. It is bounded by open subscriptions
and demanded units and is much cheaper than the current 100–200 ms agent
transition that still misses surfaces or the 400–1,100 ms whole-debug render.
If measurement says observation replay itself is material, deduplicate identical
observations once per transaction and add conservative hints derived from the
runtime observation request. A hint that cannot prove its scope is broad and
must replay. A source-literal set must never again suppress a semantic read.

## Scope and evidence

This audit read the active root, agent, surface, feed, plan, and database-read
observer code; the current architecture and roadmap; and the checked-in
Datastar, Datastar Clojure, and Reitit sources. It exercised the default cluster
through ordinary HTTP actions and server-side gzip SSE. The audit created the
ordinary agent `ripe-carpets-agree` with purpose `reactive root journey audit`;
that is an intentional live database mutation made during the authorized
default-cluster proof.

The in-app browser was unavailable to this worker (`agent.browsers.list()` was
empty), so it did not open a competing browser mechanism. The orchestrating
worker owned the real-browser journey and supplied the visual observations
called out below. HTTP, gzip feed, database, and log evidence was reproduced
directly here.

## What the reference implementations say

### Datastar

The shipped bundle establishes the useful constraints:

- `datastar-patch-elements` defaults to an outer morph and locates each incoming
  top-level element by stable DOM id. Several independent unit elements in one
  SSE event are normal; the server does not need to patch `#app-view` for every
  change.
- Idiomorph preserves matching stable IDs and input values. Browser form signals
  and unit morphs compose without a parallel client store.
- `data-on-intersect` supports threshold, exit, and once behavior. It can demand
  visible previews through the existing `/view/unit` action. `once` alone does
  not unload offscreen work, so use enter/exit when a large fleet makes that
  distinction worthwhile.
- `@get` already owns retry limits, request cancellation, and hidden-page
  behavior. Reconnection should remain one feed responsibility rather than a
  page-specific socket implementation.

`reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj:15-18` explicitly
tests tiny compressed updates and finds no compression buffering problem. The
cost in Seon is deriving and serializing unnecessary HTML, not gzip itself.

### Reitit

`reference-code/reitit/modules/reitit-core/src/reitit/core.cljc:331-380` resolves
and compiles the route tree once, then selects the appropriate lookup/trie
router. A distinct root layout is ordinary route data. Sharing one live update
mechanism does not require every route to render the same page component.

## Current mechanism

### Why root resembles another agent

`src/seon/web/datastar.cljs:1299-1310` documents and implements `/` by calling
the ordinary `write-agent-page!` with `"root"`. Its shell title is therefore
`seon · agent root`, its composer says to message root, and its feed is
`/agent/root/feed`. `open-agent-feed!` at lines 1312–1366 always binds
`seon.ui.agent-view`.

The ordinary layout in `src/seon/ui/agent_view.cljs:256-297` materializes every
surface into both:

- an expanded primary panel, hidden with `data-show` when unselected; and
- a compact rail card, also hidden with `data-show` when selected.

Root's system dashboard is only `seon.render.system/system-view`, pinned through
`config/system.edn` as root's canvas. It is consequently rendered once into the
primary and once into the rail DOM. The two faces come from one renderer
invocation, but both complete trees are serialized, morphed, retained, and
styled. Hiding one is not pay-for-use.

This is a vocabulary error, not a reason for a second transport. The desired
statement is: root and agents use the same route resolution, unit transition,
gzip feed, and Datastar morph mechanism; root uses a different page layout.
`docs/seon/architecture/ui.md:163-215` now says exactly that. The older sentence
in `docs/seon/architecture/architecture.md:147-151` should be tightened from
“identical block/layout/route machinery” to “shared block/render-unit/route/live
machinery with a dedicated system layout.”

### Why current tabs become stale

The failure chain is exact:

1. The analyzer tee walks only the current function's read forms and persists
   its qualified keyword literals as `:seon.fn/read-attrs`
   (`src/seon/eval.cljs:2488-2533`). It does not compute a call-graph closure.
2. `renderer-read-attrs` reads that one stored set. Its own documentation notes
   that an attribute reached dynamically is not watched
   (`src/seon/agent/ctx/render_fns.cljs:164-178`).
3. Surface materialization correctly wraps the *whole actual invocation* in
   `db/capture-reads`, so nested helper queries are captured
   (`src/seon/render/surface.cljs:454-489`).
4. `seon.ui.agent-view/transition` first requires an intersection between the
   transaction attributes and the non-transitive declared set, and only then
   calls `read-observation-changed?`
   (`src/seon/ui/agent_view.cljs:363-372`).
5. `system-view` itself only delegates to `fleet-summary`, recovery, recent
   activity, and card helpers (`src/seon/render/system.cljs:443-469`). Plan and
   transcript surface wrappers similarly delegate to implementation helpers.
   Their semantic reads are in the capture, but their source-literal gate does
   not admit the relevant run, turn, eval, message, or plan transaction.

The header updates because it has a separate hand-maintained `header-attrs` set
covering run/turn/eval state (`src/seon/ui/agent_view.cljs:188-203`). The local
agent-state header has another explicit set. These independent manual gates
explain the visually contradictory state: the header says one agent is running
while the nested fleet dashboard still says all agents are idle.

The root card appeared on agent creation because that birth transaction changed
`:seon.agent/ctx`, which `structural-change?` treats as a full-shell change.
Later domain transactions did not pass that structural gate. This is partial
reactivity, not stale database data.

### Live proof

| Journey or measurement | Observed result | Meaning |
|---|---|---|
| `GET /` | Ordinary root-agent shell; title `seon · agent root`; feed `/agent/root/feed` | Root has no dedicated system layout. |
| Initial gzip root feed, two agents | One complete `#app-view` patch, about 4,730 estimated tokens | First paint serializes the ordinary agent layout and every root surface. |
| `POST /agents` with a purpose while root feed was open | `200`, new id `ripe-carpets-agree`; root received another complete `#app-view` patch of about 5,195 estimated tokens; purpose occurred twice | Birth is reactive, but the root dashboard is one large duplicated surface. |
| Initial new-agent feed | About 3,269 estimated tokens for a zero-turn agent; purpose occurred twice | Ordinary pages also serialize both faces of every surface. |
| Blank chat submission | `400` | Input validation is alive. |
| Unknown well-formed agent route | `302 /` | Stale-bookmark behavior is alive. |
| Existing browser tab after chat and plan writes | State chip changed to running; plan stayed “no plan yet”; transcript stayed “no events yet” | Incremental unit selection missed helper-indirected dependencies. |
| Fresh gzip feed over the same database | Included the new plan text and transcript facts | Database and full renderer are current; only incremental invalidation is wrong. |
| Existing root tab during an agent run | Header showed 3 agents / 2 idle / 1 running; system canvas showed 3 agents / 3 idle / 0 turns / 0 evals / no activity | Header's manual gate updated while the nested canvas did not. |
| Root feed logs on run open/close | `targets 2` (system header and root agent header); plan/turn/eval commits usually `targets 1` (system header) | The canvas was not selected for rerender. |
| Ordinary agent transition logs | Commonly 100–200 ms even when the expected plan/transcript target was absent | The current gate is neither cheap enough nor correct. |
| Open root debug feed | Repeated whole-view renders around 400–1,100 ms, including blob and heartbeat-adjacent changes | Debug is lazy while closed but far too broad while open. |

The pod log evidence is in
`logs/operator/pod/9af147c3-c072-499f-a447-823e3512edd3.log`, especially root
feed lines 666–725 and the agent/debug sequence around lines 744–867.

## Facts that exist, and facts that do not

| Concern | Current database authority | Gap or rule |
|---|---|---|
| Agent identity | `:seon.agent/id` | Use one card per agent, not an invented roster entity. |
| Description | Optional `:seon.agent/purpose`; `create!`, `mint!`, and `set-purpose!` already use it | New-agent UI currently sends an empty purpose. Keep a graceful fallback; do not store a generated summary merely for display. |
| Durable work summary | Root plan title, optional goal and pace, active/ready step, and progress from `my.plan` facts | Derive a compact projection from the existing anchor. Do not restore `seon.ai.dispatch/generate-plan` or add a second planning path. |
| Agent-derived focused surface | `seon.render.surface/surface-catalog` plus `latest-focus-selection`; explicit canvas pin or latest deliberate REPL/domain update | This is the preview authority for root cards. |
| Browser-tab identity and location | None in active source | Architecture specifies `:seon.web.session/id`, human ref, and normalized location, but implementation is pending. |
| Current `view` query id | Ephemeral UUID/query value in `seon.web.datastar/!feeds` | Socket ownership only; it is not durable human-session state. |
| Manual surface pin | Datastar signals `$selected` and `$pinnedselection` | Pending web-session location query should persist the explicit pin only. |
| Message/turn navigation provenance | No `:seon.agent.message/web-session` or `:seon.agent.turn/cause-message` in active source | Required before root can reliably redirect the tab that spoke to it. |
| Recency | Datom transaction coordinates and transaction metadata | Do not add copied `updated-at`, active, accessed, or presence facts. |

The word “session” is ambiguous in the product request. The root grid should be
one card per agent workstream. A browser web session is a human tab/location and
can duplicate an agent, go stale, or sit on `/data`; it belongs in navigation
provenance and a small “you are here” indicator, not as the primary fleet row.

## Plan overlay from the existing planning system

`my.plan.internal/anchor` already returns the active step (or oldest ready leaf),
its `[root … self]` chain, whether it is active, and the root roll-up
(`src/my/plan/internal.cljs:319-339`). The first chain entry carries the root
title plus optional goal and pace; the step carries current title and optional
expectation. That is the desired card overlay:

- high-level line: root goal when present, otherwise root title;
- current line: active step title, or “next” plus the first ready step;
- small progress: derived done/total; and
- fallback: purpose, then a quiet “waiting for direction.”

Expose one small, pure, fully specified public projection in `my.plan`, backed
by `internal/anchor`, and have both the plan HTML twin and the root-card overlay
consume it. The projection takes an explicit database value and agent ref/id at
the rendering boundary. It stores nothing.

The current `plan-block-html` renders the whole forest and has no distinct
compact face (`src/my/plan/internal.cljs:1717-1765`). If it becomes an agent's
focused surface, `surface/materialize-surface :compact` falls back to the whole
Hiccup tree. Root cards must not treat that cropped full plan as their status
overlay. The small plan-position projection is a shell fact; the selected
surface preview remains a separate lazy unit.

Planning adoption is a behavioral question, not a UI mutation. The plan block
is already enabled and teaches agents to create durable plans. Drive real agents
through multi-step/restart scenarios and observe whether they author and advance
the existing `my.plan` graph. Do not resurrect the removed automatic planner or
store a UI-only summary to make the cards look populated.

## Simplest one-mechanism target

### Page shape

Create a dedicated root layout owner, for example `seon.ui.root-view`, and bind
`/` to that layout through the existing database route. Its shell contains:

- the shared system header;
- a calm page title and one primary `#root-agent-grid`;
- responsive cards keyed by `:seon.agent/id`;
- a lightweight root/supervisor status region without a recursive root preview;
- recovery facts only when present; and
- bounded recent activity as a separately observed, collapsible unit.

Each ordinary-agent card contains a cheap always-present shell: readable id,
derived state, purpose fallback, plan overlay, current surface label, and open
link. Its preview is a child unit keyed by agent id and current derived surface
selection. That producer invokes the existing
`seon.render.surface/materialize-surface` exactly once for its compact face. A
large fleet can activate previews on viewport enter and deactivate on exit
through `/view/unit`; no new feed is needed.

The whole-card navigation overlay currently covers the preview
(`src/seon/render/system.cljs:288-319`). Keep previews noninteractive or make one
explicit open affordance; do not layer an invisible link over future controls.
Replace fixed `h-44` clipping with a bounded responsive aspect/min-height and
line clamps for status text. Arbitrary preview content remains overflow-clipped
and pointer-inert until opened in its agent page.

### General render units

Promote the stable coordinate token in `seon.web.view-unit` into the owner of a
general unit descriptor/transition contract. One unit has:

- a fully namespaced stable coordinate and DOM id;
- demanded/active state owned by the live view, never persisted;
- a synchronous producer `f(frozen-db, route-input) -> hiccup`;
- the exact immutable read observations captured during its last render; and
- its last serialized output only for the lifetime of the normalized open
  subscription.

On each committed transaction:

1. Build one transaction-local replay cache keyed by the normalized observation
   operation/request/prior-result.
2. For every active unit, replay its observations against the new frozen DB,
   sharing identical checks across equivalent units/subscriptions.
3. Rerender only units with a changed semantic read result. Refresh their
   observations from the render.
4. Serialize only changed unit roots and send one Datastar patch event containing
   those stable-ID elements.
5. Suppress identical serialized output using the existing bounded
   subscription-owned last-event behavior.

The current `open-feed!`, normalized subscription fan-out, gzip stream,
latest-wins socket backpressure, and reconnection lifecycle stay. This is a
render-plan consolidation, not a transport rewrite.

Convert root first to prove the contract, then agent, debug, and data. Once all
callers use the general engine, delete both parallel transition paths:

- the custom `seon.ui.agent-view` dependency maps, manual attr sets, and
  `transition`; and
- the whole-view `seon.web.datastar/render-observed` / `transition-observed`
  path.

Keep page/layout functions in their owning UI namespaces. One mechanism means
one unit transition and one feed, not one giant renderer or one page shape.

### Correctness and performance of observation replay

The immediate safe policy is replay-all-observations for active units. The
number is bounded by open pages and demanded units; closed debug/details have no
producer and no observations. A read replay is a pure query/pull/index operation
and avoids Hiccup construction and SCI unless its result changed. This is the
only existing policy that cannot produce a false negative through helper calls.

Optimize only after measuring these counters per broadcast: active units,
unique observations checked, observations shared, dirty units, renderer/SCI
invocations, render milliseconds, and emitted estimated tokens. They are
runtime telemetry, not database facts.

If replay becomes significant, derive a conservative hint from each *runtime
observation request*, never from source text:

- query: collect literal attributes from the query and supplied rule data; any
  attribute variable, opaque rule/input, or unsupported clause makes it broad;
- pull: explicit attributes may be scoped; wildcard, recursive, or opaque
  patterns are broad;
- entity: route by resolved entity id using changed datoms, otherwise broad;
- index read: use its explicit index/attribute prefix when present;
- installed schema, basis, foreign, temporal, or non-replayable reads: broad.

The hint may skip a replay only when it proves the transaction cannot affect
the request. “Unknown” always replays. Add a soundness property test comparing
hinted selection with replay-all over generated transactions before enabling
the optimization. Do not use `clojure.core/memoize`: an unbounded process cache
retains historical database values/results. The useful caches are the
transaction-local replay map and the already bounded live-subscription output.

### Focus recency is separate

Declared renderer attributes can remain a useful, intentionally approximate
answer to “which domain surface did this agent deliberately update?” The
history query also requires matching agent user and REPL process, so boot/config
maintenance does not steal focus. That policy is documented as heuristic in
`docs/seon/architecture/ui.md:185-203`.

It must not answer “can this unit's output have changed?” Live correctness uses
runtime semantic observations. Keep these concepts and names separate in code:

- **focus touch/read plan**: declared, agent-scoped, deliberate-recency policy;
- **live dependency observations**: actual invocation reads, exact invalidation
  authority.

## Exact implementation and deletion map

### Root and shared projections

- `src/seon/web/datastar.cljs`
  - give `/` a root shell and root feed definition instead of calling
    `write-agent-page! "root"`;
  - keep canonical `/agent/root -> /`, POST `/agents`, `open-feed!`, and the one
    feed registry;
  - route all page definitions through the shared unit transition.
- `src/seon/ui/root_view.cljs` (new owner, if no better existing owner emerges)
  - own only the dedicated root page layout and unit descriptors;
  - no socket, listener, database atom, or second surface materializer.
- `src/seon/render/system.cljs`
  - split fleet/card/activity Hiccup into pure projections consumed by root
    units;
  - stop materializing every agent preview inside one `system-view` call;
  - remove root's recursive self-card and full-dashboard-as-canvas role after
    the dedicated page owns it;
  - preserve the bounded AI twin for root context from the same projections.
- `src/seon/ui/header.cljs`
  - consume/share the same fleet projection during a root render plan rather
    than independently calling `fleet-summary` while root calls it again.
- `src/my/plan.cljs` and `src/my/plan/internal.cljs`
  - expose one pure, schema'd compact position projection backed by `anchor`;
  - reuse it in plan HTML and root card overlay.

### Shared live transition

- `src/seon/web/view_unit.cljs`
  - expand from token encoding into the one unit descriptor, render result, and
    transition contract.
- `src/seon/web/datastar.cljs`
  - retain transport/registry/backpressure;
  - move descriptor/active-catalog mechanics behind `view-unit`;
  - add transaction-local observation deduplication and unit-target patching;
  - remove whole-view observed transition after migration.
- `src/seon/ui/agent_view.cljs`
  - keep only the agent page layout and surface descriptor construction;
  - materialize focused expanded plus demanded rail previews, not every hidden
    expanded and compact face;
  - delete `::dependencies`, `header-attrs`, `surface-read-attrs`,
    `dependencies-for`, `reads-changed?`, and custom `transition` after cutover.
- `src/seon/render/surface.cljs`
  - keep the existing catalog, focus, and one-face materializer as the sole
    surface authority;
  - keep declared attrs for focus recency, remove their use as live correctness
    gates.
- `src/seon/web/debug.cljs`
  - keep debug absent from ordinary pages;
  - split open debug into header/token/raw-block/html-twin units; closed
    disclosures have no producer or observations;
  - delete its whole-debug transition after unit migration.
- data browser owner currently in `src/seon/web/debug.cljs`
  - continue the planned split into bounded summary/table/detail units using the
    same engine; do not restore `/data/sse` or another registry.

### Web sessions, after the render bug is closed

Implement the already-designed `seon.web.session` model in place:

- schemas for id, human ref, and normalized location only;
- `sessionStorage` attachment tuple plus writer-allocated validation/bootstrap;
- route/pin reconciliation only when normalized location changes;
- inbound message web-session ref and turn cause-message ref; and
- protected root `select-agent!` that follows the current turn to the originating
  tab and emits the official Datastar redirect helper through its existing feed.

Do not block the dedicated root dashboard or exact invalidation fix on session
navigation. Do not approximate it with the ephemeral feed `view-id`.

## Behavioral tests to add, move, and delete

Tests should assert facts, unit targets, and converged behavior—not prose or CSS
snapshots.

### Unit and integration tests

- `test/seon/web/view_unit_test.cljs`
  - a producer whose direct body calls a helper which queries a plan fact is
    invalidated when that fact changes;
  - the same for transcript/message facts and system fleet/run/turn facts;
  - an unrelated agent's same attribute leaves the unit clean by semantic
    result comparison;
  - two units with one identical observation replay it once per transaction;
  - inactive units perform zero reads and renders;
  - a conditional unit disappearing produces the required shell/unit removal;
  - fresh full render and incremental sequence converge to equivalent unit DOM.
- `test/seon/render/surface_test.cljs`
  - preserve agent-scoped focus recency, boot/config exclusion, canvas pin, and
    transcript conversation focus independently from live invalidation.
- `test/seon/ui/root_view_test.cljs`
  - one card per agent, no recursive root preview, and no ordinary agent header,
    rail, or canvas pin;
  - purpose/plan-position/fallback precedence as data;
  - one agent plan or preview change targets only that card's unit plus any truly
    changed aggregate;
  - offscreen/inactive previews do not invoke their renderers.
- `test/my/plan_test.cljs`
  - compact position derives root goal/title, active or next step, and progress;
  - it updates from ordinary plan status facts and stores no summary projection.
- `test/seon/web/datastar_test.cljs`
  - root has its own shell/feed descriptor while `/agent/root` canonicalizes;
  - equivalent root tabs share one normalized render authority;
  - one unit change emits that stable target, not full `#app-view`;
  - gzip fan-out, identical-event suppression, latest-wins backpressure, and
    reconnect retain current behavior.
- eventual `test/seon/web/session_test.cljs`
  - attachment validation, idempotent location reconciliation, stale-session
    replacement, tab isolation, message/turn cause chain, and targeted redirect.

Move the current general focus/materializer tests out of
`test/seon/ui/agent_view_test.cljs` into the surface owner. Delete tests that
construct or assert the custom `seon.ui.agent-view/dependencies` map after the
general engine lands. Replace `root-has-one-canonical-page`'s assertion that `/`
contains `/agent/root/feed` with structural assertions for the dedicated root
feed and canonical redirect. Do not assert visible teaching sentences.

### Browser journey matrix

Run these in a clean, agent-owned browser tab after each slice, while also
keeping a server-side gunzip client on the relevant long-lived feed:

1. Open `/`: verify system layout, no agent rail/pin, every agent card present,
   and no console errors.
2. Create an agent with a purpose: card appears without reload; only grid/card
   targets morph; clicking opens that agent.
3. Submit a multi-step task: composer immediately focuses transcript unless the
   tab is explicitly pinned; running/idle state, transcript, plan overlay, and
   current step update without reload.
4. Ask the agent to update its canvas: agent page focuses canvas; root card's
   preview updates; another agent's card does not rerender.
5. Use a canvas input, button, select, toggle, and form: write succeeds, error is
   visible on failure, and the UI changes on the committed database fact.
6. Pin another agent surface, trigger a newer update, reload, unpin, and verify
   tab-local focus behavior once web sessions land.
7. Open two tabs to one agent: both converge; later session-directed navigation
   moves only the originating tab.
8. Open and close debug disclosures: closed debug costs nothing; opening one
   produces content; unrelated writes do not reconstruct it.
9. Navigate `/data`, open bounded details, then mutate matching and unrelated
   facts; only dependent units update.
10. Restart during an idle and active workflow: agents recover idle, root shows
    the recovery fact, pages reconnect, and current database projections return
    without manual refresh.

Record console errors, emitted target IDs, estimated patch tokens, render time,
active/dirty unit counts, and Node CPU/RSS during the journey. No raw character
counts should enter logs or reports.

## Ordered delivery

1. Add a regression that reproduces the helper-indirected stale plan,
   transcript, and system surfaces on an existing feed.
2. Make runtime observations the correctness authority in the current
   transition immediately. Replay all observations for active units; measure
   before adding hints.
3. Generalize that proven transition into `seon.web.view-unit`; migrate the
   agent page and delete its custom dependency gate.
4. Build the dedicated root layout on those units. Split shell, aggregate,
   per-agent card, preview, recovery, and activity targets. Stop using the root
   system dashboard as an ordinary canvas.
5. Add the compact plan-position projection and root card overlay; drive real
   agents through plan/restart behavior.
6. Convert debug and remaining data details to the same units; delete whole-view
   observed transition paths.
7. Implement database-backed web sessions and root-directed navigation from the
   existing architecture design.
8. Run the full browser journey, gzip SSE, focused pod tests, operator gate,
   cold restart, and CPU/RSS profile. Fix every observed stale target, duplicate
   render, console error, or reconnect fault before calling the slice complete.

This order fixes correctness before styling, proves the one mechanism on the
ordinary agent path, and then makes root attractive without creating another
reactive system.
