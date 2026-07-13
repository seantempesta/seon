---
type: research
status: completed
tags: [research, web, agent, flow]
---

# Web responsiveness audit — 2026-07-13

## TL;DR

The active web UI has several good foundations: one gzip Datastar feed for the
normal agent view, stable DOM ids, reconnect fencing, lazy debug activation, a
bounded transcript projection, and a database read observer capable of exact
invalidation. The remaining system is not yet pay-for-use end to end.

The highest-risk problems are correctness problems, not tuning problems:

- subscription dependencies belong to individual sockets even though rendering
  is shared between sockets, so closing the first equivalent tab can leave the
  surviving tab with stale dependencies;
- backpressure keeps only the latest serialized event, but a later partial unit
  patch does not dominate an earlier full membership patch;
- the transaction coalescer is a trailing debounce with no maximum wait, so a
  busy agent can postpone its own UI indefinitely;
- an invalid agent feed can install the global database listener without ever
  registering a feed that can remove it; and
- dynamic route facts have no database listener, so database route changes do
  not become live until a restart or explicit rebuild.

The largest unnecessary work is also structural. A normal agent page renders
every surface in both expanded and compact form, the roster rerenders every
agent canvas after every transaction, the header and root canvas repeatedly
scan broad store/history projections, `/data` has a separate uncompressed SSE
implementation, and production rendering does not use the exact read observer
already implemented in `seon.db`. SCI reconstructs an interpreter for every
agent-authored render invocation, magnifying all of those broad invalidations.

The right sequence is to make subscription and batching semantics correct,
then make every visible region a stable pay-for-use unit, connect exact read
observation to those units, bound the underlying queries, and only then add
bounded result or prepared-renderer caches where measurements justify them.
Do not memoize database values or introduce a second event system.

## Scope and method

This audit covers the active CLJS pod only. It inspected the normal agent view,
roster, debug view, data browser, Datastar feed lifecycle, route projection,
context renderers, SCI renderer, and the relevant tests. It did not modify or
restart the live system, create an agent, or touch the ACME cluster.

Datastar behavior was grounded in the checked-in implementation, especially:

- `reference-code/datastar/library/src/plugins/watchers/patchElements.ts:83-145`
  for event parsing and top-level id targeting;
- `reference-code/datastar/library/src/plugins/watchers/patchElements.ts:231-297`
  for morph id indexing;
- `reference-code/datastar/library/src/plugins/actions/fetch.ts:470-478` for
  hidden-tab stream suspension; and
- `reference-code/datastar/library/src/plugins/actions/fetch.ts:504-566` for
  applying an HTML activation response as a patch-elements event.

The live baseline was the cold-reset default pod with one `root` agent:

| Surface | Initial response | Observation |
| --- | ---: | --- |
| `/agent/root/feed` | about 2,514 estimated tokens | 73 elements, 14 ids, three primary wrappers and three rail wrappers |
| `/agents/feed` | about 887 estimated tokens | one-agent roster |
| `/agent/root/debug` | about 951 estimated tokens | shell only |
| `/agent/root/debug/feed` | about 5,735 estimated tokens | eight inactive unit stubs, no active unit body |
| `/data` | about 1,153 estimated tokens | data fragment rendered in the page response |
| `/data/sse` | about 405 estimated tokens | same initial fragment rendered again after stream open |

These are fresh-store measurements, not grown-store performance claims. They
establish render topology and duplication. Every displayed size is an estimated
token count using the repository convention.

## What is already sound

- The normal agent feed is gzip-compressed and uses stable element ids.
  `src/seon/web/datastar.cljs:690-771`
- Replacing a connection fences cleanup by feed id, so a stale socket close does
  not delete its replacement. `src/seon/web/datastar.cljs:712-770`
- The shared database listener is installed lazily and normally removed when the
  last valid feed closes. `src/seon/web/datastar.cljs:690-771`
- Equivalent normal feeds share one render call during a broadcast today.
  `src/seon/web/datastar.cljs:406-434`
