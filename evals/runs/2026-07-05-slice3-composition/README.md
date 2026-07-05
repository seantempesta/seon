---
type: research
status: completed
tags: [research, agent]
---

# Slice 3 — the composition: Seon layered into ONE SWE-bench instance (2026-07-05)

A-overlay composition per the design
(`docs/prds/agent-ctx/research/result-driven-benchmark-suite-design-2026-07-05.md`
§2b/§2d/§8-slice-3): the canonical image's `/opt/seon` tree extracted at its
pinned digest into a named volume, mounted read-only into the UNMODIFIED
official epoch instance image, the cluster booted INSIDE, the ROOT agent
driven through `POST /agents/run`, the OFFICIAL scorer untouched. First
honest `swe_bench_verified` ledger row landed (INCORRECT — recorded as-is).

Instance used throughout: **sympy__sympy-22914** (same instance for null-run
and agent run).

## Pins (also in `evals/datasets.lock` → `image_pins`, lock sha `caa74492…`)

- seon runtime: `seon:slice1`
  `sha256:63db32776190f88411542a1415a6eb44bdb17c6b809f2d1fdab39b6a2c0a0557`
  → volume `seon-runtime-slice3` (733 MB, `cp -a /opt/seon/.`)
- instance image: `ghcr.io/epoch-research/swe-bench.eval.arm64.sympy__sympy-22914:latest`
  `sha256:ca24e98226cb7434dde2b9381401d5dff9439854eb5950dac54d567887db95ef`
- full record: `digests.txt`

## §8 slice-3 acceptance criteria — observed evidence

- **(a) Null-run: overlay provably doesn't perturb the oracle — PASS.**
  Two no-op-solver runs on the same instance, official scorer both times:
  vanilla compose vs vanilla + the two mounts (`/opt/seon` RO +
  anonymous `/seon-data`; `sleep infinity` and `network_mode: none` kept,
  NO Seon boot). Verdicts, score explanations (17 PASS_TO_PASS pass /
  1 FAIL_TO_PASS fail), and empty model patches all byte-identical —
  `null-run-comparison.json` (`verdict_identical`,
  `explanation_identical`, `patch_identical` all true). Logs:
  `logs/*swe-bench-null*.eval`.
- **(b) Agent's shell verbs executed natively in /testbed — PASS.**
  The official scorer's own in-container `git diff` (score metadata
  `model_patch`, in `seon-arm-result.json`) shows a real edit to
  `/testbed/sympy/printing/pycode.py` made from inside the instance
  container; the solver path is `POST /agents/run` on the published pod
  port — no transport verb exists in any code path
  (`seon_inspect/swebench_arm.py` reuses `solver.pod_run` unchanged).
  In-container pod log (`seon-arm-pod-log.txt`) shows the full turn sequence.
- **(c) Official scorer produced the verdict — PASS.** `swe_bench_scorer`
  ran its eval script in the same container: verdict **INCORRECT** (0.0),
  FAIL_TO_PASS `test_PythonCodePrinter` failed
  (`logs/2026-07-05T20-03-11…swe-bench-seon….eval`, `seon-arm-result.json`).
- **(d) One honest ledger row — PASS.**
  `2026-07-05:swe_bench_verified:dev:k1:slice3-composition` in
  `evals/scorecard.jsonl` (n=1, k=1, mean 0.0, labeled dev-spike, both
  digests in the row AND in `datasets.lock` `image_pins`).

## §10 falsifier — runtime self-containment on a REAL instance image: HOLDS

Standalone proof before any inspect coupling (`boot-inside-log-head.txt`,
`boot-inside-agents-run.json`): bundled JRE 25 + Node 22.23.1 exec directly
in the instance image (ubuntu 22.04, glibc 2.35); `seon-entrypoint all`
booted wire-server (ready 4s) → pod (listening ~9s, auto-boot ready); the
root agent answered a trivial task through the door (`"391"`,
`:completed`, 10.6s, DeepSeek egress from inside the container). `/testbed`
after boot: `git status` clean at the instance's base commit.

## The agent run (honest outcome)

- Verdict: **INCORRECT** (official scorer, mean 0.0).
- Door metadata: 12 turns, 45 evals, `closed_reason :completed`,
  `timed_out false`, 171.1s in-pod, pod boot wait 13.2s, eval total 3:09.
  Model provenance runtime-derived: deepseek / deepseek-v4-pro, temp 0.7,
  thinking false.
