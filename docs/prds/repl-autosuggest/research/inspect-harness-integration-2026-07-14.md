---
type: research
status: active
tags: [research, agent]
---

# Inspect harness integration — stable-lane findings and plan arms

## What was integrated

The stable and retired-gym worktrees were reviewed against the canonical
runtime-reliability branch. The stable lane's implementation commits for
provider-aware SWE-bench egress, the standard OpenAI dependency, and the
first-class `long_term_planning` task were already present. The old gym's
remaining dirty changes only translate predicates from stored agent state and
session facts to the newer run/cause model. They belong to the deleted parallel
evaluator and are not imported. Inspect AI remains the one agent/model harness.

The existing `planning_scorer` now also represents the three measured plan
arms from [[plan-preload-drive-2026-07-12]] without a second evaluator:

- `pretransacted`: the harness supplied the plan before turn one;
- `model_authored`: the model authored the plan; and
- `no_plan`: a control with no plan.

Every arm is gated by a database-derived outcome and a human report carrying
that outcome before an explicitly observed run closure. A report without a
closure event is insufficient evidence. The two plan arms additionally require
every close to have its expectation verified at the close basis and require at
least one address-step observation; an empty observation set cannot pass. The
no-plan control reports plan integrity and address discipline as not applicable
only when a completed database read explicitly proves that no plan root exists.
Offline fixtures preserve the pilot's important negative: plausible reported
`26` fails the database oracle whose correct ferry total is `25`.

The scorer does not accept a caller-supplied plan-source label. Its future live
adapter must derive this exact plain-data record from plan roots, transaction
provenance, and run facts:

```text
{observed: bool,
 observed_at_t: int,
 plan_present: bool,
 first_turn_t: int,
 agent_eid: int,
 harness_plan_tx_ids: [int],
 history_observed: bool,
 run_historical_root_ids: [str],
 run_root_creation_count: int,
 run_root_creation_tx_ids: [int],
 roots: [{id: str,
          creation_t: int,
          creation_tx_id: int,
          creation_user_eid: int}]}
```

`plan_present` must agree with the complete final root rows. The history fields
come from an as-of read at `first_turn_t` plus Datahike history over the
inclusive interval `[first_turn_t, observed_at_t]`:
`run_historical_root_ids` is the union of roots present at the interval start
and roots asserted or retracted through its end, while the creation count and
transaction ids record root assertions in the interval.
Retraction therefore cannot erase evidence that a plan existed. A
pretransacted root's creation transaction must be one returned by the harness
plan transaction and its `creation_t` must precede `first_turn_t` without
exceeding `observed_at_t`. A
model-authored root's creation user must be the driven agent and its coordinate
must be in the inclusive interval `[first_turn_t, observed_at_t]`; every final
root created in that interval must also occur in the history-derived creation
transaction ids. The no-plan control requires empty final roots, zero
historical roots, zero in-run creations, and zero plan closes. Missing
evidence, a hidden or retracted root, unknown provenance, creation after the
observation basis, or incorrect timing fails the arm contract.

Report evidence is an ordered sequence of `{kind: message_user, content: str}`
and `{kind: run_closed}` records. Address evidence is
`{coverage_complete: bool, observations: [...]}`; each database-derived
observation is `{address_active: bool, authored_open: bool}`. Plan arms require
complete coverage and at least one observation. The forbidden state is both
observation fields true.

Focused proof:

```bash
cd src-inspect-ai
.venv/bin/pytest -q tests/test_planning.py tests/test_tasks_offline.py
```

Result on 2026-07-14: 56 passed. The real Inspect task/scorer path reports
pretransacted `1.000`, model-authored `1.000`, and the measured wrong no-plan
fixture `0.000`.

The complete `src-inspect-ai` checkpoint passes 314 tests with eight expected
environment-gated skips.

## Standard Inspect versus Seon-backed runs

The stable lane established three distinct execution modes. They answer
different questions and should not be conflated.

- Standard Inspect mode lets Inspect own the agent loop and routes its native
  shell, Python, editor, or browser tools into the task sandbox. This measures
  the raw model and is the inexpensive path for established coding baselines.
- Pod-backed text-in/text-out mode drives `POST /agents/run`. This measures the
  Seon system but cannot transparently replace Inspect's native tool bridge for
  sandboxed tasks.
- Pod-in-sandbox mode, implemented for the SWE-bench arm, boots Seon inside the
  official task container while retaining the official scorer. This is the
  correct but bench-specific way to measure the Seon runtime on repo-editing
  tasks.

Therefore use standard mode for raw-model `humaneval`, `mbpp`, and later GAIA
baselines; use Seon's `long_term_planning` task for database-backed planning;
and extend pod-in-sandbox only for a funded system-level benchmark question.
The benchmark audit's deterministic-oracle rule still applies: database final
state, executed tests, or exact outcomes outrank narrative plausibility.

## Verified local 35B runbook

The stable lane ran one HumanEval sample end to end in standard Inspect mode
against the local MLX 35B server and the Docker sandbox. Accuracy was `1.000`.
The reusable command is:

```bash
cd src-inspect-ai
export LOCAL_BASE_URL="http://127.0.0.1:8081/v1"
export LOCAL_API_KEY="sk-local-nokey"
.venv/bin/inspect eval inspect_evals/humaneval \
  --model "openai-api/local/mlx-community/Qwen3.6-35B-A3B-4bit-DWQ" \
  --limit 1 --epochs 1 --max-tokens 2048 \
  --display plain --log-dir logs/humaneval-local-smoke
```

Three details are load-bearing:

- `openai` must be installed for standard `openai-api/*` mode; it is already a
  declared project dependency.
- The service prefix reads `LOCAL_BASE_URL`; passing `base_url` again duplicates
  the model argument and fails.
- A thinking model exhausted the default 512-token budget before emitting code;
  use at least 2048 tokens for this local 35B coding smoke.

Docker was installed but stopped during the original audit. Starting Docker
Desktop and waiting for `docker info` was sufficient; no install or repository
change was required.

## Research retained but not imported

- The completed fair-scoring result exists only as dirty stable-worktree
  research plus `src-needle` scratch. Its useful conclusion is preserved here:
  the deterministic staged-world scorer lifted the audited frontier from
  `.264` to `.436`, while the two small-model arms moved down, and its judged
  acceptance cases passed. Importing that implementation would violate the
  reviewed boundary because it depends on the paused training/audit scratch
  lane. Rebuild it later inside the canonical data-quality pipeline.
- [[lora-data-audit-2026-07-12]] remains the data gate: 149 of 557 retained
  pairs hard-failed the live REPL, so text-only or `ok?`-only curation cannot
  certify gold data.
- [[tool-surface-overhaul-2026-07-12]] remains measured evidence for clear
  capability docstrings and honest request schemas. It does not justify a
  second context renderer.
- The untracked [[shared-schema-section-2026-07-13]] report was read but left
  byte-for-byte untouched. Its thresholded shared-section proposal is a future
  context decision, not part of this harness integration.

## Remaining live gates

- Add live adapters that derive the experiment's database outcome, plan-root
  evidence record, close-basis expectation verdicts, report events, and
  address-step observations from the current run/turn/database facts. The pure
  checks and Inspect wiring are ready; this unit intentionally made no cluster
  or paid call.
- Run the three arms at the same wall/form budget. The decisive comparison is
  pretransacted versus no-plan on database outcome and plan integrity; compare
  model-authored versus pretransacted separately to price authoring variance.
- Re-run the local HumanEval smoke only when Docker and the local 35B endpoint
  are deliberately available. It is a raw-model baseline, not evidence for the
  Seon runtime.
