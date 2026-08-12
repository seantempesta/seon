---
type: issue
status: open
severity: blocker
tags: [issue, runtime, agent, live-drive]
---

# Say something when a run settles neither a completion nor a wait

## Problem

An agent run can close having evaluated its forms cleanly and returned
neither `my.run/complete` nor `my.run/wait`. Nothing records that the run
ended without a disposition, nothing wakes the agent again, and the
requester's message is answered by silence. The database holds the receipts
and the run's `:seon.cluster.run/closed-at`, and no fact anywhere says the
request was dropped.

For a driven episode the consequence is a wrong verdict:
`seon.eval.drive/terminal-state` (`src/seon/eval/drive.clj:262-270`) sees the
agent idle with every run-id closed, finds no completion value, and reports
`:stopped`. A run that did the requested work perfectly is graded identically
to one that did nothing.

## Evidence

Two fresh isolated roots on 2026-08-12, `deepseek-v4-flash`, thinking
disabled, one message each (the minimum-context HALF re-drive —
[research](../../prds/sci-execution-runtime/research/minimum-context-ablation-plan-2026-08-11.md)).

`tmp/ablate-half3`, run `442bd777-4dcd-4442-8141-7a0f33dc9045`, triggered by
message `inbound-536870999-0`, opened 09:57:22.397Z, closed 09:57:28.851Z
with no `:seon.cluster.run/error`. Three forms, three clean receipts:

| Ordinal | Form | Receipt |
|---:|---|---|
| 0 | `(defn cluster-agent-count …)` with `{:malli/schema [:=> [:cat] :int]}` | var `my.agents.w1-history-proof-5/cluster-agent-count` |
| 1 | `(cluster-agent-count)` | `2` |
| 2 | `(seon.db/q '[:find ?spec . …])` | `"[:=> [:cat] :int]"` |

Every operation the message asked for succeeded. There is no fourth form,
no `my.run/complete`, no `my.run/wait`, no reply, and no `:my.run/*` datom
of any kind in the ending database value. The episode reported one run and
terminal `:stopped`.

`tmp/ablate-half4` reproduced the shape with a different partial: the same
agent submitted one bad `defn` (`Unable to resolve symbol: seon.db/*db*`),
corrected it in the next form, and the run then closed — again with no
disposition and no second turn.

The cluster's root agent did the same thing in both roots (runs
`e826427c-…`/`267bfc1a-…`), so this is not one agent's slip.

## Why the class matters

The system's own standing rule is that a refusal is loud, typed, flat, and
names what was missing. A run whose reply carries no disposition is the
quietest possible outcome: the agent believes it is mid-task, the requester
believes it asked, and the only trace is an absence. It also makes every
live-drive measurement ambiguous — a grade of "task not done" cannot be
distinguished between "the context was too thin to do the work" and "the
work was done and the turn simply ended".

## Owner

`seon.cluster.loop` settlement, plus `seon.eval.drive/terminal-state` for the
episode verdict it derives.

## Acceptance

- A run that closes with neither disposition commits a durable, typed fact
  naming the run, its agent, and the trigger it left unanswered.
- The agent can see that fact in its own history, so a following turn knows
  the previous one ended undisposed.
- `seon.eval.drive` distinguishes that terminal from an agent that produced
  nothing at all.
- One regression drives a reply whose forms are all clean and carry no
  disposition, and asserts the fact exists and the episode terminal names it.
