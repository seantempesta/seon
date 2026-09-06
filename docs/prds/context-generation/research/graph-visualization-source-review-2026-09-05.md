---
type: research
status: complete
tags: [research, agent, context, render, data-model]
---

# Graph visualization source review — 2026-09-05

This review answers one implementation question for the Design Lab: how should
the existing debug surface show forward and reverse Datahike references, allow
bounded exploration from any entity, and keep renderer selection and results
coordinated while the database and program change? It distinguishes the
checked-in system from proposed experiments. The
[Design Lab PRD](../plan/design-lab-prd-2026-09-05.md) and the maintained
`datastar-web-ui` skill were read end to end before this recommendation.

## Recommendation

Use Cytoscape.js as a persistent browser view of the bounded observations that
Seon already derives. Do not make Cytoscape the graph query, renderer selector,
cache, or source of truth.

The default should be a rooted one-hop view:

- the selected subject stays in the center;
- loaded incoming endpoints occupy a left arc and loaded outgoing endpoints a
  right arc, ordered by qualified attribute and stable entity identity;
- arrows retain the stored direction, from datom `:e` to ref-valued `:v`;
- every edge carries the qualified attribute, transaction, and assertion
  identity; parallel assertions remain distinct edges;
- non-reference attributes remain in the structural entity panel rather than
  becoming fake graph nodes;
- incomplete outgoing and incoming pages have separate visible continuation
  controls outside the canvas; an empty incomplete incoming page is not shown
  as “no incoming refs”; and
- selecting a node, edge, attribute, or render function changes the coordinated
  evidence panel. It never changes the viewing namespace implicitly.

Use deterministic initial positions and Cytoscape's `preset` layout. Preserve
positions for existing nodes, including user drags, when a page adds another
bounded neighborhood. Layout only newly admitted nodes around the endpoint that
was expanded. A user-invoked “re-layout” may recompute the visible collection;
ordinary database or program updates should not.

This is a graph view of facts already admitted by the server. The structural
floor, raw datoms, ordered render-selection evidence, source/contracts, and
actual output remain normal accessible HTML beside it. Cytoscape selection is a
request for those server-owned values, never a second implementation of render
selection.

## What is checked in now

| Capability | Current implementation |
|---|---|
| Arbitrary subject and separate viewer | Debug route/query inputs in `src/seon/render/web.clj`; the read-only namespace debug route does not create an agent. |
| Bounded forward refs | `seon.render.data/entity-observation` reads EAVT `[eid]` through `seon.db/index-page` with explicit limit and result-weight (`src/seon/render/data.clj:91-104`). |
| Bounded reverse refs | The same observation probes sorted installed ref attributes through AVET `[attribute eid]`, with independent attribute and result bounds (`src/seon/render/data.clj:106-143`). |
| Snapshot honesty | Continuations carry database identity, entity and direction; mismatches return `stale-continuation` (`src/seon/render/data.clj:80-89,145-181`). |
| Renderer truth | The debug surface uses the real `render-call` decision and retained static evidence rather than recreating the candidate order in JavaScript. |
| Live delivery | The existing render proc publishes revisioned packages; the existing Datastar feed chooses a delta only for a contiguous revision and otherwise paints a complete keyframe. |
| Prototype only | `docs/prds/context-generation/plan/repl-first-atlas.html:3,548-580` loads Cytoscape 3.30.4 from a CDN and constructs a graph from hand-authored `MODEL` data. It proves interaction vocabulary, not live data or scale. |

Cytoscape was absent from `package.json`, `.gitmodules`, and `reference-code/`
before this review. The official upstream is now vendored as
`reference-code/cytoscape` at the commit peeled from annotated tag `v3.30.4`:
commit
`a4de13e0c1668436273c82f90613c6e5911f3f32`. Its package declares version
3.30.4 and MIT licensing, and contains the release asset
`dist/cytoscape.min.js`. Copying that asset into Seon's packaged public
resources is proposed UI implementation; the submodule alone does not serve it.

## Source findings and action-to-API mapping

### Graph identity and direction

Cytoscape node and edge data require immutable `id`; edges additionally have
immutable `source` and `target`
(`reference-code/cytoscape/src/collection/data.js:7-36`). Use this mapping:

| Seon value | Cytoscape value | Reason |
|---|---|---|
| Entity at one branch | node `id` from branch identity plus eid | Viewer and snapshot changes must not mint a visually new entity. A branch change resets the graph. |
| Current ref datom | edge `id` from branch, `e`, `a`, ref `v`, and assertion transaction | Two attributes or assertion transactions between the same endpoints remain separately inspectable. |
| Datom `e` | edge `source` | Preserves Datahike assertion direction. |
| Ref-valued datom `v` | edge `target` | Reverse observation changes how an assertion was found, not what it means. |
| Datom `a` | edge `attribute` and label | The connection is an attributed assertion, not an unlabeled relationship. |
| `tx`, `added`, stored value | edge data and evidence panel | Provenance remains reachable without putting it all on the canvas. |

