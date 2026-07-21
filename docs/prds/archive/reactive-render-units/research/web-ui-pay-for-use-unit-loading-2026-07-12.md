---
type: research
status: completed
tags: [research, web, database, flow]
---

# Web UI pay-for-use unit loading (2026-07-12)

## TL;DR

The current web UI hides work; it does not defer it. The main agent page renders
every context HTML twin before choosing which surface is visible, then usually
serializes both an expanded and compact face. The roster renders every agent
canvas preview, including rows outside the viewport. The debug page computes the
whole prompt and every HTML twin twice on first paint. The data browser scans the
store twice on first paint. Closed `<details>` and `data-show` only affect browser
display after the server has rendered, serialized, transferred, and parsed the
content.

The fix should extend the existing Datastar feed with one generic **view unit**
mechanism:

```text
database + route parameters
  -> cheap catalog of stable unit coordinates (no renderer calls)
  -> page shell containing stubs for inactive units
  -> one page feed holding the active unit set

click / details toggle / viewport intersection
  -> GET /view/unit?view=<ephemeral-view>&unit=<stable-token>&active=1|0
  -> update that feed's ephemeral active set
  -> render the requested unit from the current database value
  -> return text/html containing complete, stable-ID elements

transaction
  -> evaluate only observed reads for active units
  -> render each changed active unit once
  -> fan the shared Datastar patch to equivalent connections
```

There must not be a route per block type, a feed per tile, or a stored dirty
flag. The database remains the content authority. The active set is ephemeral
socket/view state, just like an open browser connection. A unit coordinate is a
presentation lookup over database facts, not a new persisted identity.

Vendored Datastar already provides the required client behavior:

- `@get` accepts `text/html` and dispatches it as a
  `datastar-patch-elements` event
  (`reference-code/datastar/library/src/plugins/actions/fetch.ts:554-566`).
- repeated fetches from the same action cancel the prior request by default
  (`reference-code/datastar/library/src/plugins/actions/fetch.ts:24-50`);
- one outer patch may contain several top-level elements, each targeted by its
  id
  (`reference-code/datastar/library/src/plugins/watchers/patchElements.ts:125-145`);
- `data-on-intersect` supports entry, exit, thresholds, and one-shot operation
  (`reference-code/datastar/library/src/plugins/attributes/onIntersect.ts:14-62`).

This means one connection and one generic one-shot HTML door can support agent
context surfaces, debug raw/HTML blocks, eval detail, roster previews, and data
browser detail while preserving the existing reactive channel.

## Scope and method

This was a read-only audit of every active human web route:

- `/` and `/agent/{id}`;
- `/agents`;
- `/agent/{id}/debug`;
- `/data`.

The audit read the current `seon.web`, `seon.ui`, context, render, and eval
paths; inspected the vendored Datastar client implementation rather than
assuming its behavior; fetched the default pod pages; and collected the first
patch from the gzip feeds with a Node HTTP client. Long-lived SSE was not tested
through the browser bridge because it does not support that transport reliably.

Measurements are estimated-token payload sizes through the repository's one
display convention, not raw character counts. Timings are local wall-clock
samples and should be treated as order-of-magnitude evidence, not a benchmark
contract. The default store had root plus the grown `vast-seals-kick` agent at
the time of measurement.

This document does not change context wording, add block-specific tests, or
implement the refactor. It complements
[[../../reactive-render-units/research/reactive-ui-dependency-routing-2026-07-12]]:
that audit answers *when an active
render is invalidated*; this one answers *which renders should be active at
all*.

## Live evidence

| Route or first patch | Estimated tokens | Local wall time | What the sample proves |
|---|---:|---:|---|
| `/` shell | ~351 | 3 ms | The ordinary GET is already a cheap opener. |
| `/agents` shell | ~495 | 2 ms | The ordinary GET is already a cheap opener. |
| `/agent/root` shell | ~351 | 2 ms | The ordinary GET is already a cheap opener. |
| root agent first feed | ~2,166 | 139 ms | The feed, not the GET, performs the view render. |
| roster first feed | ~2,840 | 64 ms | The feed eagerly includes the whole current roster. |
| grown agent first feed | ~13,819 | 314 ms | Hidden context surfaces materially enlarge first paint. |
| root debug GET | ~19,214 | 317 ms | The GET eagerly creates the complete snapshot. |
| root debug first feed | ~19,321 | ~100 ms plus collection delay | First feed repeats essentially the same snapshot. |
| grown debug GET | ~50,453 | 879 ms | Debug cost grows with real context. |
| grown debug first feed | ~53,791 | ~700 ms before collection delay | First paint repeats more than 100,000 estimated tokens across GET and feed. |
| `/data` GET | ~2,348 | 162 ms | The GET performs the data scan. |
| `/data` first feed | ~1,578 | ~61 ms | The immediate feed performs the scan again. |