- Debug is absent from normal agent work and its expensive raw/HTML bodies start
  as inactive stubs. `src/seon/web/debug.cljs:301-345` and
  `src/seon/web/debug.cljs:648-665`
- Transcript projection is bounded before HTML rendering.
- Hidden browser tabs stop their Datastar fetch stream and reconnect on return,
  avoiding a permanent background stream per tab.
- `seon.db` already captures normalized database reads and can replay a request
  against before/after database values. `src/seon/db.cljs:459-483` and
  `src/seon/db.cljs:1342-1420`

These mechanisms should be completed and unified, not replaced.

## P0 — correctness and stability blockers

### 1. Shared rendering has connection-local dependency authority

`!feeds` stores an independent catalog, active set, renderer closure, and
dependency atom for every socket. Broadcast groups equivalent sockets, then
uses the renderer closure from the first connection in the group:
`src/seon/web/datastar.cljs:207-215` and
`src/seon/web/datastar.cljs:406-434`.

The agent feed creates a separate `!dependencies` atom for every connection:
`src/seon/web/datastar.cljs:1012-1075`. A structural update refreshes only the
atom captured by the selected connection's closure. If that first tab closes,
the surviving equivalent tab can become the renderer authority with its older
dependency set and miss a newly introduced renderer dependency.

This is why “render once for equivalent tabs” must be implemented as one
normalized subscription with multiple socket consumers, not as per-socket
state grouped opportunistically during broadcast.

Required shape:

- normalize a subscription key from route, normalized parameters, live/as-of
  coordinate, and render-plan generation;
- store catalog, active units, dependency observations, and output cache on the
  subscription;
- store only transport/drain state on each socket consumer; and
- dispose subscription state only after its final consumer detaches.

Debug currently prevents equivalent-tab sharing entirely because its feed key
includes the ephemeral view id. `src/seon/web/debug.cljs:1018-1042`

### 2. Latest serialized event is not a safe backpressure model

While a gzip stream is draining, `send-event!` overwrites `pending-event` with
the latest serialized event. `src/seon/web/datastar.cljs:366-399`

That is safe only if every later event completely dominates every earlier one.
Stable unit patches do not. For example, a full `#app-view` membership update
can become pending and then be overwritten by a later single-unit patch. The
unit patch cannot add or remove the other elements represented only in the full
view, so the browser can finish in a state that is not any database state.

Datastar confirms this: without an explicit selector, each top-level element in
an event patches the existing element with the same id; omitted siblings are
not reconciled. `reference-code/datastar/library/src/plugins/watchers/patchElements.ts:125-145`

Keep pending work as a logical update, not one raw string:

- a pending full-view/membership patch dominates later unit patches and may
  absorb them by rerendering from the latest database coordinate;
- independent unit patches coalesce by stable unit id; and
- the drain callback serializes the accumulated current state once.

The current test proves only that the string labelled “latest” replaces an
earlier string. `test/seon/web/datastar_test.cljs:636-676` It does not prove DOM
equivalence after mixed structural and unit updates.

### 3. Trailing debounce can starve a busy agent

The database listener resets one timer after every transaction. Normal changes
wait 16 ms; once a structural change is present, subsequent changes keep
resetting a 300 ms timer. `src/seon/web/datastar.cljs:441-465`

There is no maximum wait. A continuous definition or eval burst can therefore
prevent the first patch for the entire burst, and the structural flag keeps the
longer delay active. This directly conflicts with responsive agent interaction.

Use a bounded batch window:

- capture the earliest database-before value;
- keep the latest database-after value;
- union transaction datoms, changed entities, and attributes;
- schedule a near-frame flush without moving it later on every arrival; and
- enforce an explicit maximum wait under continuous writes.

The current `merge-change` retains only the latest database value and attribute
keys, which is insufficient for exact before/after read comparison.

### 4. Invalid agent feeds can leak the global listener

