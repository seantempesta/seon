---
type: issue
status: resolved
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

## Resolution

Commit `71f3cb0e0` gives the existing scan one process-local set of in-flight
message IDs. A message ID is admitted before its virtual thread starts and is
removed in `finally`; Datahike's agent-run CAS remains the only cross-process
authority. The same commit removes
`:seon.agent.run/lease-until` from the interest patterns, closing the
self-written wake loop without adding durable derived state.

The recurring controlled measurement in
`test/seon/agent/driver_test.clj` runs `N = 1, 5, 10, 25`. Every rung now makes
exactly **1.0 run-open transaction call per useful run** and records **zero
same-process losing CAS outcomes**.

The conditioned real-agent baseline at commits through `ad33c2268` had five
successful database commits per useful run because its plan transaction was
broken. Including failed run-open CAS calls, writer transaction calls per
useful run were **5.0 / 7.4 / 7.9 / 7.48** at
`N = 1 / 5 / 10 / 25`; losing CAS counts were **0 / 12 / 29 / 62**. Failed CAS
calls are not mislabeled as commits.

Baseline conditions: MacBook Pro `Mac17,6`, Apple M5 Max, 18 cores, 128 GiB,
macOS 26.5.2 build `25F84`, OpenJDK 26.0.1 arm64, G1, `-Xmx4096m`, AppCDS,
application digest
`596b6c1d43bd76cbf925ea288bc402d3c393cdab9fc9bc06e3309c0e91a3ca0a`,
named cluster `agentload0726`, and DeepSeek `deepseek-v4-flash`.

After conditions: the same machine and OS, OpenJDK 26.0.1 arm64,
`-Xmx512m`, revision `71f3cb0e0`; the deterministic regression ran at all four
rungs. The later replacement proof in `3946b7192` starts with a dead process,
uses a fresh process-local in-flight set, and resumes the committed plan after
the exact lease instant, proving process loss cannot strand the marker.

The full post-fix 25-agent paid-model climb was not repeated during the shared
artifact freeze. The recurring driver regression is runner-owned, and direct
invocation ran as part of the 10-test / 57-assertion driver result with zero
failures or errors.
