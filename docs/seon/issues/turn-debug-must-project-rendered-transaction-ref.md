---
type: issue
status: open
severity: blocker
tags: [issue, agent, database]
---

# Project a turn's rendered transaction ref as its basis transaction

## Problem

`:seon.agent.turn/rendered-tx` is a Datahike ref. Pull therefore returns an
entity map such as `{:db/id 536870991}`, while `seon.agent.debug/turn` promises
the stored basis transaction value. Returning the pull map violates the public
response schema and makes `/agents/run` final evidence throw.

## Evidence

The first real run to reach final evidence failed Malli output instrumentation
at `:seon.agent.turn/rendered-tx`: expected a database ref value, received
`{:db/id 536870991}`.

## Owner

`seon.agent.debug/turn` owns reconstruction of the ordinary turn-debug result
from Datahike pull output.

## Acceptance

- The function projects the pulled ref entity to its `:db/id` basis
  transaction.
- Focused tests use the real pull shape and retain the existing numeric result.
- `/agents/run` final evidence returns data instead of throwing.
