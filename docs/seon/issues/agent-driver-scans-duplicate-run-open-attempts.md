---
type: issue
status: open
severity: friction
tags: [issue, agent, runtime, database]
---

# Coalesce duplicate run-open attempts in the JVM driver

## Problem

Every database-interest scan starts a new virtual thread for every pending
message. A later transaction report can trigger another scan before the first
thread's run-opening transaction becomes visible, so several threads race to
open the same message and all but one lose the agent-run CAS.

The CAS preserves correctness, but the duplicate requests consume writer queue,
allocation, logging, and wall time precisely when concurrent agents are
supposed to scale.

## Evidence

The named-cluster real-agent climb on 2026-07-26 first exposed the race at
`N=5`: 12 losing run-open transactions. It grew to 29 at `N=10` and 62 at
`N=25` (the latter between `05:54:34.948Z` and `05:54:45.664Z`). The errors
name the same inbound message and agent with several generated run IDs.

`seon.agent.driver/start!` serializes scan enumeration with `scanning?`, then
starts one virtual thread per row (`src/seon/agent/driver.clj:495-522`). It
does not record in-flight message IDs between scans. `pending-messages`
excludes a message only after a successful run-cause datom is committed
(`src/seon/agent/driver.clj:281-299`), leaving the pre-commit race.

Conditioned load evidence is in
[[../../prds/sci-execution-runtime/research/measurements-2026-07-25#17-named-cluster-real-agent-load-and-turn-waterfall]].

## Owner

`seon.agent.driver` owns database-interest scheduling and run admission.
Datahike's CAS remains the authority; the driver should avoid submitting work
it already has in flight without inventing durable duplicate state.

## Acceptance

- Concurrent database reports for one pending message submit at most one
  run-opening transaction while that message is in flight.
- A losing CAS caused by a genuinely competing process remains an ordinary
  fenced outcome.
- The 25-agent rung completes with no same-process duplicate run-open errors,
  and a host-kill/recovery drill proves an in-flight marker cannot strand work.
