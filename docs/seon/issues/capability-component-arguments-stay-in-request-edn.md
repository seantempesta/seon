---
type: issue
status: open
severity: cleanup
tags: [schema, database, effect]
---

# A capability's nested map arguments stay in `:seon.effect/request-edn`

## What

`seon.effect/declared-datoms` records every request and result key this branch
holds as an installed attribute, and leaves everything else in the canonical
EDN and the blob. Map-valued keys are always left behind: `:my.fs/content`,
`:my.fs/precondition` and `:my.edit/form` are declared map shapes, not
component refs, so "which fence did this write carry" is still a substring of
`:seon.effect/request-edn` rather than a datom.

## Why it is not simply declared

`:my.fs/precondition` is `:my.fs/write-precondition`, an `[:and [:map …] [:fn
seon.fs/write-precondition?]]`: the predicate is what refuses a write carrying
both fences or neither. Redeclaring the attribute as
`[:and {:seon.db/component true} :seon.db/ref]` to make it storable would
delete that validation from the request contract, and declaring a second
`:seon.effect/…` spelling of the same argument is the per-capability mapping
the derived writer exists to remove.

## Options for the owner

1. Teach the schema bridge to store a map-shaped attribute marked
   `:seon.db/component true` as a component entity, keeping the Malli shape as
   the component's own validation. One bridge change; every capability gains
   its nested arguments as facts with no per-capability code.
2. Leave nested arguments in the EDN. Cheapest; "which fence" stays unqueryable.

Option 1 is the one that matches §2.2 of the effects research
(`docs/prds/steward-platform/research/effects-and-write-back-2026-09-16.md`).
