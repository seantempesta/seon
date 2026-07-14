---
type: issue
status: open
severity: friction
tags: [issue, agent, database]
---

# Address-message steps have no explicit queue priority

## Problem

An open step linked to a newly accepted human message can sort behind older
ready plan leaves, so the plan anchor and `next` queue can direct the agent to
prior work before the message it was just asked to address.

## Evidence

`seon.agent.message/message!` atomically mints the linked open step with
`:my.plan/message`, but `my.plan.internal/ready-leaves` sorts every ready step
only by `:my.plan/created-at`, oldest first. Neither the query nor the sort
considers the message connection.

The plan-preload pilot also observed the converse timing failure in all three
scenarios: when the address step was the only ready step before plan authoring,
it captured the active position. Together these cases show that creation time
is standing in for two different semantics: address the accepted message and
advance existing planned work.

## Owner

The one derived work-queue ordering in `my.plan.internal/ready-leaves` and its
message-linked step facts from `seon.agent.message`.

## Acceptance

- With no explicit active step, a newly accepted human message's linked open
  step is selected ahead of older ready leaves until that message is addressed.
- An explicitly active step remains the position anchor.
- Behavioral tests cover older and newer authored leaves around the linked
  message step; ordering derives from facts and does not add a stored priority
  mirror or a second queue.