The agent feed handler calls `ensure-installed!` before validating that the
agent exists. An unknown or stale `/agent/{id}/feed` returns 404 without adding
a feed, so no later close can trigger listener removal.
`src/seon/web/datastar.cljs:1012-1032`

Validate first and install the listener only as part of a successful feed open.
Centralize cleanup as an idempotent once-only operation across request close,
response close/error, and gzip close/error. The request-close path is good; the
other transport error handlers currently only log, which is a leak risk rather
than a reproduced leak. `src/seon/web/datastar.cljs:749-770`

### 5. Database-backed routes are not reactive

`seon.web.router/rebuild-projection!` exists, but it is invoked at server start
and explicit hot reload only. There is no database listener that rebuilds when
route facts change. `src/seon/web/router.cljs:310-320` and
`src/seon/web/serve.cljs:843-849`

Consequently, adding, changing, or retracting dynamic route datoms does not
change routing until a restart or explicit rebuild. Add exact route-projection
invalidation to the same bounded transaction batch and rebuild once per batch.

## P1 — dominant unnecessary work

### 6. Normal agent initial paint renders every surface twice

`render-surface` invokes every surface renderer and derives both compact and
expanded faces. `src/seon/ui/agent_view.cljs:632-644` The page then emits every
expanded primary wrapper and every compact rail wrapper.
`src/seon/ui/agent_view.cljs:697-764`

Both wrapper sets remain in the DOM and are controlled with `data-show`; the
focused rail copy is hidden, not omitted. `src/seon/ui/agent_view.cljs:115-154`
The live root page confirmed three primary and three rail wrappers. Fresh-root
subtree costs were approximately:

| Surface | Primary face | Rail face |
| --- | ---: | ---: |
| Canvas | 159 estimated tokens | 254 estimated tokens |
| Plan | 148 estimated tokens | 323 estimated tokens |
| Transcript | 188 estimated tokens | 370 estimated tokens |

Datastar parses the complete event with `DOMParser` and morphs every provided
top-level id, so hidden markup still consumes server render, transfer, browser
parse, DOM memory, and morph work.

Convert the normal agent page to the same stable-unit contract already used by
debug:

- render metadata and inactive unit stubs in the shell;
- activate only the focused expanded unit and the visible nonfocused compact
  units;
- omit the focused rail unit instead of hiding a duplicate;
- on focus change, deactivate the old expanded unit, activate the new one, and
  update rail membership through one logical batch; and
- preserve the selected surface in a browser signal so reconnect can restore
  activation deliberately.

### 7. Roster rendering is O(all agents × canvas cost) per transaction

Every roster row renders that agent's canvas preview.
`src/seon/web/datastar.cljs:253-303` The roster includes all agents with no
window or paging. `src/seon/web/datastar.cljs:305-339` Its change renderer
returns the complete roster after every transaction, regardless of changed
facts. `src/seon/web/datastar.cljs:1091-1110`

An agent-authored preview may invoke SCI, so an unrelated transaction can
compile and execute every agent canvas. Split roster membership, row metadata,
and canvas preview into stable units. Observe each unit's reads. Materialize
only visible/windowed previews and rerender only dirty rows.

### 8. The header repeats broad store and history work

The header computes throughput by scanning turn usage, parsing every usage
payload, sorting, and consulting wall-clock time.
`src/seon/ui/header.cljs:48-100` It also calls `db/store-inventory`, whose
implementation scans provenance rows and reduces attribute/value pairs.
`src/seon/ui/header.cljs:150-164` and `src/seon/db.cljs:1702-1775`

Fleet summary queries per-agent state plus eval counts/times, while error storm
derivation scans eval history per agent. `src/seon/render/system.cljs:103-143`
and `src/seon/derive.cljs:317-331`

The header is independently embedded in the agent, roster, debug, and data
pages: `src/seon/ui/agent_view.cljs:729`,
`src/seon/web/datastar.cljs:326`, `src/seon/web/debug.cljs:639`, and
`src/seon/web/debug.cljs:908`. The root system canvas then repeats fleet/store
calculations. `src/seon/render/system.cljs:435-455`

