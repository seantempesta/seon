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
database-owned deadlines, effective-timeout evidence, interrupted native-log
retention, and immutable MLX model-server admission are committed. The model
server snapshot is taken before task construction and after terminal-log
publication; formal scoring joins every request to its exact endpoint and
absolute Hugging Face snapshot, then joins each successful response to its
model and fingerprint. The first formal P0b replay is finalized and reopened
at source `74530d90`. All three identities agree at start and end, and every
successful attempt joins the admitted endpoint, snapshot, response model, and
fingerprint. The model emitted no executable forms, closed `:no-forms`, and
was reviewed as `model reasoning failure` with zero fabrication. This closes
the live transport/admission read-back, but not database-workflow correctness:
the transaction, later query, and answer remain absent.

The lane currently owns a dedicated Qwen2.5 Coder 0.5B listener at PID 36369.
Its exact-path one-token smoke and two consecutive full observer calls agree on
the response fingerprint, process identity, and 289,601,531-byte artifact
manifest. The PID may disappear; a later experiment must reselect and observe
its live identity rather than trusting this run. The exact invocation-local
callback and replay evidence are in
[[research/mlx-live-wiring-audit-2026-07-15]].

The dependency fixes are public and active at Datahike
`9ada755087228e10cfb179fa5779ce227a6ed220`, Konserve
`b5c99bc02a7175652a610324215288b78551801f`, and Proximum
`9846d3e79e1aee48474bc876d3d563d7137209c6`. Their cold public consumer and
root dependency/classpath proofs pass. Default and ACME publish the same
manifest-v4 maintained-dependency vector and normalized writer digest. The
cross-task PATH defect is closed by `74530d90`; exact provider-secret drift
remains intentional process identity.

Invocation-local experiment variables must not invent a `SEON_*` name. The
operator treats that prefix as semantic managed-process configuration, so such
a variable correctly invalidates static-target admission. Pass host-only
selectors such as a model-server PID as ordinary Python arguments or lexical
values.

The multi-form ordering defect is real but queued for P4, not the active gate:
`:seon.agent.turn/evals` is cardinality-many and its identity datom transaction
orders separate eval transactions, but the model-facing contract for several
forms emitted in one reply still needs an explicit durable execution-position
decision. See
[[../../seon/issues/multi-form-eval-order-is-not-durable]] and
[[research/inspect-batch-stream-cancellation-2026-07-15]]. Do not edit eval,
projection, or solver code *for this queued multi-form defect* until the first
admitted sample is recorded unless that sample is invalidated by the defect.

Do not broaden into the remaining suite yet. The next controlled boundary is
the ranked namespace-reachability work: repair explicit root-agent routing in
the existing pod solver, then run the four fixed reachability rows one at a
time before the frozen namespace composition row. Keep the finalized 0.5B
P0b failure as the exact database-workflow baseline; do not tune context prose
from that single failure.