The gzip agent-feed numbers are estimated from the decompressed Datastar event,
because estimated tokens describe what the browser parses and what the human or
agent can reason about. The debug feed timing included a short collector delay;
the payload duplication, which is the important result, does not depend on that
timing precision.

## Current route work

### `/` and `/agent/{id}`

The GET handler is correctly a small shell. Its `data-init` opens the existing
gzip feed, and the initial feed calls `agent-view`. The eager work begins there.

`agent-view` builds `surface-specs`, calls `render-surface` for every surface,
sorts the fully rendered results, then maps every result into both the primary
area and the context rail (`src/seon/ui/agent_view.cljs:370-414,454-514`). A
primary surface hidden by `data-show` has already been rendered and serialized.
The rail is the same: its compact face is built even when below the scroll
viewport. If a renderer does not supply distinct compact and expanded faces,
the current `face` fallback uses the same hiccup for both
(`src/seon/ui/agent_view.cljs:75-85`).

There are therefore three separate questions currently collapsed into one
operation:

- which surfaces exist;
- which surface is deliberately focused;
- which faces need materialized content now.

Only the first two are required to construct the shell. The focused expanded
face is legitimate initial demand. Inactive expanded faces should be stubs.
Compact rail faces may be lightweight metadata stubs initially and load as they
enter the rail viewport. If the product chooses to show every compact preview
immediately, their producers should still be invoked once and shared with a
simultaneously active expanded face.

Root amplifies the problem. Root's canvas `system-view` computes fleet summary,
store inventory, recent activity, and a canvas preview for every non-root agent
(`src/seon/render/system.cljs:435-468`). The normal agent shell then also renders
root's context surfaces and both faces. `system-view` does not have a distinct
compact contract, so the system dashboard can be duplicated as a primary and a
rail face.

Ordinary post-load agent updates have already moved toward targeted elements in
`agent-view-changes`. That is useful, but it does not address the initial
render-all pass or prevent an inactive unit from remaining in the live
subscription set.

### `/agents`

The roster GET is also a cheap shell. The feed's first render and every later
transaction call the complete `roster-view` (`src/seon/web/datastar.cljs:149-184`).
There is no changed-attribute gate for the roster feed.

For each agent row, `roster-view` independently derives state and purpose and
calls `tile-preview`; non-root `tile-preview` invokes
`render-agent-canvas` (`src/seon/web/datastar.cljs:97-147`). Every canvas
therefore renders even when its row is outside the viewport. The header has
already derived a fleet projection, so some row/header facts are also queried
more than once in one page render.

The roster should render stable row shells from a cheap roster projection.
Viewport intersection should activate the preview unit for a visible row and
deactivate it on exit. State, name, purpose, and counts remain in the cheap row;
arbitrary SCI/domain canvas rendering is the deferred part.

### `/agent/{id}/debug`

The debug route is separate from the normal agent view, which is good: a normal
viewer does not pay debug cost. The debug route itself, however, pays twice.

`debug-page!` calls `(snapshot agent-id)` and embeds the whole result in the GET
(`src/seon/web/debug.cljs:1105-1116`). `debug-shell` immediately opens a second
debug SSE stream. `open-agent-sse!` registers the connection and calls
`snapshot` again for the initial four patches
(`src/seon/web/debug.cljs:1059-1083`). The browser receives essentially the
same large view twice.

One snapshot also duplicates producer work internally. `ctx-preview*` calls
`ctx/render-context` to build the exact prompt, then calls
`ctx/rendered-context-blocks` for `:ai` and `:html`
(`src/seon/agent/debug.cljs:76-108`). The former has already rendered the AI
blocks. The latter renders all AI block bodies again and renders every HTML
twin. `snapshot` then renders the agent canvas too
(`src/seon/web/debug.cljs:170-210`).

