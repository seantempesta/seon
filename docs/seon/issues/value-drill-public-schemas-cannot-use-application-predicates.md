---
type: issue
status: open
tags: [rendering, schema, issue]
severity: blocker
---

# Value drill public schemas cannot use application predicates

## Problem

The ruled value-drill contract placed qualified application predicate symbols
inside registered Malli forms. Registered schema forms must round-trip as pure
EDN and compile from the complete schema population, where arbitrary
application symbols are not predicate schemas.

## Evidence

`seon.schema/register!` proves readable-EDN round trips and later compiles the
population against Malli's schema registry. The existing canvas boundary
records the same platform law: recursive application validation belongs at a
compiled public function boundary, while the registered population carries a
shallow pure-data shape.

## Owner

`seon.render.value` owns the producer-neutral public drill shapes and scalar
admission predicates. `seon.config` owns the one effective-limit normalizer
that consumes the named renderer request without a reverse namespace require.

## Acceptance

- Every registered value-drill form is readable pure EDN and compiles in the
  complete candidate population.
- No registered drill form contains `:any`, `[:maybe ...]`, an inline function
  object, or an unresolved application predicate.
- Safe integers and path scalars are checked by public pure predicates at the
  function boundaries that consume hostile input.
- Effective limits only narrow host policy and normalize idempotently.
