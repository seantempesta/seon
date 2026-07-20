---
type: issue
status: resolved
severity: blocker
tags: [issue, database, schema, flow]
---

# Preserve every invalid Datahike schema update difference

## Problem

`datahike.schema/find-invalid-schema-updates` can forget a detected schema
difference and allow an incompatible installed attribute update.

## Evidence

At Datahike `d21abadb9412f1b828b02ddb3c08ddc81d57c595`, the `reduce-kv` step
returns `nil` when the current entry is unchanged or is an allowed update,
instead of returning its accumulator. A direct call changing
`:db/valueType` from string to long while retaining the same ident and
cardinality returned `nil`. An isolated `:memory` transaction then changed the
installed value type successfully. Seon's current CLJS divergence gate masks
this for its local path, but the authority-side schema seam cannot delegate
this invariant to Datahike until the reducer is fixed.

## Owner

`reference-code/datahike/src/datahike/schema.cljc` owns schema-update
comparison; its transaction tests own ordering-independent coverage.

## Acceptance

Every permutation of a schema entity's map order reports the same invalid
value-type/cardinality/uniqueness differences. A transaction attempting one of
those incompatible changes fails without changing the database or advancing
its transaction coordinate, while explicitly allowed changes remain allowed.

## Resolution

Datahike commit `670cd1ad` makes every comparison branch preserve the reducer
accumulator. Only an incompatible difference adds an entry; unchanged and
explicitly mutable entries leave earlier differences intact.

## Evidence

The regression places an incompatible value-type change before, between, and
after unchanged or mutable entries. The transaction form that previously
passed now throws without changing the installed value type or `:max-tx`.
Focused persistent-set, hitchhiker-tree, and spec-instrumented runs each pass
one test with six assertions.
