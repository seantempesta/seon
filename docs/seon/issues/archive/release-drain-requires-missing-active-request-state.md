---
type: issue
status: resolved
severity: friction
tags: [issue, database, flow]
---

# Make release draining valid for direct writer runtimes

## Problem

`seon.db.writer/drain-database-scope!` unconditionally locked
`::writer/active-requests`, but the supported direct `handle-request` runtime
does not own that request-server state. Releasing a database therefore failed
before Datahike could close the connection.

## Evidence

`bin/test-writer seon.db.request-receipt-test` failed with a null lock in
`drain-database-scope!`. The failed run left its test-runner process and JVM
alive for more than seven hours because the blocked embedding provider was
never released.

## Owner

`seon.db.writer/drain-database-scope!` owns the distinction between optional
request-server state and the executor scope that every runtime must drain.

## Acceptance

- A direct writer runtime can release and re-ensure its database while old
  embedding work is still blocked.
- The old connection generation cannot install its late derived value.
- Final release retains no connection, executor job identity, scope fence, or
  queued/running work.

## Resolution

Commit `4ca4884d` drains active callback requests only when the runtime owns the
request-server table, while always fencing and draining an available executor.
The regression proves immediate removal of the released scope's dispatcher
identity and fence, rejection of the old generation's late embedding, and a
resource-zero final release. The focused receipt gate passes 7 tests and 50
assertions.
