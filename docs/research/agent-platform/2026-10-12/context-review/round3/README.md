---
title: "Context review round 3: full first-call contexts after the rework"
type: research
status: complete
created: 2026-10-12
---

# Context review round 3

The full first-call messages (system, then user) for the three reviewed tasks, verbatim, after the new `(help)` guide
(8a3eae77a, 070c01736), the reworked block (070c01736, b4814acf5, 37313a731) and the 6,144 display / 4,096
large-value budgets (923123589).

## Method

Same as round 1 (b050829f5). Default ran 321697b08 (cluster `default`). In JVM mode as root: a `seon.db/with` of default's
main holding a fixture agent `ctx-dump` (`seon.cluster.agent/creation-tx`, in the task's namespace) and one attempt row
(`seon.task/attempt-tx`) for the task; `seon.context/model-context` with default's cluster caps, SCI context and
30,000 ms time limit (the request `seon.turn` builds); the messages laid out by `seon.ai/messages` with wrapping `:chat`.
Nothing was committed to the store and no provider was called. Tokens are `seon.ai.tokens/estimate` per message.

## Sizes (estimated tokens)

| Task | Round 1 system | Round 1 user | Round 1 total | Round 3 system | Round 3 user | Round 3 total | Change |
|---|---|---|---|---|---|---|---|
| `seon.sci.forgive/forgiving-forgiven-text-changes-nothing` | 715 | 1,944 | 2,659 | 1,889 | 2,520 | 4,409 | +1,750 |
| `seon.ai/contract-output-observed?` | 715 | 1,363 | 2,078 | 1,889 | 1,130 | 3,019 | +941 |
| `seon.merge/a-jvms-first-facts-merge-follows-the-changed-facts` | 715 | 1,953 | 2,668 | 1,889 | 3,415 | 5,304 | +2,636 |

The system message grew 715 to 1,889 tokens (the `(help)` guide, rendered whole, 6,046 characters). The user message
shrank only for the contract task; the forgive and merge blocks grew because round 1's blocks were cut at the old 2,000
token display budget (round 1 elided the intent and proofs with `…+N`); at 6,144 nothing is cut except in the contract
task, which still shows one elision mark. Round 1's merge block was cut to fit; its uncut size was 12,638 tokens
(round 2 README), now 3,415.

Capture cost on default: forgive 387 ms, contract 574 ms, merge 1,555 ms for fixture, `model-context` and `messages`
(the merge assembly is over one second; a finding for its producer).
