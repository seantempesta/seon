---
type: research
status: complete
tags: [research, rendering, schema, web]
---

# Value-drill public schema ruling (2026-07-20)

## Verdict

The producer-neutral drill request, effective limits, projection, and result
union can now be frozen. They belong to `seon.render.value`; execution frames
and the HTTP route translate to and from them directly. Neither producer adds
an umbrella "value coordinate" or a second sampling contract.

One downstream architecture choice is still genuinely unsettled: arbitrary
map-tail paging cannot satisfy all three previously stated laws at once:

- insertion-equivalent maps produce byte-identical pages;
- request work touches at most `offset + page-size + 1` source entries; and
- pages can advance through an arbitrarily large map without first traversing
  or sorting the whole map.

A fixed bounded candidate prefix can be ranked deterministically, but growing
that prefix for later offsets can reorder earlier pages. Ranking a fixed
1025-entry universe makes page identity stable but makes the first page touch
1025 entries rather than 9. Ranking the whole map is unbounded. This report
does not silently choose among those contradictions. Sequence and set paging,
map-child drill, and all public schemas below remain fully specified; the
integrator must rule the map-tail choice before implementing map `offset > 0`.

## Dependency ledger

| Dependency or mechanism | Selected source | Contract consumed |
|---|---|---|
| Unit 0 bounded sampler | `d42a88de`, current `src/seon/render/value.cljs` | One work-bounded skeleton, honest head-plus-one omission, no second walk. |
| Unit 1A/1B projection | `284cbabf`, `882b2083`, `55a811bd`; [[schema-aware-value-projection-boundary-2026-07-20]] and current `render-html-data` | Activated-projection candidates/matches; `:valid`, `:invalid`, and `:shape-only`; invalid-only explanation. |
| Projected-key ruling | [[projected-map-key-drill-boundary-2026-07-20]] (`ddf2b5c2`) | Output-local ascending `:seon.render.value/non-drillable-key-indexes`; projected originals never cross the boundary. |
| Route authorization | [[value-route-authorization-boundary-2026-07-20]] (`7b6e2243`) | Eval authorization is an agent/eval join and missing equals unauthorized; entity selection stays parent-owned; retired eval slots are honest HTTP-200 unavailable projections. |
| Strict path codec | [[value-route-path-codec-boundary-2026-07-20]] (`c932c9e1`) | Canonical EDN vector; nil, boolean, identity-stable finite non-negative-zero number, string, keyword, or symbol only; strict EOF and no tag readers. This later ruling supersedes the authorization report's illustrative UUID allowance. |
| Configured budgets | [[value-drill-budget-config-boundary-2026-07-20]] (`3d5943db`) and [[value-drill-cap-default-ruling-2026-07-20]] (`38f24f39`) | 32 segments, 4096 raw encoded path bytes, `offset + page-size <= 1024`, and at most one additional omission sentinel. |
| Execution boundary | [[execution-child-value-sampling-boundary-2026-07-20]] | Closed correlated frames carry only the request/result schemas below and eager ordinary Transit data. The child repeats admission before live lookup. |
| Malli 0.20.0 | `reference-code/malli/src/malli/core.cljc:1223-1310,2635-2641` at `80138076960e7820523b4cb932c5b5d1936d4e7f` | Closed maps and registered predicates express the concrete boundary without `:any`, `[:maybe ...]`, or bare keys. |
| Orchard | `reference-code/orchard/src/orchard/inspect.clj:44,96-141` at `c462a25d97988f1af51e8181265c43ec9b7d3d6f` | `drop` plus `take (inc page-size)` proves truthful sequence paging, but supplies neither a total-offset ceiling nor insertion-independent bounded map pagination. |

## One owner and one normalization contract

`src/seon/render/value.cljs` owns and registers the public shapes named in
this report. It owns the pure path predicates, descent, paging, sampling, and
projection. `src/seon/config.cljs` owns exactly one public pure normalizer,
suggested name `effective-value-drill-limits`, because host policy and its
literal fallbacks already live there.

