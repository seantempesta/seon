---
type: research
status: complete
tags: [research, web, schema, architecture]
---

# Data-browser implementation readiness audit

## Decision and start gate

Stage 1.5 is ready to split into dependency-ordered owners, but production
implementation must not start until the Stage 1.6 owner declares its
`seon.schema` / render overlap closed and frozen. The first implementation
boundary after that declaration is not schema matching or UI migration: it is
bounded-walk totality in `seon.render.value`.

Two existing paths falsify the claim that the current sampler is bounded in
work:

- `opaque-marker` (`src/seon/render/value.cljs:163-196`) constructs full
  `pr-str` / `str` values before token clipping records, host objects, and the
  unknown fallback. A sufficiently large or cyclic host graph can exhaust the
  pod or execution child before clipping.
- the map branch of `sample*` (`src/seon/render/value.cljs:366-382`) recursively
  samples every entry into `sampled`, then ranks and retains `max-keys`. It is
  output-bounded but not work-bounded; a huge map still walks every value.

The shortest falsifiers are respectively a host object whose printable form is
larger than the configured bound and a map with a counter-bearing value at
every key. The counter must remain bounded by an explicit probe budget rather
than the map cardinality. These are release-blocking safety prerequisites for
routing three additional high-fanout consumers through the generic tree.

## Dependency ledger

| Mechanism | Selected revision | Exact grounding | Existing Seon owner / idiom |
|---|---|---|---|
| Malli | `80138076960e7820523b4cb932c5b5d1936d4e7f` | `reference-code/malli/src/malli/core.cljc:353-361` schema-local cache; `:2582-2603` properties and children; `:2660-2666` explainer reuse; `reference-code/malli/src/malli/error.cljc:344-403` spell checking, humanize, and error value | `src/seon/schema.cljc:293-375` activated projection; `:404-419` current projection and entity catalog. Browser artifacts must key on projection object identity, never candidate forms or the 32-bit fingerprint. |
| Orchard inspector | `c462a25d97988f1af51e8181265c43ec9b7d3d6f` | `reference-code/orchard/src/orchard/inspect.clj:44,96-141,150-200` page size, head-plus-one bounded count, paging, and path descent | `src/seon/render/value.cljs:314-399` is the one sampler to strengthen; path and offset remain ordinary request data. |
| Reveal | `911b7b678b739f3ca19b8f95ed013a669b296c1d` | vendored inspector/view navigation under `reference-code/reveal/` | Prior-art only; no second inspector state machine or registry. |
| ClojureScript | `946d75f3483c0c8e784e6668bff2c71a25619a77` | vendored self-host/runtime sources under `reference-code/clojurescript/` | `src/seon/eval.cljs:1030-1708` owns child-local `result/<id>` slots and `lookup-result`. |
| Bun | `d8ecf098572e2b8265b23e40c04efb4067e516cc` | vendored subprocess/IPC implementation under `reference-code/bun/` | Seon deliberately narrows IPC to Transit ordinary data in `src/seon/execution.cljs:81-102,243-289`; Bun clone identity is not an authority. |
| Datahike | `6f2569087ed31f53e751e7535ef4bf2527912046` | vendored immutable database/index behavior under `reference-code/datahike/` | `/data` already acquires an immutable database value and calls `db/index-page` in `src/seon/web/debug.cljs:169-179`; entity drill stays parent-owned. |
| Reitit + Datastar | maintained vendored sources | `reference-code/reitit/`, `reference-code/datastar/`, and `reference-code/datastar-clojure/` | `src/seon/web/router.cljs:251-268` and `src/seon/web/debug.cljs:181-211`; one read-only route and whole-element morphs, not `/call` or a second feed. |

Malli's spell checker only diagnoses extra keys for closed maps. Seon's open
maps must not promise misspelling detection. Many open schemas can validate the
same value, so deterministic specificity ordering is a correctness contract,
not presentation polish.

## Owner split and protected collisions

### Generic schema-aware rendering owner

This owner may change `src/seon/schema.cljc`, `src/seon/render/value.cljs`,
`src/seon/render.cljs`, the plan schema/property registrations, the `/data`
parent projection, and their focused tests after Stage 1.6 freezes overlapping
paths. Its coherent result is:

- activated-projection-derived indexes for valid `matching-shapes` and bounded
  structural `candidate-shapes`;
- validators/explainers cached by activated projection object identity;
- completeness, match, validation, explanation, and renderer-property data in
  the one ordinary browser projection;
- explicit schema property renderers overriding the unconditional generic
  bounded tree; and
- migration of generic fallback, `/data`, and eval cards only after sampler
  totality is proven.

It must not call `candidate-validator` or `candidate-explainer`
(`src/seon/schema.cljc:537-551`), because those read mutable candidate forms
instead of the activated program.

### Child-owned sampling transport owner

The execution/eval owner exclusively changes these protected mechanisms:

- `src/seon/execution.cljs` parent/child message schemas and child dispatch;
- `src/seon/execution/host.cljs` addressability, retirement, and bounded
  request settlement;
- `src/seon/eval.cljs:1522-1555` child-local `lookup-result`; and
- execution protocol/process tests.

The operation is a bounded read request, not a compiled function invocation:
the parent sends agent id, eval id, validated path, offset, and sampler limits;
the child reads its own `result/<id>`, samples the selected slice, and returns
only eager ordinary `render-html-data`. A retired/restarted child or evicted
slot returns the existing error value. No parent dereference, arbitrary-value
persistence, shared object identity, `/call` capability, or umbrella value
store is allowed.

The generic-rendering owner may define the ordinary request/result shapes and
consume the projection, but must not edit these execution/eval paths. The
top-level integrator owns the direct boundary translation and resolves any
schema names shared across the two lanes.

