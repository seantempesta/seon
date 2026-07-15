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

Read [[roadmap]] and the current unit's file under `research/`. The initial
ordinary-agent baseline is 21,839 estimated tokens for the namespaces block
and under 700 tokens for all other rendered blocks combined. The namespace
surface is structurally complete but has not yet been audited for necessary
contracts, repeated referenced schemas, internal callables, or task coverage.
