---
type: issue
status: resolved
severity: friction
tags: [issue, schema, database]
---

# Name database-value and transaction-data contracts

## Problem

Public database transformations described database values as anonymous `:any`
and repeatedly inlined transaction-data shapes even though these are known
dependency boundaries.

## Resolution

The schema population now owns `:seon.db/database-value`,
`:seon.store/transaction-operation`, and `:seon.store/transaction-data`.
Public database transformations and transaction producers reference those
names. The final nine database arguments in `seon.cluster.run` were converted
from `:any` to `:seon.db/database-value` on 2026-07-30.

`bin/test seon.cluster.run-test seon.schema.edn-test
seon.schema.datahike-test` ran 23 tests and 101 assertions with zero failures
or errors. The broader preceding conversion ran 87 tests and 294 assertions
with zero failures or errors.

Genuinely polymorphic SCI admission values, generic render values, arbitrary
error sources, and recursive data-browser entries remain intentionally open.
