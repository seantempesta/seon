---
type: issue
status: resolved
severity: friction
tags: [issue, turn, agent, issue-family, budget]
---

# A worker's generated opening spends its issue budget

Measured 2026-09-16 while proving `seon.issue/start!`.

## Problem

`:seon.issue/budget` is declared "Provider turns for the worker; copied to its
settings overlay at start" (`resources/seon/schemas/seon.issue.edn:37`), and
`seon.turn/episode-runs` states that system turns "do not consume this bound".
The generated opening `start!` writes nevertheless does consume it.

Measured, canonical fixture, `:seon.config.ai/no-provider true`:

| budget | turns taken | `turns-left` | answered its own assignment wake |
|---|---|---|---|
| 3 | opening + one ordinary | 1 | yes |
| 1 | opening only | 0 | **no** |

So a worker started with `budget 1` has zero ordinary turns and can never
answer its assignment: `opening-deferred?` is true from its first moment,
`next-agent-work` answers nil, and the worker sits there looking healthy.
This is the absence-as-health class again — nothing reports that the budget
was spent before the worker could use it.

Either the generated opening must not count against `max-episode-runs`
(matching `episode-runs`' own docstring), or `:seon.issue/budget` must be
documented and applied as "opening plus N−1 turns". The first reading is the
declared one.

Owners: `src/seon/turn.clj` (lane attempt-and-eval-facts) and
`resources/seon/schemas/seon.issue.edn` (lane issue-family).
`test/seon/cluster/agent_arming_test.clj` uses budget 3 and says why.
Evidence:
[start-arms-and-wakes-2026-09-16](../../../prds/steward-platform/research/start-arms-and-wakes-2026-09-16.md).

## Resolution — 2026-09-16

Commit `97d1f69e0` changes `seon.turn/episode-runs` to count positive evidence:
a provider attempt, or a reply accepted after the identity transaction.
An opening with no reply and no attempt costs zero. The outside-wake reset
and failed-attempt accounting remain unchanged; no schema edit was needed.

## Owner

`src/seon/turn.clj`, `seon.turn/episode-runs`.

## Evidence and acceptance

`seon.cluster.turn-test/generated-opening-preserves-one-provider-turn-budget`
uses the canonical fixture, real generated opening, actual SCI and ordinary
turn transitions. Budget one produced zero provider calls before the fix
(run 71356, 6 pass / 4 fail / 0 error), then exactly one provider turn after
the opening (candidate run 71561 and final run 73386, each 10/0/0).
The final reply does not request completion, so the lack of a second turn
proves the budget rather than a disposition. It also checks the durable
attempt-bearing turn count, opening closure and remaining budget.

Verification boundary: hot-loaded and re-armed in default PID 95853.
Development adoption refused source changes during publication; the recorded
source remained `6aaa62a7-41e6-5707-b0a8-dfef8347fb56`. No adopted or cold-gate
claim is made. Gate request: `tmp/orchestrator/gate-requests/turn-settlement-cost.txt`.
Full measurements and the other two slice boundaries are in the
[settlement landing](../../../prds/context-generation/research/turn-bookkeeping-cost-2026-09-16.md).
The protected `test/seon/cluster/agent_arming_test.clj` still documents its
historical budget-3 workaround; its owner can now remove that obsolete comment.
