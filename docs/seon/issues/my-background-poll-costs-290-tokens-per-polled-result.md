---
type: issue
status: open
severity: friction
tags: [issue, render, effect, class/n1, wave/capability-surface]
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

## N1 disposition — 2026-08-12

Still open outside this lane. Declare the background receipt descriptor's AI
producer and render only identity, disposition, and completion summary through
the shared profile; keep the complete polled value behind its receipt/requery
identity and repeat the eight-ref token measurement.

## Verified at HEAD (2026-09-16, N1 verification)

**CONFIRMED — the declared producer landed, and it introduced a different
loss.**

Half of the acceptance is met. `:seon.effect/receipt` now declares
`:seon.render/ai seon.effect/render-ai`
(`resources/seon/schemas/seon.effect.edn:22-26`), and `my.background/poll`
now takes ONE ref per call (`src/my/background.clj:48-62`), so the filed
eight-refs-per-poll shape no longer exists. The verbatim face for a small
receipt, rendered live on `default` (pid 69622):

```text
Effect effect-1 · run unknown, form 2, effect 0 · returned in 12 ms.
Request (~7 tokens): {:my.fs/path "README.md"}
Result (~6 tokens): {:my.fs/content "..."}
```

But the producer still embeds the WHOLE payload inline — `payload-face` is
`(str label " (~" (tokens/estimate payload) " tokens): " payload)`
(`src/seon/effect.clj:48-50`) — so a polled result's cost is still
proportional to its payload, which is the defect this note names. And when
the payload is large enough for the profile to cut, the cut takes the whole
face with it. Same cluster, same request, one 8,019-character
`:seon.effect/result-edn`:

```text
{:seon.print/bound-by :seon.render.profile/token-budget,
 :seon.print/elision-unit :characters, :seon.print/omitted 8159,
 :seon.render.data/next-offset 0, :seon.render.data/path [],
 :seon.render.data/total 8159}
```

210 characters, and not one of them is the effect id, disposition or
duration: the agent polling this receipt learns nothing at all. The
structural floor does better on the same value (468 characters, identity
attributes intact, payload elided in place), so the declared producer is
currently worse than no producer for exactly the case it was added for.

surface: effect

Fix sketch: `payload-face` should report the payload's estimated size and
its requery identity, not its bytes — the receipt already carries
`:seon.effect/result-blob` / `:seon.effect/result-size` for the drill. Then
the face is O(1) per receipt, the profile never needs to cut it, and the
identity line cannot be lost. Repeat the token measurement against a real
polled receipt when the change lands.
