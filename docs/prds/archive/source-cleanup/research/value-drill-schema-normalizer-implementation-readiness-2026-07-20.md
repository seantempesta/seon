---
type: research
status: complete
tags: [research, rendering, config, testing]
---

# Value-drill schema and normalizer implementation readiness (2026-07-20)

## Verdict

The next implementation boundary should be split in two:

1. correct and register the producer-neutral public value-drill shapes, then
   land the one `seon.config/effective-value-drill-limits` normalizer; and
2. implement descent, paging, sampling projection, and result construction in
   a later renderer unit.

The first unit is almost ready, but the frozen ruling in
[[value-drill-public-schema-ruling-2026-07-20]] (`ec86accb`) contains one
mechanically impossible detail: `::sampled-tree`,
`::bounded-explanation-value`, and the optional error data are specified as
registered custom predicate symbols. Seon's registered schema population must
round-trip as pure EDN and compile without application functions or SCI
namespace resolution. A qualified application predicate such as
`'seon.render.value/bounded-sampled-tree?` is readable data, but it is not a
Malli predicate schema in the maintained registry and cannot compile in the
complete projection. An inline `[:fn ...]` with the function object fails the
EDN round-trip law. The existing renderer authority explicitly records this
platform law for recursive hiccup in `src/seon/render/canvas.cljs`.

Do not implement the ruling literally, weaken the three slots to `:any` or an
unbounded `:map`, add a second schema registry, or teach the global registry to
evaluate application symbols merely for this feature. The top-level owner
must first fold a small correction into the ruling: registered public maps
remain concrete pure-data shapes, while deep bounded-tree, explanation, and
error-data validation is performed by public predicate functions at the
producer/transport function boundaries. That is Seon's established
non-recursive Malli idiom.

Apart from that correction, the schema/normalizer unit has no source-order or
namespace-cycle blocker. `seon.config` may name
`:seon.render.value/operation-limits` and
`:seon.render.value/effective-limits` in function metadata without requiring
`seon.render.value`: `schema/build-projection` resolves references against the
complete population, independent of namespace declaration order. The
normalizer body uses literal namespaced keys plus existing config accessors.
The production dependency stays one-way (`render.value` requires `config`),
so no `config -> render.value -> config` require cycle is introduced.

## Dependency ledger

| Dependency or mechanism | Selected source | Contract consumed |
|---|---|---|
| Public drill ruling | [[value-drill-public-schema-ruling-2026-07-20]] (`ec86accb`) and roadmap integration `3bf60d33` | Closed request, limits, projection, result union, path grammar, map non-pageability, and ordered implementation boundary. |
| Landed cap leaves | `210810dc`; `src/seon/config.cljs`, `config/system.edn`, and `test/seon/config_test.cljs` | Resolved 32-segment, 4,096-byte, and 1,024-realized-item singleton maxima plus existing `value-max-items` page-size policy. |
| Bounded renderer | `src/seon/render/value.cljs`; Unit 0 plus Units 1A-1D | One bounded skeleton, output-local non-drillable indexes, single-sample schema status, and no second walk. |
| Schema population | `src/seon/schema.cljc:222-330`; `src/seon/schema/internal.cljc:89-128`; Malli `reference-code/malli/src/malli/core.cljc:2877-2901,2928-2960` at `80138076960e7820523b4cb932c5b5d1936d4e7f` | Registered forms round-trip as EDN and compile against the complete registry. Malli's default predicate registry contains core predicates, not arbitrary qualified application predicates. |
| Existing recursive-validation ruling | `src/seon/render/canvas.cljs:106-172` and `src/seon/render.cljs:150-161` | Deep recursive validation belongs in a compiled function boundary when a pure-data registered recursive schema is not viable. |
| Ordinary wire boundary | `src/seon/db/protocol.cljc:111-174` | The result is eager ordinary data, but `ordinary-wire-value?` alone is broader and recursively unbounded, so it cannot be the only hostile-input validator. |
| Path codec | [[value-route-path-codec-boundary-2026-07-20]] (`c932c9e1`) | Nil, booleans, identity-stable finite non-negative-zero numbers, strings, keywords, and symbols; vectors apply a stricter safe-index rule contextually. |
| Paging reference | Orchard `reference-code/orchard/src/orchard/inspect.clj:44,96-141` at `c462a25d97988f1af51e8181265c43ec9b7d3d6f` | `drop` plus `take (inc n)` establishes honest sequence tail detection, but does not solve deterministic arbitrary-map paging. |

