---
title: "Card AE-7 The answered basis is a stored home t on the agent"
type: reference
status: active
created: 2026-10-08
---

# AE-7 The answered basis is a stored home t on the agent

Index: [README-agent-entity](README-agent-entity.md). Implements agent-loop.md §3.5's `:seon.agent/answered-t`.
Lane: Opus (the one REPL path's recording).

## What becomes true
A committed model eval request writes `:seon.agent/answered-t` (`:branch`), the home basis its block presented, on
the agent entity in its one recording transaction. Unanswered wakes are the home's listened datoms for the agent
after it: one seek per listened attribute, no walk of answering turns.

## Functions
- CHANGE the recording writer (`seon.turn`), `seon.agent.edn` (`:answered-t`), `seon.agent/record` (answers it from
  the branch value), `seon.turn/unanswered-wakes`.
- DELETE the answering-turn derivation in `unanswered-wakes`.

## Proof
`seon.turn-work-test/answeredness-is-the-turns-own-transaction` (existing; rewritten to the stored basis): a wake
written at the home after `:seon.agent/answered-t` opens one turn, one before it opens none, for an agent on its
branch, through `seon.mcp/call`.

## Cost
One datom per model eval request; wake derivation O(wakes after the basis).

## Dependencies
AE-4, AE-6. Commit with `--test seon.turn-work-test`.
