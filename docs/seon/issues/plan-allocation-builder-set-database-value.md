---
type: issue
status: open
tags: [agent, database, issue]
---

# Plan allocation builder set database value

## Problem

`my.plan` passed its immutable database value from inside generated-identity
transaction builders. The allocator owns that request field so every real
`step!`, `plan!`, or allocating `reconcile!` write was rejected before reaching
the sole writer.

## Evidence

Once execution-child instrumentation correctly injected the agent ID, live
agent `plain-chefs-do` called ordinary `my.plan/step!` and received “The
allocation builder may not set allocator-owned fields.” The allocator contract
in `seon.db.id` explicitly accepts `:seon.db/db` on the outer allocation
request, then adds it to the pure builder's transaction itself.

## Owner

`my.plan` passes the acquired database value to `seon.db.id/allocate!` and its
pure builders return only transaction data plus the expected database value.
Non-allocating plan writes keep using the ordinary explicit transaction
request.

## Acceptance

- Focused plan and allocator tests pass.
- A live `my.plan/step!` without explicit agent ID commits successfully.
- The same agent continues its existing plan and namespace task.