Make the header one shared stable unit with exact read dependencies and a
bounded derived-output cache shared across equivalent subscriptions. Replace
broad history scans with bounded/index-oriented queries or durable facts that
are not projections. Remove the rolling wall-clock rate: idle time alone should
not force a render, and the roadmap already rejects it.

### 9. Root system view has bounded output but unbounded computation

Recent eval and message sections query and sort all history before taking 12
rows. `src/seon/render/system.cljs:171-242` The agent grid renders every agent's
full canvas. `src/seon/render/system.cljs:297-331` Every root canvas render also
recomputes fleet, inventory, activity, and the full grid.
`src/seon/render/system.cljs:435-455`

Use index-bounded recent reads, a visible-agent window, lazy preview units, and
independent dependency/output caching for each system-view region. A local
activity change must not rerender every agent preview.

### 10. `/data` is a duplicate, unbounded transport and render path

The data browser owns a separate registry. `src/seon/web/debug.cljs:53-61`
Every render queries all transaction ids and all entity/attribute pairs before
pulling only the first 50 rows. `src/seon/web/debug.cljs:727-817`

The GET page renders the data fragment once, then `/data/sse` renders it again
on stream open. `src/seon/web/debug.cljs:889-913` and
`src/seon/web/debug.cljs:1044-1066` Every database transaction schedules a full
data-browser rerender regardless of changed facts.
`src/seon/web/debug.cljs:949-984`

This SSE path is uncompressed and lacks the normal feed's backpressure, drain,
and unified cleanup behavior. The page header is outside the patched fragment,
so it becomes stale while the data grid updates.

Move `/data` onto the shared gzip subscription/unit mechanism. Use a stable,
windowed query unit, avoid recomputing the namespace index from the full store,
and perform the initial materialization once.

### 11. Debug is lazy while closed but broad while open

The important pay-for-use boundary is correct: normal agent pages do not build
debug projections, and inactive HTML producers are stubs. Preserve that.

When debug is open, however, every transaction rebuilds the debug projection
and full debug app, while every active HTML producer reruns regardless of exact
dependencies. `src/seon/web/debug.cljs:1018-1042` The snapshot creates the
complete exact AI prompt. `src/seon/web/debug.cljs:173-202`

`debug-projection` separately dereferences the database for snapshot and surface
catalog, then `debug-app-view` dereferences again for the header.
`src/seon/web/debug.cljs:347-356` and `src/seon/web/debug.cljs:634-646` One
emitted event can therefore mix database coordinates and cannot be observed as
one pure `view = f(db)` computation.

Pass one immutable database value through the whole render, observe reads per
unit, recalculate AI text only when its actual context inputs change, and run an
active HTML producer only when its reads change. Equivalent debug tabs should
share the resulting normalized subscription.

### 12. Exact read invalidation exists but production UI does not use it

The database layer can capture normalized reads and replay them against before
and after database values. `src/seon/db.cljs:459-483` and
`src/seon/db.cljs:1342-1420` Production UI code does not call this machinery.

Instead, normal rendering relies on broad stored or inferred attribute sets:

- hard-coded structural/header attributes in
  `src/seon/ui/agent_view.cljs:365-428`;
- renderer attributes from `:seon.fn/read-attrs` in
  `src/seon/ui/agent_view.cljs:274-279`; and
- analyzer literals plus a source regex fallback in
  `src/seon/agent/ctx/render_fns.cljs:152-207` and
  `src/seon/agent/ctx/render_fns.cljs:250-259`.

Literal attributes are useful as a cheap candidate index, but cannot establish
that a result changed. They produce false positives for unrelated entities with
the same attribute and can miss conditional/dynamic reads.

For each stable unit:

1. render with one immutable database value while capturing normalized reads;
2. index the observations by candidate attributes/entities;
3. after a batch, replay candidate reads against earliest-before/latest-after;
4. rerender only units whose observed result changed;
5. recapture after every rerender so conditional dependencies evolve; and
6. suppress transmission when serialized output is identical.

