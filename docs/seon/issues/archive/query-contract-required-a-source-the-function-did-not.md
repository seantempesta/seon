---
type: issue
status: resolved
tags:
  - database
  - schema
  - runtime
---

# Query contract required a source the function did not

## Failure

Cold-start wrapper verification rejected `seon.db/query` for an arity mismatch.
The live variadic function accepts a query form with zero or more source inputs,
including ordinary `(query query-form)`, while its Malli variadic branch used
`[:+ :any]` and required at least one source.

## Resolution

The variadic contract now uses `[:* :any]`, matching both the implemented
function and the established query interface. Complete committed-program
wrapper verification is the regression gate.
