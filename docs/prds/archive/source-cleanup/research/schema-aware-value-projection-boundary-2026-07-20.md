---
type: research
status: complete
tags: [research, schema, web, architecture]
---

# Schema-aware value projection boundary (2026-07-20)

## Decision

After the activated schema projection freezes, Unit 1B is the smallest
dependency-ready consumer: enrich the existing bounded
`seon.render.value/render-html-data` data projection with schema labels,
validation status, and invalid-only explanation data.

This unit owns exactly:

- `src/seon/render/value.cljs`; and
- `test/seon/render/value_test.cljs`.

It does not render badges or hover UI, select a custom renderer, migrate a
generic fallback or plan, add a web route, read the database, or address an
execution child. Those consumers remain ordered behind this plain-data
contract.

## Dependency ledger

| Dependency or mechanism | Selected revision and source | Contract consumed by Unit 1B |
|---|---|---|
| Activated schema projection | Unit 1A in `src/seon/schema.cljc` and `test/seon/schema_test.cljs`; handoff in [[activated-schema-projection-boundary-2026-07-20]] | Ordered `candidate-shapes` and validated `matching-shapes` derive only from the activated projection. Projection-scoped explainers use that projection's registry. Candidate validators, candidate explainers, and the projection fingerprint are not browser authorities. |
| Malli | `80138076960e7820523b4cb932c5b5d1936d4e7f` (`0.20.0`) | `reference-code/malli/src/malli/core.cljc:353-361` establishes schema-local caching; `:2582-2603` properties and children; `:2660-2666` explainer reuse. `reference-code/malli/src/malli/error.cljc:344-403` establishes `humanize` and `error-value`. Spell checking must not be promised for Seon's open maps. |
| Bounded value sampler | Unit 0 commit `d42a88de`; `src/seon/render/value.cljs:359-488` | `sample` produces the one bounded skeleton. Existing `truncated?` recognizes every partial-view marker. Schema confirmation must consume that result rather than inspect or size the raw value independently. |
| HTML value data contract | `src/seon/render/value.cljs:834-864` and `test/seon/render/value_test.cljs:493-505` | `render-html-data` already returns `:seon.render.value/eval-id`, `:summary`, `:truncated?`, and `:tree`. Unit 1B extends this map without changing the public arguments or replacing its tree. |
| Data-oriented render law | `src/seon/render/AGENTS.md` and `docs/seon/architecture/ui.md` | The projection is immutable derived data. It is never stored, and no parallel renderer, cache, or dispatch registry is introduced. |

Reitit, Datastar, Datahike, Orchard paging, and execution-child IPC are not
dependencies of this unit. Their later consumers use this projection without
widening its owner.

## Public input and output contract

The public call remains:

```clojure
(render-html-data configuration eval-id value)
```

The existing output keys and meanings remain unchanged. Unit 1B adds:

```clojure
{:seon.render.value/schemas
 [{:seon.schema/key schema-key
   :seon.schema/entity? entity?
   :seon.render.value/status status}]

 :seon.render.value/explanation
 {:seon.render.value/humanized humanized
  :seon.render.value/error-value error-value}}
```

`:seon.render.value/status` is one of `:valid`, `:invalid`, or
`:shape-only`. The schema rows retain the activated projection's deterministic
order: required-attribute count descending, then schema key alphabetically.
Renderer symbols are not exposed or invoked by this projection unit.

The output laws are:

- complete with one or more matches: `:schemas` contains every ordered match,
  each with `:valid`;
- complete with no match but one or more candidates: `:schemas` contains only
  the first ordered candidate with `:invalid`;
- incomplete with candidates: `:schemas` contains every bounded ordered
  candidate with `:shape-only`;
- no candidate: `:schemas` is an empty vector, which is an ordinary generic
  browser state; and
- `:explanation` is present only for the complete-invalid primary candidate.
  It is absent for valid, shape-only, and no-schema values, never present with
  nil fields.

The invalid explanation uses the activated projection's explainer for the
primary candidate, followed by Malli's `humanize` and `error-value`. It runs
on the complete top value or complete drilled slice that entered this call.
It is not computed over an elided raw parent and is not made safe by trimming
afterward.

