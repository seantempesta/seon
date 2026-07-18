---
type: issue
status: open
severity: blocker
tags: [issue, agent, database]
---

# Give message participant pulls a real result allowance

## Problem

Message intake sets Datahike's `max-results` to the number of sender and
recipient refs. Datahike charges retained pull-result nodes, not only root
entities, so resolving the user and one agent can exceed a limit of two before
the message transaction is constructed.

## Evidence

After clean hot-reload and restart proof, a real `POST /agents/run` for
`solid-worms-punch` returned `Message database acquisition failed` before
opening a run. `seon.agent.message.internal/pull-many-member` uses
`(count refs)` while its selector pulls `:db/id`, `:seon.user/id`, and
`:seon.agent/id`. This is the same source-grounded Datahike result-accounting
mistake previously repaired in agent birth.

## Owner

`seon.agent.message.internal/acquire-send-data` owns the bounded participant
pull and human-message barrier query.

## Acceptance

- Participant pulls use a bounded result allowance independent of root ref
  count while retaining their shallow result-weight bound.
- Focused message tests assert the authority request and its existing frozen
  database-value reuse.
- A real user message opens and completes an agent run, with its transaction,
  turn, eval, and reply observable from one final database value.