The raw pane places complete text inside closed `<details>` elements. The HTML
pane materializes every HTML twin. Closing or collapsing these nodes does not
save server work or transport. Finally, debug owns a second socket registry,
second listener, separate coalescer, and global correctness-first fan-out:
every relevant commit schedules a full snapshot for every watching agent
(`src/seon/web/debug.cljs:997-1042`). This parallel live channel should be
deleted, not optimized in place.

Debug has one exactness constraint: the total prompt and per-block token bar
must describe the exact AI text. It is acceptable and necessary to render the
AI format once to know those values. The result should be reused for prompt
assembly, block metadata, the bar, and any currently open raw bodies. HTML twins
are not needed for token accounting and should remain inactive until opened.
Closed raw bodies should not be serialized; opening a block can reuse the
already derived AI string for that database basis.

### `/data`

`data-page!` calls `data-page-html`, which immediately calls
`data-browser-fragment`; the page then opens `/data/sse`, whose initial patch
calls `data-browser-fragment` again
(`src/seon/web/debug.cljs:862-912,1085-1103,1129-1138`).

`data-scan` queries all transaction ids and all `[entity attribute]` pairs,
then reduces the pairs into namespace/entity sets and attribute counts
(`src/seon/web/debug.cljs:726-772`). Detail subsequently pulls up to 50 complete
entities (`src/seon/web/debug.cljs:793-811`). The aggregate and selected-detail
questions are coupled, so a detail page pays for broad sets even when it only
needs one selected namespace.

`/data` is an explicit human request, so computing one visible index unit on
first feed is legitimate demand. It should not be done once in the GET and again
in the feed. Namespace detail should be a second unit and pull only after the
user selects that namespace. The current debug listener treats `::data` as a
pseudo-agent and reruns every open parameter group after every transaction,
including changes that cannot alter the active query.

### Eval detail and other closed technical bodies

The normal transcript's compact activity renderer no longer includes hidden
source, result, or error bodies. That is the correct default. The explicit eval
detail renderer still builds technical source/result/error content inside
closed `<details>` (`src/seon/handlers/eval.cljs:161-234`). A closed disclosure
is useful interaction chrome, but it is not lazy execution.

Eval activity should expose a stable detail unit coordinate. Its summary row is
cheap and reactive; opening the disclosure activates the detail unit and calls
the existing full renderer. The same abstraction can later cover technical
details in function, namespace, schema, and test handlers without introducing
handler-specific HTTP routes.

### Persistent header

The persistent header is visible, so its work is not hidden. It is still an
important shared producer. `system-header` derives fleet state, throughput,
store information, and error storms (`src/seon/ui/header.cljs:226-264`).
`throughput` scans, parses, and sorts turn usage (`src/seon/ui/header.cljs:71-114`).
Root's `system-view` repeats some fleet and store projections already used by
the header.

One render request should carry a shared database snapshot and request-local
projection cache so identical visible facts are computed once. Cross-feed
sharing belongs to the compiled subscription/unit producer described in
[[../../reactive-render-units/research/reactive-ui-dependency-routing-2026-07-12]],
not to persisted cache entities.

## Datastar-grounded mechanism

### One feed, one one-shot door

Keep `seon.web.datastar/open-feed!` as the only long-lived browser channel.
Debug and data should become view plans handled by it; delete
`seon.web.debug`'s `!sse-by-agent`, debug listener, timer map, and custom SSE
framing after parity is proven.

Seed exactly one core database route through `seon.route/core-routes-tx`:

```text
GET /view/unit?view=<ephemeral-view-id>&unit=<stable-token>&active=1|0
```

This route returns `text/html`, not SSE. Datastar's fetch action detects that
content type and sends the response body through the normal patch-elements
watcher. Because outer mode iterates all top-level response children and locates
each existing target by id, activation may return both the newly materialized
unit and stubs for units deactivated from the same exclusive group.

The persistent feed continues to carry transaction-driven patches for active
units. Unit activation is a control message for that same feed descriptor, not
the start of another feed.

