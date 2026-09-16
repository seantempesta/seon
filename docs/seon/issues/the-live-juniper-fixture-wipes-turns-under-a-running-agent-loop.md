---
type: issue
status: open
severity: friction
created: 2026-09-17
tags: [issue, test, turn, fixture, class/p1]
---

# The live Juniper fixture wipes turns under a running agent loop

## Problem

`seon.context-blocks-fixture/clear-history!`
(`test/seon/context_blocks_fixture.clj:288-301`) retracts every turn and
evaluation of agent `juniper` at the writer:

```clojure
(mapv #(vector :db.fn/retractEntity %) (concat evaluations turns))
```

Its docstring says it runs "after the fixture agent and earlier arm wakes are
idle", but it establishes no idleness: it calls `agent/disarm!` and then
wipes, while `install-running!` (`:303`) deliberately leaves the agent's
ordinary flow graph running. Nothing bounds the wait for the loop to reach a
closed turn, and nothing refuses the wipe when a turn is open — unlike
`seon.turn/compact-call` (`src/seon/turn.clj:2312-2340`), which refuses
`::compaction-refused` for exactly this reason.

## Evidence

Live `default` (pid 30138), Juniper fixture seeded 2026-09-16T15:32:40Z:

| `:t` | instant | what |
|---|---|---|
| 536870999 | 15:33:00Z | turn `3f81dfc4c014` OPENED for juniper (`:seon.turn.work/situation :call`) |
| 536871000 | 15:33:01Z | 933 retractions, 1 assertion: all four juniper turns, their 20 evaluations, 18 read-evidence and 135 read-request entities |

Retracting a turn also retracts its incoming `:seon.runtime/turns` component
datom (`reference-code/datahike/src/datahike/db/transaction.cljc:997-1012`),
so the agent's runtime turn set went with it.

The consequence, one transaction later: the loop's terminal writer met
`:seon.turn/no-such-run` closing `3f81dfc4c014`
(`data/clusters/default/logs/seon.log`, three `:datahike/write-rejected` at
15:33:01.267/.468/.521). The loop is now total against that
(`seon.turn/open-run-tx-call`, landed with this note's sibling research
`docs/prds/steward-platform/research/terminal-refusal-settlement-2026-09-17.md`),
so this no longer produces a core fault — but the fixture still races the
running loop, and a wipe that lands mid-turn leaves the seeded scenario in a
state nobody asked for.

## Acceptance

`clear-history!` establishes the agent's idleness through bounded terminal
events before it wipes (AGENTS.md §2.3: the bound is part of the seam, and a
bound firing is a bug report naming what never arrived), or decides at the
writer the way `compact-call` does. No sleeps, and no weakening of the
one-open-turn fence.

Related: `virtual-loop-fixture-submission-can-race-an-armed-turn.md` — the
same "fixture submits into a running loop without establishing its turn
lifecycle" class.
