---
type: issue
status: superseded
severity: blocker
tags: [issue, agent, runtime, database, architecture]
---

# Commit authored corpus facts with the terminal receipt

## Problem

The surviving claim-native driver does not commit the function, namespace, and
schema facts produced by an authored form. The capability disappeared when
`seon.host.record/tee-tx-data` was deleted with the old guarded door.

## Evidence

Commit `8dc8623ad5053d90f34e84803638735937778715` deleted
`src/seon/host/record.clj`, including `tee-tx-data`, rather than porting the old
recording implementation. Git history is the reference for the removed owner.

## Owner

`seon.agent.driver` owns the step's terminal transaction. Design the
replacement fresh as durable facts: the step's terminal transaction includes
the authored function, namespace, and schema transaction data next to its
receipt. No eval-internal side channel or second transaction path survives.

## Acceptance

- A successful authored definition commits its terminal receipt and its
  `:seon.fn`, `:seon.ns`, and applicable `:seon.schema` facts in one
  transaction.
- Querying the transaction proves the receipt and corpus facts share the same
  transaction id.
- Failure returns and records the existing flat error value without leaving a
  running receipt or a partial corpus publication.
- `rg 'seon\.host\.record|tee-tx-data' src test` remains empty.

## Resolution

Superseded by the fresh-tree split in f25e34594: the cited State A owner is quarry or deleted, and the current B2/N3/N4 ledgers do not carry this defect forward.
