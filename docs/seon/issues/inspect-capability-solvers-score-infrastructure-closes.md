---
type: issue
status: open
severity: blocker
tags: [issue, agent, research, flow]
---

# Reject infrastructure closes in every Inspect capability task

## Problem

The shared static-pod solver records timeout and close-reason evidence but does
not apply the existing scorable-state guard. Standard pod-backed benchmarks can
therefore pass a timeout, `:error`, or `:quiesced` infrastructure close to an
upstream capability scorer. A score produced from that state can enter the
scorecard as model capability evidence even though the model never received a
valid execution opportunity.

This blocks accepted Unit 7 capability numbers. Recent source/run admission,
closing target identity, generated-bytecode, and native failure-classification
commits harden the harness and retained evidence; they do not establish the
frozen ordinary-work or planner/worker graduation matrix while the common
solver path can score infrastructure failure.

## Evidence

- `src-inspect-ai/src/seon_inspect/solver.py` defines
  `require_scorable_pod_state`, which raises `PodRunInfrastructureError` for a
  pod timeout and close reasons `:error` and `:quiesced`.
- The same file's `seon_pod_solver` returns `_record_result(state, result)`
  directly. It records the relevant fields but never calls the guard.
- `src-inspect-ai/src/seon_inspect/catalog.py` selects `seon_pod_solver` for the
  ordinary static-cluster `run_bench` path and passes it to the task adapter.
- `src-inspect-ai/src/seon_inspect/bfcl_adapter.py` constructs
  `[bfcl_prompt(), pod_solver, bfcl_parse()]`; no scorable-state check appears
  between the pod result and upstream parsing/scoring.
- Only the bespoke frozen-tool-row and milestone-lift tasks call the guard.
  Focused helper tests prove the guard itself, not its use by every capability
  task.
- `src-inspect-ai/src/seon_inspect/scorecard.py` classifies pod timeouts and
  `:error` closes as infrastructure outcomes, but does not classify
  `:quiesced`. A quiesced sample falls through to the first scorer value and is
  reduced to pass or fail.
- [[acme-typeahead-worker-unavailable]] currently says the shared boundary
  rejects timeout and `:error` closes. That statement is true only for task
  paths that explicitly apply the guard, not for the common static-pod solver.

## Owner

The one Inspect pod-solver and admitted score-reduction boundary under
`src-inspect-ai/src/seon_inspect/solver.py`, the catalog adapter chain, and
`scorecard.py`. Capability tasks should share one guarded solver composition.
Diagnostic tasks such as timeout-honesty may intentionally consume raw failure
states, but must opt into a distinctly named diagnostic path rather than
weakening the capability default.

## Acceptance

- Every catalog capability adapter applies the scorable-state guard after the
  pod result is recorded and before task parsing or scoring; BFCL and an
  ordinary frozen task have direct composition tests.
- Timeout, `:error`, and `:quiesced` fixtures produce an errored/unscored native
  Inspect sample and cannot produce a capability pass or fail.
- The shared scorecard reducer independently classifies all three conditions as
  infrastructure evidence, so importing an older or externally produced native
  log cannot turn a quiesced sample into a model failure.
- The timeout-honesty diagnostic still observes and scores the raw timeout
  contract through an explicit diagnostic-only solver path.
- One admitted static-pod run injects each infrastructure terminal state and
  proves that no capability number is published, while a completed control run
  reaches the unchanged upstream scorer.