- What happened: the agent worked natively in /testbed and produced a
  plausible-looking patch adding `_print_Min`/`_print_Max` to
  `PythonCodePrinter` — but inserted them into the MIDDLE of
  `_print_Symbol` (between its `def` line and body), so the F2P test
  failed. It concluded with a real terminal reply → attributed
  **model_miss**, NOT behavior_miss (no turn-limit/deadline cut, reply
  non-empty). One contract, one run, recorded as-is — no prompt iteration.
- Turn-memo (design §7 budget sizing starts here): 12 of the default 20
  turn-limit consumed; 171s of the 900s door budget; ctx grew 10.8k →
  ~20k tokens over the run.

## Deviations / named conditions

- **Egress asymmetry (design §2c/§10 interim):** the agent-arm container
  has unrestricted egress (the in-container pod must reach the DeepSeek
  API; the endpoint allowlist is a named, unbuilt gap). Recorded on the
  ledger row. The null-run kept `network_mode: none`.
- **`SEON_FS_ROOT=/opt/seon` read-only:** the shipped entrypoint pins the
  fs verb to the immutable runtime tree, so the agent's ONLY write surface
  in /testbed is the shell verb (`SEON_SHELL=1`). Honest for this slice
  (the overlay is used exactly as pinned); a workspace-rooted fs grant is
  a tooling-lane entrypoint follow-up.
- **Fixed per-sample port scheme** (deterministic hash into 17900-17999,
  published on 127.0.0.1) rather than docker-assigned ephemeral ports —
  fine at concurrency 1; revisit if slice 4 parallelizes.
- **`image_pins` lock seam:** `freeze.py` `build_lock` now carries an
  `image_pins` section over verbatim (like canary GUIDs); seeded with the
  two digests above. `verify_lock()` returns clean.
- The final `seon-arm-testbed-diff.txt` snapshot is EMPTY because the
  official eval script ends with `git checkout` (repo reset after
  scoring) — the authoritative in-container diff is the scorer's own
  `model_patch` in `seon-arm-result.json`.

## New harness code (src-inspect-ai — the eval lane's package)

- `src/seon_inspect/swebench_arm.py` — compose generators
  (`overlay_sandbox_config(boot=False|True)` via inspect_evals'
  `sandbox_config` seam), `noop_solver`, §3 `task_contract`,
  `seon_swebench_solver` (reuses `solver.pod_run` — no parallel door).
- `src/seon_inspect/tasks/swe_bench_seon.py` — `swe_bench_null` /
  `swe_bench_seon` task wrappers (lazy inspect_evals import; run from
  `tmp/slice2-venv` with `PYTHONPATH=src-inspect-ai/src`).
- `src/seon_inspect/scorecard.py` — `behavior_miss` added per §7: scored
  FAIL (counts in the mean), attributed distinctly; `solve_timeout` stays
  an excluded flake. `executions_from_eval_log` applies it.
- `tests/test_swebench_arm.py` — offline coverage (compose shape, port
  stamping, contract statement, behavior_miss reducer). Suite: 221 passed.

## Exact commands

```bash
# overlay volume (from the pinned image id)
docker volume create seon-runtime-slice3
docker run --rm --entrypoint sh -v seon-runtime-slice3:/dst \
  seon@sha256:63db32776190f88411542a1415a6eb44bdb17c6b809f2d1fdab39b6a2c0a0557 \
  -c 'cp -a /opt/seon/. /dst/'

# null-run pair (identical-verdict proof)
PYTHONPATH=src-inspect-ai/src tmp/slice2-venv/bin/inspect eval \
  src-inspect-ai/src/seon_inspect/tasks/swe_bench_seon.py@swe_bench_null \
  --model mockllm/model --sample-id sympy__sympy-22914 -T mounted=false
# … then -T mounted=true

# the Seon arm
PYTHONPATH=src-inspect-ai/src tmp/slice2-venv/bin/inspect eval \
  src-inspect-ai/src/seon_inspect/tasks/swe_bench_seon.py@swe_bench_seon \
  --model mockllm/model --sample-id sympy__sympy-22914
```

## Files

- `digests.txt` — image/volume pins
- `boot-inside-log-head.txt`, `boot-inside-agents-run.json` — §10 falsifier proof
- `null-run-comparison.json` — the identical-verdict proof (+ both explanations)
- `logs/*.eval` — the three Inspect logs (null ×2, seon arm)
- `seon-arm-result.json` — verdict + door metadata + full model_patch + explanation
- `seon-arm-pod-log.txt` — the in-container pod log (boot + all turns)
- `seon-arm-console.txt` — eval CLI output
- `seon-arm-testbed-diff.txt` — post-scoring snapshot (empty; see Deviations)