### Stable unit coordinates

A coordinate is an exact vector of fully namespaced attribute/value segments.
Examples:

```clojure
[:seon.agent/id agent-id
 :seon.agent.ctx/name block-name
 :seon.render/view :html
 :seon.web.unit/face :expanded]

[:seon.agent/id agent-id
 :seon.render.canvas/content :seon.render.canvas/content
 :seon.web.unit/face :expanded]

[:seon.eval/id eval-id
 :seon.render/view :html
 :seon.web.unit/face :detail]

[:seon.agent/id agent-id
 :seon.web.unit/face :preview]

[:seon.web.data/namespace namespace
 :seon.web.data/page page
 :seon.web.data/system? include-system?]
```

The repeated canvas attribute as a value is deliberate: each vector remains an
ordered coordinate expressed entirely with namespaced keys and values, rather
than relying on an unqualified `:kind` discriminator. A more ergonomic
map-shaped coordinate is also valid if every key is namespaced and its
canonical order is explicit.

Encode the canonical coordinate EDN as UTF-8 base64url with Node's Buffer to
form the token, then prefix that token with `seon-unit-` for the DOM id. This is
deterministic and collision-free for the canonical text; it is not a new random
id function. The server-side catalog maps the token back to the trusted
descriptor. The route must never decode a client token into an arbitrary
renderer symbol.

The page shell receives a random ephemeral view id. Stable unit tokens survive
morphs and reconnects, while the view id scopes active presentation state to
one browser view. Closing the feed removes the descriptor. Reconnecting with
the same view id replaces stale connection state rather than opening a parallel
subscription.

### Catalog, producer, and active subscription

Each compiled page plan supplies:

- a cheap `unit-catalog` that derives coordinates, labels, ordering, exclusive
  groups, and stub hiccup without invoking content renderers;
- one content producer per coordinate;
- the producer's observed database reads;
- an ephemeral active set and optional exclusive-group selection;
- the database basis/as-of value used by the page.

The catalog describes available doors. It does not compute what is behind
them. Activating a unit:

1. verifies that the view and unit exist;
2. applies exclusive-group changes, if any;
3. snapshots the current database, or uses the feed's frozen as-of database;
4. invokes only the requested producer;
5. records its read observations;
6. returns complete stable-id content plus any newly inactive stubs.

Deactivation removes the unit from the active set and replaces its materialized
body with the cheap stub. It therefore stops future transaction renders. An
unknown view returns `410`; an unknown or no-longer-present unit returns `404`.
Neither path invokes a renderer.

### Client activation patterns

Use a persistent loader/control element for exclusive mainstage selection. Its
effect issues `@get` when the selection signal changes. Repeated requests from
the same action inherit Datastar's default latest-request cancellation.

Use `data-on:toggle` for debug/eval disclosures. Opening activates the unit;
closing deactivates it and reinstalls its stub. Use entry and exit
`data-on-intersect` handlers for roster and optional rail previews. Avoid
`__once` when the intended policy is to stop paying for content after it leaves
the viewport.

The client never sends renderer code. It sends the opaque stable token already
present in the server-generated catalog.

### Reactive updates and authority

The database remains the only durable authority. Do not transact:

- active/inactive state;
- dirty flags;
- rendered HTML;
- dependency subscriptions;
- content hashes;
- last-seen markers.

Those values are projections of an open socket, a page plan, code, and a
database basis. Keeping them in process is not the atom-backed authority smell:
there is no useful state after the socket/view disappears.

On a transaction, changed attributes choose candidate observations. Compare
each candidate read over `db-before` and `db-after`; only an active unit whose
result changed is dirty. Render each unique dirty unit once and fan its event to
equivalent feeds. Grouping equivalence must include the base view key, database
basis, and active-unit fingerprint. Dependency and active state belong to the
shared subscription, not whichever socket happens to be first in a vector.

Structural changes recompute the cheap catalog. Removed units are patched back
to absent/stub state and removed from the active set. Added units add stubs only.
No content renderer is called merely because the catalog changed.

As-of views are frozen. Their unit activations use the frozen database value,
and current commits never rerender them.

### Exact debug prompt without duplicate renders

AI context is the one intentional exception to “do not render inactive
content”: exact prompt totals require the AI strings. It is not an exception to
render-once.

