---
type: research
status: draft
tags: [research, web, database, flow]
---

# Datastar SSE render allocation profile (2026-07-12)

## TL;DR

The default pod is idle-cheap. Its normal transcript is now bounded, while
exact read-result invalidation is still pending. The earlier 1.4–2.5 GB RSS
sawtooth is transient render allocation followed by garbage collection, not
evidence of an idle loop or a monotonic leak.

The current default-store reproduction is smaller but decisive. Agent
`vast-seals-kick` has 182 evals and four messages. Its initial agent feed is
about 105,000 estimated tokens of decompressed SSE HTML. Before the canvas
dependency fix, one domain button transaction emitted about 99,000 tokens even
though the visible domain canvas was the only meaningful change. Twelve direct
reproductions took roughly 200–220 ms apiece before gzip and moved RSS from
about 934 MiB to 1.1 GiB while heap usage periodically dropped under GC; after
idle GC, RSS returned to about 826 MiB.

Two independent correctness/performance faults caused that result:

- `seon.ui.agent-view` decoded a persisted canvas renderer with its arguments
  reversed through `some->`. Instrumentation threw, a broad `catch` converted
  the renderer to nil, and the feed never subscribed to its domain attributes.
  The post-button patch was not stale data: it omitted the canvas and left the
  old DOM in place. Commits `190404db` and `b32e6438` fix the argument order,
  consolidate one missing-safe lookup, and make malformed persisted pins a
  recorded core fault rather than a silently disabled subscription.
- Datahike transaction reports include a reasserted unique identity datom from
  an entity-map upsert even when that fact is equal in `db-before` and
  `db-after` and does not appear in history at the new transaction. Every
  agent-authored map update therefore reports `:seon.agent/id` as changed.
  Plan, transcript, canvas, and header dependency sets all mention that
  identity, so one canvas-domain transaction currently dirties all of them.

The dominant payload is the transcript primary element: about 94,000 estimated
tokens for 182 evals. Closed `<details>` elements are only a browser display
choice; the server still builds and syntax-highlights every source/result
subtree, serializes it, and gzip receives the whole string. Datastar then parses
the entire elements payload through `DOMParser` before applying the ID-addressed
morph. Disclosure is not a compute, allocation, or transport bound.

The right next steps are exact no-op/result invalidation, then a transcript
composition that emits compact activity faces by default and materializes
detailed eval bodies only for the deliberate focused/windowed set. Do not raise
the SCI deadline or add an arbitrary output substring cap.

## Scope and live baseline

This profile is read-only apart from exercising the existing cyclic domain
button and the independent canvas dependency repair. It did not reset or edit
ACME.

The live process after the default reset was:

- pod PID 14909, Node, idle CPU near zero;
- writer PID 14315, current pinned Datahike/Konserve forks;
- one default-store agent feed at a time;
- `vast-seals-kick`, 182 evals, four messages;
- pod RSS around 826–930 MiB after idle collection.

The feed was verified with a server-side Node gzip client, not the browser
bridge. Direct render measurements ran against one frozen `@seon.db/*conn*`
value and did not transact.

## The live transaction was current; the canvas target was absent

A listener installed through `seon.db/listen!` captured two cyclic button
transactions. For the first, `db-before` read `:completed` and both the supplied
`db` and live conn read `:planned`; for the next, `db-before` read `:planned`
and both post-commit values read `:active`. Basis values were consecutive.

The apparent stale UI therefore was not a stale listener snapshot. Before the
repair, `agent-view-dependencies` returned no renderer for the pinned
`my.agent.vast-seals-kick/expedition-canvas`, and the patch had no
`agent-view-primary-canvas` or `agent-view-rail-canvas` root. Reopening the feed
performed a full render and displayed the new database value, matching this
diagnosis exactly.

The fault was the thread direction in both duplicated helpers:

```clojure
(some-> pulled
        :seon.render.canvas/content
        (db/decode-edn-value :seon.render.canvas/content))
```

`some->` threads first, so this called `decode-edn-value` as `(stored-value,
attribute)` instead of `(attribute, stored-value)`. Always-on instrumentation
rejected the string in the keyword argument slot. The surrounding `catch
:default` then returned nil, making a core defect indistinguishable from a
missing pin.

