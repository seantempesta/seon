---
type: issue
status: resolved
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

## Resolution

Resolved by `c1618e22`. The registered request, limit, projection, status,
explanation, error, and result shapes are pure readable EDN; the three scalar
predicates reuse the pre-existing raw-value boundary and carry no new inline
`:any`. The focused renderer/config gate passed 78 tests and 492 assertions,
including negative-zero, unsafe-integer, closed-map, monotone clamp,
idempotence, same-policy parent/child, and narrower-child handoff proofs. Deep
sampled-tree and error-data validators remain dependency-ordered with their
public producer/transport boundaries; the shallow schemas do not claim that
semantic admission has already occurred.
