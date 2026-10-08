---
title: "Card AE-5 The call cap is anchored on the assignment's grant"
type: reference
status: active
created: 2026-10-08
---

# AE-5 The call cap is anchored on the assignment's grant

Index: [README-agent-entity](README-agent-entity.md). Answers RESPEC R-8 (Q-AE2 recommended option assumed); amends
RT-13 and S6-05, retires W-4's anchor. Lane: Opus (the cap is a paid-call bound).

## What becomes true
- `assign!`/`assign-tx` mints `:seon.task/grant` (UUID) on each assignment it writes.
- Every model eval request's call row records the grant of the assignment it worked (`:seon.turn/grant`, a value,
  indexed; beside CX-13's assignment key).
- `seon.agent/record` answers `:seon.agent/calls-left` = the cap (record settings, else the cluster dial) − the count
  of call rows on the agent's branch carrying the current grant. `turns-left`, `opening-deferred?` and the block read
  it.
- A message, a task revision or a schedule firing never refills it; re-assignment after `:capped` (RT-13) does.

## Functions
- CHANGE `seon.task.edn` (`:grant`), the call row schema, `seon.task/assign!`, the recording writer in `seon.turn`,
  `seon.turn/turns-left`, `opening-deferred?`, `seon.agent/record`.
- DELETE `seon.turn/outside-wake-t`, `spent-runs`, `episode-runs`'s anchor.

## Proof
`seon.turn-work-test/the-turn-bound-is-turns-since-the-latest-outside-wake` asserts the deleted anchor: rename it
`…/the-call-cap-counts-calls-on-the-grant` and convert its rows in this card.
`seon.turn-work-test/a-capped-task-resumes-when-reassigned` (RT-13's) and
`seon.turn-work-test/a-message-from-root-does-not-refill-the-cap` (new): with the cap at 1 and one model call
spent, root's message leaves `calls-left` 0; root's re-assignment makes it 1. Through `seon.mcp/call`, no provider
(the model call is the harness's recorded attempt).

## Cost
One `:avet` count per record read, O(calls on the grant); removes the O(wakes) `outside-wake-t` walk per pass.

## Dependencies
AE-4, CX-13. Commit with `--test seon.turn-work-test --test seon.turn-work-cost-test`.
