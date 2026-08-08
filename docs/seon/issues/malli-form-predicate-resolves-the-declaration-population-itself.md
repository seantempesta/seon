---
type: issue
status: open
severity: friction
tags: [issue, schema, malli, performance, isolation]
---

# `seon.schema/malli-form?` resolves the declaration population itself

## Problem

`seon.schema/malli-form?` (`src/seon/schema.clj:722-740`) builds a
`candidate-registry` on every call, which resolves the declaration population.
With no projection, projection state, or candidate overlay supplied, that is a
complete classpath re-read and re-merge — 152 resource reads, ~15 ms — per
call.

It cannot be given the population as an argument. It is a REGISTERED CORE
PREDICATE (`register-core-predicate! 'seon.schema/malli-form?`), so Malli
calls it with the candidate value and nothing else. Every population-taking
arity added elsewhere stops at this boundary.

## Evidence

Found 2026-08-07 on a live cluster while repairing the per-item callers
([research](../../prds/sci-execution-runtime/research/declaration-population-per-item-2026-08-07.md)).
`seon.config`'s initialization admission still cost **82,992 resource reads /
6,495 ms** after its own population was threaded. The stack sample at the read
seam:

```
seon.schema$candidate_registry (schema.clj:675)
seon.schema$malli_form_QMARK_  (schema.clj:722)
  … malli internals …
seon.schema.datahike$malli__GT_datahike_attr_in (datahike.clj:220)
seon.schema.datahike$storable_attribute_in_QMARK_ (datahike.clj:274)
```

One population per attribute derived, 546 attributes. Note that
`malli->datahike-attr-in` and `storable-attribute-in?` are explicit-projection
`-in` functions doing everything right; the resolution escapes underneath them
through the predicate.

## Present containment

`seon.config/admit-initialization` supplies its already-resolved population
for the extent of admission through `schema/call-with-forms` — 82,992 reads
and 6,495 ms become 0 and 11.6 ms, with an identical result. That is a
containment at ONE caller, not a repair: any other operation that reaches this
predicate without supplying a population pays the same cost silently.

## Owner

`seon.schema`, resolved by the environment. Under the
[seon.env PRD](../../prds/sci-execution-runtime/plan/seon-env-prd-2026-08-07.md)
a predicate that needs the declarations reads
`:seon.schema/projection` from the environment it is running in, rather than
falling through to the classpath. This issue is a concrete Phase 1/Phase 3
acceptance case: when the environment carries the projection, the
`call-with-forms` containment above is deleted and the cost cannot return.

## Acceptance criteria

- `seon.schema/malli-form?` performs no resource read when the calling
  environment supplies a projection.
- `seon.config/admit-initialization` no longer needs its `call-with-forms`
  wrapper, and deleting that wrapper leaves the measured cost unchanged.
- No process-global cache of declaration facts is introduced.
