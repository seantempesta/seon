---
type: research
status: completed
tags: [research, web, database, flow]
---

# Agent-header next-consumer cutover audit — 2026-07-15

## Conclusion

The ordinary agent header is the smallest production page unit that can move
next onto `seon.web.view-unit`. It has one stable DOM id
(`agent-view-header`), one agent-scoped identity, a small pure producer, and
already-captured exact reads. Moving it first removes a complete branch of the
page-specific dependency authority without coupling the unit engine to surface
selection, shell reconstruction, or root's still-aspirational dedicated system
layout.

One transport regression is a prerequisite, not part of the header cut. Two
views in the same normalized subscription can consume the same retained unit;
fan-in must put that stable-ID element into the subscription event once while
still pushing the event to both sockets. The first debug lifecycle regression
used distinct active fingerprints and therefore did not falsify this common
case. The integration tree now contains a focused same-subscription assertion
and distinct managed-element fan-in, but that fix and its evidence must land
before the header slice begins.

The header cut must not add another registry or let UI code dereference the
feed registry. The existing `!feeds` registry continues to store the one plain
`::view-unit/state`. Feed attachment reconciles an always-demanded descriptor
before first paint, and passes its retained serialized element into page
composition as ordinary immutable input. Live broadcasts use the shared unit
transition. Historical feeds stay frozen and do not attach a live unit.

## Dependency ledger

| Dependency or mechanism | Selected identity | Source and established use | Constraint for this cut |
|---|---|---|---|
| Datahike | maintained SHA `6f90b339768b1a02066dce3b6fcc93a200758fcc` in root `:writer` and `:cljs` aliases | `reference-code/datahike` at the same SHA; capture/replay through `seon.db/capture-reads` and `read-observation-changed?` | The producer receives exactly one immutable db value. Retain normalized observations and the resolved coordinate, never the db or entity. |
| Konserve | maintained SHA `df6818d43ea3363a808cd051c0d68917f1b987a9` | `reference-code/konserve` at the same SHA; replica values enter through the existing `seon.db` boundary | Do not re-dereference the replica inside the producer or page composer. |
| Datastar and idiomorph | vendored RC.7 client; `reference-code/datastar` SHA `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f`; Clojure SDK SHA `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | `patchElements.ts` and the Clojure SDK element API establish complete stable-ID elements in one event | Emit at most one `#agent-view-header` element per subscription event. Equivalent sockets may receive the same event. |
| Hiccup serialization | first-party `seon.ui.html` | `src/seon/ui/html.cljc` and its direct tests | The retained output is the final serialized header. Initial composition may receive it as trusted renderer-produced `html/raw`; it must not invoke the producer again. |
| Unit lifecycle | `seon.web.view-unit` kernel plus first consumer at commit `739a646c` | `src/seon/web/view_unit.cljs`, managed debug integration in `src/seon/web/datastar.cljs`, and focused tests | Strengthen the existing lifecycle and registry. Do not create a page-unit atom, header cache, or second listener. |
| Agent-state derivation | first-party `seon.derive/derive-state` | `src/seon/derive.cljs`; current producer `seon.ui.agent-view/agent-header` and its captured reads | Runtime observations are the correctness authority. `derive/agent-state-read-attrs` may no longer veto whether the header replays. |
| ClojureScript and Shadow | selected ClojureScript `1.12.145`, Shadow `3.4.10` | exact selected ClojureScript source is still absent; `reference-code/clojurescript` identifies `1.12.41`. Shadow release commit `d3c04691952aa9ea33f7287ffe9a2b3109c1e510` is present in `reference-code/shadow-cljs` | This cut does not depend on analyzer internals or declared read attrs, so the source gap does not block it. It must not introduce analyzer-derived invalidation. |

The broader dependency/source evidence remains in
[[reactive-render-source-audit-2026-07-14]]. No new dependency is justified by
this slice.

## Current source ownership and flow

