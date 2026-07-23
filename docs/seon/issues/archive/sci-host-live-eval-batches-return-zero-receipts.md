---
type: issue
status: resolved
severity: blocker
tags: [issue, agent, runtime]
---

# Make SCI-hosted live eval batches execute and record forms

## Resolution

The observed zero-receipt runs had no live SCI coordinate. The protected
launch manifest reconciled `:seon.config.execution/host-tier?` to false and
there were zero eval-socket facts. Enabling the database fact and running the
existing host-coordinate reconciliation made ordinary batches execute through
the JVM SCI host immediately. A restart that did not reapply the disabling
manifest preserved the database fact and resumed the same agent.

The exact pod write-back drop is also loud now:
`seon.agent.turn/eval-parsed!` counts executable parsed entries and rejects a
result batch that records zero attempts. It records a core fault and returns a
flat `:seon/error` value. No second evaluator or receipt owner was added.

## Proof

Agent `common-camels-sit` produced 186 SCI receipts before restart and 163 SCI
receipts after restart while recovering the durable municipal heat-resilience
facts. Bun-local package batches in the same arc retained 33 receipts.

- focused runtime: 38 tests / 182 assertions / 0 failures / 0 errors;
- full CLJS: 1,566 / 7,732 / 0 / 0;
- full writer: 382 / 2,982 / 0 / 0.

Evidence and the complete hop trace are in
`docs/prds/sci-execution-runtime/research/loop-slice-2026-07-22.md`.
