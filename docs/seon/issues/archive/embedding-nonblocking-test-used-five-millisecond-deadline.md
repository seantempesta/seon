---
type: issue
status: resolved
severity: medium
tags: [issue, database]
---

# Embedding nonblocking test used a five-millisecond deadline

## Problem

The transaction/embedding regression used a five-millisecond Future timeout as
its proof that background provider work did not block a committed transaction.
After mutation completion moved to Datahike's nonblocking core.async `take!`, a
cold scheduler could miss that window even though the provider remained
correctly independent and the same test passed on immediate rerun.

## Resolution

The test retains its behavioral fence—the embedding provider stays blocked
until every primary transaction response is observed—but gives the independent
commit up to one second to cross ordinary test scheduling. End-to-end mutation
latency remains measured performance evidence, not a correctness timeout.
