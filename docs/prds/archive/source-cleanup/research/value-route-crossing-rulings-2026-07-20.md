---
type: research
status: complete
tags: [research, web, rendering, architecture]
---

# Value-route crossing rulings (2026-07-20)

## Decision

The read-only value route has one implementable crossing after Unit 1G exposes
its parent-facing sampler. Admission acquires policy from one immutable database
value, parent-owned entities derive their schema projection from that same
value, and successful HTTP reads return one server-rendered HTML subtree through
Datastar's existing non-stream morph path. The route adds no configuration
cache, ambient schema projection, browser EDN reader, feed, or execution lane.

These rulings close the four questions in
[[value-route-implementation-readiness-2026-07-20]]. They do not authorize the
eval branch to guess Unit 1G's still-uncommitted public entry point.

## Grounding

| Boundary | Maintained source | Constraint |
|---|---|---|
| Configuration | `src/seon/config.cljs:989-1112,1187-1238` | The operation owning an immutable database value decodes the singleton once. The database value is the cache; there is no ambient accessor or second configuration cache. |
| Database reads | `src/seon/db.cljs:1094-1112`; `docs/seon/architecture/ui.md` | Related reads execute against one explicit immutable database value. A derived UI projection never becomes stored authority. |
| Schema projection | `src/seon/schema.cljc:485-584`; `src/seon/render/value.cljc` | `projection-from-rows` is the portable committed-rows decoder and `drill-value` requires an explicit projection. `current-projection` exposes no database basis and is not an entity-route input. |
| Execution transport | `src/seon/execution.cljs:894-963`; `src/seon/execution/host.cljs`; [[unit-1g-value-sampling-transport-implementation-readiness-2026-07-20]] | Serving peers implement correlated bounded sampling, but repository commit `9388d6aa` has no public parent sampler. The route may consume only the Unit 1G handoff after it lands. |
| HTTP and UI | `src/seon/render.cljs:522-600`; `src/seon/web/datastar.cljs:122-201`; `reference-code/datastar/library/src/plugins/actions/fetch.ts:530-615` | `render/block` already renders a value projection server-side. Datastar treats a successful `text/html` response as `datastar-patch-elements`; it does not require another SSE feed or client parser. |
| Routes | `reference-code/reitit/modules/reitit-ring/src/reitit/ring.cljc:121-148,360-390`; `src/seon/web/router.cljs` | The database-seeded GET row and late-resolved handler are sufficient. No static route supplement or special dispatcher is required. |

## Ruling 1: configuration acquisition precedes configured work admission

Before database acquisition, the handler performs only work that needs no
configuration:

- request-method and parameter multiplicity checks;
- malformed percent-framing rejection; and
- one fixed, implementation-level absolute framing ceiling that is no larger
  than the parser can safely hold.

It then acquires exactly one immutable database value. At that value it reads
and decodes the configuration singleton once and resolves the effective drill
limits. Configured encoded-byte, decoded-segment, offset, checked-addition, and
total-realization admission completes before the handler interprets a selector
result, looks up a retained execution lane, sends a host message, descends into
a value, or realizes a collection.

Literal refusal before every database operation is incompatible with the
maintained configuration law: there is no public ambient configuration value,
and adding an injected or route-local cache would create a second authority.
Accordingly, “zero database lookup” means zero domain, selector, entity,
program, or schema use after the single mandatory policy acquisition—not zero
acquisition of the policy that defines the bound.

The fixed pre-acquisition ceiling is a parser-safety bound, not configuration.
It cannot be query-controlled, persisted as route state, or used to widen the
database-owned effective limits.

## Ruling 2: parent entities and schema rows share one database value

For `entity=<eid>`, the one acquired immutable database value supplies all of:

- the configuration singleton;
- entity existence and the entity value;
- committed `:seon.schema/key` / `:seon.schema/form` rows; and
- committed `:seon.fn/sym` / `:seon.fn/spec` rows.

The route constructs the complete explicit projection with
`schema/projection-from-rows` and passes that projection and entity value to
`render.value/drill-value`. It does not call `schema/current-projection`, infer
schema state from namespace load order, add a projection cache, or create a
basis accessor merely to avoid the existing portable transform.

