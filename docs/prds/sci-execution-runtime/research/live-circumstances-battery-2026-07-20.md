---
type: research
status: active
tags: [research, agent, testing]
---

# Live-circumstances acceptance battery

The owner's goal (2026-07-20 night): no early victories — live agents
working well in a VARIETY of circumstances, then multi-agent, then
generate-code. This battery is the executable definition. Every drive
runs on the live cluster with the REAL configured provider (Muse; a 402
is recorded as an external blocker, never worked around silently), and
every claim is proven by database facts + rendered context, not logs.

## Leg 1 — single live agent, varied circumstances

| # | Circumstance | Proof |
|---|---|---|
| L1 | Fresh agent, simple task (eval + reply) | turn :done; reply message datom; wall time recorded |
| L2 | Toolkit db write (my.kb/remember) then recall NEXT turn | note datom; second turn's recall returns it (cross-turn memory) |
| L3 | Capability call (my.fs read of an allowlisted file) | envelope in eval row; gating honored |
| L4 | Error steering: task designed to provoke one wrong call | :seon/error with directive text in transcript; agent self-corrects in the SAME run |
| L5 | Restart mid-conversation: bin/seon restart between two messages | pending/second message wakes the agent post-restart; context renders prior transcript (THE FINDING TO VERIFY: pre-restart pending messages must wake — earlier probe suggests they may not) |
| L6 | Concurrent agents: 3 agents driven simultaneously | all runs close :done; writer read-spend shows 3 identities |
| L7 | Canvas: agent shows a canvas with live data | :seon.render.canvas/content datom; UI renders it |
| L8 | Budget bound: long task hits turn/form limit honestly | run closes at bound with honest status, no wedge |

## Leg 2 — multi-agent

| # | Circumstance | Proof |
|---|---|---|
| M1 | Root delegates: message! root->task agent with a subtask | task agent's turn runs; reply hops back; root's subagents block renders it |
| M2 | Agent spawns a subagent via the toolkit and consumes its result | spawn depth respected; run results section shows the child outcome |
| M3 | Hop cap: a message chain hits the cap | REFUSED with the hop-exhausted warning rendering in context |
| M4 | Two agents share database state (one writes, other reads next turn) | cross-agent visibility via ordinary queries |

## Leg 3 — generate-code

The generate-code lane's live graduation (its roadmap's checkpoint) IS
this leg: caller agent -> generate-code! -> two-namespace goal ->
ordered evaluation -> delegated failure -> evidence-derived completion.

## Standing rules

Real provider or recorded-as-blocked; one circumstance at a time; facts
over logs; every failure found becomes an issue note (fix if simple,
ledger if not); the battery re-runs green top to bottom before the goal
is called met.