## Unit 1E: public shapes and effective limits

### Owned paths

The implementation unit should own only:

- `src/seon/render/value.cljs`;
- `src/seon/config.cljs`;
- `test/seon/render/value_test.cljs`; and
- `test/seon/config_test.cljs`.

It does not edit execution, host, routes, web UI, config manifest leaves, or
shared roadmaps. The existing config literals and accessors are already
landed. This unit adds no collection realization or value lookup.

### Public predicates

Add these pure public functions in `seon.render.value`, each with a concrete
function schema and table/generative tests:

- `safe-nonnegative-int?`: `number?`, `js/Number.isSafeInteger`, and `>= 0`;
- `safe-positive-int?`: the same predicate with `pos?`;
- `drill-path-segment?`: nil, boolean, finite number excluding negative zero,
  string, keyword, or symbol; and
- bounded deep validators for sampled trees, explanation values, and ordinary
  error data, after the ruling assigns their exact function-boundary use.

`drill-path-segment?` must not reuse the current private
`drillable-map-key?` unchanged. That helper also applies the display
`max-string` bound, while the route path's independent bound is encoded bytes
plus segment count. The shared scalar predicate owns types and numeric
identity only; the sampler may conjunct it with its display-string limit.
Negative zero must use `js/Object.is`, not division as a second local idiom.

Every predicate that walks hostile nested data must carry an explicit visit,
depth, string, and collection budget derived from the effective limits or from
the already-produced bounded skeleton. Calling
`db.protocol/ordinary-wire-value?` first would recursively walk an untrusted
value without a cap and would defeat the admission law. Marker maps must reject
unknown keys; non-drillable indexes must be ascending, distinct, non-negative,
and in range; explanation/error data must be ordinary eager data and bounded
before crossing Transit.

### Registered pure-data shapes

Register the pure-data portions in `seon.render.value` exactly once:

- `::path-segment`, `::path`, `::offset`, and `::page-size` after correcting
  the predicate representation;
- `::operation-limits` and `::effective-limits` as closed maps;
- `::drill-request` as a closed map;
- `::schema-status`, `::schema-status-row`, `::schema-statuses`, and
  `::explanation`;
- `::drilled-projection`;
- `::availability`, `::recompute?`, `::error-value`;
- `::available-result`, `::unavailable-result`, `::failed-result`, and
  `::drill-result`.

Do not preserve `render-html-data`'s current public `:map` return once the
producer is migrated; its function output becomes the named projection or
result shape. Until Unit 1F constructs the new projection, Unit 1E may register
the shapes and test candidate compilation without falsely changing the old
producer contract.

The normalizer needs one map-in contract. The ruling says it accepts a closed
request but does not name that shape. Resolve this explicitly rather than
using positional arguments or an inline duplicate. Recommended owner:
`seon.render.value` registers `::limit-normalization-request` with required
`:seon.config/configuration :seon.config/singleton` and optional
`::operation-limits ::operation-limits`; the config function references that
schema by keyword. If the owner declines the extra named request, the ruling
must instead authorize a fully named positional contract. There must not be an
anonymous duplicate of `::operation-limits` in `seon.config`.

### Normalizer

`seon.config/effective-value-drill-limits` is one pure map-in/map-out public
function. It reads host maxima only through:

- `value-max-path-segments`;
- `value-max-path-bytes`;
- `value-max-realized-items`; and
- `value-max-items` for the default page size.

