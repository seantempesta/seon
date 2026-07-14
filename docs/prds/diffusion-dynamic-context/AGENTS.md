---
type: orchestrator
status: active
tags: [orchestrator, prd, agent, flow]
---

# Diffusion and dynamic context — localized index

## Current state

As of 2026-07-14, the optional DiffusionGemma/typeahead work is paused behind
the runtime-reliability refactor. The local MLX worker and measured oracle loop
remain useful research, but the default provider is DeepSeek and no diffusion
provider is activated as a side effect. ACME and `src-diffusion/` may be owned
by another lane; do not operate or rewrite them without coordination.

## Durable findings

- The oracle/eval loop produced the value; generation steering must earn its
  complexity through measured behavioral lift.
- Context selection and prefill latency dominate the small worker. Report
  performance in estimated tokens and tokens/second.
- `my.plan` is the sole durable plan surface. Do not restore plan-ledger,
  markdown reconciliation, or another planner representation.
- Optional suggestions never become a required correctness path.
- Seon-side vocabulary changes stop at adapter boundaries; upstream model
  field names are not renamed.
- Agent/model evaluation belongs in `src-inspect-ai/`; do not restore the gym
  or add a bespoke drive harness.

## Runbook boundary

The worker is a separate optional process selected by `SEON_DG_ENDPOINT` and
`SEON_AI_PROVIDER`. Read the current worker repository/runbook before operating
it and verify the reported worker source revision. The runtime-reliability
branch's default cluster is the proof target; leave ACME alone while shared.

## Entry points

- `planner-worker-design.md` — planner/worker design and measurements.
- `typeahead-design.md` — optional completion surface and oracle loop.
- `roadmap.md` — dated experiment history, not universal runtime truth.
- `docs/prds/runtime-reliability/roadmap.md` — current integration status.
