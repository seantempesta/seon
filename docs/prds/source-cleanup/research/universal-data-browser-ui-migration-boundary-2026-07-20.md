---
type: research
status: complete
tags: [research, web, rendering, architecture]
---

# Universal data browser UI migration boundary (2026-07-20)

## Decision

The final Stage 1.5 UI cut is one consumer migration after the activated
schema, bounded projection, drill-budget, execution-child, and route contracts
freeze. It strengthens `seon.render` in place:

- raw values first honor a per-entity render override, then the first
  deterministically ordered **validated** matching schema carrying the
  requested `:seon.render/html` or `:seon.render/ai` property;
- values without such a renderer use the schema-aware bounded tree;
- an invalid structural candidate never selects custom code; and
- an already-produced `:seon.render.value/tree` projection always means "as
  data", so the generic escape hatch cannot recurse back into custom dispatch.

There is no UI registry, plan branch in `block`, second value walker, browser
validation cache, or drill feed. Renderer properties remain data on activated
schema forms, symbols resolve late, `unwrap-response` remains the one result
contract, and the existing guard remains the one failure boundary.

## Dependency ledger

| Dependency or mechanism | Selected source | Contract consumed here |
|---|---|---|
| Activated schema projection | `src/seon/schema.cljc`; [[activated-schema-projection-boundary-2026-07-20]] | `matching-shapes` is activated-projection-scoped, deterministic, and returns only valid matches. The widened activated catalog exposes render properties for any map schema, not only database entities. |
| Schema-aware value projection | `src/seon/render/value.cljs`; [[schema-aware-value-projection-boundary-2026-07-20]] | One bounded sample carries ordered schema rows, status, invalid-only explanation, summary, tree, and strict `:truncated?`. The UI must render this data and must not validate or sample again. |
| Drill bounds and original keys | [[../../seon/issues/projected-map-keys-are-not-drill-paths]] and [[../../seon/issues/value-drill-has-no-total-work-bounds]] | A drill control is emitted only for a separately retained original drill path; displayed projected keys are never serialized as paths. Path bytes, segments, offset plus page size, and total realization have already been rejected or bounded before UI work. |
| Child sampling and value route | [[execution-child-value-sampling-boundary-2026-07-20]] and [[value-route-authorization-boundary-2026-07-20]] | `GET /agent/{id}/value` returns the same ordinary bounded projection for authorized eval values and parent-owned database entities. UI consumes it; it does not add another feed, authorization check, result lookup, or database acquisition. |
| One renderer | `src/seon/render.cljs`, `src/seon/render/AGENTS.md`, `docs/seon/architecture/ui.md` | `block`, late symbol resolution, `unwrap-response`, strict/graceful guard, and `data-panel` are extended in place. |
| One live channel | `src/seon/web/datastar.cljs`, `src/seon/web/debug.cljs`, `reference-code/datastar-clojure/src/dev/examples/tiny_gzip.clj` | Pages remain `view = f(db)` whole-element Datastar morphs. A drill response updates the existing view; it does not create a per-node SSE channel. Long-lived feeds are proven server-side. |

## Exact owner cut

### Custom dispatch and generic tree

`src/seon/render.cljs` is the only dispatch owner. Its final ordered resolution
is:

1. preserve the existing explicit per-entity slot override;
2. obtain ordered valid matches from `schema/matching-shapes` and select the
   first match whose activated catalog row carries the requested property;
3. call the late-resolved symbol with the ordinary render request plus
   `:seon.render/schema-key`;
4. otherwise call `render-html-data`/`render-ai` and the existing data panel.

The tagged `block` cases (markdown, source, code, error, literal hiccup, and an
existing data projection) remain ahead of raw-value schema dispatch. This keeps
control values unambiguous and makes the explicit "as data" action a call to
the same projection rather than a flag or second renderer. A missing or
throwing property renderer uses the current visible guarded failure behavior;
it must not silently fall through and conceal a broken registration.

The same file renders the projection header: primary schema, green/red/hollow
status, additional valid/shape-only badges, invalid explanation already present
in the projection, honest partial state, and drill affordances. Hover is pure
HTML/CSS with zero request. Click builds only a validated route URL from stored
selector/path data; `<details>` still owns local disclosure.

Owning focused tests are `test/seon/render/block_test.cljs` and
`test/seon/render_test.cljs`. They prove precedence, all-match labels,
invalid-candidate non-dispatch, no-schema fallback, "as data" non-recursion,
late redefinition, and guarded renderer failure. Projection semantics stay in
`test/seon/render/value_test.cljs` and must not be duplicated here.

### `/data`

`src/seon/web/debug.cljs` replaces `data-element`'s raw
`pr-str (:datahike.index-page/datoms page)` with `render/block` over the one
bounded projection. The existing `render-data!` acquisition remains pinned to
the database value supplied by the feed computation; entity drill targets the
frozen route contract and never sends a database/entity handle to a child.
`src/seon/web/router.cljs` removes `/data`'s temporary static-supplement entries
only when the frozen database-seeded route is active; it must not leave two
route authorities.

`test/seon/web/serve_test.cljs` owns the focused server assertions: public
index-page request shape remains bounded, output contains value-panel structure
rather than one raw EDN `<pre>`, the acquired database value is identical
through selection and projection, and route headers/statuses follow the route
contract.

### Eval technical card

`src/seon/handlers/eval.cljs` preserves the cheap one-line activity row and
source/error presentation. Only the successful technical result disclosure
changes: the stored bounded `:seon.eval/result-edn` remains a summary/fallback,
while an available live result is obtained through the authorized value route
and rendered as the returned data projection. The web host never reads
`result/<id>`, reparses stored EDN as the authoritative live value, or embeds a
large result in every normal transcript morph.

