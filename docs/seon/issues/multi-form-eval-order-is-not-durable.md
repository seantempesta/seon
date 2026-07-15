---
type: issue
status: open
severity: friction
tags: [issue, agent, database, flow]
---

# Make multi-form eval order a durable database fact

## Problem

One model reply may contain several executable forms, but the database cannot
currently reproduce their execution order. `seon.eval/eval-batch!` returns an
ordered vector of eval ids to its caller, while the turn connects those evals
through cardinality-many `:seon.agent.turn/evals`.

The current pod projection has improved since this issue was opened: it emits
the owning turn id and orders rows by the transaction that asserted each
`:seon.eval/id`. Because `record-eval!` commits one eval per transaction through
the serialized writer, that transaction order is exact for the committed rows.
It is still an implicit property of the present write topology, not a
contiguous per-turn execution fact. It cannot prove that no attempted eval row
was lost between two committed rows, and consumers cannot validate the
multi-form contract without reconstructing it from transaction history.

The shortest live falsifier at ACME database coordinate
`{database-id 6813d1c2-4feb-3272-9b74-4c6769142514, branch db,
commit-id 6a57c6e2-46f6-5ed8-bbfb-95952847b8c1, t 536872999}` found turn
`a0e6yobirv1b` with two component evals committed at transactions `536872727`
and `536872728`. Neither `:seon.eval/position` nor a separate
`:seon.eval/turn` attribute is installed. The component edge already is the
turn fact; only the ordinal is missing.

## Dependency ledger

- Seon owners: `src/seon/eval.cljs` owns sequential execution and eval
  persistence; `src/seon/agent/turn.cljs` owns the component connection;
  `src/seon/web/serve.cljs` owns the external projection; and
  `src/seon/agent/ctx/transcript.cljs` consumes per-turn order.
- Inspect owner: `src-inspect-ai/src/seon_inspect/solver.py`.
- Existing behavior: `seon.eval/eval-batch!` preserves process-local id order;
  `:seon.agent.turn/evals` preserves membership but not order; the pod projects
  turn identity plus eval-identity transaction order; and the solver retains
  the projected `eval_evidence` vector unchanged.
- Grounding source: pinned Inspect AI multi-call tests under
  `reference-code/inspect-ai/tests/model/test_parallel_tools.py`,
  `tests/tools/test_call_tools.py`, and `tests/model/test_generate_loop.py`.
  Seon evals remain Seon database evidence and are not synthesized as Inspect
  tool calls.

## One-mechanism repair

Strengthen `seon.eval/eval-batch!` and `record-eval!` in place:

- Register one optional historical attribute, `:seon.eval/position`, as a
  non-negative integer and include it in the existing `:seon.eval` entity
  schema and boot schema corpus. Do not add `:seon.eval/turn`: the
  `:seon.agent.turn/evals` component datom is the one connection and already
  supplies exact owning-turn identity.
- Extend the existing `eval-batch!` fold with a zero-based next-position
  accumulator. Consume one position immediately before each eval-record
  attempt, including read failures, comment rows, parity forms, prose
  demotions, and every entry produced when one repaired parser span expands
  into several evals. Parser-entry index is therefore not a substitute.
- Pass the allocated position through the existing dispatch map into
  `record-eval!`. Freeze it in `stable-eval-row`, outside the id allocator, so
  identity-collision retries and the transcript-first no-tee fallback reuse
  the same position. Advance the fold even when a row cannot be persisted;
  any later committed row then exposes a gap instead of concealing data loss.
- Leave old rows absent. Backfilling from timestamps, random ids, or current
  transaction order would claim knowledge the database did not record.
  Historical UI rendering may use its existing stable fallback, but admitted
  new runs fail closed when any position is absent.
- Project every selected eval row, including malformed rows with an absent
  position. Never make `:seon.eval/position` a required Datalog clause that
  silently drops the evidence the admission check needs to reject. For new
  rows, order by the request's already ordered turn vector and then stored
  position; retain `eval_transaction` as provenance, not as the ordinal.
