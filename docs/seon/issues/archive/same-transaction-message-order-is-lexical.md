---
type: issue
status: resolved
severity: friction
tags: [issue, runtime, concurrency]
---

# Preserve message vector order past index nine

## Problem

Messages delivered in one returned vector share one timestamp, and downstream
ordering breaks the tie by lexical message identity. A batch of twelve is
therefore rendered and scheduled as `0,1,10,11,2...`, not in the agent's
declared vector order.

## Evidence

Two concurrent runs each returned twelve `my.message/send` values. All 24
message facts committed with correct sender and recipient. Each recipient's
twelve facts shared one transaction and one `:seon.cluster.message/at` value.
`seon.cluster.work/unanswered-triggers` and `seon.render.transcript/render-ai`
both ordered the batch `00,01,10,11,02,03,...,09`.

`work/unanswered-triggers` sorts equal timestamps by the string message ID,
whose suffix is `message-<index>`. Exact facts and rendered output are in
[concurrency streams crossed](../../prds/sci-execution-runtime/research/concurrency-streams-crossed-2026-08-04.md).

## Owner

The message fact and `unanswered-triggers` ordering contract, reused by the
transcript projection.

## Acceptance

- A vector of at least twelve messages is scheduled and rendered in numeric
  source-vector order.
- Equal timestamps remain deterministic without parsing identity strings.
- Every message remains one durable fact with its existing derived identity;
  no queue or stored transcript projection is introduced.

## Resolution

Resolved by commit `7cfb2435f`. Delivery now records the source vector's
numeric `:seon.cluster.message/ordinal` on every message row. Inbound singleton
messages record ordinal zero. Work derivation and both transcript projections
order messages by the message instant, assertion transaction, numeric ordinal,
and numeric entity id; no ordering consumer parses or compares the message id.

The two-digit regression commits twelve messages in one transaction and proves
numeric order in `unanswered-triggers`, the AI projection, and the HTML
projection. The focused checkpoint passed 36 tests / 232 assertions. A fresh
`message-order-proof-0804` cluster recorded ordinals `0..11` in transaction
`536870977`; its AI and HTML transcript projections each returned all twelve
messages in order from `message-00` through `message-11`.
