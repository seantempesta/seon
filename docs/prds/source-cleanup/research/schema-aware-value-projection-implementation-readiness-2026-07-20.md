---
type: research
status: complete
tags: [research, schema, rendering, testing]
---

# Schema-aware value projection implementation readiness (2026-07-20)

## Verdict

Stage 1.5 Unit 1B is source-ready except for one serious Unit 1A handoff gap:
the ruled design requires invalid explanations compiled against the activated
projection, but Unit 1A has not named a public callable contract for obtaining
that explanation. `candidate-shapes` and `matching-shapes` are named; the
projection-scoped explainer cache is described only as a mechanism. Unit 1B
must not fall back to `explain-candidate-value`, reach into the projection's
Malli registry, or invent a renderer-local cache.

Freeze one activated-projection explanation function in Unit 1A, including its
arguments, nil/error behavior, projection-generation cache rule, and focused
tests. After that handoff lands, Unit 1B remains a two-file implementation in
`src/seon/render/value.cljs` and `test/seon/render/value_test.cljs`.

This audit is read-only with respect to source and tests. Current worktree
changes in `src/seon/schema.cljc` belong to the Unit 1A owner and were inspected
only to establish that the handoff is not yet frozen.

## Dependency ledger

| Dependency or mechanism | Selected source | Readiness consequence |
|---|---|---|
| Unit 1A activated projection | [[activated-schema-projection-boundary-2026-07-20]]; current `src/seon/schema.cljc` | `candidate-shapes` and `matching-shapes` must consume only the activated projection and return rows in required-count-descending, schema-key-alphabetical order. The diagnostic visit cap is 32. Unit 1B waits for their committed implementation and for a named activated explainer API. |
| Unit 1B ruling | [[schema-aware-value-projection-boundary-2026-07-20]] (`817a821f`) | Extend `render-html-data` in place with ordered schema rows and invalid-only explanation. Sample once; use that exact skeleton and its existing strict `truncated?` fact. |
| Unit 0 bounded sampler | `src/seon/render/value.cljs:113-130, 152-209, 242-300, 356-488`; tests at `test/seon/render/value_test.cljs:75-178, 271-310` | The existing owner already bounds map candidate traversal, sequence head-plus-one realization, projected keys, opaque handling, named scalars, and output printing. Unit 1B consumes this mechanism; it does not add a raw-value sizing walk. |
| Capped CLJS printer | `src/seon/ai/tokens.cljc:70-187`; Unit 0 `d42a88de` | `CappedWriter`, `:print-length 256`, `*print-level* 64`, and scalar `:alt-impl` prevent full expansion before clipping. Opaque values never invoke arbitrary printers. Schema code may not introduce `pr-str` over a raw value as a gate or explanation cap. |
| Malli 0.20.0 | `reference-code/malli/src/malli/core.cljc:353-361,2582-2603,2660-2666`; `reference-code/malli/src/malli/error.cljc:344-403` | Compile/reuse validation and explanation per activated projection; `humanize` and `error-value` produce the two explanation values. Spell checking is not promised for open maps. |
| Orchard paging prior art | `reference-code/orchard/src/orchard/inspect.clj:44,96-141,150-200` at `c462a25d97988f1af51e8181265c43ec9b7d3d6f` | Preserve head-plus-one truthfulness, stable paths, and honest omitted tails. Orchard is evidence, not a dependency or second inspector state machine. |
| Drill/path rulings | [[projected-map-key-drill-boundary-2026-07-20]], [[value-drill-budget-config-boundary-2026-07-20]], [[value-drill-cap-default-ruling-2026-07-20]] | Unit 1B preserves original retained keys and the existing projected-key partiality marker. Later drilling uses 32 path segments, 4096 raw encoded bytes, and `offset + n <= 1024`; those route/child mechanisms do not belong in this unit. |

## Exact owner and call boundary

The existing public call remains:

```clojure
(render-html-data configuration eval-id value)
```

The implementation boundary is the current body at
`src/seon/render/value.cljs:834-864`. It must bind exactly one result of
`sample`, derive `truncated?` from that skeleton, return that same object under
`:seon.render.value/tree`, and then select schema work:

- incomplete skeleton: call only activated `candidate-shapes`; emit every
  bounded ordered row as `:shape-only`; never validate or explain;
- complete skeleton: call activated `matching-shapes`; if matches exist, emit
  every ordered match as `:valid` and no diagnostic candidates;
- complete skeleton with no match but candidates: emit only the first ordered
  candidate as `:invalid`, and call the frozen activated explainer for that
  schema and the complete input value; and
- no candidate: emit `:schemas []` and omit `:explanation`.

