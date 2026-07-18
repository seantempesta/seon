---
type: issue
status: resolved
severity: bug
tags: [issue, database]
---

# Execute-many omitted selected database value

## Problem

`seon.db/execute-many` selected one immutable database value for all members but
returned only the member results. Startup consumers already relied on the
selected database value to fence a following transaction, so LLM configuration
seeding supplied an explicit nil `:seon.db/expected-db` and instrumentation
rejected the request.

## Resolution

Successful `execute-many` results now contain both `:seon.db/db` and
`:seon.db/results`. This exposes the exact database value already selected for
the batch, allowing a following transaction to use the same basis transaction
without another read.

## Evidence

The complete ClojureScript gate passes 1,098 tests and 4,880 assertions,
including the remote database contract and LLM configuration synchronization.