Once live proof covers dynamic dependencies, remove the old attribute-only
mechanism instead of retaining parallel invalidation paths.

### 13. SCI reconstruction is paid on every renderer invocation

Every invocation queries source and require edges, enumerates namespaces and
indexes, creates a fresh SCI context, evaluates source, invokes the renderer,
and deep-realizes its result. `src/seon/render/sci.cljs:316-441` and
`src/seon/render/sci.cljs:472-610`

The safety bounds are valuable. The first optimization is to stop unnecessary
invocations through stable units and exact invalidation. Then profile two
bounded caches:

- a rendered-output cache keyed by stable unit plus a dependency-result
  fingerprint; and
- if SCI setup remains material, a prepared interpreter/execution-plan cache
  keyed by renderer definition transaction, require-edge/program generation,
  and host capability generation.

Do not key caches by database objects. Use bounded LRU/TTL or explicit
reference-counted subscription storage, report hit/miss/eviction metrics, and
invalidate on source, require-edge, schema, or host-var changes. The existing
schema-table cache is only one slot keyed by database identity, so separate
dereferences can defeat it. `src/seon/render.cljs:293-324`

## P2 — cleanup and completion

### 14. `/sse` is a dead compatibility route

`src/seon/web/serve.cljs:67-78` and `src/seon/web/serve.cljs:145-166` retain an
SSE registry/handler, and `src/seon/web/router.cljs:250-259` exposes `/sse`.
No active producer broadcasts through it. A client can connect but receives no
live application updates. Remove it when `/data` joins the unified feed so
there is one transport mechanism.

### 15. Reconnect does not durably express active server units

The browser correctly reconnects after becoming visible, but server-side active
unit state belongs to the disposed view/socket. A reconnect starts with a fresh
full event and can lose debug disclosure state or other activated units. Keep
the selected/expanded unit set in browser signals or normalized request state
and reissue activation after reconnect. Do not persist this presentation state
as domain facts unless there is a proven cross-session requirement.

### 16. Event-level instrumentation is insufficient

The current broadcast log reports connections, targets, changed attributes,
and rounded render time. `src/seon/web/datastar.cljs:424-431` It cannot explain
whether time was query, SCI, serialization, gzip/drain, browser payload, or
garbage collection, nor can it expose starvation or cache effectiveness.

Add runtime-only aggregated metrics per route and stable unit:

- commit count, batch count, earliest/latest transaction, coalesce delay, and
  maximum wait;
- sockets, normalized subscriptions, active units, and database listeners;
- candidate units, replayed reads, dirty units, renderer calls, and SCI calls;
- query/replay, SCI setup/body, hiccup serialization, gzip write/drain, and
  end-to-end patch timing;
- payload estimated tokens, suppressed identical outputs, pending logical
  updates, full-update dominance, cache hits/misses/evictions;
- event-loop delay, heap/RSS, and garbage-collection pause summaries.

Expose these only through an on-demand diagnostics surface or sampled logs so
observability does not itself become the hot path.

## Cache boundaries

Caching should compose at the same stable unit boundaries as rendering:

| Layer | Safe key | Invalidation | Bound |
| --- | --- | --- | --- |
| Pure data projection | normalized immutable arguments, not a DB object | argument change | small LRU/TTL if measured |
| Unit output | subscription/unit id plus observed read-result fingerprint | changed observed result or renderer generation | active subscriptions plus bounded warm LRU |
| Prepared SCI renderer | definition tx, require graph generation, capability generation | program/schema/host capability change | bounded LRU |
| Serialized patch | unit output identity plus face/selector | output identity change | short-lived batch cache |

`clojure.core/memoize` is not appropriate for these long-lived runtime paths
because it is unbounded and cannot expose deliberate invalidation. A bounded
cache library is useful only after the key and invalidation law are explicit.
The main win is still avoiding calls through exact stable-unit invalidation.

