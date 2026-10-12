---
title: "Context review round 4: full first-call contexts after one-field tasks"
type: research
status: complete
created: 2026-10-12
---

# Context review round 4

The full first-call messages (system, then user) for the three reviewed tasks plus one task that declares
`:seon.task/needs`, verbatim, on default running 8d14faeee (a task is its name, its intent with inline references and
its optional needs; `seon.task/refine!`; a referenced function shows its source; references resolve one level; `(help)`
updated).

## Method

Same as round 3 (a8d4a784e). In JVM mode as root: a `seon.db/with` of default's main holding a fixture agent `ctx-dump`
(`seon.cluster.agent/creation-tx`, in the task's namespace) and one attempt row (`seon.task/attempt-tx`) for the task;
`seon.context/model-context` with default's cluster caps, SCI context and 30,000 ms time limit (the request
`seon.turn` builds); the messages laid out by `seon.ai/messages` with wrapping `:chat`. Nothing was committed to the
store and no provider was called. Tokens are `seon.ai.tokens/estimate` per message. Task handles changed with the
identity (`seon.id/id` over namespace and name), so they differ from rounds 1 and 3.

## Sizes (estimated tokens)

| Task | R1 sys | R1 user | R1 total | R3 sys | R3 user | R3 total | R4 sys | R4 user | R4 total | R4 vs R3 |
|---|---|---|---|---|---|---|---|---|---|---|
| `seon.sci.forgive/forgiving-forgiven-text-changes-nothing` | 715 | 1,944 | 2,659 | 1,889 | 2,520 | 4,409 | 2,162 | 6,092 | 8,254 | +3,845 |
| `seon.ai/contract-output-observed?` | 715 | 1,363 | 2,078 | 1,889 | 1,130 | 3,019 | 2,162 | 2,001 | 4,163 | +1,144 |
| `seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts` | 715 | 1,953 | 2,668 | 1,889 | 3,415 | 5,304 | 2,162 | 6,093 | 8,255 | +2,951 |
| `seon.cluster.message/root-offers-a-sent-message-once-and-merges-its-record` (has needs) | n/a | n/a | n/a | n/a | n/a | n/a | 2,162 | 5,995 | 8,157 | n/a |

System message: 1,889 to 2,162 tokens (6,920 characters; the updated `(help)`). User message characters: forgive
19,496, contract 6,404, merge 19,500, needs task 19,184.

## Findings

- Three of four user messages sit at 5,995 to 6,093 tokens, the 6,144 display budget: the block now hits its cap and
  cuts (elision marks `…+N` appear on 7, 25 and 28 lines of the forgive, merge and needs captures). The folded
  references and function source are what fill it; the contract task (no cited functions of size) stays at 2,001
  with no elision.
- The needs task shows its need as a resolved entry in `:must-reads` (`seon.turn/a-held-task-opens-no-model-turn`,
  with its intent and its own one-level references), then the intent's cited function `seon.cluster.message/send!`
  with its whole source.
- Capture cost on default (fixture, `model-context`, `messages`): forgive 662 ms, contract 886 ms, merge 1,946 ms
  (over one second; unchanged finding for its producer), needs task 207 ms.
