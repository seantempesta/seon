---
type: issue
status: archived
tags: [issue, database, agent]
---

# AI seed reported failure after a successful transaction

## Failure

A fresh default-cluster reset committed the environment-derived
`:seon.ai/config` row, but startup logged `LLM config env seed transact FAILED`
and returned `{:seon.ai/synced? false}`. The database row existed afterward.

## Cause

`seon.ai/sync!` still destructured the removed `:seon.db/ok?` wrapper from the
result of `seon.db/transact!`. The current database API returns a transaction
report on success and a `:seon/error` value on failure. The adjacent brand seed
already used that contract.

## Resolution

AI seeding now treats the absence of `:seon.error/message` as transaction
success and logs the complete error value otherwise. Its focused asynchronous
test returns a real transaction-report shape, so restoring the obsolete wrapper
would fail the regression. `seon.ai-test` passes 11 tests and 40 assertions.

Commit: `8e5986ff`.