### Web route and authorization owner

`src/seon/web/router.cljs` currently keeps `/data` and `/data/feed` in the
static supplement despite the localized route authority's target of route
datoms. Stage 1.5 should add the one read-only
`/agent/{id}/value?eval=&path=&offset=` route through the established router
owner; it must not expand `/call`. Before addressing a child, the handler joins
the eval's `:seon.eval/agent` to the path agent id. It validates path length,
path element shape, offset, and requested limits against configuration maxima.
Entity drill reads an acquired immutable parent database value; eval drill
addresses the child. They share a projection and route presentation, not an
authority abstraction.

Route source and `src/seon/web/debug.cljs` are independent of the protected
execution implementation, but integration tests cross both and remain a
top-level proof boundary.

## Acceptance-probe map

| Acceptance probe | Existing owner | Dependency / shortest falsifier | Collision and proof destination |
|---|---|---|---|
| Plan tree uses schema properties; bespoke tree deleted | plan schemas plus `seon.render` dispatch | Register `:seon.render/html` and `:seon.render/ai` on the plan shape, render representative plan data, and assert the generic machinery resolves those symbols. | Plan source is shared semantic ownership; perform after projection/property contract settles. Delete, do not retain, the hand-built tree in `src/my/plan/internal.cljs` around the PRD-recorded `1831,1940,1970` sites. |
| A second new custom schema needs no machinery edit | `seon.schema` projection plus render dispatch | Register one test-only schema property and renderer symbol; source diff outside registration/renderer/test must be empty. | Proves extension by addition and explicit override precedence. |
| Green/red/shape-only status on agent page and `/data` | schema matcher, value projection, `data-panel`, `/data` render | Valid complete value, wrong-type complete value, missing-required-key near match, ambiguous valid open-map matches, and an elided value. | `schema.cljc` overlaps Stage 1.6. Invalid explanation runs only on the selected bounded complete slice; elided top values never confirm. |
| Invalid hover explanation has zero round trips | HTML tree renderer | Render the deliberately invalid projection and inspect the hiccup/DOM for embedded humanized explanation before interaction. | Datastar presentation only; no hover endpoint or stored explanation entity. Browser live proof after focused structural test. |
| No-schema value renders and drills | unconditional `render-html-data` and generic default | A namespaced ordinary map matching no registered schema must render a tree and a child slice. | Blocked by opaque/map sampler safety. `generic-default-renderer` currently uses unbounded pprint at `src/seon/render.cljs:676-692`; replacement happens only after safety proof. |
| `/data` entity drill uses the same projection | `seon.web.debug` plus parent database API | Select an entity from `db/index-page`, drill a nested attr/ref from one immutable database value, and observe bounded ordinary projection data. | Current `/data` raw `pr-str` at `src/seon/web/debug.cljs:154-179` is a known leak. It must not be “fixed” by sending database handles to the child. |
| Large live eval value pages parent-to-child | execution/eval transport plus route | Evaluate a value larger than one page in agent A, request page zero and nonzero offset, and assert ordinary bounded responses with stable paths and no parent lookup. | Protected execution/eval lane. Include huge-map work-bound counter and infinite/lazy-seq head-plus-one proofs. |
| Route refuses cross-agent ownership | web authorization before execution host | Request agent A's eval id through agent B's route and assert refusal while no child invocation is sent. | Integration spy/test belongs at route-host seam; route read-only status is not authorization. |
| Retired child renders honest unavailable | execution host retirement plus existing eval miss value | Produce eval, retire its child, request the same slice, assert prior-session/eviction error plus recorded source recomputation affordance. | No durability workaround. Verify after real child retirement, not only a mocked miss. |
| Final graduation | all owners, integrated | Full CLJS suite; live default cluster agent page and `/data`; large eval paging; ownership refusal; child retirement; console/feed check. | Source freeze before build/restart. No acceptance claim from separately green lanes until the integrated system passes. |

## Dependency-ordered implementation split

1. **Stage 1.6 closure and source freeze.** The top-level owner records that
   its schema/render changes and G8-G11 proofs are complete. This is the only
   prerequisite that prevents immediate implementation.
2. **Unit 0 — bounded-walk safety.** In `seon.render.value`, make opaque
   summaries non-materializing and make map work explicitly bounded while
   retaining stable path, honest elision, totality, and ordinary-data output.
   Focused allocation/counter/poisoned-value tests close this unit.
3. **Unit 1 — activated schema projection.** Derive property, required-key,
   specificity, valid-match, and bounded near-match indexes from the activated
   projection. Add the projection-identity validator/explainer generation.
4. **Parallel refill after Unit 1 contract freezes.** One owner implements
   generic browser projection/tree/status/custom dispatch and plan
   extensibility; the protected execution/eval owner implements bounded child
   sampling transport. A third safe owner may implement route parsing,
   ownership authorization, and parent-owned entity sampling against the
   frozen ordinary request/result shapes.
5. **Unit 2 integration.** Connect the route directly to parent entity
   sampling or child eval sampling, then migrate `/data`, generic fallback,
   and eval cards. Delete every superseded pprint and bespoke plan-tree path in
   the same unit.
6. **Integrated graduation.** Freeze sources, run the full suite, restart the
   default cluster, and execute every acceptance probe above. Stage 1.5 closes
   only after the retired-child and cross-agent live negatives pass.

Immediately after Stage 1.6 closes, Unit 0 can begin without waiting for any
execution-protocol design work. Source reading has already established the
transport boundary, so the execution/eval lane can prepare its focused test
fixtures concurrently, but production edits should wait until Unit 1 freezes
the ordinary projection/request shapes and the top-level integrator assigns
their owner.
