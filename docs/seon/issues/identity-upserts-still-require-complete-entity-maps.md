---
type: issue
status: open
severity: friction
tags: [issue, database, schema]
---

# Identity upsert maps still require the complete entity contract

## Problem

The 2026-09-16 write-validation assignment explicitly preserves rejection of
an incomplete map asserting :seon.cluster.eval/id. The same rule means a
map asserting :seon.turn/id or a model identity must supply its entity's
required keys, even when intended as a partial upsert. This is a remaining
difference from Datahike map syntax, not optional-identity misclassification.

## Evidence

On default pid 7595, after commit `20d30a0bd`, the candidate admission of
`{:seon.turn/id "gauge-run"}` refuses at [0 :seon.turn/agent].
The existing gauge test seeds exactly this at test/seon/turn_loop_test.clj:198.
A direct read-only admission probe of
`{:seon.ai.model/id "deepseek-flash" :seon.ai.model/max-output-tokens 1000}`
also refuses at [0 :seon.ai.model/provider], preserving the original
model-upsert observation.
No pre-read can safely decide whether an incomplete map updates an existing
entity. Identity-free maps using :db/id lookup refs already admit partial
attribute changes; explicit :db/add remains available.

## Owner

seon.db/write-map-error. Any broader partial-upsert policy must reconcile
the required-entity contract with Datahike's writer authority. Do not add
a racy existence query in front of transact!.

## Acceptance

An owner ruling defines whether asserting an identity means a complete
entity or an upsert. If upsert completeness is required, the writer must
validate against its own transaction database. Until then, fixtures supply
complete entities or explicit attribute writes, and read their results.

The bounded implemented guarantee, regression and tradeoff are in
[the landing note](../../prds/steward-platform/research/write-validation-class-2026-09-16.md).
