---
type: issue
status: closed
severity: blocker
tags: [issue, agent, database]
---

# Keep the committed-work limit separate from its result budget

## Problem

The committed-work query asks for one inbound message with Datalog `:limit 1`
and also set Datahike's resource `max-results` too low. The latter accounts for
retained query-result nodes; it is not the query's semantic row limit. A real
query could therefore exceed the resource budget before returning its one row.

After that resource repair, planned quiescence exposed a second selection bug:
a run closed as `:quiesced` incorrectly claimed that its inbound message had
already completed.

## Resolution

Commit `9f752544` retains the ordered semantic limit of one and gives the query
an independent bounded result-node allowance. Commit `39238aa0` ignores
`:quiesced` infrastructure closes when determining whether a prior message is
covered, while ordinary terminal closes still cover it. Focused loop proof
passes 13 tests and 57 assertions.

A clean live repeat committed message `pvr5ygzpaznu`, immediately restarted
the system, and observed the replacement runtime select it automatically. The
agent produced exactly one reply, reached `:idle`, and retained no current run.

## Acceptance

- The query returns at most one ordered inbound message.
- Its bounded resource allowance is independent of that semantic limit.
- A `:quiesced` close does not claim completed work.
- A normal terminal close still covers prior messages.
- Restart recovery completes already-committed work exactly once.
