---
type: issue
status: resolved
severity: friction
tags: [issue, agent, runtime]
---

# A turn ending in only refusals does not continue the episode

## Problem

Observed live during the context-MVP drive
(`research/mvp-seams-notes-2026-07-31.md`, final iteration): the agent's
turn produced forms that were all lint-refused; the refusals settled the
receipt, the original trigger message was already answered by the run, and
no new wake existed — so the episode ended and the agent never saw the
refusal steering it was supposed to adapt to. The episode turn budget
(100) was not the limiter.

The ruled refusal design ("below-cap, the next turn carries the
refusal") assumed a next turn occurs. Under wake semantics, it only
occurs if something re-wakes the agent, and a refusal is not a wake.

## Decision needed (owner design gate — options, simplest first)

1. A turn whose forms were ALL refused does not settle the episode: the
   loop opens the next turn immediately (bounded by the episode cap),
   with the refusal in context. Simplest; makes the ruled sentence true;
   risk = a persistently confused agent burns turns to the cap (the cap
   is the existing bound).
2. Refusals leave the trigger UNANSWERED (no run-opening tx answer until
   a turn commits at least one successful settlement), so the ordinary
   wake machinery retries. Reuses wake semantics; changes the meaning of
   "answered."
3. Keep current behavior; refused work waits for the next external
   message. Cheapest; the agent's steering arrives late or never.

## Acceptance

One recurring test: a turn of all-refused forms is followed (under the
chosen rule) by a turn whose context contains the refusal, without any
external message; episode cap still bounds the loop.

## Resolution — 2026-07-31

Ruling #22 selected presence-derived continuation for **any** turn containing
at least one lint-refused form, including a mixed successful/refused turn.
`02cc0b2ec` derives the next situation from the latest closed run's committed
receipt values at `seon.cluster.work`; it reuses the original trigger identity
and introduces no state, timer, message, or new cap. `3f29a6aff` proves the
per-agent graph self-wakes, and `37c31dba1` keeps that proof behavioral under
the compact walk.

Recurring evidence is green: the exact graph falsifier passed 1 test / 15
assertions, while work derivation plus the repaired restart proof passed 10
tests / 89 assertions. `dee80767b` also removed the restart test's obsolete
prompt marker and observes the corrective run from database facts.

The DeepSeek drive in
[[refusal-continuation-notes-2026-07-31]] supplies live proof. Three consecutive
`context-mvp` runs reused trigger entity `4315`; the second and third contexts
rendered the preceding `:seon.cluster.loop/lint-rejected` findings with no
intervening message. The broader self-message MVP remained red because the
model sent to `root`; that separate harness/exit defect does not reopen the
refusal-continuation contract.