When an edge's endpoint changes, remove and add the edge; `.data()` cannot
change `source` or `target`. Node removal also removes its connected edges
(`reference-code/cytoscape/src/collection/index.js:586-634`). Reconciliation
must therefore compute exact node and edge identity sets, remove stale edges
before stale nodes, update mutable data, and add nodes before edges.

Cytoscape supports multiedges directly. `parallelEdges()` and
`codirectedEdges()` compare source and target while preserving each edge
element (`reference-code/cytoscape/src/collection/traversing.js:318-361`). Use
`curve-style: bezier` when distinct parallel assertions need visible separation;
the official style documentation says straight edges are unsuitable for
multigraphs and that bezier edges are more expensive
(`reference-code/cytoscape/documentation/index.html:14804-14922`). Do not
collapse several qualified attributes into one synthetic edge.

### Forward, reverse, and local neighborhood

`outgoers()` walks every connected edge whose source is the selected node and
returns both edge and target. `incomers()` symmetrically returns edge and source
(`reference-code/cytoscape/src/collection/traversing.js:47-74,122-134`).
`neighborhood()` is directionless and also returns edges plus adjacent nodes
(`:138-178`). These APIs are useful for highlighting the portion already in
the browser. They are not acquisition bounds: each iterates all connected
edges already loaded into that Cytoscape instance.

