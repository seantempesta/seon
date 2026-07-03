"""Run ESTABLISHED inspect_evals benchmarks against Seon clusters.

Owner directive (2026-07-02): "test useful behaviours with general tests like
with inspect-ai capabilities, not bullshit things we made up." So the standard
path is: take a real `inspect_evals` Task (its dataset + its host-side scorer,
unchanged) and SUBSTITUTE our pod solver for the task's own `generate()`
solver — inspect's `eval(task, solver=...)` overrides the solver while keeping
the task's dataset and scorer (verified against the vendored inspect-ai
source, `_eval/eval.py:121` — `solver` param is an override).

Two run modes:
- static URL (`cluster_url` argument or SEON_CLUSTER_URL): every sample drives
  the SAME long-lived cluster's pod (e.g. acme). Nothing is acme-specific —
  any pod that mounts `POST /agents/run` works.
- `per_sample_cluster=True`: one EPHEMERAL cluster per sample (create → drive
  → destroy via `bin/seon cluster create|destroy`) — true isolation by
  construction, at ~config.CLUSTER_BOOT_BUDGET_S boot cost per sample.

CASE-1 only (this module): benches that need `input text -> final answer` with
a host-side scorer and NO inspect sandbox/tool-bridge. Code-execution benches
(HumanEval/MBPP run the model's code in a sandbox) and web-tool benches (GAIA)
are the CASE-2 / mvm tier — deferred, not faked.
"""

from __future__ import annotations

import importlib
from typing import Any, Callable, Sequence

from inspect_ai import Task, eval as inspect_eval
from inspect_ai._util.registry import registry_log_name
from inspect_ai.solver import Solver

from seon_inspect import config
from seon_inspect.solver import seon_cluster_solver, seon_pod_solver


# The case-1 catalog we have assessed as viable through the pod door (see
# README "Standard benchmarks"). Value = (module, task-attr). Kept as data so
# adding a bench is a one-line edit, not new code.
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
            "case-2/mvm tier — not runnable through the pod door."
        )
    mod_name, attr = CASE1_BENCHES[name]
    task_fn: Callable[..., Task] = getattr(importlib.import_module(mod_name), attr)
    return task_fn(**task_kwargs)


def swap_generate(task_solver: Solver | Sequence[Solver],
                  pod_solver: Solver) -> list[Solver]:
    """The task's own solver chain with `generate()` swapped for the pod.

    A bench's answer-format contract (e.g. gsm8k's "ANSWER: $ANSWER"
    prompt_template) lives in its SOLVER CHAIN, not its dataset — replacing
    the whole chain silently drops the contract and the bench then measures
    prompt-omission, not capability (first dev pass, 2026-07-03: correct
    conversational replies scored INCORRECT because match() never saw a
    templated answer). So: keep every non-generate step (templates, system
    messages, fewshot), replace each `generate` with the pod solver, and
    append the pod solver if the chain had no generate at all."""
    steps = (list(task_solver) if isinstance(task_solver, Sequence)
             else [task_solver])
    out: list[Solver] = []
    swapped = False
    for step in steps:
        try:
            name = registry_log_name(step)
        except Exception:
            name = ""
        if name == "generate":
            out.append(pod_solver)
            swapped = True
        else:
            out.append(step)
    if not swapped:
        out.append(pod_solver)
    return out


def run_bench(
    name: str,
    *,
    cluster_url: str | None = None,
    per_sample_cluster: bool = False,
    limit: int | None = 5,
    epochs: int = 1,
    run_timeout_s: int | None = None,
    max_samples: int | None = None,
    task_kwargs: dict[str, Any] | None = None,
    task: Task | None = None,
    **eval_kwargs: Any,
):
    """Run a standard bench with a Seon cluster's pod agent as the solver.

    `cluster_url` (or SEON_CLUSTER_URL) selects the long-lived cluster's pod
    door — cluster-agnostic; acme is just one value. `per_sample_cluster=True`
    switches to one ephemeral cluster per sample instead (mutually exclusive
    with `cluster_url`). `limit` keeps baselines cheap; `epochs` enables
    pass^k. Timeouts/concurrency default from `seon_inspect.config`
    (calibration-derived) and are overridable here per-run — but `max_samples`
    above `config.POD_MAX_SAMPLES` (1) against a SINGLE cluster corrupts
    samples (one pod = one cluster = one sample at a time); raise it only when
    the URL fronts a pool. The bench's OWN scorer grades the replies
    host-side. Returns inspect's eval logs. `--model` is required by inspect
    but never called (the solver bypasses it) — pass model="mockllm/model".

    `task` lets a caller pass a PREBUILT catalog Task (e.g. `freeze.run_split`,
    which injects canary metadata into the blind tier) instead of constructing
    one from `task_kwargs` — same dataset+scorer either way.
    """
    if per_sample_cluster and cluster_url:
        raise ValueError(
            "per_sample_cluster=True mints its own clusters — "
            "cluster_url selects a long-lived one; pass one or the other")
    if task is None:
        task = load_bench_task(name, **(task_kwargs or {}))
    if per_sample_cluster:
        the_solver = seon_cluster_solver(timeout_s=run_timeout_s)
    else:
        the_solver = seon_pod_solver(cluster_url=cluster_url,
                                     timeout_s=run_timeout_s)
    return inspect_eval(
        task,
        solver=swap_generate(task.solver, the_solver),
        model=eval_kwargs.pop("model", "mockllm/model"),
        limit=limit,
        epochs=epochs,
        max_samples=max_samples or config.POD_MAX_SAMPLES,
        **eval_kwargs,
    )
