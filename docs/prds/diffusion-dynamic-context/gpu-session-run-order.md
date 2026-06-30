---
type: orchestrator
status: active
tags: [orchestrator, diffusion, agent]
---

# GPU-session run-order — the ready-to-fire battery

> The dense, fire-the-moment-it's-warm checklist. A master runner
> (`tmp/flash-diffgemma/battery.py`, gitignored) wraps the ordered STOCK-WORKER
> experiments — each `verify_fresh`-gated, each emitting a scorecard line
> (`{experiment, worker_sha, params, result, win?, gen_s, tok_per_s, cost_est}`)
> to `tmp/flash-diffgemma/battery_scorecard.jsonl` + a console table. Parameterized
> so a single experiment or the whole battery re-runs with tweaked knobs. This is
> the operational companion to [[owner-gpu-runbook]] (the depth lives there).

## Discipline — the non-negotiables (top of every session)

- **NOTHING is measured before `verify_fresh` prints FRESH.** A `flash deploy` does
  NOT recycle a WARM worker; old code serves indefinitely. The battery refuses to
  run unless `worker_sha == local_sha` (current local: `63c09bebadad`). Force-recycle
  with `flash undeploy diffgemma --force && flash deploy`, or bump `FLASH_GPU_IMAGE`
  to a new tag (structural → server-side recreation, **preserves the EP id**).
- **Cost:** scale-to-zero (`workers=(0,1)`) = **$0 idle**, ~66 s cold reload per batch.
  Keep-warm (min worker 1) = **~$1.19/hr** continuous A100 — owner's call once
  iterating. Do NOT set `(1,1)` and walk away. `cost_est` per scorecard line =
  `wall_seconds × $1.19/3600`.
- **A knob change is a MOVED number, not an anecdote** — every line carries
  `worker_sha` + `params`, scenario × sha.

## The exact sequence (once the A100 is warm)

```bash
cd tmp/flash-diffgemma
set -a; . ./.env; set +a                                   # RUNPOD_API_KEY, HF_TOKEN
.venv/bin/flash undeploy diffgemma --force && .venv/bin/flash deploy
export DIFFGEMMA_EP=<ep-from-deploy-output>                # or write it to diffgemma_ep.txt
python3 verify_fresh.py                                     # MUST print FRESH ✓
python3 battery.py --all                                    # the whole ordered battery
#   …or iterate one experiment:
python3 battery.py 1 --param max_length=768                 # tweak + re-run
python3 battery.py 4 --param K=16,20,24,28                  # sweep
python3 battery.py kill_gate --param n=8                    # more samples on the verdict
python3 battery.py --list                                   # the ordered plan + tunable params
python3 battery.py --selfcheck                              # NO GPU — offline plumbing proof
python3 battery.py --dry-run --all                          # NO GPU — print the payload plan
```

`battery.py` resolves the endpoint from `$DIFFGEMMA_EP` or `diffgemma_ep.txt`,
gates once on `verify_fresh`, then runs the chosen experiments and prints a
scorecard table + total cost. Experiment ids accept aliases (`1`/`batched`,
`7`/`kill_gate`/`e1`, …).

## The ordered battery — cheapest decisive probe first

