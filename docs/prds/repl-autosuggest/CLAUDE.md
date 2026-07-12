---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# repl-autosuggest — PRD index

**Current state (2026-07-12):** design settled ([[design.md]]) —
general REPL autocomplete (every turn, every domain), not plan-only;
nothing built yet; A1 (projection/exporter) and B1 (MLX port)
launching in parallel. Live status: [[roadmap.md]].

## Settled — do NOT re-litigate

- **Byte-exact contract, no second renderer**: encoder input =
  `seon.repl.autocomplete/context`, a thin wrapper over the pod's ONE
  prompt producer (`seon.ctx/render-context`) with a small
  config-defined block profile + tight caps; pure over a db value,
  used identically at export (as-of `rendered-as-of`) and inference
  (live db). Prompt blobs are debug ground truth, never the training
  input. Stale exports are re-derived, never patched.
- **Scope is the whole REPL surface** (owner, 2026-07-12): suggestions
  at every turn for data modeling, querying, transacting, defns,
  plans. Target = the turn's ok multi-form output; plan markdown →
  `reconcile!` is the flagship case, not the scope.
- **Encoder shape keeps needle's structure**: situation projection in
  the query slot, retrieved program-graph fn/schema cards in the tools
  slot (contrastive head does retrieval).
- **Envelope**: 1024 enc / 512 dec BPE tokens. Keep needle's tokenizer
  until measured token efficiency says otherwise.
- **MLX** for inference AND finetune; tok/s reporting always.
- Code home `src-needle/` (package `seon_needle`, `src-diffusion`
  conventions). Model-quality measurement inside `src-inspect-ai`.
- Reuse shipped mechanisms: `my.plan/reconcile!`, turn capture, eval
  rows, `#code` heredocs, program-graph cards — a parallel version of
  any of these is the violation.

## Runbook

- Needle source: `reference-code/needle/` (submodule). Key files:
  `needle/model/architecture.py`, `model/constrained.py`,
  `training/finetune.py`, `dataset/dataset.py`.
- Stores to mine: `data/clusters/acme` (3.7G), `data/clusters/default`
  (545M). This lane uses the ACME pod (7980/7981) —
  `bin/acme build && bin/acme start wire-server && bin/acme start pod`.
- Shared branch: commit with explicit pathspecs only
  (`docs/prds/repl-autosuggest/`, `src-needle/`, `src/seon/repl/autocomplete*`,
  `src/my/plan*`, `.gitmodules` + `reference-code/needle`).

## Entry points

- [[design.md]] — the contract (projection, target grammar, retrieval,
  data format, serving).
- [[roadmap.md]] — we-are-here + open questions.
- `docs/prds/diffusion-dynamic-context/planner-worker-design.md` — the
  P7 planner/worker design the plan-shape work builds on.
