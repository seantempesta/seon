---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database, pod]
---

# Accept Datahike query relations in the stale-run watchdog

## Problem

The first live ticker pass after a clean start failed before checking stale
runs. Datahike returned the empty Datalog relation `#{}`, while
`seon.agent.run/stale-run-ids` declared its input as Malli `:sequential` and
rejected the set. The error recorder then called the instrumented config
accessor with no inherited config singleton and failed while reporting the
original fault.

## Resolution

The watchdog input schema now names Datahike's actual query result—a set of
tuples. Its pure function retains deterministic sorted vector output. The core
error dial accepts nil during early boot and cross-cutting process callbacks,
where its established default remains `:gate`. Error output names the recorded
Proximum basis transaction.

## Evidence

- The live retained error named `seon.agent.run/stale-run-ids`, value `#{}`,
  expected schema `[:sequential :seon.agent.run/stale-run-row]`.
- A direct live `close-stale-runs!` probe now returns
  `{:seon.agent.run/closed []}`.
- Focused `seon.agent.run-test` passes 9 tests/47 assertions and
  `seon.error-record-test` passes 15 tests/74 assertions.

## Acceptance

- Empty and populated Datahike query relations pass the pure watchdog schema.
- A ticker pass with no stale runs returns normally.
- Recording a core fault without an inherited config value uses `:gate` and
  does not create a second Malli failure.
