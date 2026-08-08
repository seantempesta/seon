---
type: issue
status: open
severity: friction
tags: [issue, toolkit, render]
---

# `my.background/poll` costs ~290 tokens per polled result

## Problem

Every descriptor `my.background/poll` returns carries the complete
`:seon.effect/request-edn` and `:seon.effect/result-edn`, so polling costs
tokens proportional to the payloads of the work rather than to its state. An
agent that fans out eight background jobs and polls twice has spent over
5,000 estimated tokens on bookkeeping before reading a single result it
asked for.

Two owners: `my.background/poll`'s selector, which pulls the full request
and result EDN for every ref, and the absence of a declared
`:seon.render/ai` producer for the receipt descriptor, so the value falls to
the generic render floor.

## Evidence

Tool-exercise lane, 2026-08-07, cluster `tools` in an isolated operator root.
Report:
[tool-exercise-2026-08-08.md](../../prds/sci-execution-runtime/research/tool-exercise-2026-08-08.md).

```text
one poll of 8 refs, pending:  2,833 estimated tokens (11,334 characters)
one poll of 8 refs, settled:  2,318 estimated tokens (13,872 characters)
                              ≈ 290 tokens per polled result
```

## Expected

Polling reports STATE — settled or not, and the identity to read the result
with — at a cost proportional to the number of refs, not to their payloads.
The full result stays retrievable by the identity the descriptor already
carries. The receipt descriptor declares its own `:seon.render/ai` producer
rather than falling to the value floor.

## Acceptance

- A poll of eight pending refs costs on the order of tens of tokens per ref,
  measured the same way.
- The receipt descriptor has a declared `:seon.render/ai` producer, and a
  polled result's rendered face is read verbatim in the issue when closed.