`seon.web.datastar/open-agent-feed!` owns both live and historical agent feeds.
The live definition calls `seon.ui.agent-view/render-agent-view` for first paint
and `seon.ui.agent-view/transition` for every database change. The historical
definition renders one frozen database value and has an empty transition.

`seon.ui.agent-view/render-agent-view` currently:

- materializes every surface;
- separately captures `header/system-header`;
- separately captures the private `agent-header` producer; and
- stores both captures in its custom `::dependencies` map.

`seon.ui.agent-view/transition` then intersects changed attrs with
`derive/agent-state-read-attrs`, replays `::agent-header-read-observations`,
recaptures the header, updates the custom map, and emits the header Hiccup. The
runtime observations are exact, but the declared-attribute test is still a
false veto.

The committed unit engine already owns the desired lifecycle. The missing
seam is demand at feed attachment: lazy debug units attach through
`handle-view-unit!`, while an always-present page unit must be attached before
`push-full!`. Merely putting a token in `::active-tokens` is insufficient,
because `managed-active-unit` intentionally renders a stub until lifecycle
state has been committed.

## Candidate comparison

| Candidate | Benefit | Coupling and deletion boundary | Decision |
|---|---|---|---|
| Agent header | Stable id, one small query/derivation, agent-scoped coordinate, already independently captured | Deletes one exact custom observation branch and one declared-attr veto; needs one generic demanded-unit attachment seam | **Cut next.** |
| System header | Stable id and independently captured | Global fleet projection shared across many agent pages; root will eventually have a dedicated system layout; requires a deliberate coordinate and cross-agent sharing policy | Cut after the demanded-unit seam is proven by the agent header. |
| One context/canvas surface | Large performance and correctness value | Coupled to catalog reconciliation, two DOM faces, focus markers, disappearance/full-shell fallback, SCI, and the current surface declared-attr veto | Too broad for the next proof. |
| Whole agent page | Removes the custom transition superficially | Preserves eager hidden work and makes the whole page one unit; does not achieve unit ownership | Reject. |

## Ordered implementation boundary

### 0. Close the same-subscription framing prerequisite

Keep managed fan-in distinct per normalized subscription. The focused test must
open two same-fingerprint views, attach the same token, cause one relevant
transition, and prove:

- one producer invocation;
- one serialized stable-ID element in the event; and
- two socket pushes carrying that shared event.

Distinct subscriptions still each receive the unit update. Deduplication is a
transport framing concern; it must not change unit consumers or lifecycle
state.

### 1. Make demanded-unit attachment a feed lifecycle operation

Extend the existing feed descriptor with plain demanded managed descriptors,
or reuse its catalog plus an explicit demanded-token set. During live
`open-feed!`, after socket ownership is committed and before `push-full!`,
reconcile those tokens through `view-unit/attach-consumer` against one snapshot
and its `db.coordinate/resolved` value. Reconnect of the same view id must
replace socket ownership without double-attaching the consumer. Final close
continues through `detach-view` and releases the unit only after its final
consumer leaves.

This should be one generic operation usable by later always-present units. It
must not special-case `agent-header` inside `open-feed!`.

### 2. Define the agent-header descriptor at the web/UI boundary

Use a coordinate containing at least the route, agent id, and unit name, for
example:

```clojure
{:seon.route/name :seon.route/agent-feed
 :seon.agent/id agent-id
 :seon.web.view-unit/name :seon.web.view-unit/agent-header}
```

Use a stable renderer token owned with the public pure header producer. The
producer takes the supplied immutable db and returns the complete
`#agent-view-header` element. Do not retain the agent entity or close over a db
value.

### 3. Compose first paint from retained output

After demanded attachment, resolve the committed serialized element from the
pure lifecycle result and pass it as an explicit immutable page-composition
input. `seon.ui.agent-view` may wrap that trusted renderer-produced string with
`seon.ui.html/raw`; it must not know about `!feeds`, tokens, sockets, or
subscriptions. The first full `#app-view` must contain exactly one agent header
and the producer must have run exactly once.

Prefer strengthening the existing `render-agent-view` request contract over a
parallel `render-agent-view-v2` or header-aware page function. Migrate its
first-party callers and tests in the same slice.

