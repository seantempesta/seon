---
type: issue
status: open
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
[start-arms-and-wakes-2026-09-16](../../prds/steward-platform/research/start-arms-and-wakes-2026-09-16.md).
