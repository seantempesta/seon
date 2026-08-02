---
type: issue
status: open
severity: friction
tags: [issue, database, testing]
---

# `:keep-history? false` is not a creation-seam toggle

## Problem

Ruling #40 made `keep-history` a per-cluster dial so scratch and eval
clusters could run history-off for store economy. Implementing it for
the 2026-08-02 eval run found the dial is not sufficient: **live
`d/history` calls prevent booting a non-temporal database**, so a
history-off store fails where those readers run. The run therefore
used history-on and paid the full store cost
([[eval-samples-cost-42mb-of-store-each]]).

## Acceptance

Find every `d/history` (and `as-of`/`since`) reader in first-party
source and decide, per reader, whether it is essential to a
history-off cluster: readers that exist for debug archaeology should
degrade honestly (state that history is absent) rather than refuse the
boot; readers genuinely required by grading or restore stay, and their
requirement is the argument for keeping history on wherever they run.
Then the dial works as ruled: a scratch/eval cluster boots history-off
and reports what it gives up. A regression boots one cluster each way
and proves both serve their intended readers.
