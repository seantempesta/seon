"""Run ESTABLISHED inspect_evals benchmarks against a Seon pod.

Owner directive (2026-07-02): "test useful behaviours with general tests like
with inspect-ai capabilities, not bullshit things we made up." So the standard
path is: take a real `inspect_evals` Task (its dataset + its host-side scorer,
unchanged) and SUBSTITUTE our `seon_pod_solver` for the task's own
`generate()` solver — inspect's `eval(task, solver=...)` overrides the solver
while keeping the task's dataset and scorer (verified against the vendored
inspect-ai source, `_eval/eval.py:121` — `solver` param is an override).

Pod-agnostic by construction: the pod endpoint is `SEON_SOLVE_URL` (env) or the
`solve_url` argument here — NOTHING is acme-specific. Acme is only ever a
worked example in the README; any pod that mounts `POST /solve` works.

CASE-1 only (this module): benches that need `input text -> final answer` with
a host-side scorer and NO inspect sandbox/tool-bridge. Code-execution benches
(HumanEval/MBPP run the model's code in a sandbox) and web-tool benches (GAIA)
are the CASE-2 / mvm tier — deferred, not faked.
"""

from __future__ import annotations

import importlib
from typing import Any, Callable

from inspect_ai import Task, eval as inspect_eval

from seon_inspect import config
from seon_inspect.solver import seon_pod_solver


# The case-1 catalog we have assessed as viable through /solve (see README
# "Standard benchmarks"). Value = (module, task-attr). Kept as data so adding a
# bench is a one-line edit, not new code.
CASE1_BENCHES: dict[str, tuple[str, str]] = {
    "gsm8k": ("inspect_evals.gsm8k", "gsm8k"),
    "arc_easy": ("inspect_evals.arc", "arc_easy"),
    "arc_challenge": ("inspect_evals.arc", "arc_challenge"),
    "mmlu_0_shot": ("inspect_evals.mmlu", "mmlu_0_shot"),
    "commonsense_qa": ("inspect_evals.commonsense_qa", "commonsense_qa"),
    "truthfulqa": ("inspect_evals.truthfulqa", "truthfulqa"),
    "gpqa_diamond": ("inspect_evals.gpqa", "gpqa_diamond"),
}


def load_bench_task(name: str, **task_kwargs: Any) -> Task:
    """Load a standard inspect_evals Task by catalog name (its own dataset+scorer)."""
    if name not in CASE1_BENCHES:
        raise KeyError(
            f"{name!r} not in the assessed case-1 catalog {sorted(CASE1_BENCHES)}; "
            "code-exec (humaneval/mbpp) + web-tool (gaia) benches are the "
            "case-2/mvm tier — not runnable through /solve."
        )
    mod_name, attr = CASE1_BENCHES[name]
    task_fn: Callable[..., Task] = getattr(importlib.import_module(mod_name), attr)
    return task_fn(**task_kwargs)


def run_bench(
    name: str,
    *,
    solve_url: str | None = None,
    limit: int | None = 5,
    epochs: int = 1,
    solve_timeout_s: int | None = None,
    max_samples: int | None = None,
    task_kwargs: dict[str, Any] | None = None,
    task: Task | None = None,
    **eval_kwargs: Any,
):
    """Run a standard bench with the Seon pod as the solver.

    `solve_url` (or SEON_SOLVE_URL) selects the pod — pod-agnostic; acme is
    just one value. `limit` keeps baselines cheap; `epochs` enables pass^k.
    Timeouts/concurrency default from `seon_inspect.config` (calibration-
    derived) and are overridable here per-run — but `max_samples` above
    `config.POD_MAX_SAMPLES` (1) against a SINGLE pod corrupts samples
    (solve-once! swaps the root conn); raise it only when the URL fronts a
    pool of pods. The bench's OWN scorer grades the pod's replies host-side.
    Returns inspect's eval logs. `--model` is required by inspect but never
    called (the solver bypasses it) — pass model="mockllm/model".

    `task` lets a caller pass a PREBUILT catalog Task (e.g. `freeze.run_split`,
    which injects canary metadata into the blind tier) instead of constructing
    one from `task_kwargs` — same dataset+scorer either way.
    """
    if task is None:
        task = load_bench_task(name, **(task_kwargs or {}))
    return inspect_eval(
        task,
        solver=seon_pod_solver(solve_url=solve_url, timeout_s=solve_timeout_s),
        model=eval_kwargs.pop("model", "mockllm/model"),
        limit=limit,
        epochs=epochs,
        max_samples=max_samples or config.POD_MAX_SAMPLES,
        **eval_kwargs,
    )
