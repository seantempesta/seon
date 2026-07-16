---
type: issue
status: active
tags: [issue, database, flow]
---

# Historical DB secondary-index lifetime is unowned

## Failure

Every attached `commit-as-db` call reconstructs a DB through `stored->db`.
That function restores configured secondary-index instances, including
`java.io.Closeable` native owners. Connection release closes the secondary
indices on the live connection DB only; Datahike exposes no matching release
operation for an independently returned historical DB value.

Seon's current coordinate-pinned historical query, pull, and pull-many path can
therefore open secondary resources without closing them. Calling
`commit-as-db` once per execute-many member would multiply both the cost and the
leak.

## Evidence

- `reference-code/datahike/src/datahike/versioning.cljc:414-433` calls
  `stored->db` on every `commit-as-db`.
- `reference-code/datahike/src/datahike/writing.cljc:182-224` creates/restores
  each configured secondary index.
- `reference-code/datahike/src/datahike/connector.cljc:258-273,483-527` closes
  only the live connection DB's secondary indices at final release.
- [[execute-many-value-reuse-2026-07-16]] measures the repeated primary-wrapper
  allocation and defines the request-lifetime immutable-value owner.

## Acceptance

- The Datahike fork has one explicit, transport-free lifetime operation for a
  historical materialized DB or an equivalent operation that does not restore
  secondary owners for primary-index-only reads.
- Proximum and one other Closeable test index prove exactly one acquire and one
  close for an execute-many request over an older commit.
- Head reads never close the connection-owned secondary index.
- Cancellation, member failure, final connection release, and reconnect close
  old-generation owners without crossing into the replacement generation.
- Resource evidence returns to baseline after repeated historical reads.
