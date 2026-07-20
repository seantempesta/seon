---
type: issue
status: open
severity: blocker
tags: [issue, agent, database]
---

# Put the database value in every transcript query member

## Problem

The transcript supplies a database value only at the `execute-many` envelope.
The writer plans every grouped member independently and requires each query's
own database source. Transcript rendering therefore fails before any query
runs.

## Evidence

The real agent prompt showed transcript rendering failed with `Every grouped
remote read requires a database source` at member position zero.
`seon.agent.ctx.transcript/query-member` placed only query inputs in protocol
arguments, unlike the maintained execution query-member that leads with the
database value.

## Owner

`seon.agent.ctx.transcript/acquire-transcript` owns both grouped read stages and
their query members.

## Acceptance

- Every grouped transcript query leads its arguments with the same immutable
  database value used by the envelope.
- Focused tests inspect every member in both stages.
- The real transcript renders rather than emitting an acquisition error.