The normalizer takes one closed request containing the resolved singleton and
an optional closed operation-limit map. It returns `::effective-limits` below.
It clamps every supplied operation value downward; absence uses the resolved
singleton; no operation can widen host policy. The route calls it once before
selection. The child calls the same function again with its own resolved
singleton and the received effective values as subordinate operation values.
The two results must be equal before live lookup. Parent and child must not
copy arithmetic or defaults into `serve`, `execution`, or `host`.

The raw encoded byte count is HTTP evidence, not part of the decoded drill
request. The parent measures it against the effective byte maximum before EDN
reading. The child cannot recreate URL bytes; it independently enforces the
remaining decoded-segment, scalar, safe-integer, and total-work laws.

## Frozen scalar and limit schemas

These are registered, named schemas. The quoted predicates are pure and
public so the route and child consume the same semantics rather than copying
hand validation.

```clojure
(schema/register! ::path-segment
  'seon.render.value/drill-path-segment?)
;; nil | boolean | finite non-negative-zero number | string | keyword | symbol,
;; with the canonical EDN and Transit identity law.

(schema/register! ::path [:vector ::path-segment])
(schema/register! ::offset 'seon.render.value/safe-nonnegative-int?)
(schema/register! ::page-size 'seon.render.value/safe-positive-int?)

(schema/register! ::effective-limits
  [:map {:closed true}
   [:seon.config.render/value-max-path-segments :seon.config/cap]
   [:seon.config.render/value-max-path-bytes :seon.config/cap]
   [:seon.config.render/value-max-realized-items :seon.config/cap]
   [::page-size ::page-size]])

(schema/register! ::operation-limits
  [:map {:closed true}
   [:seon.config.render/value-max-path-segments
    {:optional true} :seon.config/cap]
   [:seon.config.render/value-max-path-bytes
    {:optional true} :seon.config/cap]
   [:seon.config.render/value-max-realized-items
    {:optional true} :seon.config/cap]
   [::page-size {:optional true} ::page-size]])
```

`safe-nonnegative-int?` means a ClojureScript safe integer in `[0,
Number.MAX_SAFE_INTEGER]`; `safe-positive-int?` excludes zero. Checked
addition is still required because separate valid integers can overflow or
cross the effective maximum. `page-size` defaults to the resolved existing
`:seon.config.render/value-max-items` and may only narrow it. It is not an HTTP
query parameter.

The initial path grammar has no tags, UUIDs, characters, collections, marker
maps, non-finite numbers, or negative zero. Vector descent adds the contextual
requirement that a numeric segment be a non-negative safe integer within the
vector count. Map descent uses the exact admitted scalar as its key. Sequences
and sets do not accept positional descent.

## Frozen producer-neutral request

```clojure
(schema/register! ::drill-request
  [:map {:closed true}
   [::path ::path]
   [::offset ::offset]
   [::effective-limits ::effective-limits]])
```

There is deliberately no eval id, entity id, agent id, selector, request id,
raw URL, configuration, database value, or live value in this map. Eval and
entity are different producers:

- the execution `value-sample` frame adds its own correlated agent, request,
  and eval ids beside one `::drill-request`; and
- the entity handler applies one `::drill-request` to the entity value it
  selected from its already-acquired immutable database value.

The HTTP parser owns a separate closed selection union in `seon.web.serve`:

```clojure
[:or
 [:map {:closed true}
  [:seon.web.serve/eval-id :seon.eval/id]
  [:seon.render.value/drill-request ::drill-request]]
 [:map {:closed true}
  [:seon.web.serve/entity-id :seon.db/id]
  [:seon.render.value/drill-request ::drill-request]]]
```

Exactly one branch is present. Missing, duplicate, both, unknown, malformed,
or over-budget query fields return a bounded user-input error before database
acquisition, authorization, child send, descent, or realization. The route
agent id comes only from the path. A missing eval and an eval owned by another
agent return the same `404` error and perform zero host sends. An entity branch
requires the `root` route namespace but no invented entity/agent ownership.

