---
type: issue
status: open
severity: blocker
tags: [issue, agent, database]
---

# Keep the committed-work limit separate from its result budget

## Problem

The committed-work query asks for one inbound message with Datalog `:limit 1`
and also sets Datahike's resource `max-results` to one. The latter accounts for
retained query-result nodes; it is not the query's semantic row limit. A real
query can therefore exceed the resource budget before returning its one row.

## Evidence

After a clean system restart, the pod found the committed user message for
`solid-worms-punch`, but `acquire-committed-work` rejected the second member of
its batched read with `datahike query-results budget exceeded`. The query is
already ordered and carries `:limit 1`; only its resource allowance was one.
Datahike's `resource/charge-result!` charges each retained result node.

## Owner

`seon.agent.loop/acquire-committed-work` owns the batched agent pull and
pending-inbound query.

## Acceptance

- The query continues to return at most one ordered inbound message.
- Its bounded resource allowance is independent of that semantic limit.
- A focused test asserts both constraints.
- Restart recovery opens and completes the already-committed agent run.