## Behavioral tests needed

These tests should assert behavior and counts, never context wording:

- Open two equivalent agent feeds, change renderer dependencies structurally,
  close the first tab, and prove the survivor still invalidates correctly.
- Force gzip backpressure; enqueue a full membership update followed by a unit
  update; apply emitted events and prove final DOM equals a fresh current render.
- Drive continuous transactions with a deterministic scheduler and prove first
  patch latency remains below the maximum wait.
- Request many invalid agent feeds and prove feed count and database listener
  count remain zero afterward.
- Add, replace, and retract route facts and prove routing changes without restart.
- Change an unrelated entity carrying the same attribute and prove the observed
  unit renderer is not invoked.
- Change and revert a dependency within one batch and prove no patch is sent
  when the final observed output is unchanged.
- Focus each agent surface and prove its rail duplicate is absent from produced
  DOM and its compact producer is not called.
- With many agents, change one unrelated fact and prove zero roster previews run;
  change one agent preview dependency and prove exactly one runs.
- Open `/data` and prove initial materialization occurs once over the shared gzip
  transport, then prove an unrelated transaction does not rerender it.

## Measurable graduation gates

### Correctness

- Every browser state after arbitrary transaction batching/backpressure equals
  a render of a real database coordinate.
- Equivalent tabs share one subscription plan and remain correct regardless of
  consumer attach/detach order.
- Route additions, changes, and removals become live without restart.
- Invalid, aborted, errored, and reconnected feeds return listener, subscription,
  and socket counts to their exact expected values.

### Responsiveness

- Under continuous writes, transaction-to-first-patch latency is bounded by one
  frame-oriented window plus an explicit maximum wait; it cannot starve.
- An agent button transaction updates only its dependent units and reaches the
  browser without waiting for unrelated renderers or roster canvases.
- Focus changes materialize one expanded face and omit its rail duplicate.

### Pay for use

- Closed debug contributes zero snapshot, context-renderer, and debug HTML work.
- Hidden/inactive agent surfaces contribute no expanded render work.
- Unrelated transactions invoke zero header, roster-preview, data-browser, and
  SCI renderers.
- Work for a one-agent update is bounded independently of total agent count and
  hidden transcript/store history.

### Resource stability

- Grown-store profiling shows flat live subscription/listener counts after
  repeated connect/disconnect/error cycles.
- Open idle feeds do not cause periodic rendering, CPU work, or allocation.
- Heap/RSS reaches a stable band under repeated broadcasts; no sawtooth is driven
  by repeated full HTML/SCI reconstruction for unchanged units.
- Payload estimated tokens and browser morph time scale with dirty visible units,
  not total page or store size.

## Recommended implementation order

1. Add runtime counters and the P0 falsification tests so each semantic change
   has a visible invariant.
2. Introduce normalized subscription ownership and a bounded logical change
   batch retaining earliest-before/latest-after.
3. Fix pending-update dominance, invalid-feed cleanup, and route projection
   invalidation before adding more partial patches.
4. Convert the normal agent view to shell plus activated stable units, omitting
   the focused rail duplicate.
5. Move roster, shared header, root-system regions, debug, and `/data` onto the
   same unit/subscription mechanism; remove dead `/sse` and duplicate data SSE.
6. Connect `seon.db` read capture/replay to unit dependency indexes, recapture
   conditional reads, and suppress identical serialized outputs. Delete the old
   attribute-only invalidation path after live proof.
7. Bound recent-history, inventory, roster, and data queries before introducing
   caches.
8. Profile grown stores and real agent-authored canvases; add only measured,
   bounded output or prepared-SCI caches.
9. Run live browser, server-side gzip-feed, reconnect, continuous-write,
   grown-store, and memory/CPU acceptance passes before declaring the web path
   fast, stable, and responsive.

This order preserves one mechanism: database transaction → bounded logical
batch → exact dirty stable units → one shared subscription render → gzip
Datastar patches → many socket consumers.
