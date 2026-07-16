---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow]
---

# Historical DB secondary-index lifetime is unowned

## Failure

Every attached `commit-as-db` call reconstructs a DB through `stored->db`.
That function restores configured secondary-index instances, including
`java.io.Closeable` native owners. Connection release closes the secondary
indices on the live connection DB only; Datahike originally exposed no matching
release operation for an independently returned historical DB value.

Seon's coordinate-pinned historical query, pull, and pull-many path could
therefore open secondary resources without closing them. Calling
`commit-as-db` once per execute-many member would multiply both the cost and the
leak.

## Evidence

- `reference-code/datahike/src/datahike/versioning.cljc` reconstructs retained
  commits through `stored->db`.
- `reference-code/datahike/src/datahike/writing.cljc` creates or restores each
  configured secondary index.
- [[execute-many-value-reuse-2026-07-16]] measures the repeated wrapper cost and
  defines one request-lifetime immutable-value owner.

## Acceptance

- The Datahike fork has one explicit, transport-free lifetime operation for a
  historical materialized DB and an option that omits secondary owners only for
  proven primary-index-only work.
- Proximum and another closeable test index prove exactly one acquire and close.
- Head reads never close the connection-owned secondary index.
- Cancellation, failure, final connection release, and reconnect do not cross
  into a replacement generation.
- Repeated historical reads return resource evidence to baseline.

## Resolution

Datahike now exposes idempotent `release-materialized-db` and can construct a
historical value without secondary owners for proven primary-only work. It
closes only owners restored for that detached value and never the live
connection value. The focused dependency proof passes 24 tests and 198
assertions. Seon's read owner retains one historical value for the complete
request and releases it after physical work ends; arbitrary Datalog remains
secondary-capable because the planner may select a configured index.
