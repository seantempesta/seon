---
type: research
status: completed
tags: [research, agent]
---

# Slice 2 — zero-Seon SWE-bench de-risk (2026-07-05)

Ran inspect-evals' SWE-bench Verified UNCHANGED on 2 instances on this
arm64 Mac (docker 29.6.1), proving the docker plumbing before any Seon
coupling. Zero files touched under `src/`, `src-inspect-ai/`, `docker/`.

## Verdict: ALL FOUR ACCEPTANCE CRITERIA PASS

| Criterion | Observed evidence |
|---|---|
| (a) arm64 image pull + sandbox boot | `ghcr.io/epoch-research/swe-bench.eval.arm64.sympy__sympy-22914:latest` pulled in 18.8s and `…arm64.astropy__astropy-12907:latest` in 6.9s, both PUBLIC (no ghcr login needed, contra the README note). Both booted as Inspect docker-sandbox containers (`inspect-swe_bench-*-default-1`, observed `Up` via `docker ps`). |
| (b) solver ran, tool calls in sandbox | DeepSeek made 12 tool calls (astropy) and 9 (sympy) via the default `swe_bench_agent_with_inspect_tool_support` react agent (python/bash_session/text_editor). Real model patches produced (813 and 665 chars, in score metadata `model_patch`). |
| (c) official scorer produced a verdict | `swe_bench_scorer` ran the eval script in each sandbox; PASS_TO_PASS and FAIL_TO_PASS test lists both executed with explicit success/failure arrays in the score explanation. Verdict: **CORRECT on both** (mean 1.000). Astropy FAIL_TO_PASS: 2/2 pass; sympy FAIL_TO_PASS: `test_PythonCodePrinter` pass. |
| (d) .eval log exists + readable | `logs/2026-07-05T19-47-57-00-00_swe-bench_c5AiaKP4tSHsnBMHhPnWsM.eval` (70 KB), read back with `inspect_ai.log.read_eval_log`; status `success`, per-sample scores/messages/events intact. |

## Timings

| Phase | Wall clock |
|---|---|
| Image pull (sympy__sympy-22914) | 18.8s |
| Image pull (astropy__astropy-12907) | 6.9s |
| Whole eval (2 samples, parallel) | 68s (inspect) / 76s incl. CLI startup |
| sympy sample (solve+score) | 33.3s total (score event at t+34s) |
| astropy sample (solve+score) | 65.8s total (score event at t+67s) |

Token usage: 160,510 total (input 18,162 + cache-read 137,472, output 4,876)
— a trivially cheap DeepSeek run.

## Disk cost (left in place for slice 3)

| Image | Size |
|---|---|
| ghcr.io/epoch-research/swe-bench.eval.arm64.sympy__sympy-22914:latest | 2.85 GB |
| ghcr.io/epoch-research/swe-bench.eval.arm64.astropy__astropy-12907:latest | 2.91 GB |
| tmp/slice2-venv (python env) | 432 MB |

Host had 1.2 TB free before the run; budget ~3 GB per additional instance
image if slice 3 widens the instance set.

## Exact configuration

- Command: `tmp/slice2-venv/bin/inspect eval inspect_evals/swe_bench
  --model openai-api/deepseek/deepseek-chat
  --sample-id sympy__sympy-22914,astropy__astropy-12907 --no-ansi`
  (message_limit at the task default 30, 1 epoch, sandbox `docker`,
  default epoch-research image template, dataset revision pinned by the
  task: `c104f840…`).
- Model wiring: inspect's `openai-api/<service>/<model>` provider —
  `DEEPSEEK_API_KEY` from host env + `DEEPSEEK_BASE_URL=https://api.deepseek.com/v1`.
- Env: fresh `tmp/slice2-venv/` (uv, Python 3.12.13), **vendored
  inspect-evals installed editable** from `reference-code/inspect-evals`
  (commit `ce900d63`) with the `[swe_bench]` extra. Versions in
  `versions.txt` (inspect-ai 0.3.244, swebench 4.1.0, datasets 5.0.0,
  openai 2.44.0). `src-inspect-ai/`'s pinned env untouched.

## Surprises + resolutions

1. **No ghcr auth needed.** The vendored README and swe_bench.py comment
   (:43-44) say ghcr.io authentication is required; the epoch-research
   images pulled anonymously. Nothing to resolve — noted so slice 3
   doesn't set up credentials it doesn't need.
2. **`openai` pip package not pulled in by the `[swe_bench]` extra** —
   the `openai-api` provider validates the OpenAI client at startup.
   Fixed by `uv pip install openai` into the venv.
3. **Both instances solved (mean 1.000) in 68s** — faster and better
   than expected for a 30-message limit. Verified it is NOT a scorer
   artifact: the score explanations contain the executed PASS_TO_PASS /
   FAIL_TO_PASS test lists with per-test results, and the model patches
   are real, plausible diffs (astropy `_cstack` cright fix; sympy
   `_print_Min`/`_print_Max`). These are two well-known easy instances;
   expect much longer wall times on harder ones.
4. **No preflight blockers.** Disk 1.2 TB free; HF dataset
   (princeton-nlp/SWE-bench_Verified @ pinned revision, 500 rows) loads
   anonymously.

## Leftover artifacts

- Instance images kept (slice 3 reuses them) — 5.76 GB total.
- Inspect compose files in `~/Library/Caches/inspect_evals/swe_bench/compose_files/`.
- Sandbox containers were auto-removed by Inspect after the run
  (`docker ps` clean; `seon:slice1` container untouched).
- `reference-code/inspect-evals` submodule left pristine (uv's editable
  install writes no in-tree egg-info).

## Files

- `logs/2026-07-05T19-47-57-00-00_swe-bench_c5AiaKP4tSHsnBMHhPnWsM.eval` — the full Inspect log
- `run-console.txt` — CLI output (results panel)
- `versions.txt` — exact package versions + vendored commit
- `run-start-epoch.txt` / `run-end-epoch.txt` — wall-clock bounds
