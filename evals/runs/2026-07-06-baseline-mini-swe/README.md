---
type: research
status: completed
tags: [research, agent]
---

# Baseline arm — mini-swe-agent on SWE-bench Verified (frozen n=10 dev slice)

Unit C of the eval-lane handoff. The reference scaffold the Seon arm is
measured against: **mini-swe-agent** (the SWE-bench team's own minimal
bash-only agent) over the SAME frozen 10 SWE-bench Verified instances, the
SAME pinned arm64 epoch images, the SAME model, scored by the SAME official
FAIL_TO_PASS/PASS_TO_PASS oracle. It does not depend on Seon's tool surface,
so it can run now and never goes stale.

## Result

**7 / 10 resolved — mean 0.700** (k=1). 0 flakes, 0 apply failures, 0 harness
errors. All 3 misses are clean `model_miss` (patch applied cleanly, the
instance's targeted tests failed).

| instance_id                      | resolved | steps | patch_len | score_s |
|----------------------------------|:--------:|------:|----------:|--------:|
| sympy__sympy-20916               |    ✓     |    41 |       424 |     3.2 |
| sphinx-doc__sphinx-10614         |    ✗     |    78 |      1774 |     4.1 |
| django__django-11299             |    ✓     |    33 |       668 |     7.0 |
| pallets__flask-5014              |    ✓     |    16 |       435 |     4.2 |
| mwaskom__seaborn-3187            |    ✗     |    89 |      1130 |    32.0 |
| psf__requests-1921               |    ✓     |    23 |       834 |    17.1 |
| pytest-dev__pytest-7490          |    ✓     |    54 |       996 |     7.6 |
| pylint-dev__pylint-4604          |    ✗     |    48 |       860 |     2.6 |
| sympy__sympy-15875               |    ✓     |    36 |       404 |     7.2 |
| sphinx-doc__sphinx-8269          |    ✓     |    16 |       629 |     3.7 |

`steps` = assistant turns in the mini-swe trajectory (none hit the step_limit
250; all exited `Submitted`). `score_s` = per-instance official-grading wall
(targeted-test run only — SWE-bench runs just the F2P/P2P tests, not the whole
suite, hence seconds).

## Ledger row

`evals/scorecard.jsonl`, run_id
`2026-07-06:swe_bench_verified:dev:k1:baseline-mini-swe`, row
`swe_bench_verified`, `attribution.arm = "baseline-mini-swe"`.

The Seon arm carries `attribution.arm = "seon-overlay"` (slice-3 spike row is
the only `swe_bench_verified` entry so far). The pass^k regression alarm keys
by `(row, arm)` (`scorecard.regression_failures`), so the distinct arm isolates
this baseline from the Seon arm's history — both remain first-entries, neither
trips. **Seon-vs-baseline join = row `swe_bench_verified` + `attribution.arm`.**
Alarm + well-formed tests: green.

## Model (matched to the Seon arm)

Both arms send model string **`deepseek-v4-pro`** to `api.deepseek.com/v1`
(OpenAI-compatible), temperature **0.7**, max_tokens **4096**, thinking
**disabled** via the exact `{"thinking":{"type":"disabled"}}` struct the pod
sends (`src/seon/ai/openai_compat.cljs:216`; the shipped default is
`deepseek-v4-pro`, `src/seon/ai.cljs:225`).

> **Correction to the handoff:** Unit C's spec said `deepseek-chat`. That is a
> stale label — the pod does NOT send `deepseek-chat`; it sends
> `deepseek-v4-pro`. Verified live: `deepseek-chat` currently serves
> `deepseek-v4-flash` (a *different, cheaper tier*), while `deepseek-v4-pro`
> serves `deepseek-v4-pro`. Using `deepseek-chat` would have confounded the
> scaffold delta with a model-tier delta. This baseline sends the pod's actual
> wire model to preserve scaffold-vs-scaffold validity.

The API key is read from the environment (`OPENAI_API_KEY` set to
`$DEEPSEEK_API_KEY` at launch) — never written to any config/evidence file.

## How it was run (reproduce)

Predictions — mini-swe-agent's own batch mode:

```bash
export MSWEA_SILENT_STARTUP=1 OPENAI_API_KEY="$DEEPSEEK_API_KEY"
BUILTIN=tmp/mini-swe-venv/lib/python3.14/site-packages/minisweagent/config/benchmarks/swebench.yaml
tmp/mini-swe-venv/bin/mini-extra swebench \
  --subset verified --split test \
  --filter '^(sympy__sympy-20916|sphinx-doc__sphinx-10614|django__django-11299|pallets__flask-5014|mwaskom__seaborn-3187|psf__requests-1921|pytest-dev__pytest-7490|pylint-dev__pylint-4604|sympy__sympy-15875|sphinx-doc__sphinx-8269)$' \
  -o preds -w 3 -c "$BUILTIN" -c mini-swe-deepseek.yaml
```

Scoring — the OFFICIAL swebench oracle (`score_official.py`):

```bash
DOCKER_CONFIG=tmp/docker-clean-config tmp/slice2-venv/bin/python score_official.py
```

### Image resolution (arm64)

The 10 pinned epoch arm64 images (`ghcr.io/epoch-research/swe-bench.eval.arm64.<id>`)
were retagged to the names each tool expects — no pulls, no rebuilds:

- **mini-swe** default name `docker.io/swebench/sweb.eval.x86_64.<id _1776_>:latest`
  (mini-swe passes no `--platform`, so the arm64 image runs natively).
- **swebench scorer** name (namespace=`swebench`): same
  `swebench/sweb.eval.x86_64.<id _1776_>:latest` — reused, not rebuilt.

### Why the scorer is orchestrated, not `run_evaluation` verbatim

swebench 4.1.0's `run_evaluation` hardcodes `arch="x86_64"` and passes
`--platform linux/x86_64` to `containers.create`, which the daemon **rejects**
for the arm64 epoch images (`does not provide the specified platform
(linux/amd64)`); with `--namespace none` it instead tries to BUILD base images
from scratch (and hit a broken `docker-credential-gcloud` helper). So
`score_official.py` reproduces `run_instance`'s EXACT official sequence —
swebench's own `make_test_spec` eval script + the `GIT_APPLY_CMDS` apply ladder
+ the `swebench.harness.grading.get_eval_report` FAIL_TO_PASS/PASS_TO_PASS
grader — but drives the arm64 container via the docker CLI with no platform
flag (exactly as mini-swe-agent itself does). **The verdict is produced by
swebench's unmodified grader; only container orchestration differs** — the same
mechanism inspect_evals uses on this host (proven slices 2/3).

## Cost

- **Wall:** predictions ~11 min (18:11→18:22, workers=3); official scoring
  ~2 min (targeted tests only). Total end-to-end ~20 min.
- **Disk:** 0 new — all 10 epoch arm64 images were already present
  (`pull-stats.txt`); only docker tags added (aliases, no layer duplication).
- **Model:** 10 instances × 16–89 steps × deepseek-v4-pro (cheap $). One run
  per instance, no retries (a mini-swe miss is baseline data).

## Files

- `preds/preds.json` — SWE-bench-format predictions (10 non-empty patches).
- `preds/<id>/<id>.traj.json` — full mini-swe trajectories.
- `mini-swe-deepseek.yaml` — the model override config used.
- `score_official.py` — the arm64 official-grading runner (one-shot evidence).
- `eval-report/arm64-official-results.json` — per-instance verdicts + summary.
- `eval-report/arm64-official/<id>/{report.json,test_output.txt,eval.sh,apply.log}`
  — the official grader's per-instance report + raw test output.
- `per-instance-table.json` — the table above as data.
- `run-wall.txt` — phase timestamps.
