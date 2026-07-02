---
type: orchestrator
status: active
tags: [orchestrator, agent]
---

# Eval suite — auto-loaded index

> The standing measurement of how GOOD and how STABLE the Seon agent system
> is, per capability. Three frozen tiers: **dev** (run constantly, iterate
> freely) / **milestone** (aggregate-only, at milestones) / **test** (blind
> reserve, formal evals only). Runs through `src-inspect-ai/` (pod-agnostic
> `/solve`); established benchmarks first, bespoke oracle-scored tasks only
> where no standard bench exists.

## Current state

Designed, not yet running. [[design]] is the spec (tiers, rows, sampling,
metrics, parallel-cluster execution). The tool-surface survey
([[research/tool-surface-survey-2026-07-02]]) grounds per-row readiness and
the 8-class flake taxonomy.

## Blockers before the first dev pass

1. `SEON_SHELL`/`SEON_WEB` grants in bench cluster env (default-deny today).
2. Planning bench re-grounded on the redesigned `my.plan` (deps/pace/expect).
3. Tool-row generators (shell / web-fixture / file-edit) authored.
4. Fresh-world `my.kb` empty render + turn-6 recall visibility (agent-fsm
   lane, flagged in their coordination.md).
5. One calibration run (pod concurrency, latency medians → timeouts).

## Settled — do not re-litigate

- Tier names dev/milestone/test (owner). Milestone is aggregate-only.
- Correctness gates correctness; idiom/style reported, never gated (owner:
  "preferred doesn't mean write gating tests on it").
- Established benches over homemade; bespoke only where no standard bench
  measures it (plan-survives-restart has NO public equivalent — stays ours).
- Flakes are classified + excluded from capability means, never conflated.
- Long-term planning is the headline capability row.
- Bench utility is pod-agnostic; grants/endpoints are cluster config.

## How to run (once live)

See `src-inspect-ai/README.md` (run matrix). Scorecard ledger:
`evals/scorecard.jsonl` — one row per capability per run.