Refactor context assembly so the debug view obtains one vector of rendered AI
blocks and joins those same strings into the prompt. Reuse it for:

- full prompt text;
- per-block token estimates;
- total and cache-line estimates;
- an open raw block body.

Do not call `render-context` and then rerender the same AI blocks through
`rendered-context-blocks`. HTML format producers remain fully lazy. A bounded,
ephemeral derived cache keyed by database basis and block coordinate is
acceptable if measurement proves it useful; it cannot become a second source
of truth.

## Concrete implementation plan

### 1. Establish generic unit data contracts

In `src/seon/web/datastar.cljs`, register schemas for fully namespaced unit
coordinates, descriptors, activation requests, active subscription state, and
producer responses. Add pure helpers for canonical token generation, stable DOM
ids, stubs, exclusive-group transitions, and active fingerprints.

Do this in the existing namespace because it already owns feed registration,
render sharing, gzip framing, and backpressure. Do not add `seon.web.units-v2`
or a second connection registry.

### 2. Correct the feed ownership model

Replace connection-local dependency closures with one subscription record per
unique view key plus active fingerprint, holding a set of socket writers. Keep
the current latest-event backpressure behavior. Feed cleanup removes socket
membership and deletes the subscription/view catalog when its final connection
closes.

Preserve one initial visible-unit render. A page GET emits only the shell and
feed opener. The feed's initial event emits visible active content exactly
once.

### 3. Add the single database-seeded unit route

In `src/seon/route.cljs`, add `/view/unit` to the reconciled core route facts
and delegate it to a public handler in `seon.web.datastar`. Do not add it to the
static supplement. The handler validates view id, token, and active flag;
mutates only ephemeral view state; and returns one or more complete `text/html`
elements.

There are no block, eval, agent, roster, or data-specific HTTP routes beneath
it. Page plans supply descriptors and producers.

### 4. Convert the agent page

In `src/seon/ui/agent_view.cljs`, split `surface-specs` into a cheap descriptor
pass and a producer pass. Materialize only the deliberately focused expanded
surface initially. Render all other expanded surfaces as stubs. Omit the focused
surface from the rail, preserving the existing no-duplicate product rule.

Choose and document the compact policy:

- recommended: compact stubs load on rail viewport intersection;
- acceptable if measured cheap: all compact faces active, but producer output
  shared with an active expanded face.

The latest deliberate canvas/reply focus query remains database-derived. A
manual browser selection remains ephemeral and should not be transacted.

### 5. Convert roster and root previews

In `src/seon/web/datastar.cljs`, make `roster-view` build row shells from one
fleet projection and register canvas preview units. Activate/deactivate previews
with viewport intersection. In `src/seon/render/system.cljs`, make root's agent
cards use the same preview producers rather than invoking every agent canvas
inside `system-view`.

Share visible fleet/store projections with `system-header` for one request.
This is derivation reuse, not durable cached state.

### 6. Convert debug and eliminate its parallel feed

In `src/seon/agent/debug.cljs` and `src/seon/agent/ctx.cljs`, expose a path that
assembles the exact prompt from already-rendered AI block results. Render AI
blocks once. Make raw block bodies and all HTML twins unit producers.

In `src/seon/web/debug.cljs`, make the GET a shell, express header/bar/raw/HTML
as a generic page plan, and open the same `seon.web.datastar` feed. Delete
`!sse-by-agent`, `!pending`, the custom patch/SSE functions, and the second
database listener after the generic feed passes live parity checks.

The default debug page may activate its header, token bar, and whichever raw or
HTML block the human has deliberately opened. It must not serialize the bodies
of closed disclosures.

### 7. Split the data browser into query units

In `src/seon/web/debug.cljs` initially, then a better owning namespace if the
data browser remains substantial, separate:

- namespace/attribute index query;
- selected namespace entity-id query;
- bounded entity detail pull.

Make the GET a shell, the index the one default active unit, and selected detail
a deliberate unit. Run no data scan in the GET. Route updates through the same
feed and dependency observations, then remove the `::data` pseudo-agent path.

This is a refactor of one data browser, not a second data API.

### 8. Make eval technical detail a unit

