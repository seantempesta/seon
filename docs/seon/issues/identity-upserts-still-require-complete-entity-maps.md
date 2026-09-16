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

Turn-test-reds recheck at `c1d7d4695`, after `20d30a0bd`: both test-local
config seeds still return `:seon.db/invalid-write`, naming the missing
`:seon.config/applied-manifest-digest`. The affected tests are
`refused-terminal-program-transactions-settle-and-do-not-refire` and
`generated-model-attempt-traces-preserve-presence-and-episode-laws`.
The fresh canonical-base probe recorded run 44594, with both complete returned
refusals retained in the [lane evidence](../../prds/context-generation/research/turn-test-reds-cache-2026-09-16.md).
This is the deliberately retained complete-entity constraint, not recurrence
of optional-identity misclassification. The lane skips their downstream
assertions under the owner's seed-refusal boundary.

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

## 2026-09-16 fixture disposition

The two turn-test members above no longer block: explicit lookup-ref updates
and checked seed results preserve the complete-entity admission rule. Generated
scenario repair `d8b06746b` passes 48 trials before/after source reload; terminal
refusal repair `80d8fbd0f` passes 33 assertions before/after reload (57019, 60109).
The broader policy question remains open; no validator changed.

## Config/reconcile cold-gate follow-up — 2026-09-16

Batch 36's config hand-edit and reconcile provenance/scope/pull failures
share this same refused-fixture-write premise. Read-only admission on default
confirmed the original five-field config seed refuses the missing
`:seon.config.agent/turn-completion-backstop-ms`; the identity-bearing
hand edit refuses missing `:seon.config/applied-manifest-digest`. Neither
write had been checked, so downstream assertions described facts that were
never committed. This is not evidence of lost transaction metadata or a
digest-only convergence shortcut.

`seon.reconcile-test/config-row` now uses `config/compile-manifest` for a
complete desired row. Its `transact-as!` helper throws with the full refusal
before continuing. Hand edits use the existing `:db/id` lookup-ref form;
the config class regression verifies both the write report and changed value
before checking repair. No write validator or behavioral expectation was
weakened. The exact cold iteration result lives in
[the config landing](../../prds/context-generation/research/turn-bookkeeping-cost-2026-09-16.md).