### 4. Route live updates only through the shared unit transition

`broadcast!` already advances active units once before subscription fan-out.
The agent-page transition must no longer consider or emit the agent header.
The shared serialized header morph is framed beside any remaining legacy page
elements, with identical-output suppression owned by `view-unit`.

### 5. Delete the superseded page authority

In `src/seon/ui/agent_view.cljs`, delete together:

- schema registrations `::agent-state-attrs` and
  `::agent-header-read-observations`;
- both keys from `::dependencies`;
- the agent-header capture parameter and entries in `dependencies-for`;
- the agent-header capture performed by `render-agent-view`;
- the fallback dependency entries;
- `agent-header-dirty?`, transition recapture, dependency reassociation, and
  emitted-element branch; and
- `::agent-state-attrs` assertions in
  `test/seon/ui/agent_view_test.cljs`.

Keep the pure renderer, renamed/public only as needed for descriptor ownership.
Do not delete `derive/agent-state-read-attrs` if other mechanisms still use it.

In `src/seon/web/datastar.cljs`, the live agent feed stops inheriting header
dependencies from `render-agent-view`; the remaining page dependencies stay
temporarily until their own cuts. Historical rendering keeps a direct frozen
header because it has no live lifecycle or listener.

## Falsifiable evidence

### Focused lifecycle and feed tests

- Two same-fingerprint live views demand one agent-header coordinate: one
  producer, one retained unit, two consumers, one element per event, two
  socket pushes.
- Two different agent ids produce different tokens and do not share output.
- A real `derive-state` input change replays captured reads and emits only
  `#agent-view-header` when no remaining page unit changes.
- An unrelated immutable database snapshot advances the coordinate but invokes
  no producer and emits nothing.
- First paint contains one `#agent-view-header` and invokes its producer once.
- Reconnect of the same view id does not duplicate the consumer. Closing the
  first of two consumers retains the unit; final close releases observations
  and serialized output.
- A historical feed renders the frozen header directly, attaches no active
  unit, and remains silent after later transactions.
- Existing agent-view tests no longer assert the deleted header dependency
  map; they continue to prove page composition and status-chip structure.

### Running-system proof

Use the default cluster before ACME. Open two browser tabs for the same agent
and verify one `#agent-view-header` in each DOM, stable element identity across
an agent-state transition, and no console errors. Inspect the gzip feed with a
server-side client and prove one `datastar-patch-elements` target per event;
the browser bridge is not evidence for long-lived gzip framing. Then close one
tab and the final tab while inspecting retained consumer/release state through
the cluster-qualified CLJS REPL.

Use an existing safe lifecycle transition if available. Do not reset the
database or manufacture unrelated durable facts merely for this proof.

## Overlap and stop conditions

- `src/seon/web/datastar.cljs` and
  `test/seon/web/datastar_test.cljs` are integration-owned shared paths. Land
  the current same-subscription regression before assigning the header slice,
  and give one lane exclusive ownership of these paths.
- `src/seon/ui/agent_view.cljs` and its test are the cut's other owned paths.
  Do not concurrently implement the dedicated root layout or a surface
  cutover there.
- The agent header currently appears on root because root uses the ordinary
  agent page. This cut may preserve that current behavior, but it must not
  encode it as the intended root architecture. The later dedicated root layout
  chooses its own demanded units.
- Database admission/branch lifecycle work may alter coordinates and replica
  timing. Consume only the public resolved coordinate and immutable db supplied
  by the feed; do not reach into writer or replica internals.
- Stop if initial composition requires a second producer invocation, if UI
  code must access `!feeds`, if historical feeds acquire live listeners, or if
  reconnect produces duplicate consumers. Those observations mean the generic
  attachment seam is incomplete.

This slice does not remove the system-header or surface dependency maps, build
the reverse candidate index, implement the dedicated root layout, or justify a
recent-output LRU. It proves the reusable always-demanded page-unit seam and
deletes one complete legacy authority.
