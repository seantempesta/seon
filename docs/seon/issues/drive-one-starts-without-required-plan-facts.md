---
type: issue
status: open
severity: friction
tags: [issue, agent, database, wave/live-drive-context]
---

# Start Drive 1 with the required real plan facts

## Problem

The live-drive specification requires a task against a real `my.plan` with
structured `:about` refs, but the preserved Drive 1 agent has no plan items.
The #11 omission frequency and first-use plan demonstration therefore have no
subject even if the generated opening is repaired.

## Evidence

At observed basis `t=536871061`, a query joining
`:my.plan.item/agent` to `drive-one-agent` returned zero items. The only
objective message was:

```text
Define a durable contracted function named largest that returns the row with
the greatest :example/amount, or {} for empty input. Call it once, query its
stored :seon.fn/spec, then complete with a short reply naming what you built
and its contract.
```

It names neither `my.plan` nor an existing subject, and no structured
`:my.plan.item/about` fact exists for the agent.

## Owner

The Drive 1 setup transaction that creates the agent, task message, and
pre-drive plan state.

## Acceptance

Before the objective run opens, the preserved database contains the specified
real plan item(s) owned by the drive agent with resolvable `:about` refs. The
report can derive explicit versus omitted-about frequency from those facts.
