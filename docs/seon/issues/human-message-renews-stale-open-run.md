---
type: issue
status: open
severity: blocker
tags: [issue, agent, flow, pod]
---

# Human messages renew stale open runs instead of superseding them

## Problem

A retained branch can resume an inherited open root run. When a new human
message arrives, the running wake path only renews that run's lease. Work from
the prior task can therefore continue appending turns after the new instruction
instead of starting the ordinary message-caused run.

## Acceptance

- A human message closes the currently owned run as `:superseded` and opens a
  message-caused run with that message as `:seon.agent.run/cause`.
- Agent-to-agent messages continue renewing an open run.
- Run fencing prevents the superseded run from committing later work.
- A live reused-root task follows the new human instruction rather than its
  inherited plan.

## Evidence

The coordinated reuse/repair diagnostic kept the watcher, writer, and isolated
branch stable through two 90-second phases. Root queried three agents from its
previous plan and never called `agent/delegate!`. The retained eval sequence
showed the stale trajectory continued after `/agents/run` had committed the new
human message. The focused loop regression proves the replacement run sequence;
live task-priority proof remains.
