---
type: issue
status: open
severity: blocker
tags: [turn, since-diff, context, prompt-growth, design, owner-ruling]
created: 2026-09-10
---

# System turns re-read self-churning reads after every provider turn, and the model cannot tell them from its own forms

## Observed (live run 2, 2026-09-10; landing `live-run-2-landing-2026-09-10.md`)

Sixty-one turns: 30 provider, 31 system. After every provider turn the
since-diff re-evaluated `(seon.agent/effective-settings)` (its
`:my.agent/turns-left` changes every turn) and the runtime pull (its
`:seon.runtime/turns` list changes every turn), 458 bytes per system
turn, printed under the same `my.agents.juniper=>` prompt as the agent's
own forms. The model read them as its own actions and lost roughly a
third of its turns to it: "I have been repeating the same introspection
pulls every turn (effective-settings, runtime pull, dir) and burning turns
without progress. I must stop that pattern" (`a51f8821e5be`, six similar).
After a lane's adoption changed program facts, one system turn re-ran
seven reads (819 bytes) including help. The prompt reached 177,889 bytes
(63,514 tokens) by turn 30.

## Why

Two rules collide: PRD §14 (changed reads are appended in a system turn)
and §18d (every evaluation prints as `<ns>=>` with the agent's comment).
Reads whose value changes on every turn BY CONSTRUCTION (a countdown, the
list of the agent's own turns) are re-appended forever, and nothing in the
grammar says "the system re-read this for you".

## Options for the owner (decision needed; the loop is otherwise working)

1. **Keep churning data out of read forms** (simplest): the opening's
   settings read omits the countdown and the runtime read omits the turns
   list; turns-left is shown by the prompt/help line (derived, not an
   evaluation) and the runtime block shows trigger + listens only. Nothing
   changes in the grammar; system turns become rare (only real changes).
   Gives up: the agent seeing its own turn list in-context (it can query it).
2. **Mark system re-reads in the grammar**: a system turn's evaluations
   print with a fixed comment (`;; changed since your last turn`) before
   the prompt-first entry. Keeps the data, adds one line the model reads
   as system, not self. Gives up: byte identity with the agent's own
   evaluation of the same form.
3. **Both** (recommended): 1 removes the by-construction churn, 2 makes
   any remaining re-read honest.