## Frozen projection schemas

The existing skeleton remains the only tree. Its concrete schema is a named
predicate over eager ordinary wire data plus the sampler invariants, never
`:any`:

```clojure
(schema/register! ::sampled-tree
  'seon.render.value/bounded-sampled-tree?)
(schema/register! ::bounded-explanation-value
  'seon.render.value/bounded-explanation-value?)

(schema/register! ::schema-status
  [:enum :valid :invalid :shape-only])
(schema/register! ::schema-status-row
  [:map {:closed true}
   [:seon.schema/key :keyword]
   [:seon.schema/entity? :boolean]
   [::status ::schema-status]])
(schema/register! ::schema-statuses [:vector ::schema-status-row])
(schema/register! ::explanation
  [:map {:closed true}
   [::humanized ::bounded-explanation-value]
   [::error-value ::bounded-explanation-value]])

(schema/register! ::drilled-projection
  [:map {:closed true}
   [::path ::path]
   [::offset ::offset]
   [::page-size ::page-size]
   [::summary [:string {:min 1}]]
   [::truncated? :boolean]
   [::more? :boolean]
   [::tree ::sampled-tree]
   [::schemas ::schema-statuses]
   [::explanation {:optional true} ::explanation]])
```

`bounded-sampled-tree?` is not a loophole for arbitrary values. It first
requires `seon.db.protocol/ordinary-wire-value?`, then recognizes only sampler
scalars and the existing closed marker vocabulary recursively within resolved
depth, key, item, and string limits. Marker maps reject unknown keys. The map
marker registers `:seon.render.value/map-entries` as ordered pairs and optional
`:seon.render.value/non-drillable-key-indexes` as an ascending vector of
distinct, in-range non-negative integers. Empty is omitted. Collection markers
register `kind`, `shown`, optional `elided`, and optional `shape`; prune,
bounded-string, opaque, and datom markers retain their current concrete
fields. This one recursive schema replaces the current public `:map` return;
it does not widen the raw-value input boundary.

`more?` is the honest page-tail sentinel: true only when the bounded probe
observed an item beyond this page. `truncated?` is broader and remains true for
depth pruning, clipped strings, projected keys, omitted map entries, or other
partial evidence. Therefore consumers never infer paging from `truncated?`.
For any non-empty `non-drillable-key-indexes`, that map node and every
descendant below the named entry emit no path control, and the projection is
incomplete for schema confirmation.

Schema rows are deterministic and use exactly the current three statuses.
`explanation` is absent unless the primary row is `:invalid`; `:valid` and
`:shape-only` never store nil or an empty placeholder. A partial skeleton is
`shape-only` and performs zero validation/explanation work. No-schema data has
an empty `schemas` vector and remains ordinarily drillable.

## Frozen available, unavailable, and error union

Producer availability is distinct from request failure. An unavailable prior
eval is still a successful, renderable HTTP-200 read of honest current state:

```clojure
(schema/register! ::availability [:enum :available :unavailable])
(schema/register! ::recompute? :boolean)
(schema/register! ::error-value
  [:map {:closed true}
   [:seon.error/message [:string {:min 1}]]
   [:seon.error/kind :keyword]
   [:seon.error/data {:optional true}
    'seon.render.value/ordinary-error-data?]])

(schema/register! ::available-result
  [:map {:closed true}
   [::ok? [:= true]]
   [::availability [:= :available]]
   [::projection ::drilled-projection]])
(schema/register! ::unavailable-result
  [:map {:closed true}
   [::ok? [:= true]]
   [::availability [:= :unavailable]]
   [::projection ::drilled-projection]
   [::recompute? [:= true]]])
(schema/register! ::failed-result
  [:map {:closed true}
   [::ok? [:= false]]
   [:seon/error ::error-value]])
(schema/register! ::drill-result
  [:or ::available-result ::unavailable-result ::failed-result])
```

