---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, database]
---

# Historical agent existence query used scalar result budget

## Problem

`seon.agent/ensure-initial-agent!` asked whether any ordinary agent had ever
been born, but allowed one Datahike query-result node because the final find
shape was scalar. Datahike charges intermediate relation rows before applying
the final scalar result. An existing database with several agents therefore
returned `datahike query-results budget exceeded` and skipped startup
reconciliation.

The client compounded the failure by checking only an explicit false
`:seon.db/ok?`; an ordinary `:seon.error/message` value did not fail startup.

## Resolution

The bounded historical query now admits 4,096 intermediate result nodes while
retaining its small result-weight limit. Autonomous startup treats either a
database error value or an explicit failed response as a core initialization
failure. Focused multi-agent proof passes 7 tests/51 assertions and client
initialization proof passes 9/29, including both accepted error shapes.

## Acceptance

- A populated database reconciles every missing agent namespace ref.
- An initialization database error prevents publication and hosting.
- Repeated initialization is convergent and writes nothing after repair.
