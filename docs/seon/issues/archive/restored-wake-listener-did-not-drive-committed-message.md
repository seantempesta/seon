---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database]
---

# Restored wake listener did not drive committed message

## Problem

When a physical database session closed after an inbound message committed,
the restored message listener received Datahike's resynchronization event. Its
handler only inspected transaction-report datoms, so it ignored that event and
did not discover the durable message. The agent remained idle until a restart
or manual drive.

## Resolution

The one wake listener routes a resynchronization event to the existing
`drive-run!` reconciliation owner. Ordinary datom events retain the existing
message-specific fast path. No event queue, message replay, or second driver
was added.

## Evidence

Focused loop proof invokes the restored listener with Datahike's native event
and asserts that it drives the same agent input exactly once. The public agent
repeat is recorded in the database-authority roadmap.