`test/seon/handlers/eval_test.cljs` proves success, ordinary eval failure,
retired/evicted child, and transport failure remain distinct. A retired child
is an HTTP-200 prior-session/eviction projection with the recorded source
recompute affordance; unauthorized/missing remains 404; transport/core
unavailability remains 503.

### Plan extensibility proof and deletion

The current `my.plan.internal/plan-block-html` is not directly registrable as a
custom value renderer: it performs its own database query and constructs a
whole forest. Adding schema properties to that async function would preserve
the parallel acquisition/render mechanism.

The cut therefore separates the already-existing structural read from pure
presentation:

- `src/my/plan.cljs` registers a concrete nested plan-value schema (replacing
  the current loose `::tree-result` `:map`/`:vector :map` boundary) with
  `:seon.render/html` and `:seon.render/ai` properties;
- acquisition continues through the existing `my.plan/tree`/database path and
  produces that ordinary nested value once;
- `src/my/plan/internal.cljs` retains only pure plan-value-to-hiccup and
  plan-value-to-text functions, reusing the current row/forest presentation
  helpers where useful; and
- `acquire-html-plan-rows`, the second HTML-only query selector, and the
  DB-acquiring `plan-block-html` path are deleted. Config block entries in
  `config/system.edn` and `config/minimal-plan.edn` point at the ordinary
  acquisition/composition owner, not the retired HTML query path.

The acceptance value is an eval-returned plan tree, not a special entity row:
it selects its renderer solely from schema properties, displays the same value
through "as data", and becomes generic-red with a hover explanation when a
required key is removed. `test/my/plan_test.cljs` replaces the test that stubs
`db/query` inside `plan-block-html` with pure property-dispatch and structural
read tests. No code in `render.cljs` names `my.plan`.

## Crossing hazards

- **Candidate is not match.** Dispatching from `candidate-shapes` invokes code
  on invalid data and defeats the red diagnostic state.
- **Properties are activated state.** Reading candidate forms makes a
  mid-eval registration visible before admission and breaks projection
  identity/cache semantics.
- **Async plan renderer is not the proof.** A registered function that queries
  the database creates a second acquisition boundary and cannot render an
  eval-returned ordinary plan value consistently.
- **Stored eval EDN is not the live value.** Parsing it loses types, may describe
  an intentionally clipped value, and bypasses child ownership/retirement.
- **Morph cost.** Normal transcript activity rows must stay fixed-size; live
  result materialization belongs behind explicit technical disclosure/drill.
- **Displayed keys are not addresses.** A clipped/opaque key label can never be
  placed into `path`; non-drillable nodes remain visibly honest.
- **No route duplication.** `/data` and the value GET move into database route
  truth in the route unit; static-supplement copies are removed in the same
  cutover.
- **No browser-only SSE conclusion.** The browser bridge may 503 a long-lived
  event stream. Feed/morph proof uses a server-side client plus pod logs, with
  the real browser used for DOM and interaction evidence.

## Ordered implementation handoff

1. Freeze Units 1A/1B, original-key and total-work fixes, child frames, and the
   value route with their focused tests.
2. Extend `seon.render` property dispatch and projection UI; freeze pure tests.
3. Migrate `/data` and eval technical cards, deleting raw `pr-str`/source-block
   result rendering and the superseded static route entries.
4. Convert plan acquisition to one ordinary nested value, register its two
   properties, and delete the HTML-only database query/render path.
5. Run the integrated server, retirement, authorization, and real-browser gate.

No downstream unit may infer missing config numbers, projection keys, route
statuses, or child frames while performing this UI cut.

## Integrated acceptance matrix

### Pure and focused

- Complete value matching two open schemas shows deterministic badges and
  selects the most-specific valid renderer carrying the requested property.
- Missing-key and wrong-type candidates render generic red with hover
  explanation and never call custom code.
- Incomplete data is hollow `:shape-only`; no-schema data is an ordinary
  drillable generic tree.
- Custom render and "as data" show the same value without recursion or another
  sample authority.
- One new fixture schema plus one renderer function works without edits to
  `block`, the panel, route, or web consumer code.
- The plan tree renders by property, its HTML-only query path is absent, and
  `rg "my\\.plan" src/seon/render.cljs` finds no plan dispatch.

### Server and child lifecycle

- `/data` renders bounded schema-aware datoms/entities from one immutable
  database value and pages through the value route.
- A large live eval value initially returns bounded markup and each click
  realizes only the allowed page plus one honest tail sentinel.
- Cross-agent eval selection returns the same 404 as missing and records zero
  child sends.
- Retiring the owning child changes the drill to the honest unavailable
  projection with recompute source; a transport failure remains 503.
- Response bytes and visited-item instrumentation prove work bounds, not merely
  small output.

### Real browser plus server-side feed

- On `/agent/{id}`, valid/invalid/shape-only dots and badges are legible in the
  Phosphor palette; invalid hover reveals the shipped explanation without a
  request; custom/"as data" switching and nested paging work after Datastar
  morphs.
- On `/data`, the same tree, statuses, hover, and paging behavior appears; no
  raw EDN `<pre>` remains.
- A server-side SSE client observes `datastar-patch-elements` frames and
  `logs/pod.log` shows feed open/broadcast. Browser console has no errors and
  stable `#app-view` identity survives the morph.

Stage 1.5 graduates only when the focused CLJS suites, full CLJS suite, server
matrix, child-retirement proof, server-side SSE observation, and real-browser
interaction all pass against one frozen source revision.
