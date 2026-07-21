---
type: issue
status: resolved
severity: blocker
tags: [issue, schema, rendering]
---

# Give schema browser matching one activated bounded owner

## Problem

`seon.schema` derives an activated entity catalog, but it has no general
activated-projection API for structural schema candidates or validated schema
matches. The only reusable compiled validators and explainers read the mutable
candidate declarations. A browser consumer must therefore either leak
unactivated declarations into rendering, copy matching logic, or reuse the
entity-only presence matcher that does not perform Malli validation.

The missing diagnostic bound is also observable work, not merely output size.
A common required attribute can address hundreds of schemas, so collecting all
of them and retaining only a short result would still make one render scale
with the registry population.

## Evidence

- `src/seon/schema.cljc` publishes `:seon.schema.projection/catalog` only for
  entity maps and exposes `candidate-validator` / `candidate-explainer` only
  against the mutable candidate registry.
- `src/seon/render.cljs` privately selects an entity shape by required-key
  presence and never validates the value against that shape.
- [[../../prds/source-cleanup/research/activated-schema-projection-boundary-2026-07-20]]
  requires activated-only matching, projection-object cache rotation,
  deterministic ambiguity, and bounded near-match work before the universal
  browser can consume schema results.

## Resolution

Commit `284cbabf` derives all required-key rows and the inverse shape index in
the immutable projection. `candidate-shapes` stops after 32 instrumented index
visits; `matching-shapes` independently validates every full-key possibility,
so the diagnostic cap cannot discard a valid ambiguous match.

One projection-object generation cache owns validators and explainers.
`explain-shape` returns nil for valid input, explanation data for invalid input,
and a structured core-defect exception for a key that is not an activated map
shape. Candidate registration and restoration do not invalidate this cache;
activating a distinct projection object does, even when its 32-bit fingerprint
is equal.

The focused `seon.schema-test` gate passed 16 tests / 123 assertions. Its
400-schema common-key fixture recorded exactly 32 candidate visits, and the
projection-identity, candidate-isolation, open-map type, ambiguity, metadata,
nil-explanation, and unknown-shape falsifiers all passed. Compilation reported
one pre-existing `my.blob/crypto` warning outside this owner.

## Owner

`src/seon/schema.cljc` owns the single immutable projection, its derived shape
index, projection-scoped validator generation, and the public
`candidate-shapes` / `matching-shapes` queries. No renderer, database call, or
second schema registry belongs in this fix.

## Acceptance

- Candidate-only registration and restoration cannot change browser matching
  until a distinct projection object activates.
- An equal-fingerprint replacement projection rotates compiled validators by
  object identity.
- All valid ambiguous matches are returned in deterministic specificity order;
  wrong-typed and missing-required-key values cannot become matches.
- Diagnostic candidates retain plausible missing-key shapes, return at most 32
  rows, and instrumented candidate visits remain at or below 32 even when one
  required attribute indexes hundreds of schemas.
- Non-entity render properties and entity identity metadata survive in derived
  rows, while no-overlap values return an empty vector.
