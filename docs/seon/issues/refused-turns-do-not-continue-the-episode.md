---
type: issue
status: open
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
