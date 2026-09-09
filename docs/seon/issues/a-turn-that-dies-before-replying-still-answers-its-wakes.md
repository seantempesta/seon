---
type: issue
status: open
severity: friction
tags: [issue, wake, run-loop, architecture, wave/why-awake]
date: 2026-09-08
---

# A turn that dies before replying still answers its wakes

## What

Answeredness is now the turn's own transaction `:t`: a wake is answered when
some turn of that agent has `:t` at or after the wake's
(`seon.cluster.work/unanswered-wakes`, `src/seon/cluster/work.clj`). The `:t`
is stamped when the turn OPENS, which is before the context is projected and
long before the model replies.

So every turn answers every wake at or before its opening — including turns
that never got that far:

- a turn that opened and whose process died before the model call;
- a turn whose provider call failed;
- a system source submission (`seon.cluster.agent/submit-source!`), which
  opens an ordinary turn to evaluate submitted forms and has nothing to do
  with the messages pending at that instant.

In each case the wake is consumed and no later pass re-derives it. The PRD
states the first case deliberately — "the model is never re-called because
the turn's basis was recorded at open, so the wake is answered"
([the turn-loop PRD](../../prds/context-generation/plan/agent-record-and-turn-loop-prd-2026-09-07.md)
§1a, "On resume, explicitly") — but it does not say what wakes the agent to
look at the interruption, and it does not mention the source-submission case
at all.

## Why it matters

This is the project's recurring failure class in a new place: the ABSENCE of
an unanswered wake reads as health. Nothing is logged, nothing refuses, and
the agent simply never hears a message that arrived before a turn that
achieved nothing.

The stored reference this replaced had the same hazard for a crashed turn
(the reference was also written at open) but NOT for a source submission,
because a source run recorded no trigger and therefore answered nothing.

## Evidence

`seon.cluster.turn-test/a-settled-orphan-stops-wedging-the-agent` fabricates
an orphan run with no trigger reference and asserts that a message seeded
before it is still answered by a NEW run. Under `:t` the orphan answers it,
and the test went red. It was re-expressed to transact a later message
(`m-after-crash`), which is the production shape — a real crashed turn does
record what it answered — and it names the new semantics in a comment rather
than hiding it.

## What would close this

One of:

1. a declared reason a turn answers nothing — for example, answeredness
   counts only turns that stored a reply, with the paid-twice risk stated and
   measured; or
2. a wake asserted BY the interruption path, so a turn that died with no
   reply leaves something for the next pass to see (this is the shape the
   rest of the system uses: the fact that says "look" is a datom on a
   listened attribute); or
3. an owner ruling that the loss is intended, written into the PRD together
   with what the agent is expected to notice instead.

Option 2 is the one that fits the mechanism this lane built: `interrupted-at`
is already stamped, and a listened attribute on the interrupted turn would
make the interruption its own wake with no new machinery.
