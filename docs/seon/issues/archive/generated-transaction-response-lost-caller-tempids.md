---
type: issue
status: resolved
tags:
  - database
  - transaction
  - generated-ids
---

# Generated transaction response lost caller tempids

## Failure

A generated-ID transaction committed the correct entities and relationships,
but its immediate response returned an empty `:tempids` map for caller tempids.
The generated-ID allocator may replace caller tempids internally; Datahike's
raw report therefore cannot by itself preserve the public mapping.

## Resolution

The writer already stores caller tempid mappings as reserved same-transaction
receipt facts so ambiguous-delivery recovery can reconstruct them. Immediate
transaction reports now merge those durable receipt mappings with Datahike's
ordinary public tempids after filtering writer-private tempids. First delivery
and idempotent recovery consequently use the same committed authority.

## Evidence

- `seon.db.generated-id-transaction-test`: generated relationships, caller
  tempids, retry recovery, conflicts, and concurrency.
