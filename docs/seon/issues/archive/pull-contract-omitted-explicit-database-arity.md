---
type: issue
status: resolved
tags:
  - database
  - schema
  - runtime
severity: friction
tags: [issue]
---

# Pull contract omitted the explicit database arity

## Failure

Cold-start wrapper verification found that `seon.db/pull` implements the
Datomic-style `(pull db selector entity-id)` arity but its Malli function
contract described only the map request and current-database positional forms.

## Resolution

The public contract now includes the explicit immutable database value arity.
The implementation and interface remain unchanged; committed-program wrapper
verification now covers all three callable forms.
