---
type: prd
status: active
tags: [prd, agent]
---

# Eval suite — dev / milestone / test

> One standing measurement: **how good and how stable** is the Seon agent
> system, per capability, over time. A frozen dev sample runs constantly; a
> frozen milestone sample keeps us honest; a blind test reserve stays unseen.
> Everything runs through the general inspect-ai harness (`src-inspect-ai/`,
> pod-agnostic `/solve`) — established benchmarks where they exist, bespoke
> oracle-scored tasks only where none does. Correctness gates correctness;
> idiom/style is reported, never gated.

## The three tiers

| Tier | Frozen? | Visibility | Run when |
|---|---|---|---|
| **dev** | yes — seed 1 slice | per-sample, iterate freely | every merge / nightly |
| **milestone** | yes — seed 2, disjoint | aggregate metrics ONLY, never per-sample | releases, major tool landings |
| **test** | blind reserve: the rest of each bench + fresh-seed generations | nobody, until a formal eval | rarely; refreshes milestone when stale |

Sampling: per source, shuffle under one recorded global seed → first N = dev,
next N = milestone, rest = test. Stratify where labels exist (MMLU subjects,
ARC difficulty). Bespoke generated tasks: seed 1 = dev, seed 2 = milestone,
fresh seed per draw = test — the generator + difficulty distribution is what's
frozen, so test instances are contamination-proof by construction. A
`datasets.lock` records every seed + sample id. Canary GUIDs in every bespoke
dataset; CI greps skills/context/config for them (a hit = answer-shaped
context, fail loud).

## Capability rows (source · dev count · epochs)

Two cost classes: QA (~1–2 turns) and agentic (multi-turn, 30–300s observed).
Stability matters most on agentic rows → more epochs there.

| Row | Source | dev N | epochs | Notes |
|---|---|---|---|---|
| reasoning | gsm8k (external) | 15 | 2 | proven live 2/2 |
| science QA | arc_challenge (external) | 15 | 2 | |
| knowledge | mmlu (external, subject-stratified) | 15 | 2 | |
| hard calibration | gpqa_diamond (external) | 10 | 2 | small + hard |
| memory store→recall | bespoke generator | 10 | 4 | goal-stated, no verb coaching |
| long-term planning | bespoke generator (plan → pod restart → resume) | 10 | 4 | headline row; ISOLATED cluster (it restarts the pod) |
| Clojure codegen w/ specs | bespoke generator, oracle-scored | 10 | 4 | idiom reported, never gated |
| shell use | bespoke generator | 8 | 4 | needs `SEON_SHELL` grant in bench env |
| web fetch | bespoke generator vs LOCAL fixture server | 8 | 4 | needs `SEON_WEB` grant; fixtures keep dev hermetic |
| file edit | bespoke generator | 8 | 4 | tool shipped 2026-07-02, zero coverage yet |
| UI/tiles (optional) | bespoke, scored on the `:seon.render/ai` line | 5 | 2 | machine-scorable; observer depth stays manual |

≈ 110 QA runs + ~230 agentic runs per dev pass.

## Metrics — good AND stable, separately

Per row per run: **mean**, **pass@k** (can it ever), **pass^k** (does it
always) — the pass@k↔pass^k gap IS the stability number — plus **flake_rate**
with flakes classified by the taxonomy below and EXCLUDED from capability
means (a timeout is not a wrong answer; conflating them poisons the trend).

Flake taxonomy (survey-grounded, `research/tool-surface-survey-2026-07-02.md`
§5): solve-latency variance (51→300s; per-sample timeout ≥ 3× row median) ·
fresh-world empty renders · tx-feed wire-rpc timeouts (recur beyond boot,
self-heal ~2s) · stale-bundle races (supervisor-guarded since `45429044`) ·
provider-stub churn / mislabeled boot log · ambient-state coupling ·
eval-sandbox skew (no `require` in the node oracle — prompts state it) ·
inspect-ai version-pin skew.

Every run appends one row per capability to `evals/scorecard.jsonl` (ledger =
data, committed): `{row, tier, mean, pass_at_k, pass_hat_k, flake_rate,
n, k, git_sha, datasets_lock_sha, elapsed_s}`. Regression alarm (proposal):
pass^k drop > 0.10 on any row vs its 7-run median → investigate before merge.

## Execution — parallel by construction

- **Within a row:** inspect `max_connections`; each sample is its own pod
  agent. Pod-side concurrency (2→4→8) is calibrated once before first use.
- **Across rows: disposable clusters** (the `bin/acme` pattern; harness is
  pod-agnostic via `SEON_SOLVE_URL`). Row groups: QA-shared cluster ·
  memory/codegen cluster · planning cluster (ISOLATED — it restarts its pod
  by design) · tools cluster (shell/web/file-edit grants set HERE only).
  Fresh `cluster reset` before every pass (supervisor auto-rebuilds).
- Target wall-clock: **≤ 30 min** dev pass at parallelism ~16, ~$1 DeepSeek.
- **Calibration run** (once, before the first real pass): measure pod
  concurrency ceiling, per-row latency medians (sets timeouts), DeepSeek rate
  ceiling. Re-run after major runtime changes.

## Preconditions before the first dev pass (survey-grounded)

1. Grant `SEON_SHELL` + `SEON_WEB` in the bench clusters' env (default-deny
   everywhere today — shell/web rows are untestable without this).
2. Re-ground the planning bench on the REDESIGNED `my.plan` (deps/pace/expect,
   `1cda2948`, never re-driven; the spike bench references pre-rename verbs).
3. Author the tool-row generators (shell / web-fixture / file-edit) — goal-
   stated, oracle- or artifact-scored.
4. Fix or fence the open flake sources: fresh-world `my.kb` empty render +
   turn-6 recall visibility (agent-fsm lane, flagged in coordination).
5. Calibration run (above).

## What this replaces

The gym scorecards (already at inspect parity for diffusion tasks) and ad-hoc
drive scripts. The diffusion measurement plan (tabled 2026-07-02) plugs into
the same rows later: point a cluster's provider row at the worker and re-run —
same datasets, same scorers, different model.

## Pointers

- [[research/tool-surface-survey-2026-07-02]] — what's wired, per-row
  readiness, the flake taxonomy (this design's ground truth).
- `src-inspect-ai/README.md` — the harness run matrix + scoring philosophy.
- `docs/prds/diffusion-dynamic-context/research/deepseek-preflight-drives-2026-07-02.md`
  — the preflight discipline this generalizes.
