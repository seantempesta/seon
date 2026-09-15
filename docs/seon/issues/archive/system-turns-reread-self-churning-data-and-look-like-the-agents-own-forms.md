---
type: issue
status: resolved
severity: blocker
tags: [issue, turn, since-diff, context, prompt-growth, design, owner-ruling]
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

## Owner ruling, 2026-09-14

Generated read evidence must exclude the turn, attempt, evaluation and
runtime-turn families, declared through schema data. A generated read that
violates this invariant is a fault naming its source form. Turns-left
belongs in the derived prompt frame. Runtime statistics remain available
on demand.

A changed read carries `;; changed since your last turn`, its original
form, and an EDN change against the previous shown value. A full-value
requery points at the new evaluation's real result handle. The opening
remains full; the agent's own input remains byte-stable.

Implementation and verification are recorded in
[context-renders-landing-2026-09-14.md](../../prds/context-generation/research/context-renders-landing-2026-09-14.md).
The broader invariant also fixed root statistics reading excluded families,
collection-bound evidence, and promotion of turn-dependent agent queries.
The recurring loop proves three no-event turns add zero evaluations and
zero bytes; a plan write and a message each add only their own changed read.
Responses are readable EDN with working full-value handles. The final
provenance regression proves an opening is not falsely marked changed.
The combined isolated gate passes 19 tests / 369 assertions; the final
help trial scores 12/12. Owner-observed run 3 independently had only two
system turns in 21 turns (opening plus a plan update). The requested
stored-run-2 explain comparison remains unavailable after the owner's reset;
the landing note does not claim that comparison was performed.