This ruling is parent-entity-specific. An eval's raw value remains owned by its
retained serving runtime, which samples with that runtime's already
basis-fenced committed projection. The route never substitutes its current
parent projection for the serving runtime's projection.

## Ruling 3: successful reads are one HTML subtree

A successful or honestly unavailable sample returns:

- status `200`;
- `Content-Type: text/html; charset=utf-8`;
- `Cache-Control: no-store`;
- no cross-origin resource-sharing header; and
- one complete, stable-id value subtree produced server-side by the existing
  value-block rendering mechanism from the ordinary bounded
  `:seon.render.value/drill-result` projection.

The subtree root is the element the click intends to replace. The later UI
unit owns its Hiccup, controls, labels, and styling; the route unit owns only
the HTTP wrapper and the call into that one renderer. This prevents route/UI
scope overlap while freezing one representation.

Datastar's maintained fetch action converts a successful `text/html` response
directly into its existing `datastar-patch-elements` operation. Therefore the
route does not emit an SSE event, open a per-drill feed, return EDN for a new
browser parser, or maintain client-side drill state. The ordinary long-lived
page feed remains unchanged.

Input, absence, and core failures remain closed bounded error values:

- `400` for syntax or budget refusal;
- uniform `404` for missing and cross-agent evals, non-root entity selection,
  and missing entities; and
- `503` for a distinct transport or core failure.

Those non-200 bodies use `application/edn; charset=utf-8`, carry
`Cache-Control: no-store`, and carry no CORS header. Datastar does not morph a
non-200 response. Honest runtime retirement or eviction is not a transport
failure: it is the status-200 unavailable value subtree with recomputation
meaning.

## Ruling 4: the eval branch waits for Unit 1G's public sampler

The eval branch depends on one public function owned by
`seon.execution.host`. Unit 1G must freeze its exact name and closed input,
result, unavailable, timeout, retirement, and stale-owner semantics before the
route calls it. Its contract must:

- accept the trusted path agent id, authorized eval id, and already-admitted
  drill request;
- address only the eval's already-retained recorded owner;
- join the existing lane queue and correlated request settlement;
- return the closed bounded drill result, unavailable result, or bounded core
  error; and
- never spawn, retry on another runtime, re-select a tier from current facts,
  call parent `lookup-result`, or reparse persisted result EDN.

At `9388d6aa`, the protocol peers and serving-runtime handlers exist, but
`seon.execution.host` exposes no parent-facing value-sampling function. The
route must not call private lane state, overload `invoke!`, or guess the future
symbol. Entity parsing can be prepared in design, but landing a half-route
before this handoff would leave the database route authority with an invented
eval contract and is rejected.

## Revised acceptance language

The route's focused proof must establish:

- absolute framing refusals perform zero database acquisition;
- after one policy acquisition, every configured refusal performs zero
  selector/entity/schema use, retained-lane lookup, host send, descent, and
  realization;
- one entity request proves configuration, entity, schema rows, function rows,
  and `projection-from-rows` all consume the identical immutable database
  value, while the execution-send spy remains zero;
- `schema/current-projection` is made to throw in the entity test and is never
  called;
- missing and cross-agent eval requests are byte-identical `404` responses and
  call Unit 1G's sampler zero times;
- a successful available and honestly unavailable result is status 200,
  `text/html; charset=utf-8`, one stable-id value subtree, `no-store`, and no
  CORS header;
- `400`, `404`, and `503` responses are bounded EDN, `no-store`, and do not
  morph the current value subtree;
- a Datastar click consumes the real successful HTML response through the
  maintained non-SSE patch-elements path, without opening another feed or
  loading a client EDN reader; and
- real retirement addresses no fresh runtime, returns one unavailable subtree,
  and performs no retry, spawn, persisted-result parse, or stale settlement.

The integrated work proof still instruments source touches: an admitted page
may touch at most `offset + page-size + 1`; an output-size assertion alone is
not acceptance evidence.

## No user decision required

There is no product contradiction requiring owner intervention. The apparent
zero-work conflict disappears once policy acquisition is named separately from
domain work, and Datastar's maintained `text/html` response behavior supplies
the one UI crossing without merging route and UI ownership. The only remaining
dependency is ordinary sequencing: Unit 1G must commit and hand off its public
sampler before route implementation begins.
