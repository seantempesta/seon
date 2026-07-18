---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, flow]
---

# Retry concurrent agent births at the latest database value

## Problem

Concurrent `start!` and `delegate!` calls acquire the same database value, but
each makes only one guarded transaction attempt. The JVM writer accepts the
first birth and correctly rejects the others as stale, causing independent
agent launches to fail instead of reacquiring the latest database value.

## Evidence

- Three simultaneous public `POST /agents/run` requests on 2026-07-17 acquired
  the same database value.
- One agent completed its task; the other two returned HTTP 422 with
  `{"error":"The database changed before commit."}`.
- `seon.agent/start!` and `seon.agent/delegate!` each call `spawn-child!` once,
  while the writer returns the typed
  `:seon.db.protocol.error/stale-database-value` failure expected for this
  serialization conflict.

## Owner

The shared child-birth transition in `seon.agent`. It must preserve the
expected-database guard and reacquire every database-derived input before a
bounded retry; no queue or second birth path belongs here.

## Acceptance

- A focused test proves stale birth attempts reacquire the latest database
  value and eventually host exactly one committed child.
- Concurrent public root-agent launches all commit and complete without an
  HTTP conflict response.
- `delegate!` rebuilds its initial message transaction from the newly acquired
  database value on every retry, so child birth and first task remain atomic.

## Resolution

Commit `3780d0b1` added one bounded retry owner shared by `start!` and
`delegate!`. The focused test forces two stale transactions before success.
After a clean rebuild, three simultaneous public launches all returned HTTP
200 and committed distinct children.
