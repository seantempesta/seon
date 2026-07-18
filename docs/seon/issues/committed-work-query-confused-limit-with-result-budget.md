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
Raising the allowance from one to 64 only postponed the same failure: on the
populated default database, a clean restart on 2026-07-18 logged this error for
every resumed agent, including root, before any committed work could run.

After the resource-budget repair, a live restart exposed the next part of the
same selection contract. Message `ss2aycwqs99i` committed for
`tricky-terms-shine`; planned quiescence closed its just-opened run as
`:quiesced`. The replacement pod resumed the agent as idle, but the committed
work query treated that infrastructure close as completed coverage. The agent
retained only the original human message and produced no reply.

## Owner

`seon.agent.loop/acquire-committed-work` owns the batched agent pull and
pending-inbound query.

## Acceptance

- The query continues to return at most one ordered inbound message.
- Its bounded resource allowance is independent of that semantic limit.
- A focused test asserts both constraints.
- Restart recovery opens and completes the already-committed agent run.
- A `:quiesced` infrastructure close does not claim that its inbound message
  was completed; an ordinary terminal run close still covers prior messages.
