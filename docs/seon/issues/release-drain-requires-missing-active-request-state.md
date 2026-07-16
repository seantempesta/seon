---
type: issue
status: open
severity: friction
tags: [issue, database, flow]
---

# Make release draining valid for direct writer runtimes

## Problem

`seon.db.writer/drain-database-scope!` unconditionally locks
`::writer/active-requests`, but the supported direct `handle-request` runtime
used by receipt tests does not contain that server-owned state. Releasing a
database therefore fails before Datahike can close the connection.

## Evidence

On 2026-07-16, `bin/test-writer seon.db.request-receipt-test` failed alone with
three failures and one error. The release path threw `NullPointerException:
Cannot enter synchronized block ... lock ... is null` at
`drain-database-scope!`; re-ensure then failed and a later `d/db` dereference
encountered a null future. The gate ran 7 tests and 44 assertions instead of
its previously recorded 45 green assertions.

## Owner

The runtime-state contract shared by `seon.db.writer/drain-database-scope!`,
direct `handle-request` callers, and `seon.db.request-receipt-test/runtime`.

## Acceptance

- A direct writer runtime can release and re-ensure its database while old
  embedding work is still blocked.
- The old connection generation cannot install its late derived value.
- `bin/test-writer seon.db.request-receipt-test` passes all 7 tests without a
  null lock or connection future.
