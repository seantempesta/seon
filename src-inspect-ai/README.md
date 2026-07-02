# seon-inspect — Seon benchmarks on the inspect-ai standard harness

Core, maintained code (owner directive 2026-07-02): the `/solve` pod solver and
the diffusion measurement tasks live HERE, not in a PRD dir. The Phase-0 spike
(`docs/prds/agent-fsm/research/inspect-bridge-spike/`) is history; future bench
work goes in this package.

**The shape (Option B — Seon owns the loop):** inspect supplies the dataset +
host-side scorer; the Seon pod agent is a custom `@solver` behind
`POST /solve` (its own FSM runs every turn, inspect never manages one), and
diffusion-worker tasks call the RunPod worker the same way. Every generation
scores through the REAL Seon oracles — the bb parse/structural/phase server
(`bin/oracle-server`) and the node cljs.js eval bundle
(`out/worker-oracle-eval/main.js`) — behind a fail-loud **oracle-liveness
gate**: a golden known-good must score faithful AND a golden known-bad
(def-vs-defn) must FAIL the eval tier before any task constructs. That gate
exists because a dead eval bundle silently voided a GPU run once
(`docs/prds/diffusion-dynamic-context/research/e1-behavioral-zero-audit-2026-07-02.md`).

## Setup (one command)

```bash
cd src-inspect-ai
uv venv && uv pip install -e ".[test]"     # or: python -m venv .venv && .venv/bin/pip install -e ".[test]"
```

`inspect-ai` installs from the vendored source `reference-code/inspect-ai`
(`[tool.uv.sources]`) — the exact dev build everything here was proven against
(`0.1.dev1+g92dd737b9`; verified on python 3.12 and 3.14). The node eval bundle
must exist: `clj -M:cljs compile worker-oracle-eval` (the liveness gate tells
you loudly when it doesn't). `bb` must be on PATH.

## Layout

```
src/seon_inspect/
  solver.py              seon_pod_solver (/solve) + timeout_honesty scorer
  oracle_scorers.py      persistent bb+node oracle servers, score_code tier
                         ladder, ladder_scorer, assert_oracle_live
  worker_endpoints.py    mock:<scenario> | runpod endpoint resolution
  worker_mock.py         canned offline worker (REAL Clojure fixtures)
  offline_proof.py       python -m seon_inspect.offline_proof
  tasks/
    e1_spec_fn.py        E1 three-arm (runbook step 3)
    skill_lift.py        north-star skill A/B
    ladder_lift.py       ladder ON/OFF (runbook step 4)
tests/                   the offline proofs as the pytest regression suite
datasets/                (future .jsonl datasets live here, not under docs/)
```

## Run matrix

| Target | Command | Needs |
|---|---|---|
| Offline proof (all tasks, canned worker, REAL oracles) | `.venv/bin/python -m seon_inspect.offline_proof` | bb + the node bundle |
| Test suite | `.venv/bin/pytest` | same |
| One task offline | `.venv/bin/inspect eval src/seon_inspect/tasks/e1_spec_fn.py@e1_spec_fn -T arm=arm1_guided_refine -T endpoint=mock:guided_wins --model mockllm/model --display plain` | same |
| Pod-backed (acme) | `SEON_SOLVE_URL=http://127.0.0.1:7980/solve .venv/bin/inspect eval <task> --model mockllm/model --max-samples 1` | acme pod + DeepSeek key |
| GPU worker | add `-T endpoint=runpod` + env `DIFFGEMMA_EP`/`RUNPOD_API_KEY`, `--max-samples 1` | deployed worker, `verify_fresh` FIRST (runbook step 0) |

`--model mockllm/model` satisfies eval()'s model requirement and is never
called — the solvers set completions themselves.

## Scoring: report BOTH reducers

Every task runs `Epochs(k, ["mean", pass_at(k)])`. `mean` is the RATE the E1
decision rule compares (Δ arm1−arm3 ≥ 0.10 EARNS); `pass_at_k` (at-least-one)
is the noise-robustness view and ALONE it hides arm differences — offline,
arm2's pass_at_4 is 1.000 while its rate is 0.250. Caveat: per-epoch variation
keys off `TaskState.epoch`; if a future inspect version renames it the solvers
fall back to epoch 1 (`getattr(state, "epoch", 1)`), which collapses mock
pools to one fixture — the tests would catch it (arm means shift).

## Offline proof (captured 2026-07-02, from THIS layout)

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

The mock encodes the live 06-29 pools + the audit's fixture classes — these
deltas prove the harness DISCRIMINATES; the model's numbers come from the GPU.
pytest: 15 passed (scorer-vs-oracles tier table incl. the transducer
false-positive caught by behavioral-not-eval; liveness-gate loud abort;
task-wiring means). Engineering note: inspect runs epochs CONCURRENTLY — the
oracle line-servers serialize request/response pairs with a lock, and metrics
are read from the eval LOGS, never scraped from stdout.

## Parity map (the gym retires at parity)

| This task | Replaces | Notes |
|---|---|---|
| `e1_spec_fn` (3 arms) | `tmp/flash-diffgemma/e1_kill_gate.py` scorecard | same prompts / tiers / liveness gate; verdict = compare arm runs' `mean` |
| `skill_lift` | `tmp/flash-diffgemma/skill_lift.py` + `score_ab.py` | ledger rows → `LEDGER` entries |
| `ladder_lift` | `battery.py` refine_loop scoring | exp D's sweep grid stays in `battery.py` (knob sweep, not a benchmark) |
| memory/QA benches | spike dir (already inspect) | migrate into `tasks/` as they're next touched |

`bin/acme gym-diffusion` retires once these tasks produce one real GPU
scorecard each; the gym's agent scenarios are the agent-fsm lane's parity call.

## Live-validation checklist (pending)

- [ ] Pod smoke via acme `/solve` (7980) with DeepSeek — the pod-backed row of
      the run matrix, composing `seon_pod_solver` with `ladder_scorer`.
- [ ] GPU E1 re-run (runbook step 3): three `e1_spec_fn` runs,
      `-T endpoint=runpod`, epochs≥4, `--max-samples 1` (~$0.50).
- [ ] GPU ladder-lift (runbook step 4) on the co-location image.
- [ ] A tiny verdict reader over two E1 eval logs (Δ + EARNS/KILL/MARGINAL).