In `src/seon/handlers/eval.cljs`, keep `render-activity-html` compact. Expose the
existing full detail producer through the page catalog and replace eager closed
detail bodies with a stable unit stub. Use `data-on:toggle` to activate and
deactivate it. Apply the same mechanism to other handler disclosures only after
the generic eval path proves it.

### 9. Integrate exact invalidation

Complete the observed-read subscription design in
[[../../reactive-render-units/research/reactive-ui-dependency-routing-2026-07-12]].
Unit activation records reads;
unit deactivation removes them from candidates. Exact read-result comparison
must replace provenance routing and broad attribute-only fan-out. Retain
database user/process metadata only where the question is deliberate focus or
historical provenance.

### 10. Live rollout

Land in reviewable commits:

1. pure unit contracts/token/stub/active-set tests;
2. generic route and shared subscription ownership;
3. agent page and roster/root previews;
4. debug feed consolidation and single AI render;
5. data browser split;
6. eval detail;
7. dependency-result comparison and profiling.

After each stage, rebuild and reset the default cluster from the supervisor,
then verify with a server-side gzip client and an owned browser tab. Do not
update or reset ACME until default behavior, reconnect, buttons, inputs, and
cold restart are proven.

## Behavioral test plan

Tests should assert calls, state transitions, and absent expensive output—not
specific context prose.

### Pure unit tests

- The same coordinate always returns the same token and DOM id.
- Distinct coordinates produce distinct tokens in a representative generative
  sample.
- Constructing a catalog or stub invokes its renderer zero times.
- Activating one unit invokes exactly that producer once.
- Activating a unit in an exclusive group deactivates the former unit and
  returns both complete target elements.
- Deactivation replaces content with a stub and removes its observation.
- Unknown view and unknown unit invoke zero producers.

### Reactive feed tests

- An inactive unit receives no renders on relevant or unrelated transactions.
- An active unit renders once when its observed read result changes.
- A changed attribute whose normalized read result is equal invokes zero
  renderers.
- A structural removal patches the old target and clears its active state.
- Equivalent connections with the same active fingerprint share one render.
- A different active fingerprint does not inherit another view's units.
- Closing the final socket releases the view state.
- Reconnect replaces stale connection ownership without duplicating a feed.
- A frozen as-of unit loads from its frozen database and never receives current
  transaction patches.
- Backpressure retains only the latest complete event.

### Route behavior tests

- Agent first paint invokes only the focused expanded producer; inactive HTML
  twins are structurally absent.
- Selecting another mainstage surface loads it and reinstalls the old surface's
  stub.
- The focused surface does not appear a second time in the rail.
- Roster first paint invokes zero arbitrary agent-canvas producers; one viewport
  activation invokes one.
- Debug GET invokes no snapshot or HTML producer; initial feed renders AI blocks
  once; closed raw/HTML bodies are absent.
- Opening one debug HTML block invokes only that block producer.
- Eval activity includes no source/result/error body until detail activation.
- Data GET invokes no scan; initial feed invokes the index once; selecting a
  namespace invokes only its bounded detail query.
- One `text/html` activation response with several stable-id roots is applied by
  Datastar in a real browser.
- The gzip feed remains server-verifiable and its reconnect path performs one
  initial render.

## Acceptance criteria

The refactor is successful when live default-cluster evidence shows:

- no route computes the same first-paint projection in both GET and feed;
- hidden, closed, below-viewport, and inactive units invoke zero expensive
  renderers;
- the agent page invokes one expanded renderer initially and never duplicates
  the focused unit in the rail;
- debug AI blocks render once per required database basis and HTML twins render
  only on activation;
- `/data` performs one visible index query initially and no broad detail pull;
- one browser view has one long-lived feed regardless of how many units it
  activates;
- transaction patches render only changed active read results;
- reconnect, as-of, button, form, transcript-scroll, and focus behavior remain
  correct;
- repeated live updates no longer reproduce the avoidable allocation/RSS
  sawtooth documented in
  [[../../reactive-render-units/research/datastar-sse-render-allocation-profile-2026-07-12]].

The target is pay-for-use, not an arbitrary render cap. Legitimately visible
content may still be large, but invisible content must cost only its cheap
catalog/stub metadata until the human asks to see it.
