---
type: research
status: complete
tags: [research, rendering, config, testing]
---

# Value-drill projection and budget implementation readiness (2026-07-20)

## Verdict

The first two post-Unit-1B units are implementation-ready in dependency order:

1. repair projected map-key drillability in `seon.render.value`; then
2. register and resolve the three value-drill cluster-policy caps in
   `seon.config`.

They are deliberately narrower than the later drill request, paging, child
transport, route, and UI units. The projection repair freezes one honest fact
about the already-bounded skeleton. The configuration unit freezes three
hard maxima and their accessors. Neither unit may invent the still-unfrozen
drilled projection or transport request shape.

The current worktree contains an active Unit 1B edit to
`src/seon/render/value.cljs`. That owner must commit and release both renderer
files before the projection repair starts. Configuration files do not overlap
Unit 1B, but dependency order still freezes the Unit 1B projection and then the
drillability repair before the cap unit is declared integrated with rendering.

No serious underspecification blocks these two narrow units. One serious
underspecification remains downstream: the exact public drill request and
drilled projection schemas, including how path, offset, effective limits,
schema status, and honest unavailable/error values are represented, are not
yet frozen. The execution-child report explicitly leaves that shape to the
generic-rendering owner. Therefore the config unit may add pure cap accessors
and a clamp over an explicitly supplied operation-limit map only if that map's
schema is frozen in the same owner; otherwise the normalizer waits for the
request/projection unit. Transport, route, and UI work must not infer it from
the examples in research prose.

## Dependency ledger

| Dependency or mechanism | Selected source | Contract consumed |
|---|---|---|
| Unit 0 bounded sampler | `d42a88de`; corrected schema input bound `882b2083`; current `src/seon/render/value.cljs` | The map branch inspects one bounded candidate window plus a sentinel, ranks only that window, recursively samples only visited candidates, and emits one bounded skeleton. The repair cannot add a second source-map walk. |
| Unit 1A activated schema projection | `284cbabf`, `882b2083`, `55a811bd`; `src/seon/schema.cljc` and its focused tests | Candidate and match work is already bounded and deterministic. Drill metadata is a strict completeness marker consumed by Unit 1B; it does not create another schema path. |
| Unit 1B projection | [[schema-aware-value-projection-boundary-2026-07-20]] (`817a821f`) and [[schema-aware-value-projection-implementation-readiness-2026-07-20]] (`aa441dfe`) | `render-html-data` samples exactly once and uses the same skeleton for generic output and schema status. Any non-drillable retained key makes the skeleton incomplete and therefore `:shape-only`. The active Unit 1B owner must land first. |
| Projected-key ruling | [[projected-map-key-drill-boundary-2026-07-20]] (`ddf2b5c2`) | Keep ordered `[display-key sampled-value]` pairs; replace aggregate `projected-keys` with ascending output-local `non-drillable-key-indexes`; do not ship unsafe originals or add tokens/registries. |
| Path codec ruling | [[value-route-path-codec-boundary-2026-07-20]] (`c932c9e1`) | A retained map key is drillable only when it remains the original value and is nil, boolean, finite non-negative-zero number, bounded string, keyword, or symbol with exact EDN and Transit lookup equality. Vector indexes are a separate non-negative-safe-integer descent rule. |
| Drill-budget ruling | [[value-drill-budget-config-boundary-2026-07-20]] (`3d5943db`) | Three positive singleton caps are independent. Parent and child later repeat admission before lookup or realization. Page size remains `value-max-items`; total work is not page size. |
| Numeric default ruling | [[value-drill-cap-default-ruling-2026-07-20]] (`38f24f39`) | Defaults are 32 decoded path segments, 4096 raw percent-encoded UTF-8 bytes, and `offset + page-size <= 1024`, with at most 1025 touches including the honest tail sentinel. |
| Existing config mechanism | `src/seon/config.cljs`, `config/system.edn`, `test/seon/config_test.cljs` | Register each leaf once as `:seon.config/cap`, reference it from the closed render section and singleton, flatten it in `resolve-config-singleton`, and provide a pure accessor whose absent-config fallback is byte-identical to the shipped manifest. |
| Orchard paging evidence | `reference-code/orchard/src/orchard/inspect.clj:44-141` at `c462a25d97988f1af51e8181265c43ec9b7d3d6f` | `drop` followed by `take (inc page-size)` establishes the truthful head-plus-one sentinel. Orchard supplies no total-offset ceiling and is not a second inspector implementation. |
| Malli 0.20.0 | `reference-code/malli/src/malli/core.cljc:1223-1310,2635-2641` at `80138076960e` | Closed maps reject unknown keys; positive-int leaves are registered schemas, not repeated hand validation. |
| Aero 1.1.6 | `reference-code/aero/src/aero/core.cljc:63-70,100-102,258-275,414-431` at `c47a10fa5f6a52084d04769af06d5e04d6603e13` | The selected manifest resolves values once before schema validation and database reconciliation. Runtime drill code never rereads Aero or environment variables. |