Existing `:eval-id`, `:summary`, `:truncated?`, and `:tree` meanings remain
unchanged. Schema rows carry only `:seon.schema/key`,
`:seon.schema/entity?`, and `:seon.render.value/status`; renderer symbols do
not cross this boundary. The explanation map is omitted unless both
`:seon.render.value/humanized` and `:seon.render.value/error-value` are real
values for the complete-invalid primary candidate. It is never stored.

## Work-bound proof design

Output length is insufficient evidence. The focused tests must measure work.

### Traversal

Reuse the existing `CountingMap` and `UncountedMap` test fixtures rather than
constructing a million-entry persistent map in memory. For a logical
1,000,000-entry value, instrument separately:

- map entry visits;
- recursive child touches;
- calls to `sample`;
- calls to activated candidate selection;
- calls to activated matching/validation; and
- calls to the activated explainer.

The sampler's existing law is `entry visits <= max-map-visits + 1`, with only
the bounded candidate values recursively touched. Unit 1B adds the following
observable laws: `sample` is called exactly once; an incomplete skeleton calls
candidate selection once and matching/explaining zero times; a complete value
does not trigger any second raw walk for counting, token estimation, or size
gating. Populate schemas whose required attribute is common and assert the
Unit 1A instrumented candidate visits remain at or below 32, not merely that
the returned vector has at most 32 rows.

Use a poisoned value beyond the sampler window. A passing output assertion
with the poison touched is a failure; the poison must remain unrealized.

### Printer

Retain both independent printer falsifiers already established by Unit 0:

- an opaque `IPrintWithWriter` value representing a 100 MiB logical print has
  an invocation/write counter of zero, proving `opaque-marker` never enters an
  arbitrary printer; and
- an ordinary map containing a very large string goes through
  `bounded-pr-str-result`; the capped writer and scalar `:alt-impl` retain only
  the bounded prefix before printing. Test the returned character-truncated
  fact and bounded text, and instrument the writer/print seam so accepted
  writes stay within the resolved character cap rather than asserting only
  final output length.

The second assertion must be attached to the capped writer seam, not to a
post-hoc `clip-str`. A 100 MiB string already allocated as the input is
unavoidable test setup; the property under test is that rendering never
materializes a second full printed/escaped representation.

## Determinism and honest omission

The byte-identity law is same value, same activated projection object, and
same render configuration produces the same bytes. Prove it with repeated
renders and insertion-equivalent persistent maps. The sampler retains entries
in immutable map iteration order after deterministic bounded-window ranking;
schema rows independently use Unit 1A's required-count/alphabetical order.

Every partiality marker already recognized by `truncated?` must independently
force `:shape-only`: sequence `:elided`, map `:elided-keys`, projected keys,
`:pruned`, opaque/datom markers, clipped strings, and clipped named scalars.
Counted maps report the exact omitted count; uncounted maps report `:more`.
Projected keys remain visible but non-drillable, and Unit 1B must not recover
or synthesize an original key from their display marker.

## Unit 1A handoff gate

Unit 1B may start only after one coherent Unit 1A commit proves all of these:

1. `candidate-shapes` and `matching-shapes` exist with public Malli schemas and
   consume the activated projection only.
2. Candidate traversal is capped at 32 visits by instrumentation, including a
   common-key index containing hundreds of schemas.
3. Ordering is required-count descending then schema-key alphabetical, and
   every valid ambiguous match within the declared bounded candidate set is
   returned.
4. Candidate-only registration/restoration cannot affect results; activation
   of a distinct equal-fingerprint projection rotates caches by identity.
5. One public activated-projection explanation API is named and tested. It
   explains a supplied schema key/value using the same projection generation
   as matching, returns nil for a valid value, and never consults candidate
   declarations.

Item 5 is the serious underspecified contract. Its exact function name and map
shape belong to Unit 1A. This audit deliberately does not invent them.

## Focused acceptance matrix

After the handoff freezes, Unit 1B's focused gate is
`test/seon/render/value_test.cljs` and must cover:

1. ambiguous complete valid maps emit every ordered valid row;
2. complete wrong-type and missing-required-key near matches emit only the
   primary invalid row plus both explanation values;
3. valid matches suppress invalid near matches and explanation;
4. every partial marker forces ordered `:shape-only` rows with validator and
   explainer counters at zero;
5. no candidate preserves the generic tree with `:schemas []`;
6. one sample call returns the identical skeleton under `:tree`;
7. the million-entry counted and uncounted fixtures satisfy visited-work,
   poison-tail, deterministic-byte, and honest-omission laws;
8. the opaque 100 MiB logical printer remains uninvoked and the ordinary huge
   scalar print stays capped at the writer seam; and
9. P1 remains visible after unactivated candidate mutation, while activation
   of P2 changes the next render without stored status or explanation state.

No route, child IPC, hiccup, hover, badge, custom renderer, database read, or
result memo is part of this gate.
