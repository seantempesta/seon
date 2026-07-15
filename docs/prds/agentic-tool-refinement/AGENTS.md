---
type: orchestrator
status: active
tags: [orchestrator, prd, agent]
---

# Agentic tool refinement lane

## Scope

This lane uses increasingly small language models as diagnostic probes of
Seon's ordinary dynamic function surface. It improves the maintained Seon
source, not an ACME-only fork. ACME is the isolated downstream cluster and
fresh-checkout integration proof; Inspect AI is the only model-evaluation
harness for Seon-native tasks and established benchmarks.

## Invariants

- Work in the shared checkout and use the lane's isolated named ACME cluster.
  Never restart, reset, or benchmark another agent's cluster. The historical
  `codex/acme-agentic-tool-refinement` worktree exists only until its committed
  changes are reconciled into the shared checkout; do not create a successor.
- Use `src-inspect-ai/` plus the pinned `reference-code/inspect-ai/` source.
  Do not create a drive script, gym, scorer stack, or simulation harness.
- The ordinary agent's dynamic context is the experimental surface. Prefer
  changing default requires, namespace ownership, function identity,
  docstring line one, argument names, Malli input/output schemas, and return
  envelopes. Change standing context prose only when it is false or omits
  non-derivable state.
- Current namespace source renders in full. Required namespaces render as
  compact, inert, complete function and schema contracts.
- Every implementation unit starts with a dependency ledger and live ACME
  failure. Read the exact dependency source and probe the critical assumption
  through the repository MCP boundary before editing.
- Preserve upstream tasks, datasets, and scorers. Freeze membership before
  tuning, classify every failure, and rerun exact failures plus the frozen
  development slice.
- Commit coherent gains frequently. Every verified defect has one note under
  `docs/seon/issues/`.

## Current checkpoint

The immediate resumption authority is [[roadmap#Resumption packet —
2026-07-15]]. Resume at its **Exact next order**, not at an interesting open
issue.

The single active contract is bounded proof of what the model and database
actually did. Ordered per-eval database-operation observations and per-attempt
model transport facts are persisted, projected from the final immutable
request snapshot, and consumed through fail-closed Inspect admission. The
source checkpoint proves the display cap is read once from that frozen
database, adapter identity is re-derived from the attempt coordinate, and
stream mode is re-derived from the linked turn's rendered coordinate. Its
focused config/web/retry gate passes 40 tests and 218 assertions.

P0 membership, native wrappers, source locks, request-scoped evidence,
database-owned deadlines, effective-timeout evidence, and interrupted native-
log retention are committed. P0b has not run. Shared runtime source is dirty
under other owners and the live ACME target is degraded after hot reload; do
not restart it or stage those paths. Finish and commit the focused evidence
boundary, then wait for the runtime lane's explicit handoff of one coordinated
Datahike checkout/gitlink/dependency/artifact-manifest coordinate. Restart only
ACME after that handoff, require ready status, then run exactly
`database_workflow-seed1-000` and read its finalized native log back.

The current handoff is withheld because a freshness audit rejected the interim
Datahike candidate: upstream delete/GC fixes require Konserve 0.9.357 or newer,
and the maintained compatibility patch is being forward-ported to 0.9.359.
Never pin or rebuild against the interim candidate from this lane.

The multi-form ordering defect is real but queued for P4, not the active gate:
`:seon.agent.turn/evals` is cardinality-many and its identity datom transaction
orders separate eval transactions, but the model-facing contract for several
forms emitted in one reply still needs an explicit durable execution-position
decision. See
[[../../seon/issues/multi-form-eval-order-is-not-durable]] and
[[research/inspect-batch-stream-cancellation-2026-07-15]]. Do not edit eval,
projection, or solver code *for this queued multi-form defect* until the first
admitted sample is recorded unless that sample is invalidated by the defect.

Do not broaden into the remaining nine samples, model selection, shared-schema
rendering, parser changes, streaming transport, or the operator lease before
the active action. The clean admitted Qwen2.5 Coder 0.5B run remains the exact
before-state for the post-contract replay; earlier dirty direct invocations are
diagnostic only.
