---
title: "Card RT-5 A proof reads the task it proves"
type: reference
status: active
created: 2026-10-08
---

# RT-5 A proof reads the task it proves

Ledger §3.28 RT-5 (group E). Lane: Opus (test entrance and reuse evidence).

## What becomes true
- A proof request names the task whose proofs it runs; the member's world carries `:seon.task/id`, supplied by schema
  name to a test body that declares it, so one deftest can prove many generated tasks.
- A member that read task data records the digest of what it read
  (`:seon.test.member/task-digest`, the F6 mechanism generalized; F6's `answer-digest` is the special case of the task's
  answer), and its green is reused only for that task and while that digest holds.
- `seon.test/verified?` gains the arity `[database test task-handle]`; the 2-arity stays for tests that read no task.
  `assign-tx`, completion and the block call the 3-arity for a task's proofs.

## Functions
- CHANGE `seon.test/run`'s proof request (F4a's): carry `:seon.task/id`; the member world gains it.
- CHANGE `seon.test/verified?`: compare the member's `task-digest` with the digest of the task's current data read the
  same way (one pull of the task row and its held assignment's answer).
- CHANGE `seon.test.member.edn`: `:task-digest` optional. Coordinate with F6: if F6 lands first, rename its
  `answer-digest` into this key in this card (one scripted sweep).

## Deleted
F6's separate `answer-digest` path, if F6 landed first.

## Proof
`seon.test-test/a-task-reading-proof-is-green-per-task` (new): one deftest reading `:seon.task/id` from its world,
green for task A after A's answer is recorded, is not verified for task B and is not verified for A after A's answer
changes.

## Cost
One pull per verification of a task-reading proof; none for others.

## Dependencies
F6 (and F4a, landed). Commit with `--test seon.test-test --test seon.competency-test`.