Use `cy.getElementById(id)` for direct selection and reconciliation. The
[official performance guidance](https://js.cytoscape.org/#performance)
identifies it as the fastest lookup and warns that selector traversals, edges,
multigraph curves, compound nodes, labels, and high pixel ratios add cost.
Delegated events keep one handler for changing elements:

```javascript
cy.on('tap.designLab', 'node', selectNode)
cy.on('tap.designLab', 'edge', selectEdge)
```

The core emitter supports qualified event names, delegated selectors, and
removal by event/selector/callback (`reference-code/cytoscape/src/emitter.js:105-173`,
`src/core/events.js:40-97`). Keyboard-accessible HTML rows must mirror node and
edge selection; a canvas alone is not an accessible fact browser.

### Incremental updates and stable position

Apply one server model change inside `cy.batch(() => ...)`. Batching queues
style work and renderer notifications, then applies the combined work once
(`reference-code/cytoscape/src/core/notification.js:45-100`). Add/remove already
invalidates traversal caches for affected nodes and parallel edges
(`reference-code/cytoscape/src/collection/index.js:541-569,586-710`); Seon
should not maintain another neighborhood cache in JavaScript.

Do not run a layout inside the data/style batch. Cytoscape's
[official layout guidance](https://blog.js.cytoscape.org/2020/05/11/layouts/)
explains that layouts need applied node dimensions and recommends completing
the style batch first. It also confirms layouts operate on a supplied
collection, so newly added elements can be laid out without moving the whole
graph.

The built-in breadth-first layout accepts explicit roots and performs a BFS
over the supplied collection (`reference-code/cytoscape/src/extensions/layout/breadthfirst.js:41-129`).
It then recalculates positions for every supplied node (`:224-366`). That makes
it a useful explicit comparison, but a poor ordinary-update default: adding one
endpoint can move everything the user just read. Directed maximal adjustment
also detects cycles and bails rather than pretending cyclic data is a DAG
(`:170-219`).

The built-in preset layout reads supplied positions or leaves the current
position when none is supplied (`reference-code/cytoscape/src/extensions/layout/preset.js:25-58`).
That is the correct default for preserving the mental map. A deterministic
two-arc position function is proposed application code, not a new layout
engine. It must handle an endpoint appearing in both directions without
duplicating the entity: keep one node, mark both directions in its data, and
place it in a neutral part of the ring.

### Datastar lifecycle

The graph instance must outlive ordinary debug-block patches. Morph a sibling
JSON/model carrier and reconcile it into the existing canvas; do not replace
the canvas node. Keep pan, zoom, selected IDs, node positions, and expanded
subjects in browser state. Database snapshot, renderer evidence, and result
remain server values.

Datastar runs cleanup callbacks returned by an attribute plugin when an
attribute or element is removed (`reference-code/datastar/library/src/engine/engine.ts:105-170,302-334`).
Its built-in `data-init` plugin discards the expression's return value
(`reference-code/datastar/library/src/plugins/attributes/init.ts:11-33`).
Therefore `data-init="() => cy.destroy()"` is not a lifecycle. The smallest
correct integration experiment is a narrow attribute plugin whose `apply`
creates/reuses one instance and returns cleanup that removes namespaced event
handlers and calls `cy.destroy()`.

`cy.destroy()` stops the animation loop, destroys the renderer, emits destroy,
and marks the core destroyed (`reference-code/cytoscape/src/core/index.js:200-227`).
The canvas renderer's destruction removes every bound DOM listener and clears
render callbacks (`reference-code/cytoscape/src/extensions/renderer/base/index.js:164-180`).
Ten Datastar updates must still report one instance and one handler set; route
removal must report the instance destroyed.

## Concrete interaction plan

1. **Open.** The existing debug URL supplies viewer, subject, output and
   explicit observation bounds. The server returns one immutable-snapshot
   observation plus renderer/result evidence. The canvas paints only ref
   datoms; the structural panel remains primary exact data.
2. **Inspect a node.** A single tap selects it in Cytoscape and the mirrored
   HTML list. The evidence panel shows its loaded identities, EID, snapshot,
   completeness, and structural value. Viewer remains fixed.
3. **Inspect an edge.** A tap shows qualified attribute, direction, `e/a/v/tx`,
   stored versus decoded value, and whether it came from outgoing or incoming
   acquisition. The attribute is a link to its schema/program row.
4. **Inspect rendering.** Selecting the node or attribute value supplies that
   exact server-side render input. The adjacent panel shows ordered stages,
   candidates, selected function or floor, source/contracts, and the actual
   output retained by the same `render-call`. JavaScript displays this evidence
   but never ranks or invokes candidates.
5. **Expand.** An endpoint's “expand” action adds its subject and independent
   incoming/outgoing cursors to the same debug registration. The render proc
   observes every expanded subject against one database value and returns one
   bounded model. New endpoints appear near the expanded node; existing nodes
   do not move.
6. **Continue.** “More outgoing” and “more incoming” advance only that subject's
   corresponding snapshot-bound continuation. A stale continuation triggers
   the existing visible restart behavior; it is never silently reused.
7. **Re-root.** “Make root” changes the selected subject while keeping viewer
   and output. It may retain the loaded subgraph, but the header and renderer
   panels clearly identify the new subject.
8. **Live database change.** The existing render wake recomputes the expanded
   observation set against one new database value. The client reconciles the
   exact set in one batch. If the selected assertion disappeared, retain a
   visible “removed at new snapshot” selection message rather than silently
   selecting something else.
9. **Live program change.** The existing runtime-evaluation input invalidates
   render calls. Candidate and output panels update; unchanged graph elements
   and positions remain intact.
10. **Leave.** The Datastar attribute cleanup destroys the Cytoscape instance.

Step 5 is proposed, not built. The present debug registration observes one
subject. Correct accumulated exploration requires the server to receive the
expanded subject/cursor set and derive every page from the same database value.
Retaining old one-subject responses only in JavaScript would make previously
expanded nodes stale after a transaction. Until the multi-subject request is
implemented, ref navigation should re-root and replace the one-hop graph rather
than claim an accumulated current graph.

## Large and high-degree graphs

The hard bound belongs in Datahike acquisition, before Cytoscape. Continue to
use independent EAVT and AVET pages. For accumulated exploration, add a single
contracted graph-observation request that composes the existing
`entity-observation` for an explicit expanded-subject vector under explicit
per-page and whole-request work/result-weight bounds. This is a composition of
the current observation owner, not stored reverse edges or a second graph
engine.

The response should expose:

- returned nodes and assertion edges;
- each subject/direction's completeness and continuation;
- ref attributes probed, including empty-but-incomplete incoming search;
- lower-bound language for loaded counts when incomplete;
- database identity shared by every page; and
- the exact request bounds and measured acquisition work already available at
  the database boundary.

Do not invent a maximum node or edge number in this review. Establish defaults
from the decisive browser experiment below. Correctness comes from server bounds
and honest continuation, not from assuming Cytoscape can comfortably render a
particular count.

Keep exact edge labels for the admitted graph. At low zoom, use
`min-zoomed-font-size` or selection styling to avoid drawing unreadable text;
the qualified attribute remains present in edge data and the accessible list.
The official docs recommend hiding labels below a useful zoom and explain that
haystack edges are faster, but haystack edges do not support endpoint arrows
(`reference-code/cytoscape/documentation/index.html:14869-14876,15109-15167,16645-16650`).
Direction is central here, so haystack is a measured fallback for overview only,
not the default. Avoid compound nodes until an experiment proves that visual
grouping is worth their documented style/render cost.

## Smallest decisive experiments

No benchmark was run in this research task. These experiments should precede a
full UI implementation:

1. **Direction and identity specimen.** One arbitrary subject with an outgoing
   ref, incoming ref, loop, two attributes to the same endpoint, and an empty
   incomplete incoming page. Falsifier: any edge loses its qualified attribute,
   direction, assertion identity, or continuation truth.
2. **Mental-map update.** Start with one page, drag two nodes, expand one
   endpoint, then apply an unrelated renderer-only update and a relevant
   database update. Compare deterministic preset expansion with full
   breadth-first and CoSE. Falsifier: existing positions, pan, zoom, selection,
   or viewer change without an explicit user action.
3. **Lifecycle.** Apply ten Datastar model patches and remove/reinsert the debug
   block. Count Cytoscape instances, DOM/cy handlers, animation loops, and
   canvases. Falsifier: more than one live instance per canvas or a non-destroyed
   removed instance.
4. **Bounded-degree progression.** Replay real observation pages from low to
   high loaded degree without changing backend bounds. Record separately data
   acquisition, JSON/event bytes, reconciliation, layout, first paint, pan/zoom
   frame behavior, and browser heap after disposal. Compare labels always
   visible versus zoom/selection disclosure, and bezier versus the directionally
   weaker haystack overview. Choose defaults only after these measurements.
5. **Coordinated rendering.** Select a node, edge attribute value, renderer
   candidate, and output in sequence; reevaluate a helper and transact a selected
   datom. Falsifier: the graph selection, header subject/viewer/snapshot,
   selection explanation, and actual output refer to different calls or bases.

For repeated cases report cold and warm acquisition, reconciliation and layout
separately, with loaded node/edge/multiedge counts, subject degree, returned
work/weight, event bytes, and p50/p95. Package suppression after expensive
recomputation is not a browser performance success; retained read/call evidence
must prevent unchanged server work first.

## Integration risks

| Risk | Required guard |
|---|---|
| Canvas replacement leaks instances or loses position | Stable canvas ID, sibling model carrier, Datastar attribute cleanup, explicit `cy.destroy()`. |
| Browser union contains mixed database snapshots | Server derives the complete expanded set from one immutable database value; otherwise replace on re-root/update. |
| Client traversal escapes backend bounds | `incomers`/`outgoers` highlight loaded elements only; every expansion returns to bounded `entity-observation`. |
| Parallel assertions collapse | Stable per-datom edge IDs and multiedge-capable curve; never key an edge only by endpoints. |
| Direction is confused with discovery path | Always draw `e -> v`; record incoming/outgoing acquisition separately. |
| Layout churn destroys the mental map | Preset for ordinary updates, layout only new elements, full re-layout explicit. |
| High-degree styling overwhelms canvas | Bound before rendering; measure labels/curves/pixel ratio; keep exact accessible list and continuation. |
| Candidate UI becomes a second selector | Render panel consumes exact `render-call` static evidence and output. |
| Viewer changes with navigation | Viewer is an independent persistent request input; re-root changes subject only. |
| Selected fact disappears | Visible removed/stale state tied to the new snapshot, never silent fallback. |
| Canvas excludes keyboard/screen-reader users | Mirror the loaded node/edge collection as semantic buttons/list rows with shared selection state. |
| Version drift or CDN dependency | Serve the pinned vendored release asset; record any later version experiment separately. |

## Tradeoffs considered

**Persistent bounded Cytoscape view with preset positions — recommended.** It
supports attributed directed multigraphs, selection, incremental add/remove,
batching, and explicit destruction while keeping Seon's Datahike and render
owners authoritative. It requires a small browser lifecycle integration and a
server request shape for correct accumulated exploration.

**Full breadth-first layout after every update.** It makes a hierarchy legible
when the visible graph is actually tree-like. Cycles, incoming and outgoing
edges around an arbitrary root, and repeated movement make it a poor default.
Keep it as an explicit comparison/re-layout control.

**Force-directed CoSE.** It can reveal clusters without a declared hierarchy,
but ordinary incremental updates move the user's landmarks and continuous work
competes with interaction. Test it only as an explicit layout choice after the
bounded specimen works.

**HTML/SVG built by hand.** This avoids a dependency for the first tiny graph,
but immediately recreates hit testing, pan/zoom, multiedges, selection,
incremental geometry and teardown. Cytoscape already owns those mechanisms.

**Load the entire database/program graph.** It removes expansion controls at
the cost of unbounded acquisition, misleading visual density and browser work.
It conflicts with the PRD's one-hop-first rule and Seon's bounded boundary law.

## Conclusion

Cytoscape 3.30.4 is a suitable rendering and interaction dependency for the
Design Lab, provided its authority stays narrow. The smallest useful next step
is the direction-and-identity specimen on the existing debug feed, using a
persistent canvas, deterministic preset positions, a semantic list mirror and
real renderer evidence. Accumulated exploration should wait for one explicit,
same-snapshot expanded-subject request; client-only accumulation would look
useful while becoming stale on the first transaction.
