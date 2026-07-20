---
type: issue
status: resolved
severity: friction
tags: [issue, database, flow]
---

# Prove cross-database mutation concurrency through the writer

## Resolution

A focused regression now submits three real transactions through the complete
writer request path. The first transactions for two independent in-memory
Datahike databases enter mutation execution together, while a second
transaction for one database remains queued until its first transaction
finishes. All three commits are then read from their owning databases.

## Original problem

The bounded JVM executor had direct tests for cross-database mutation
scheduling, and the writer had integrated cross-database query tests, but no
test joined those claims at the writer-to-Datahike mutation boundary. A later
routing or work-class regression could therefore serialize every database
without contradicting either test.

## Acceptance

- Independent databases execute admitted mutations concurrently when the
  configured processor capacity permits it.
- Mutations against one database remain serialized.
- Every accepted transaction commits to only its addressed database.
