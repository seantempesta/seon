---
type: issue
status: open
severity: blocker
tags: [issue, agent, pod]
---

# Share one driver for an open agent run

## Problem

Message wake delivery, committed-work replay, and hot-reload reconciliation can
all discover the same open run. The database compare-and-swap ensures that only
one run opens, but both local callers can still enter its turn loop. The second
turn then reaches the same execution child while its first invocation is
active.

## Evidence

The repaired restart drive opened the pending run for `solid-worms-punch` and
then immediately closed it after `run-turn!` received `The agent already has an
active invocation.` The execution host is intentionally single-invocation per
agent; the agent loop had no corresponding single-driver rule.

## Owner

`seon.agent.loop` owns every entry into `run-loop!` from wakes and committed
work reconciliation.

## Acceptance

- Concurrent local discoveries of the same agent/run share one loop promise.
- A later run waits for the prior local loop to finish rather than overlapping
  the execution child.
- Focused tests prove two callers invoke `run-loop!` once.
- Restart and a real inbound message complete without an active-invocation
  refusal.