The unavailable projection samples the existing bounded `lookup-result` miss
value through the same renderer; it never reconstructs or persists the old
value. `recompute?` says only that the already-authorized eval row has recorded
source from which the UI may offer an explicit re-run. It does not put source
in the public drill result. A nonexistent or errored eval is not this branch.

HTTP maps syntax/budget failure to `400`, missing-or-unauthorized eval/entity
to `404`, and transport/core unavailability to `503`, all from
`::failed-result`. No selector absence is converted to an empty success, and
no child absence spawns or retries a child. Execution uses a correlated
sample-result frame for the two successful shapes and a correlated
sample-error frame for `::failed-result`; retirement adds its ordinary
child-retired evidence inside the error data without widening the union.

## Admission and implementation order

1. Land Unit 1B, projected-key metadata, and the three config leaves.
2. Register the schemas and pure predicates above in `seon.render.value`;
   implement `config/effective-value-drill-limits` once and prove default,
   override, clamp, and parent/child equality.
3. Rule the map-tail contradiction. The conservative compatible choice is to
   make map omission honest but non-pageable (`offset` must be zero for maps);
   the alternative must explicitly relax either prefix work or
   insertion-equivalent page identity. Do not let execution or UI decide.
4. Implement descent/paging/projection in `seon.render.value` with the same
   bounded sampler and no second source walk.
5. Add closed correlated execution frames. The child repeats request schema,
   path grammar, checked arithmetic, effective-limit normalization, and
   ordinary-data validation before `lookup-result`.
6. Add strict HTTP parsing and route authorization. All parent admission
   precedes selection and child send.
7. Migrate the generic UI, `/data`, and eval technical card. Consumers read
   `more?`, status rows, explanation absence, and non-drillable indexes; they
   do not reinterpret marker labels.

## Acceptance tests

- Generatively reject every unknown request, limit, projection, marker,
  status, explanation, result, and error key. No registered shape uses bare
  keys, `:any`, `[:maybe ...]`, stored nil, or an unbounded predicate.
- Round-trip every path scalar through canonical strict EDN and the existing
  Transit codec, then prove exact map lookup. Reject tags, collections,
  non-finite numbers, negative zero, aliases, trailing input, and unsafe
  vector indexes.
- Prove absent and shipped config yield identical effective limits; operation
  values only narrow; parent and child normalization is byte-identical.
- Instrument EDN read, database acquisition/query, host send, `lookup-result`,
  descent, and realization. Duplicate/unknown fields, 4097 encoded bytes, 33
  segments, unsafe arithmetic, and `offset + page-size > 1024` leave all later
  counters at zero at the parent; direct invalid Transit frames leave child
  lookup/realization counters at zero.
- At offset 1016 and page size 8, retain at most 8 and touch at most 1025
  sequence items including the sentinel. Offset 1017 is refused with zero
  touches. `more?` matches the sentinel; `truncated?` additionally covers
  every non-page omission class.
- Assert a non-drillable index vector is ascending, distinct, and names final
  retained map-entry positions; unsafe originals never enter projection,
  path, Transit, or a mutable registry; descendants emit no control.
- Complete valid, complete invalid, incomplete, and no-schema slices prove
  exact rows and explanation presence. Incomplete slices leave validator and
  explainer counters at zero.
- Transit-round-trip request plus all three result branches. Available and
  unavailable are bounded HTTP-200 projections; missing/unauthorized is one
  indistinguishable `404`; syntax/budget is `400`; transport/core is `503`.
- Eviction, prior process, no retained child, mid-request retirement, timeout,
  cancellation, and stale response settle once, never spawn/retry, and never
  return a raw live value.
- After the map-tail ruling, add the chosen falsifier explicitly: either map
  offset is refused honestly, or the relaxed work/identity law is named and
  instrumented. A small-output assertion is not proof.

This schema ruling closes the transport-shape ambiguity. It intentionally
leaves the irreducible map-tail policy visible for top-level architecture
judgment before implementation begins.