## Unit 1C: projected map-key drillability repair

### Exact owner

This unit owns only:

- `src/seon/render/value.cljs`; and
- `test/seon/render/value_test.cljs`.

It starts only after Unit 1B commits and releases both paths. It does not edit
config, execution, host, route, web, or generic interaction code.

### Frozen skeleton contract

`map-key-projection` must return a bounded display value plus whether the
original key is a legal drill path component. For retained entries, the final
marker is:

```clojure
{:seon.render.value/map-entries
 [[display-key sampled-value] ...]
 :seon.render.value/non-drillable-key-indexes [0 3]}
```

Indexes refer to positions in the final retained `map-entries` vector after
candidate ranking and retention. They are ascending vectors, never sets or
source/candidate positions. Empty means omission of the metadata field.

For an index absent from the vector, `display-key` is the original map key and
passes the closed scalar codec: nil, boolean, finite number excluding negative
zero, bounded string, bounded keyword, or bounded symbol. Numeric map keys keep
their exact numeric value, including negative and fractional values when the
codec round trip preserves equality. A collection, marker map, clipped scalar,
record, host object, non-finite number, negative zero, or any replaced display
label is non-drillable.

The sampler must not retain a second copy of a non-drillable original key. For
drillable entries the displayed key already is the original key. For
non-drillable entries the original may remain only in invocation-local
candidate data long enough to derive the boolean; it never enters the returned
skeleton, text, HTML model, EDN path, Transit frame, token registry, or mutable
lookup table. A display-only key also makes every descendant non-addressable;
the later UI derives that disabled subtree from the index fact.

Delete `:seon.render.value/projected-keys` and its aggregate prose in the same
unit. `truncated?`, text emission, and Unit 1B completeness derive the existing
"shown safely" meaning from the non-empty index vector. Repeated sampling of
the same value and budget must produce byte-identical entries and metadata.

### Work-bound acceptance

The focused tests must assert work, not output size:

1. A logical million-entry counted and uncounted map visits no more than the
   sampler's configured candidate window plus one sentinel and recursively
   touches only visited candidate values. Deriving drillability indexes adds
   no source-map revisit.
2. Candidate discard and reordering prove indexes name final retained
   positions. Insertion-equivalent maps produce identical bytes and identical
   ascending vectors.
3. For every unmarked retained entry, appending the displayed/original key and
   applying `get-in` to the original map returns the corresponding raw child.
   Every marked entry exposes no path component.
4. A one-megabyte string key and a hostile record/opaque key never invoke the
   arbitrary printer, never enter the skeleton or Transit-ready projection,
   and are represented only by bounded display markers.
5. A manually submitted projected marker is rejected by the strict path
   predicate before lookup; this pure predicate proof belongs here only if the
   predicate is owned in `render.value`. HTTP and child-send zero-work proof
   remains with their later owners.
6. A non-empty index vector forces Unit 1B `:shape-only` output with matching,
   validator, and explainer counters at zero. Empty/absent metadata preserves
   complete-value schema behavior.

The issue `projected-map-keys-are-not-drill-paths` remains open after this pure
unit because its route and UI acceptance clauses still require consumer proof.

## Unit 1D: configured drill caps

### Exact owner

This unit owns only:

