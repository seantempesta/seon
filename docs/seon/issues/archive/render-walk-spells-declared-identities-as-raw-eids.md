---
type: issue
status: resolved
severity: blocker
tags: [issue, render, database, context]
---

# Spell every identifiable render-walk entity with its lookup ref

## Problem

`seon.render.walk/entity-lookup` recognized only `:seon.ns/name`. Every other
entity reached through a forward, reverse, or derived connection was therefore
addressed by its process-local numeric eid even when its declared entity shape
carried an identity attribute.

## Evidence

The 2026-08-11 fresh-cluster distance-2 root walk returned 114 raw-eid lookup
units out of 132. The source at `src/seon/render/walk.clj` contained one
literal `:seon.ns/name` special case and otherwise returned `eid`.

The class regression in `test/seon/render/walk_test.clj` seeds both a reverse
ref from an entity carrying `:seon.cluster.run/id` and a reverse ref from an
entity carrying no identity. Before the repair, the identified run was numeric;
after the repair it is `[:seon.cluster.run/id "render-walk-run"]`, while only
the identityless entity remains numeric.

## Owner

`seon.render.walk/entity-lookup`, using the active exact-basis schema
projection's `:seon.entity/id-attr` declarations.

## Acceptance

- The lookup rule contains no identity-attribute hand list.
- Every visited entity carrying an attribute enumerated by
  `:seon.entity/id-attr` is addressed as `[attribute value]`.
- Entities carrying no declared identity remain numeric and are counted by the
  live proof.
- A fresh distance-2 root-agent walk contains zero numeric lookups for entities
  that do carry a declared identity.

## Resolution

Resolved by `ee255347b`. `entity-lookup` now derives its candidate identity
attributes from the exact-basis projection's `:seon.entity/id-attr`
declarations and selects only an attribute the pulled entity actually carries.
The fresh-cluster distance-2 root walk returned zero numeric lookups; its
identityless raw-eid residue was also zero. The regression separately proves
that a deliberately identityless reverse-ref entity remains numeric.
