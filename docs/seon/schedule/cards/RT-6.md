---
title: "Card RT-6 Answers live on the assignment"
type: reference
status: active
created: 2026-10-08
---

# RT-6 Answers live on the assignment

Ledger §3.28 RT-6: an amendment to A1 before it lands (group E). Q-RT4 (recommended option assumed). Lane: Sonnet.

## What becomes true
A1's answer is a component of the assignment (`:seon.task/answer`), not of the agent: an agent answering several held
questions (root) keeps one answer per task. `my.agent/answer!` and `my.agent/answer` keep their contracts and find the
held task as A1 says; with RT-3 they take an optional `:seon.task/id` to name which held task when several are held.

## Functions
A1's card, with `seon.agent.edn`'s `:answer`, `:sources`, `:source-names` moved to `seon.task.edn` as assignment
members, and the transaction writing the assignment entity instead of the agent. If A1 has landed, this card moves the
three members (publish and adopt on a live-store branch) and converts its readers in one commit.

## Proof
`my.agent-test/an-answer-is-data-on-the-assignment` (A1's proof renamed): the A1 cases, plus two held question tasks
answered separately read back separately.

## Cost
As A1: one validation, one transaction of the answer's datoms.

## Dependencies
A1 (amend before launch), RT-3 for the optional task argument.
