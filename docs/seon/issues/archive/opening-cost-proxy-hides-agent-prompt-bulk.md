---
type: issue
status: resolved
severity: friction
tags: [issue, render, performance, wave/live-drive-context]
---

# Measure opening cost from the agent-consumed bytes

## Problem

The generated-opening source/result-face estimate is not the cost of the bytes
later sent to the agent. It materially understates even one opening snapshot,
so it cannot explain or gate provider context cost.

## Evidence

Drive 1 Attempt 4 reported a 339-token source estimate plus a 4,437-token
result-face estimate: 4,776 tokens. The exact paid request contained one
68,905-character user message and no system message. DeepSeek charged 22,604
prompt tokens.

Splitting the durable capture at its actual history entries shows what filled
the request:

| Exact capture region | Characters | Local estimate |
|---|---:|---:|
| first opening snapshot, entries 0–18 | 34,033 | 10,635 tokens |
| repeated opening snapshot, entries 19–35 | 33,285 | 10,401 tokens |
| paid-run tail, entries 36–39 | 1,583 | 494 tokens |

Thus there is no hidden provider system segment. The retained-history defect
accounts for about half the prompt, but the first opening snapshot alone is
2.2 times the 4,776-token proxy. Its rendered values consume 32,574 characters
and approximately 10,179 locally estimated tokens. They include large
agent-facing directory faces such as this 3,318-character entry:

```text
my.agents.drive-one-agent-attempt-4=> (dir (quote my.web))
[(ns my.web (:require [seon.effect :as effect] [seon.schema.edn :as schema.edn])) {:seon.fn/sym "my.web/fetch", ...}
```

Eight other toolkit directory entries are similarly large. The stored receipt
face proxy does not price the fitted transcript bytes, REPL source prefixes,
or the consumer-specific directory output that the provider actually sees.

## Owner

The generated-opening cost measurement and its join to the exact
`seon.render.walk/history` bytes consumed by `seon.cluster.prompt`.

## Acceptance

- Opening cost is derived from the exact agent-consumed bytes, with an
  authoritative per-entry decomposition whose sum equals the capture.
- A report cannot label stored source/result strings as an opening-context
  estimate without also exposing and reconciling the consumer-fit delta.
- A recurring live fixture bounds full toolkit-directory contribution and
  fails when the opening proxy diverges materially from the captured prompt.

## Resolution

Commit `470ecf029` makes the render proc return the exact ordered retained
history segments whose concatenation is the agent prompt. Prompt accounting
records one contribution per segment. Each token count is the difference
between calibrated cumulative-character estimates, so separators belong to a
specific entry and the ordered sum is exactly the whole-prompt estimate.
Commit `b57076d08` adds the recurring full-toolkit check: the fitted
`(dir (quote my.web))` bytes are one bounded contribution inside that whole.
The final prompt suite passed 10 tests and 121 assertions.

The fresh isolated-root proof recorded these durable facts:

| Capture | Characters | Entries / positions | Contribution-token sum |
|---|---:|---:|---:|
| `bootstrap:root-context-536870964` | 33,199 | 16 | 10,374 |
| `context-fidelity-current-run-context-536871000` | 60,005 | 47 | 18,751 |

For the second capture the in-memory whole-prompt budget estimate was also
18,751. The contribution rows therefore reconcile to the exact
agent-consumed bytes; stored source/result faces remain receipt evidence and
are no longer the prompt-cost measurement.
