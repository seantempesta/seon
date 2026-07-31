---
type: issue
status: resolved
severity: friction
tags: [issue, render, datahike, architecture]
---

# The walk's reverse-ref read follows non-ref long attributes

## Problem

The old reverse query left the attribute position unbound. Because entity ids
are ordinary longs, it treated any equal `:db.type/long` value as a reverse
connection and registered an `:all` invalidation dependency.

## Owner

`src/seon/render/walk.clj`.

## Acceptance

Reverse reads name only installed ref attributes derived from entity-family
schema EDN, a non-ref long equal to the target eid never appears as a
connection, and collection truncation is explicit.

## Resolution

W1 derives every entity family's attributes, identity probe, and ref subset
from the active schema forms through `seon.schema.form` and
`seon.schema.datahike`, intersected with `(:schema db)`. Reverse reads are
concrete reverse-pull selectors grouped by those family ref subsets. The old
unbound Datalog query and wildcard entity pulls are gone.

`seon.render.walk-test/reverse-reads-never-match-equal-non-ref-longs` plants a
run-form ordinal equal to an agent eid and proves the form is not a reverse
connection. Seeded P1 and P6 properties prove membership-or-elision and loud
reverse truncation. Implementation commit: recorded in the W1 implementation
notes with the final path-limited commit.
