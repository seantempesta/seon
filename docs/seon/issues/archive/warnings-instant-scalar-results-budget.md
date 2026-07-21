---
type: issue
status: resolved
tags: [agent, context, database, issue]
severity: friction
---

# Warnings block died on the database-instant scalar results budget

## Evidence

On the live default cluster (2026-07-21, ~2374 transactions) every agent
context rendered `[warnings] render failed: Warning acquisition failed.`
followed by the raw acquisition member dump — the whole warnings block was
dead, so the hop-exhausted dead-letter for refused messages `e8yov6xye7jv`
(root → real-hats-wave, hops 5/4) and `z0dxqec1d8a2` never surfaced to any
agent or the human.

Root cause: the `database-instant-query` member in
`src/seon/agent/ctx/warnings.cljs` budgeted `max-results 1` for the
`(max ?instant) .` scalar, but Datahike's resource governance counts the
scanned relation — one row per transaction — against the results budget.
REPL proof: the identical query at `max-results 65536` succeeds with
`:datahike.resource/result-count 2374`; at 1 or 2 it fails closed with
"datahike query-results budget exceeded". The member therefore fails on any
grown database, and the block-level `every? success?` gate turned one
under-budgeted member into total warnings loss. Same class as the
`my.plan` scalar-budget fix (`4f38818f`), but there the relation was
selective; here it is the full transaction log.

## Fix

`database-instant-query` member budget raised to
`2000000 / 65536 / 1048576` with a comment recording the measured
accounting. Verified live: `seon.agent.turn/render-prompt` for
`real-hats-wave` now renders the warnings block with the hop-exhausted
rows named, no "render failed" text anywhere in the 126k-char context.

## Residual risk

Any other aggregation-over-large-relation member budgeted for its output
row count will fail the same way as the database grows; budgets for
aggregations must cover the scanned relation until governance charges
post-aggregation results separately.
