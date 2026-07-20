---
type: issue
status: resolved
severity: blocker
tags: [issue, database, flow, pod]
---

# Branch close misread remote connection identity and release result

## Evidence

A live `mesh-proof` branch used the same JVM writer as the default database
while its pod and feed ran independently. Closing it first rejected the
writer's database value because the operator compared Datahike's two-part
connection ID with the remote database value's three-part `:store-id`, whose
third element identifies the serialized writer backend.

After that comparison was corrected, retry reached branch deletion but treated
`released? false` as failure. That result is valid when the branch pod has
already released its authority session before operator cleanup. A further
retry could also replay deletion after the lifecycle record had reached
`:seon.dev.branch.phase/closed`, and ensuring the now-absent native branch
reported a generic registry conflict instead of the protocol's existing
branch-missing error.

## Resolution

The operator now derives Datahike's connection ID from the returned ordinary
database value before comparing it, accepts either release result when branch
deletion succeeds against the expected branch head, and makes a closed record
an idempotent cleanup boundary. The writer translates the exact absent-native-
branch registry conflict to the existing branch-missing protocol error.

Focused writer initialization proof passes 3 tests and 17 assertions. Focused
branch operator proof passes 5 tests and 147 assertions.

## Acceptance

- A branch database and the default database use distinct database values over
  one live JVM writer.
- A write to the branch remains absent from the default database.
- Both feeds run concurrently.
- Branch close succeeds after the pod has already released its session.
- Retrying a closed lifecycle record does not replay branch deletion.
- The branch lifecycle record and private process directory are removed while
  the default cluster remains ready.