The existing `:seon.render.value/truncated?` is the public completeness fact.
Unit 1B does not add a redundant `complete?` complement. A complete projection
has `truncated?` false; every other case is incomplete.

## Strict completeness and work bounds

The strict gate uses the existing `truncated?` marker vocabulary in full. Any
of the following makes the projection incomplete and forbids validation or
explanation:

- a sequence `:seon.render.value/elided` tail;
- map `:seon.render.value/elided-keys`;
- projected map keys;
- a `:seon.render.value/pruned` node;
- an opaque or datom projection; or
- a clipped string or named scalar marker.

This is intentionally stricter than validating values whose skeleton has only
avoided an elision or prune marker. It preserves the current meaning of
`truncated?`, avoids a second partiality vocabulary, and never confirms a
value whose rendered evidence is incomplete.

`render-html-data` samples exactly once. Its returned `:tree` is that exact
skeleton, `:truncated?` is derived from it, and schema work follows from the
same result. It must not:

- call `sample` again;
- walk the raw value to count nodes or estimate tokens;
- call `pr-str` on the raw value as a size gate;
- validate or explain an incomplete value; or
- add a value-level result memo.

For incomplete values, only `candidate-shapes` may run. Its Unit 1A contract is
bounded by the activated required-attribute index; returning every candidate
means every candidate within that declared bound, never an unbounded registry
or raw-map scan.

## Ambiguity and precedence rules

Multiple open schemas may validly match the same map. Valid composition is
first-class, so every ordered valid match ships as a badge row. Structural
near-matches do not ship beside successful matches: that would turn ordinary
open-map overlap into a misleading field of red diagnostics.

When a complete value has no valid match, the first ordered candidate is the
sole diagnostic authority. Only it receives `:invalid`, and only its explainer
runs. Remaining near-matches stay internal to candidate discovery.

When confirmation is unsafe because the skeleton is incomplete, every bounded
ordered candidate ships as `:shape-only`. No candidate is privileged as an
invalid diagnosis because the missing evidence can change which schema is the
best complete match.

## Shortest falsifiers

1. A complete value satisfying two open schemas emits both `:valid` rows in
   required-count-descending, schema-key-alphabetical order.
2. A complete wrong-type or missing-required-key near-match emits only the
   first candidate as `:invalid` and includes both humanized and error-value
   explanation data.
3. A complete value with a valid match and several invalid near-matches emits
   only the valid match rows.
4. An incomplete value with several candidates emits every bounded candidate
   as `:shape-only`, carries no explanation, and validator/explainer spies are
   never called.
5. Each existing partial marker independently forces `:shape-only`, including
   clipped strings, opaque/datom markers, projected keys, elision, and prune.
6. A value with no schema candidate preserves the generic tree, emits an empty
   `:schemas` vector, and carries no explanation.
7. `render-html-data` calls `sample` exactly once and returns the same skeleton
   under `:tree`; existing summary, eval-id, and truncated contracts remain
   compatible.
8. A huge counted or uncounted map, poisoned lazy tail, opaque printer, and
   huge key preserve Unit 0's bounded work and byte-stable output.
9. Activate projection P1, mutate candidate declarations without activation,
   and prove Unit 1B still reports P1. Activate P2 and prove the next render
   reports P2.
10. An invalid explanation is absent after the value becomes valid, proving
    omission derives from current input rather than stored render state.

## Deferred consumers

The following units consume this contract only after Unit 1B freezes:

- `src/seon/render.cljs` and its tests render the primary status dot, remaining
  valid badges, and zero-round-trip hover explanation;
- the same render owner replaces its private entity matcher with activated
  `matching-shapes` and later adds explicit schema-property dispatch;
- the plan owner registers render properties and deletes its bespoke tree;
- generic fallback and eval cards migrate through the one bounded value
  projection;
- `/data` projects authority-owned entities through this contract;
- the web owner adds the read-only value route and authorization checks; and
- the execution/eval owner implements bounded child-local result sampling and
  honest retired-child results.

No deferred consumer may reopen Unit 1B by adding a second status pipeline,
raw-value size probe, explanation endpoint, stored badge state, or renderer
registry.
