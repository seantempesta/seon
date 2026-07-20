---
type: research
status: complete
tags: [research, schema, web]
---

# Activated schema projection boundary (2026-07-20)

## Decision

After Stage 1.6 freezes its schema/render overlap, the first open Stage 1.5
implementation boundary is the activated schema projection. Bounded sampler
Unit 0 is already closed by `d42a88de`; this unit does not reopen rendering,
web routing, database access, or execution-child transport.

The implementation owns exactly:

- `src/seon/schema.cljc`; and
- `test/seon/schema_test.cljs`.

`src/seon/schema/internal.cljc` remains protected unless an existing form
inspection primitive proves insufficient. The current `map-shape?`,
`schema-properties`, and `map-required-attrs` primitives already cover the
planned derivation.

## Dependency ledger

| Dependency | Selected revision | Grounding | Current Seon owner or call site |
|---|---|---|---|
| Malli | `80138076960e7820523b4cb932c5b5d1936d4e7f` (`0.20.0` runtime dependency) | `reference-code/malli/src/malli/core.cljc:353-361` schema-local cache, `:2582-2603` properties and children, `:2660-2666` explainer reuse; `reference-code/malli/src/malli/error.cljc:344-403` humanized errors and spell checking | `src/seon/schema.cljc:293-375` builds the immutable projection; `:377-419` publishes and reads it; `:537-551` compiles candidate-only validators that the browser must not use |
| Orchard | `c462a25d97988f1af51e8181265c43ec9b7d3d6f` | `reference-code/orchard/src/orchard/inspect.clj:44,96-141,150-200` bounded head-plus-one paging and path descent | Prior art for later drill units; no Orchard state enters this projection |
| Reveal | `911b7b678b739f3ca19b8f95ed013a669b296c1d` | `reference-code/reveal/` inspector navigation | Prior art only; no second inspector registry or state machine |
| Datahike | `6f2569087ed31f53e751e7535ef4bf2527912046` | `reference-code/datahike/src/datahike/db.cljc:307-338` immutable database schema value; entity meaning remains attribute presence | No database call belongs in this pure projection unit |
| Reitit | `106fc4c7a09290c8e2df2d4ef9570ea1322ab2ab` | `reference-code/reitit/` | Route work is deferred |
| Datastar | `bb9ed6fbe78cf5690f5ad23a5faf86407a44982f` | `reference-code/datastar/` | Morph and hover presentation are deferred |
| Datastar Clojure | `1cef624e9e59a2ea79ffe2f65df2e7b06f8198d2` | `reference-code/datastar-clojure/` | Morph and hover presentation are deferred |

Existing first-party behavior fixes the local idiom. `build-projection` already
derives the entity catalog from canonical forms, `entity-primary-schema` in
`src/seon/render.cljs:199-224` orders by required-attribute count descending
and schema key alphabetically, and
`test/seon/schema_test.cljs:66-102` proves activation publishes the exact
projection object while candidate declarations may run ahead.

## Settled projection and API contract

`build-projection` derives one index from
`:seon.schema.projection/forms`, never from the mutable candidate registry:

- required attributes by schema key for every registered map form with at
  least one required key, whether open or closed;
- the inverse required-attribute to schema-key index; and
- derived rows carrying schema key, required attributes,
  `:seon.schema/entity?`, and authored render properties when present. Entity
  rows additionally retain their derived identity attribute.

`candidate-shapes` and `matching-shapes` are two queries over that same index:

- `candidate-shapes` returns a bounded, deterministically ordered set of
  structural near-matches. A plausible map missing a required key remains a
  diagnostic candidate.
- `matching-shapes` validates candidates against the activated projection's
  registry and returns every valid match. Failed candidates never select a
  custom renderer.

Both order rows by required-attribute count descending and schema key
alphabetically. Returning only the first match is incorrect: one ordinary
open entity map can validly satisfy several schemas, and all matches are
first-class badges and future dispatch inputs.

Projection-scoped validator and explainer compilers receive the projection
whose registry they use. One process-local generation cache compares the
cached projection with `schema/current-projection` using `identical?` and
rebuilds the whole generation on object replacement. The 32-bit projection
fingerprint is display/debug data, never cache authority. `register!` or
`restore-state!` without activation cannot change browser matching. Before
initial activation, the existing `entity-catalog` precedent permits building
one candidate projection object on demand; the first activated object replaces
that generation.

## Open-map, ambiguity, and elision constraints

Presence is only a candidate prefilter. Full Malli validation decides a match.
Malli spell checking does not diagnose extra keys on Seon's open maps, so this
unit must not promise misspelling detection. A wrong-type open map may be a
candidate but cannot be a match. A map with no indexed-key overlap has no
schema candidate, which is the ordinary no-schema state.

Full validation and explanation are legal only for a complete sampled value.
Any skeleton containing an elided or pruned marker remains `:shape-only` in
the later value projection; it must never become confirmed valid because its
bounded marker maps happen to validate. This unit should keep matching over
complete raw or drilled values and leave completeness/status production to
the later `seon.render.value` consumer. If completeness becomes an explicit
matcher input instead, its schema and refusal behavior must be pinned here
before downstream work starts.

## Shortest falsifiers

1. Activate projection P1, mutate a candidate declaration without activation,
   and prove both matching and compiled validation remain on P1.
2. Activate a distinct projection object with an equal fingerprint and prove
   the validator generation rotates by object identity.
3. Validate one map against two open schemas and require both ordered matches,
   with required-count precedence and an alphabetical tie break.
4. Give an open schema a wrong-typed value sharing its required keys: it must
   remain a candidate and disappear from matches.
5. Omit one required key from the closest shape: it must remain a bounded
   diagnostic candidate and never become a match.
6. Supply a map with no indexed-key overlap and require empty candidates, not
   an error.
7. Add hundreds of unrelated keys and schemas and prove candidate work stays
   within the declared bound.
8. Put a render property on a non-entity map and require it in the derived row;
   require entity rows to preserve their identity metadata without changing
   canonical forms.
9. Present an elided skeleton and prove this unit cannot emit confirmed-valid
   or explanation data for it.
10. Restore candidate state after an unactivated registration and prove no
    active-generation invalidation occurs.

## Deferred Stage 1.5 units

The following wait until this projection contract freezes:

- widening `seon.render` catalog consumption and replacing its private entity
  matcher;
- adding schemas, status, explanation, and completeness data to
  `seon.render.value`;
- custom-render dispatch and the plan property migration with deletion of the
  bespoke plan tree;
- generic fallback and eval-card migration;
- parent-owned `/data` entity sampling;
- child-owned eval-result sampling transport in `seon.execution` and
  `seon.eval`;
- route parsing, cross-agent authorization, and honest retired-child results;
- Datastar status, hover, and drill presentation; and
- integrated full-suite, live-cluster, feed, ownership-refusal, and child-
  retirement graduation.

Dirty execution, UDS, and web-serve paths remain protected throughout this
unit. No separately green downstream lane may build on a candidate API before
the two-file projection contract and focused schema tests freeze.
