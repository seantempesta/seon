---
title: "Card CX-2 A model turn only with an assignment; a reply with no form waits"
type: reference
status: done (7e39aeed3)
created: 2026-10-08
---

# CX-2 A model turn only with an assignment; a reply with no form waits

Ledger row CX-2 (group B). Spec: agent-loop.md §3.5 table (continuation) and CLAUDE.md "Derive work and completion"
(a model agent launches only on an assigned task with proofs). Tasks: `seon.turn/no-model-turn-without-an-assignment`
(its proof is this card's first proof). Lane: Sonnet.

## What becomes true

- `seon.turn/next-agent-work` derives no `:open` situation for an agent that holds no assignment
  (`seon.task/agent-task` answers no handle). Wakes stay unanswered and show in the block once a task is assigned.
  Root holds no assignment today, so error notifications stop opening root model turns.
- `seon.turn/continuing-reply?` continues only when the latest reply evaluated at least one form without a read
  error (§3.5: "no form (prose only), or only read errors → `:wait`"). A disposition (wait, complete) stops it as
  today. The cap (`:seon.config.run/max-episode-runs`) stays the bound.

## Functions

- CHANGE `seon.turn/next-agent-work` (`:open` arm only): `(when (string? (task/agent-task db agent-id)) …)` around
  the wake/continuation derivation; an error value from `agent-task` is returned as the work's error, as the
  deferred branch already does.
- CHANGE `seon.turn/continuing-reply?`: add the clause that the turn has an evaluation without
  `:seon.cluster.eval/error` of the reader's type (read the installed reader-error key; do not parse messages).

## Deleted

Nothing. Net ≈ +10 source, +40 test.

## Proof

`seon.turn-work-test/an-unassigned-agent-with-a-wake-gets-no-model-turn` (tagged `seon.turn/no-model-turn-without-an-assignment`):
an agent with a provider configured and an unread message, no assignment: `(seon.turn/next-agent-work db request)`
is nil; after assigning a task with a red proof it is `:open`.
`seon.turn-work-test/a-prose-only-reply-waits`: a fixture-model reply with only prose: `next-agent-work` is nil after
the run closes; a reply with one evaluated form continues.

## Cost

Two indexed reads per derivation (`agent-task`, one evaluation clause): ~1 ms, O: follows the agent's assignments.

## Dependencies and order

None (file `src/seon/turn.clj` shared with CX-1: run after CX-1 in group B). Commit:
`bin/lane commit LANE -m MSG --test seon.turn-work-test --test seon.cluster.turn-test src/seon/turn.clj test/seon/turn_work_test.clj`.