- Add one Inspect admission validator beside the existing model-transport
  validator. It accepts unique eval ids whose turn ids are members of the
  exact ordered `pod_turn_evidence`, whose positions are integers, and whose
  per-turn positions are exactly `0..n-1` in vector order. Missing, duplicate,
  foreign-turn, gapped, or reordered evidence raises
  `PodRunInfrastructureError` before any capability scorer.

Per-turn transcript consumers must share the same position-first ordering
function rather than each retaining a timestamp sort. The concrete consumers
are `seon.agent.ctx.transcript/eval-events`,
`seon.agent.ctx/session-evals`, and the autocomplete turn export. Global recent
and frequency views may keep their creation/recency order because they do not
reconstruct one reply's authored sequence. The future running-eval receipt in
`seon.eval.internal/start-tx-data` must accept the same position before that
path becomes a production writer; it must not mint a second ordering scheme.

## Focused proof

- Eval/database: one parsed reply with success, failure, and success records
  positions `0,1,2`, the third executes, each identity/position/component edge
  shares its eval transaction, and id-allocation retry does not re-execute or
  renumber the form.
- Repair expansion: one malformed parser entry repaired into multiple entries
  consumes consecutive eval positions, proving source-span index is not the
  contract.
- Projection: shuffled rows with equal timestamps and adversarial ids project
  in turn/position order; an absent position remains visible rather than being
  filtered out.
- Inspect: mutation cases for missing position, duplicate eval id, duplicate
  position, gap, foreign turn, and vector reordering all fail before scoring.
- Live read-back: one fake-provider reply containing three forms, with a
  failure between independent successes, yields exactly three component rows
  at `0,1,2`; the next prompt renders those results in the same order while the
  captured assistant reply remains byte-identical and contains no injected
  values.

## Acceptance criteria

- Each new eval is connected to its originating turn by the existing component
  fact and records a contiguous execution position; absence remains meaningful
  for historical rows whose order is unknown.
- The pod projection emits unique eval identities, turn identities, and exact
  positions in execution order from one immutable database value.
- The Inspect solver preserves that vector and rejects missing, duplicate,
  foreign-turn, or non-contiguous evidence as infrastructure failure rather
  than a model score.
- A focused live proof executes three forms from one reply, records exactly
  three ordered eval rows, and leaves execution results out of the model-
  authored assistant reply.

## Scheduling

This belongs to P4 of [[../../prds/agentic-tool-refinement/roadmap]] after the
first accepted P0b serial slice. It must not displace the current clean-artifact
and admitted-sample gate unless that sample is directly invalidated by missing
order evidence.

Durable order itself does not depend on transport cancellation and can land
first. The later batch/stream comparison still depends on addressable
cancellation: Inspect currently cancels its task while
`anyio.to_thread.run_sync` remains blocked in `urllib`, and the pod run/provider
continues. `sample_active().interrupt(...)` supplies native operator accounting;
it does not cancel the HTTP request. The cancellation fixture is admissible
only when one addressed request can close the canonical run fence and propagate
to the provider's owned abort signal.

A 2026-07-15 pod-death proof audit found a sharper persistence boundary.
`seon.eval.internal/start-tx-data` and `terminal-tx-data` already describe a
running receipt, and recovery can interrupt a durable running eval, but
production `seon.eval/eval-form-entry!` still allocates and records only after
execution and auto-await settle. A killed unresolved middle form therefore has
no eval identity to mark interrupted. Current source can prove a committed
prefix, logged middle start, absent middle/suffix eval and result rows, crashed
run, interrupted turn, and one recovery anchor without fabrication. The
stronger acceptance claim requires integrating the existing start receipt into
the one per-form path before execution and terminal-CASing that same row after
settlement, using the same future canonical position rather than another
ordering scheme.