| # | experiment | exact command | win condition | rough cost | tune (`--param`) |
|---|---|---|---|---|---|
| 0 | **fresh + baseline** | `python3 battery.py 0` | introspect returns `canvas_length` + a baseline `tok_per_s` | ~1 drive (~$0.02) | `max_new_tokens` |
| 1 | **batched_mm probe** | `python3 battery.py 1` | `find_spec` break GONE **and** compiled-batched steady `tok_per_s` > eager (strong: ≥1.8×); fail = OOM or `≤ eager` | eager×2 + reload+compiled×3 (~$0.10) | `max_length` |
| 2 | **canvas-length** | `python3 battery.py 2` | scaffold frame fits `canvas_length` (256) **and** every clamp/infill span boundary lands on a token edge | 1 introspect + tokenizer-only (~$0.01) | `fn_name`, `intent` |
| 3 | **primitives** | `python3 battery.py 3` | `clamp_smoke.all_held` **and** infill `prefix_held` ∧ `suffix_held` | 2 drives (~$0.04) | — |
| 4 | **closed_loop** | `python3 battery.py 4` | per K: `errors_after < errors_before` with `good_held`, or clean short-circuit | denoise+renoise per K (~$0.10) | `K` (int/list) |
| 5 | **inject (W2/W3)** | `python3 battery.py 5` | the canvas commits `replacement` over the hallucination (`injections_held`) | denoise + inject (~$0.05) | `prompt`, `K`, `replacement`, `spec_text` |
| 6 | **unified refine** | `python3 battery.py 6` | the parse→renoise loop converges to `errors==0` within `max_iters` | denoise + N renoise (~$0.10) | `prompt`, `fn_name`, `K`, `max_iters` |
| 7 | **three-arm kill-gate (E1)** | `python3 battery.py 7` | **THE thesis verdict** — `EARNS`: arm1 (guided) beats arm3 (naked+oracle) on `faithful_rate` by ≥0.10 (eval tier live) | 3 arms × n (~$0.05–0.15) | `n` (samples/arm) |
| 8 | **skill-lift sweep** | `python3 battery.py 8` | every swept skill shows `lift > 0` (treatment − control, scored through the local oracle) | 2×N per skill × 6 skills (~$0.20) | `skills` (list), `N`, `max_new_tokens` |

Win/result/cost land in `battery_scorecard.jsonl` (append-only) and the console
table; `--all` prints a `WINS:` / `MISS:` roll-up + total est cost.

## Second-session track — needs the CO-LOCATION IMAGE (NOT in the stock battery)

`kv_reuse` (the 62%-prefill win at 9k ctx) and `inject-W1` (held-cache cross-attn
injection) both require the transformers `Cache` object to live in-process — it
**cannot ride a JSON payload** — so they need the co-location Docker image
(Dockerfile layer + spawn-wiring, [[owner-gpu-runbook]] §2/§3) and a tag bump that
recreates the worker. They are **excluded from `--all`**. Offline sanity first
(no GPU): `python3 test_kv_walk.py && python3 test_inject_apply.py &&
python3 test_kv_reuse_cpu_proxy.py` (all green). Then drive per the runbook.
`python3 battery.py --allow-image-track` prints the guard + procedure.

## Harness inventory (all gitignored under `tmp/flash-diffgemma/`)

- `battery.py` — the master runner (this doc's subject). Self-contained.
- `verify_fresh.py` — the stale-measurement gate (`worker_sha == local_sha`).
- `gpu_worker.py` + `diffgemma_common.py` — the worker (modes: probe/introspect/
  generate/clamp_smoke/infill/denoise_to_step/resume_renoise/inject/kv_reuse).
- `e1_kill_gate.py` + `e1_mock.py` + `e1_mock_test.py` — the three-arm gate + its
  offline proof (the eval tier via `out/worker-oracle-eval/main.js --serve`).
- `closed_loop.py`, `skill_lift.py`, `score_ab.py`, `compile_test.py` — the
  standalone single-experiment drivers the battery's experiments mirror.
- `span_token_align_check.py` — tokenizer-only (`.venv`) span↔BPE boundary check.
- `test_inject_apply.py`, `test_kv_walk.py`, `test_kv_reuse_cpu_proxy.py` — the
  off-GPU pure-unit proofs.

## Entry points (depth)

- [[owner-gpu-runbook]] — the per-step grounding (find_spec root cause, KV-cache
  contract, span alignment, the kill-gate decision rule).
- [[north-star]] — the OWNER HANDOFF (PROVEN / BUILT / AWAITS) + the measured-lift ledger.
- [[roadmap]] — the kill-gate-first sequence (P0–P5).
