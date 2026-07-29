---
type: issue
status: open
severity: friction
tags: [issue, agent-runtime, run-loop]
---

# A frozen disposition can close a run against facts newer than the basis it was written at

## Problem

An agent's forms are frozen from one model reply, derived at one pinned
database value. The last form may be `(my.run/complete "…")`. The loop accepts
that value out of the receipt and closes the run in the same terminal
transaction — **without re-deriving anything at that transaction's current
database value** (`src/seon/cluster/loop.cljc:116-187,755-876`).

Between the pinned basis and the terminal transaction, other writers commit:
other agents' turns, the error recorder, and `bin/test` result rows are all
deliberately concurrent. So a completion authored against basis B can close at
B+2 while B+1 carries a fact that contradicts it.

The same-agent turn is sequential, so this is not a same-agent race. It is a
staleness window that is structural to freezing a plan and then executing it.

## Evidence

Interleaving, from the generate-code v0 falsification review
(`docs/prds/sci-execution-runtime/research/generate-code-v0-falsification-2026-07-29.md`
§S10):

1. an agent pins basis B and its context renders as complete;
2. while the model call is in flight, another agent's reply or a failing test
   result commits at B+1;
3. the frozen `complete` form evaluates and closes the run at B+2 without ever
   testing B+1's facts.

The run closes, and — since a completed run replies to the agent that asked
(`src/seon/cluster/message.cljc:134-183`) — the stale conclusion is also
**delivered to another agent** as an answer.

This is a near neighbour of the open issue
`a-failed-form-does-not-stop-the-fold`: both end with a confident wrong answer
propagating to a second agent, one through a missing definition and one
through a stale basis.

## Why it is not merely cosmetic

Any capability whose output is "which parts are accepted" — the generate-code
delegation loop is the immediate consumer — needs completion to mean something
a reader can trust. Saying "readers should trust the derived query rather than
the agent's prose" does not stop the runtime from recording and delivering the
stale completion.

## Owner and acceptance

Owner: the run-closing path (`seon.cluster.loop`).

Acceptance is a ruling first, then whichever mechanism it implies:

- **either** the terminal transition re-derives at its own current database
  value and refuses to close a run whose completion contradicts newer facts
  (a transaction-time check, in the transaction, not a caller pre-read);
- **or** completion is ruled to mean only "I have nothing further to say this
  turn" — never an acceptance claim — and every consumer that currently reads
  it as acceptance is corrected, with the derived value carrying acceptance
  instead.

A regression must show the interleaving above producing either a refusal or a
completion that no consumer reads as acceptance.

## Related

- `docs/seon/issues/a-failed-form-does-not-stop-the-fold.md` — open, same
  failure family (a confident wrong answer reaching a second agent).
- `docs/prds/sci-execution-runtime/plan/generate-code-v0-plan-2026-07-29.md`
  §10 — rules the semantics for that capability's v0 and defers this class
  here rather than absorbing it.
