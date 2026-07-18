---
type: issue
status: open
severity: blocker
tags: [issue, agent, database]
---

# Give message participant pulls a real result allowance

## Problem

Message intake uses semantic result counts as Datahike `max-results` resource
budgets. Datahike charges retained result nodes, not only root entities or the
single scalar aggregate, so resolving the user and one agent or finding the
human-message barrier can exceed those tiny budgets before the message
transaction is constructed.

## Evidence

After clean hot-reload and restart proof, a real `POST /agents/run` for
`solid-worms-punch` returned `Message database acquisition failed` before
opening a run. `seon.agent.message.internal/pull-many-member` uses
`(count refs)` while its selector pulls `:db/id`, `:seon.user/id`, and
`:seon.agent/id`. This is the same source-grounded Datahike result-accounting
mistake previously repaired in agent birth.

The participant allowance then passed in the live REPL, exposing the adjacent
human-message barrier query: its scalar `(max ?at)` request also set the
resource budget to one and failed with `datahike query-results budget exceeded`.

## Owner

`seon.agent.message.internal/acquire-send-data` owns the bounded participant
pull and human-message barrier query.

## Acceptance

- Participant pulls use a bounded result allowance independent of root ref
  count while retaining their shallow result-weight bound.
- The scalar barrier query keeps scalar result semantics with independent,
  bounded retained-node headroom.
- Focused message tests assert the authority request and its existing frozen
  database-value reuse.
- A real user message opens and completes an agent run, with its transaction,
  turn, eval, and reply observable from one final database value.