The repaired code uses one Datalog query that naturally returns no row for a
missing agent or missing content. It decodes once, distinguishes a symbolic
renderer from a valid literal pin, records and throws malformed persisted
values, and derives a default renderer only when the pin is genuinely absent.

Live proof after hot compilation:

- the canvas renderer resolves to
  `my.agent.vast-seals-kick/expedition-canvas`;
- its dependency set includes expedition status, id, title, destination,
  notes, filter, and last-action attributes;
- the next button patch included both canvas roots;
- the patched canvas carried the post-commit `:planned` value after the initial
  full view carried `:completed`.

## Why a domain update still renders eight targets

The button handler commits expedition status and agent last-action in one
transaction. Its listener report also contained an added
`:seon.agent/id "vast-seals-kick"` datom at the new transaction. Querying the
history transaction found no such identity change, and the identity fact was
equal in the before and after snapshots.

The source explains it. `entity-map->op-vec` explodes every map attribute,
including the identity used for upsert. `transact-add` passes every exploded
datom to `transact-report`, and `transact-report` always appends that datom to
`:tx-data` after applying the index operation
(`reference-code/datahike/src/datahike/db/transaction.cljc:517-526,693-718`).
The index upsert recognizes the existing fact, but the report does not suppress
the semantic no-op.

That false candidate attribute intersects:

- the transcript renderer, which joins messages through agent identities;
- the plan renderer, which scopes plans by agent identity;
- the canvas renderer, which scopes its domain query by agent identity;
- the system header's fixed dependency set.

After the canvas repair, the button patch therefore contains eight complete
roots: focus marker, header, plan primary and rail, transcript primary and
rail, and canvas primary and rail. This is why a correctness fix slightly
increases the oversized patch until no-op/result invalidation lands.

Attribute intersection alone also remains overly broad for real changes: the
same attribute on another entity can dirty a renderer whose query result is
unchanged. The durable target remains the one in
[[reactive-ui-dependency-routing-2026-07-12]]: attributes select candidate
reads, then compare normalized read results across the before/after database
values before invoking a renderer.

## Allocation breakdown

One direct frozen-snapshot measurement produced:

| Surface | Render | Serialize | Expanded | Compact | Observed heap delta |
|---|---:|---:|---:|---:|---:|
| plan | 77 ms | 3 ms | ~1,753 tokens | ~1,753 tokens | +3.3 MiB |
| transcript | 54 ms | 64 ms | ~93,986 tokens | ~618 tokens | +34.2 MiB |
| canvas | 28 ms | 1 ms | ~1,771 tokens | ~1,771 tokens | GC crossed sample |

The full initial agent view was about 103,500–105,400 estimated tokens,
depending on the current canvas value, and one measured render/serialization
pass took 498 ms with roughly 52 MiB of additional live heap at the sample
boundary.

### Live JavaScript CPU profile

An inclusive V8 CPU profile was captured from the actual long-running pod with
the Node Inspector/CDP `Profiler` API: attach to the live process, enable and
start the profiler, issue 25 identical expedition toggle POSTs, then stop the
profiler and aggregate samples by JavaScript function. This method matters: an
earlier `--cpu-prof` attempt was invalid because the startup npm/CSS child
claimed the output name, and supervisor SIGTERM did not flush a usable profile
from the long-running pod. The CDP profile stayed in `/tmp` and is deliberately
not a repository artifact.

Across the 25-click interval:

- broadcast work occupied 79.5% of sampled time;
- `agent-view-changes` occupied 65.2%;
- Datahike query work occupied 39.5%;
- `system-header` occupied 20.4%;
- `renderer-touch` occupied 17.8%;
- Hiccup-to-HTML serialization occupied 14.4%;
- `transcript-block-html` occupied 10.4%;
- SCI rendering occupied 8.9%;
- garbage collection occupied 5.4%.

Each click still rendered eight targets, emitted about 105,000 estimated
tokens, and took 330–365 ms. This is inclusive attribution, so percentages
overlap; it identifies owners in the call tree rather than additive phases.
The result makes the order of work measurable: bound the eager transcript,
then rebuild with Datahike's semantic no-op report fix and repeat the identical
workload before choosing the next cache owner.

