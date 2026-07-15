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
through cardinality-many `:seon.agent.turn/evals`. Projected Inspect evidence
sorts evals by timestamp and id and omits both the originating turn identity
and a per-turn execution position.

That projection is deterministic but is not execution truth. Equal timestamps
or random identity order can change the reconstructed sequence, so an Inspect
sample cannot prove that N authored forms executed once, in order, and became
visible on the next turn.

## Dependency ledger

- Seon owners: `src/seon/eval.cljs`, `src/seon/agent/turn.cljs`, and
  `src/seon/web/serve.cljs`.
- Inspect owner: `src-inspect-ai/src/seon_inspect/solver.py`.
- Existing behavior: `seon.eval/eval-batch!` preserves process-local id order;
  `:seon.agent.turn/evals` preserves membership but not order; the run response
  retains the projected `eval_evidence` vector unchanged.
- Grounding source: pinned Inspect AI multi-call tests under
  `reference-code/inspect-ai/tests/model/test_parallel_tools.py`,
  `tests/tools/test_call_tools.py`, and `tests/model/test_generate_loop.py`.
  Seon evals remain Seon database evidence and are not synthesized as Inspect
  tool calls.

## Acceptance criteria

- Each eval created from a multi-form reply records its originating turn and a
  contiguous execution position as event facts; absence remains meaningful for
  historical rows whose order is unknown.
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
