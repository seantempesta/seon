---
type: research
status: active
tags: [research, eval, inspect-ai, model, agent]
---

# BFCL Qwen 3.5 2B unchanged baseline — 2026-07-14

## Run identity

Inspect AI `0.3.246` ran `inspect_evals/bfcl` task version `5-B` from
`inspect_evals` `0.14.3`. The outer Inspect model is intentionally
`mockllm/model`; `seon_pod_solver` delegates generation to the live Seon pod at
`http://127.0.0.1:8094/agents/run`. Every sample records the actual pod model as
`Qwen/Qwen3.5-2B` through the `openai-compat` provider, temperature `0.7`,
1,024 output tokens, and thinking disabled.

The unchanged slice freezes ten BFCL sample ids across `simple_python`,
`multiple`, `parallel`, and `parallel_multiple`. Inspect records source commit
`c0d0eecf`, clean, and one epoch. The raw authoritative log is preserved at
`evals/runs/2026-07-14-bfcl-qwen35-2b-unchanged/inspect-logs/`.

## Result

All ten samples completed without infrastructure errors in 11 minutes and 3
seconds. BFCL accuracy was `0.0` overall and in every category. This is not yet
a function-selection comparison: every sample produced zero BFCL calls and
ended with `:seon.agent.run/closed-reason :no-forms` after three to seven pod
turns. Recorded eval counts ranged from zero to twelve, and every parser result
was `no call-shaped JSON in reply`.

The clean signal is therefore earlier in the agent loop than scorer
correctness. Before tuning namespace descriptions or task prose, inspect the
exact rendered namespace/tool contracts, model outputs, repaired parser
entries, and transcript that led each run to its no-forms streak. The frozen
sample membership and raw log remain the regression input after each general
parser/context/tool-surface fix.
