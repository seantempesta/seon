---
type: issue
status: open
severity: cleanup
tags: [issue, schema, architecture]
---

# Derive repeated state vocabularies from their owning schemas

## Problem

Two fresh derived-state paths repeat finite vocabularies by hand instead of
referencing or computing from their existing owner.

## Evidence

- `resources/seon/schema/block.edn:48` owns the render-band schema, but
  `resources/seon/schema/context.edn:80-81` repeats the same five enum members for
  `:seon.context.contribution/band`. The contribution is explicitly evidence
  of the block band, so this is one constraint with two authorities.
- `resources/seon/schema/work.edn:34-36` owns all derived form states, while
  `src/seon/cluster/work.cljc:292-293` separately lists the three settled
  states and line 327 uses that list to derive `settled?`. The same `cond` at
  lines 312-320 already knows which evidence makes a form settled.

Both values are in-memory derivations rather than stored status facts; the
defect is duplicated classification authority, not stored-derived state.

## Owner

The render-band schema reference and `seon.cluster.work/form-settlement`
derivation.

## Acceptance

Context contributions reference the one render-band schema. Form settlement
computes `state` and `settled?` from one shared derivation rather than a
second literal state set. A new band/state requires one edit and the existing
generative contracts cover the result.

## Scope note 2026-07-29

The bounded docstring/comment hygiene lane did not alter these owners.
Replacing the context enum with the render-band schema reference and deriving
`settled?` from `form-settlement` both require code-form edits; the lane
explicitly prohibited code-form changes. This issue remains open for that
behavior-preserving source change.
