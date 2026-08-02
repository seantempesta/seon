---
type: issue
status: open
severity: friction
tags: [issue, schema, config, flow]
---

# Replace recurring anonymous runtime contracts with named predicates

## Problem

Public runtime functions again use `:any` for values whose concrete predicates
are immediately required. This reopens a contract class an archived repair
claimed to have removed.

## Evidence

- `src/seon/config.cljc:257-277` contracts the database argument as `:any` and
  immediately passes it to `d/pull`; `:seon.db/database-value` is registered.
- `src/seon/flow.clj:83-111` contracts the step Var as `:any` and then
  hand-validates `var?`.
- `docs/seon/issues/archive/database-and-transaction-boundaries-use-anonymous-any-contracts.md`
  records the previous class and its intended removal.

## Owner

The shared named predicate schemas for database values and Vars, with honest
generators where Malli needs one.

## Acceptance

Both public contracts name their actual accepted shapes; generated values pass
the predicates, and invalid inputs fail at the contract rather than a second
hand check.
