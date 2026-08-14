---
type: issue
status: open
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