For each supplied operation value it returns `min(operation, host)`; absence
returns the host value. The output keys are exactly the three config cap keys
plus `:seon.render.value/page-size`. It neither performs checked offset
addition nor adds the sentinel; parent and child admission own checked
`offset + page-size <= realized-max`, and paging alone may touch the one extra
sentinel. It must not silently clamp page size to the realized-work cap unless
the ruling is changed: those are independent policies, and an inconsistent
operator configuration should make the request refuse rather than alter it.

Parent normalization and child re-normalization become byte-identical by
passing the parent's effective map as the child's subordinate operation map.
This proves monotonic idempotence:

```text
normalize(child-host, normalize(parent-host, operation))
= min(child-host, parent-effective) per field
```

Equality with the parent requires the child host policy to be the same or
narrower only where the parent result already satisfies it. A blanket test
claiming equality under a strictly narrower child policy would be false; that
case must refuse before lookup rather than pretend the limits are unchanged.

### Acceptance tests

The focused unit must prove:

1. the complete candidate schema population builds; every registered drill
   form is pure readable EDN, closed where specified, contains no `:any`,
   `[:maybe ...]`, inline function object, or unresolved application predicate;
2. unknown keys and stored nil fail every request, limit, projection,
   explanation, error, and result map;
3. safe integers accept `0`, `1`, and `Number.MAX_SAFE_INTEGER` at the correct
   boundary and reject negatives, fractions, infinities, NaN, unsafe integers,
   and negative zero; positive page size additionally rejects zero;
4. path segments accept the six frozen scalar families and reject tags,
   UUIDs, instants, characters, collections, records, host values, non-finite
   numbers, and negative zero;
5. absent operation limits equal singleton limits; each smaller value narrows;
   each larger value clamps; fields act independently; repeated normalization
   is byte-identical; same-policy parent/child normalization is byte-identical;
6. a narrower child policy produces its narrower effective map and is refused
   by the later frame-consistency check, rather than being asserted equal;
7. bounded deep validators reject every unknown marker key and malformed index
   vector without walking beyond their budgets; poison placed one element past
   each budget remains untouched; and
8. all three result branches round-trip through the existing Transit codec
   once Unit 1F supplies representative bounded projections.

Tests 7-8 may remain pending behind the predicate-placement correction and
Unit 1F producer. They must not be replaced by output-size assertions.

## Unit 1F: descent and paging projection

Keep descent/paging separate from Unit 1E. It changes the raw-value work path,
must reuse `sample` without a second walk, and needs different falsifiers:

- map descent uses exact admitted scalar keys;
- vector descent additionally requires a safe non-negative in-range index;
- sequences and sets are pageable but never positionally descendable;
- sequence/set paging touches at most `offset + page-size + 1`, retains at
  most `page-size`, and sets `more?` only from the sentinel;
- maps refuse `offset > 0` before source entry access; and
- schema validation/explanation runs only for a complete slice.

The roadmapped map ruling is sufficient to forbid map tail paging, but one
wording conflict remains. `ec86accb` says both that arbitrary maps are
non-pageable and that insertion-equivalent maps produce byte-identical pages.
Refusing `offset > 0` does not make the first bounded candidate window
insertion-independent for every possible map implementation. The existing
sampler guarantees repeated bytes for the same stable immutable value and
bounded work; it cannot sort an arbitrary million-entry map without violating
the work law. Unit 1F must either scope byte identity to repeated sampling of
the same concrete value/iteration order, or refuse any map whose complete
entry set does not fit the bounded window. It cannot honestly claim arbitrary
insertion-equivalent identity plus bounded partial-map work.

## Ordered handoff

1. Correct the three custom-predicate registrations and name the normalizer
   request shape in the frozen ruling/roadmap.
2. Land Unit 1E schemas, public predicates that are mechanically valid, and
   the effective-limit normalizer with candidate-compilation and clamp proof.
3. Land Unit 1F descent/paging/projection after the first-page map identity
   wording is ruled.
4. Only then add execution frames, route parsing/authorization, and the UI.

This split freezes transport data before consumers while keeping the first
source-realization change in its own work-bounded proof unit.
