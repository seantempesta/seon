---
type: issue
status: open
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
