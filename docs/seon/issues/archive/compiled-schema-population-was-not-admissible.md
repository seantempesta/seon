---
type: issue
status: resolved
severity: blocker
tags: [issue, database, schema]
---

# Compiled schema population was not admissible

## Problem

The compiled schema projection referenced `:seon.db/error` without registering
it and registered `:seon.schema/explanation` as a top-level nilable value. A
fresh authority could not accept the complete packaged schema population.

## Resolution

`seon.db` now owns the referenced error map and the persisted explanation form
is a map. Optionality remains at the function-result boundary, not in stored
database values.

## Evidence

The real two-child process proof transacts the compiled schema projection into
a fresh in-memory Datahike database before either child starts. Both children
then acquire and activate that same database program.

## Owner

`seon.db` owns the shared error form and `seon.schema` owns the explanation
form.

## Acceptance

- Every persisted schema reference resolves in the complete projection.
- `:seon.schema/explanation` rejects stored nil.
- Fresh program admission reaches both real execution children.