- `src/seon/config.cljs`;
- `config/system.edn`; and
- `test/seon/config_test.cljs`.

It starts after Unit 1C freezes the drillability vocabulary. These files are
independent of the active Unit 1B renderer edit, but landing them earlier would
make a later policy look dependency-complete while the data it governs is not.

### Frozen attributes and defaults

Register and thread these real positive-integer singleton attributes through
the existing mechanism:

| Attribute | Default | Enforcement unit |
|---|---:|---|
| `:seon.config.render/value-max-path-segments` | 32 | decoded path elements |
| `:seon.config.render/value-max-path-bytes` | 4096 | UTF-8 bytes of raw percent-encoded `path` query value before decoding |
| `:seon.config.render/value-max-realized-items` | 1024 | admitted `offset + page-size` |

Each leaf is registered once against `:seon.config/cap`, referenced from both
the closed `:seon.config/render` section and `:seon.config/singleton`, flattened
by `resolve-config-singleton`, documented in the shipped manifest, and exposed
through one public accessor with the identical literal fallback. No new config
map, environment reader, route constant, agent state, or ALS field is allowed.

Selected manifests may replace each maximum independently. A per-operation
limit may narrow but never widen the resolved singleton maximum. The normal
page size remains the resolved `value-max-items` value. With effective page
size `n`, later request admission must use checked safe-integer arithmetic:

```text
offset + n <= value-max-realized-items
items touched <= offset + n + 1
items retained <= n
```

The `+1` is an observation sentinel, not permission to retain or recursively
sample another item. These caps bound cardinality and amplification, not the
latency of realizing an arbitrary lazy element; execution deadlines and child
isolation remain the time/fault boundary.

### Configuration acceptance

The focused config gate must prove:

1. all three leaves have Datahike shapes and the render/singleton maps remain
   closed; unknown, zero, negative, and non-integer values fail;
2. absent configuration and the shipped manifest resolve the same three bytes
   of policy data, while selected-manifest overrides replace each independently;
3. operation values below the maxima narrow them and values above cannot widen
   them, if the operation-limit normalizer is frozen in this unit; and
4. boundary-minus-one, boundary, and boundary-plus-one assertions use the
   correct units rather than conflating segment count, encoded bytes, page
   size, and total realization work.

Pre-lookup rejection is a required integrated acceptance condition, not a
config-only test. Later route and child tests must instrument database
selection, host send, `lookup-result`, path descent, and realization. Oversized
encoded paths, 33 segments, unsafe/overflow offsets, and
`offset + page-size > 1024` must leave every counter at zero. A counter-bearing
infinite sequence at offset 1016 and page size 8 may retain 8 and touch at most
1025; offset 1017 must be refused with the counter still zero. The child must
repeat the same check when handed a Transit-valid over-budget frame directly.

The issue `value-drill-has-no-total-work-bounds` remains open after the config
unit. It closes only after sampler, protocol, parent-route, and independent
child work-counter proofs land.

## Ordered handoff

1. Unit 1B commits and releases `render/value.cljs` and its test with the
   single-sample schema projection proof.
2. Unit 1C replaces aggregate projected-key counts with exact output-local
   drillability indexes and proves original-key lookup, poison, printer,
   determinism, and `:shape-only` behavior in the same two files.
3. Unit 1D registers the three cap attributes and exact defaults through the
   existing manifest/singleton/accessor mechanism and proves override/clamp
   behavior in its three files.
4. The generic-rendering owner freezes the closed drill request and drilled
   projection schemas. It resolves whether the effective-limit normalizer is
   part of the config unit or this schema unit; there must be exactly one pure
   normalizer consumed by parent and child.
5. Only then may execution/host add correlated value-sample frames and repeat
   admission at the live-value boundary.
6. Route parsing/authorization consumes the frozen codec, limits, and frames;
   the UI consumes `non-drillable-key-indexes` and never reconstructs original
   keys from display labels.
7. Close both issues only after integrated pure, Transit, route, child
   retirement, and real-browser proof.

This handoff preserves one bounded projection mechanism: schema status,
drillability, paging, transport, and presentation are successive consumers of
the same sampled data rather than parallel renderers or hidden mutable state.
