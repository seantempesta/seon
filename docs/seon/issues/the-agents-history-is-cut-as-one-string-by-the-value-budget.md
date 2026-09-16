---
type: issue
status: open
severity: blocker
tags: [issue, render, print, agent-context, prompt]
created: 2026-09-16
---

# The agent's history is cut as one string by the value renderer's budget

## Problem

`seon.render.transcript/render-ai` carries `seon.render/request-profile` — the
cluster's AGENT VALUE render profile — and renders the whole history through
`seon.render.value/render-ai` (`src/seon/render/transcript.clj:1893`,
`src/seon/render/transcript.clj:425`). The walk produces the history as ONE
string, so when it exceeds `:seon.render.profile/token-budget` the entire
history is cut by `seon.print/fit-text` AT A CHARACTER OFFSET.

Measured on batch 70 (`ca9a8b0e8`), `seon.concurrency-independence-test`: a
2,075-character transcript against a ~640-token budget renders as one cut with
a ~500-character prefix and `:seon.print/omitted 1575`. Every message payload,
every form after the prefix, and every contract in the agent's own history is
gone — and the assertion that catches it is a CONTENT assertion, not a
presentation one:

```clojure
(is (str/includes? rendered (::payload incoming)))  ; concurrency_independence_test.clj:497
```

Two things are wrong and they are separable:

1. **The history is cut in the wrong dimension.** An agent's history is a
   sequence of turns and evaluations. Cutting it BY EVALUATION — dropping
   whole oldest entries, each remaining one intact and nameable — is the cut
   the reader can act on. A character offset into the concatenated text cuts
   mid-form, mid-message and mid-contract, and the elision's path is `[]`, so
   nothing names which turns were lost.
2. **It is bounded by the wrong budget.** The prompt has its own declared
   dial, `:seon.config.ai/prompt-token-budget`
   (`src/seon/cluster/prompt.clj:232`). The value renderer's
   `:seon.render.profile/token-budget` is the bound for ONE RESULT an
   evaluation shows, not for the whole session an agent reads.

## Not the floor change, either way

Verified by A/B in one JVM, same input (2,075 characters, 640-token budget):

| `seon.print` | result |
|---|---|
| `bb33b93fa~1` (before the floor slice) | `omitted 2075`, `next-offset 0`, **no prefix** |
| HEAD | `omitted 1051`, `next-offset 1024`, 1,024-character prefix |

So the content assertions above could not have passed before the floor slice
either — a whole-omission elision contains no payload at all. The floor
strictly increased what they can see; it did not cause them, and relaxing
them would hide the defect rather than fix it.

## Wanted

The history's cut is structural: whole evaluations, oldest first, with an
elision naming how many turns were dropped and from which ordinal — and it is
judged against the prompt's own token budget, not the value profile's.
Regression: an agent whose history exceeds the budget still renders its most
recent turns COMPLETE, and the assertion that its rendered history contains
the message it just received holds.
