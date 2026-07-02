---
type: reference
status: active
tags: [reference, agent]
---

# inspect-bench — the diffusion measurements on the standard harness

The GPU session's numbers come from inspect-ai tasks, not ad-hoc scripts: the
E1 three-arm measurement, the skill-lift A/B, and the ladder-lift (runbook
step 4) each run as an inspect `@task` scored through the REAL Seon oracles
(bb `bin/oracle-server` + the node `worker-oracle-eval` bundle) with `mean` +
`pass_at_k` epoch reducers. The oracle-liveness gate (`assert_oracle_live`)
runs at task construction and REFUSES a dead or lenient oracle stack — the
defect that voided the 06-29 E1 run
([[../research/e1-behavioral-zero-audit-2026-07-02]]).

## Files

- `oracle_scorers.py` — persistent bb + node oracle servers (one process per
  eval run, lock-serialized for inspect's concurrent epochs), the ported tier
  scorer (`score_code`: parse → structural → eval → behavioral → vacuity), the
  `ladder_scorer` inspect `@scorer`, and `assert_oracle_live` (golden
  known-good must score faithful AND golden known-bad def-vs-defn must FAIL
  eval — fail-loud both directions).
- `e1_spec_fn_task.py` — E1 as `@task e1_spec_fn(arm, endpoint, epochs)`; the
  three arms port the fixed kill-gate harness (contract-stating prompts, raw
  persistence to `e1_inspect_samples.jsonl`).
- `skill_lift_task.py` — the north-star skill A/B as
  `@task skill_lift(condition, endpoint, epochs)`; extend `LEDGER` with one
  (skill × task × expects) entry per skill.
- `ladder_lift_task.py` — runbook step 4 as
  `@task ladder_lift(ladder, endpoint, epochs)`; worker metrics (stop_tier /
  iters / oracle_ms / tok_per_s / denoise_steps) ride into the eval log.
- `worker_endpoints.py` / `worker_mock.py` — `mock:<scenario>` (offline canned
  worker; REAL Clojure fixtures the oracles score for real) vs `runpod` (the
  live A100; `DIFFGEMMA_EP` + `RUNPOD_API_KEY`, verify_fresh per runbook
  step 0).
- `run_offline_proof.py` — the reproducible wiring proof (below).

Python env: the spike venv —
`docs/prds/agent-fsm/research/inspect-bridge-spike/.venv` (python 3.12 +
inspect-ai from `reference-code/inspect-ai`). The solver for pod-backed runs is
REUSED from `…/inspect-bridge-spike/seon_solver.py` via sys.path, never copied.

## Offline proof (captured 2026-07-02, REAL bb+node oracles, canned worker)

`run_offline_proof.py` output — metrics read from the eval logs:

```text
RUN                          METRICS (from the eval logs)
E1 arm1 guided_refine        mean/accuracy=1.000  pass_at_4/accuracy=1.000
E1 arm2 naked                mean/accuracy=0.250  pass_at_4/accuracy=1.000
E1 arm3 naked+oracle         mean/accuracy=0.250  pass_at_4/accuracy=1.000
skill control                mean/accuracy=0.000  pass_at_4/accuracy=0.000
skill treatment              mean/accuracy=1.000  pass_at_4/accuracy=1.000
ladder ON                    mean/accuracy=1.000  pass_at_4/accuracy=1.000
ladder OFF                   mean/accuracy=0.250  pass_at_4/accuracy=1.000
```

The mock encodes the live 06-29 pools + the audit's fixture classes, so these
deltas prove the harness DISCRIMINATES (arm1 beats arm3 on the guided_wins
fixture; treatment beats control; ladder-ON beats OFF) — they say nothing
about the model. The scorer layer was additionally proven standalone: golden
faithful+behavioral green; naked_plain fails structural; vacuous fails
vacuity; semantic fails eval; the TRANSDUCER live false-positive passes eval
but FAILS behavioral; broken fails parse. The liveness gate aborts loud on a
missing/dead bundle (`SEON_EVAL_BUNDLE=/nonexistent` → RuntimeError).

Report BOTH reducers: `mean` is the rate the E1 decision rule compares;
`pass_at_k` (at-least-one-of-k) is the noise-robustness view — alone it hides
arm differences (arm2 pass_at_4 = 1.000 while its rate is 0.250).

## Run

```bash
VENV=docs/prds/agent-fsm/research/inspect-bridge-spike/.venv
cd docs/prds/diffusion-dynamic-context/inspect-bench

# the whole offline proof:
$VENV/bin/python run_offline_proof.py

# one run, CLI form:
$VENV/bin/inspect eval e1_spec_fn_task.py@e1_spec_fn \
  -T arm=arm1_guided_refine -T endpoint=mock:guided_wins \
  --model mockllm/model --display plain

# GPU (owner, after runbook step 0's verify_fresh → FRESH ✓):
DIFFGEMMA_EP=<ep> RUNPOD_API_KEY=<key> $VENV/bin/inspect eval \
  e1_spec_fn_task.py@e1_spec_fn -T arm=arm1_guided_refine -T endpoint=runpod \
  --model mockllm/model --display plain --max-samples 1
```

`--model mockllm/model` satisfies eval()'s model requirement and is never
called — the solvers set the completion themselves.

## Parity map (the gym retires at parity)

| This task | Replaces | Notes |
|---|---|---|
| `e1_spec_fn` (3 arms) | `tmp/flash-diffgemma/e1_kill_gate.py` scorecard | same prompts/scorer tiers/liveness gate; verdict = compare arm runs' `mean` (Δ arm1−arm3 ≥ 0.10 EARNS) |
| `skill_lift` | `tmp/flash-diffgemma/skill_lift.py` + `score_ab.py` | ledger rows become LEDGER entries; lift = treatment − control `mean` |
| `ladder_lift` | `battery.py` exp scoring for refine_loop arms | exp D's sweep grid stays in `battery.py` (a knob sweep, not a benchmark) |
| pod memory/QA benches | (already inspect) | `…/inspect-bridge-spike/memory_qa_bench.py` etc. |

The gym's diffusion scenarios (`bin/acme gym-diffusion`) retire once these
tasks have produced one real GPU scorecard each; the gym's agent scenarios are
the agent-fsm lane's parity question, not this bench's.

## Live-validation checklist (pending — blocked on pod/GPU access this pass)

- [ ] Pod smoke: `skill_lift`-style generation via `POST /solve` (acme 7980)
      with DeepSeek — proves the pod-backed solver path composes with the
      oracle scorer (reuse `seon_solver.seon_pod_solver`).
- [ ] GPU E1 re-run (runbook step 3): three `e1_spec_fn` runs with
      `-T endpoint=runpod`, `--max-samples 1`, epochs≥4 — the first meaningful
      behavioral numbers (~$0.50).
- [ ] GPU ladder-lift (runbook step 4): `ladder_lift` ON vs OFF on the
      co-location image (bb `op:"refine"` in-container).
- [ ] Wire the E1 verdict line (Δ + EARNS/KILL/MARGINAL) as a tiny reader over
      the two eval logs (today: compare the printed `mean`s).