### Bounded transcript result

The normal HTML transcript now applies the block entity's existing
`turns-retained` value even when AI eviction tiers are empty. It keeps the
recent turns plus one preceding message, renders evals as fixed-size activity
rows, and coalesces error runs without embedding their member cards. The
technical `seon.handlers.eval/render-html` renderer remains available for a
surface that deliberately requests eval detail; the normal transcript calls a
separate terse projection and contains no source/result/error disclosure tree.

On the same 182-eval `vast-seals-kick` store, the hot-compiled initial agent
feed fell from about 105,000 to 13,865 estimated tokens—a roughly 7.6× reduction
for the complete view. A source sentinel unique to the expedition defn was
absent from the emitted normal feed. Focused transcript and agent-view tests
passed 14 tests / 42 assertions, including a behavioral proof that arbitrarily
large source/result/error payloads do not change the normal activity-row DOM.
The final cold-rebuild profile remains the authoritative performance gate.

Before the canvas dependency repair, twelve identical button-shaped patch
derivations each emitted about 99,400 estimated tokens. Render time was
144–169 ms and serialization was 53–55 ms. Heap dropped on some iterations
while RSS continued stepping upward, then idle GC reclaimed it. That pattern is
the small-store version of the grown-store sawtooth.

The transcript's domain content is not itself 94,000 tokens:

- eval sources total about 11,200 tokens;
- eval result projections total about 7,600 tokens;
- message bodies total about 600 tokens.

Server-side syntax-highlighting hiccup and HTML chrome multiply those values,
and the same historical collection is reconstructed on every falsely relevant
transaction.

## Collapsed evals are eagerly complete

`seon.agent.ctx.transcript/transcript-block-html` calls
`seon.render/render-entity-html` for every event, oldest first. Every eval
resolves to `seon.handlers.eval/render-html`, which returns a closed
`<details>` containing:

- its one-line activity summary;
- narration markdown;
- fully highlighted source;
- a nested result/error disclosure;
- fully highlighted result projection or full error text.

The transcript then places every complete card into its expanded face. The
outer agent-view renderer projects that expanded face into the primary element
and its compact latest-reply face into the rail. The rail is small, but the
hidden/selected-state primary element is produced on every transcript render
regardless of which browser-local surface is currently visible.

CSS and `<details>` do not defer Hiccup construction or HTML serialization.
Datastar's `patchElements` watcher parses the entire `elements` string through
`DOMParser`, enumerates each complete top-level child, finds its existing DOM
target by id, and then applies the selected patch mode
(`reference-code/datastar/library/src/plugins/watchers/patchElements.ts:39-145`).

The existing multi-element transport is still correct. Datastar's Clojure SDK
explicitly builds one patch event from a sequence of complete elements
(`reference-code/datastar-clojure/libraries/sdk/src/main/starfederation/datastar/clojure/api/elements.clj:98-129`).
The fix is to send fewer bounded complete elements, not to hand-author DOM
diffs or create another channel.

## Gzip and SCI

The initial HTML event compressed by roughly 12.3:1. Gzip materially reduces
wire transfer, but it cannot undo upstream Hiccup, highlighting, String, and
`DOMParser` allocations.

`broadcast!` stops its `render-ms` clock after Hiccup serialization and SSE
framing but before `push-event!` writes/flushed gzip. The observed 239–314 ms
button logs therefore prove expensive pre-gzip work; native compression is
additional work outside that timer.

Per socket, latest-wins backpressure is already sound: a pressured stream keeps
only its newest pending event. Equivalent feed keys share one rendered event
string. These mechanisms bound queued output, not one legitimate render's
allocation.

An agent-authored canvas render also rebuilds an SCI environment on every
invocation: query stored source/require data, expose compiled namespaces,
create a fresh SCI context, evaluate the defining source, invoke it, and deep
realize the result under the deadline
(`src/seon/render/sci.cljs:314-606`). The current expedition canvas measured
about 28 ms, but roster rendering can invoke every agent canvas preview. On a
grown store or broad renderer this is the amplifier behind the previously
observed SCI deadline failures. Raising the deadline would increase the
allocation window and hide the routing defect.

## Ranked changes

### 1. Remove false changed facts and retain exact result invalidation

- Correct Datahike's transaction-report delta so a reasserted equal fact is not
  reported as a new changed datom. Prove add, retract, replace, cardinality-many,
  tuple, history, and optimistic paths mechanically in the fork.
- Keep the planned before/after normalized read-result comparison. Report-level
  no-op suppression fixes this reproduction, but only result comparison scopes
  same-attribute changes on unrelated entities.
- After the fork fix, a domain-only button transaction must render the canvas
  unit only. It must not invoke plan, transcript, or header renderers.

### 2. Respect focused placement without duplicating expensive faces

One surface render may still produce compact and expanded faces, as the canvas
contract requires. Placement should serialize only the complete face needed by
that target. A transaction should not rebuild a large unselected primary face
merely because browser-local selection currently shows another surface.

Selection is browser-local, so the server cannot choose one face for several
tabs from database state alone. The subscription plan therefore needs stable
per-face units or a bounded shared representation, while preserving the one
renderer and complete-element morph semantics.

### 3. Make transcript activity summary-first in computation, not only CSS — implemented

The human transcript contract says message conversation is primary and evals
are compact activity rows. The implementation now enforces that contract
structurally:

- retain message bubbles as the conversation;
- derive compact eval activity rows without constructing code/result bodies;
- keep detailed eval rendering out of the normal transcript; a deliberate
  inspection surface may request the existing technical entity renderer;
- take the window coordinate from the transcript block's existing
  `:seon.agent.ctx.transcript/turns-retained`/tier policy or an explicit
  database query, not a second hard-coded output substring cap;
- keep exact raw agent context and full HTML twins available in the separate
  debug view;
- use `seon.render/block` for both compact and detailed bodies; do not duplicate
  markdown/Clojure/error rendering logic.

The current no-render-level canvas decision still stands: a tile renderer owns
one semantic render. The transcript section, as the placer of many event
entities, owns which bounded event faces it asks to materialize.

### 4. Finish unit/subscription consolidation

- Share the header unit instead of rendering it per open agent subscription.
- Decompose roster membership/rows/previews; an unrelated transaction must not
  invoke every agent canvas.
- Move debug and data pages onto the same subscription/feed owner and remove
  duplicate timers/registries.
- Cache one dependency plan per normalized view key rather than one closure per
  first-opened connection.
- Suppress byte-identical bounded element output after exact read comparison.

## Mechanical proof plan

| Scenario | Required evidence |
|---|---|
| Missing canvas pin | Dependency projection returns normally and keeps the canvas-content structural dependency. |
| Symbolic canvas pin | Renderer domain read attributes appear in the subscription plan. |
| Malformed persisted pin | A core error is recorded and the projection fails loudly; no nil fallback. |
| Domain-only button tx | One current-snapshot canvas render; no header/plan/transcript invocation. |
| Button SSE patch | Canvas primary/rail roots carry the post-commit value in the first and only patch. |
| Same attr on unrelated entity | Candidate read may compare; renderer is not invoked when its result is equal. |
| 182-eval transcript | Initial and message-update allocation is bounded; closed historical eval detail is not serialized. |
| Two equivalent tabs | One render and two pushes; either tab can close without invalidating the shared plan. |
| Slow client | Only newest pending bounded event retained. |
| Grown store | Stable idle CPU, bounded event-loop delay, no repeated SCI interrupts, and RSS returns to a stable band without multi-GB render spikes. |
| Frozen as-of feed | No current transaction comparison or render. |

The feed proof must use a server-side gzip client. Browser verification remains
necessary for idiomorph correctness, focused-face behavior, scroll anchoring,
and control latency.

## Current verdict

The canvas now updates correctly from the transaction's current immutable
database value, and the normal transcript no longer serializes its full eval
history. The broader performance work is not complete: report-level false
positives, roster-wide previews, and duplicate debug/data stream ownership
remain. Until the Datahike fix and bounded transcript are cold-rebuilt and the
identical workload is re-profiled, it is inaccurate to claim that boot,
updates, every HTML stream, and CPU/RSS behavior are all performant.
